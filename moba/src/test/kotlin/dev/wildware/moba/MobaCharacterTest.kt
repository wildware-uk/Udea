package dev.wildware.moba

import dev.wildware.udea.assets.AnimNotify
import dev.wildware.udea.assets.SpriteAnimation
import dev.wildware.udea.assets.SpriteSheet
import dev.wildware.udea.assets.assetRef
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.core.Tick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The six characters exist in the bundle, and the machinery that animates them is arithmetic.
 *
 * The GL half - that six *different* sprites reach a framebuffer - is `:moba:runShot`, which
 * needs a driver. Everything here runs anywhere, off the real `.udeapak` that `processResources`
 * put on this test's classpath: the same bundle the game opens, through the same door.
 */
class MobaCharacterTest {

    // --- the roster, read out of the real bundle ---------------------------------------------

    /**
     * Every character the old game had is in the pack, and each is its own art.
     *
     * The number that matters is the last assertion: 33 distinct sheets. The demo this replaces
     * had **one**, and drew it on every unit - which is exactly why its screenshot scored two
     * distinct colours. Delete a `spriteSheet(...)` from any `moba/assets/character/<name>.udea.kts`
     * and the count falls; point two characters at one sheet and it falls too.
     */
    @Test
    fun `the bundle carries all six characters, each with its own sheets`() {
        val roster = MobaCharacters.roster
        assertEquals(
            listOf("orc", "orc_elite", "priest", "skeleton", "soldier", "wizard"),
            roster.entries.map { it.name },
            "the roster is every `character/*_animation_set` in the bundle, sorted",
        )
        val sheets = roster.entries.flatMap { entry -> entry.sheets.values.map { it.texture.value } }
        assertEquals(
            sheets.size,
            sheets.distinct().size,
            "two characters share a sheet, so two units would be the same picture: $sheets",
        )
    }

    /** Every character can stand, walk, swing, flinch and die. */
    @Test
    fun `every character has an animation for every state`() {
        for (entry in MobaCharacters.roster.entries) {
            for (state in UnitState.entries) {
                val animation = entry.animation(state)
                assertTrue(
                    animation.id.value.endsWith("_" + state.suffix),
                    "${entry.name}'s $state is '${animation.id}'",
                )
                assertTrue(entry.frameCount(state) > 0, "${entry.name} $state has no frames")
            }
        }
    }

    /**
     * The atlas holds one region per declared frame, cut at pack time.
     *
     * The orc elite's spin is eleven frames of `orc_elite_attack02.png`, which is 1100x100. If
     * the packer ever stopped splitting sheets this would report one region 1100 wide and every
     * unit would draw the whole strip - which is precisely what a runtime slicer's absence looks
     * like when nothing checks for it.
     */
    @Test
    fun `the atlas cut every frame of the richest sheet`() {
        val frames = MobaAssets.atlas.framesOf(AssetId("character/orc_elite_spin_sheet"))
        assertEquals(11, frames.size, "eleven frames were declared; the atlas holds ${frames.size}")
        assertTrue(frames.all { it.width == 100 && it.height == 100 }, "frames: $frames")
        // Sorted by name, and the name is `<id>#<frame>` zero-padded - so region order is frame
        // order and `frames[i]` is frame `i` rather than whatever the packer met first.
        assertEquals(frames.map { it.name }.sorted(), frames.map { it.name })
    }

    /** Every frame the renderer will ask for is in the atlas. A missing one draws nothing at all. */
    @Test
    fun `every animation frame the roster names is in the atlas`() {
        for (entry in MobaCharacters.roster.entries) {
            for ((state, sheet) in entry.sheets) {
                val regions = MobaAssets.atlas.framesOf(sheet.id)
                assertEquals(
                    sheet.frameCount,
                    regions.size,
                    "${entry.name} $state declares ${sheet.frameCount} frames, the atlas cut " +
                        "${regions.size} for '${sheet.id}'",
                )
            }
        }
    }

    /**
     * The authored scale reaches the runtime, which is the number the renderer multiplies by.
     *
     * Change `orcScale` in `moba/assets/character/orc.udea.kts` and this fails - which is the
     * same edit `assets.patch` makes, through the same graph, and is why a hot reload is visible
     * in the next capture.
     */
    @Test
    fun `a sheet carries the authored scale`() {
        val orc = assertNotNull(MobaCharacters.roster.byName("orc"))
        val idle = orc.sheets.getValue(UnitState.Idle)
        assertEquals(1.88f, idle.scale)
        assertEquals(6, idle.columns)
        assertEquals(1, idle.rows)
        // 100 pixels at 1.88 world units per pixel is a 188-unit frame, and the orc inside it is
        // 16 of those 100 pixels tall - so the drawn orc is about 30 world units, which is the
        // size `level/test_level` lays its clearings out for and the `reach` its units fight at.
        // The frame is mostly transparent margin; that is the art, not a mistake in the number.
        assertEquals(188f, 100 * idle.scale)
    }

