package dev.wildware.udea.agent.host

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `GET /artifact` - the endpoint that exists because image bytes cannot ride in a JSON digest.
 *
 * A base64 PNG inside a 2KB Tier-0 snapshot would destroy the token budget that makes a 40-step
 * agent session fit in one context window, so the path-first convention is asserted here: the
 * digest never carries bytes, and this endpoint serves them for an agent that is not on this
 * machine.
 */
class ArtifactEndpointTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `serves the exact bytes with the right type and length`() {
        val store = AgentArtifacts(temp)
        val payload = ByteArray(512) { (it % 251).toByte() }
        val id = assertNotNull(store.put(payload, AgentArtifacts.PNG))

        HostHarness(artifacts = store).use { harness ->
            val response = harness.bytes("/artifact?id=${id.value}")

            assertEquals(200, response.statusCode())
            assertEquals(AgentArtifacts.PNG, response.headers().firstValue("content-type").orElse(""))
            assertEquals(
                payload.size.toString(),
                response.headers().firstValue("content-length").orElse(""),
                "an inaccurate Content-Length makes a client either truncate or hang",
            )
            assertContentEquals(payload, response.body())
        }
    }

    @Test
    fun `an unknown id is a 404 with a JSON body`() {
        HostHarness(artifacts = AgentArtifacts(temp)).use { harness ->
            val response = harness.get("/artifact?id=cap_4242")

            assertEquals(404, response.statusCode())
            // The body shape matters: the bridge distinguishes "nothing there" from "something
            // else took this port", and a bare HTML error page reads as the latter.
            assertContains(response.body(), """"error":"artifact_not_found"""")
            assertContains(response.body(), """"evicted":false""")
            assertContains(response.body(), "cap_4242")
        }
    }

    @Test
    fun `an evicted id says so, distinctly from one that never existed`() {
        val store = AgentArtifacts(temp, maxEntries = 1, maxBytes = Long.MAX_VALUE)
        val dropped = assertNotNull(store.put(ByteArray(8)))
        store.put(ByteArray(8))

        HostHarness(artifacts = store).use { harness ->
            val evicted = harness.get("/artifact?id=${dropped.value}")
            assertEquals(404, evicted.statusCode())
            assertContains(evicted.body(), """"error":"artifact_evicted"""")
            assertContains(evicted.body(), """"evicted":true""")

            val never = harness.get("/artifact?id=cap_9999")
            assertContains(never.body(), """"error":"artifact_not_found"""")
            assertContains(never.body(), """"evicted":false""")
        }
    }

    /**
     * Traversal is refused by the id grammar, before any filesystem call. The assertion that it
     * never read anything is the file next door still existing *and* the body naming the id
     * grammar rather than a path.
     */
    @Test
    fun `path traversal is refused without a filesystem read`() {
        val store = AgentArtifacts(temp)
        val secret = temp.resolve("secret.txt")
        Files.writeString(secret, "do not serve me")

        HostHarness(artifacts = store).use { harness ->
            listOf(
                "/artifact?id=../../build.gradle.kts",
                "/artifact?id=cap_0001/../x",
                "/artifact?id=" + java.net.URLEncoder.encode("../secret.txt", Charsets.UTF_8),
                "/artifact?id=secret.txt",
            ).forEach { path ->
                val response = harness.get(path)
                assertEquals(404, response.statusCode(), path)
                assertContains(response.body(), """"error":"bad_artifact_id"""", message = path)
                assertFalse(response.body().contains("do not serve me"), path)
            }
        }
    }

    @Test
    fun `a host with no artifact store answers a typed 404`() {
        HostHarness().use { harness ->
            val response = harness.get("/artifact?id=cap_0001")

            assertEquals(404, response.statusCode())
            assertContains(response.body(), """"error":"no_artifact_store"""")
        }
    }

    /** Bytes live behind an id and a path; the digest carries neither them nor base64 of them. */
    @Test
    fun `no artifact bytes reach the state digest`() {
        val store = AgentArtifacts(temp)
        val payload = ByteArray(64) { 0x7F }
        val id = assertNotNull(store.put(payload, AgentArtifacts.PNG))
        val artifact = assertNotNull(store.get(id))

        HostHarness(artifacts = store).use { harness ->
            // What a render tool publishes into a digest: an id and a path, never bytes.
            harness.bridge.publish(
                """{"frame":1,"lastCapture":{"artifactId":"${id.value}","path":"${
                    artifact.path.toString().replace("\\", "\\\\")
                }"}}""",
            )
            val digest = harness.get("/state").body()

            assertContains(digest, id.value)
            assertFalse(
                digest.contains(java.util.Base64.getEncoder().encodeToString(payload)),
                "base64 image bytes must never appear in the digest",
            )
            assertTrue(digest.length < 1024, "the digest stayed small: ${digest.length} chars")
        }
    }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size, "byte length")
        assertTrue(expected.contentEquals(actual), "artifact bytes differ from what was stored")
    }
}
