package dev.wildware.udea.render.overlay

/**
 * Where an entity is *right now*, by packed `NetId`.
 *
 * ## The `false` case is the interesting one
 *
 * A marker must **track** the entity it rings as the entity moves, so it cannot cache a position
 * from the moment the tool was called - the caller stores what the call was *about*, not where
 * the subject was. And a `NetId` whose generation has gone stale must draw **nothing**: the index
 * is dense and recycled, so ringing "whatever is in slot 7 now" would put a marker on an
 * unrelated entity and tell a human the agent had inspected it.
 *
 * This interface holds no `NetId` type itself - it is the port an overlay renders through, and it
 * takes the packed `Int` a caller already has, so this module needs no dependency on whatever
 * indexes identity. `dev.wildware.udea.agent.host.overlay.NetIdEntityLocator` is the real
 * implementation, over `dev.wildware.udea.core.identity.NetIdIndex`.
 */
public fun interface EntityLocator {

    /**
     * Writes the world position of the entity with this packed NetId into [out].
     *
     * @param out a two-slot array the caller owns and reuses, `[x, y]`.
     * @return `false` when the id is stale, freed, unknown, or the entity carries no position.
     *   The caller must draw nothing; there is deliberately no "last known position" fallback.
     */
    public fun locate(packedNetId: Int, out: FloatArray): Boolean

    public companion object {
        /** Nothing is ever locatable. What an instance with no world index is wired with. */
        public val NONE: EntityLocator = EntityLocator { _, _ -> false }
    }
}
