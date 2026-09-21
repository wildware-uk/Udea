package dev.wildware.hollow.net

import com.github.quillraven.fleks.World
import dev.wildware.hollow.Scenery
import dev.wildware.hollow.Sunlight
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.net.replication.RelevancySet
import dev.wildware.udea.net.transport.PeerId

/**
 * How much each of the server's entities matters to a client (issue #251): the clearing's dressing
 * - its trees, rocks and grass, and its sun - hardly at all, and everything that moves fully.
 *
 * ## The stall this prevents
 *
 * `udea-net`'s send loop picks entities in order of accumulated priority, which grows with the
 * ticks since an entity was last *sent* - and a tree that never changes is never sent after its
 * create, so its priority climbs for as long as the session runs. Every couple of seconds its
 * baseline also leaves the server's snapshot ring, and the server re-sends it whole. With
 * `RelevancySet.ALL_VISIBLE`'s equal weights that re-send puts the whole clearing ahead of every fox
 * and player until it has gone out, which takes several ticks of datagrams: a client is told a new
 * server tick while every fox in its world still stands where the server had it ticks before, and
 * the foxes on its screen stall while the server's run on. `FoxReplicationTest`'s tick-for-tick
 * comparison is what caught it.
 *
 * Weighting the dressing at [DRESSING] keeps the movers ahead of it on every tick - a fox's
 * priority grows by one a tick, and a prop's by one sixty-five-thousandth of the ticks it has gone
 * unsent - while the dressing still climbs and is still sent in whatever room the movers leave, so
 * no prop is starved. The re-sends themselves are `udea-net`'s to remove; which entities matter
 * most to a player is the part a game decides.
 *
 * Everything is relevant: Hollow's clearing is small enough that every client is told everything.
 *
 * Called once per entity per client per tick, so it resolves through the `NetIdIndex` array and
 * reads two component masks, and allocates nothing.
 */
internal class HollowRelevancy(
    private val world: World,
    private val netIds: NetIdIndex,
) : RelevancySet {

    override fun isRelevant(client: PeerId, netId: NetId): Boolean = true

    override fun weightOf(client: PeerId, netId: NetId): Float {
        val entity = netIds.resolveOrNull(netId) ?: return MOVER
        return with(world) { if (entity has Scenery || entity has Sunlight) DRESSING else MOVER }
    }

    override fun toString(): String = "HollowRelevancy(dressing=$DRESSING)"

    internal companion object {
        /** The weight of anything that is not the clearing's dressing: `ALL_VISIBLE`'s one. */
        const val MOVER: Float = 1f

        /**
         * The clearing's dressing. Small enough that a prop unsent for the length of a long session
         * (tens of thousands of ticks) still grows no faster than a fox sent every tick.
         */
        const val DRESSING: Float = 1f / 65_536f
    }
}
