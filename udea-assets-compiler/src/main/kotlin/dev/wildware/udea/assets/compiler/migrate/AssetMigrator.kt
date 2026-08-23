package dev.wildware.udea.assets.compiler.migrate

import dev.wildware.udea.assets.compiler.AssetCompilerRules
import dev.wildware.udea.assets.compiler.scan.KtParser
import dev.wildware.udea.assets.compiler.scan.LineIndex
import dev.wildware.udea.diagnostics.SourceSpan
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * What one rewrite did, so a migration report names a change rather than a file.
 *
 * The [span] is of the **original** text: every edit is computed against the parse of the file
 * as it was, and they are applied back-to-front, so an edit's span never has to be adjusted for
 * an earlier one.
 */
public data class MigrationEdit(
    public val rule: MigrationRule,
    public val span: SourceSpan,
    /** The text that was there. */
    public val before: String,
    /** What replaced it. */
    public val after: String,
)

/** The mechanical rewrites this migrator knows, named so a report can count them per kind. */
public enum class MigrationRule {
    /** `bundle { ... }` unwrapped: the file *is* the bundle (issue #86). */
    UnwrapBundle,

    /** `x = lazy { ... }` → `x = { ... }`, deleting the `fun lazy` that shadows `kotlin.lazy`. */
    DropLazy,

    /** `"/sprites/a.png"` → `"sprites/a.png"`, the one spelling `ResPath` has. */
    StripLeadingSlash,

    /** A hand-written `columns` corrected against the sheet's real width. */
    InferColumns,

    /** `kotlin.random.Random` replaced by a seeded generator, so two builds agree. */
    SeedRandom,
}

/** One `.udea.kts` after migration, with everything the migrator did and everything it refused. */
public data class MigrationResult(
    /** Repo-relative, `/`-separated. */
    public val path: String,
    public val original: String,
    public val migrated: String,
    public val edits: List<MigrationEdit>,
    /**
     * What it could not decide, as [AssetCompilerRules.MIGRATION_UNDECIDED] with a span.
     *
     * These are *also* written into the file as `// TODO(udea-migrate)` comments, because a
     * diagnostic scrolls off a console and a comment sits on the line that needs a human.
     */
    public val undecided: List<UdeaDiagnostic>,
) {
    public val changed: Boolean get() = original != migrated
}

/**
 * Reads a sheet's pixel dimensions, so a hand-written frame count can be checked against the art.
 *
 * An interface because the migrator must run in a Gradle worker with no AWT and no GL: the only
 * implementation reads a PNG's `IHDR` header directly ([PngSheetProbe]), and a test substitutes
 * a map.
 */
public fun interface SheetProbe {
    /** `width to height` in pixels, or null when [resPath] names no file under the asset root. */
    public fun dimensions(resPath: String): Pair<Int, Int>?
}

/**
 * The 24 bytes of a PNG that say how big it is.
 *
 * `ImageIO.read` would decode the whole image to answer the same question — for the example
 * corpus that is thirty-odd megabytes of pixels inflated to learn sixty integers — and it drags
 * `java.desktop` onto a build worker that otherwise needs no AWT at all.
 */
public class PngSheetProbe(private val assetRoot: Path) : SheetProbe {

    override fun dimensions(resPath: String): Pair<Int, Int>? {
        val file = assetRoot.resolve(resPath).toFile()
        if (!file.isFile) return null
        val header = ByteArray(24)
        file.inputStream().use { stream ->
            var read = 0
            while (read < header.size) {
                val n = stream.read(header, read, header.size - read)
                if (n < 0) return null
                read += n
            }
        }
        if (!header.copyOfRange(0, 8).contentEquals(PNG_MAGIC)) return null
        return beInt(header, 16) to beInt(header, 20)
    }

    private fun beInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF shl 24) or
            (bytes[at + 1].toInt() and 0xFF shl 16) or
            (bytes[at + 2].toInt() and 0xFF shl 8) or
            (bytes[at + 3].toInt() and 0xFF)

    private companion object {
        val PNG_MAGIC = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    }
}

