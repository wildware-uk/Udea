package dev.wildware.udea.replay

import java.util.Random
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [ReplayFormat.crc32] against `java.util.zip.CRC32`, the checksum every `.udearep` recorded
 * before issue #206 carries.
 *
 * The common checksum is a pure-Kotlin table so Wasm and Android can write a recording; this is
 * where it is held to the implementation it replaced, over lengths either side of every byte
 * boundary a table-driven loop could get wrong and over a prefix shorter than the buffer.
 */
class Crc32ParityTest {

    @Test
    fun `the common checksum agrees with java util zip on every length and prefix`() {
        // Seeded, so a failure names a buffer that can be rebuilt.
        val random = Random(0x206L)
        for (length in 0..1100) {
            val bytes = ByteArray(length + 7).also(random::nextBytes)
            val zip = CRC32().apply { update(bytes, 0, length) }.value
            assertEquals(zip, ReplayFormat.crc32(bytes, length), "length $length")
        }
    }
}
