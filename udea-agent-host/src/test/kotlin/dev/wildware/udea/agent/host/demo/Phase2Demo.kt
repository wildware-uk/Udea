package dev.wildware.udea.agent.host.demo

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.EntityCreateContext
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentTimings
import dev.wildware.udea.agent.assets.AssetHotReload
import dev.wildware.udea.agent.assets.AssetToolModule
import dev.wildware.udea.agent.assets.AssetsToolset
import dev.wildware.udea.agent.dispatch.AgentRuntime
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.agent.host.AgentArtifacts
import dev.wildware.udea.agent.host.AgentGameLoop
import dev.wildware.udea.agent.host.AgentHost
import dev.wildware.udea.agent.host.AgentHostConfig
import dev.wildware.udea.agent.host.AgentHostTools
import dev.wildware.udea.agent.host.BuildFlags
import dev.wildware.udea.agent.host.ArtifactToolset
import dev.wildware.udea.agent.host.GameIdentity
import dev.wildware.udea.agent.host.HostShutdown
import dev.wildware.udea.agent.host.RenderToolset
import dev.wildware.udea.agent.host.ToolManifest
import dev.wildware.udea.agent.query.AgentComponentIndex
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
import dev.wildware.udea.agent.tools.LifecycleToolset
import dev.wildware.udea.agent.tools.TimeToolset
import dev.wildware.udea.agent.tools.WorldToolset
import dev.wildware.udea.assets.AssetData
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.AssetRegistry
import dev.wildware.udea.assets.SpriteSheet
import dev.wildware.udea.assets.compiler.daemon.AssetDaemon
import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.blueprint.Blueprint
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.core.blueprint.BlueprintSpawner
import dev.wildware.udea.core.blueprint.SpawnPosition
import dev.wildware.udea.core.blueprint.blueprintSpawner
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.loop.barrier
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.fleksComponentType
import dev.wildware.udea.core.snapshot.snapshotTimeTravel
import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * The Phase 2 exit demo: an agent edits an asset and the **running** game changes.
 *
 * `./gradlew :udea-agent-host:udeaPhase2Demo -Pudea.agent.port=7830` boots a real headless
 * [GameHost], a real warm [AssetDaemon] over a real `.udea.kts` tree, binds an [AgentHost] on
 * loopback and blocks. It is [Phase1Demo] plus the two things spec 6's Phase 2 demo needs and
 * Phase 1 had no reason to have:
 *
 * - an [AssetDaemon] and the `assets.*` toolset, wired through [AssetToolModule];
 * - a **system that reads an asset value every tick**, [SpriteScaleSystem].
 *
 * ## Why a system, and why `Position.hp`
 *
 * "The running game reflects it" is the claim, and only the simulation can make it. A transcript
 * that showed `assets.get` returning the new number would show the *daemon* holding it, which
 * proves nothing about the game. So the demo's one system reads
 * `SpriteSheet("character/orc_idle").scale` out of the live [AssetRegistry] on every tick and
 * writes `scale * 100` into `Position.hp` - an ordinary replicated field that `world.get_component`
 * already publishes and that no agent may write (`hp` is outside `agentWritableFields`, see
 * [Phase1Demo.positionAccess]). An observer therefore cannot fake the number through the agent
 * surface: the only way `hp` moves is that a system read a new asset value.
 *
 * The demo seeds its own asset tree under `build/`, so the transcript is reproducible from a clean
 * checkout and an agent's edits do not dirty the repository.
 */
public object Phase2Demo {

    /** The asset whose value the running simulation reads every tick. */
    private const val PROBED_ID: String = "character/orc_idle"

    /** Boots and blocks. `close` over HTTP, or kill the process. */
    @JvmStatic
    public fun main(args: Array<String>) {
        val instance = start(
            port = System.getProperty("udea.agent.port")?.toIntOrNull(),
            scratchName = "phase2-demo",
        )
        if (instance == null) {
            System.err.println("[phase2-demo] no agent host; pass -Pudea.agent.port=<port>")
            return
        }
        Runtime.getRuntime().addShutdownHook(Thread { instance.shutdown.shutdown("jvm shutdown hook") })
        // Blocks on the loop's own thread, which is what `close` has to be able to end.
        instance.awaitClose()
        println("[phase2-demo] closed: ${instance.shutdown.reason ?: "the loop stopped on its own"}")
    }

