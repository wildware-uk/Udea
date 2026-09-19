package dev.wildware.moba

import dev.wildware.composegl.ui.backend.HeadlessBackend
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.graphics.DrawCall
import dev.wildware.composegl.ui.graphics.RecordingCanvas
import dev.wildware.composegl.ui.testing.UiTest
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.moba.ability.MobaAbilities
import dev.wildware.moba.ability.UnitBlueprint
import dev.wildware.moba.level.Team
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The drawing half of the HUD: what a [HudState] turns into on screen, composed with no window.
 *
 * `MobaHudTest` proves the numbers in a [HudState] are the game's. This proves the other half of "a
 * player can read them": that each number reaches the screen, in the place it belongs, and that a
 * number which changes between frames changes on screen too. Issue #188 moved this half from
 * `BitmapFont2D` sprites to ComposeGL, and ComposeGL's `HeadlessBackend` - a recording canvas and a
 * font measurer, and no GL context - is what makes it testable: the text is read off a real draw of the
 * real composable rather than off the composable's arguments. That the same drawing reaches a *capture*
 * is `GlCapturedUiTest`'s claim in `udea-render`, and `MatchShot`'s about this HUD.
 *
 * Every state here is filled in by hand, through the same `internal` setters `MobaHudModel.sample`
 * uses, because the subject is the drawing and not the sampling.
 */
class MobaHudScreenTest {

    @Test
    fun `a living player reads their unit and health, and the bar is as long as the health`() {
        val state = living()

        screen(state).use { (ui, _) ->
            assertEquals("ORC_ELITE   330 / 750", ui.text(MobaHudTags.VITALS))
            val fill = ui.frame().rectangles(MobaHudScreen.HEALTH_FILL).single()
            assertEquals(
                MobaHudScreen.BAR_WIDTH * 330f / 750f,
                fill.rect.width,
                absoluteTolerance = 0.01f,
                message = "the health bar's fill is not the fraction of health the player has",
            )
            ui.assertDoesNotExist(MobaHudTags.MANA)
            ui.assertDoesNotExist(MobaHudTags.DEATH)
        }
    }

    @Test
    fun `a unit with mana gets a mana bar, and one without does not`() {
        val state = living().apply {
            mana = 30f
            maxMana = 120f
        }

        screen(state).use { (ui, _) ->
            ui.assertExists(MobaHudTags.MANA)
            val fill = ui.frame().rectangles(MobaHudScreen.MANA_FILL).single()
            assertEquals(MobaHudScreen.BAR_WIDTH * 0.25f, fill.rect.width, absoluteTolerance = 0.01f)
        }
    }

    @Test
    fun `every slot shows its key, a cooling slot its seconds and its shutter, and granted slots their names`() {
        val state = living()

        screen(state).use { (ui, _) ->
            assertEquals(listOf("SPACE"), ui.texts(MobaHudTags.slot(0)), "a ready slot is its key and nothing else")
            assertEquals(listOf("Q", "12.7s"), ui.texts(MobaHudTags.slot(1)), "761 ticks at 60Hz is 12.68s, which rounds to 12.7s")
            assertEquals(listOf("E"), ui.texts(MobaHudTags.slot(2)), "an empty item slot still names its key")
            assertEquals(listOf("R"), ui.texts(MobaHudTags.slot(3)))
            assertEquals(listOf("NPC_MELEE", "ORC_ELITE_SPIN"), ui.texts(MobaHudTags.NAMES))
            val shutter = ui.frame().rectangles(MobaHudScreen.SWEEP).single()
            assertEquals(
                MobaHudScreen.SLOT_SIZE * 761f / 900f,
                shutter.rect.height,
                absoluteTolerance = 0.01f,
                message = "the shutter over a cooling slot is not the fraction of the cooldown left",
            )
        }
    }

    @Test
    fun `a dead player is told so and when they are back, and is offered no slots`() {
        val state = HudState().apply {
            died = true
            respawnTicks = 150
            tickRate = 60
        }

        screen(state).use { (ui, _) ->
            assertEquals(listOf("YOU DIED", "back in 2.5s"), ui.texts(MobaHudTags.DEATH))
            ui.assertDoesNotExist(MobaHudTags.VITALS)
            ui.assertDoesNotExist(MobaHudTags.slot(0))
        }
    }

    @Test
    fun `a death with no respawn scheduled says only that the player died`() {
        val state = HudState().apply { died = true }

        screen(state).use { (ui, _) ->
            assertEquals(listOf("YOU DIED"), ui.texts(MobaHudTags.DEATH))
        }
    }

    /**
     * `MobaShot` stands the roster up with no player and no match. `alive` and `died` both false is
     * a third state, not a death, and it must draw nothing - a YOU DIED across the roster picture
     * is what an `else` would do.
     */
    @Test
    fun `a world that never had a player or a match draws nothing at all`() {
        screen(HudState()).use { (ui, _) ->
            ui.assertDoesNotExist(MobaHudTags.VITALS)
            ui.assertDoesNotExist(MobaHudTags.DEATH)
            ui.assertDoesNotExist(MobaHudTags.SCORE)
            ui.assertDoesNotExist(MobaHudTags.RESULT)
            assertTrue(ui.frame().texts().isEmpty(), "something was drawn over a world with no player in it")
        }
    }

