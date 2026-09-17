package dev.wildware.udea.assets.pack

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The bundle's content hash is SHA-256 on every target (issue #205).
 *
 * The digests are the published FIPS 180-4 examples and the NIST long-message vector, so the
 * expected values come from outside this repository rather than from the implementation under
 * test. `Sha256JvmCrossCheckTest` compares against the JDK's own digest at every padding boundary.
 */
class Sha256Test {

    @Test
    fun `the empty message`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.digest(ByteArray(0)).hex(),
        )
    }

    @Test
    fun `the one-block FIPS example abc`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.digest("abc".encodeToByteArray()).hex(),
        )
    }

    @Test
    fun `the two-block FIPS example - whose padding spills into a second block`() {
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).hex(),
        )
    }

    @Test
    fun `a million letter a bytes fed in uneven chunks`() {
        val digest = Sha256()
        var fed = 0
        var chunk = 1
        while (fed < MILLION) {
            val length = minOf(chunk, MILLION - fed)
            digest.update(ByteArray(length) { 'a'.code.toByte() })
            fed += length
            chunk = chunk * 3 % 997 + 1
        }

        assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", digest.digest().hex())
    }

    private fun ByteArray.hex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        const val MILLION = 1_000_000
    }
}
