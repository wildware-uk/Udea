package dev.wildware.udea.codegen.replicator

/**
 * The **placeholder** `Replicator.typeId`.
 *
 * Spec 5 assigns the real ids from sorted FQNs pinned in a checked-in `net-protocol.lock`, and
 * that lock file is a separate issue. Until it exists, an id has to come from somewhere, and
 * the requirements on the stand-in are: identical for identical input, independent of
 * compilation order and of the set of components in the module (an id must not shift when an
 * unrelated component is added), and computed at *generation* time so the emitted file is a
 * literal rather than a runtime hash.
 *
 * FNV-1a over the FQN satisfies all three. It is **not** collision-free and is not the wire
 * contract; nothing may persist an id produced here.
 */
internal object TypeIds {

    private const val FNV_OFFSET_BASIS = -0x7EE3623B // 0x811C9DC5
    private const val FNV_PRIME = 0x01000193

    /** A non-negative placeholder id derived from [qualifiedName]. */
    fun placeholder(qualifiedName: String): Int {
        var hash = FNV_OFFSET_BASIS
        for (ch in qualifiedName) {
            hash = hash xor ch.code
            hash *= FNV_PRIME
        }
        return hash and 0x7FFFFFFF
    }
}
