package dev.wildware.moba.render

/**
 * Inflates a zlib stream: the one thing [Png] cannot do in common code.
 *
 * ## Why this is an `expect` and not a library
 *
 * A PNG's pixel data is deflate-compressed, so decoding one needs an inflater, and there is no
 * multiplatform inflater on this build's dependency graph. Both of this module's targets are
 * JVM-family, so there is exactly one `actual` - `jvmAndAndroidMain`'s, over `java.util.zip` - and
 * it is a real implementation on both rather than one real one beside a `TODO()`. A `wasmJs` target
 * (issue #226) adds a second `actual`, and the browser has `DecompressionStream("deflate")` for it.
 *
 * @param data the whole zlib stream, header and checksum included.
 * @param expectedSize how many bytes the caller knows will come out. Used to size the output
 *   buffer exactly, so a decode allocates once; a stream that inflates to a different size is a
 *   corrupt PNG and [Png] is what reports it.
 * @throws IllegalArgumentException when [data] is not a readable zlib stream.
 */
internal expect fun inflateZlib(data: ByteArray, expectedSize: Int): ByteArray
