package dev.wildware.udea.assets.compiler.scan

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import dev.wildware.udea.assets.compiler.AssetCompilerRules
import dev.wildware.udea.diagnostics.SourceSpan
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtScriptInitializer
import org.jetbrains.kotlin.psi.KtSimpleNameStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

/** One asset declaration, as pass 1 can see it without compiling anything. */
public data class Declaration(
    /** The DSL function that declared it: `character`, `spriteSheet`, `soundCue`, ... */
    public val kind: String,
    /** `character/orc_elite` — the directory relative to the asset root plus the name. */
    public val id: String,
    /** The declared name literal, or the script's base name when the kind carries none. */
    public val name: String,
    /** The callee, e.g. the `character` in `character(name = "orc_elite", ...)`. */
    public val span: SourceSpan,
)

/** One `reference("...")` literal, with the span of the literal itself. */
public data class ReferenceSite(
    /** The referenced id exactly as written. */
    public val target: String,
    public val span: SourceSpan,
    /** The id of the enclosing top-level declaration, or null when there is none. */
    public val from: String?,
)

/**
 * Every `reference("...")` span in a scan, and the reason pass 2 can always locate a failure.
 *
 * Pass 2's origin capture reads `Throwable().stackTrace[0]`, which is empty or wrong for a
 * reference written inside an inlined lambda or in a helper declared in game source. This
 * index is the guaranteed fallback: it was produced syntactically, so it exists for every
 * reference literally present in a `.udea.kts` whether or not the runtime could attribute a
 * frame. A build never fails for want of a line number (issue #85) — worst case a diagnostic
 * degrades to the file and the asset id.
 */
public class ReferenceSpanIndex(sites: List<ReferenceSite>) {

    private val byTarget: Map<String, List<ReferenceSite>> = sites.groupBy { it.target }

    /** Every referenced id this index knows about. */
    public fun targets(): Set<String> = byTarget.keys

    /** Every site referencing [target], in scan order. */
    public fun sitesFor(target: String): List<ReferenceSite> = byTarget[target].orEmpty()

    /** The first span referencing [target], or null when nothing does. */
    public fun spanFor(target: String): SourceSpan? = byTarget[target]?.firstOrNull()?.span

    /** The first span referencing [target] from within [file], falling back to any site. */
    public fun spanFor(target: String, file: String): SourceSpan? {
        val sites = byTarget[target] ?: return null
        return (sites.firstOrNull { it.span.path == file } ?: sites.first()).span
    }

    public val size: Int get() = byTarget.values.sumOf { it.size }
}

/** The scan of one file: what it declares, what it references, and what is wrong with it. */
public data class FileScan(
    /** Repo-relative, `/`-separated. */
    public val path: String,
    /** sha256 of the file's bytes, hex. The per-file cache key. */
    public val contentHash: String,
    public val declarations: List<Declaration>,
    public val references: List<ReferenceSite>,
    public val diagnostics: List<UdeaDiagnostic>,
)

/** The scan of a tree. */
public data class ScanReport(
    /** Repo-relative path of the asset root every id is computed against. */
    public val assetRoot: String,
    /** Sorted by [FileScan.path]. */
    public val files: List<FileScan>,
) {
    public val declarations: List<Declaration> get() = files.flatMap { it.declarations }
    public val references: List<ReferenceSite> get() = files.flatMap { it.references }
    public val diagnostics: List<UdeaDiagnostic> get() = files.flatMap { it.diagnostics }
    public val ids: Set<String> get() = declarations.map { it.id }.toSet()

    /** The pass-2 fallback index (see [ReferenceSpanIndex]). */
    public fun referenceSpanIndex(): ReferenceSpanIndex = ReferenceSpanIndex(references)
}

