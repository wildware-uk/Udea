package dev.wildware.udea.agent.tools

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.EntityCreateContext
import com.github.quillraven.fleks.World
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentError
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentTimings
import dev.wildware.udea.agent.Champion
import dev.wildware.udea.agent.ChampionReplicator
import dev.wildware.udea.agent.Health
import dev.wildware.udea.agent.HealthReplicator
import dev.wildware.udea.agent.Team
import dev.wildware.udea.agent.TeamReplicator
import dev.wildware.udea.agent.Transform
import dev.wildware.udea.agent.TransformReplicator
import dev.wildware.udea.agent.championAccess
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.agent.harness.SimHarness
import dev.wildware.udea.agent.healthAccess
import dev.wildware.udea.agent.query.AgentComponentIndex
import dev.wildware.udea.agent.state.ArchetypeVisitor
import dev.wildware.udea.agent.state.DigestSources
import dev.wildware.udea.agent.state.EntityCensus
import dev.wildware.udea.agent.state.StateDigest
import dev.wildware.udea.agent.teamAccess
import dev.wildware.udea.agent.transformAccess
import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.blueprint.Blueprint
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.core.blueprint.BlueprintSpawner
import dev.wildware.udea.core.blueprint.SpawnPlacement
import dev.wildware.udea.core.blueprint.blueprintSpawner
import dev.wildware.udea.core.fixtures.Vec2
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.fleksComponentType
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.snapshotTimeTravel
import kotlin.test.assertIs

/**
 * A real headless game with the four engine toolsets wired, driven through [SimHarness].
 *
 * Everything here is the shipped path: a real [GameHost] in [RenderMode.Headless], a real
 * `SimBarrier`, a real [NetIdIndex], a real snapshot ring over real `Replicator`s, and a
 * [ToolIndex] built the way a host builds one. No tool is ever called directly - [call] submits
 * to the bridge, exactly as an HTTP handler would, which is the whole claim of issue #73.
 */
internal class ToolsetHarness(withSnapshotRing: Boolean = true) {

    val bridge: AgentBridge = AgentBridge()

    private val module = ToolsetModule()

    private val definition = UdeaGameDef(
        modules = listOf(module),
        timeTravel = if (withSnapshotRing) snapshotTimeTravel(registry()) else null,
    )

    val netIds: NetIdIndex = definition.core.netIds

    val spawner: BlueprintSpawner = BlueprintSpawner(
        barrier = definition.core.barrier,
        netIds = netIds,
        placement = TransformPlacement,
    )

    val components: AgentComponentIndex = AgentComponentIndex(
        listOf(transformAccess(), healthAccess(), teamAccess(), championAccess()),
    )

    val host: GameHost

    val census: MutableCensus = MutableCensus()

    val timings: AgentTimings = AgentTimings()

    val digest: StateDigest

    val world: World

    val worldTools: WorldToolset

    val timeTools: TimeToolset

    val eventTools: EventsToolset

    val diagTools: DiagToolset

    val sim: SimHarness

    init {
        module.spawner = spawner
        host = GameHost(RenderMode.Headless, definition)
        world = host.world
        digest = StateDigest(
            bridge = bridge,
            sources = DigestSources(entities = census),
            timings = timings,
        )
        worldTools = WorldToolset(
            world = world,
            components = components,
            netIds = netIds,
            bridge = bridge,
            clock = host.ctx.clock,
            catalog = BlueprintCatalog.of(listOf(GruntBlueprint, ChampionBlueprint)),
            spawner = spawner,
        )
        timeTools = TimeToolset(host.time, host.ctx.clock, bridge)
        eventTools = EventsToolset(bridge, host.ctx.clock)
        diagTools = DiagToolset(
            bridge = bridge,
            clock = host.ctx.clock,
            timings = timings,
            census = census,
            digest = digest,
            barrier = definition.core.barrier,
        )
        val tools = EngineToolModules
            .wireAll(ToolIndex.builder(), worldTools, timeTools, eventTools, diagTools)
            .build()
        sim = SimHarness(host, bridge, tools, digest)
    }

    /** Submits [name] through the bridge and returns its typed answer. */
    fun call(name: String, vararg args: Pair<String, String>): AgentResult = sim.call(name, *args)

