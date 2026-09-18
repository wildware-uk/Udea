package dev.wildware.moba.render

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * `java.util.zip.Inflater`, which the JVM and Android both have.
 *
 * One `actual` for two targets rather than two copies: the shared `jvmAndAndroidMain` source set
 * exists for exactly this, and an Android `java.util.zip` is the same class as a desktop one.
 */
internal actual fun inflateZlib(data: ByteArray, expectedSize: Int): ByteArray {
    require(expectedSize > 0) { "a zlib stream cannot be asked to inflate to $expectedSize bytes" }
    val inflater = Inflater()
    try {
        inflater.setInput(data)
        val out = ByteArray(expectedSize)
        var written = 0
        while (written < expectedSize) {
            val produced = try {
                inflater.inflate(out, written, expectedSize - written)
            } catch (failure: DataFormatException) {
                throw IllegalArgumentException("not a readable zlib stream: ${failure.message}", failure)
            }
            if (produced == 0) {
                // `needsInput` with bytes still owed means the stream ended early; `needsDictionary`
                // means a preset dictionary nobody supplied. Either way there is no more output
                // coming, and looping would spin.
                require(!inflater.needsInput() && !inflater.needsDictionary()) {
                    "the zlib stream ended after $written of $expectedSize bytes"
                }
            }
            written += produced
            if (inflater.finished()) break
        }
        require(written == expectedSize) {
            "the zlib stream inflated to $written bytes, and $expectedSize were expected"
        }
        return out
    } finally {
        inflater.end()
    }
}