/**
 * Pass 1 of spec 3.6: a **syntactic** scan of `.udea.kts` with no classpath and no resolution.
 *
 * What it produces is a located name for everything: every declaration and every
 * `reference("...")` literal with a precise [SourceSpan]. That is what turns a typo into a
 * diagnostic an agent can fix in one turn rather than a stack trace out of a script host, and
 * it is available *before* anything is compiled — which is the only way to break the
 * chicken-and-egg between generated accessors and script compilation.
 *
 * ### Ids are computed from the asset root, never from a path substring
 *
 * `assetRoot.relativize(file).parent` plus the declared name. The code this replaces used
 * `file.path.substringAfterLast("assets/")`, which makes an asset id depend on where the
 * repository is checked out and on the process working directory — two developers with
 * different directory layouts got different ids from identical sources.
 *
 * ### What counts as a declaration
 *
 * Every **top-level** call expression, taking its kind from the callee's name. There is no
 * hardcoded vocabulary of kinds here on purpose: pass 1 must keep working when issue #84's
 * generated DSL grows a kind, and a fixed list would silently stop seeing it.
 *
 * Two shapes are structural rather than declarations, and are descended into instead:
 *
 * - a lambda-taking call from [STRUCTURAL_CALLEES] — `bundle { }` (which issue #86 deletes,
 *   but which the whole current example tree still uses), and the scoping functions;
 * - `<constant list>.forEach { }`, the one sanctioned dynamic form. The receiver must be a
 *   `listOf`/`setOf`/`arrayOf` of string literals, or a `val` in the same file holding one,
 *   and the loop variable is then bound to each literal in turn so that loop-generated ids
 *   are statically known — including through a `"prefix_$it"` template.
 *
 * Anything else that computes a name yields [AssetCompilerRules.NON_LITERAL_ID] carrying the
 * span, and contributes no id.
 *
 * Not thread-safe; it owns a [KtParser], and PSI is not thread-safe.
 */
