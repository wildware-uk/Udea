package dev.wildware.udea.assets.pack

import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The reader makes of [GoldenPak] exactly what it made before the multiplatform port (issue #205).
 *
 * In `commonTest`, so every target holds its own reader to the pre-port JVM reader's output.
 *
 * Both ways a bundle is opened: from bytes already in memory, and from a file whose sections are
 * read on demand. They are separate sources with separate range arithmetic, so each is held to
 * the golden on its own.
 */
class GoldenPakTest {

    @Test
    fun `a pak opened from memory reads as it did before the port`() {
        val bytes = GoldenPak.bytes

        val described = BundleReader.open(bytes).use { describeBundle(it, BundleSource.of(bytes)) }

        assertEquals(GOLDEN_PAK_EXPECTED, described)
    }

    @Test
    fun `a pak opened from a file reads as it did before the port`() {
        withTemporaryFile("golden", GoldenPak.bytes) { file: Path ->
            val described = BundleSource.of(file).use { source ->
                BundleReader.open(file).use { describeBundle(it, source) }
            }

            assertEquals(GOLDEN_PAK_EXPECTED, described)
        }
    }
}
