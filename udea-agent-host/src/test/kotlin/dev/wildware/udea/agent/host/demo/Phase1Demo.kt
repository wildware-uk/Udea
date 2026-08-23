package dev.wildware.udea.agent.host.demo

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.EntityCreateContext
import com.github.quillraven.fleks.World
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentTimings
import dev.wildware.udea.agent.dispatch.AgentRuntime
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.agent.host.AgentArtifacts
import dev.wildware.udea.agent.host.AgentGameLoop
import dev.wildware.udea.agent.host.AgentHost
import dev.wildware.udea.agent.host.AgentHostConfig
import dev.wildware.udea.agent.host.AgentHostTools
import dev.wildware.udea.agent.host.ArtifactToolset
import dev.wildware.udea.agent.host.GameIdentity
import dev.wildware.udea.agent.host.RenderToolset
import dev.wildware.udea.agent.host.ToolManifest
import dev.wildware.udea.agent.query.AgentComponentIndex
import dev.wildware.udea.agent.query.AgentComponentType
import dev.wildware.udea.agent.query.agentComponent
import dev.wildware.udea.agent.state.ArchetypeVisitor
import dev.wildware.udea.agent.state.DigestSources
import dev.wildware.udea.agent.state.EntityCensus
import dev.wildware.udea.agent.state.LoopStatus
import dev.wildware.udea.agent.state.StateDigest
import dev.wildware.udea.agent.tools.BlueprintCatalog
import dev.wildware.udea.agent.tools.DiagToolset
import dev.wildware.udea.agent.tools.EngineToolModules
import dev.wildware.udea.agent.tools.EventsToolset
import dev.wildware.udea.agent.tools.TimeToolset
import dev.wildware.udea.agent.tools.WorldToolset
import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.blueprint.Blueprint
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.core.blueprint.BlueprintSpawner
import dev.wildware.udea.core.blueprint.SpawnPlacement
import dev.wildware.udea.core.blueprint.blueprintSpawner
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.core.replication.BitWriter
import dev.wildware.udea.core.replication.ComponentTypeId
import dev.wildware.udea.core.replication.FieldMask
import dev.wildware.udea.core.replication.FieldStore
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.replication.NoSuchFieldIndexException
import dev.wildware.udea.core.replication.Replicator
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.fleksComponentType
import dev.wildware.udea.core.snapshot.snapshotTimeTravel
import java.nio.file.Path

/**
 * The Phase 1 exit demo, as a process an agent can actually drive over HTTP.
 *
 * `./gradlew :udea-agent-host:udeaPhase1Demo -Pudea.agent.port=7820` boots a real headless
 * [GameHost], wires the four engine toolsets, binds an [AgentHost] on loopback, and pumps the
 * pair with [AgentGameLoop] until the JVM is killed. It exists because **nothing else in the
 * repository stands one up**: `moba` has no `main`, `UdeaAgentPlugin` has no plugin id and is
 * applied by no project, and the render toolset's `RenderControl` has no implementation. Until
 * those land, this is the only executable answer to "does the agent surface work end to end",
 * and a claim that it does should be checked against a transcript from this and not from a test.
 *
 * ## What is real here and what is a stand-in
 *
 * Real: the `GameHost`, the `SimBarrier`, the `NetIdIndex`, the snapshot ring over a real
 * `Replicator`, the `ToolIndex`, the `AgentRuntime`, the `AgentHost` and every tool. The command
 * path is the shipped one — HTTP handler, bridge queue, barrier drain, dispatcher — with no
 * shortcut, because this class holds no reference to the tool index at all.
 *
 * A stand-in: the *game*. One component, one blueprint, one drifting system, and a census kept
 * by hand. A real game supplies those; the surface does not care which, and a demo that needed a
 * real game would be testing the game.
 */
public object Phase1Demo {