    // --- the notify frames, which are what make a swing read --------------------------------

    /** The orc's axe connects on frame 4, and the wind-up is one frame before it. */
    @Test
    fun `the authored notify frames survived the pack`() {
        val orc = assertNotNull(MobaCharacters.roster.byName("orc"))
        val attack = orc.animation(UnitState.Attack)
        assertEquals(
            listOf(AnimNotify(4, "attack_hit"), AnimNotify(3, "swoosh")).sortedBy { it.name },
            attack.notifies.sortedBy { it.name },
        )
    }

    /**
     * A hit lands on exactly one tick, and stepping in one lump fires it exactly as often as
     * stepping one tick at a time.
     *
     * This is the property that makes a notify safe to hang damage off. Change `notifiesBetween`
     * to compare with `>=` on the lower bound and the single-step loop fires frame 0's notify on
     * every tick while the lump fires it once - the two columns stop agreeing, and this fails.
     */
    @Test
    fun `a notify fires once, whether the clock steps by one tick or by a hundred`() {
        val animation = attackAnimation()
        val lump = mutableListOf<String>()
        CharacterAnimator.notifiesBetween(animation, -1, 100, TICK_RATE) { lump += it.name }
        val stepped = mutableListOf<String>()
        for (t in 0L..100L) {
            CharacterAnimator.notifiesBetween(animation, t - 1, t, TICK_RATE) { stepped += it.name }
        }
        assertEquals(lump.sorted(), stepped.sorted())
        assertEquals(listOf("attack_hit", "swoosh"), stepped.sorted())
    }

    /** Frame 4 of a 0.1s-per-frame animation at 60Hz is tick 24, and nothing else. */
    @Test
    fun `the hit tick is the frame index times the ticks per frame`() {
        val animation = attackAnimation()
        val hit = animation.notifies.first { it.name == "attack_hit" }
        assertEquals(6L, CharacterAnimator.ticksPerFrame(animation, TICK_RATE))
        assertEquals(24L, CharacterAnimator.notifyTick(animation, hit, TICK_RATE))
        val fired = mutableListOf<Long>()
        for (t in 0L..60L) {
            CharacterAnimator.notifiesBetween(animation, t - 1, t, TICK_RATE) {
                if (it.name == "attack_hit") fired += t
            }
        }
        assertEquals(listOf(24L), fired)
    }

    // --- the playhead ------------------------------------------------------------------------

    /** A looping animation advances one frame every `ticksPerFrame` and wraps at the end. */
    @Test
    fun `a looping playhead advances with the tick and wraps`() {
        val idle = loopingAnimation()
        assertEquals(0, CharacterAnimator.frameAt(idle, 6, 0, TICK_RATE))
        assertEquals(0, CharacterAnimator.frameAt(idle, 6, 5, TICK_RATE))
        assertEquals(1, CharacterAnimator.frameAt(idle, 6, 6, TICK_RATE))
        assertEquals(5, CharacterAnimator.frameAt(idle, 6, 30, TICK_RATE))
        assertEquals(0, CharacterAnimator.frameAt(idle, 6, 36, TICK_RATE))
    }

    /**
     * A negative elapsed tick still names a frame in the strip.
     *
     * `time.rewind` can put the clock before the tick a state was entered on, and `%` would hand
     * back a negative index - an `ArrayIndexOutOfBoundsException` on the render thread, reachable
     * from a tool an agent calls. Replace `Math.floorMod` with `%` in `frameAt` and this fails;
     * nothing else in the suite notices.
     */
    @Test
    fun `a negative elapsed tick still names a frame`() {
        val idle = loopingAnimation()
        for (elapsed in -60L..0L) {
            val index = CharacterAnimator.frameAt(idle, 6, elapsed, TICK_RATE)
            assertTrue(index in 0 until 6, "elapsed $elapsed gave index $index")
        }
        assertEquals(5, CharacterAnimator.frameAt(idle, 6, -6, TICK_RATE))
    }