    /**
     * Everything [main] boots, as an object a test can also hold.
     *
     * The refactor is the point: `Phase2ExitTest` asserts the two Phase 2 budgets over HTTP
     * against *this* wiring, so the transcript a human runs and the gate CI runs exercise one
     * assembly rather than two that drift.
     *
     * @param port loopback port to bind, or `null` to bind none (and return `null`). `0` picks a
     *   free one, which is what a test wants and what a demo must never be given.
     * @return the running instance, or `null` when [port] is `null`.
     */
    internal fun start(port: Int?, scratchName: String): Phase2Instance? {
        val repoRoot = Path(System.getProperty("udea.repoRoot") ?: ".").toAbsolutePath().normalize()
        val assetRoot = seedAssets(repoRoot.resolve("udea-agent-host/build/tmp/$scratchName/assets"))
        val daemon = AssetDaemon(
            repoRoot = repoRoot,
            assetRoot = assetRoot,
            scriptClasspath = checkNotNull(System.getProperty("udea.assetsCompiler.classpath")) {
                "system property 'udea.assetsCompiler.classpath' is not set; the udeaPhase2Demo " +
                    "task sets it"
            }.split(File.pathSeparatorChar).filter { it.isNotBlank() }.map { Path(it) },
            cacheDirectory = repoRoot.resolve("udea-agent-host/build/tmp/$scratchName/cache"),
        )
        val started = daemon.start()
        println("[phase2-demo] daemon start: ok=${started.ok} ${started.durationMs}ms ${daemon.ids.size} assets")
        check(started.ok) {
            "the demo corpus must be valid: " + started.diagnostics.joinToString("; ")
        }

        val registry = AssetRegistry(
            daemon.ids.mapNotNull { daemon.value(it) }.toTypedArray<AssetData>(),
            contentHash = ByteArray(32),
        )

        val artifacts = AgentArtifacts(Path.of("build", "udea-agent-artifacts").toAbsolutePath())
        // The store, not `TextSpill.NONE`, and constructed before the bridge for that reason: an
        // `assets.write` rejection is thousands of characters against a 1280-character digest
        // ceiling, so without somewhere to put it the diagnostic an agent asked for never reaches
        // it. See `AgentBridge.complete`.
        val bridge = AgentBridge(resultSpill = artifacts.textSpill())
        val module = AssetProbeModule(registry)
        val definition = UdeaGameDef(
            modules = listOf(module),
            timeTravel = snapshotTimeTravel(registry()),
        )
        val netIds = definition.core.netIds
        val spawner = BlueprintSpawner(
            barrier = definition.core.barrier,
            netIds = netIds,
            placement = PositionPlacement2,
        )
        module.spawner = spawner

        val host = GameHost(RenderMode.Headless, definition)
        // One entity, spawned through the real spawner before the loop starts, so it carries a
        // `NetId` and `world.query_entities` has a subject on the very first call rather than
        // after an agent remembers to spawn one. The spawn lands on the barrier and is drained by
        // the first `Simulation.step`, which is the same path `world.spawn_blueprint` takes.
        val probeId = spawner.spawn(ProbeBlueprint, SpawnPosition(1f, 1f))

        val hotReload = AssetHotReload(registry, host.ctx.barrier, host.ctx.clock)
        val census = ProbeCensus(host.world)
        val timings = AgentTimings()
        val digest = StateDigest(
            bridge = bridge,
            sources = DigestSources(entities = census, loop = LoopView2(host)),
            timings = timings,
        )
        val shutdown = HostShutdown()
        val tools = EngineToolModules
            .wireAll(
                ToolIndex.builder(),
                WorldToolset(
                    world = host.world,
                    components = AgentComponentIndex(
                        listOf(
                            agentComponent(
                                name = "Position",
                                replicator = PositionReplicator,
                                componentType = Position,
                                agentWritableFields = setOf(PositionReplicator.X, PositionReplicator.Y),
                            ),
                        ),
                    ),
                    netIds = netIds,
                    bridge = bridge,
                    clock = host.ctx.clock,
                    catalog = BlueprintCatalog.of(listOf(ProbeBlueprint)),
                    spawner = spawner,
                ),
                TimeToolset(host.time, host.ctx.clock, bridge),
                EventsToolset(bridge, host.ctx.clock, artifacts.textSpill()),
                DiagToolset(
                    bridge = bridge,
                    clock = host.ctx.clock,
                    timings = timings,
                    census = census,
                    digest = digest,
                    barrier = definition.core.barrier,
                ),
                LifecycleToolset(bridge, shutdown),
            )
            .module(AgentHostTools)
            .toolset(RenderToolset(RenderMode.Headless, control = null, artifacts = artifacts))
            .toolset(ArtifactToolset(artifacts))
            .let { AssetToolModule.wire(it, AssetsToolset(daemon, hotReload)) }
            .build()

        val runtime = AgentRuntime(
            bridge = bridge,
            tools = tools,
            world = host.world,
            ctx = host.ctx,
            digest = digest,
        )
        val loop = AgentGameLoop(host, runtime)
        val identity = GameIdentity("udea-phase2-demo", "0.0.1")
        if (port == null) return null
        // The build flag is still consulted, and stated rather than defaulted: `AgentHost.start`
        // is used instead of `startIfRequested` only because a test needs port 0, and losing the
        // gate along with the port lookup would be a silent regression.
        check(BuildFlags.AGENT_ALLOWED) {
            "this build does not allow an agent surface; BuildFlags.AGENT_ALLOWED is false"
        }
        val agentHost = AgentHost.start(
            bridge = bridge,
            config = AgentHostConfig(
                port = port,
                identity = identity,
                renderMode = RenderMode.Headless,
                manifest = ToolManifest.of(identity, tools.tools),
                artifacts = artifacts,
                paused = { host.time.paused },
            ),
        )
        println("[phase2-demo] assetRoot=$assetRoot")
        println("[phase2-demo] probe entity netId=${probeId.raw}")
        println("[phase2-demo] listening on http://127.0.0.1:${agentHost.port} with ${tools.tools.size} tools")
        digest.publish()
        shutdown
            .onClose("frame-loop") { loop.stop() }
            .onClose("agent-host") { agentHost.stop() }
        // The loop runs on a thread of its own rather than on the caller's, because that is what
        // makes `close` provable: a command that arrives on the HTTP thread has to make *this*
        // thread return, and a caller that had donated its own stack could not observe that.
        val thread = Thread({ loop.run() }, "udea-phase2-demo-loop").apply {
            isDaemon = true
            start()
        }
        return Phase2Instance(agentHost.port, probeId.raw, assetRoot, shutdown, thread)
    }

