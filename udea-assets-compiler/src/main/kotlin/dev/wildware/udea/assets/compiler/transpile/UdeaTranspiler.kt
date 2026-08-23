package dev.wildware.udea.assets.compiler.transpile

import dev.wildware.udea.assets.compiler.AssetCompilerRules
import dev.wildware.udea.assets.compiler.AssetScope
import dev.wildware.udea.assets.compiler.scan.KtParser
import dev.wildware.udea.assets.compiler.scan.LineIndex
import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import dev.wildware.udea.diagnostics.SourceSpan
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.KtTypeAlias
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

/** One `.udea.kts` rewritten as a plain Kotlin class, or the reason it could not be. */
public data class TranspileResult(
    /** Repo-relative path of the script. */
    public val source: String,
    /** Fully qualified name of the emitted class. */
    public val className: String,
    /** The `.kt` source, or null when [diagnostics] holds an error. */
    public val code: String?,
    public val diagnostics: List<UdeaDiagnostic> = emptyList(),
) {
    public val simpleName: String get() = className.substringAfterLast('.')
}

/**
 * The escape hatch (issue #87): a `.udea.kts` rewritten as `fun build(scope: AssetScope)`.
 *
 * Spec 7's risk table names `kotlin-compiler-embeddable` in a Gradle worker as the highest-risk
 * remaining component, and its mitigation is explicit: build the transpile-to-plain-`.kt` path
 * **during** the work rather than after a crisis. The reason it is cheap to build now and
 * expensive later is structural — a `.udea.kts` is already, almost exactly, the body of a
 * function with `AssetScope` as its receiver. So the rewrite is: hoist the imports, wrap the
 * body, and qualify the implicit-receiver calls.
 *
 * Everything downstream is untouched. Both front ends produce an
 * [dev.wildware.udea.assets.compiler.AssetGraph], and `TranspilerParityTest` asserts the two
 * graphs are the same graph.
 *
 * ### It refuses rather than guesses
 *
 * A transpiler that emitted *nearly* right code would be worse than none: the output compiles,
 * the game runs, and an asset is subtly wrong. So every construct that does not have a
 * faithful rewrite raises [AssetCompilerRules.TRANSPILE_UNSUPPORTED] with a span and the file
 * produces no code at all. See [checkSupported] for the current list, and the report for the
 * fidelity gaps that follow from it.
 *
 * ### Status
 *
 * A prototype behind [ScriptMode]. It is not the production path and does not become one until
 * the risk it insures against actually fires.
 */