    /** Boots and blocks. Kill the process to stop it. */
    @JvmStatic
    public fun main(args: Array<String>) {
        val bridge = AgentBridge()
        val module = DemoModule()
        val definition = UdeaGameDef(
            modules = listOf(module),
            timeTravel = snapshotTimeTravel(registry()),
        )
        val netIds = definition.core.netIds
        val spawner = BlueprintSpawner(
            barrier = definition.core.barrier,
            netIds = netIds,
            placement = PositionPlacement,
        )
        module.spawner = spawner

        val host = GameHost(RenderMode.Headless, definition)
        val census = DemoCensus(host.world)
        val timings = AgentTimings()
        val digest = StateDigest(
            bridge = bridge,
            sources = DigestSources(entities = census, loop = LoopView(host)),
            timings = timings,
        )

        val worldTools = WorldToolset(
            world = host.world,
            components = AgentComponentIndex(listOf(positionAccess())),
            netIds = netIds,
            bridge = bridge,
            clock = host.ctx.clock,
            catalog = BlueprintCatalog.of(listOf(GruntBlueprint)),
            spawner = spawner,
        )
        val timeTools = TimeToolset(host.time, host.ctx.clock, bridge)
        val eventTools = EventsToolset(bridge, host.ctx.clock)
        val diagTools = DiagToolset(
            bridge = bridge,
            clock = host.ctx.clock,
            timings = timings,
            census = census,
            digest = digest,
            barrier = definition.core.barrier,
        )
        // The host's own toolset too, so `render.*` is reachable and answers for itself. It has
        // no `RenderControl`: nothing in `udea-render` implements that interface yet, so every
        // render tool here answers the typed `no_render_context` - which is the correct answer
        // for `RenderMode.Headless` in any case, and is what the demo transcript should show.
        val artifacts = AgentArtifacts(Path.of("build", "udea-agent-artifacts").toAbsolutePath())
        val tools = EngineToolModules
            .wireAll(ToolIndex.builder(), worldTools, timeTools, eventTools, diagTools)
            .module(AgentHostTools)
            .toolset(RenderToolset(RenderMode.Headless, control = null, artifacts = artifacts))
            .toolset(ArtifactToolset(artifacts))
            .build()

        val runtime = AgentRuntime(
            bridge = bridge,
            tools = tools,
            world = host.world,
            ctx = host.ctx,
            digest = digest,
        )
        val loop = AgentGameLoop(host, runtime)

        val identity = GameIdentity("udea-phase1-demo", "0.0.1")
        val agentHost = AgentHost.startIfRequested(
            bridge = bridge,
            config = { port ->
                AgentHostConfig(
                    port = port,
                    identity = identity,
                    renderMode = RenderMode.Headless,
                    manifest = ToolManifest.of(identity, tools.tools),
                    artifacts = artifacts,
                    paused = { host.time.paused },
                )
            },
        )
        if (agentHost == null) {
            System.err.println("[phase1-demo] no agent host; pass -Dudea.agent.port=<port>")
            return
        }
        println("[phase1-demo] listening on http://127.0.0.1:${agentHost.port} with ${tools.tools.size} tools")
        // Publish once before the first frame, so a `/state` that beats the loop is a document
        // rather than an empty string.
        digest.publish()
        Runtime.getRuntime().addShutdownHook(Thread { loop.stop() })
        loop.run()
    }

    /** The snapshot ring's view of [Position]. One component is enough to rewind. */
    private fun registry(): ComponentRegistry = ComponentRegistry(
        listOf(
            fleksComponentType(
                PositionReplicator,
                ComponentSchema.of(
                    PositionReplicator,
                    "Position",
                    listOf(FieldKind.Float, FieldKind.Float, FieldKind.Float),
                ),
                Position,
            ) { Position() },
        ),
    )

    /** [Position], with x and y writable and `hp` not — so `field_not_writable` is reachable. */
    private fun positionAccess(): AgentComponentType = agentComponent(
        name = "Position",
        replicator = PositionReplicator,
        componentType = Position,
        agentWritableFields = setOf(PositionReplicator.X, PositionReplicator.Y),
    )
}

/** Where a thing is, and how healthy it is. Three floats, so the ring has something to restore. */
internal class Position(
    var x: Float = 0f,
    var y: Float = 0f,
    var hp: Float = 100f,
) : Component<Position> {
    override fun type(): ComponentType<Position> = Position

    companion object : ComponentType<Position>()
}

/** Hand-written, as every replicator is until `udea-codegen` is pointed at this module. */
internal object PositionReplicator : Replicator<Position> {
    const val X = 0
    const val Y = 1
    const val HP = 2
    const val FIELD_COUNT = 3

