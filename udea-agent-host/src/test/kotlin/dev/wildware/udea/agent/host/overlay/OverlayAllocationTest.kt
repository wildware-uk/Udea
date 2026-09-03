package dev.wildware.udea.agent.host.overlay

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentClock
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentToolArg
import dev.wildware.udea.agent.activity.AgentNarration
import dev.wildware.udea.agent.activity.AgentOutcome
import dev.wildware.udea.agent.activity.AgentSessionId
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.agent.activity.AnchorRule
import dev.wildware.udea.agent.host.AllocationProbe
import dev.wildware.udea.core.host.RenderMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The overlay's per-frame allocation budget, measured (issue #160's sixth acceptance criterion).
 *
 * ## Why this file exists
 *
 * Three shipped KDocs already claim the property. [AgentOverlayModel] says a frame on which
 * nothing changed "did no string work at all, which is the property `OverlayAllocationTest`
 * asserts" - naming this file, which did not exist. [AgentOverlayView.drawPanel] says
 * "Allocation-free". [OverlayCanvas] justifies a packed `Int` colour over a colour object on the
 * grounds that an object per draw "would be presentation-thread garbage sixty times a second".
 * Nothing measured any of it.
 *
 * The nearest existing test, `AgentOverlayViewTest`'s "a frame on which nothing changed
 * re-formats nothing", asserts that [AgentOverlayModel.refreshes] does not move. That is a real
 * assertion and it is not this one: a refresh counter says the *rows* were not rebuilt, and says
 * nothing about the marker pass, the panel measure loop, the colour arithmetic or the boxing of a
 * session id. All four of those run on every frame whether the model refreshed or not.
 *
 * ## What "steady state" means here, and the two guards that keep it honest
 *
 * Steady state is: the agent has called some tools, the markers are collected, the panel is
 * formatted, and the frames keep coming with nothing changing. That is the overwhelming majority
 * of frames in a real session - an agent calls a tool a few times a second at most, and the
 * window redraws sixty times a second.
 *
 * "Zero bytes" is trivially achievable by measuring frames that do nothing, so two guards run
 * alongside the measurement and both are assertions rather than comments:
 *
 * - **the measured frames drew.** [CountingCanvas] counts every primitive, and the expected total
 *   is *draws-per-frame times frames-actually-run* - including the probe's warmups, which run the
 *   same block. A frame that quietly stopped drawing a ring is an arithmetic mismatch, not a
 *   suspiciously good number.
 * - **the measured frames were steady.** [AgentMarkers.refreshes] and
 *   [AgentOverlayModel.refreshes] must not move across the measured region. If either did, the
 *   region contained a collect or a re-format and is not the steady state this is about.
 *
 * ## `dtSeconds` is zero in the measured block, deliberately
 *
 * A marker's time to live is [AgentMarkers.DEFAULT_TTL_SECONDS], four seconds. The probe runs its
 * block [WARMUPS] + [ATTEMPTS] times and each block is [FRAMES] frames, so a measurement at a
 * plausible 1/60s would advance more than an hour of overlay wall time, every marker would be
 * long dead, and the thing measured would be an empty marker pass reporting zero for the wrong
 * reason. Passing `0f` avoids that rather than merely detecting it.
 *
 * Nothing about the code path changes: `ages[index] += dtSeconds` and `1f - age / ttlSeconds` are
 * the same instructions whatever the value. The frames on which a marker *has* expired are
 * measured separately, by [a frame after every marker has expired allocates nothing].
 *
 * ## What a green result says, and the measured reason it says no more
 *
 * [AllocationProbe] counts heap bytes, so an allocation C2 proves does not escape its frame is
 * scalar-replaced and invisible to it. The honest scope is therefore *the overlay's frame path
 * allocates nothing the JIT cannot eliminate* - which is the statement that matters
 * operationally, because a scalar-replaced object costs no GC.
 *
 * That boundary is not inferred from the literature; it was measured here, and it is sharper than
 * "sometimes invisible". Replacing [AgentMarkers]'s two reused scratch arrays with a
 * `FloatArray(2)` pair allocated per marker inside `draw`:
 *
 * - run as the only test in the class, it is **seen**: 4800 bytes for one marker and 38400 for
 *   eight, over a hundred frames. Both are exactly 24 bytes per array - a `FloatArray(2)` under
 *   compressed oops - times two arrays, times the marker count, times a hundred.
 * - run with the rest of the class ahead of it in the same JVM, the identical mutation measures
 *   **zero**, because by then the [EntityLocator] and [WorldProjector] call sites are inlined
 *   enough for escape analysis to prove non-escape and scalar-replace both arrays.
 *
 * So sensitivity to a *non-escaping* allocation depends on JIT state and cannot be relied on. An
 * *escaping* one is caught deterministically and in every ordering, which is what the two further
 * mutations recorded in `BRIEF.md` establish. This is the same conclusion `udea-render`'s
 * `RenderAllocationTest` reached by the same method, and it is stated the same way here rather
 * than being quietly narrower.
 *
 * ### The assertion that used to be here, and why it is gone
 *
 * A fourth test compared bytes for one marker against bytes for eight, on the reasoning that a
 * per-marker allocation would show up as a difference. It was removed rather than kept, because
 * it is the order-dependence above wearing a useful-looking hat: it ran late in the class, so it
 * measured zero against zero and **passed under two of the three mutations the assertions below
 * caught**. For an escaping allocation it added nothing - the eight-marker zero below already
 * fails - and for a non-escaping one it reported a false pass. A check that returns the answer
 * you wanted is worse than no check, so it is a paragraph now instead.
 */
