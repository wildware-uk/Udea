package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.tools.TextSpill
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger

/**
 * One artifact's id. Validated on construction so nothing downstream re-checks the spelling.
 *
 * `cap_0007`, always. The narrow alphabet is not cosmetic: the id arrives as a query parameter
 * and is turned into a file name, so anything that is not `cap_` plus digits is a path-traversal
 * attempt or a bug, and both are refused **before** a filesystem call. [ArtifactId.parse] is the
 * only way to get one from untrusted text.
 */
@JvmInline
public value class ArtifactId private constructor(public val value: String) {

    override fun toString(): String = value

    /** The ordinal part, for ordering and for the startup sweep. */
    public val ordinal: Int get() = value.substring(PREFIX.length).toInt()

    public companion object {
        /** Every id starts with this. */
        public const val PREFIX: String = "cap_"

        /** `cap_` followed by at least one digit and nothing else. */
        private val SHAPE = Regex("cap_[0-9]+")

        /** Longer than any honest id; a cheap first refusal for a hostile query string. */
        private const val MAX_LENGTH: Int = 32

        /** Digits that still fit an `Int`. */
        private const val MAX_DIGITS: Int = 9

        /** [ordinal] as an id, zero-padded to four digits (wider when it overflows). */
        public fun of(ordinal: Int): ArtifactId {
            require(ordinal >= 0) { "an artifact ordinal is not negative, was $ordinal" }
            return ArtifactId(PREFIX + ordinal.toString().padStart(4, '0'))
        }

        /** [text] as an id, or `null` when it is not one. Never throws, never touches disk. */
        public fun parse(text: String?): ArtifactId? {
            if (text == null || text.length > MAX_LENGTH) return null
            if (!SHAPE.matches(text)) return null
            // Guards the `toInt()` in `ordinal`: `cap_99999999999` matches the shape.
            if (text.length - PREFIX.length > MAX_DIGITS) return null
            return ArtifactId(text)
        }
    }
}

/** One stored artifact: where it is, how big it is, and what it is. */
public class Artifact(
    /** Its id. */
    public val id: ArtifactId,
    /** The file on disk. The path-first convention: ~10 tokens, and the common case. */
    public val path: Path,
    /** The MIME type served as `Content-Type`. */
    public val mediaType: String,
    /** Bytes on disk. What `Content-Length` reports. */
    public val bytes: Long,
) {
    override fun toString(): String = "$id ($mediaType, $bytes bytes)"
}

/**
 * A bounded on-disk store for the bytes that cannot ride in a JSON digest.
 *
 * ## Why the bytes are not in `/state`
 *
 * A base64 PNG inside a 2KB Tier-0 digest destroys the token budget that makes a 40-step agent
 * session fit in one context window, so a capture lands here under an id and the tool result
 * carries the id and the path. An agent on this machine opens the path; an agent that is not
 * fetches `GET /artifact?id=cap_0007`. No base64 ever enters the digest, and
 * `ArtifactEndpointTest` is what keeps that true.
 *
 * ## Why it is bounded on both axes
 *
 * The prior art (`FruitGameKTX`'s `ScreenCapture`) wrote into `build/debug-screenshots/` with no
 * bound at all, and a long session filled a disk. A count bound alone does not help when the
 * captures are 4K; a byte bound alone does not help when they are 1x1 images and there are a
 * hundred thousand of them. Eviction is **oldest-accessed first**, so an artifact an agent is
 * still comparing against survives a burst of new captures.
 *
 * ## Thread safety
 *
 * Every method synchronises on the store. Captures are produced on the simulation thread and read
 * by the HTTP thread, and the LRU's access order is mutated by the *read*, so a read-write lock
 * would not have bought the reader anything. The critical sections are a map operation and, on
 * eviction, a file delete.
 */
