package dev.wildware.udea.agent.host.gl

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentSubmission
import dev.wildware.udea.agent.dispatch.AgentRuntime
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.agent.host.AgentArtifacts
import dev.wildware.udea.agent.host.AgentGameLoop
import dev.wildware.udea.agent.host.AgentHostTools
import dev.wildware.udea.agent.host.ArtifactId
import dev.wildware.udea.agent.host.ArtifactToolset
import dev.wildware.udea.agent.host.RenderToolset
import dev.wildware.udea.agent.host.demo.BodyCensus
import dev.wildware.udea.agent.host.demo.BodyPlacement
import dev.wildware.udea.agent.host.demo.BodyQuadRenderSystem
import dev.wildware.udea.agent.host.demo.BoxBlueprint
import dev.wildware.udea.agent.host.demo.DebugGridRenderSystem
import dev.wildware.udea.agent.host.demo.DemoBodyModule
import dev.wildware.udea.agent.host.demo.OffscreenRenderControl
import dev.wildware.udea.agent.host.demo.SimulatedPoseOnly
import dev.wildware.udea.agent.host.demo.demoBodyAccess
import dev.wildware.udea.agent.host.demo.demoRegistry
import dev.wildware.udea.agent.query.AgentComponentIndex
import dev.wildware.udea.agent.state.DigestSources
import dev.wildware.udea.agent.state.LoopStatus
import dev.wildware.udea.agent.state.StateDigest
import dev.wildware.udea.agent.tools.BlueprintCatalog
import dev.wildware.udea.agent.tools.EngineToolModules
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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The render toolset against a real driver: the whole stack, in one JVM, with pixels at the end.
 *
 * ## What is under test that nothing else covers
 *
 * `RenderToolsetTest` checks what the toolset decides; `GlCaptureTest` checks what the pixel path
 * produces. Neither covers the join: `PresentationControl` -> `OffscreenRenderControl` ->
 * `RenderToolset` -> `AgentContext.answerLater` -> `AgentArtifacts`, driven by an `AgentGameLoop`
 * on the thread that also renders. That join is where the ordering can be wrong in a way no unit
 * test sees, and it is exactly where it *was* wrong: with the barrier drained in
 * `AgentRuntime.afterFrame`, a paused host queued the capture after the frame it needed and every
 * screenshot failed with `capture_failed`.
 *
 * The demo's game is reused deliberately — `Phase1OffscreenDemo` boots the same objects for a
 * human to drive over HTTP, so a green run here means that demo works, and the transcript and the
 * test cannot drift apart.
 *
 * Everything runs on the render thread via [Lwjgl3Backend.onRenderThread]: on an `Offscreen` host
 * that thread is the simulation thread, so pumping the loop from the test thread would be a
 * different, easier arrangement than the one that ships.
 */
class OffscreenRenderToolsTest {

    @TempDir
    lateinit var temp: Path

    /**
     * The Phase 1 demo, as an assertion: screenshot, move, screenshot, diff, rewind, screenshot.
     *
     * The two diffs are the whole claim. A *before* and an *after* must differ in the way the
     * world differs, and the frame drawn after a rewind must match the one drawn before the write
     * — byte for byte, because the loop is paused and the scene is a function of the simulated
     * state alone (see `GlCaptureDeterminismTest` for why that is the honest form of the
     * determinism claim).
     */
    @Test
    fun `screenshot rewind screenshot shows the world going back`() {
        GlAvailabilityHere.require()
        withHost { fixture ->
            fixture.ok("time.pause")
            val spawn = fixture.ok("world.spawn_blueprint", "blueprint" to "box", "x" to "0", "y" to "0")
            val netId = Regex(""""id":(-?\d+)""").find(spawn)?.groupValues?.get(1)
            assertNotNull(netId, "spawn_blueprint reported no NetId: $spawn")
            // Enough ticks for the snapshot ring to hold history: `time.rewind` restores from a
            // recorded tick, and a world three ticks old has nothing to go back to.
            fixture.ok("time.step", "ticks" to "30")

            val before = fixture.capture()

            fixture.ok(
                "world.set_component_field",
                "id" to netId,
                "component" to "PhysicsBody",
                "field" to "x",
                "value" to "9.0",
            )
            fixture.ok("time.step", "ticks" to "1")
            val moved = fixture.capture()

            val movedDiff = fixture.ok("render.compare_artifacts", "a" to before.id, "b" to moved.id)
            assertContains(movedDiff, """"identical":false""")
            assertFalse(
                before.bytes.contentEquals(moved.bytes),
                "the box moved nine world units and the capture did not change",
            )

            fixture.ok("time.rewind", "ticks" to "2")
            val rewound = fixture.capture()

            val rewoundDiff = fixture.ok("render.compare_artifacts", "a" to before.id, "b" to rewound.id)
            assertContains(
                rewoundDiff,
                """"identical":true""",
                message = "the frame after a rewind differs from the frame before the write it undid",
            )
            assertContentEquals(before.bytes, rewound.bytes)
        }
    }

