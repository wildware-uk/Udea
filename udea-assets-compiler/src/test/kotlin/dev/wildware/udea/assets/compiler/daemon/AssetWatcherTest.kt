package dev.wildware.udea.assets.compiler.daemon

import dev.wildware.udea.assets.compiler.TestPaths
import org.junit.jupiter.api.Test
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The debounce, driven by a hand-advanced clock.
 *
 * No sleeps and no real editor: the property under test is "events within the quiet window
 * coalesce into one batch, and the window is measured from the last event", and a test that
 * asserted it by writing files and sleeping would be asserting the OS's flush timing (standards
 * section 5).
 *
 * The `WatchService` registration itself is exercised separately, because that half genuinely is
 * the JDK's and the only honest thing to assert about it is that the directories were registered.
 */
class AssetWatcherTest {

    private fun watcher(clock: () -> Long): AssetWatcher {
        val root = TestPaths.scratch("watcher/root")
        root.resolve("character").createDirectories()
        return AssetWatcher(listOf(root), debounce = AssetWatcher.DEFAULT_DEBOUNCE_MS, now = clock)
    }

    @Test
    fun `events inside the quiet window coalesce into one batch`() {
        var time = 0L
        watcher { time }.use { watcher ->
            val root = watcher.watched.first()
            // One editor save: truncate, write, rename, all within a few milliseconds.
            watcher.record(root.resolve("a.udea.kts"), at = 0)
            watcher.record(root.resolve("a.udea.kts"), at = 5)
            watcher.record(root.resolve("b.udea.kts"), at = 40)

            assertNull(watcher.settle(at = 100), "still inside the window: not settled, not empty")
            assertNull(watcher.settle(at = 159), "the window runs from the LAST event at 40, not the first")

            val batch = watcher.settle(at = 160)
            assertEquals(
                setOf(root.resolve("a.udea.kts"), root.resolve("b.udea.kts")),
                batch,
                "three events over two files is one batch of two files",
            )
            assertNull(watcher.settle(at = 1000), "a delivered batch is not delivered twice")
        }
    }

    @Test
    fun `a file that is not a udea script is never recorded`() {
        watcher { 0L }.use { watcher ->
            val root = watcher.watched.first()
            watcher.record(root.resolve("idle.png"))
            watcher.record(root.resolve("notes.md"))
            assertNull(
                watcher.settle(at = 10_000),
                "a texture landing beside a script must not trigger a recompile of anything",
            )
        }
    }

    @Test
    fun `subdirectories are registered, because WatchService is not recursive`() {
        watcher { 0L }.use { watcher ->
            val names = watcher.watched.map { it.fileName.toString() }
            assertTrue(
                "character" in names,
                "a watcher registered on the root alone sees nothing an author ever edits: $names",
            )
        }
    }
}
