package dev.wildware.udea.assets.compiler.edit

import dev.wildware.udea.diagnostics.SourceSpan
import java.nio.file.Path

/**
 * Why a field cannot be saved from the editor, and the line to go and change it on.
 *
 * [message] reads as the end of "read-only: ...", e.g. "set by `val soldierScale` on line 8", and
 * always carries [line]: the issue's second criterion is a refusal with the reason *and* the line.
 */
public data class ReadOnlyReason(
    public val message: String,
    /** 1-based: the line of the declaration that sets the value, or of the expression that does. */
    public val line: Int,
)

/** Whether the editor can save a field, and in what type, or why not. */
public sealed class Editability {

    /** A plain literal: the editor may replace it with another value of [value]'s type. */
    public data class Editable(public val value: LiteralValue) : Editability()

    /**
     * Anything else. [constant] is the plain literal the value comes to when it is the name of a
     * file-level `val` holding one - `soldierScale` is `1.58F` - which is what a new asset copies in
     * its place, since a new file does not carry the old one's constants.
     */
    public data class ReadOnly(
        public val reason: ReadOnlyReason,
        internal val constant: LiteralValue? = null,
    ) : Editability()
}

/**
 * One argument of an asset's declaring call, as the file writes it.
 *
 * [span] is the argument's value, exactly: `6` in `columns = 6`, `"a"` in `path = "a"`,
 * `reference("x")` in `sheet = reference("x")`. It is the text the editor's save replaces.
 */
public class FieldSite internal constructor(
    /** The parameter name, `columns`; `argument 2` for one passed by position. */
    public val name: String,
    /** The value's text, exactly as the file has it. */
    public val text: String,
    public val span: SourceSpan,
    public val editability: Editability,
    /** Where, in the file's raw text, a save writes: start inclusive, end exclusive. */
    internal val start: Int,
    internal val end: Int,
) {
    override fun toString(): String = "FieldSite($name = $text at ${span.path}:${span.startLine}, $editability)"
}

/** What [AssetSource.set] did, or why it did nothing. */
public sealed class SetResult {

    /** A sentence for a person or an agent: what happened, and what to do about a refusal. */
    public abstract val message: String

    override fun toString(): String = "${this::class.simpleName}: $message"

    /** [text] is the whole file, with [site]'s value replaced by [value] and nothing else moved. */
    public class Changed(
        public val text: String,
        internal val site: FieldSite,
        internal val value: LiteralValue,
    ) : SetResult() {
        override val message: String get() = "`${site.name}` = ${value.expression()} on line ${site.span.startLine}"
    }

    public class ReadOnly internal constructor(internal val name: String, internal val reason: ReadOnlyReason) : SetResult() {
        override val message: String get() = "`$name` is read-only: ${reason.message}"
    }

    public class NotParseable internal constructor(internal val name: String, internal val type: LiteralType, internal val input: String) : SetResult() {
        override val message: String get() = "`$name` holds a $type, and `$input` is not one"
    }

    public class NoSuchField internal constructor(internal val name: String, internal val fields: List<String>) : SetResult() {
        override val message: String get() =
            "this declaration does not write `$name`; it writes ${fields.joinToString { "`$it`" }}. " +
                "A field the file leaves at its default has no text to replace: write it into the file first."
    }
}

/**
 * One asset's declaring call, read syntactically out of the text of its script.
 *
 * Produced by [AssetSources.read] - pass 1's parser, no classpath and nothing compiled, so it costs
 * a parse and answers before any compile does.
 */
public class AssetSource internal constructor(
    /** `character/soldier_idle_sheet`, as pass 1 computes it. */
    public val id: String,
    /** The declaring function: `spriteSheet`. */
    public val kind: String,
    /** The script, absolute. */
    public val file: Path,
    /** The script, repo-relative and `/`-separated, as every [SourceSpan] spells it. */
    public val path: String,
    /** Every argument of the call, in the order the file writes them. */
    public val fields: List<FieldSite>,
    /** The file's text the fields were read out of, line endings as they are on disk. */
    private val text: String,
) {

    /**
     * The file's text with [field] set to [input], and nothing else changed.
     *
     * The value's own text - the span pass 1 recorded - is swapped for the new literal, so every
     * comment, blank line and piece of spacing in the file is the same bytes afterwards. A field
     * that is not a plain literal is refused with the reason and the line to change instead.
     */
    public fun set(field: String, input: String): SetResult {
        val site = fields.firstOrNull { it.name == field } ?: return SetResult.NoSuchField(field, fields.map { it.name })
        val current = when (val editability = site.editability) {
            is Editability.ReadOnly -> return SetResult.ReadOnly(field, editability.reason)
            is Editability.Editable -> editability.value
        }
        val value = LiteralValue.parse(current.type, input) ?: return SetResult.NotParseable(field, current.type, input)
        // A splice of the file, not code assembled from pieces: the one new piece is KotlinPoet's.
        val replaced = StringBuilder(text).replace(site.start, site.end, value.replacement().toString())
        return SetResult.Changed(replaced.toString(), site, value)
    }

    override fun toString(): String = "AssetSource($id, $kind, ${fields.size} fields)"
}
