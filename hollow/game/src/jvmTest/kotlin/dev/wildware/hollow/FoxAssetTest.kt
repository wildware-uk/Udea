package dev.wildware.hollow

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hollow's copy of the Khronos fox is the same file as moba's, and the one its notice names
 * (issue #251). `HumanAssetTest` is the same guard over the human, and says at length why a copy
 * with nothing watching it drifts.
 */
class FoxAssetTest {

    @Test
    fun `the fox is byte for byte moba's fox and the Khronos file the notice records`() {
        val hollow = projectDir().resolve(FOX)
        val moba = projectDir().resolve("../../moba/game").normalize().resolve(FOX)
        assertTrue(Files.isRegularFile(moba), "moba's fox is not at $moba")

        assertEquals(sha256(moba), sha256(hollow), "Hollow's fox differs from moba's")
        assertEquals(KHRONOS_SHA, sha256(hollow), "Hollow's fox is not the Khronos sample file")

        val notice = Files.readString(hollow.resolveSibling("NOTICE.md"))
        assertTrue(KHRONOS_SHA in notice, "the notice no longer records the file's hash")
        assertTrue("CC BY 4.0" in notice, "the notice no longer states the licence")
        assertTrue("FoxAssetTest" in notice, "the notice no longer names the test that keeps the copies equal")
    }

    private fun sha256(file: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)).joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }

    private fun projectDir(): Path = Path.of(
        checkNotNull(System.getProperty(PROJECT_DIR)) {
            "-D$PROJECT_DIR is not set; hollow/game/build.gradle.kts sets it on every Test task"
        },
    )

    private companion object {
        const val PROJECT_DIR = "udea.hollow.projectDir"

        const val FOX = "assets/models/fox/Fox.glb"

        /** `Models/Fox/glTF-Binary/Fox.glb` at the Khronos commit the notice names. */
        const val KHRONOS_SHA = "d97044e701822bac5a62696459b27d7b375aada5de8574ed4362edbba94771f7"
    }
}