class OverlayAllocationTest {

    @Test
    fun `a hundred steady-state frames allocate nothing at all`() {
        if (!AllocationProbe.isSupported) return

        val fixture = Fixture(entityMarkers = 4, pointMarkers = 4)
        val perFrame = fixture.primeAndMeasureDraws()

        // Without this the file could pass against an overlay that draws nothing. Eight markers
        // and a seven-row panel is a busy frame, not an idle one.
        assertEquals(8L, perFrame.markers, "the fixture drew ${perFrame.markers} marker(s)")
        assertTrue(perFrame.total > 8L, "the fixture drew no panel: ${perFrame.total} draw(s)")

        val markerRefreshes = fixture.view.markers.refreshes
        val modelRefreshes = fixture.view.model.refreshes
        val drawsBefore = fixture.canvas.total

        val bytes = AllocationProbe.bytesAllocated(WARMUPS, ATTEMPTS) { fixture.render(FRAMES) }

        val framesRun = AllocationProbe.invocations(WARMUPS, ATTEMPTS) * FRAMES
        assertEquals(
            drawsBefore + framesRun * perFrame.total,
            fixture.canvas.total,
            "the measured region did not draw what a steady frame draws, so the zero below would " +
                "be a measurement of an overlay that had stopped drawing",
        )
        assertEquals(
            markerRefreshes,
            fixture.view.markers.refreshes,
            "the markers were re-collected inside the measurement, so it was not steady state",
        )
        assertEquals(
            modelRefreshes,
            fixture.view.model.refreshes,
            "the panel was re-formatted inside the measurement, so it was not steady state",
        )

        assertEquals(
            0L,
            bytes,
            "$FRAMES steady-state frames allocated $bytes bytes. The overlay's frame path is " +
                "meant to allocate nothing: the rows are pre-formatted strings held in an array, " +
                "the colours are packed ints, the session id is a value class, and the marker " +
                "pass writes into two scratch FloatArrays it owns",
        )
    }

    @Test
    fun `a frame with no markers and no calls allocates nothing`() {
        if (!AllocationProbe.isSupported) return

        // The empty case, and it is a different shape rather than a smaller one: the marker pass
        // walks nothing, and the panel is the idle header instead of a formatted call list. An
        // empty fixture satisfies invariants a populated one does not, so it is measured rather
        // than assumed to be covered by the busy case above.
        val fixture = Fixture(entityMarkers = 0, pointMarkers = 0, caption = null)
        val perFrame = fixture.primeAndMeasureDraws()

        assertEquals(0L, perFrame.markers, "the empty fixture drew a marker")
        assertTrue(perFrame.total > 0L, "the empty fixture drew nothing at all, not even a panel")

        val bytes = AllocationProbe.bytesAllocated(WARMUPS, ATTEMPTS) { fixture.render(FRAMES) }

        assertEquals(0L, bytes, "an idle overlay allocated $bytes bytes over $FRAMES frames")
    }

    @Test
    fun `a frame after every marker has expired allocates nothing`() {
        if (!AllocationProbe.isSupported) return

        // The way back out, and the one state `dtSeconds = 0f` cannot reach: the markers are
        // still collected and still walked, and every one takes the `age >= ttlSeconds` branch
        // out of the loop. A cleanup that allocated - a list of dead slots, a compaction into a
        // fresh array - would show up here and nowhere else.
        val fixture = Fixture(entityMarkers = 4, pointMarkers = 4)
        fixture.render(1)
        fixture.age(AgentMarkers.DEFAULT_TTL_SECONDS * 2f)
        val perFrame = fixture.measureDraws()

        assertEquals(0L, perFrame.markers, "an expired marker was still drawn")
        assertTrue(perFrame.total > 0L, "the panel stopped drawing when the markers expired")

        val markerCount = fixture.view.markers.count
        assertTrue(
            markerCount > 0,
            "the markers were dropped rather than aged out, so the expiry branch this measures " +
                "is not being taken",
        )

        val bytes = AllocationProbe.bytesAllocated(WARMUPS, ATTEMPTS) { fixture.render(FRAMES) }

        assertEquals(
            0L,
            bytes,
            "walking $markerCount expired markers allocated $bytes bytes over $FRAMES frames",
        )
    }