    override val typeId: ComponentTypeId = ComponentTypeId(1)

    override val fieldNames: List<String> = listOf("x", "y", "hp")

    override val netMask: FieldMask = MaskOps.of(X, Y)

    override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override fun capture(component: Position, store: FieldStore, slot: Int) {
        store.setFloat(slot, X, component.x)
        store.setFloat(slot, Y, component.y)
        store.setFloat(slot, HP, component.hp)
    }

    override fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask {
        var mask = MaskOps.EMPTY
        for (field in 0 until FIELD_COUNT) {
            if (store.getFloat(slotA, field) != store.getFloat(slotB, field)) {
                mask = MaskOps.set(mask, field)
            }
        }
        return mask
    }

    override fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter) {
        for (field in 0 until FIELD_COUNT) {
            if (MaskOps.test(mask, field)) out.writeFloat(store.getFloat(slot, field))
        }
    }

    override fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask {
        var mask = MaskOps.EMPTY
        for (field in 0 until FIELD_COUNT) {
            store.setFloat(slot, field, src.readFloat())
            mask = MaskOps.set(mask, field)
        }
        return mask
    }

    override fun apply(store: FieldStore, slot: Int, component: Position, mask: FieldMask) {
        if (MaskOps.test(mask, X)) component.x = store.getFloat(slot, X)
        if (MaskOps.test(mask, Y)) component.y = store.getFloat(slot, Y)
        if (MaskOps.test(mask, HP)) component.hp = store.getFloat(slot, HP)
    }

    override fun getField(component: Position, fieldIndex: Int): Any? = when (fieldIndex) {
        X -> component.x
        Y -> component.y
        HP -> component.hp
        else -> throw NoSuchFieldIndexException("Position", fieldIndex, FIELD_COUNT)
    }

    override fun setField(component: Position, fieldIndex: Int, value: Any?) {
        val float = requireNotNull(value as? Float) { "Position fields are floats, got $value" }
        when (fieldIndex) {
            X -> component.x = float
            Y -> component.y = float
            HP -> component.hp = float
            else -> throw NoSuchFieldIndexException("Position", fieldIndex, FIELD_COUNT)
        }
    }
}

/** Publishes the spawner on the context, which is where `ctx.blueprints` reads it from. */
private class DemoModule : UdeaModule {

    var spawner: BlueprintSpawner? = null

    override fun context(builder: GameContextBuilder) {
        builder.blueprintSpawner(checkNotNull(spawner) { "wire the spawner before building" })
    }
}

/** This game's spatial component is [Position]. */
private object PositionPlacement : SpawnPlacement {

    override fun defaultIfAbsent(world: World, entity: Entity) {
        with(world) {
            if (entity.getOrNull(Position) == null) entity.configure { it += Position() }
        }
    }

    override fun moveTo(world: World, entity: Entity, x: Float, y: Float) {
        with(world) {
            val position = entity[Position]
            position.x = x
            position.y = y
        }
    }
}

/** The one thing an agent can create here. */
private object GruntBlueprint : Blueprint {
    override val id: BlueprintId = BlueprintId("grunt")

    override fun configure(context: EntityCreateContext, entity: Entity) {
        with(context) { entity += Position(hp = 40f) }
    }
}

/**
 * Counts what Fleks already counts.
 *
 * `EntityCensus`'s contract is that counts are maintained incrementally and never by walking the
 * world, and `World.numEntities` honours that - Fleks keeps it as a field. It is *not* the
 * archetype bookkeeping a real game does: there is one blueprint here, so one bucket, and a game
 * with several must count them at its own spawn and despawn sites.
 */
private class DemoCensus(private val world: World) : EntityCensus {

    override val entityCount: Int get() = world.numEntities

    override fun forEachArchetype(visitor: ArchetypeVisitor) {
        visitor.visit("grunt", entityCount)
    }
}

/** The digest's window onto the real loop, so `/state.paused` is the loop's own answer. */
private class LoopView(private val host: GameHost) : LoopStatus {
    override val paused: Boolean get() = host.loop.paused
    override val timeScale: Float get() = host.loop.timeScale
    override val fps: Float get() = 0f
}
