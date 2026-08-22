package dev.wildware.udea.compiler.kdoc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Finding the comment, in both of the shapes a FIR declaration's source range can have.
 *
 * The PSI parser makes a KDoc a child of the declaration, so the range starts at the
 * opening delimiter. The light-tree parser - what the K2 CLI uses by default - can hand back a range that starts
 * after it. A harvester that handled only one of those would work in tests and harvest nothing
 * in a real build, so both are pinned here.
 */
class KDocScannerTest {

    @Test
    fun `finds a comment the declaration range starts with`() {
        val text = "/** Health. */\nclass Health"

        assertEquals("/** Health. */", KDocScanner.docCommentAt(text, 0))
    }

    @Test
    fun `finds a comment immediately before the declaration range`() {
        val text = "/** Health. */\nclass Health"
        val classKeyword = text.indexOf("class")

        assertEquals("/** Health. */", KDocScanner.docCommentAt(text, classKeyword))
    }

    @Test
    fun `finds a comment separated from the declaration by blank lines`() {
        val text = "/** Health. */\n\n\n    class Health"

        assertEquals("/** Health. */", KDocScanner.docCommentAt(text, text.indexOf("class")))
    }

    @Test
    fun `does not mistake an annotation for a comment`() {
        val text = "@Replicated\nclass Health"

        assertNull(KDocScanner.docCommentAt(text, text.indexOf("class")))
    }

    @Test
    fun `does not mistake an ordinary block comment for a doc comment`() {
        val text = "/* not a doc comment */\nclass Health"

        assertNull(KDocScanner.docCommentAt(text, text.indexOf("class")))
    }

    @Test
    fun `does not mistake a line comment for a doc comment`() {
        val text = "// not a doc comment\nclass Health"

        assertNull(KDocScanner.docCommentAt(text, text.indexOf("class")))
    }

    @Test
    fun `an unterminated comment is not a comment`() {
        assertNull(KDocScanner.docCommentAt("/** unterminated\nclass Health", 0))
    }

    @Test
    fun `a declaration at the start of the file has no preceding comment`() {
        assertNull(KDocScanner.docCommentAt("class Health", 0))
    }

    @Test
    fun `an out-of-range offset is not a crash`() {
        val text = "class Health"

        assertNull(KDocScanner.docCommentAt(text, -1))
        assertNull(KDocScanner.docCommentAt(text, text.length + 10))
    }

    @Test
    fun `the second of two comments is the one that belongs to the declaration`() {
        val text = "/** first */\n/** second */\nclass Health"

        assertEquals("/** second */", KDocScanner.docCommentAt(text, text.indexOf("class")))
    }
}
