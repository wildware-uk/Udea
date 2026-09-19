package dev.wildware.moba.level

import com.github.quillraven.fleks.Entity
import dev.wildware.moba.MobaGame
import dev.wildware.moba.Player
import dev.wildware.moba.Position
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.snapshot.SnapshotService
import dev.wildware.udea.core.snapshot.WorldHasher

/**
 * Who is on the field and exactly where, one line per entity, as text a person can diff.
 *
 * Issue #192 turned the authored `test_level.udea.kts` into a saved level file. The claim that made
 * that safe is "the game boots with the same units in the same places", and this is the form the
 * claim is checked in: `test_level.roster.txt` was written from the world the script built, before
 * the script was deleted, and `TestLevelRosterTest` compares a boot against it.
 *
 * The first line is the whole-world hash of a full capture - every registered component field of
 * every entity, the clock, the random streams and the `NetId` allocator - so a value the lines
 * below do not print still cannot move unnoticed. Then every entity with a `NetId`, in `NetId`
 * order, with:
 * - its `NetId` (index and generation), because the player's id is what a camera follows;
 * - its team and unit kind, read off [GameUnit];
 * - whether it is the [Player];
 * - its [Position] as the exact float bits as well as a readable number, because "the same place"
 *   means the same bits and a printed float rounds;
 * - the simple names of every component it carries, sorted, read off Fleks rather than the
 *   snapshot registry, so a component outside that registry is still named.
 */
internal object LevelRoster {

    fun of(host: GameHost): List<String> {
        val lines = sortedMapOf<Int, String>()
        host.ctx[CoreModule.NET_IDS].forEachLive { netId: NetId, entity: Entity ->
            lines[netId.index] = line(host, netId, entity)
        }
        return listOf("worldHash=" + hash(host).toULong().toString(16)) + lines.values
    }

    private fun hash(host: GameHost): Long = WorldHasher.hash(
        SnapshotService(MobaGame.componentRegistry(), host.world, host.ctx, host.ctx[CoreModule.NET_IDS]).capture(),
    )

    private fun line(host: GameHost, netId: NetId, entity: Entity): String = with(host.world) {
        val unit = entity.getOrNull(GameUnit)
        val position = entity.getOrNull(Position)
        val components = snapshotOf(entity).components
            .map { it::class.simpleName ?: it::class.java.name }
            .sorted()
        buildString {
            append("netId=").append(netId.index).append('/').append(netId.generation)
            append(" team=").append(unit?.let { Team.nameOf(it.team) } ?: "-")
            append(" kind=").append(unit?.kind ?: "-")
            append(" player=").append(entity.has(Player))
            if (position != null) {
                append(" x=").append(position.x).append(" (").append(bits(position.x)).append(')')
                append(" y=").append(position.y).append(" (").append(bits(position.y)).append(')')
                append(" hp=").append(position.hp)
            }
            append(" components=").append(components.joinToString(","))
        }
    }

    private fun bits(value: Float): String = "0x%08x".format(value.toRawBits())
}