    /**
     * An [AgentOverlayView] in [RenderMode.Windowed] with its markers collected and its panel
     * formatted, over collaborators that allocate nothing of their own.
     *
     * Every collaborator here is a second implementation of one `OverlayFakes.kt` already has,
     * and the duplication is the point rather than an oversight: [RecordingCanvas] allocates a
     * `Draw` per primitive into a growing `ArrayList`, [MapLocator] boxes a `Pair` into a
     * `HashMap` on every lookup, and a measurement taken through either would be measuring the
     * test's own garbage and attributing it to the overlay. `RenderAllocationTest` needed the
     * same second set for the same reason and says so about its `ReusedLayoutFont`.
     */
    private class Fixture(
        entityMarkers: Int,
        pointMarkers: Int,
        caption: String? = "holding mid while the wave pushes",
    ) {

        val canvas = CountingCanvas()

        /**
         * Frozen, so the caption cannot expire part-way through a measurement.
         *
         * `AgentNarration.version` bumps on expiry, which would re-format the panel inside the
         * measured region - caught by the steady-state guard, but as a confusing failure about
         * refresh counts rather than as the wall-clock dependency it actually is.
         */
        private val bridge = AgentBridge(narration = AgentNarration(AgentClock { FROZEN_NANOS }))

        private val sessions = AgentSessions()

        private val locator = ArrayLocator(entityMarkers)

        private val projector = FlatProjector()

        val view: AgentOverlayView = AgentOverlayView(
            bridge,
            sessions,
            RenderMode.Windowed,
            initialVerbosity = OverlayVerbosity.VERBOSE,
        )

        init {
            // Two sessions, so the marker pass exercises more than one palette entry and the
            // panel more than one row colour.
            val a = sessions.intern("claude-a")
            val b = sessions.intern("claude-b")
            if (caption != null) bridge.narration.say(caption, ttlSeconds = CAPTION_TTL, a)

            repeat(entityMarkers) { index ->
                val netId = FIRST_NET_ID + index
                // A write and a read alternate, so both marker styles are on the measured frame.
                val write = index % 2 == 0
                val tool = if (write) "world.set_component_field" else "world.get_component"
                record(tool, ENTITY_RULE, if (write) a else b, "id" to netId.toString())
                locator.put(netId, MARKER_SPREAD * index, MARKER_SPREAD * index)
            }
            repeat(pointMarkers) { index ->
                record(
                    "world.spawn_blueprint",
                    POINT_RULE,
                    b,
                    "x" to (MARKER_SPREAD * index).toString(),
                    "y" to (-MARKER_SPREAD * index).toString(),
                )
            }
        }

        private fun record(
            tool: String,
            rule: AnchorRule,
            session: AgentSessionId,
            vararg args: Pair<String, String>,
        ) {
            val command = AgentCommand(tool, mapOf(*args), session = session)
            val slot = bridge.activity.begin(command, tick = TICK, session = session, anchor = rule)
            bridge.activity.complete(slot, command.id, AgentOutcome.OK, durationNanos = DURATION)
        }

        /** Renders [frames] frames with no elapsed time, which is the measured block. */
        fun render(frames: Int) {
            repeat(frames) { view.render(canvas, dtSeconds = 0f, projector, locator) }
        }

        /** Advances the overlay's wall time by [seconds] over a single frame. */
        fun age(seconds: Float) {
            view.render(canvas, dtSeconds = seconds, projector, locator)
        }

        /** Primes the lazy first-frame work, then reports what one steady frame draws. */
        fun primeAndMeasureDraws(): Draws {
            render(1)
            return measureDraws()
        }

        /** What one further frame draws, from wherever the fixture currently is. */
        fun measureDraws(): Draws {
            val before = canvas.snapshot()
            render(1)
            return canvas.since(before)
        }
    }

