package dev.wildware.udea.agent.host.demo

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
import dev.wildware.udea.agent.host.BuildFlags
import dev.wildware.udea.agent.host.GameIdentity
import dev.wildware.udea.agent.host.HostShutdown
import dev.wildware.udea.agent.host.RenderToolset
import dev.wildware.udea.agent.host.render.OffscreenRenderControl
import dev.wildware.udea.agent.host.ToolManifest
import dev.wildware.udea.agent.query.AgentComponentIndex
import dev.wildware.udea.agent.state.DigestSources
import dev.wildware.udea.agent.state.LoopStatus
import dev.wildware.udea.agent.state.StateDigest
import dev.wildware.udea.agent.tools.BlueprintCatalog
import dev.wildware.udea.agent.tools.DiagToolset
import dev.wildware.udea.agent.tools.EngineToolModules
import dev.wildware.udea.agent.tools.EventsToolset
import dev.wildware.udea.agent.tools.LifecycleToolset
import dev.wildware.udea.agent.tools.TimeToolset
import dev.wildware.udea.agent.tools.WorldToolset
import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.blueprint.BlueprintSpawner
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.snapshot.snapshotTimeTravel
import dev.wildware.udea.generated.CoreUdeaRegistry
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.camera.CameraRig
import dev.wildware.udea.render.control.PresentationControl
import dev.wildware.udea.render.draw.DebugDraw
import dev.wildware.udea.render.interp.Interpolator
import java.nio.file.Path

/**
 * The Phase 1 exit demo's third mode: a real, **visible** window (issue #211's third acceptance
 * criterion - Headless, Offscreen and Windowed all start, and `/health` reports each).
 *
 * ```
 * ./gradlew :udea-agent-host:udeaPhase1WindowedDemo -Pudea.agent.port=7822
 * ```
 *
 * This is [Phase1OffscreenDemo] with one line different: `RenderMode.Windowed` instead of
 * `RenderMode.Offscreen`. Every other line - the game, the camera, the render systems, the tool
 * wiring - is identical on purpose. `KoolBackend` and `RenderPipeline` do not know which mode
 * they were started in (see `KoolBackend`'s own KDoc: "There is no `if (offscreen)` anywhere
 * below, and there must never be one"), so the only thing this file is entitled to prove
 * differently from its sibling is that a *visible* window comes up, and that a capture still
 * reads the offscreen pass rather than whatever the window happens to be showing.
 */
public object Phase1WindowedDemo {

