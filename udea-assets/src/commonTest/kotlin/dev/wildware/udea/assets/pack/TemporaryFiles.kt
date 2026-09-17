package dev.wildware.udea.assets.pack

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

/**
 * Runs [block] on a file holding [bytes] in the system temporary directory, and deletes it after.
 *
 * Through kotlinx-io rather than `java.nio`, so the file-backed reader is exercised by the same
 * test on every target that has a file system: the JVM, Android host tests, Node for wasmJs, and
 * iOS.
 */
internal fun withTemporaryFile(prefix: String, bytes: ByteArray, block: (Path) -> Unit) {
    // A name no concurrent test run shares. Not simulation code: nothing here is replayed.
    val file = Path(SystemTemporaryDirectory, "$prefix-${Random.nextLong().toULong()}.udeapak")
    SystemFileSystem.sink(file).buffered().use { it.write(bytes) }
    try {
        block(file)
    } finally {
        SystemFileSystem.delete(file)
    }
}
