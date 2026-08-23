package dev.wildware.udea.agent.host.gl

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.BufferUtils
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentSubmission
import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.agent.dispatch.AgentRuntime
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.agent.host.AgentArtifacts
import dev.wildware.udea.agent.host.AgentGameLoop
import dev.wildware.udea.agent.host.AgentHostTools
import dev.wildware.udea.agent.host.ArtifactId
import dev.wildware.udea.agent.host.ArtifactToolset
import dev.wildware.udea.agent.host.RenderToolset
import dev.wildware.udea.agent.host.demo.OffscreenRenderControl
import dev.wildware.udea.agent.host.overlay.AgentOverlayView
import dev.wildware.udea.agent.host.overlay.OverlayVerbosity
import dev.wildware.udea.agent.state.DigestSources
import dev.wildware.udea.agent.state.StateDigest
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.ScreenTarget
import dev.wildware.udea.render.backend.Lwjgl3Backend
import dev.wildware.udea.render.backend.WindowConfig
import dev.wildware.udea.render.control.PresentationControl
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Spec 3.7's guarantee, owned end to end by one test (issue #162).
 *
 * ## What "enumerates capture routes from the declared tools" means here, and why
 *
 * The routes are not written down. They are **derived from the render toolset's published
 * declarations**: every tool in [AgentHostTools] that declares an `afterTick` argument is a tool
 * that reads pixels back - that argument exists for one reason, to pin a capture to a simulation
 * tick, and nothing that does not capture has any use for one. Each route found is then driven
 * through the real [ToolIndex], the real [AgentRuntime] dispatch path, the real [RenderToolset]
 * and the real artifact store.
 *
 * A hand-written list of two tool names would pass for ever after somebody added a third route,
 * and the failure mode of that is not a red test: it is every visual diff an agent does through
 * the new route silently carrying the agent's own narration. So [captureRoutes] fails loudly if
 * it finds none, and [argumentsFor] fails loudly on a required argument it does not recognise
 * rather than skipping the route.
 *
 * ## Both halves, because the first alone is vacuous
 *
 * 1. **Every capture is byte-identical with the overlay on and off.** This is the property that
 *    matters, and on its own it is worthless: it passes just as happily when the overlay never
 *    drew, was registered into a phase the pipeline skips, or quietly disabled itself.
 * 2. **The on-screen framebuffer differs.** That is what says the overlay *did* draw, so the
 *    matching captures mean it drew somewhere a capture cannot see rather than not at all.
 *
 * `GlOverlayIsolationTest` in `udea-render` asserts this shape for a synthetic overlay and a
 * direct `FrameCaptureSlot`. This one asserts it for the **real** [AgentOverlayView] reached
 * through the **real** tool surface, which is the combination that would be wrong in production.
 *
 * ## Windowed, deliberately
 *
 * The overlay exists only in [RenderMode.Windowed] - spec 3.7's other rule - so running this in
 * `Offscreen` would be asserting the isolation of something that had already switched itself off.
 */
class OverlayCaptureIsolationTest {

    @TempDir
    lateinit var artifactRoot: Path

    @Test
    fun `every declared capture route is identical with the overlay on and off, and the window is not`() {
        GlAvailabilityHere.require()

        val routes = captureRoutes()
        assertTrue(
            routes.isNotEmpty(),
            "no capture route was found among ${AgentHostTools.moduleName}'s declared tools " +
                "(${AgentHostTools.tools.joinToString { it.name }}). The routes are derived from " +
                "the declarations so that a new one is covered automatically; finding none means " +
                "the derivation has stopped matching and spec 3.7's guarantee is being asserted " +
                "about nothing.",
        )

        val without = runOnce(overlay = false, routes = routes)
        val with = runOnce(overlay = true, routes = routes)

        // 1. The overlay reached the window. Without this the rest is a statement about a no-op.
        assertEquals(
            SCENE,
            without.windowSample,
            "with no overlay the window should show the blitted scene where the panel goes, " +
                "was ${hex(without.windowSample)}",
        )
        assertNotEquals(
            without.windowSample,
            with.windowSample,
            "the window is identical with the agent overlay on and off, so the overlay drew " +
                "nothing and the byte-equality below proves nothing",
        )

        // 2. ...and none of it reached any declared capture route.
        assertEquals(routes.map { it.name }, without.captures.keys.toList())
        for (route in routes) {
            val quiet = without.captures.getValue(route.name)
            val narrated = with.captures.getValue(route.name)
            assertTrue(quiet.isNotEmpty(), "${route.name} produced no bytes at all")
            assertContentEquals(
                quiet,
                narrated,
                "${route.name} returned different bytes with the agent overlay switched on. An " +
                    "agent doing capture / act / capture through this route would read its own " +
                    "narration as a change in the game.",
            )
        }
    }

    /** The capture routes, derived from what the render toolset publishes. */
    private fun captureRoutes(): List<Route> = AgentHostTools.tools
        .filter { tool -> tool.args.any { it.name == AFTER_TICK } }
        .map { tool -> Route(tool.name, argumentsFor(tool)) }