    /**
     * The bytes filed under an artifact id are the bytes the renderer produced, and they are a PNG.
     *
     * The path through the store is where a capture could plausibly be truncated or re-encoded,
     * and an agent fetching `GET /artifact` gets exactly this file.
     */
    @Test
    fun `a capture is filed as a PNG whose size matches the framebuffer`() {
        GlAvailabilityHere.require()
        withHost { fixture ->
            fixture.ok("time.pause")

            val shot = fixture.capture()

            assertContains(shot.json, """"w":$RENDER_WIDTH""")
            assertContains(shot.json, """"h":$RENDER_HEIGHT""")
            assertTrue(shot.bytes.size > PNG_SIGNATURE.size)
            assertContentEquals(PNG_SIGNATURE, shot.bytes.copyOf(PNG_SIGNATURE.size))
            val decoded = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(shot.bytes))
            assertNotNull(decoded, "the filed artifact is not a decodable image")
            assertEquals(RENDER_WIDTH, decoded.width)
            assertEquals(RENDER_HEIGHT, decoded.height)
        }
    }

    /**
     * `toggle_debug_draw` changes the picture, not just the answer.
     *
     * A tool that returned `{"debugDraw":true}` while every subsequent capture looked identical
     * would be reporting a field rather than a behaviour, and an agent that turned the overlay on
     * to look at collision shapes would see none and conclude the game had none.
     */
    @Test
    fun `toggling debug draw changes what a capture contains`() {
        GlAvailabilityHere.require()
        withHost { fixture ->
            fixture.ok("time.pause")
            val off = fixture.capture()

            assertContains(fixture.ok("render.toggle_debug_draw"), """"debugDraw":true""")
            val on = fixture.capture()

            assertFalse(
                off.bytes.contentEquals(on.bytes),
                "the debug grid was switched on and the frame did not change",
            )

            assertContains(fixture.ok("render.toggle_debug_draw"), """"debugDraw":false""")
            assertContentEquals(
                off.bytes,
                fixture.capture().bytes,
                "switching the overlay back off did not restore the frame",
            )
        }
    }

    /** `set_camera` moves the view, which is only observable in a capture. */
    @Test
    fun `set_camera changes the framing of the next capture`() {
        GlAvailabilityHere.require()
        withHost { fixture ->
            fixture.ok("time.pause")
            fixture.ok("world.spawn_blueprint", "blueprint" to "box", "x" to "0", "y" to "0")
            fixture.ok("time.step", "ticks" to "1")
            val centred = fixture.capture()

            fixture.ok("render.set_camera", "x" to "12", "y" to "0", "zoom" to "1")
            val panned = fixture.capture()

            assertFalse(
                centred.bytes.contentEquals(panned.bytes),
                "the camera was moved twelve world units and the frame did not change",
            )
        }
    }

    /** A region capture crops the framebuffer, and an impossible region is refused by name. */
    @Test
    fun `screenshot_region crops, and an out-of-bounds region is refused`() {
        GlAvailabilityHere.require()
        withHost { fixture ->
            fixture.ok("time.pause")

            val json = fixture.ok(
                "render.screenshot_region",
                "x" to "0", "y" to "0", "w" to "16", "h" to "8",
            )
            val id = requireNotNull(Regex(""""artifactId":"([^"]+)"""").find(json)).groupValues[1]
            val decoded = javax.imageio.ImageIO.read(
                java.io.ByteArrayInputStream(fixture.artifactBytes(id)),
            )
            assertEquals(16, decoded.width)
            assertEquals(8, decoded.height)

            val refusal = fixture.call(
                "render.screenshot_region",
                "x" to "0", "y" to "0", "w" to "9000", "h" to "9000",
            )
            assertTrue(refusal is AgentResult.Failed)
            assertEquals("bad_argument", refusal.error.kind.id)
            assertContains(refusal.error.message, "(0, 0, $RENDER_WIDTH, $RENDER_HEIGHT)")
        }
    }

    // --- fixture -----------------------------------------------------------------------------

    private fun withHost(block: (Fixture) -> Unit) {
        val module = DemoBodyModule()
        val definition = UdeaGameDef(
            modules = listOf(module),
            timeTravel = snapshotTimeTravel(demoRegistry()),
        )
        val netIds = definition.core.netIds
        val spawner = BlueprintSpawner(definition.core.barrier, netIds, BodyPlacement)
        module.spawner = spawner

        val debugDraw = DebugDraw(enabled = false)
        val registry = RenderRegistry()
        val camera = CameraRig(
            netIds = netIds,
            interpolator = Interpolator(SimClock(), SimulatedPoseOnly),
            frameTime = registry.frameTime,
        )
        registry.register(RenderPhase.PreRender, { camera })
        registry.register(RenderPhase.World, { r -> BodyQuadRenderSystem(r, camera) })
        registry.register(RenderPhase.Debug, { r -> DebugGridRenderSystem(r, camera, debugDraw) })

        val backend = Lwjgl3Backend.start(
            RenderMode.Offscreen,
            WindowConfig(
                title = "udea-agent-host-gl-test",
                windowWidth = 160,
                windowHeight = 120,
                renderWidth = RENDER_WIDTH,
                renderHeight = RENDER_HEIGHT,
            ),
            registry,
        )
        try {
            val host = GameHost(RenderMode.Offscreen, definition, backend)
            val pipeline = checkNotNull(backend.pipeline)
            val control = OffscreenRenderControl(PresentationControl(pipeline, camera, debugDraw))
            val bridge = AgentBridge()
            val artifacts = AgentArtifacts(temp)
            val digest = StateDigest(bridge, DigestSources(entities = BodyCensus(host.world), loop = Loop(host)))
            val tools = EngineToolModules
                .wireAll(
                    ToolIndex.builder(),
                    WorldToolset(
                        world = host.world,
                        components = AgentComponentIndex(listOf(demoBodyAccess())),
                        netIds = netIds,
                        bridge = bridge,
                        clock = host.ctx.clock,
                        catalog = BlueprintCatalog.of(listOf(BoxBlueprint)),
                        spawner = spawner,
                    ),
                    TimeToolset(host.time, host.ctx.clock, bridge),
                )
                .module(AgentHostTools)
                .toolset(RenderToolset(RenderMode.Offscreen, control, artifacts))
                .toolset(ArtifactToolset(artifacts))
                .build()
            val loop = AgentGameLoop(
                host,
                AgentRuntime(bridge, tools, host.world, host.ctx, digest),
            )
            block(Fixture(backend, bridge, loop, artifacts))
        } finally {
            backend.close()
        }
    }

    /** One command at a time, pumped on the render thread, exactly as a live host runs. */
    private class Fixture(
        private val backend: Lwjgl3Backend,
        private val bridge: AgentBridge,
        private val loop: AgentGameLoop,
        private val artifacts: AgentArtifacts,
    ) {

        fun call(name: String, vararg args: Pair<String, String>): AgentResult {
            val accepted = bridge.submit(AgentCommand(name, args.toMap())) as? AgentSubmission.Accepted
                ?: error("the bridge refused $name")
            repeat(MAX_PUMPS) {
                // On the render thread, which is where a driven host pumps from. Anything that
                // only works when the pump and the render are on different threads is not a
                // property this engine has.
                backend.onRenderThread { loop.pump(0f) }
                if (bridge.completedCommandId() >= accepted.commandId) {
                    return bridge.commandResults().last { it.id == accepted.commandId }.result
                }
            }
            error("$name did not complete within $MAX_PUMPS iterations")
        }

        fun ok(name: String, vararg args: Pair<String, String>): String {
            val result = call(name, *args)
            assertTrue(result is AgentResult.Ok, "$name failed: $result")
            return result.json
        }

        fun artifactBytes(id: String): ByteArray {
            val artifact = artifacts.get(requireNotNull(ArtifactId.parse(id)))
            return java.nio.file.Files.readAllBytes(requireNotNull(artifact).path)
        }

        fun capture(): Shot {
            val json = ok("render.screenshot")
            val id = requireNotNull(Regex(""""artifactId":"([^"]+)"""").find(json)).groupValues[1]
            return Shot(id, json, artifactBytes(id))
        }

        companion object {
            const val MAX_PUMPS = 8
        }
    }

    private class Shot(val id: String, val json: String, val bytes: ByteArray)

    private class Loop(private val host: GameHost) : LoopStatus {
        override val paused: Boolean get() = host.loop.paused
        override val timeScale: Float get() = host.loop.timeScale
        override val fps: Float get() = 0f
    }

    private companion object {
        const val RENDER_WIDTH = 96
        const val RENDER_HEIGHT = 64

        val PNG_SIGNATURE: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }
}
