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
import dev.wildware.udea.agent.host.render.OffscreenRenderControl
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
import dev.wildware.udea.core.identity.NetId
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
 * The render thread runs [AgentGameLoop.pump] on its own, via [KoolBackend.drive] — the same
 * wiring `Phase1OffscreenDemo` uses for a live host — and this thread only submits a command and
 * polls for it, exactly as an HTTP handler does. Pumping from the test thread directly would be a
 * different, easier arrangement than the one that ships, and it is precisely the arrangement
 * this class had before issue #211's Kool port dropped the `drive` wiring that used to come from
 * `Lwjgl3Application`'s own render callback for free: nothing then ever advanced a Kool frame,
 * and every capture timed out waiting for one.
 *
 * ## Why seven claims share one `@Test` method
 *
 * Kool allows exactly one `KoolContext` per JVM for the life of the JVM (`KoolThread`'s KDoc),
 * and `forkEvery = 1` gives this class one JVM, not one per method. Seven methods each starting
 * their own `KoolBackend` raced for that one context; only the first ever won. One context,
 * shared by every claim in sequence below, is what the fork setting actually buys. The claims
 * that touch the camera or spawn entities are ordered so each either resets what it changed or
 * does not depend on what came before it: `set_camera` and `follow_entity` come after every claim
 * that assumes an untouched camera, and `follow_entity`'s own claim resets the camera to the
 * origin first rather than trusting wherever `set_camera`'s claim left it.
 */
class OffscreenRenderToolsTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `the render toolset against a real driver, end to end`() {
        GlAvailabilityHere.require()
        withHost { fixture ->
            fixture.ok("time.pause")

            // 1. The bytes filed under an artifact id are the bytes the renderer produced, and
            // they are a PNG. The path through the store is where a capture could plausibly be
            // truncated or re-encoded, and an agent fetching GET /artifact gets exactly this file.
            run {
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

            // 2. toggle_debug_draw changes the picture, not just the answer. A tool that returned
            // debugDraw:true while every subsequent capture looked identical would be reporting a
            // field rather than a behaviour. Ends with debug draw off again, restoring the state
            // the next claim assumes.
            run {
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

            // 3. An id nothing resolves to is refused by name rather than accepted and forgotten.
            // Through the real adapter and the real rig: RenderToolset -> OffscreenRenderControl
            // -> PresentationControl -> CameraRig -> NetIdIndex, and back with a reason.
            run {
                val refusal = fixture.call("render.follow_entity", "netId" to "$UNALLOCATED_NET_ID")
                assertTrue(refusal is AgentResult.Failed, "an unallocated id was accepted: $refusal")
                assertEquals("no_such_entity", refusal.error.kind.id)
            }

            // 4. A region capture crops the framebuffer, and an impossible region is refused by
            // name. Neither depends on what is drawn, so it runs before anything moves the camera.
            run {
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

            // 5. set_camera moves the view, which is only observable in a capture.
            run {
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

            // 6. follow_entity against the real stack: accepted for an entity it can track, and
            // the camera demonstrably moves because of it. The camera is put back at the origin
            // first, deliberately, rather than trusting wherever claim 5 left it - the whole point
            // of the check PresentationControl.follow makes is that APPLIED is a promise about the
            // *next frames*, so the assertion has to be about frames, and it needs a known start.
            run {
                fixture.ok("render.set_camera", "x" to "0", "y" to "0", "zoom" to "1")
                val spawned = fixture.ok(
                    "world.spawn_blueprint",
                    "blueprint" to "box", "x" to "12", "y" to "0",
                )
                val netId = requireNotNull(Regex(""""id":(-?\d+)""").find(spawned)).groupValues[1]
                fixture.ok("time.step", "ticks" to "1")

                val before = fixture.capture()
                assertContains(fixture.ok("render.follow_entity", "netId" to netId), """"following":""")
                repeat(FOLLOW_FRAMES) { fixture.ok("render.toggle_debug_draw", "enabled" to "false") }
                val after = fixture.capture()

                assertFalse(
                    before.bytes.contentEquals(after.bytes),
                    "render.follow_entity answered ok and the camera never moved, which is " +
                        "exactly the silent success this surface is not allowed to have",
                )
            }

            // 7. The Phase 1 demo, as an assertion: screenshot, move, screenshot, diff, rewind,
            // screenshot. The two diffs are the whole claim - self-contained, since it spawns its
            // own entity and only ever compares captures of it against each other. `set_camera`
            // first, and deliberately: claim 6 left the camera *following*, and follow easing
            // runs on real elapsed time (`KoolBackend.drive` now pumps with the frame's real
            // delta, not zero, since the fix that let a capture settle on a later real frame
            // rather than the one that queued it - see `AgentContext.answerWhenReady`). A camera
            // still easing toward claim 6's target would drift a pixel or two between this
            // claim's own "before" and "rewound" captures for a reason that has nothing to do
            // with the rewind, and did exactly that the first time this ran merged.
            // `set_camera` also stops following (`CameraOutcome`'s own contract), which is what
            // actually pins it.
            run {
                fixture.ok("render.set_camera", "x" to "0", "y" to "0", "zoom" to "1")
                val spawn = fixture.ok("world.spawn_blueprint", "blueprint" to "box", "x" to "0", "y" to "0")
                val netId = Regex(""""id":(-?\d+)""").find(spawn)?.groupValues?.get(1)
                assertNotNull(netId, "spawn_blueprint reported no NetId: $spawn")
                // Enough ticks for the snapshot ring to hold history: time.rewind restores from a
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
    }

    // --- fixture -----------------------------------------------------------------------------

    private fun withHost(block: (Fixture) -> Unit) {
        val module = DemoBodyModule()
        val definition = UdeaGameDef(
            registry = CoreUdeaRegistry,
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
            poses = Interpolator(SimClock(), SimulatedPoseOnly),
            frameTime = registry.frameTime,
        )
        registry.register(RenderPhase.PreRender, { camera })
        registry.register(RenderPhase.World, { r -> BodyQuadRenderSystem(r, camera) })
        registry.register(RenderPhase.Debug, { r -> DebugGridRenderSystem(r, camera, debugDraw) })

        val backend = KoolBackend.start(
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
            // The free-running driver: exactly what `Phase1OffscreenDemo` wires for a live host.
            // Without it nothing ever calls `loop.pump`, and a queued capture waits for a frame
            // that never comes - `FrameCaptureSlot` reports "the render loop has stopped
            // drawing", which is a true description of a loop that never started. This was the
            // one thing missing here: production drives the same way and was never affected.
            backend.drive(loop::pump)
            block(Fixture(bridge, artifacts))
        } finally {
            backend.close()
        }
    }

    /**
     * One command at a time, submitted from this thread and polled for completion, exactly as
     * an HTTP handler over a driven host does — the render thread runs [AgentGameLoop.pump] on
     * its own via [KoolBackend.drive], installed in [withHost].
     */
    private class Fixture(
        private val bridge: AgentBridge,
        private val artifacts: AgentArtifacts,
    ) {

        fun call(name: String, vararg args: Pair<String, String>): AgentResult {
            val accepted = bridge.submit(AgentCommand(name, args.toMap())) as? AgentSubmission.Accepted
                ?: error("the bridge refused $name")
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(COMMAND_TIMEOUT_SECONDS)
            while (bridge.completedCommandId() < accepted.commandId && System.nanoTime() < deadline) {
                Thread.onSpinWait()
            }
            return bridge.commandResults().lastOrNull { it.id == accepted.commandId }?.result
                ?: error("$name did not complete within ${COMMAND_TIMEOUT_SECONDS}s")
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
            const val COMMAND_TIMEOUT_SECONDS = 20L
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

        /**
         * Frames driven between the two captures of the follow claim.
         *
         * Enough for a 0.1s half-life to close most of twelve world units, and driven with a
         * harmless tool call because every command this fixture sends pumps exactly one frame.
         */
        const val FOLLOW_FRAMES = 6

        /**
         * A packed id inside the engine's range that nothing has allocated.
         *
         * Index 900 with generation 3: `NetId.ofRaw` accepts it - so it reaches the camera rather
         * than being turned back as a bad argument - and `NetIdIndex` resolves it to nothing.
         */
        val UNALLOCATED_NET_ID: Int = NetId.of(index = 900, generation = 3).raw

        val PNG_SIGNATURE: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }
}