    /** Boots and blocks until the render loop exits. Kill the process to stop it. */
    @JvmStatic
    public fun main(args: Array<String>) {
        val bridge = AgentBridge()
        val module = DemoBodyModule()
        val definition = UdeaGameDef(
            registry = CoreUdeaRegistry,
            modules = listOf(module),
            timeTravel = snapshotTimeTravel(demoRegistry()),
        )
        val netIds = definition.core.netIds
        val spawner = BlueprintSpawner(
            barrier = definition.core.barrier,
            netIds = netIds,
            placement = BodyPlacement,
        )
        module.spawner = spawner

        val debugDraw = DebugDraw(enabled = false)
        val registry = RenderRegistry()
        val camera = CameraRig(
            netIds = netIds,
            poses = Interpolator(SimClock(), SimulatedPoseOnly),
            frameTime = registry.frameTime,
            worldWidth = WORLD_WIDTH,
            worldHeight = WORLD_HEIGHT,
        )
        registry.register(RenderPhase.PreRender, { camera })
        registry.register(RenderPhase.World, { resources -> BodyQuadRenderSystem(resources, camera) })
        registry.register(RenderPhase.Debug, { resources ->
            DebugGridRenderSystem(resources, camera, debugDraw)
        })

        val backend = KoolBackend.start(
            RenderMode.Windowed,
            WindowConfig(
                title = "udea-phase1-windowed-demo",
                windowWidth = WINDOW_WIDTH,
                windowHeight = WINDOW_HEIGHT,
                renderWidth = RENDER_WIDTH,
                renderHeight = RENDER_HEIGHT,
            ),
            registry,
        )

        val host = GameHost(RenderMode.Windowed, definition, backend)
        val pipeline = checkNotNull(backend.pipeline) {
            "GameHost did not build a presentation in RenderMode.Windowed"
        }
        val control = OffscreenRenderControl(PresentationControl(pipeline, camera, debugDraw))

        val census = BodyCensus(host.world)
        val timings = AgentTimings()
        val digest = StateDigest(
            bridge = bridge,
            sources = DigestSources(entities = census, loop = WindowedLoopView(host)),
            timings = timings,
        )

        val artifacts = AgentArtifacts(
            Path.of("build", "udea-agent-artifacts-windowed").toAbsolutePath(),
        )
        val shutdown = HostShutdown()
        val closeTools = LifecycleToolset(bridge, shutdown)
        val worldTools = WorldToolset(
            world = host.world,
            components = AgentComponentIndex(listOf(demoBodyAccess())),
            netIds = netIds,
            bridge = bridge,
            clock = host.ctx.clock,
            catalog = BlueprintCatalog.of(listOf(BoxBlueprint)),
            spawner = spawner,
        )
        val tools = EngineToolModules
            .wireAll(
                ToolIndex.builder(),
                worldTools,
                TimeToolset(host.time, host.ctx.clock, bridge),
                EventsToolset(bridge, host.ctx.clock, artifacts.textSpill()),
                closeTools,
                DiagToolset(
                    bridge = bridge,
                    clock = host.ctx.clock,
                    timings = timings,
                    census = census,
                    digest = digest,
                    barrier = definition.core.barrier,
                ),
            )
            .module(AgentHostTools)
            .toolset(RenderToolset(RenderMode.Windowed, control, artifacts))
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

        val identity = GameIdentity("udea-phase1-windowed-demo", "0.0.1")
        val agentHost = AgentHost.startIfRequested(
            bridge = bridge,
            config = { port ->
                AgentHostConfig(
                    port = port,
                    identity = identity,
                    renderMode = RenderMode.Windowed,
                    manifest = ToolManifest.of(identity, tools.tools),
                    artifacts = artifacts,
                    paused = { host.time.paused },
                )
            },
            agentAllowed = BuildFlags.AGENT_ALLOWED,
        )
        if (agentHost == null) {
            System.err.println("[phase1-windowed] no agent host; pass -Dudea.agent.port=<port>")
            backend.close()
            return
        }
        println(
            "[phase1-windowed] listening on http://127.0.0.1:${agentHost.port} with " +
                "${tools.tools.size} tools, rendering ${RENDER_WIDTH}x$RENDER_HEIGHT in a " +
                "${WINDOW_WIDTH}x$WINDOW_HEIGHT window",
        )
        digest.publish()
        shutdown
            .onClose("frame-loop") { loop.stop() }
            .onClose("agent-host") { agentHost.stop() }
            .onClose("render-loop") { offThread("phase1-windowed-exit") { backend.close() } }
        Runtime.getRuntime().addShutdownHook(Thread { shutdown.shutdown("jvm shutdown hook") })
        backend.drive(loop::pump)
        backend.awaitExit()
        backend.close()
    }

    private fun offThread(name: String, body: () -> Unit) {
        val thread = Thread({ runCatching(body) }, name)
        thread.isDaemon = true
        thread.start()
    }

    private const val WORLD_WIDTH: Float = 32f
    private const val WORLD_HEIGHT: Float = 18f

    private const val WINDOW_WIDTH: Int = 640
    private const val WINDOW_HEIGHT: Int = 360

    private const val RENDER_WIDTH: Int = 320
    private const val RENDER_HEIGHT: Int = 180
}

private class WindowedLoopView(private val host: GameHost) : LoopStatus {
    override val paused: Boolean get() = host.loop.paused
    override val timeScale: Float get() = host.loop.timeScale
    override val fps: Float get() = 0f
}