public class UdeaDeclarationScanner @JvmOverloads constructor(
    /** Absolute path of the repository root. Every emitted span is relative to it. */
    repoRoot: Path,
    /** Absolute path of the asset root. Every emitted id is relative to it. */
    assetRoot: Path,
    private val parser: KtParser = KtParser.shared,
    /** Whether [close] closes [parser]. False for the shared one, which outlives any scanner. */
    private val ownsParser: Boolean = false,
) : AutoCloseable {

    private val repoRoot: Path = repoRoot.toAbsolutePath().normalize()
    private val assetRoot: Path = assetRoot.toAbsolutePath().normalize()

    /**
     * Per-file results keyed on content hash, so the daemon rescans one edited file and reuses
     * eighteen unchanged ones.
     *
     * Keyed on the *hash* and not on a modification time: a touched-but-unchanged file is the
     * common case under an editor's save-all, and a rescan of it produces byte-identical output
     * that a downstream consumer then has to diff to discover nothing happened.
     */
    private val cache = HashMap<String, FileScan>()

    /** How many [scanFile] calls were answered from [cache]. Asserted by the warm-scan test. */
    public var cacheHits: Int = 0
        private set

    /** Every `.udea.kts` under the asset root, sorted, scanned. */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    public fun scanTree(): ScanReport {
        val files = assetRoot.walk()
            .filter { it.isRegularFile() && it.name.endsWith(SCRIPT_SUFFIX) }
            .sortedBy { it.toString().replace('\\', '/') }
            .toList()
        return scanFiles(files)
    }

    /** Scans exactly [files], sorted by repo-relative path so the report is stable. */
    public fun scanFiles(files: List<Path>): ScanReport = ScanReport(
        assetRoot = relative(assetRoot),
        files = files.map(::scanFile).sortedBy { it.path },
    )

    /** Scans one file, reusing a cached result when its bytes are unchanged. */
    public fun scanFile(file: Path): FileScan {
        val bytes = file.readBytes()
        val hash = sha256Hex(bytes)
        val path = relative(file.toAbsolutePath().normalize())
        cache["$path@$hash"]?.let {
            cacheHits++
            return it
        }
        val result = scanText(path, file, String(bytes, Charsets.UTF_8), hash)
        cache["$path@$hash"] = result
        return result
    }

    /** Discards every cached file result. */
    public fun clearCache() {
        cache.clear()
        cacheHits = 0
    }

    private fun scanText(path: String, file: Path, text: String, hash: String): FileScan {
        val normalized = normalizeLineEndings(text)
        val ktFile = parser.parse(file.name, normalized)
        val lines = LineIndex(normalized)
        val visitor = FileVisitor(path, lines, idPrefixOf(file), baseNameOf(file))
        visitor.run(ktFile)
        return FileScan(path, hash, visitor.declarations, visitor.references, visitor.diagnostics)
    }

    // --- id and path arithmetic ---------------------------------------------------------

    /**
     * The `character` in `character/orc_elite`: the file's directory relative to the asset
     * root. Empty for a script sitting at the root, whose ids are bare names.
     */
    private fun idPrefixOf(file: Path): String {
        val parent = file.toAbsolutePath().normalize().parent ?: return ""
        if (parent == assetRoot) return ""
        return parent.relativeTo(assetRoot).toString().replace('\\', '/')
    }

    /** `orc_elite` from `orc_elite.udea.kts`; the id of a kind that declares no name. */
    private fun baseNameOf(file: Path): String = file.name.removeSuffix(SCRIPT_SUFFIX)

    private fun relative(path: Path): String =
        SourceSpan.relativize(repoRoot.toString(), path.toString())

    override fun close() {
        if (ownsParser) parser.close()
    }

    /**
     * The walk over one file. A class rather than a fold because it accumulates three lists
     * and a binding environment, and threading four values through a recursion reads worse
     * than mutating four fields does.
     */
    private inner class FileVisitor(
        private val path: String,
        private val lines: LineIndex,
        private val idPrefix: String,
        private val defaultName: String,
    ) {
        val declarations = mutableListOf<Declaration>()
        val references = mutableListOf<ReferenceSite>()
        val diagnostics = mutableListOf<UdeaDiagnostic>()

        /** Declaration call expressions, kept to attribute references to the declaration. */
        private val declarationElements = mutableListOf<Pair<KtCallExpression, Declaration>>()

        /** File-level `val name = "literal"` and `val xs = listOf("a", "b")` constants. */
        private var fileConstants: Map<String, String> = emptyMap()
        private var fileConstantLists: Map<String, List<String>> = emptyMap()

        fun run(ktFile: KtFile) {
            val statements = topLevelStatements(ktFile)
            collectFileConstants(statements)
            statements.forEach { visitStatement(it, emptyMap()) }
            collectReferences(ktFile)
        }

        /**
         * The top-level statements of a script, with the parser's wrapper removed.
         *
         * A script body is a list of `KtScriptInitializer`s (one per *expression* statement)
         * mixed with plain `KtProperty` and `KtNamedFunction` declarations. Unwrapping here
         * rather than at every use is what keeps [visitStatement] a `when` over real
         * expression types; forgetting to is silent — the walk simply matches nothing and a
         * scan of nineteen files reports zero declarations, which is exactly what it did.
         */
        private fun topLevelStatements(ktFile: KtFile): List<KtExpression> =
            ktFile.script?.blockExpression?.statements.orEmpty().mapNotNull { statement ->
                when (statement) {
                    is KtScriptInitializer -> statement.body
                    is KtExpression -> statement
                    else -> null
                }
            }

        private fun collectFileConstants(statements: List<KtExpression>) {
            val scalars = mutableMapOf<String, String>()
            val lists = mutableMapOf<String, List<String>>()
            for (property in statements.filterIsInstance<KtProperty>()) {
                val name = property.name ?: continue
                val initializer = property.initializer ?: continue
                constantString(initializer, emptyMap())?.let { scalars[name] = it }
                constantStringList(initializer, emptyMap())?.let { lists[name] = it }
            }
            fileConstants = scalars
            fileConstantLists = lists
        }

        /** [bindings] maps a bound loop variable to the literal it currently holds. */
        private fun visitStatement(statement: KtExpression, bindings: Map<String, String>) {
            when (statement) {
                is KtCallExpression -> visitCall(statement, bindings)
                is KtDotQualifiedExpression -> visitForEach(statement, bindings)
                else -> Unit // val, fun, import, comment: not a declaration.
            }
        }

        private fun visitCall(call: KtCallExpression, bindings: Map<String, String>) {
            val callee = call.calleeExpression?.text ?: return
            if (callee in STRUCTURAL_CALLEES) {
                lambdaBodyOf(call)?.forEach { visitStatement(it, bindings) }
                return
            }
            recordDeclaration(callee, call, bindings)
        }

        /**
         * `listOf("a", "b").forEach { ... }` — the one sanctioned dynamic form.
         *
         * A non-constant receiver is not an error here: the lambda is still descended into
         * with no binding, so any declaration inside it that uses a literal name is still
         * found and any that does not gets [AssetCompilerRules.NON_LITERAL_ID] pointing at the
         * name expression rather than at the loop.
         */
        private fun visitForEach(expression: KtDotQualifiedExpression, bindings: Map<String, String>) {
            val call = expression.selectorExpression as? KtCallExpression ?: return
            if (call.calleeExpression?.text !in ITERATION_CALLEES) return
            val body = lambdaBodyOf(call) ?: return
            val parameter = lambdaOf(call)?.valueParameters?.firstOrNull()?.name ?: IMPLICIT_PARAMETER
            val elements = constantStringList(expression.receiverExpression, bindings)
            if (elements == null) {
                body.forEach { visitStatement(it, bindings) }
                return
            }
            for (element in elements) {
                body.forEach { visitStatement(it, bindings + (parameter to element)) }
            }
        }

        private fun recordDeclaration(
            kind: String,
            call: KtCallExpression,
            bindings: Map<String, String>,
        ) {
            val nameArgument = call.valueArguments.firstOrNull { it.getArgumentName()?.asName?.asString() == NAME_ARGUMENT }
            val nameExpression = nameArgument?.getArgumentExpression()
            val name = when {
                // A kind that declares no name (`gameConfig`, `level`) is named for its file.
                nameExpression == null -> defaultName
                else -> constantString(nameExpression, bindings)
            }
            val calleeSpan = spanOf(call.calleeExpression ?: call)
            if (name == null) {
                diagnostics += AssetCompilerRules.NON_LITERAL_ID.diagnostic(
                    message = "`$kind` is declared with a name that is not a string literal, so " +
                        "its asset id cannot be known before the script is evaluated. Use a " +
                        "literal, or the supported form `listOf(\"a\", \"b\").forEach { " +
                        "$kind(name = it, ...) }`.",
                    span = spanOrNull(nameExpression),
                )
                return
            }
            val declaration = Declaration(
                kind = kind,
                id = if (idPrefix.isEmpty()) name else "$idPrefix/$name",
                name = name,
                span = calleeSpan,
            )
            declarations += declaration
            declarationElements += call to declaration
        }

        /**
         * Every `reference("...")` in the file, wherever it is nested.
         *
         * Deliberately a whole-file sweep rather than a walk of the declaration subtrees: a
         * reference stored in a `val` at file scope, or written inside a local helper
         * function, is still a reference that has to be validated and still has a span. Those
         * simply get a null [ReferenceSite.from].
         */
        private fun collectReferences(ktFile: KtFile) {
            val calls = PsiTreeUtil.collectElementsOfType(ktFile, KtCallExpression::class.java)
            for (call in calls) {
                if (call.calleeExpression?.text != REFERENCE_CALLEE) continue
                val argument = call.valueArguments.singleOrNull()?.getArgumentExpression() ?: continue
                val target = constantString(argument, emptyMap()) ?: continue
                references += ReferenceSite(
                    target = target,
                    span = spanOf(argument),
                    from = declarationElements.firstOrNull { (element, _) ->
                        PsiTreeUtil.isAncestor(element, call, false)
                    }?.second?.id,
                )
            }
        }

        // --- constant folding, syntactic only ------------------------------------------

        /**
         * The compile-time string value of [expression], or null when there is not one.
         *
         * Four shapes fold: a plain literal, a `"prefix_$loopVar"` template whose every
         * interpolation is bound, a reference to a bound loop variable, and a reference to a
         * file-level `val` holding a literal. Everything else is honestly unknown — this is a
         * syntactic pass, and guessing here would produce an id that does not match what pass
         * 2 evaluates.
         */
        fun constantString(expression: KtExpression?, bindings: Map<String, String>): String? =
            when (expression) {
                is KtStringTemplateExpression -> foldTemplate(expression, bindings)
                is KtNameReferenceExpression ->
                    bindings[expression.getReferencedName()] ?: fileConstants[expression.getReferencedName()]
                else -> null
            }

        private fun foldTemplate(
            template: KtStringTemplateExpression,
            bindings: Map<String, String>,
        ): String? {
            val builder = StringBuilder()
            for (entry in template.entries) {
                when (entry) {
                    is KtSimpleNameStringTemplateEntry -> {
                        val name = entry.expression?.text ?: return null
                        builder.append(bindings[name] ?: fileConstants[name] ?: return null)
                    }
                    else -> {
                        // A literal chunk or an escape; anything with a nested expression
                        // (`${a.b}`) has no `text` we may trust, so it is rejected below.
                        if (entry.expression != null) return null
                        builder.append(entry.text.unescape() ?: return null)
                    }
                }
            }
            return builder.toString()
        }

        fun constantStringList(expression: KtExpression?, bindings: Map<String, String>): List<String>? {
            if (expression is KtNameReferenceExpression) {
                return fileConstantLists[expression.getReferencedName()]
            }
            val call = expression as? KtCallExpression ?: return null
            if (call.calleeExpression?.text !in COLLECTION_CALLEES) return null
            if (call.valueArguments.isEmpty()) return null
            return call.valueArguments.map { argument: KtValueArgument ->
                constantString(argument.getArgumentExpression(), bindings) ?: return null
            }
        }

        private fun spanOrNull(element: PsiElement?): SourceSpan? {
            if (element == null) return null
            val range = element.textRange
            return SourceSpan(
                path,
                lines.lineOf(range.startOffset),
                lines.columnOf(range.startOffset),
                lines.lineOf(range.endOffset),
                lines.columnOf(range.endOffset),
            )
        }

        private fun spanOf(element: PsiElement): SourceSpan = spanOrNull(element)!!
    }

    public companion object {
        /** The extension every asset script carries. */
        public const val SCRIPT_SUFFIX: String = ".udea.kts"

        /**
         * IntelliJ PSI documents are LF-only, and a carriage return reaching the parser is
         * not a parse error - it is worse than that. The file still parses, top-level calls
         * are still found, and only argument lists spanning more than one line quietly lose
         * their named arguments, so every multi-line `spriteSheet(name = "orc_idle", ...)`
         * in the example tree came back named for its *file* instead. A wrong id rather than
         * a failure, which is the kind of defect this whole pass exists to stop shipping.
         *
         * Every `.udea.kts` in the repository is CRLF today, so this is not a hypothetical
         * portability nicety: without it, pass 1 is wrong on the actual corpus. The content
         * hash is deliberately still taken over the *raw* bytes - a file's identity is its
         * bytes; only the parser needs the normalised view.
         */
        public fun normalizeLineEndings(text: String): String =
            if (CR !in text) text else text.replace(CRLF, LFS).replace(CR, LF)

        private const val CR: Char = '\r'
        private const val LF: Char = '\n'
        private const val CRLF: String = "\r\n"
        private const val LFS: String = "\n"

        private const val NAME_ARGUMENT = "name"
        private const val REFERENCE_CALLEE = "reference"
        private const val IMPLICIT_PARAMETER = "it"

        /**
         * Lambda-taking calls that *contain* declarations rather than being one.
         *
         * `bundle` is here for the current tree only: issue #86 makes the file itself the
         * bundle, and the migrator deletes the wrapper. Until then, refusing to look inside it
         * would mean pass 1 sees nothing at all in seventeen of the nineteen example scripts.
         */
        public val STRUCTURAL_CALLEES: Set<String> =
            setOf("bundle", "repeat", "run", "apply", "with", "also", "let")

        /** Selector calls that iterate a receiver and bind a loop variable. */
        public val ITERATION_CALLEES: Set<String> = setOf("forEach", "forEachIndexed", "onEach")

        /** Constructors of a constant collection the `forEach` form may iterate. */
        public val COLLECTION_CALLEES: Set<String> = setOf("listOf", "setOf", "arrayOf", "mutableListOf")

        private val HEX = "0123456789abcdef".toCharArray()

        /** sha256 of [bytes] as lowercase hex. */
        public fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val out = CharArray(digest.size * 2)
            for (i in digest.indices) {
                out[i * 2] = HEX[(digest[i].toInt() shr 4) and 0xF]
                out[i * 2 + 1] = HEX[digest[i].toInt() and 0xF]
            }
            return String(out)
        }

        /**
         * The text of a literal template entry, with the escapes Kotlin allows in one.
         *
         * Returns null for an escape this pass will not guess at, so an exotic literal
         * degrades to [AssetCompilerRules.NON_LITERAL_ID] rather than to a wrong id.
         */
        private fun String.unescape(): String? {
            if ('\\' !in this) return this
            val out = StringBuilder()
            var i = 0
            while (i < length) {
                val c = this[i]
                if (c != '\\') {
                    out.append(c)
                    i++
                    continue
                }
                if (i + 1 >= length) return null
                when (val escaped = this[i + 1]) {
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    '\\' -> out.append('\\')
                    '"' -> out.append('"')
                    '\'' -> out.append('\'')
                    '$' -> out.append('$')
                    else -> if (escaped == 'u' && i + 5 < length) {
                        out.append(substring(i + 2, i + 6).toInt(16).toChar())
                        i += 4
                    } else {
                        return null
                    }
                }
                i += 2
            }
            return out.toString()
        }

        /** The trailing or last-argument lambda of [call], if it has one. */
        private fun lambdaOf(call: KtCallExpression): KtLambdaExpression? =
            call.lambdaArguments.firstOrNull()?.getLambdaExpression()
                ?: call.valueArguments.asSequence()
                    .filter { it !is KtLambdaArgument }
                    .mapNotNull { it.getArgumentExpression() as? KtLambdaExpression }
                    .lastOrNull()

        /** The statements of [call]'s lambda argument, if it has one. */
        private fun lambdaBodyOf(call: KtCallExpression): List<KtExpression>? =
            lambdaOf(call)?.bodyExpression?.statements
    }
}