public class AgentArtifacts(
    /** Where the files live. Created on demand; a directory that cannot be made disables writes. */
    public val root: Path,
    /** How many artifacts are kept. */
    public val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    /** How many bytes are kept in total. */
    public val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    init {
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
        require(maxBytes > 0) { "maxBytes must be positive, was $maxBytes" }
    }

    /**
     * Insertion-ordered by **access**: `get` moves an entry to the end, so the head is always the
     * least recently used and eviction is a head removal rather than a scan.
     */
    private val entries = LinkedHashMap<String, Artifact>(16, 0.75f, true)

    /**
     * Ids this store has evicted, so a 404 can say *which* 404 it is.
     *
     * The distinction is worth the memory: "cap_0003 was evicted, capture again" and "cap_0003
     * never existed, you mistyped it" have different remedies, and an agent that cannot tell them
     * apart retries the wrong one. Bounded by [MAX_EVICTED_REMEMBERED] so a long session does not
     * accumulate ids forever.
     */
    private val evicted = LinkedHashSet<String>()

    private val nextOrdinal = AtomicInteger(0)

    private var held: Long = 0

    /** Total bytes currently held. */
    public val totalBytes: Long get() = synchronized(this) { held }

    /** How many artifacts are currently held. */
    public val count: Int get() = synchronized(this) { entries.size }

    init {
        sweep()
    }

    /**
     * Stores [bytes] and returns its id, or `null` when the store could not be written.
     *
     * Returns rather than throws for the same reason the registry logs rather than throws: a
     * capture that cannot be filed is a degraded agent surface, not a reason to fail the tick the
     * tool call is inside. The caller reports it as a typed error.
     */
    public fun put(bytes: ByteArray, mediaType: String = PNG): ArtifactId? {
        val id = ArtifactId.of(nextOrdinal.getAndIncrement())
        val file = root.resolve(id.value + extensionOf(mediaType))
        try {
            Files.createDirectories(root)
            // Write beside and move, so a reader that raced the write never sees half a PNG.
            val temp = root.resolve(id.value + ".part")
            Files.write(temp, bytes)
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            warn("could not write artifact $id to $file", e)
            return null
        }
        synchronized(this) {
            entries[id.value] = Artifact(id, file, mediaType, bytes.size.toLong())
            held += bytes.size.toLong()
            evict()
        }
        return id
    }

    /**
     * This store as a [TextSpill], for the tool answers that are too big for the digest.
     *
     * ## Why text goes through the same door as a PNG
     *
     * Because it is the same problem. A 4KB event message and a 2MB screenshot are both bytes
     * that cannot ride in a 2048-byte digest, and `render.screenshot` already settled it: file
     * the bytes, answer with the id, let the caller fetch `GET /artifact?id=cap_0007`. A second
     * mechanism for text would be a second lifetime to reason about, a second bound to get wrong
     * and a second endpoint for an agent to learn. The eviction rules, the `Content-Type`, the
     * typed 404 that distinguishes *evicted* from *never existed* - all of it already works, and
     * `.txt` is already in [extensionOf].
     *
     * `text/plain` rather than `text/plain; charset=utf-8`, because [extensionOf] and
     * [mediaTypeOf] match on the bare type and a decorated one would be filed as `.bin` and read
     * back as something else. The bytes are UTF-8 either way; a caller reading an event message
     * is reading what a Kotlin `String` held.
     */
    public fun textSpill(): TextSpill = TextSpill { text ->
        put(text.toByteArray(StandardCharsets.UTF_8), TEXT)?.value
    }

    /** The artifact for [id], marking it most recently used, or `null` when it is not held. */
    public fun get(id: ArtifactId): Artifact? = synchronized(this) { entries[id.value] }

    /** Whether [id] was held by this store and has since been evicted. */
    public fun wasEvicted(id: ArtifactId): Boolean = synchronized(this) { id.value in evicted }

    /** Everything held, least recently used first. */
    public fun list(): List<Artifact> = synchronized(this) { entries.values.toList() }

    /** Evicts everything and deletes the files. For tests and for a session boundary. */
    public fun clear() {
        synchronized(this) { entries.keys.toList().forEach { drop(it) } }
    }

    /**
     * Adopts whatever a previous run left in [root], so the bound covers it.
     *
     * Without this a restart starts counting from zero over a directory that is already at the
     * limit, and the store's idea of what is on disk is wrong for the rest of the session - which
     * is how an unbounded directory grows behind a store that believes it is bounded.
     */
    private fun sweep() {
        val existing = try {
            if (!Files.isDirectory(root)) return
            Files.list(root).use { stream -> stream.toList() }
        } catch (e: IOException) {
            warn("could not sweep the artifact directory $root", e)
            return
        }
        val found = existing.mapNotNull { file -> adopt(file) }.sortedBy { it.id.ordinal }
        synchronized(this) {
            found.forEach { artifact ->
                entries[artifact.id.value] = artifact
                held += artifact.bytes
            }
            nextOrdinal.set((found.maxOfOrNull { it.id.ordinal } ?: -1) + 1)
            evict()
        }
    }

    /** [file] as an artifact, or `null` when it is not one of ours. */
    private fun adopt(file: Path): Artifact? {
        val name = file.fileName.toString()
        val id = ArtifactId.parse(name.substringBeforeLast('.', name)) ?: return null
        return try {
            if (!Files.isRegularFile(file)) null else Artifact(id, file, mediaTypeOf(name), Files.size(file))
        } catch (e: IOException) {
            warn("could not size $file", e)
            null
        }
    }

    /** Caller holds the monitor. */
    private fun evict() {
        while (entries.isNotEmpty() && (entries.size > maxEntries || held > maxBytes)) {
            drop(entries.keys.first())
        }
    }

    /** Caller holds the monitor. */
    private fun drop(key: String) {
        val artifact = entries.remove(key) ?: return
        held -= artifact.bytes
        try {
            Files.deleteIfExists(artifact.path)
        } catch (e: IOException) {
            warn("could not delete evicted artifact ${artifact.path}", e)
        }
        evicted.add(key)
        while (evicted.size > MAX_EVICTED_REMEMBERED) {
            val iterator = evicted.iterator()
            iterator.next()
            iterator.remove()
        }
    }

    private fun warn(message: String, failure: Throwable) {
        System.err.println(
            "[udea-agent-host] $message: ${failure.javaClass.simpleName}: ${failure.message}",
        )
    }

    override fun toString(): String =
        "AgentArtifacts($root, $count/$maxEntries, $totalBytes/$maxBytes bytes)"

    public companion object {

        /** `image/png`, the only type anything produces today. */
        public const val PNG: String = "image/png"

        /** The media type a [textSpill] files under. Bare, so [extensionOf] recognises it. */
        public const val TEXT: String = "text/plain"

        /** 200 artifacts. A long session's worth of captures without a full disk to show for it. */
        public const val DEFAULT_MAX_ENTRIES: Int = 200

        /** 256MB. */
        public const val DEFAULT_MAX_BYTES: Long = 256L * 1024L * 1024L

        /** `-Dudea.agent.artifacts.maxEntries`. */
        public const val MAX_ENTRIES_PROPERTY: String = "udea.agent.artifacts.maxEntries"

        /** `-Dudea.agent.artifacts.maxBytes`. */
        public const val MAX_BYTES_PROPERTY: String = "udea.agent.artifacts.maxBytes"

        /** `-Dudea.agent.artifacts.dir`. Defaults to `build/udea-agent/artifacts`. */
        public const val DIRECTORY_PROPERTY: String = "udea.agent.artifacts.dir"

        /** How many evicted ids are remembered so a 404 can say "evicted" rather than "unknown". */
        private const val MAX_EVICTED_REMEMBERED: Int = 512

        /** A store configured from system properties, under `build/udea-agent/artifacts`. */
        public fun fromProperties(
            properties: (String) -> String? = System::getProperty,
            workingDirectory: Path = Path.of("."),
        ): AgentArtifacts {
            val dir = properties(DIRECTORY_PROPERTY)?.let { Path.of(it) }
                ?: workingDirectory.resolve("build").resolve("udea-agent").resolve("artifacts")
            return AgentArtifacts(
                root = dir.toAbsolutePath().normalize(),
                maxEntries = properties(MAX_ENTRIES_PROPERTY)?.toIntOrNull() ?: DEFAULT_MAX_ENTRIES,
                maxBytes = properties(MAX_BYTES_PROPERTY)?.toLongOrNull() ?: DEFAULT_MAX_BYTES,
            )
        }

        /** The file extension for [mediaType]; `.bin` for anything unrecognised. */
        public fun extensionOf(mediaType: String): String = when (mediaType) {
            PNG -> ".png"
            "image/jpeg" -> ".jpg"
            "application/json" -> ".json"
            "text/plain" -> ".txt"
            else -> ".bin"
        }

        /** The media type for a file name: the inverse of [extensionOf]. */
        public fun mediaTypeOf(fileName: String): String = when {
            fileName.endsWith(".png") -> PNG
            fileName.endsWith(".jpg") -> "image/jpeg"
            fileName.endsWith(".json") -> "application/json"
            fileName.endsWith(".txt") -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