    /**
     * Arguments that make [tool] capture something, derived from its own declaration.
     *
     * Errors on an unrecognised required argument rather than omitting it: a route quietly
     * skipped is a route not covered, which is the thing this test exists to make impossible.
     */
    private fun argumentsFor(tool: AgentToolDef<*>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (arg in tool.args) {
            if (!arg.required) continue
            out[arg.name] = when (arg.name) {
                "x", "y" -> "0"
                "w" -> REGION_WIDTH.toString()
                "h" -> REGION_HEIGHT.toString()
                else -> error(
                    "${tool.name} declares a required argument '${arg.name}' this test does not " +
                        "know how to fill, so the route would be skipped silently. Teach it here.",
                )
            }
        }
        return out
    }

    // --- one boot --------------------------------------------------------------------------

    private class Route(val name: String, val args: Map<String, String>)

    private class Run(val captures: Map<String, ByteArray>, val windowSample: Int)

    private fun runOnce(overlay: Boolean, routes: List<Route>): Run {
        val bridge = AgentBridge()
        val sessions = AgentSessions()
        // Real overlay content, so "the captures matched" is not two blank panels agreeing.
        bridge.narration.say(CAPTION, ttlSeconds = 300f, sessions.intern("isolation-test"))

        val registry = RenderRegistry()
        registry.register(RenderPhase.World, { resources -> SceneSystem(resources) })
        val view = AgentOverlayView(
            bridge = bridge,
            sessions = sessions,
            mode = RenderMode.Windowed,
            initialVerbosity = OverlayVerbosity.VERBOSE,
        )
        if (overlay) {
            registry.overlay({ resources -> AgentOverlaySystem(resources, view) })
        }
        // Registered in both runs, so the window is sampled at the same point in the frame.
        val probe = WindowProbe()
        registry.overlay({ probe })

        val backend = Lwjgl3Backend.start(
            RenderMode.Windowed,
            WindowConfig(
                title = "udea-agent-overlay-isolation",
                windowWidth = WINDOW_WIDTH,
                windowHeight = WINDOW_HEIGHT,
                renderWidth = RENDER_WIDTH,
                renderHeight = RENDER_HEIGHT,
            ),
            registry,
        )
        try {
            val host = GameHost(RenderMode.Windowed, UdeaGameDef(modules = emptyList()), backend)
            val pipeline = checkNotNull(backend.pipeline) { "the backend built no pipeline" }

            val artifacts = AgentArtifacts(artifactRoot.resolve(if (overlay) "with" else "without"))
            // The *real* adapter, the one `Phase1OffscreenDemo` wires. A route proven against a
            // control invented by this test would prove nothing about the route an agent drives.
            val control = OffscreenRenderControl(PresentationControl(pipeline))
            val tools = ToolIndex.builder()
                .module(AgentHostTools)
                .toolset(RenderToolset(RenderMode.Windowed, control, artifacts))
                .toolset(ArtifactToolset(artifacts))
                .build()
            val digest = StateDigest(bridge, DigestSources())
            val loop = AgentGameLoop(host, AgentRuntime(bridge, tools, host.world, host.ctx, digest))
            // `drive(frame)` and not `drive(host)`: an agent host has to bracket every frame with
            // `AgentRuntime.beforeFrame`/`afterFrame`, which is what `AgentGameLoop.pump` is, and
            // driving with `host::frame` would leave every submitted command in the queue for ever.
            backend.drive { dtSeconds -> loop.pump(dtSeconds) }

            // Several frames of overlay before anything is captured, so a capture that *did* read
            // the window would certainly contain overlay pixels.
            awaitFrames(probe, probe.frames.get() + SETTLE_FRAMES)

            val captured = LinkedHashMap<String, ByteArray>()
            for (route in routes) {
                captured[route.name] = bytesOf(route, bridge, artifacts)
            }

            awaitFrames(probe, probe.frames.get() + 2)
            return Run(captured, probe.sample.get())
        } finally {
            backend.close()
        }
    }

