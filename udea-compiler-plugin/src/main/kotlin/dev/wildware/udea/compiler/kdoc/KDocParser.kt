package dev.wildware.udea.compiler.kdoc

/**
 * Turns the raw text of a KDoc comment into a [KDocBlock].
 *
 * A pure function of a string, on purpose: the FIR half of the harvester is awkward to drive
 * from a test and this is the half that holds all the decisions, so it is tested directly and
 * exhaustively rather than through a compilation.
 */
internal object KDocParser {

    /**
     * The tags re-emitted into generated code, besides `@param`.
     *
     * `@param` is handled separately because its first token is a name rather than prose.
     * Everything else - `@sample`, `@constructor`, `@property`, `@suppress`, a typo - is
     * dropped: issue #42 requires dropping rather than emitting malformed KDoc into a
     * generated file, and a tag whose text names something the generated file cannot see is
     * exactly that.
     */
    val PASS_THROUGH_TAGS: Set<String> = setOf("return", "see", "throws")

    private const val PARAM_TAG = "param"

    private val TAG_START = Regex("""^@(\w+)\b\s*(.*)$""")

    /**
     * Parses [raw], which must be the whole comment including its opening and closing
     * delimiters.
     *
     * @return the block, or `null` when [raw] is not a KDoc comment at all. A comment that
     *   parses to nothing returns an empty [KDocBlock] rather than `null`, so a caller can
     *   tell "no doc comment" from "a doc comment with nothing in it".
     */
    fun parse(raw: String): KDocBlock? {
        if (!raw.startsWith("/**") || !raw.endsWith("*/") || raw.length < 5) return null
        val builder = Builder()
        for (line in raw.substring(3, raw.length - 2).lineSequence()) {
            builder.accept(stripMargin(line))
        }
        return builder.build()
    }

    /**
     * Line-at-a-time accumulator.
     *
     * A class rather than a fistful of captured `var`s so that "which section does this line
     * belong to" is one nullable field ([section]) instead of three that can disagree.
     */
    private class Builder {
        private val summary = StringBuilder()
        private val params = mutableListOf<KDocParam>()
        private val tags = mutableListOf<KDocTag>()

        /** The tag currently being read, or `null` while still in the summary. */
        private var section: Section? = null

        private class Section(val tag: String, val name: String?) {
            val text: StringBuilder = StringBuilder()
        }

        fun accept(line: String) {
            val match = TAG_START.find(line)
            if (match == null) {
                append(line)
                return
            }
            close()
            val (tag, rest) = match.destructured
            section = when (tag) {
                PARAM_TAG -> {
                    val name = rest.substringBefore(' ').trim()
                    // `@param` with no name documents nothing and has no identity in the
                    // index, so it is dropped like any unsupported tag.
                    val section = Section(tag, name.ifEmpty { null })
                    section.text.append(rest.removePrefix(name).trim())
                    section
                }

                else -> Section(tag, null).also { it.text.append(rest.trim()) }
            }
        }

        fun build(): KDocBlock {
            close()
            return KDocBlock(
                summary = summary.toString().trim('\n', ' '),
                params = params.toList(),
                tags = tags.toList(),
            )
        }

        private fun append(line: String) {
            val target = section?.text ?: summary
            if (target.isNotEmpty()) target.append('\n')
            target.append(line)
        }

        /** Commits the open section, dropping it if it is not a tag we re-emit. */
        private fun close() {
            val open = section ?: return
            section = null
            val text = open.text.toString().trimEnd()
            when {
                open.tag == PARAM_TAG && open.name != null -> params += KDocParam(open.name, text)
                open.tag in PASS_THROUGH_TAGS -> tags += KDocTag(open.tag, text)
                // Anything else is dropped, along with its continuation lines - which is the
                // reason an unsupported tag still opens a section instead of being ignored.
            }
        }
    }

    /**
     * Strips one line's leading whitespace and its `*` margin.
     *
     * The spaces *after* the margin are kept, because indentation inside a KDoc is markdown -
     * a list continuation or a fenced block - and losing it turns a formatted comment into one
     * run-on paragraph.
     */
    private fun stripMargin(line: String): String {
        val trimmed = line.trimStart()
        if (!trimmed.startsWith('*')) return trimmed.trimEnd()
        val afterMargin = trimmed.substring(1)
        return (if (afterMargin.startsWith(' ')) afterMargin.substring(1) else afterMargin).trimEnd()
    }
}
