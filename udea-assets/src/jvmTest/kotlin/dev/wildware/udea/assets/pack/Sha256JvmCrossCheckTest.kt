package dev.wildware.udea.assets.pack

import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.test.assertEquals

/**
 * The portable [Sha256] agrees with the JDK's `MessageDigest`, which is what `.udeapak` content
 * hashes were computed with before issue #205 and what `udea-assets-compiler`'s writer still uses.
 *
 * Every length from empty through three blocks, so each padding case is covered: a message that
 * leaves room for the length in its last block, one that does not (lengths 56 to 63 in a block),
 * and one that ends exactly on a block boundary.
 */
class Sha256JvmCrossCheckTest {

    @Test
    fun `every length up to three blocks digests as the JDK does`() {
        for (length in 0..3 * BLOCK) {
            val message = ByteArray(length) { (it * 131 + length).toByte() }

            assertEquals(
                MessageDigest.getInstance("SHA-256").digest(message).toList(),
                Sha256.digest(message).toList(),
                "SHA-256 of a $length-byte message",
            )
        }
    }

    private companion object {
        const val BLOCK = 64
    }
}
