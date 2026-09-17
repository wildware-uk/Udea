package dev.wildware.udea.assets.pack

/**
 * SHA-256 (FIPS 180-4), written in common Kotlin so a bundle's content hash is the same function
 * on every target (issue #205).
 *
 * Until the port this was `java.security.MessageDigest`, which exists only on the JVM. The
 * content hash is not a security boundary - it is how a hot-reload handshake or an installer
 * notices that two bundles differ (`BundleReader.verifyContentHash`) - but its bytes are written
 * into every `.udeapak` header by the JVM writer, so the algorithm cannot change. Written out
 * rather than taken from a library because `UDEA-MG-006` keeps this module's dependencies to a
 * short named list, and a digest checked against the published vectors (`Sha256Test`) and
 * against the JDK at every padding boundary (`Sha256JvmCrossCheckTest`) is cheaper than widening
 * it for a second library.
 *
 * Incremental: [update] any number of times, then [digest] once.
 */
internal class Sha256 {

    private val state = INITIAL_STATE.copyOf()
    private val block = ByteArray(BLOCK_BYTES)
    private val words = IntArray(ROUNDS)
    private var blockFill = 0
    private var messageBytes = 0L
    private var finished = false

    fun update(bytes: ByteArray) {
        check(!finished) { "this digest has already been computed; start a new Sha256" }
        for (byte in bytes) {
            block[blockFill++] = byte
            if (blockFill == BLOCK_BYTES) {
                compress()
                blockFill = 0
            }
        }
        messageBytes += bytes.size
    }

    /** The 32-byte digest of everything [update] was given. */
    fun digest(): ByteArray {
        check(!finished) { "this digest has already been computed; start a new Sha256" }
        finished = true
        val messageBits = messageBytes * Byte.SIZE_BITS
        // The padding: one set bit, zeros to 56 bytes into a block, then the length in bits.
        block[blockFill++] = PADDING_MARKER
        if (blockFill > LENGTH_OFFSET) {
            block.fill(0, blockFill, BLOCK_BYTES)
            compress()
            blockFill = 0
        }
        block.fill(0, blockFill, LENGTH_OFFSET)
        for (i in 0 until Long.SIZE_BYTES) {
            block[LENGTH_OFFSET + i] = (messageBits ushr ((Long.SIZE_BYTES - 1 - i) * Byte.SIZE_BITS)).toByte()
        }
        compress()
        return ByteArray(DIGEST_BYTES) { i ->
            (state[i / Int.SIZE_BYTES] ushr ((Int.SIZE_BYTES - 1 - i % Int.SIZE_BYTES) * Byte.SIZE_BITS)).toByte()
        }
    }

    private fun compress() {
        for (t in 0 until BLOCK_WORDS) {
            val at = t * Int.SIZE_BYTES
            words[t] = ((block[at].toInt() and 0xFF) shl 24) or
                ((block[at + 1].toInt() and 0xFF) shl 16) or
                ((block[at + 2].toInt() and 0xFF) shl 8) or
                (block[at + 3].toInt() and 0xFF)
        }
        for (t in BLOCK_WORDS until ROUNDS) {
            val s0 = words[t - 2]
            val s1 = words[t - 15]
            words[t] = words[t - 16] +
                (s1.rotateRight(7) xor s1.rotateRight(18) xor (s1 ushr 3)) +
                words[t - 7] +
                (s0.rotateRight(17) xor s0.rotateRight(19) xor (s0 ushr 10))
        }
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        for (t in 0 until ROUNDS) {
            val t1 = h + (e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)) +
                ((e and f) xor (e.inv() and g)) + ROUND_CONSTANTS[t] + words[t]
            val t2 = (a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)) +
                ((a and b) xor (a and c) xor (b and c))
            h = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }
        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
    }

    companion object {

        /** The digest of [bytes] in one call. */
        fun digest(bytes: ByteArray): ByteArray = Sha256().apply { update(bytes) }.digest()

        private const val BLOCK_BYTES = 64
        private const val DIGEST_BYTES = 32
        private const val ROUNDS = 64

        /** A block is this many big-endian 32-bit words; the rest of the schedule is derived from them. */
        private const val BLOCK_WORDS = BLOCK_BYTES / Int.SIZE_BYTES

        /** Where the 64-bit message length starts in the final block. */
        private const val LENGTH_OFFSET = BLOCK_BYTES - Long.SIZE_BYTES

        /** `0x80`: the single set bit that follows the message. */
        private const val PADDING_MARKER: Byte = -0x80

        /** FIPS 180-4 section 5.3.3: the first 32 bits of the fractional parts of the square roots of the first eight primes. */
        private val INITIAL_STATE = intArrayOf(
            0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6,
            0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19,
        )

        /** FIPS 180-4 section 4.2.2: the first 32 bits of the fractional parts of the cube roots of the first 64 primes. */
        private val ROUND_CONSTANTS = longArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
        ).map { it.toInt() }.toIntArray()
    }
}
