package dev.wildware.moba

import dev.wildware.udea.render.input.ActionId
import dev.wildware.udea.render.input.DeviceIntent
import dev.wildware.udea.render.input.Intent
import dev.wildware.udea.render.input.KeyboardState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every key this game binds produces the intent it is supposed to, and no other.
 *
 * ## The defect this exists for
 *
 * A key code is a number, and the number changed underneath this game when LibGDX left. Gdx's
 * `W` is 51 and Kool's 51 is the digit `3`; gdx's `Space` is 62 and Kool's 62 is `>`. Carried
 * across unchanged, `moba/game/assets/control/controls.udea.kts` would have gone on compiling,
 * `udeaValidateAssets` would have gone on passing it, `MobaControlAssets` would have built a
 * perfectly well-formed `InputBindings` out of it, and the game would have answered to eight
 * keys nobody presses. Nothing in the build catches that. Nothing in a screenshot shows it:
 * `runShot`, `runMatchShot` and `runLaneShot` all synthesise their input through
 * `InjectedIntent`, which never touches a key code at all.
 *
 * So this is the only thing between a renumbered keyboard and a player. It drives **all eight**
 * bound keys, because the failure is per-key: a table with one number wrong looks exactly like a
 * table with eight numbers right until the wrong key is the one you press.
 *
 * ## Why it stops at the `Intent`
 *
 * `MobaInputTest` already carries the other half - a key held through a whole `GameHost` moving
 * the player's coordinate - and repeating that eight times would be eight seeded matches to
 * assert one array index each. The claim here is narrower and is the one that broke: *this code
 * reaches this action*. `DeviceIntent` is the real production `IntentSource`, sampling the real
 * `MobaControls.BINDINGS` read out of the real packed bundle, so nothing on the path from the
 * asset to the intent is a stand-in.
 *
 * `KeyboardState` is, and that is the seam rather than a shortcut: `DeviceIntent` names no
 * backend type, and the class that feeds it real key events is issue #224's and does not exist
 * yet. What a Kool key event *is* - and therefore whether 87 is the number a driver delivers -
 * is that ticket's to pin, in `udea-render`, against a running context. What this file pins is
 * that the game asks for 87, that the asset and the constant table agree it is "walk up", and
 * that no other key does.
 *
 * ## The eight are all printable, which is why they are GLFW's own numbers
 *
 * Kool's desktop backend resolves a key as `KEY_CODE_MAP[key] ?: UniversalKeyCode(key)`, and
 * `KEY_CODE_MAP` holds only special keys - the modifiers, escape, enter, tab, backspace, delete,
 * insert, home, end, the page and cursor keys, the numpad and F1-F12 - which it maps to Kool's
 * own **negative** codes (escape is -9, not GLFW's 256). Every key this game binds is a letter or
 * the space bar, so every one of them falls through to the raw GLFW int. A binding on escape or
 * an arrow would need Kool's number instead, and neither the character nor the GLFW constant
 * would be right for it.
 */
class MobaKeyBindingTest {

    /** Holding a key and sampling the real source, as a tick does. */
    private fun sample(vararg keycodes: Int): Intent {
        val keys = HeldKeys(keycodes.toSet())
        val intent = Intent(MobaControls.BINDINGS.catalog)
        DeviceIntent(MobaControls.BINDINGS, keys).sample(intent)
        return intent
    }

    /** WASD deflects the move axis, one key at a time, in the direction its name claims. */
    @Test
    fun `each movement key deflects the move axis its own way`() {
        val axis = MobaControls.MOVE_AXIS
        val cases = listOf(
            Triple("W", MobaControls.Keys.W, 0f to 1f),
            Triple("A", MobaControls.Keys.A, -1f to 0f),
            Triple("S", MobaControls.Keys.S, 0f to -1f),
            Triple("D", MobaControls.Keys.D, 1f to 0f),
        )
        cases.forEach { (name, code, expected) ->
            val intent = sample(code)
            assertEquals(
                expected.first,
                intent.axisX(axis),
                absoluteTolerance = 1e-6f,
                message = "$name (code $code) deflected move x the wrong way",
            )
            assertEquals(
                expected.second,
                intent.axisY(axis),
                absoluteTolerance = 1e-6f,
                message = "$name (code $code) deflected move y the wrong way",
            )
        }
    }

    /** Space, Q, E and R each fire their own action and nobody else's. */
    @Test
    fun `each ability key fires its own action and only its own`() {
        val bound = listOf(
            Triple("Space", MobaControls.Keys.SPACE, MobaControls.ATTACK_ACTION),
            Triple("Q", MobaControls.Keys.Q, MobaControls.ATTACK_2_ACTION),
            Triple("E", MobaControls.Keys.E, MobaControls.ITEM_1_ACTION),
            Triple("R", MobaControls.Keys.R, MobaControls.ITEM_2_ACTION),
        )
        bound.forEach { (name, code, action) ->
            val intent = sample(code)
            assertTrue(
                intent.isPressed(action),
                "$name (code $code) did not press the action it is bound to",
            )
            bound.forEach { (otherName, _, other) ->
                if (other != action) {
                    assertFalse(
                        intent.isPressed(other),
                        "$name (code $code) also pressed $otherName's action",
                    )
                }
            }
        }
    }

    /**
     * The four movement keys fire no ability, and the four ability keys move nothing.
     *
     * The cross-check the two tests above cannot make on their own: a table where `W` happened to
     * carry `Space`'s number would pass "W deflects the axis" and "Space fires attack" and still
     * be wrong, because one key would be doing two jobs.
     */
    @Test
    fun `movement and ability keys do not cross over`() {
        val movement = listOf(
            MobaControls.Keys.W,
            MobaControls.Keys.A,
            MobaControls.Keys.S,
            MobaControls.Keys.D,
        )
        val abilities = listOf(
            MobaControls.Keys.SPACE,
            MobaControls.Keys.Q,
            MobaControls.Keys.E,
            MobaControls.Keys.R,
        )
        movement.forEach { code ->
            val intent = sample(code)
            repeat(MobaControls.BINDINGS.catalog.actionCount) { index ->
                assertFalse(
                    intent.isPressed(ActionId(index)),
                    "movement key $code fired action $index",
                )
            }
        }
        abilities.forEach { code ->
            val intent = sample(code)
            assertEquals(
                0f,
                intent.axisX(MobaControls.MOVE_AXIS),
                absoluteTolerance = 1e-6f,
                message = "ability key $code moved the player",
            )
            assertEquals(
                0f,
                intent.axisY(MobaControls.MOVE_AXIS),
                absoluteTolerance = 1e-6f,
                message = "ability key $code moved the player",
            )
        }
    }

    /**
     * None of the eight is still on the number LibGDX used for it.
     *
     * ## Why this is a negative and not `assertEquals('W'.code, Keys.W)`
     *
     * `MobaControls.Keys.W` *is* `'W'.code`, so asserting that would restate the declaration and
     * pass for any value it ever took. A test that cannot fail is worse than no test, because it
     * reads as coverage. What can fail is the revert: somebody merging an older branch, or
     * copying a binding out of one of this repository's many surviving gdx-era documents, puts
     * `51` back and the game answers to the digit `3`.
     *
     * ## What this file does not claim
     *
     * That 87 is the number a driver delivers. Nothing here has a GL context, a window or a key
     * event in it, so that claim would have no subject; it belongs to `udea-render`, where issue
     * #224's `GlKoolInputTest` drives Kool's own GLFW key callback and reads the code back out of
     * a running context. What these tests pin is the game's half - that the packed asset, this
     * constant table and the action each key fires all agree, key by key, with no crossover -
     * which is the half that would otherwise have no test at all.
     */
    @Test
    fun `no binding is still on the LibGDX code it replaced`() {
        // `com.badlogic.gdx.Input.Keys` values, from the table this game used to import.
        val gdx = listOf(
            Triple("Q", MobaControls.Keys.Q, 45),
            Triple("W", MobaControls.Keys.W, 51),
            Triple("A", MobaControls.Keys.A, 29),
            Triple("S", MobaControls.Keys.S, 47),
            Triple("D", MobaControls.Keys.D, 32),
            Triple("E", MobaControls.Keys.E, 33),
            Triple("R", MobaControls.Keys.R, 46),
            Triple("SPACE", MobaControls.Keys.SPACE, 62),
        )
        gdx.forEach { (name, bound, old) ->
            assertTrue(
                bound != old,
                "$name is back on LibGDX's code $old, which is a different key under Kool",
            )
        }
    }

    /**
     * The code each key used to carry does not do that key's job any more.
     *
     * ## Why the positive tests above are not enough on their own
     *
     * Each of them holds `MobaControls.Keys.W` and asserts the axis moved, so it proves the code
     * in the table reaches the action the table says it does. It cannot notice that the table
     * holds the wrong number, because it asks the question using the answer. Issue #224 made
     * exactly this mistake in `udea-render` and it is what let a wrong key code look tested.
     *
     * The negative closes it. Holding the **LibGDX** code for a key and getting nothing is a
     * measurement of the table rather than a restatement of it: a branch that reverts these eight
     * numbers passes every assertion above and fails every assertion here.
     *
     * ## One of the eight needs care, and it is the reason this is per-key
     *
     * Gdx's `D` is 32, and 32 is Kool's space bar, so holding gdx-`D` really does press attack -
     * it is a live code, just not the one `D` should be. So the claim is not "the old code does
     * nothing" but "the old code does not do *this key's* job", which is what a player would
     * notice and is true of all eight.
     */
    @Test
    fun `the LibGDX code for each key no longer does that key's job`() {
        val axis = MobaControls.MOVE_AXIS
        listOf(
            Triple("W", 51, 0f to 1f),
            Triple("A", 29, -1f to 0f),
            Triple("S", 47, 0f to -1f),
            Triple("D", 32, 1f to 0f),
        ).forEach { (name, gdxCode, wouldBe) ->
            val intent = sample(gdxCode)
            assertFalse(
                intent.axisX(axis) == wouldBe.first && intent.axisY(axis) == wouldBe.second,
                "LibGDX's $name (code $gdxCode) still walks the player $name-wards, so the " +
                    "binding table was never moved off gdx's numbering",
            )
        }
        listOf(
            Triple("Space", 62, MobaControls.ATTACK_ACTION),
            Triple("Q", 45, MobaControls.ATTACK_2_ACTION),
            Triple("E", 33, MobaControls.ITEM_1_ACTION),
            Triple("R", 46, MobaControls.ITEM_2_ACTION),
        ).forEach { (name, gdxCode, action) ->
            assertFalse(
                sample(gdxCode).isPressed(action),
                "LibGDX's $name (code $gdxCode) still fires $name's ability, so the binding " +
                    "table was never moved off gdx's numbering",
            )
        }
    }

    /** A keyboard with a fixed set of keys held down and nothing else. */
    private class HeldKeys(private val down: Set<Int>) : KeyboardState {

        override fun isKeyDown(keycode: Int): Boolean = keycode in down

        override fun pressesSince(keycode: Int): Int = if (keycode in down) 1 else 0

        override fun endSample(): Unit = Unit
    }
}