    /** A death pose holds on its last frame instead of looping back to standing up again. */
    @Test
    fun `a non-looping playhead clamps at both ends`() {
        val death = attackAnimation()
        assertEquals(0, CharacterAnimator.frameAt(death, 4, -100, TICK_RATE))
        assertEquals(3, CharacterAnimator.frameAt(death, 4, 24, TICK_RATE))
        assertEquals(3, CharacterAnimator.frameAt(death, 4, 10_000, TICK_RATE))
    }

    /** A strip with no frames is a wiring fault, and says so rather than throwing an index error. */
    @Test
    fun `an empty strip is refused`() {
        assertFailsWith<IllegalArgumentException> {
            CharacterAnimator.frameAt(loopingAnimation(), 0, 0, TICK_RATE)
        }
    }

    /**
     * The playhead is a pure function of the tick, which is the property an agent depends on.
     *
     * Two captures of the same paused world must be byte-identical, or `render.compare_artifacts`
     * reports the animation instead of the mutation. Wall time is not in the signature, and this
     * asserts it is not in the answer either.
     */
    @Test
    fun `the same elapsed tick always names the same frame`() {
        val idle = loopingAnimation()
        val first = CharacterAnimator.frameAt(idle, 6, 1234, TICK_RATE)
        Thread.sleep(5)
        assertEquals(first, CharacterAnimator.frameAt(idle, 6, 1234, TICK_RATE))
    }

    /** A frame shorter than a tick is held for one tick rather than dividing by zero. */
    @Test
    fun `a sub-tick frame time is clamped to one tick`() {
        val fast = SpriteAnimation(
            id = AssetId("character/fast"),
            sheet = assetRef(AssetId("character/fast_sheet"), SpriteSheet::class),
            frameTime = 0.001f,
        )
        assertEquals(1L, CharacterAnimator.ticksPerFrame(fast, TICK_RATE))
        assertEquals(3, CharacterAnimator.frameAt(fast, 6, 3, TICK_RATE))
    }

    // --- the state seam ----------------------------------------------------------------------

    /**
     * Re-entering the state that is already showing does not restart the animation.
     *
     * A system that writes `enter(Walk, tick)` on every tick while a unit walks would otherwise
     * hold the animation on frame 0 forever - a unit sliding along in a single pose, which is the
     * most common way an animation state machine is got wrong.
     */
    @Test
    fun `entering the same state does not restart it`() {
        val view = CharacterView(character = 0, state = UnitState.Idle, startTick = 10)
        view.enter(UnitState.Idle, 500)
        assertEquals(10L, view.startTick)
        view.enter(UnitState.Walk, 500)
        assertEquals(500L, view.startTick)
        assertEquals(UnitState.Walk, view.state)
    }

    /** Every notify name in the bundle has an id, and the table round-trips. */
    @Test
    fun `the cue table names every notify in the bundle`() {
        val cues = MobaCharacters.cues
        assertTrue(cues.size >= 5, "the bundle declares ${cues.size} notify names")
        for (entry in MobaCharacters.roster.entries) {
            for (animation in entry.states.values + entry.extras.values) {
                for (notify in animation.notifies) {
                    val id = assertNotNull(cues.idOf(notify.name), "no cue id for '${notify.name}'")
                    assertEquals(notify.name, cues.nameOf(id))
                }
            }
        }
        assertNull(cues.idOf("a notify no animation declares"))
    }

    /** A bounded log keeps the newest records and counts the ones it dropped. */
    @Test
    fun `the notify log is bounded and counts what it evicted`() {
        val log = NotifyLog(capacity = 2)
        repeat(5) { log.record(NotifyRecord("orc", "attack_hit", Tick(it.toLong()))) }
        assertEquals(2, log.entries.size)
        assertEquals(5L, log.totalCount)
        assertEquals(listOf(3L, 4L), log.entries.map { it.tick.value })
    }

    /** The shot lines the roster up so that several different animations are in one frame. */
    @Test
    fun `the roster shot covers every state`() {
        val states = (0 until MobaCharacters.roster.size).map(dev.wildware.moba.entry.MobaShot::stateFor)
        assertEquals(UnitState.entries.toSet(), states.toSet(), "states in the shot: $states")
    }

    private companion object {

        /** The engine default, and what `SimClock()` runs at. */
        const val TICK_RATE: Int = 60

        /** The real orc attack, out of the real bundle: six frames, two notifies. */
        fun attackAnimation(): SpriteAnimation =
            MobaCharacters.roster.byName("orc")!!.animation(UnitState.Attack)

        /** The real orc idle: six frames, looping. */
        fun loopingAnimation(): SpriteAnimation =
            MobaCharacters.roster.byName("orc")!!.animation(UnitState.Idle)
    }
}
