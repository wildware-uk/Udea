package dev.wildware.udea.diagnostics

/**
 * A single textual edit: replace the text at [span] with [newText].
 *
 * An insertion is a zero-width span; a deletion is an empty [newText].
 */
public data class Replacement(
    public val span: SourceSpan,
    public val newText: String,
)

/**
 * A machine-applicable repair for a diagnostic.
 *
 * [description] is written for an agent to read and act on in one turn ("change `val` to
 * `var`"), not for a human to skim. [replacements] must be non-overlapping; applying them
 * all is the whole fix, and applying a subset is not defined.
 */
public data class Fix(
    public val description: String,
    public val replacements: List<Replacement>,
) {
    init {
        require(description.isNotBlank()) { "Fix.description must not be blank" }
    }
}
