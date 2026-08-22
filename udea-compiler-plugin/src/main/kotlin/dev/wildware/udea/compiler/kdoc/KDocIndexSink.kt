package dev.wildware.udea.compiler.kdoc

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Collects [KDocEntry]s and keeps `kdoc-index.json` on disk in sync with them.
 *
 * ### Why it rewrites the whole file after every source file
 *
 * A K2 checkers extension has no "compilation finished" callback, and issue #42 requires the
 * index to be byte-identical between two clean builds. Buffering until an end-of-compilation
 * hook that does not exist would mean inventing one - a shutdown hook, or an IR extension the
 * plugin has promised never to have (spec 3.2). Rewriting the whole, sorted index each time a
 * file is harvested is deterministic *whatever* order the compiler visits files in, because
 * the output is a pure function of the accumulated set rather than of the visit sequence.
 *
 * The cost is one small write per source file during a harvest pass, which is a build-time
 * step that runs before KSP and never on a per-tick path.
 *
 * The write is atomic: content goes to a sibling temp file and is moved into place, so a
 * cancelled build cannot leave KSP a half-written index to parse.
 */
internal class KDocIndexSink(
    indexPath: String,
    /** Repo root, used to keep every span repo-relative (spec 5). */
    val repoRoot: String,
) {
    private val target = File(indexPath)

    /** Keyed by FQN plus span, the identity issue #42 specifies. */
    private val entries = LinkedHashMap<String, KDocEntry>()

    /** Records [entry], replacing any earlier entry for the same declaration, and rewrites. */
    @Synchronized
    fun add(entry: KDocEntry) {
        entries["${entry.fqn}@${entry.span}"] = entry
        write()
    }

    private fun write() {
        val encoded = KDocIndexJson.encode(entries.values)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(encoded, StandardCharsets.UTF_8)
        // `renameTo` is not atomic across every filesystem, but a failure here is a build
        // failure rather than a silently stale index, which is the property that matters.
        check(temp.renameTo(target) || (target.delete() && temp.renameTo(target))) {
            "could not move the harvested KDoc index into place at ${target.absolutePath}"
        }
    }
}
