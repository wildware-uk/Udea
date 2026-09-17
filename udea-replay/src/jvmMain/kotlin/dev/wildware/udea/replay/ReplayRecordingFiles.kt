package dev.wildware.udea.replay

import java.nio.file.Files
import java.nio.file.Path

// A recording's bytes are common code and its files are the JVM's (issue #206). `encode` and
// `decode` are what every target shares; what a "file" is on Wasm - a fetch, a buffer - is the
// caller's to decide, so only the JVM gets these two.

/** Writes [ReplayRecording.encode] to [path], creating parent directories. */
public fun ReplayRecording.writeTo(path: Path) {
    path.parent?.let(Files::createDirectories)
    Files.write(path, encode())
}

/** Reads a `.udearep` from [path]. */
public fun ReplayRecording.Companion.readFrom(path: Path): ReplayRecording =
    ReplayRecording.decode(Files.readAllBytes(path))
