package dev.wildware.udea.agent.query

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.agent.Champion
import dev.wildware.udea.agent.Health
import dev.wildware.udea.agent.Team
import dev.wildware.udea.agent.Transform
import dev.wildware.udea.agent.championAccess
import dev.wildware.udea.agent.healthAccess
import dev.wildware.udea.agent.teamAccess
import dev.wildware.udea.agent.transformAccess
import dev.wildware.udea.core.fixtures.Vec2
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex

/**
 * A world with a population, a `NetIdIndex` over it, and the query engine on top.
 *
 * The world is real and so are the components: the whole claim of issue #69 is that field access
 * goes through a generated `Replicator` against a live Fleks entity, and a fake component store
 * would test the fake.
 */
internal class QueryHarness {

    val ctx = testGameContext { }

    val world: World = configureWorld { injectables { gameContext(ctx) } }

    val netIds: NetIdIndex = NetIdIndex(capacity = 4096, entityCapacity = 4096)

    val index: AgentComponentIndex = AgentComponentIndex(
        listOf(transformAccess(), healthAccess(), teamAccess(), championAccess()),
    )

    val engine: EntityQueryEngine = EntityQueryEngine(index, netIds, world)

    val detail: EntityDetail = EntityDetail(index, netIds, world)

    /** Creates an entity and gives it an id. `champion` decides whether the marker is present. */
    fun spawn(
        x: Float = 0f,
        y: Float = 0f,
        health: Float = 100f,
        team: Int = 1,
        champion: Boolean = true,
        ally: NetId = NetId.NONE,
    ): NetId {
        val entity = world.entity {
            it += Transform(Vec2(x, y))
            it += Health(current = health, max = 100f)
            it += Team(team = team, ally = ally)
            if (champion) it += Champion(level = 1)
        }
        return netIds.allocate(entity)
    }

    /** Destroys the entity behind [netId] and frees the id, so the id becomes stale. */
    fun destroy(netId: NetId) {
        val entity = requireNotNull(netIds.resolveOrNull(netId)) { "$netId is not live" }
        netIds.free(netId)
        world -= entity
    }

    /** Advances the world without any systems, which is what a bare tick loop does here. */
    fun tick(count: Int) {
        repeat(count) { world.update(ctx.clock.dt) }
    }

    fun entityOf(netId: NetId): Entity? = netIds.resolveOrNull(netId)

    /** Parses and runs one query in the text form an agent actually sends. */
    fun query(
        with: String? = null,
        where: String? = null,
        near: String? = null,
        fields: String? = null,
        limit: Int = EntityQuery.DEFAULT_LIMIT,
        offset: Int = 0,
    ): String = engine.render(
        EntityQueryParser.parse(index, with, where, near, fields, limit, offset),
    )
}
