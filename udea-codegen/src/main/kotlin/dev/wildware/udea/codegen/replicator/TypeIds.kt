package dev.wildware.udea.codegen.replicator

/**
 * **The single source of component type ids.**
 *
 * Spec 3.2 states it plainly: bit indices and component ids come from one place — sorted
 * fully-qualified names in `udea-codegen` — written to a checked-in `net-protocol.lock` that
 * CI diffs. This is that one place.
 *
 * Sorted names rather than a content hash, because the id is a `u16` in every packet and a
 * dense id is a small one. The cost is that inserting a component renumbers its successors,
 * which is exactly why the lock file is checked in: renumbering the wire format has to be a
 * reviewed act rather than a side effect of adding a class.
 *
 * The generator this replaces had no stable ids at all and named its cross-module index
 * `UdeaSerializerRegistry_${'$'}{System.currentTimeMillis()}`, so nothing about its wire format was
 * reproducible between two builds of the same source, let alone between a running server and
 * a client built yesterday.
 */
internal object TypeIds {

    /** Ids are a `u16` in byte 1 of every component entry, so this is the last usable id. */
    const val MAX_ID: Int = 0xFFFF

    /**
     * Dense ids for [qualifiedNames], assigned `0, 1, 2, …` in ascending name order.
     *
     * **[qualifiedNames] is the id space, and the id space is the whole project.** This
     * function cannot tell a project-wide list from one module's, so the caller owes it the
     * former: `UdeaSymbolProcessor` passes `udea.projectComponents`, which the build computes
     * from resolved artifacts. Handing it the symbols of a single KSP run gives every module
     * its own dense `0..n-1`, which is not an id space at all — it is several, all claiming
     * the same numbers.
     *
     * Pure: no IO, no clock, no dependence on the order the caller discovered the names in.
     * Two builds that compile the same set of components produce the same map, which is what
     * makes `net-protocol.lock` diffable and what makes a build cache sound.
     *
     * @throws IllegalArgumentException if a name repeats — two `@Replicated` classes cannot
     *   share a fully-qualified name, and an id space built from a list that thinks they can
     *   would silently hand one id to two components — or if there are more components than
     *   the `u16` id space holds.
     */
    fun assignIds(qualifiedNames: List<String>): Map<String, Int> {
        val sorted = qualifiedNames.sorted()
        for (index in 1 until sorted.size) {
            require(sorted[index] != sorted[index - 1]) {
                "two @Replicated components share the fully-qualified name '${sorted[index]}'; " +
                    "a component type id is assigned per name, so the two would collide on id " +
                    "${index - 1}"
            }
        }
        require(sorted.size <= MAX_ID + 1) {
            "${sorted.size} @Replicated components exceeds the ${MAX_ID + 1} a u16 component " +
                "type id can address"
        }
        return sorted.withIndex().associate { (index, name) -> name to index }
    }
}
