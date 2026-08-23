package dev.wildware.udea.assets.compiler.daemon

import dev.wildware.udea.assets.compiler.scan.UdeaDeclarationScanner
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit

/**
 * A `WatchService` over the asset roots, debounced, delivering the set of `.udea.kts` that moved.
 *
 * ## Why the debounce is not a nicety
 *
 * An editor saving one file produces two to five events - a truncate, one or more writes, a
 * rename over the target, sometimes a delete of a backup. Recompiling on each is recompiling a
 * file that is half written, which produces a syntax error the author never made and, without a
 * last-good graph, would take the running game down for the fifty milliseconds between the
 * truncate and the write. So events coalesce into one batch, and [debounce] is measured from the
 * **last** event rather than the first: a save that keeps producing events keeps deferring, which
 * is exactly the behaviour wanted while a large file is being flushed.
 *
 * ## Time is injected
 *
 * [now] and [poll] are constructor parameters so that [WatcherDebounceTest] can drive coalescing
 * with a hand-advanced clock and no sleeping at all (standards section 5: no wall-clock waits in
 * tests). The production defaults are `System.nanoTime` and the real `WatchService`.
 *
 * ## What it does not do
 *
 * It does not recompile, validate, or push anything. It answers "these files changed" and hands
 * that to whatever the host wired - [AssetDaemon.reload] in the dev loop. Keeping the two apart is
 * what lets the daemon be tested against explicit file sets, without a watcher and without a
 * filesystem race, which is every test in `AssetDaemonTest`.
 */
public class AssetWatcher(
    private val roots: List<Path>,
    /** Quiet period after the last event before a batch is delivered. Spec 3.6 names 120ms. */
    private val debounce: Long = DEFAULT_DEBOUNCE_MS,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {

    private val service: WatchService = FileSystems.getDefault().newWatchService()

    private val keys = LinkedHashMap<WatchKey, Path>()

    /** Files seen since the last delivered batch, and when the most recent event arrived. */
    private val pending = LinkedHashSet<Path>()

    private var lastEventAt: Long = 0

    init {
        require(roots.isNotEmpty()) { "an AssetWatcher with no roots would watch nothing forever" }
        require(debounce >= 0) { "debounce must not be negative, was $debounce" }
        roots.forEach { register(it) }
    }

    /** Directories currently registered, sorted. A new subdirectory is registered when seen. */
    public val watched: List<Path> get() = keys.values.sortedBy { it.toString() }

    /**
     * Blocks up to [timeoutMs] for events and returns a batch once [debounce] has passed quietly,
     * or an empty set if nothing settled in time.
     *
     * Returns empty rather than blocking forever on purpose: a caller wants to be able to shut a
     * daemon down, and an interruptible poll loop it drives is a shutdown it controls rather than
     * one that depends on interrupting a thread parked in the JDK.
     */
    public fun poll(timeoutMs: Long): Set<Path> {
        val deadline = now() + timeoutMs
        while (true) {
            val remaining = deadline - now()
            val key = service.poll(remaining.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            if (key != null) {
                drain(key)
            } else if (pending.isEmpty()) {
                return emptySet()
            }
            val settled = settle()
            if (settled != null) return settled
            if (now() >= deadline) return emptySet()
        }
    }

    /**
     * Records [file] as changed at [at]. The seam the debounce test drives.
     *
     * Public because coalescing is the behaviour worth testing and the only alternative way to
     * test it is to write real files and sleep, which standards section 5 forbids.
     */
    public fun record(file: Path, at: Long = now()) {
        if (!file.toString().endsWith(UdeaDeclarationScanner.SCRIPT_SUFFIX)) return
        pending.add(file.toAbsolutePath().normalize())
        lastEventAt = at
    }

    /**
     * The batch, if the quiet period has elapsed since the last recorded event; otherwise `null`.
     *
     * `null` and not an empty set: "nothing changed" and "changes are still arriving" are
     * different answers, and a caller that treats the second as the first recompiles mid-save.
     */
    public fun settle(at: Long = now()): Set<Path>? {
        if (pending.isEmpty()) return null
        if (at - lastEventAt < debounce) return null
        val batch = LinkedHashSet(pending)
        pending.clear()
        return batch
    }

    override fun close() {
        keys.keys.forEach { it.cancel() }
        keys.clear()
        service.close()
    }

    /**
     * Registers [directory] and every directory beneath it.
     *
     * Recursive because `WatchService` is not: on every platform it watches one directory, and an
     * asset tree is `character/`, `level/`, `sounds/`. A daemon that registered only the root
     * would see nothing an author ever edits.
     */
    private fun register(directory: Path) {
        if (!Files.isDirectory(directory)) return
        Files.walk(directory).use { stream ->
            stream.filter { Files.isDirectory(it) }.forEach { dir ->
                val key = dir.register(
                    service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE,
                )
                keys[key] = dir
            }
        }
    }

    private fun drain(key: WatchKey) {
        val directory = keys[key]
        if (directory == null) {
            key.cancel()
            return
        }
        for (event in key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                // The OS dropped events. Everything under the roots is suspect, so the batch
                // becomes the whole tree rather than the handful that happened to survive - an
                // overflow silently reloading a subset is a reload that leaves the graph wrong.
                roots.forEach { root ->
                    Files.walk(root).use { stream ->
                        stream.filter { Files.isRegularFile(it) }.forEach { record(it) }
                    }
                }
                continue
            }
            val relative = event.context() as? Path ?: continue
            val resolved = directory.resolve(relative)
            // A new directory has to be registered or its contents are invisible; doing it here
            // rather than on a rescan is what makes `mkdir character && touch character/x.udea.kts`
            // work the first time.
            if (Files.isDirectory(resolved)) register(resolved) else record(resolved)
        }
        if (!key.reset()) {
            keys.remove(key)
        }
    }

    public companion object {
        /** Spec 3.6's debounce. */
        public const val DEFAULT_DEBOUNCE_MS: Long = 120
    }
}