    /**
     * Drives one route through the real dispatch path and reads the filed PNG back.
     *
     * Submitted from this thread and confirmed against `completedCommandId`, exactly as an HTTP
     * caller does: the render thread is running the loop, so waiting on the answer here is the
     * real arrangement and not a test-shaped one.
     */
    private fun bytesOf(route: Route, bridge: AgentBridge, artifacts: AgentArtifacts): ByteArray {
        val accepted = bridge.submit(AgentCommand(route.name, route.args)) as? AgentSubmission.Accepted
            ?: error("the bridge refused ${route.name}")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COMMAND_TIMEOUT_SECONDS)
        while (bridge.completedCommandId() < accepted.commandId && System.nanoTime() < deadline) {
            Thread.onSpinWait()
        }
        val settled = bridge.commandResults().lastOrNull { it.id == accepted.commandId }?.result
            ?: error("${route.name} did not complete within ${COMMAND_TIMEOUT_SECONDS}s")
        val ok = settled as? AgentResult.Ok
            ?: error("${route.name} answered $settled; the capture route is not live here")
        val id = ARTIFACT_ID.find(ok.json)?.groupValues?.get(1)
            ?: error("${route.name} returned no artifactId: ${ok.json}")
        val parsed = ArtifactId.parse(id) ?: error("${route.name} returned an unparseable id: $id")
        val artifact = artifacts.get(parsed) ?: error("${route.name} filed $id and it is not there")
        return Files.readAllBytes(artifact.path)
    }

    private fun awaitFrames(probe: WindowProbe, target: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (probe.frames.get() < target && System.nanoTime() < deadline) Thread.onSpinWait()
        assertTrue(probe.frames.get() >= target, "the render thread stopped drawing")
    }

    private fun hex(rgb: Int): String = "#%06X".format(rgb)

    /** Fills the capturable target, so the two captures agree on real content and not on black. */
    private class SceneSystem(private val resources: RenderResources) : RenderSystem {

        private val projection = Matrix4()
        private val pixel = TextureRegion(resources.own(whitePixel()))

        override fun render(target: OffscreenTarget, alpha: Float) {
            projection.setToOrtho2D(0f, 0f, target.width.toFloat(), target.height.toFloat())
            val batch = resources.batch
            batch.projectionMatrix = projection
            batch.color = Color.BLUE
            batch.begin()
            batch.draw(pixel, 0f, 0f, target.width.toFloat(), target.height.toFloat())
            batch.end()
            batch.color = Color.WHITE
        }
    }

    /**
     * Reads one pixel of whatever is bound once the overlays have run - the window.
     *
     * Registered last, so it runs after the agent overlay. `glReadPixels` reads the *bound*
     * framebuffer, and by this point the offscreen one has been unbound and the frame blitted, so
     * this is the human's picture and not the agent's.
     */
    private class WindowProbe : OverlaySystem {

        val frames = AtomicInteger()
        val sample = AtomicInteger()

        private val buffer = BufferUtils.newByteBuffer(4)

        override fun render(target: ScreenTarget, dtSeconds: Float) {
            buffer.clear()
            Gdx.gl.glReadPixels(
                PROBE_INSET,
                target.height - PROBE_INSET,
                1,
                1,
                GL20.GL_RGBA,
                GL20.GL_UNSIGNED_BYTE,
                buffer,
            )
            val r = buffer.get(0).toInt() and 0xFF
            val g = buffer.get(1).toInt() and 0xFF
            val b = buffer.get(2).toInt() and 0xFF
            sample.set((r shl 16) or (g shl 8) or b)
            frames.incrementAndGet()
        }
    }

    private companion object {

        /**
         * The window, and the framebuffer, deliberately the **same size**.
         *
         * Not cosmetic, and it was found by mutation. A capture that leaked would leak by reading
         * the window instead of the offscreen target - which is exactly what happens if the drain
         * moves past the overlay pass in `RenderPipeline`. With a framebuffer smaller than the
         * window, that read takes the window's bottom-left `RENDER_WIDTH x RENDER_HEIGHT` corner,
         * the panel sits in the *top* left, and the leak produces byte-identical captures: the
         * test goes green on a pipeline that is corrupting every agent screenshot.
         *
         * Same size, 1:1 blit, no letterbox: the captured rectangle covers the whole window, so
         * anything the overlay draws anywhere is inside it.
         */
        const val WINDOW_WIDTH = 320
        const val WINDOW_HEIGHT = 320

        /** The framebuffer every capture reads. See [WINDOW_WIDTH]. */
        const val RENDER_WIDTH = WINDOW_WIDTH
        const val RENDER_HEIGHT = WINDOW_HEIGHT

        /**
         * The region handed to whichever route declares one: the whole framebuffer.
         *
         * A small rectangle in a corner would be the same trap as a small framebuffer - it would
         * miss the panel, and the region route would then be enumerated, driven, and asserted
         * about nothing.
         */
        const val REGION_WIDTH = RENDER_WIDTH
        const val REGION_HEIGHT = RENDER_HEIGHT

        /**
         * Where the window is sampled, in from the top-left corner.
         *
         * Inside the panel, which is `AgentOverlayView.MARGIN` in and is the only thing the
         * overlay always draws. Sampling the window centre instead would depend on a marker
         * happening to land there, and this fixture has no anchored calls.
         */
        const val PROBE_INSET = 20

        /** What `SceneSystem` fills the capturable target with. */
        const val SCENE = 0x0000FF

        /** The declared argument that marks a tool as a capture route. */
        const val AFTER_TICK = "afterTick"

        const val CAPTION = "verifying the overlay never reaches a capture"

        /** Frames of overlay drawn before any capture is taken. */
        const val SETTLE_FRAMES = 5

        /** How long a route is given to answer, in seconds. The render thread drives the loop. */
        const val COMMAND_TIMEOUT_SECONDS = 20L

        val ARTIFACT_ID = Regex("\"artifactId\"\\s*:\\s*\"([^\"]+)\"")

        fun whitePixel(): Texture = Texture(
            Pixmap(1, 1, Pixmap.Format.RGBA8888).apply {
                setColor(Color.WHITE)
                fill()
            },
        )
    }
}