    /**
     * [call], asserting success, and returning the rendered result document.
     *
     * The result is captured before the assertion message names it. Building the message inline
     * would call the tool a *second* time - eagerly, because a message argument is a value - and
     * a helper that silently doubles every mutation is a helper that makes an audit test lie.
     */
    fun ok(name: String, vararg args: Pair<String, String>): String {
        val result = call(name, *args)
        return assertIs<AgentResult.Ok>(result, "$name failed: $result").json
    }

    /** [call], asserting refusal, and returning the typed error. */
    fun failure(name: String, vararg args: Pair<String, String>): AgentError {
        val result = call(name, *args)
        return assertIs<AgentResult.Failed>(result, "$name unexpectedly succeeded: $result").error
    }

    /** Creates an entity directly, bypassing the tools, so a read can be tested against a known world. */
    fun place(x: Float = 0f, y: Float = 0f, health: Float = 100f, team: Int = 1): NetId {
        val entity = world.entity {
            it += Transform(Vec2(x, y))
            it += Health(current = health, max = 100f)
            it += Team(team = team, ally = NetId.NONE)
            it += Champion(level = 1)
        }
        census.spawned()
        return netIds.allocate(entity)
    }

    private companion object {

        fun registry(): ComponentRegistry = ComponentRegistry(
            listOf(
                fleksComponentType(
                    TransformReplicator,
                    ComponentSchema.of(
                        TransformReplicator,
                        "Transform",
                        listOf(FieldKind.Float, FieldKind.Float, FieldKind.Float),
                    ),
                    Transform,
                ) { Transform(Vec2()) },
                fleksComponentType(
                    HealthReplicator,
                    ComponentSchema.of(
                        HealthReplicator,
                        "Health",
                        listOf(FieldKind.Float, FieldKind.Float),
                    ),
                    Health,
                ) { Health() },
                fleksComponentType(
                    TeamReplicator,
                    ComponentSchema.of(TeamReplicator, "Team", listOf(FieldKind.Int, FieldKind.NetId)),
                    Team,
                ) { Team() },
                fleksComponentType(
                    ChampionReplicator,
                    ComponentSchema.of(ChampionReplicator, "Champion", listOf(FieldKind.Int)),
                    Champion,
                ) { Champion() },
            ),
        )
    }
}

/** Publishes the spawner on the context, which is where `ctx.blueprints` reads it from. */
private class ToolsetModule : UdeaModule {

    var spawner: BlueprintSpawner? = null

    override fun context(builder: GameContextBuilder) {
        builder.blueprintSpawner(checkNotNull(spawner) { "wire the spawner before building" })
    }
}

/** This game's spatial component is [Transform], which is what `SpawnPlacement` exists to say. */
private object TransformPlacement : SpawnPlacement {

    override fun defaultIfAbsent(world: World, entity: Entity) {
        with(world) {
            if (entity.getOrNull(Transform) == null) {
                entity.configure { it += Transform(Vec2()) }
            }
        }
    }

    override fun moveTo(world: World, entity: Entity, x: Float, y: Float) {
        with(world) {
            val transform = entity[Transform]
            transform.position.x = x
            transform.position.y = y
        }
    }
}

/** A census a test drives by hand, standing in for the spawn/despawn bookkeeping a game does. */
internal class MutableCensus : EntityCensus {

    override var entityCount: Int = 0
        private set

    fun spawned() {
        entityCount++
    }

    override fun forEachArchetype(visitor: ArchetypeVisitor) {
        visitor.visit("all", entityCount)
    }
}

/** A blueprint that makes something with health and a team. */
internal object GruntBlueprint : Blueprint {
    override val id: BlueprintId = BlueprintId("grunt")

    override fun configure(context: EntityCreateContext, entity: Entity) {
        with(context) {
            entity += Health(current = 40f, max = 40f)
            entity += Team(team = 2, ally = NetId.NONE)
        }
    }
}

/** A second blueprint, so `list_blueprints` and the did-you-mean have something to choose between. */
internal object ChampionBlueprint : Blueprint {
    override val id: BlueprintId = BlueprintId("champion")

    override fun configure(context: EntityCreateContext, entity: Entity) {
        with(context) {
            entity += Health(current = 600f, max = 600f)
            entity += Team(team = 1, ally = NetId.NONE)
            entity += Champion(level = 1)
        }
    }
}
