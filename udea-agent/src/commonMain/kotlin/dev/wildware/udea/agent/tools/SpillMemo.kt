package dev.wildware.udea.agent.tools

/**
 * The last few texts a toolset spilled, keyed by the text, evicting the least recently used.
 *
 * Both `EventsToolset` and `WorldToolset` keep one, for the reason their own KDoc gives: a caller
 * polling a tool would otherwise file one artifact per call, and the artifact store's
 * oldest-accessed-first eviction would quietly delete the evidence somebody else filed.
 *
 * Common code has no access-ordered `LinkedHashMap` to subclass (issue #208), so recency is kept
 * by re-inserting a key on every hit: Kotlin's `LinkedHashMap` iterates in insertion order on
 * every platform, which makes its first key the least recently used one.
 */
internal class SpillMemo(private val capacity: Int) {

    init {
        require(capacity > 0) { "a spill memo remembers at least one text, was $capacity" }
    }

    private val handles = LinkedHashMap<String, String>()

    /** The handle [text] was filed under, or what [file] returns for it, remembered if not null. */
    fun handleFor(text: String, file: (String) -> String?): String? {
        handles.remove(text)?.let { handle ->
            handles[text] = handle
            return handle
        }
        val handle = file(text) ?: return null
        handles[text] = handle
        if (handles.size > capacity) handles.remove(handles.keys.first())
        return handle
    }
}
