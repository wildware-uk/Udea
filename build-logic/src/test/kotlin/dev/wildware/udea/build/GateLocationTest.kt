package dev.wildware.udea.build

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [gateLocation]: the one spelling every gate in this package prints a filesystem location in.
 *
 * The cases below are written as string pairs rather than as real files on purpose. A `File` on
 * Linux renders a Windows path unchanged and a `File` on Windows renders a POSIX one unchanged, so
 * a test built on `File` would assert nothing on the platform it is not running on - which is the
 * exact defect this function exists to close. Both spellings are fed in here, on every platform.
 *
 * They are a **pair**, and each half is here because the other cannot catch its mutation. A test
 * whose whole job is to find an absence of backslashes is the family that reads as a pass while
 * asserting nothing, so:
 *
 * - the Windows cases die if [gateLocation] becomes the identity function - the shape this
 *   repository shipped until now, and the one a refactor would quietly restore;
 * - the POSIX case dies if it over-reaches the other way and rewrites `/` into `\`.
 *
 * Neither mutation is caught by the other half, which is what makes running both worth the lines.
 */
class GateLocationTest {

    @Test
    fun `a Windows location is printed with forward slashes`() {
        assertEquals("D:/a/Udea/Udea/moba/editor", gateLocation("""D:\a\Udea\Udea\moba\editor"""))
    }

    @Test
    fun `a UNC location keeps both of its leading separators, turned round`() {
        assertEquals("//build/share/moba.jar", gateLocation("""\\build\share\moba.jar"""))
    }

    @Test
    fun `a POSIX location is passed through unchanged`() {
        assertEquals("/srv/ssd1/workspace/Udea/moba/editor", gateLocation("/srv/ssd1/workspace/Udea/moba/editor"))
    }

    @Test
    fun `a jar entry suffix survives, because that is how a gate names a class inside an archive`() {
        assertEquals("D:/a/moba.jar!/game/Ring.class", gateLocation("""D:\a\moba.jar!/game/Ring.class"""))
    }
}
