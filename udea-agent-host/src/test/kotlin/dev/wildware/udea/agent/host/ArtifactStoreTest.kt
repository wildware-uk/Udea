package dev.wildware.udea.agent.host

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** The bound, the eviction order, the sweep, and the id validation that keeps `/artifact` shut. */
class ArtifactStoreTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `evicts by count, deletes the file, and keeps the newest`() {
        val store = AgentArtifacts(temp, maxEntries = 3, maxBytes = Long.MAX_VALUE)
        val ids = (1..5).map { assertNotNull(store.put(ByteArray(it), AgentArtifacts.PNG)) }

        assertEquals(3, store.count)
        assertNull(store.get(ids[0]), "the oldest must be gone")
        assertTrue(store.wasEvicted(ids[0]))
        assertFalse(temp.resolve(ids[0].value + ".png").exists(), "an evicted entry's file is deleted")
        assertNotNull(store.get(ids[4]), "the newest must survive")
    }

    @Test
    fun `evicts by total bytes`() {
        val store = AgentArtifacts(temp, maxEntries = 1_000, maxBytes = 300)
        repeat(5) { store.put(ByteArray(100)) }

        assertEquals(3, store.count, "300 bytes holds three 100-byte artifacts")
        assertEquals(300, store.totalBytes)
    }

    /**
     * Oldest **accessed**, not oldest written: an artifact an agent is still comparing against has
     * to survive a burst of new captures, or the second half of every diff is gone by the time it
     * asks for it.
     */
    @Test
    fun `eviction is by access order`() {
        val store = AgentArtifacts(temp, maxEntries = 3, maxBytes = Long.MAX_VALUE)
        val first = assertNotNull(store.put(ByteArray(1)))
        val second = assertNotNull(store.put(ByteArray(1)))
        store.put(ByteArray(1))

        store.get(first) // touched, so `second` is now the least recently used
        val fourth = assertNotNull(store.put(ByteArray(1)))

        assertNotNull(store.get(first), "the touched entry survives")
        assertNull(store.get(second), "the untouched older entry is the one evicted")
        assertNotNull(store.get(fourth))
    }

    @Test
    fun `a restart sweeps the directory and accounts for what is on disk`() {
        val first = AgentArtifacts(temp, maxEntries = 10, maxBytes = Long.MAX_VALUE)
        repeat(4) { first.put(ByteArray(50)) }

        val restarted = AgentArtifacts(temp, maxEntries = 10, maxBytes = Long.MAX_VALUE)
        assertEquals(4, restarted.count)
        assertEquals(200, restarted.totalBytes)
        assertEquals(
            Files.list(temp).use { it.toList() }.sumOf { Files.size(it) },
            restarted.totalBytes,
            "the store's byte total must match what is actually on disk",
        )

        // And the ids continue rather than colliding with what the sweep adopted.
        val next = assertNotNull(restarted.put(ByteArray(1)))
        assertEquals("cap_0004", next.value)
    }

    @Test
    fun `a restart applies the bound to what a previous run left`() {
        val first = AgentArtifacts(temp, maxEntries = 20, maxBytes = Long.MAX_VALUE)
        repeat(10) { first.put(ByteArray(10)) }

        val restarted = AgentArtifacts(temp, maxEntries = 3, maxBytes = Long.MAX_VALUE)
        assertEquals(3, restarted.count, "a previous run's files are bounded, not orphaned")
        assertEquals(3, Files.list(temp).use { it.toList() }.size)
    }

    @Test
    fun `ids outside cap_digits are rejected without touching the filesystem`() {
        listOf(
            "../../build.gradle.kts",
            "cap_0001/../x",
            "cap_",
            "cap_0001.png",
            "CAP_0001",
            "cap_-1",
            "cap_99999999999",
            "",
        ).forEach { assertNull(ArtifactId.parse(it), "$it must not parse as an artifact id") }

        assertEquals("cap_0007", assertNotNull(ArtifactId.parse("cap_0007")).value)
        assertEquals(7, assertNotNull(ArtifactId.parse("cap_0007")).ordinal)
    }

    @Test
    fun `a store whose directory cannot be created returns null rather than throwing`() {
        val blocked = temp.resolve("blocked")
        Files.writeString(blocked, "not a directory")
        val store = AgentArtifacts(blocked.resolve("artifacts"))

        assertNull(store.put(ByteArray(4)), "a capture that cannot be filed is not an exception")
        assertEquals(0, store.count)
    }

    @Test
    fun `an unknown id and an evicted id are distinguishable`() {
        val store = AgentArtifacts(temp, maxEntries = 1, maxBytes = Long.MAX_VALUE)
        val dropped = assertNotNull(store.put(ByteArray(1)))
        store.put(ByteArray(1))

        assertTrue(store.wasEvicted(dropped))
        assertFalse(store.wasEvicted(assertNotNull(ArtifactId.parse("cap_9999"))))
    }

    @Test
    fun `media types round trip through the file extension`() {
        assertEquals(".png", AgentArtifacts.extensionOf(AgentArtifacts.PNG))
        assertEquals(AgentArtifacts.PNG, AgentArtifacts.mediaTypeOf("cap_0001.png"))
        assertEquals(".bin", AgentArtifacts.extensionOf("application/x-unknown"))
        assertContains(AgentArtifacts.mediaTypeOf("cap_0001.bin"), "octet-stream")
    }
}