/**
 * `udeaMigrateAssets`: the mechanical half of porting a `.udea.kts` tree onto the new model.
 *
 * ## Why a migrator exists at all, and what it is evidence of
 *
 * Spec D4 kept `.udea.kts` as the authoring format precisely so this would be a *rewrite of
 * spellings* rather than a rewrite of files. This class is the test of that claim, and the
 * honest result is in the report: the five rules below cover every syntactic difference between
 * the old bundle format and the new one, and they do **not** cover the parts of the old corpus
 * that call into `common`'s ECS component functions. Those are a port of game code, not of
 * assets, and the migrator marks them rather than pretending.
 *
 * ## Everything is computed from PSI and applied by offset
 *
 * Regex over Kotlin source is how a migration silently corrupts a string that happens to contain
 * the word it was looking for. Every rule here matches a PSI node, records the node's text range,
 * and the ranges are applied right-to-left so no offset has to be adjusted. A rule that cannot
 * find the shape it wants makes no edit; it does not fall back to text.
 *
 * ## It is idempotent by construction, not by a second pass
 *
 * Each rule's pattern is *absent from its own output*: there is no `bundle` call left to unwrap,
 * no `lazy` callee left to drop, no leading slash left to strip, no `rows`/`columns` pair left
 * that disagrees with the art, and the seeded generator is only inserted when the file does not
 * already declare one. `MigratorIdempotenceTest` runs the real corpus twice and asserts the
 * second pass produces no diff — the property is asserted rather than argued.
 */
