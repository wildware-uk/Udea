package dev.wildware.moba

import dev.wildware.udea.assets.InputKey
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
 * The controls asset used to hold numbers, and the numbers changed underneath this game twice:
 * LibGDX's `W` was 51, which under Kool is the digit `3`, and Kool's own desktop codes are not its
 * Android codes. Since issue #228 the asset names its keys - `key(InputKey.W)` - and `udea-render`
 * owns the numbers, so what is left to go wrong on this side is the *naming*: an asset that binds
 * walk-up to `InputKey.S`, or attack to a key nobody expects. Nothing in the build catches that and
 * nothing in a screenshot shows it: `runShot`, `runMatchShot` and `runLaneShot` all synthesise their
 * input through `InjectedIntent`, which never touches a key at all.
 *
 * So this drives **all eight** bound keys, because the failure is per-key, and then every key the
 * game does not bind, because a key doing a job it should not is the same failure seen from the
 * other side.
 *
 * ## Why it stops at the `Intent`
 *
 * `MobaInputTest` already carries the other half - a key held through a whole `GameHost` moving
 * the player's coordinate - and repeating that eight times would be eight seeded matches to
 * assert one array index each. The claim here is narrower: *this key reaches this action*.
 * `DeviceIntent` is the real production `IntentSource`, sampling the real `MobaControls.BINDINGS`
 * read out of the real packed bundle, so nothing on the path from the asset to the intent is a
 * stand-in.
 *
 * `KeyboardState` is, and that is the seam rather than a shortcut: which physical key arrives as
 * which [InputKey] is `udea-render`'s, pinned per key against Kool's real key path by
 * `GlKoolKeyTableTest` (desktop) and `AndroidKeyTableTest` (Kool's Android key map).
 */
class MobaKeyBindingTest {

    /** Holding a key and sampling the real source, as a tick does. */
    private fun sample(vararg held: InputKey): Intent {
        val keys = HeldKeys(held.toSet())
        val intent = Intent(MobaControls.BINDINGS.catalog)
        DeviceIntent(MobaControls.BINDINGS, keys).sample(intent)
        return intent
    }

    /** WASD deflects the move axis, one key at a time, in the direction its name claims. */
    @Test
    fun `each movement key deflects the move axis its own way`() {
        val axis = MobaControls.MOVE_AXIS
        val cases = listOf(
            Triple("W", InputKey.W, 0f to 1f),
            Triple("A", InputKey.A, -1f to 0f),
            Triple("S", InputKey.S, 0f to -1f),
            Triple("D", InputKey.D, 1f to 0f),
        )
        cases.forEach { (name, key, expected) ->
            val intent = sample(key)
            assertEquals(
                expected.first,
                intent.axisX(axis),
                absoluteTolerance = 1e-6f,
                message = "$name deflected move x the wrong way",
            )
            assertEquals(
                expected.second,
                intent.axisY(axis),
                absoluteTolerance = 1e-6f,
                message = "$name deflected move y the wrong way",
            )
        }
    }

    /** Space, Q, E and R each fire their own action and nobody else's. */
    @Test
    fun `each ability key fires its own action and only its own`() {
        val bound = listOf(
            Triple("Space", InputKey.Space, MobaControls.ATTACK_ACTION),
            Triple("Q", InputKey.Q, MobaControls.ATTACK_2_ACTION),
            Triple("E", InputKey.E, MobaControls.ITEM_1_ACTION),
            Triple("R", InputKey.R, MobaControls.ITEM_2_ACTION),
        )
        bound.forEach { (name, key, action) ->
            val intent = sample(key)
            assertTrue(
                intent.isPressed(action),
                "$name did not press the action it is bound to",
            )
            bound.forEach { (otherName, _, other) ->
                if (other != action) {
                    assertFalse(
                        intent.isPressed(other),
                        "$name also pressed $otherName's action",
                    )
                }
            }
        }
    }

    /**
     * The four movement keys fire no ability, and the four ability keys move nothing.
     *
     * The cross-check the two tests above cannot make on their own: an asset where `W` were also
     * bound to attack would pass "W deflects the axis" and "Space fires attack" and still
     * be wrong, because one key would be doing two jobs.
     */
    @Test
    fun `movement and ability keys do not cross over`() {
        val movement = listOf(
            InputKey.W,
            InputKey.A,
            InputKey.S,
            InputKey.D,
        )
        val abilities = listOf(
            InputKey.Space,
            InputKey.Q,
            InputKey.E,
            InputKey.R,
        )
        movement.forEach { key ->
            val intent = sample(key)
            repeat(MobaControls.BINDINGS.catalog.actionCount) { index ->
                assertFalse(
                    intent.isPressed(ActionId(index)),
                    "movement key $key fired action $index",
                )
            }
        }
        abilities.forEach { key ->
            val intent = sample(key)
            assertEquals(
                0f,
                intent.axisX(MobaControls.MOVE_AXIS),
                absoluteTolerance = 1e-6f,
                message = "ability key $key moved the player",
            )
            assertEquals(
                0f,
                intent.axisY(MobaControls.MOVE_AXIS),
                absoluteTolerance = 1e-6f,
                message = "ability key $key moved the player",
            )
        }
    }

    /**
     * Every key the game does not bind does nothing: no action, no movement.
     *
     * The negative the positive tests cannot make: each of them holds a bound key and asserts the
     * job happened, so an asset that *also* bound attack to, say, `InputKey.Digit3` would pass all
     * of them. Here every other key is held alone and must leave the intent empty.
     */
    @Test
    fun `a key the game does not bind does nothing`() {
        val bound = setOf(
            InputKey.W, InputKey.A, InputKey.S, InputKey.D,
            InputKey.Space, InputKey.Q, InputKey.E, InputKey.R,
        )
        val live = InputKey.entries.filter { it !in bound }.filter { key ->
            val intent = sample(key)
            val fired = (0 until MobaControls.BINDINGS.catalog.actionCount).any { intent.isPressed(ActionId(it)) }
            fired || intent.axisX(MobaControls.MOVE_AXIS) != 0f || intent.axisY(MobaControls.MOVE_AXIS) != 0f
        }
        assertEquals(emptyList(), live, "keys the game does not bind still did something")
    }

    /** A keyboard with a fixed set of keys held down and nothing else. */
    private class HeldKeys(private val down: Set<InputKey>) : KeyboardState {

        override fun isKeyDown(key: InputKey): Boolean = key in down

        override fun pressesSince(key: InputKey): Int = if (key in down) 1 else 0

        override fun endSample(): Unit = Unit
    }
}
