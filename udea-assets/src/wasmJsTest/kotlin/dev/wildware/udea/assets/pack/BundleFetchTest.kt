package dev.wildware.udea.assets.pack

import kotlinx.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * On Wasm a `.udeapak` loads from bytes fetched over HTTP (issue #205).
 *
 * Real HTTP, not a stub: each test starts a server with Node's own `http` module on a loopback
 * port the operating system picks, and [BundleReader.fetch] reaches it through the global `fetch`
 * a browser has too. So the test crosses the same boundary a web build does - a `Promise` of a
 * `Response`, an `ArrayBuffer` body, and its bytes copied into Kotlin - and then holds what the
 * reader made of them to the pre-port golden, like every other target's [GoldenPakTest].
 *
 * What it does not exercise is a browser: Node's `fetch` and a browser's share the API and not
 * the implementation, and CORS exists only in the latter.
 */
class BundleFetchTest {

    @Test
    fun `a pak fetched over HTTP reads as it did before the port`(): Promise<JsAny?> = runAsync {
        withServer(GoldenPak.bytes) { baseUrl ->
            val bytes = GoldenPak.bytes

            val described = BundleReader.fetch("$baseUrl/$PAK").use { describeBundle(it, BundleSource.of(bytes)) }

            assertEquals(GOLDEN_PAK_EXPECTED, described)
        }
    }

    @Test
    fun `an HTTP error is an IOException naming the URL and the status`(): Promise<JsAny?> = runAsync {
        withServer(GoldenPak.bytes) { baseUrl ->
            val url = "$baseUrl/missing.udeapak"

            val failure = try {
                BundleReader.fetch(url)
                fail("a 404 opened as a bundle")
            } catch (expected: IOException) {
                expected
            }

            val message = failure.message.orEmpty()
            assertTrue(url in message && "404" in message, "the message was '$message'")
        }
    }

    @Test
    fun `a refused connection is an IOException naming the URL`(): Promise<JsAny?> = runAsync {
        // A port the server was listening on and no longer is: nothing answers it.
        val closedUrl = withServer(GoldenPak.bytes) { baseUrl -> baseUrl }

        val failure = try {
            BundleReader.fetch("$closedUrl/$PAK")
            fail("a refused connection opened as a bundle")
        } catch (expected: IOException) {
            expected
        }

        val message = failure.message.orEmpty()
        assertTrue(closedUrl in message, "the message was '$message'")
    }

    private suspend fun <T> withServer(body: ByteArray, block: suspend (baseUrl: String) -> T): T {
        val server = awaitPromise(startServer(PAK, body.toInt8Array()))
        try {
            return block("http://127.0.0.1:${serverPort(server)}")
        } finally {
            awaitPromise(stopServer(server))
        }
    }

    private companion object {
        const val PAK = "golden.udeapak"
    }
}

/** A test body the Kotlin/Wasm test runner awaits: it treats a returned `Promise` as async. */
private fun runAsync(block: suspend () -> Unit): Promise<JsAny?> = Promise { resolve, reject ->
    block.startCoroutine(
        Continuation(EmptyCoroutineContext) { result ->
            result.fold(
                onSuccess = { resolve(null) },
                onFailure = { reject(it.toJsReference()) },
            )
        },
    )
}

private suspend fun <T : JsAny?> awaitPromise(promise: Promise<T>): T = suspendCoroutine { continuation ->
    promise.then(
        onFulfilled = { value ->
            continuation.resume(value)
            null
        },
        onRejected = { error ->
            continuation.resumeWithException(IllegalStateException("the test server failed: $error"))
            null
        },
    )
}

private fun ByteArray.toInt8Array(): JsAny {
    val array = newInt8Array(size)
    forEachIndexed { index, byte -> setInt8(array, index, byte) }
    return array
}

private external interface NodeServer : JsAny

@JsFun("(size) => new Int8Array(size)")
private external fun newInt8Array(size: Int): JsAny

@JsFun("(array, index, value) => { array[index] = value; }")
private external fun setInt8(array: JsAny, index: Int, value: Byte)

/** Serves [body] at `/[name]` and 404s everything else, on a loopback port the OS assigns. */
@JsFun(
    """(name, body) => import('node:http').then((http) => new Promise((resolve, reject) => {
        const server = http.createServer((request, response) => {
            if (request.url === '/' + name) {
                response.writeHead(200, { 'Content-Type': 'application/octet-stream' });
                response.end(Buffer.from(body.buffer, body.byteOffset, body.byteLength));
            } else {
                response.writeHead(404);
                response.end();
            }
        });
        server.on('error', reject);
        server.listen(0, '127.0.0.1', () => resolve(server));
    }))""",
)
private external fun startServer(name: String, body: JsAny): Promise<NodeServer>

@JsFun("(server) => server.address().port")
private external fun serverPort(server: NodeServer): Int

/** Closes the listener and every kept-alive connection, so the port is refused afterwards. */
@JsFun("(server) => new Promise((resolve) => { server.close(() => resolve(null)); server.closeAllConnections(); })")
private external fun stopServer(server: NodeServer): Promise<JsAny?>