public class AssetMigrator @JvmOverloads constructor(
    repoRoot: Path,
    private val probe: SheetProbe,
    private val parser: KtParser = KtParser.shared,
) {
    private val repoRoot: Path = repoRoot.toAbsolutePath().normalize()

    /** Migrates [file]'s text without writing anything. */
    public fun migrate(file: Path): MigrationResult =
        migrateText(SourceSpan.relativize(repoRoot.toString(), file.toAbsolutePath().normalize().toString()), file.name, file.readText())

    /**
     * Migrates [text] as the script [fileName].
     *
     * @param path the repo-relative path used in every emitted span.
     */
    public fun migrateText(path: String, fileName: String, text: String): MigrationResult {
        val source = text.replace("\r\n", "\n")
        // Two passes, and the split is forced rather than stylistic. Unwrapping `bundle { }`
        // rewrites a span that *contains* every other rule's span - the whole file - so running
        // all five against one parse produces overlapping edits, which is a class of bug that
        // ends in silently corrupted output (it was caught by `apply`'s overlap check on the
        // real corpus, which is why that check is loud rather than resolving a winner). The
        // unwrap therefore runs alone, and the remaining four run against a fresh parse of its
        // result. Two parses of a 200-line script costs a few milliseconds; deciding by hand
        // which of two overlapping edits wins is how a migrator starts guessing.
        val unwrapped = Plan(path, source).also { it.unwrapBundle(parser.parse(fileName, source)) }.apply()
        val second = Plan(path, unwrapped.migrated)
        val ktFile = parser.parse(fileName, unwrapped.migrated)
        second.dropLazy(ktFile)
        second.stripLeadingSlashes(ktFile)
        second.inferColumns(ktFile)
        second.seedRandom(ktFile)
        val result = second.apply()
        // Spans from the second pass are line numbers in the *unwrapped* text, which is one line
        // shallower than the original. That is stated rather than corrected: the unwrapped text
        // is what is written to disk and what a human will open, so a span that points into it
        // points at the file they will actually be looking at.
        return result.copy(
            original = source,
            edits = unwrapped.edits + result.edits,
            undecided = unwrapped.undecided + result.undecided,
        )
    }

    /**
     * One file's accumulated rewrites.
     *
     * A [Replacement] is a half-open offset range and its new text. Two rules never overlap on
     * the corpus, and an overlap is a bug rather than a case to resolve, so [apply] refuses one
     * loudly instead of picking a winner.
     */
    private inner class Plan(
        private val path: String,
        private val source: String,
    ) {
        private val lines = LineIndex(source)

        private val replacements = mutableListOf<Replacement>()
        private val undecided = mutableListOf<UdeaDiagnostic>()

        /** Line-start offsets where a `// TODO(udea-migrate)` comment is to be inserted. */
        private val todoComments = mutableListOf<Pair<Int, String>>()

        // --- rule 1: the file is the bundle ------------------------------------------------

        /**
         * Replaces a top-level `bundle { body }` with `body`, dedented by one indent level.
         *
         * Dedent rather than leave-as-is because every line of every migrated file would
         * otherwise carry four spaces of indent that mean nothing, and a reviewer diffing the
         * migration would be reading whitespace. The dedent is exact: it removes the common
         * leading indent shared by every non-blank line of the body, so a body indented
         * irregularly is left alone rather than mangled.
         */
        fun unwrapBundle(ktFile: KtFile) {
            for (call in topLevelCalls(ktFile)) {
                if (call.calleeExpression?.text != BUNDLE) continue
                if (call.valueArguments.any { it.getArgumentExpression() != null && it !in call.lambdaArguments }) continue
                val lambda = call.lambdaArguments.singleOrNull()?.getLambdaExpression() ?: continue
                val body = lambda.bodyExpression ?: continue
                val bodyText = source.substring(body.textRange.startOffset, body.textRange.endOffset)
                replace(
                    call.textRange.startOffset,
                    call.textRange.endOffset,
                    dedent(bodyText).trim(),
                    MigrationRule.UnwrapBundle,
                )
                // One `bundle` per file. A second is malformed input, and unwrapping both would
                // splice two bodies together with no way to see where the seam was.
                break
            }
        }

        // --- rule 2: no `fun lazy` shadowing `kotlin.lazy` ---------------------------------

        /**
         * Deletes the `lazy` callee of `lazy { ... }`, leaving the builder lambda in place.
         *
         * The brief's wording is "rewrite `lazy { ... }` component lists to `components { }`",
         * and this deliberately does something slightly different, for a reason worth stating.
         * Every one of these is a **named argument** — `components = lazy { }`,
         * `abilitySpecs = lazy { }` — so the argument's name is already written one token to the
         * left. `components = components { }` says it twice, and `components { }` on its own is
         * not a named argument at all and would not parse in the position it appears in. Every
         * *other* builder argument in the same corpus (`sounds = { add(...) }`,
         * `notifies = { ... }`, `tags = { ... }`) is already a bare lambda, so dropping the
         * callee makes `lazy` uniform with them rather than inventing a sixth spelling.
         *
         * A bare `lazy { }` that is **not** a named argument is left alone and reported: it is
         * either `kotlin.lazy` (which must not be rewritten) or a list in a position this rule
         * cannot see the intended name of.
         */
        fun dropLazy(ktFile: KtFile) {
            PsiTreeUtil.findChildrenOfType(ktFile, KtCallExpression::class.java).forEach { call ->
                val callee = call.calleeExpression as? KtNameReferenceExpression ?: return@forEach
                if (callee.text != LAZY) return@forEach
                // A qualified `kotlin.lazy { }` is the stdlib one and is never the shadow.
                if (call.parent is KtDotQualifiedExpression) return@forEach
                if (call.valueArguments.any { it !in call.lambdaArguments }) return@forEach
                if (call.lambdaArguments.size != 1) return@forEach
                val argument = call.parent as? KtValueArgument
                if (argument?.getArgumentName() == null) {
                    undecide(
                        call,
                        "`lazy { }` here is not a named argument, so the migrator cannot tell " +
                            "whether it is the shadowing list builder or `kotlin.lazy`. Name the " +
                            "argument, or replace it by hand.",
                    )
                    return@forEach
                }
                // Delete the callee and any whitespace between it and the lambda, so
                // `lazy { x }` becomes `{ x }` rather than ` { x }`.
                val lambdaStart = call.lambdaArguments.single().textRange.startOffset
                replace(callee.textRange.startOffset, lambdaStart, "", MigrationRule.DropLazy)
            }
        }

        // --- rule 3: one spelling per resource path ----------------------------------------

        /**
         * Strips the leading `/` from every string literal that names a file.
         *
         * "Names a file" is decided syntactically: a single-part string literal starting with
         * `/` whose last segment has an extension. That is narrow enough not to touch an id
         * (`character/orc` has no extension) and wide enough to cover both spellings the corpus
         * uses — `spritePath = "/sprites/..."` and `add("/sounds/....ogg")` inside a builder.
         *
         * This is the rewrite that turns the old tree's two-keys-for-one-file bug into one key;
         * see `ResPath`'s KDoc for the bug itself.
         */
        fun stripLeadingSlashes(ktFile: KtFile) {
            PsiTreeUtil.findChildrenOfType(ktFile, KtStringTemplateExpression::class.java)
                .forEach { literal ->
                    val entry = literal.entries.singleOrNull() as? KtLiteralStringTemplateEntry
                        ?: return@forEach
                    val value = entry.text
                    if (!value.startsWith("/")) return@forEach
                    if (!value.substringAfterLast('/').contains('.')) return@forEach
                    replace(
                        entry.textRange.startOffset,
                        entry.textRange.startOffset + 1,
                        "",
                        MigrationRule.StripLeadingSlash,
                    )
                }
        }

        // --- rule 4: the art decides the frame count ---------------------------------------

        /**
         * Corrects a `spriteSheet`'s hand-written `columns` against the sheet's real width.
         *
         * The brief asks for a `frames` argument inferred from the PNG. `frames` is not written,
         * and the divergence is deliberate: the runtime model that landed in `udea-assets`
         * (`SpriteSheet(texture, columns, rows, scale)`) has no `frames` field, and
         * `AssetScope.spriteSheet` has no such parameter, so a migrator emitting one would
         * produce a corpus that does not compile against the receiver it was migrated onto. What
         * the PNG is actually good for is the same thing either way — *checking* the number a
         * human typed — so that is what it does, and a disagreement is corrected in place and
         * recorded as an edit.
         *
         * A missing sheet is [AssetCompilerRules.MIGRATION_UNDECIDED], not a silent pass. The
         * example corpus has six of them and they are a real bug, not a migration failure: see
         * the report.
         */
        fun inferColumns(ktFile: KtFile) {
            PsiTreeUtil.findChildrenOfType(ktFile, KtCallExpression::class.java).forEach { call ->
                if (call.calleeExpression?.text != SPRITE_SHEET) return@forEach
                val spritePath = namedStringArgument(call, "spritePath") ?: return@forEach
                val resPath = spritePath.removePrefix("/")
                val columns = namedIntArgument(call, "columns")
                val rows = namedIntArgument(call, "rows")
                val size = probe.dimensions(resPath)
                if (size == null) {
                    undecide(
                        call,
                        "sprite sheet '$resPath' is not under the asset root, so its frame count " +
                            "cannot be checked against the art. The declared columns were left " +
                            "as written.",
                    )
                    return@forEach
                }
                val (width, height) = size
                val rowCount = rows?.second ?: 1
                if (rowCount <= 0 || height % rowCount != 0) {
                    undecide(call, "sprite sheet '$resPath' is ${width}x$height, which does not divide into $rowCount rows.")
                    return@forEach
                }
                val frameHeight = height / rowCount
                if (frameHeight == 0 || width % frameHeight != 0) {
                    undecide(
                        call,
                        "sprite sheet '$resPath' is ${width}x$height, so a $rowCount-row grid has " +
                            "${frameHeight}px-tall frames and the width does not divide into " +
                            "square ones. Set `columns` by hand.",
                    )
                    return@forEach
                }
                val inferred = width / frameHeight
                if (columns == null) {
                    if (inferred != 1) {
                        undecide(
                            call,
                            "sprite sheet '$resPath' declares no `columns` but is ${width}x$height, " +
                                "which holds $inferred square frames.",
                        )
                    }
                    return@forEach
                }
                val (argument, declared) = columns
                if (declared == inferred) return@forEach
                val expression = argument.getArgumentExpression() ?: return@forEach
                replace(
                    expression.textRange.startOffset,
                    expression.textRange.endOffset,
                    inferred.toString(),
                    MigrationRule.InferColumns,
                )
            }
        }

        // --- rule 5: two builds agree -------------------------------------------------------

        /**
         * Replaces `kotlin.random.Random` with a seeded generator declared in the file.
         *
         * `Random.nextFloat()` in `level/test_level.udea.kts` is the reason two clean builds of
         * that level produce different bytes, which is the thing Phase 2's reproducibility gate
         * measures. A seed makes the level's layout a fixed, reviewable fact rather than a
         * property of when the build ran.
         *
         * The seed is derived from the file's own path, not from a constant: two levels that
         * both migrated to `Random(0)` would lay their entities out identically, which is a new
         * and stranger bug than the one being fixed.
         */
        fun seedRandom(ktFile: KtFile) {
            val import = ktFile.importDirectives.firstOrNull { it.importedFqName?.asString() == RANDOM_FQN }
                ?: return
            val uses = PsiTreeUtil.findChildrenOfType(ktFile, KtNameReferenceExpression::class.java)
                .filter { it.text == RANDOM && it.parent is KtDotQualifiedExpression }
                .filter { PsiTreeUtil.getParentOfType(it, KtImportDirective::class.java) == null }
            if (uses.isEmpty()) return
            val seed = seedFor(path)
            // Idempotence: a file already holding the generator declares it, so a second run
            // finds no bare `Random.` uses at all and never reaches here.
            val declaration = "\nprivate val $SEEDED = $RANDOM($seed)\n"
            replace(
                import.textRange.endOffset,
                import.textRange.endOffset,
                declaration,
                MigrationRule.SeedRandom,
            )
            uses.forEach { use ->
                replace(use.textRange.startOffset, use.textRange.endOffset, SEEDED, MigrationRule.SeedRandom)
            }
        }

        // --- plumbing -----------------------------------------------------------------------

        fun apply(): MigrationResult {
            val ordered = replacements.sortedWith(compareBy({ it.from }, { it.to }))
            for (i in 1 until ordered.size) {
                val previous = ordered[i - 1]
                val current = ordered[i]
                check(current.from >= previous.to) {
                    "migration rules ${previous.rule} and ${current.rule} both rewrite " +
                        "$path offsets ${current.from}..${previous.to}; that is a migrator bug, " +
                        "not an authoring one"
                }
            }
            val builder = StringBuilder(source)
            val edits = mutableListOf<MigrationEdit>()
            for (replacement in ordered.asReversed()) {
                edits += MigrationEdit(
                    rule = replacement.rule,
                    span = spanOf(replacement.from, replacement.to),
                    before = source.substring(replacement.from, replacement.to),
                    after = replacement.text,
                )
                builder.replace(replacement.from, replacement.to, replacement.text)
            }
            var migrated = builder.toString()
            migrated = insertTodos(migrated)
            return MigrationResult(
                path = path,
                original = source,
                migrated = migrated,
                edits = edits.asReversed(),
                undecided = undecided.toList(),
            )
        }

        /**
         * Writes each undecided case as a comment above the line it is about.
         *
         * Done on the *migrated* text and keyed by line number rather than by offset, because
         * the offsets in [undecided] are into the original. That is safe here and only here: no
         * rule changes the number of lines above another rule's span except [unwrapBundle], and
         * a bundle unwrap removes exactly one line at the top of the file, which is corrected
         * for below.
         */
        private fun insertTodos(text: String): String {
            if (todoComments.isEmpty()) return text
            val out = text.lines().toMutableList()
            for ((line, message) in todoComments.sortedByDescending { it.first }) {
                val at = (line - 1).coerceIn(0, out.size)
                val comment = "// $TODO_MARKER $message"
                // Idempotence. A second run over an already-migrated file raises the *same*
                // undecided case in the same place — the wizard's six missing sheets do not
                // become decidable by having been migrated once — so without this the comments
                // stack up one per run. Found by running the migrator twice and diffing the
                // tree, which is exactly what the acceptance criteria ask for; it was not
                // hypothetical.
                if (out.getOrNull(at - 1)?.trimStart() == comment) continue
                val indent = out.getOrNull(at)?.takeWhile { it == ' ' } ?: ""
                out.add(at, "$indent$comment")
            }
            return out.joinToString("\n")
        }

        private fun replace(from: Int, to: Int, text: String, rule: MigrationRule) {
            if (from == to && text.isEmpty()) return
            if (source.substring(from, to) == text) return
            replacements += Replacement(from, to, text, rule)
        }

        private fun undecide(element: PsiElement, message: String) {
            val span = spanOf(element.textRange.startOffset, element.textRange.endOffset)
            undecided += AssetCompilerRules.MIGRATION_UNDECIDED.diagnostic(message = message, span = span)
            todoComments += span.startLine to message
        }

        private fun spanOf(from: Int, to: Int): SourceSpan = SourceSpan(
            path = path,
            startLine = lines.lineOf(from),
            startColumn = lines.columnOf(from),
            endLine = lines.lineOf(to),
            endColumn = lines.columnOf(to),
        )
    }

    private class Replacement(val from: Int, val to: Int, val text: String, val rule: MigrationRule)

    public companion object {
        public const val BUNDLE: String = "bundle"
        public const val LAZY: String = "lazy"
        public const val RANDOM: String = "Random"
        public const val RANDOM_FQN: String = "kotlin.random.Random"
        public const val SPRITE_SHEET: String = "spriteSheet"

        /** The name the seeded generator is declared under. */
        public const val SEEDED: String = "migratedRandom"

        /** What a human greps for to find everything the migrator refused to decide. */
        public const val TODO_MARKER: String = "TODO(udea-migrate):"

        /** Stable, path-derived, and not the same for two files. See `seedRandom`. */
        public fun seedFor(path: String): Int = path.fold(17) { acc, c -> acc * 31 + c.code } and 0x7FFFFFF

        private fun topLevelCalls(ktFile: KtFile): List<KtCallExpression> =
            ktFile.script?.blockExpression?.statements.orEmpty()
                .flatMap { PsiTreeUtil.findChildrenOfType(it, KtCallExpression::class.java).toList().take(1) }

        private fun namedStringArgument(call: KtCallExpression, name: String): String? {
            val argument = call.valueArguments.firstOrNull { it.getArgumentName()?.asName?.asString() == name }
            val template = argument?.getArgumentExpression() as? KtStringTemplateExpression ?: return null
            val entry = template.entries.singleOrNull() as? KtLiteralStringTemplateEntry ?: return null
            return entry.text
        }

        private fun namedIntArgument(call: KtCallExpression, name: String): Pair<KtValueArgument, Int>? {
            val argument = call.valueArguments
                .filterIsInstance<KtValueArgument>()
                .firstOrNull { it.getArgumentName()?.asName?.asString() == name } ?: return null
            val value = argument.getArgumentExpression()?.text?.toIntOrNull() ?: return null
            return argument to value
        }

        /**
         * Removes the indent every non-blank line shares, **ignoring the first line**.
         *
         * The exclusion is not a heuristic. A lambda body's PSI range starts at its first
         * *statement*, so the whitespace in front of that statement is outside the range and the
         * extracted text's first line always begins at column 0. Counting it makes the common
         * indent zero for every well-formatted file in the corpus, and the dedent then silently
         * does nothing — which is exactly what it did before this was written down.
         *
         * An irregularly indented body is left alone rather than mangled: the common indent is
         * then small or zero, and little or nothing is removed.
         */
        internal fun dedent(text: String): String {
            val lines = text.lines()
            if (lines.size < 2) return text
            val common = lines.drop(1).filter { it.isNotBlank() }
                .minOfOrNull { line -> line.takeWhile { it == ' ' }.length } ?: return text
            if (common == 0) return text
            return lines.mapIndexed { index, line ->
                if (index == 0 || line.isBlank()) line else line.substring(common)
            }.joinToString("\n")
        }
    }
}
