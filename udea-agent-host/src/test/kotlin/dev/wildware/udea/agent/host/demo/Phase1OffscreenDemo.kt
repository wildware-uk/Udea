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
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.backend.Lwjgl3Backend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.camera.CameraRig
import dev.wildware.udea.render.control.PresentationControl
import dev.wildware.udea.render.draw.DebugDraw
import dev.wildware.udea.render.interp.Interpolator
import java.nio.file.Path

/**
 * The Phase 1 exit demo with **pixels**: a real LWJGL3 context behind a hidden window, driven over
 * HTTP by an agent that screenshots, rewinds and screenshots again.
 *
 * ```
 * ./gradlew :udea-agent-host:udeaPhase1OffscreenDemo -Pudea.agent.port=7821
 * ```
 *
 * `Phase1Demo` is the other half and stays `RenderMode.Headless`: it proves the numbers — pause,
 * spawn, step, query, rewind — and answers `no_render_context` from every render tool, which is
 * the correct answer in that mode. This one proves the picture. Both exist because nothing in
 * the repository stands an instance up yet: `moba` has no `main` and `UdeaAgentPlugin` has no
 * plugin id, so a demo entry point is where a host is assembled today.
 *
 * ## What is real here
 *
 * Everything except the game. A genuine `Lwjgl3Backend` with a hidden window and a real driver; a
 * real `FrameBuffer` the frame is drawn into and the capture is read out of; the shipped
 * `RenderPipeline`, `CameraRig`, `FrameCaptureSlot` and `GlPixelSource`; the shipped command path
 * — HTTP handler, bridge queue, barrier drain, dispatcher, `answerLater`. The game is one
 * component, one blueprint and two render systems, because a demo that needed a real game would
 * be testing the game.
 *
 * ## The frame, and why the loop is driven from the render thread
 *
 * ```
 * GL thread: AgentGameLoop.pump(dt)
 *              AgentRuntime.beforeFrame()      queue -> barrier
 *              GameHost.frame(dt)              ticks; the barrier drain runs `render.screenshot`,
 *                                              which QUEUES a capture and returns
 *                                              ...then renders, and the capture point serves it
 *              AgentRuntime.afterFrame(ticks)  deferred work: the queued capture is collected,
 *                                              filed in the artifact store, and the command
 *                                              completes with its id
 * ```
 *
 * One thread, one frame, no blocking anywhere. That is why `Lwjgl3Backend.drive` is handed
 * `loop::pump` and not `host::frame`: driving with the latter would leave nothing to move
 * commands from the bridge queue onto the barrier, and every tool call would sit unexecuted with
 * `/health` reporting a cheerful, frozen instance.
 */
public object Phase1OffscreenDemo {

    /** Boots and blocks until the render loop exits. Kill the process to stop it. */
    @JvmStatic
    public fun main(args: Array<String>) {
        val bridge = AgentBridge()
        val module = DemoBodyModule()
        val definition = UdeaGameDef(
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
        // Constructed here rather than inside the factory because the control surface needs the
        // same instance the pipeline will draw with, and `RenderRegistry.build` does not hand
        // instances back. Nothing in either constructor touches GL - an `OrthographicCamera` and
        // an `ExtendViewport` are arithmetic until `viewport.apply()` runs on the render thread.
        val camera = CameraRig(
            netIds = netIds,
            // A clock of its own, and it is never read for a value that matters: `SimulatedPoseOnly`
            // reports every frame as a restore frame, and an entity spawned by this demo has no
            // `Interp` component, so `Interpolator` takes the "draw at the simulated pose" branch
            // either way. The demo pauses for every capture, so there is nothing to interpolate.
            interpolator = Interpolator(SimClock(), SimulatedPoseOnly),
            frameTime = registry.frameTime,
            worldWidth = WORLD_WIDTH,
            worldHeight = WORLD_HEIGHT,
        )
        // The factory is passed positionally rather than as a trailing lambda: the trailing
        // lambda of `register` is the ordering constraint block, and a factory written there
        // compiles to a constraint that registers nothing.
        registry.register(RenderPhase.PreRender, { camera })
        registry.register(RenderPhase.World, { resources -> BodyQuadRenderSystem(resources, camera) })
        registry.register(RenderPhase.Debug, { resources ->
            DebugGridRenderSystem(resources, camera, debugDraw)
        })

        val backend = Lwjgl3Backend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-phase1-offscreen-demo",
                windowWidth = WINDOW_WIDTH,
                windowHeight = WINDOW_HEIGHT,
                renderWidth = RENDER_WIDTH,
                renderHeight = RENDER_HEIGHT,
            ),
            registry,
        )

