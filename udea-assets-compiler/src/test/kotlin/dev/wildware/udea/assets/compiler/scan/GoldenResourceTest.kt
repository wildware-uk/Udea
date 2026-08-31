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
        val committed = GoldenResource.bytes(GoldenResource.EXAMPLE_DECLARATIONS)
        val translated = committed.toString(Charsets.UTF_8).replace("\n", "\r\n").toByteArray()

        val failure = assertFailsWith<IllegalStateException> {
            GoldenResource.untranslated(GoldenResource.EXAMPLE_DECLARATIONS, translated)
        }

        val message = failure.message.orEmpty()
        assertTrue(GoldenResource.EXAMPLE_DECLARATIONS in message, message)
        assertTrue("core.autocrlf" in message, message)
        assertTrue(".gitattributes" in message, message)
        // The count, so the failure says how much was translated rather than only that it was.
        assertTrue("${committed.toString(Charsets.UTF_8).count { it == '\n' }} carriage" in message, message)
    }

    @Test
    fun `an untranslated copy is returned unchanged`() {
        // The control for the test above. A fence that refuses everything is as wrong as one
        // that refuses nothing, and this is the case the whole suite runs on every green build.
        val committed = GoldenResource.bytes(GoldenResource.EXAMPLE_DECLARATIONS)

        assertEquals(
            committed.toString(Charsets.UTF_8),
            GoldenResource.untranslated(GoldenResource.EXAMPLE_DECLARATIONS, committed),
        )
    }
}
