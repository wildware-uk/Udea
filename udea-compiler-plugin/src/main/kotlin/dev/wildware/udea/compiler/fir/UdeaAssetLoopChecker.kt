package dev.wildware.udea.compiler.fir

import dev.wildware.udea.diagnostics.UdeaRules
import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.contracts.description.EventOccurrencesRange
import org.jetbrains.kotlin.contracts.description.KtCallsEffectDeclaration
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirDoWhileLoop
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLoop
import org.jetbrains.kotlin.fir.expressions.resolvedArgumentMapping
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.realElement

/**
 * No loops in an asset script (issue #192), decided by what a call **resolves to**.
 *
 * The editor's Save writes an exact value back to the line of the `.udea.kts` it came from. A
 * value made inside a loop has no single line, so an asset script may not loop, and
 * `UdeaRules.LOOP_IN_ASSET` is the rule. The asset compiler's syntactic first pass already
 * refuses loop keywords and the names `repeat` is reachable by, but it matches spelling:
 * `import kotlin.repeat as times`, `(1..3).forEach { }` or a helper the script declares itself
 * are all loops no name list can name. This checker sees the resolved symbol instead, so the
 * spelling does not matter. It refuses three shapes:
 *
 * - **a loop node** - `for`, `while`, `do`-`while` - at its keyword;
 * - **a lambda handed to a callee that may run it more than once**, at the call. A callee is
 *   trusted with a function-typed argument only when it declares
 *   `callsInPlace(<that parameter>, EXACTLY_ONCE | AT_MOST_ONCE)`, as `let`, `apply`, `run`,
 *   `also`, `with` and `takeIf` do, or when it carries `@AssetDsl`, the asset DSL's promise
 *   that it runs each lambda once. Everything else - `repeat`, `forEach`, `map`, `lazy`, a
 *   script's own `fun twice(body: () -> Unit)` - is refused, because nothing says it will not
 *   call the lambda again;
 * - **a function calling itself**, at the recursive call. Recursion is a loop with no keyword
 *   and no lambda.
 *
 * ### Where it runs
 *
 * Only in a file named `*.udea.kts`, which in practice means inside the asset compiler:
 * `AssetCompiler` passes this plugin to its K2 script compile. Every other compilation the
 * plugin reaches is ordinary game and engine source, where loops are the point, and this checker
 * returns before looking at anything.
 *
 * ### What it does not see
 *
 * Mutual recursion (`a` calls `b` calls `a`) is not refused: only a call to a function that
 * lexically contains it is. A lambda stored in a `val` and called once is fine and is not
 * refused; the same lambda called twice by the script itself is two calls, which is two values
 * on two lines, which the editor can save.
 */
internal object UdeaAssetLoopChecker {

    /** The asset script extension; kept here as a literal because this module does not see the asset compiler. */
    private const val SCRIPT_SUFFIX: String = ".udea.kts"

    private const val WHY: String = "Assets may not contain loops: the editor saves an exact value back " +
        "to the line it came from, and a value a loop produces has no single line. Write each " +
        "declaration out, or keep a level's units in a `.udealevel` file."

    context(context: CheckerContext)
    private fun inAssetScript(): Boolean = context.containingFile?.name?.endsWith(SCRIPT_SUFFIX) == true

    context(context: CheckerContext, reporter: DiagnosticReporter)
    private fun report(source: KtSourceElement?, detail: String) {
        UdeaDiagnostics.report(UdeaRules.LOOP_IN_ASSET, source?.realElement(), "$detail $WHY")
    }

    /** `for`, `while` and `do`-`while`. A `for` reaches FIR as a `while` desugared from it. */
    object Loops : FirExpressionChecker<FirLoop>(MppCheckerKind.Common) {

        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(expression: FirLoop) {
            if (!inAssetScript()) return
            val keyword = when {
                expression is FirDoWhileLoop -> "do-while"
                expression.source?.kind == KtFakeSourceElementKind.DesugaredForLoop -> "for"
                else -> "while"
            }
            report(expression.source, "`$keyword` loop in an asset script.")
        }
    }

    /** A lambda handed to a callee that may run it again, and a function calling itself. */
    object Calls : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {

        context(context: CheckerContext, reporter: DiagnosticReporter)
        override fun check(expression: FirFunctionCall) {
            if (!inAssetScript()) return
            val callee = expression.calleeReference.toResolvedCallableSymbol() as? FirFunctionSymbol<*> ?: return
            val name = callee.name.asString()

            if (context.containingDeclarations.any { it == callee }) {
                report(expression.source, "`$name` calls itself, which is a loop in an asset script.")
                return
            }
            if (callee.hasAnnotation(UdeaAnnotations.ASSET_DSL, context.session)) return

            val mapping = expression.resolvedArgumentMapping ?: return
            val runsOnce = parametersRunAtMostOnce(callee)
            val repeatable = mapping.values.any { parameter ->
                parameter.returnTypeRef.coneType.isSomeFunctionType(context.session) &&
                    callee.valueParameterSymbols.indexOf(parameter.symbol) !in runsOnce
            }
            if (repeatable) {
                report(
                    expression.source,
                    "`$name` takes a lambda it may run more than once, which is a loop in an asset script.",
                )
            }
        }

        /**
         * The indices of [callee]'s value parameters its contract says are called at most once.
         *
         * A contract index of -1 names the receiver, which is not a value parameter and so is
         * never in the result.
         */
        private fun parametersRunAtMostOnce(callee: FirFunctionSymbol<*>): Set<Int> {
            val effects = callee.resolvedContractDescription?.effects ?: return emptySet()
            return effects.mapNotNullTo(HashSet()) { declaration ->
                val calls = declaration.effect as? KtCallsEffectDeclaration<*, *> ?: return@mapNotNullTo null
                calls.valueParameterReference.parameterIndex.takeIf { calls.kind in ONCE_AT_MOST }
            }
        }

        private val ONCE_AT_MOST: Set<EventOccurrencesRange> =
            setOf(EventOccurrencesRange.EXACTLY_ONCE, EventOccurrencesRange.AT_MOST_ONCE, EventOccurrencesRange.ZERO)
    }
}