    /**
     * Writes the demo's asset tree, replacing whatever a previous run left.
     *
     * Rewritten every boot on purpose: an agent's `assets.patch` edits these files for real, so a
     * second run that inherited the first run's edits would start from a different number and the
     * transcript would stop being reproducible.
     */
    private fun seedAssets(root: Path): Path {
        root.toFile().deleteRecursively()
        root.resolve("character").createDirectories()
        root.resolve("character/orc.udea.kts").writeText(
            """
            spriteSheet(name = "orc_idle", spritePath = "/sprites/orc/idle.png", rows = 1, columns = 6, scale = 0.02f)
            spriteAnimation(name = "orc_idle_anim", sheet = reference("character/orc_idle"))
            soundCue(name = "orc_hit", pitchVariance = 0.3f, volume = 1.0f, sounds = listOf("/sounds/orc/hit.ogg"))

            """.trimIndent(),
        )
        return root.toAbsolutePath().normalize()
    }

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

    /**
     * Reads the live asset graph every tick and publishes what it found.
     *
     * The whole demo turns on this class. It holds the [AssetRegistry] the barrier mutates - not a
     * copy of a value read at start-up - so a delta that lands at the top of a tick is visible to
     * the very next `onTick`. `hp` is written unconditionally rather than only on change, because
     * "the number in the world equals the number in the file" is the claim, and a system that only
     * wrote on change would leave a stale field looking correct after a rollback.
     */
    private class SpriteScaleSystem(private val registry: AssetRegistry) : SimSystem() {

        private val probes = family { all(Position) }

        override fun onTick() {
            val sheet = registry.find(AssetId(PROBED_ID)) as? SpriteSheet ?: return
            probes.forEach { entity -> entity[Position].hp = sheet.scale * 100f }
        }
    }

    /** Publishes the spawner and declares the one system. */
    private class AssetProbeModule(private val assets: AssetRegistry) : UdeaModule {

        var spawner: BlueprintSpawner? = null

        override fun context(builder: GameContextBuilder) {
            builder.blueprintSpawner(checkNotNull(spawner) { "wire the spawner before building" })
        }

        override fun simulation(registry: SimRegistry) {
            registry.add(SimPhase.Gameplay, { SpriteScaleSystem(assets) })
        }
    }
}

/** The one thing this demo spawns: an entity carrying [Position], so a system can write its `hp`. */
private object ProbeBlueprint : Blueprint {
    override val id: BlueprintId = BlueprintId("probe")

    override fun configure(context: EntityCreateContext, entity: Entity) {
        with(context) { entity += Position(hp = 0f) }
    }
}

/** [Phase1Demo]'s placement, again: this demo's spatial component is also [Position]. */
private object PositionPlacement2 : dev.wildware.udea.core.blueprint.SpawnPlacement {

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

/** One bucket, counted the way Fleks already counts. */
private class ProbeCensus(private val world: World) : EntityCensus {

    override val entityCount: Int get() = world.numEntities

    override fun forEachArchetype(visitor: ArchetypeVisitor) {
        visitor.visit("probe", entityCount)
    }
}

/** The digest's window onto the real loop. */
private class LoopView2(private val host: GameHost) : LoopStatus {
    override val paused: Boolean get() = host.loop.paused
    override val timeScale: Float get() = host.loop.timeScale
    override val fps: Float get() = 0f
}

/**
 * A running [Phase2Demo], for a caller that is not `main`.
 *
 * Deliberately thin: it publishes the port, the entity to look at and the teardown, and nothing
 * else. Anything richer would be a back door around HTTP, and the whole claim under test is that
 * an agent gets there over HTTP.
 */
internal class Phase2Instance(
    val port: Int,
    /** `NetId.raw` of the one entity carrying `Position`, for `world.get_component`. */
    val probeNetId: Int,
    val assetRoot: java.nio.file.Path,
    val shutdown: dev.wildware.udea.agent.host.HostShutdown,
    private val loopThread: Thread,
) : AutoCloseable {

    /** Blocks until the loop thread returns, which only `close` (or a kill) causes. */
    fun awaitClose() {
        loopThread.join()
    }

    /** Whether the loop thread has finished. */
    fun loopFinished(): Boolean = !loopThread.isAlive

    override fun close() {
        shutdown.shutdown("test teardown")
        loopThread.join(5_000)
    }
}
