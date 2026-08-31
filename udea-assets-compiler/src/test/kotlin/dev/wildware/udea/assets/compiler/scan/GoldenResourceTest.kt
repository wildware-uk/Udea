package dev.wildware.udea.assets.compiler.scan

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The fence around the pass-1 golden (issue #176).
 *
 * Two questions, and they are different questions. The first is about this checkout: did the
 * golden reach the test suite as the bytes it was committed as. The second is about the fence
 * itself: if it did not, does anybody find out - and find out *why*, rather than being handed a
 * diff of two blocks that render identically because a carriage return draws as nothing.
 */
class GoldenResourceTest {

    @Test
    fun `the golden reached this checkout untranslated`() {
        val text = GoldenResource.bytes(GoldenResource.EXAMPLE_DECLARATIONS).toString(Charsets.UTF_8)

        assertEquals(0, text.count { it == '\r' }, "the golden on the classpath contains CRLF")
        assertTrue(text.contains('\n'), "the golden has no line breaks at all, so this asserts nothing")
    }

    @Test
    fun `a translated copy is refused, and the failure names the cause and the file`() {
        val failure = assertFailsWith<IllegalStateException> {
            GoldenResource.untranslated(GoldenResource.EXAMPLE_DECLARATIONS, TRANSLATED)
        }

        val message = failure.message.orEmpty()
        assertTrue(GoldenResource.EXAMPLE_DECLARATIONS in message, message)
        assertTrue("core.autocrlf" in message, message)
        assertTrue(".gitattributes" in message, message)
        // The count, so the failure says how much was translated rather than only that it was.
        assertTrue("3 carriage return(s)" in message, message)
    }

    @Test
    fun `an untranslated copy is returned unchanged`() {
        // The control for the test above, and it is not a formality: a fence that refuses
        // everything is as wrong as one that refuses nothing, and this is the case the rest of
        // the suite runs on every green build.
        assertEquals(UNTRANSLATED, GoldenResource.untranslated(GoldenResource.EXAMPLE_DECLARATIONS, COMMITTED))
    }

    private companion object {

        /**
         * A fixture rather than the real golden, on purpose.
         *
         * The two tests above are about the fence, not about this checkout - that is the first
         * test's job. Deriving them from the file on disk would make all three fail together
         * the moment one CRLF copy arrived, which reports one defect three times and buries the
         * one of them that is actually about the tree.
         */
        const val UNTRANSLATED: String = "{\n  \"files\": []\n}\n"

        val COMMITTED: ByteArray = UNTRANSLATED.toByteArray(Charsets.UTF_8)

        val TRANSLATED: ByteArray = UNTRANSLATED.replace("\n", "\r\n").toByteArray(Charsets.UTF_8)
    }
}