    @Test
    fun `the score is drawn while the fight is on, and the winner once it is decided`() {
        val state = living().apply {
            hasMatch = true
            matchNumber = 1
            orcAlive = 2
            soldierAlive = 8
            undeadAlive = 8
        }

        screen(state).use { (ui, screen) ->
            assertEquals("MATCH 1    ORC 2   SOLDIER 8   UNDEAD 8", ui.text(MobaHudTags.SCORE))
            ui.assertDoesNotExist(MobaHudTags.RESULT)

            state.matchDecided = true
            state.winner = Team.SOLDIER
            screen.refresh()
            ui.settle()
            assertEquals("SOLDIER WINS", ui.text(MobaHudTags.RESULT))

            state.winner = Team.NONE
            screen.refresh()
            ui.settle()
            assertEquals("DRAW", ui.text(MobaHudTags.RESULT))
        }
    }

    /**
     * [HudState] is refilled in place and is not Compose state, so nothing tells the composition a
     * field moved. [MobaHudScreen.refresh] is what does, once a frame, and this is the test that
     * fails if it stops: the second reading would still say 330.
     */
    @Test
    fun `a number that changes between frames changes on screen`() {
        val state = living()

        screen(state).use { (ui, screen) ->
            assertEquals("ORC_ELITE   330 / 750", ui.text(MobaHudTags.VITALS))

            state.health = 120f
            state.setSlot(1, MobaAbilities.ORC_SPIN, remainingTicks = 30, totalTicks = 900)
            screen.refresh()
            ui.settle()

            assertEquals("ORC_ELITE   120 / 750", ui.text(MobaHudTags.VITALS))
            assertEquals(listOf("Q", "0.5s"), ui.texts(MobaHudTags.slot(1)))

            state.alive = false
            state.died = true
            state.clearSlots()
            screen.refresh()
            ui.settle()

            ui.assertDoesNotExist(MobaHudTags.VITALS)
            assertEquals(listOf("YOU DIED"), ui.texts(MobaHudTags.DEATH))
        }
    }

    /**
     * The panels are solid, in the colours [MobaHudLook] publishes. `MatchShot` reads one pixel of each
     * out of a capture to decide the HUD reached it, and a translucent panel's pixel is a blend with
     * whatever melee is behind it - so this is what keeps that check meaningful.
     */
    @Test
    fun `the player panel, the score strip and both banners are solid, in the colours MobaHudLook names`() {
        val state = living().apply {
            hasMatch = true
            matchDecided = true
            winner = Team.ORC
        }

        screen(state).use { (ui, _) ->
            val canvas = ui.frame()
            val panels = canvas.rectangles(Colour.rgb(MobaHudLook.PANEL_RGB.toLong()))
            assertEquals(
                listOf(0f, MobaHudLook.MARGIN),
                panels.map { it.rect.left }.sorted(),
                "expected the score strip across the whole width and the player panel in from the margin",
            )
            assertEquals(1, canvas.rectangles(Colour.rgb(MobaHudLook.RESULT_RGB.toLong())).size)
        }
        screen(HudState().apply { died = true }).use { (ui, _) ->
            assertEquals(1, ui.frame().rectangles(Colour.rgb(MobaHudLook.DEATH_RGB.toLong())).size)
        }
    }

    /** An elite orc on 330 of 750, sword ready, spin cooling with 761 of 900 ticks left, items empty. */
    private fun living(): HudState = HudState().apply {
        alive = true
        unitName = "orc_elite"
        health = 330f
        maxHealth = 750f
        tickRate = 60
        slotCount = UnitBlueprint.ABILITY_SLOTS
        setSlot(0, MobaAbilities.MELEE, remainingTicks = 0, totalTicks = 40)
        setSlot(1, MobaAbilities.ORC_SPIN, remainingTicks = 761, totalTicks = 900)
    }

    private data class Composed(val ui: UiTest, val screen: MobaHudScreen) : AutoCloseable {
        override fun close() = ui.close()
    }

    private fun screen(state: HudState): Composed {
        val screen = MobaHudScreen(state, KEYS)
        val ui = uiTest(SIZE, HeadlessBackend()) { screen.content() }
        return Composed(ui, screen)
    }

    /** One whole frame, drawn fresh into the headless backend's recording canvas. */
    private fun UiTest.frame(): RecordingCanvas {
        val canvas = backend.canvas as RecordingCanvas
        canvas.clear(Rect.of(0f, 0f, size.width, size.height))
        render()
        return canvas
    }

    private fun RecordingCanvas.rectangles(colour: Colour): List<DrawCall.Rectangle> =
        only<DrawCall.Rectangle>().filter { it.colour == colour }

    private companion object {
        val SIZE = Size(1280f, 720f)

        /** The labels `MobaHudSystem.keyLabels` reads out of the shipped bindings today. */
        val KEYS = arrayOf("SPACE", "Q", "E", "R")
    }
}