        val host = GameHost(RenderMode.Offscreen, definition, backend)
        val pipeline = checkNotNull(backend.pipeline) {
            "GameHost did not build a presentation in RenderMode.Offscreen"
        }
        val control = OffscreenRenderControl(PresentationControl(pipeline, camera, debugDraw))

        val census = BodyCensus(host.world)
        val timings = AgentTimings()
        val digest = StateDigest(
            bridge = bridge,
            sources = DigestSources(entities = census, loop = OffscreenLoopView(host)),
            timings = timings,
        )

        val artifacts = AgentArtifacts(
            Path.of("build", "udea-agent-artifacts-offscreen").toAbsolutePath(),
        )
        // Appended to below, once the loop and the host it has to stop exist.
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
            .toolset(RenderToolset(RenderMode.Offscreen, control, artifacts))
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

        val identity = GameIdentity("udea-phase1-offscreen-demo", "0.0.1")
        val agentHost = AgentHost.startIfRequested(
            bridge = bridge,
            config = { port ->
                AgentHostConfig(
                    port = port,
                    identity = identity,
                    renderMode = RenderMode.Offscreen,
                    manifest = ToolManifest.of(identity, tools.tools),
                    artifacts = artifacts,
                    paused = { host.time.paused },
                )
            },
            // Stated rather than defaulted; see `BuildFlags`. A test-source-set demo has no
            // generated per-variant flag and the library constant is the honest answer.
            agentAllowed = BuildFlags.AGENT_ALLOWED,
        )
        if (agentHost == null) {
            System.err.println("[phase1-offscreen] no agent host; pass -Dudea.agent.port=<port>")
            backend.close()
            return
        }
        println(
            "[phase1-offscreen] listening on http://127.0.0.1:${agentHost.port} with " +
                "${tools.tools.size} tools, rendering ${RENDER_WIDTH}x$RENDER_HEIGHT offscreen",
        )
        digest.publish()
        shutdown
            .onClose("frame-loop") { loop.stop() }
            .onClose("agent-host") { agentHost.stop() }
            // Last, and on a thread of its own. `close` runs on the simulation thread, which in
            // Offscreen **is** the GL thread: `Lwjgl3Backend.close` submits the pipeline dispose
            // to that thread and then waits for the loop to finish, so calling it from inside a
            // frame is a self-join. Handing it to a daemon thread lets the frame return, drain
            // the submitted task and exit - which is the whole point of a clean close.
            .onClose("render-loop") { offThread("phase1-offscreen-exit") { backend.close() } }
        Runtime.getRuntime().addShutdownHook(Thread { shutdown.shutdown("jvm shutdown hook") })
        // The GL thread becomes the simulation thread from here. See the class KDoc.
        backend.drive(loop::pump)
        backend.awaitExit()
        backend.close()
    }

    /** Runs [body] on a short-lived daemon thread. See the `render-loop` close step. */
    private fun offThread(name: String, body: () -> Unit) {
        val thread = Thread({ runCatching(body) }, name)
        thread.isDaemon = true
        thread.start()
    }

    /** World units kept visible on the shorter axis. Small, so a 2-unit box is clearly visible. */
    private const val WORLD_WIDTH: Float = 32f
    private const val WORLD_HEIGHT: Float = 18f

    private const val WINDOW_WIDTH: Int = 640
    private const val WINDOW_HEIGHT: Int = 360

    /**
     * The framebuffer every capture is read from.
     *
     * Deliberately smaller than the window: the two sizes being different is what proves a
     * capture is a property of `WindowConfig` and not of the window, and it is the case that
     * caught `DebugOverlayRenderSystem` projecting through `Gdx.graphics` instead of the target.
     */
    private const val RENDER_WIDTH: Int = 320
    private const val RENDER_HEIGHT: Int = 180
}

/** The digest's window onto the real loop, so `/state.paused` is the loop's own answer. */
private class OffscreenLoopView(private val host: GameHost) : LoopStatus {
    override val paused: Boolean get() = host.loop.paused
    override val timeScale: Float get() = host.loop.timeScale
    override val fps: Float get() = 0f
}
