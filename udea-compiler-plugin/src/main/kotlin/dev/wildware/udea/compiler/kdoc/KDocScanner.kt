package dev.wildware.udea.compiler.kdoc

/**
 * Finds the doc comment attached to a declaration, given the file text and where the
 * declaration starts.
 *
 * ### Why it does not go through PSI
 *
 * `KtDeclaration.docComment` is the obvious route and it only works half the time: the K2 CLI
 * parses with the *light tree* by default, where a FIR declaration's source element carries a
 * `LighterASTNode` and no PSI at all. A harvester that read `source.psi` would silently
 * harvest nothing on a normal Gradle build and everything in a PSI-mode test - the worst
 * possible failure, because the test would be green.
 *
 * Offsets are available in both modes, so this works from the file text and an offset and is
 * indifferent to how the file was parsed. It also tolerates the two shapes a declaration's
 * range can have: including the doc comment (PSI, where the KDoc is a child of the
 * declaration) or starting after it.
 */
internal object KDocScanner {

    private const val OPEN = "/**"
    private const val CLOSE = "*/"

    /**
     * The doc comment for the declaration starting at [declarationStart], or `null`.
     *
     * @param fileText the whole source file.
     * @param declarationStart the declaration's start offset, as FIR reports it.
     */
    fun docCommentAt(fileText: String, declarationStart: Int): String? {
        if (declarationStart < 0 || declarationStart > fileText.length) return null
        if (fileText.startsWith(OPEN, declarationStart)) {
            return blockAt(fileText, declarationStart)
        }
        var index = declarationStart - 1
        while (index >= 0 && fileText[index].isWhitespace()) index--
        // `index` now sits on the last non-space character before the declaration. A doc
        // comment ends with `*/`, so anything else means there is no comment to harvest -
        // including an annotation, which is what makes the "range excludes the KDoc" case
        // safe: `@Replicated` does not end in `*/`.
        if (index < OPEN.length || fileText[index] != '/' || fileText[index - 1] != '*') return null
        val end = index + 1
        val start = fileText.lastIndexOf(OPEN, end - CLOSE.length)
        if (start < 0) return null
        val block = blockAt(fileText, start) ?: return null
        // Guards against `/** a */ // b */`-shaped text, where walking back would otherwise
        // pair an opening delimiter with a closing one that is not its own.
        return if (start + block.length == end) block else null
    }

    /** The complete comment beginning at [start], or `null` if it is unterminated. */
    private fun blockAt(fileText: String, start: Int): String? {
        val close = fileText.indexOf(CLOSE, start + OPEN.length)
        if (close < 0) return null
        return fileText.substring(start, close + CLOSE.length)
    }
}
