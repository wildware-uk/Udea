package dev.wildware.udea.assets.pack

import kotlinx.io.files.FileNotFoundException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A file-backed [BundleSource] answers any range, in any order (issue #205).
 *
 * kotlinx-io reads a file front to back and has no seek, so the source keeps a read head and
 * reopens the file when asked for bytes behind it. Opening a bundle only ever reads forwards, and
 * [GoldenPakTest] is that path; these are the orders it does not take.
 */
class FileBundleSourceTest {

    private val bytes = ByteArray(SIZE) { (it * 37 + 11).toByte() }

    @Test
    fun `a range behind the read head is the same bytes as the first time`() {
        withTemporaryFile("ranges", bytes) { file ->
            BundleSource.of(file).use { source ->
                val late = source.read(12_000, 5_000)
                val early = source.read(10, 50)
                val lateAgain = source.read(12_000, 5_000)

                assertContentEquals(bytes.copyOfRange(12_000, 17_000), late)
                assertContentEquals(bytes.copyOfRange(10, 60), early)
                assertContentEquals(late, lateAgain)
            }
        }
    }

    @Test
    fun `adjacent and repeated ranges - and the last byte`() {
        withTemporaryFile("adjacent", bytes) { file ->
            BundleSource.of(file).use { source ->
                assertEquals(SIZE.toLong(), source.size)
                assertContentEquals(bytes.copyOfRange(0, 4), source.read(0, 4))
                assertContentEquals(bytes.copyOfRange(4, 8), source.read(4, 4))
                assertContentEquals(bytes.copyOfRange(4, 8), source.read(4, 4))
                assertContentEquals(bytes.copyOfRange(SIZE - 1, SIZE), source.read(SIZE - 1L, 1))
                assertContentEquals(ByteArray(0), source.read(SIZE.toLong(), 0))
            }
        }
    }

    @Test
    fun `a range past the end is a corrupt bundle - not a short read`() {
        withTemporaryFile("overrun", bytes) { file ->
            BundleSource.of(file).use { source ->
                val failure = assertFailsWith<BundleCorruptException> { source.read(SIZE - 10L, 11) }

                assertEquals(
                    "this .udeapak is corrupt: 11 byte(s) at ${SIZE - 10} are outside a $SIZE-byte bundle",
                    failure.message,
                )
            }
        }
    }

    @Test
    fun `a missing file fails when it is opened - naming the path`() {
        val missing = Path(SystemTemporaryDirectory, "udea-assets-no-such-bundle.udeapak")
        check(!SystemFileSystem.exists(missing)) { "$missing exists, so this test cannot say anything" }

        val failure = assertFailsWith<FileNotFoundException> { BundleSource.of(missing) }

        assertEquals(true, failure.message?.contains(missing.name), "the message was '${failure.message}'")
    }

    private companion object {
        /** Larger than two of kotlinx-io's 8KiB buffer segments, so a read can straddle one. */
        const val SIZE = 20_000
    }
}
