package dev.wildware.udea.assets.compiler.validate

import dev.wildware.udea.assets.compiler.scan.KtParser
import dev.wildware.udea.assets.compiler.scan.LineIndex
import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import dev.wildware.udea.diagnostics.SourceSpan
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import dev.wildware.udea.diagnostics.UdeaRule
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readBytes

/**
 * A `.udea.kts` reads a clock or an unseeded random.
 *
 * The live offender when this was written is
 * `example/src/main/resources/assets/level/test_level.udea.kts`, which imports
 * `kotlin.random.Random` and spawns every entity at `Random.nextFloat()` positions. That makes
 * the *asset pack* a different artefact on every build, which is a different and worse thing
 * than randomness at spawn time: spec 3.6 makes a pack a deterministic function of its sources,
 * and everything downstream — the content hash, the byte-identical `diagnostics.json`, a rewind
 * that reproduces a capture — is built on that being true.
 *
 * ### Syntactic, over PSI, not over text
 *
 * The check walks name references in the parsed file rather than grepping the source, so
 * `// no Random here` in a comment and `"Random"` in a string literal are not name references
 * and cannot produce a false positive. It uses pass 1's classpath-free [KtParser], so it
 * resolves nothing — a game class genuinely called `Random` would be a false positive, which is
 * the honest cost of having no resolver and is why the banned set is small and named.
 *
 * ### No `Fix`
 *
 * [dev.wildware.udea.diagnostics.Fix] is "a machine-applicable repair, when one is unambiguous",
 * and there is not one here. There is no seeded `Random` in this engine to redirect an author
 * to, and a fix that named an imaginary `assetRandom(seed)` would be worse than no fix: an agent
 * would apply it and get an unresolved reference. The message says what to do instead and stops
 * there. When a seeded source exists, this is where the `Fix` goes.
 */
public object DeterminismValidator : AssetValidator {

    /**
     * What a `.udea.kts` may not call, and the fully qualified thing each one names.
     *
     * A closed list, because the alternative — "anything that looks non-deterministic" — has no
     * definition a build can check. Every entry is matched *syntactically*: a bare name for a
     * type that is only ever used through its companion (`Random.nextFloat()`), and a
     * `Receiver.member` pair for the rest.
     */
    public val BANNED: Map<String, String> = linkedMapOf(
        "Random" to "kotlin.random.Random",
        "Math.random" to "java.lang.Math.random()",
        "System.currentTimeMillis" to "java.lang.System.currentTimeMillis()",
        "System.nanoTime" to "java.lang.System.nanoTime()",
        "UUID.randomUUID" to "java.util.UUID.randomUUID()",
    )

    override val rules: List<UdeaRule> = listOf(AssetValidationRules.NONDETERMINISTIC_ASSET)

    override fun validate(context: ValidationContext): List<UdeaDiagnostic> =
        context.sources.sortedBy { it.toString() }.flatMap { file -> validateFile(context, file) }

    private fun validateFile(context: ValidationContext, file: Path): List<UdeaDiagnostic> {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return emptyList()
        val text = UdeaDeclarationScanner.normalizeLineEndings(String(bytes, Charsets.UTF_8))
        val path = SourceSpan.relativize(context.repoRoot.toString(), file.toAbsolutePath().normalize().toString())
        val ktFile = KtParser.shared.parse(file.name, text)
        val lines = LineIndex(text)

        val found = LinkedHashMap<String, SourceSpan>()
        for (name in PsiTreeUtil.collectElementsOfType(ktFile, KtSimpleNameExpression::class.java)) {
            // An import is not a use. `import kotlin.random.Random` on its own changes nothing
            // the pack contains, and pointing an author at line 1 when the call is on line 40
            // sends them to the wrong edit.
            if (PsiTreeUtil.getParentOfType(name, KtImportDirective::class.java, false) != null) continue
            val referenced = name.getReferencedName()
            // `System.currentTimeMillis()` is a dot-qualified expression whose *selector* is a
            // call, so the name node's own parent is the call and not the qualification - the
            // qualifier has to be found by walking up. Matching the bare selector instead would
            // ban a member called `nanoTime` on any receiver at all, which is why the fixture
            // declares a `Stopwatch.nanoTime()` and asserts it is left alone.
            val qualified = run {
                val dotted = PsiTreeUtil.getParentOfType(name, KtDotQualifiedExpression::class.java, true)
                    ?: return@run null
                val selector = dotted.selectorExpression ?: return@run null
                val callee = (selector as? KtCallExpression)?.calleeExpression
                if (selector === name || callee === name) {
                    "${dotted.receiverExpression.text}.$referenced"
                } else {
                    null
                }
            }
            val hit = when {
                qualified != null && qualified in BANNED -> qualified
                referenced in BANNED -> referenced
                else -> null
            } ?: continue
            val range = name.textRange
            found.putIfAbsent(
                hit,
                SourceSpan(
                    path,
                    lines.lineOf(range.startOffset),
                    lines.columnOf(range.startOffset),
                    lines.lineOf(range.endOffset),
                    lines.columnOf(range.endOffset),
                ),
            )
        }

        return found.entries.sortedBy { it.key }.map { (hit, span) ->
            AssetValidationRules.NONDETERMINISTIC_ASSET.diagnostic(
                message = "`${file.name}` uses `$hit` (${BANNED.getValue(hit)}). An asset script " +
                    "must evaluate to the same graph on every build, and this makes the pack " +
                    "differ between two builds of the same sources. Declare the value as a " +
                    "literal, or move the randomisation to a spawn-time system where the " +
                    "simulation seed governs it.",
                span = span,
            )
        }
    }
}