    /**
     * An [OverlayCanvas] that counts primitives and allocates nothing.
     *
     * Counters rather than a record of what was drawn: the *decisions* - which shape, which
     * colour, whether anything was drawn at all - are `AgentOverlayViewTest`'s subject and are
     * asserted there over [RecordingCanvas]. What is needed here is only enough to prove the
     * measured frames were doing work, and a `Draw` object per primitive would be exactly the
     * garbage this file exists to detect.
     */
    private class CountingCanvas(
        override val width: Float = 960f,
        override val height: Float = 540f,
        override val lineHeight: Float = 16f,
    ) : OverlayCanvas {

        private var fills = 0L
        private var texts = 0L
        private var markers = 0L

        /** Every primitive drawn since construction. */
        val total: Long get() = fills + texts + markers

        /** The counters as they stand, so a caller can take a difference over a known span. */
        fun snapshot(): Draws = Draws(total = total, markers = markers)

        /** What has been drawn since [before]. */
        fun since(before: Draws): Draws =
            Draws(total = total - before.total, markers = markers - before.markers)

        override fun measure(text: CharSequence): Float = text.length * CHAR_WIDTH

        override fun fill(x: Float, y: Float, w: Float, h: Float, rgba: Int) {
            fills++
        }

        override fun text(x: Float, y: Float, text: CharSequence, rgba: Int) {
            texts++
        }

        override fun ring(cx: Float, cy: Float, radius: Float, thickness: Float, rgba: Int) {
            markers++
        }

        override fun cross(x: Float, y: Float, size: Float, thickness: Float, rgba: Int) {
            markers++
        }
    }

    /** Primitives drawn: everything, and the marker part of it. Absolute, or a difference. */
    private class Draws(val total: Long, val markers: Long)

    /**
     * An [EntityLocator] over parallel arrays.
     *
     * [MapLocator] returns a boxed `Pair` out of a `HashMap` on every lookup, which is one
     * allocation per marker per frame. A measurement through it would be red for the fixture's
     * reasons rather than the overlay's.
     *
     * An id this was never given returns `false`, which is what a stale generation looks like -
     * the same convention [MapLocator] uses.
     */
    private class ArrayLocator(capacity: Int) : EntityLocator {

        private val ids = IntArray(capacity)
        private val xs = FloatArray(capacity)
        private val ys = FloatArray(capacity)
        private var count = 0

        fun put(netId: Int, x: Float, y: Float) {
            ids[count] = netId
            xs[count] = x
            ys[count] = y
            count++
        }

        override fun locate(packedNetId: Int, out: FloatArray): Boolean {
            for (index in 0 until count) {
                if (ids[index] != packedNetId) continue
                out[0] = xs[index]
                out[1] = ys[index]
                return true
            }
            return false
        }
    }

    /**
     * World coordinates straight through to screen.
     *
     * The bounds are four explicit comparisons rather than [IdentityProjector]'s
     * `worldX !in -bound..bound`. Kotlin compiles a primitive `in` against a literal range to
     * comparisons, so the two are almost certainly identical - but "almost certainly" is not a
     * property to rest a zero on when the alternative is one line.
     */
    private class FlatProjector(private val bound: Float = SCREEN_BOUND) : WorldProjector {

        override fun project(worldX: Float, worldY: Float, out: FloatArray): Boolean {
            if (worldX < -bound || worldX > bound) return false
            if (worldY < -bound || worldY > bound) return false
            out[0] = worldX
            out[1] = worldY
            return true
        }
    }

    private companion object {

        /**
         * A hundred frames per measured block.
         *
         * A residual of a single sixteen-byte object per frame then comes back as 1600 bytes,
         * which no rounding argument explains away, rather than as a 16 that one might.
         */
        const val FRAMES: Int = 100

        /** Blocks run before the first byte is counted. `RenderAllocationTest`'s number. */
        const val WARMUPS: Int = 200

        /** Measured blocks. The smallest is taken. */
        const val ATTEMPTS: Int = 20

        /** Pixels per character, so [CountingCanvas.measure] is a pure function. */
        const val CHAR_WIDTH: Float = 7f

        /** Far enough apart that every marker projects to its own point, and all are on screen. */
        const val MARKER_SPREAD: Float = 40f

        /** Well outside [MARKER_SPREAD] times the marker count, so nothing is clipped. */
        const val SCREEN_BOUND: Float = 10_000f

        /** An arbitrary packed NetId to count up from. */
        const val FIRST_NET_ID: Int = 266

        /** Any fixed instant. The narration clock never advances. */
        const val FROZEN_NANOS: Long = 1_000_000_000L

        /** [AgentNarration.MAX_TTL_SECONDS]. The clock is frozen, so it never elapses anyway. */
        const val CAPTION_TTL: Float = 300f

        /** Any tick. Nothing here reads simulation time. */
        const val TICK: Long = 12L

        /** Any duration. It is formatted into a panel row at VERBOSE and never measured. */
        const val DURATION: Long = 400_000L

        /** What `world.get_component` declares: an integer identity argument. */
        val ENTITY_RULE: AnchorRule = AnchorRule.of(listOf(declared("id", "integer")))

        /** What `world.spawn_blueprint` declares: two numbers named x and y. */
        val POINT_RULE: AnchorRule =
            AnchorRule.of(listOf(declared("x", "number"), declared("y", "number")))

        fun declared(name: String, type: String): AgentToolArg =
            AgentToolArg(name, type, "the $name", required = true, default = null)
    }
}
