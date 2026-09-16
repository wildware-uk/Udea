package dev.wildware.udea.assets.pack

import kotlinx.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise

/**
 * Fetches the `.udeapak` at [url] over HTTP and opens it (issue #205).
 *
 * On Wasm a "file" is an HTTP fetch or an in-memory buffer (spec section 6), and a browser has no
 * file system to stream sections from. So the whole bundle is fetched once, copied into a
 * `ByteArray`, and opened with [BundleReader.open] like any other bytes - which is why the table
 * of contents' eager/streamed split changes nothing here: every section is already in memory.
 *
 * Uses the global `fetch` that browsers and Node both provide, and a relative [url] resolves as
 * `fetch` resolves it. `BundleFetchTest` exercises it on Node, against a real HTTP server. The bundle is decoded on the calling thread
 * once the body has arrived, and Wasm has only that one.
 *
 * @throws IOException when the request fails to connect, or answers with a status that is not
 *   2xx - naming [url] and, for the latter, the status. Never a half-read bundle.
 * @throws BundleException when the body arrived and is not a readable bundle.
 */
public suspend fun BundleReader.fetch(url: String, codecs: AssetCodecs = AssetCodecs.Builtin): Bundle =
    open(fetchBytes(url), codecs)

private suspend fun fetchBytes(url: String): ByteArray {
    val response = await(fetchUrl(url)) { error -> IOException("fetching $url failed: $error") }
    if (!response.ok) {
        throw IOException("fetching $url answered HTTP ${response.status} ${response.statusText}".trimEnd())
    }
    val body = await(responseBytes(response)) { error -> IOException("reading the body of $url failed: $error") }
    // One call across the boundary per byte: Wasm GC arrays are not visible to JavaScript, so
    // there is no bulk copy into a ByteArray to call instead.
    return ByteArray(body.length) { byteAt(body, it) }
}

private suspend fun <T : JsAny> await(promise: Promise<T>, failure: (JsAny) -> IOException): T =
    suspendCoroutine { continuation ->
        promise.then(
            onFulfilled = { value ->
                continuation.resume(value)
                null
            },
            onRejected = { error ->
                continuation.resumeWithException(failure(error))
                null
            },
        )
    }

/** The parts of a Fetch API `Response` this file reads. */
private external interface FetchResponse : JsAny {
    val ok: Boolean
    val status: Int
    val statusText: String
}

/** A JavaScript `Int8Array`. */
private external interface Int8Bytes : JsAny {
    val length: Int
}

@JsFun("(url) => fetch(url)")
private external fun fetchUrl(url: String): Promise<FetchResponse>

@JsFun("(response) => response.arrayBuffer().then((buffer) => new Int8Array(buffer))")
private external fun responseBytes(response: FetchResponse): Promise<Int8Bytes>

@JsFun("(bytes, index) => bytes[index]")
private external fun byteAt(bytes: Int8Bytes, index: Int): Byte