public class UdeaTranspiler(
    repoRoot: Path,
    assetRoot: Path,
    private val parser: KtParser = KtParser.shared,
    /** Package of the emitted classes. */
    private val packageName: String = DEFAULT_PACKAGE,
) {
    private val repoRoot: Path = repoRoot.toAbsolutePath().normalize()
    private val assetRoot: Path = assetRoot.toAbsolutePath().normalize()

    /**
     * Transpiles every file, and additionally reports a class-name collision across the batch.
     *
     * Two scripts whose paths differ only in punctuation (`a_b/c` and `a/b_c`) reduce to one
     * class name. Silently, that is one `AssetSource` overwriting the other on disk and half
     * the assets vanishing.
     */
    public fun transpileAll(files: List<Path>): List<TranspileResult> {
        val results = files.map(::transpile)
        val duplicates = results.groupBy { it.className }.filterValues { it.size > 1 }
        if (duplicates.isEmpty()) return results
        return results.map { result ->
            val clash = duplicates[result.className] ?: return@map result
            result.copy(
                code = null,
                diagnostics = result.diagnostics + AssetCompilerRules.TRANSPILE_UNSUPPORTED.diagnostic(
                    message = "${clash.size} scripts transpile to the class `${result.className}`: " +
                        clash.joinToString { it.source } + ". Rename one of them.",
                    span = SourceSpan(result.source, 1, 1, 1, 1),
                ),
            )
        }
    }

    /** Transpiles one file. */
    public fun transpile(file: Path): TranspileResult {
        val absolute = file.toAbsolutePath().normalize()
        val relative = SourceSpan.relativize(repoRoot.toString(), absolute.toString())
        val text = UdeaDeclarationScanner.normalizeLineEndings(file.readText())
        val ktFile = parser.parse(file.name, text)
        val lines = LineIndex(text)
        val className = "$packageName.${classNameFor(absolute)}"

        val unsupported = checkSupported(ktFile, relative, lines)
        if (unsupported.isNotEmpty()) {
            return TranspileResult(relative, className, code = null, diagnostics = unsupported)
        }

        val script = ktFile.script
            ?: return TranspileResult(
                relative,
                className,
                code = null,
                diagnostics = listOf(
                    AssetCompilerRules.TRANSPILE_UNSUPPORTED.diagnostic(
                        message = "${file.name} did not parse as a script",
                        span = SourceSpan(relative, 1, 1, 1, 1),
                    ),
                ),
            )

        val body = script.blockExpression.textRange
        val qualified = qualifyScopeCalls(ktFile, text, body.startOffset, body.endOffset)
        val imports = ktFile.importList?.imports.orEmpty().map { it.text.trim() }

        return TranspileResult(
            source = relative,
            className = className,
            code = render(relative, className.substringAfterLast('.'), absolute, imports, qualified),
        )
    }

    /**
     * Rewrites `spriteSheet(...)` into `scope.spriteSheet(...)` inside the script body.
     *
     * A text edit driven by PSI offsets rather than a PSI-to-PSI rewrite: the goal is output a
     * human can diff against the original, and reprinting a PSI tree loses every comment and
     * every formatting decision the author made. Edits are applied back to front so earlier
     * offsets stay valid.
     *
     * A call that is already the selector of a qualified expression (`something.reference(x)`)
     * is left alone: it is a call on a different receiver that merely shares a name.
     */
    private fun qualifyScopeCalls(ktFile: KtFile, text: String, from: Int, to: Int): String {
        val insertions = PsiTreeUtil.collectElementsOfType(ktFile, KtCallExpression::class.java)
            .asSequence()
            .filter { it.textRange.startOffset in from until to }
            .filter { call ->
                val callee = call.calleeExpression as? KtNameReferenceExpression
                callee != null && callee.getReferencedName() in AssetScope.MEMBER_NAMES
            }
            .filterNot { call ->
                val parent = call.parent
                parent is KtDotQualifiedExpression && parent.selectorExpression === call
            }
            .map { it.textRange.startOffset }
            .distinct()
            .sortedDescending()
            .toList()

        val builder = StringBuilder(text.substring(from, to))
        for (offset in insertions) builder.insert(offset - from, "scope.")
        return builder.toString()
    }

    /**
     * The constructs with no faithful rewrite.
     *
     * Each is here because a script body becomes a *function body*, and a function body is a
     * narrower place than a script:
     *
     * - `object Foo { }` — a named object declaration is not allowed inside a function.
     * - `typealias` — likewise not allowed locally.
     * - a `@file:` annotation — these are script-host directives (`@file:DependsOn` and
     *   friends) whose whole meaning is "the host should do something before compiling", and
     *   there is no host in the transpiled path.
     * - `return` — in a script it ends the script; in `build(scope)` it would return from
     *   `build`, which is *almost* the same thing and would quietly differ the moment the
     *   emitted function grew anything after the body.
     * - `this` — the implicit receiver is the script instance in one path and `scope` in the
     *   other. Rewriting it would be guessing which the author meant.
     * - a bare read of an `AssetScope` *property* — [AssetScope.PROPERTY_NAMES]. Calls are
     *   qualified by rewriting the callee; a property read has no callee to rewrite.
     */
    private fun checkSupported(ktFile: KtFile, path: String, lines: LineIndex): List<UdeaDiagnostic> {
        val found = mutableListOf<UdeaDiagnostic>()

        fun report(element: PsiElement, what: String) {
            val range = element.textRange
            found += AssetCompilerRules.TRANSPILE_UNSUPPORTED.diagnostic(
                message = "$what has no faithful equivalent inside `fun build(scope: AssetScope)`, " +
                    "so this script cannot be transpiled. Use ScriptMode.Script for it, or " +
                    "rewrite it without $what.",
                span = SourceSpan(
                    path,
                    lines.lineOf(range.startOffset),
                    lines.columnOf(range.startOffset),
                    lines.lineOf(range.endOffset),
                    lines.columnOf(range.endOffset),
                ),
            )
        }

        ktFile.fileAnnotationList?.let { report(it, "a `@file:` annotation") }
        PsiTreeUtil.collectElementsOfType(ktFile, KtObjectDeclaration::class.java)
            .filterNot { it.isObjectLiteral() }
            .forEach { report(it, "an `object` declaration") }
        PsiTreeUtil.collectElementsOfType(ktFile, KtTypeAlias::class.java)
            .forEach { report(it, "a `typealias`") }
        PsiTreeUtil.collectElementsOfType(ktFile, KtReturnExpression::class.java)
            .forEach { report(it, "a top-level `return`") }
        PsiTreeUtil.collectElementsOfType(ktFile, KtThisExpression::class.java)
            .forEach { report(it, "`this`") }
        PsiTreeUtil.collectElementsOfType(ktFile, KtNameReferenceExpression::class.java)
            .filter { it.getReferencedName() in AssetScope.PROPERTY_NAMES }
            .filterNot { (it.parent as? KtDotQualifiedExpression)?.selectorExpression === it }
            .forEach { report(it, "a bare read of the `AssetScope.${it.getReferencedName()}` property") }

        return found
    }

    private fun render(
        relative: String,
        simpleName: String,
        absolute: Path,
        imports: List<String>,
        body: String,
    ): String = buildString {
        append("package ").append(packageName).append("\n\n")
        val allImports = (imports + REQUIRED_IMPORTS).distinct().sorted()
        allImports.forEach { append(it).append('\n') }
        append('\n')
        append("/**\n")
        append(" * Generated from `").append(relative).append("` by UdeaTranspiler (issue #87).\n")
        append(" *\n")
        append(" * Do not edit: every downstream pass reads the graph this produces, and the only\n")
        append(" * way to change what it declares is to change the script it came from.\n")
        append(" */\n")
        append("public class ").append(simpleName).append(" : AssetSource {\n")
        append("    override val idPrefix: String = \"").append(idPrefixOf(absolute)).append("\"\n")
        append("    override val defaultName: String = \"")
            .append(absolute.name.removeSuffix(UdeaDeclarationScanner.SCRIPT_SUFFIX)).append("\"\n\n")
        append("    override fun build(scope: AssetScope) {\n")
        body.trim().lineSequence().forEach { line ->
            if (line.isBlank()) append('\n') else append("        ").append(line).append('\n')
        }
        append("    }\n")
        append("}\n")
    }

    private fun idPrefixOf(file: Path): String {
        val parent = file.parent ?: return ""
        if (parent == assetRoot) return ""
        return parent.relativeTo(assetRoot).toString().replace('\\', '/')
    }

    /**
     * `character/orc_elite.udea.kts` becomes `CharacterOrcEliteAssets`.
     *
     * The path is part of the name, not just the file name: `character/orc.udea.kts` and
     * `blueprint/orc.udea.kts` are two different assets and must be two different classes.
     */
    private fun classNameFor(file: Path): String {
        val relative = file.relativeTo(assetRoot).toString().replace('\\', '/')
        val words = relative.removeSuffix(UdeaDeclarationScanner.SCRIPT_SUFFIX)
            .split('/', '_', '-', '.')
            .filter { it.isNotEmpty() }
        return words.joinToString("") { word ->
            word.replaceFirstChar { it.uppercaseChar() }
        } + "Assets"
    }

    public companion object {
        /** Package of the emitted sources. */
        public const val DEFAULT_PACKAGE: String = "dev.wildware.udea.assets.generated"

        /** What every emitted file needs, whatever the script imported. */
        public val REQUIRED_IMPORTS: List<String> = listOf(
            "import dev.wildware.udea.assets.compiler.AssetScope",
            "import dev.wildware.udea.assets.compiler.AssetSource",
            "import dev.wildware.udea.assets.compiler.Ref",
        )

        /** The `ServiceLoader` resource the transpiled front end is discovered through. */
        public const val SERVICE_FILE: String = "META-INF/services/dev.wildware.udea.assets.compiler.AssetSource"
    }
}

/**
 * Which front end produces the [dev.wildware.udea.assets.compiler.AssetGraph].
 *
 * Defaults to [Script] everywhere. [Transpiled] is the prototype escape hatch of issue #87
 * and stays behind the flag until the `kotlin-compiler-embeddable`-in-a-worker risk actually
 * fires; the Gradle-side `udea { scriptMode = ... }` extension that surfaces it is the build
 * wiring issue's, since this module holds no Gradle types.
 */
public enum class ScriptMode {
    /** Compile and evaluate `.udea.kts` with the Kotlin scripting host (issue #86). */
    Script,

    /** Transpile to plain `.kt`, compile normally, discover by `ServiceLoader` (issue #87). */
    Transpiled,
}
