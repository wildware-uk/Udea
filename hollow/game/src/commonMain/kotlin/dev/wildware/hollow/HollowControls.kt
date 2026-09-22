package dev.wildware.hollow

import dev.wildware.udea.assets.InputKey
import dev.wildware.udea.render.input.ActionBinding
import dev.wildware.udea.render.input.ActionId
import dev.wildware.udea.render.input.Axis2DBinding
import dev.wildware.udea.render.input.AxisId
import dev.wildware.udea.render.input.InputBindings

/**
 * What Hollow binds, and the names it binds them by: walk on WASD or the left stick, and hold
 * Shift to run (issue #250); Space swings, Q dashes and E heals (issue #252).
 *
 * ## Keys are named, never numbered
 *
 * `InputKey.W`, never `87` (issue #228). The number for a physical key is `udea-render`'s, one
 * table per backend, and it is different on the desktop and on Android - which is why a game that
 * wrote the number compiled, validated, ran, and answered to whatever key that number happened to
 * mean on that backend.
 *
 * ## Why this is Kotlin and `moba`'s is an asset
 *
 * `moba/game/assets/control/controls.udea.kts` declares its controls in the asset graph and
 * `MobaControlAssets` reads them back, so rebinding a key there is an asset edit with no Kotlin
 * recompiled. Hollow could have the same, and the reason it does not yet is that the *reader* is
 * two hundred lines living in `moba:game`, which `hollow:game` cannot depend on - module arrows
 * point downward only. Copying it here would be the copy-pasted-logic smell the charter's section 8
 * rejects, and lifting it into the engine is a change to `udea-render`'s public surface that wants
 * a ticket of its own rather than a corner of the player's. So Hollow declares two bindings in
 * Kotlin, over the same `InputKey` names the asset would carry, and `InputBindings`' own KDoc
 * records that this was the whole tree's shape until recently.
 *
 * ## The names are namespaced
 *
 * `hollow/move`, not `move`. `InputCatalog` assigns ids from the sorted names across the whole
 * game, so two modules that both called an axis `move` would collide - and a collision means one
 * silently addressing the other's axis.
 */
public object HollowControls {

    /** Walk, or run with [RUN] held. WASD, or the left stick. */
    public const val MOVE: String = "hollow/move"

    /** Held, not tapped: the character runs while it is down. Either Shift. */
    public const val RUN: String = "hollow/run"

    /** Swing at every fox in reach, as often as the cooldown allows while it is held. Space. */
    public const val ATTACK: String = "hollow/attack"

    /** Dash the way the character is facing. Q. */
    public const val DASH: String = "hollow/dash"

    /** Heal. E. */
    public const val HEAL: String = "hollow/heal"

    /**
     * The bindings, built once.
     *
     * An `object`'s `val` and not a function, for the reason `MobaControls.BINDINGS` gives at
     * length: the `InputCatalog` inside it is an identity that every `Intent` and every `ActionId`
     * in this game is checked against, and two catalogs over the same names are still two catalogs.
     */
    public val BINDINGS: InputBindings = InputBindings(
        actions = listOf(
            ActionBinding(
                name = RUN,
                keys = listOf(InputKey.LeftShift, InputKey.RightShift),
                // The south face button, held: where a pad puts "sprint".
                buttons = intArrayOf(GAMEPAD_RUN_BUTTON),
            ),
            // The other three face buttons, where a pad puts its actions.
            ActionBinding(name = ATTACK, keys = listOf(InputKey.Space), buttons = intArrayOf(GAMEPAD_ATTACK_BUTTON)),
            ActionBinding(name = DASH, keys = listOf(InputKey.Q), buttons = intArrayOf(GAMEPAD_DASH_BUTTON)),
            ActionBinding(name = HEAL, keys = listOf(InputKey.E), buttons = intArrayOf(GAMEPAD_HEAL_BUTTON)),
        ),
        axes = listOf(
            Axis2DBinding(
                name = MOVE,
                negativeX = InputKey.A,
                positiveX = InputKey.D,
                negativeY = InputKey.S,
                positiveY = InputKey.W,
                gamepadAxisX = LEFT_STICK_X,
                gamepadAxisY = LEFT_STICK_Y,
            ),
        ),
    )

    /** [MOVE]'s id in [BINDINGS]' catalog. Resolved once; it is an array index. */
    public val MOVE_AXIS: AxisId = BINDINGS.catalog.axis(MOVE)

    /** [RUN]'s id. */
    public val RUN_ACTION: ActionId = BINDINGS.catalog.action(RUN)

    /** [ATTACK]'s id. */
    public val ATTACK_ACTION: ActionId = BINDINGS.catalog.action(ATTACK)

    /** [DASH]'s id. */
    public val DASH_ACTION: ActionId = BINDINGS.catalog.action(DASH)

    /** [HEAL]'s id. */
    public val HEAL_ACTION: ActionId = BINDINGS.catalog.action(HEAL)

    /** The left stick's x, as every pad this engine has seen numbers it. */
    private const val LEFT_STICK_X: Int = 0

    /** @see LEFT_STICK_X */
    private const val LEFT_STICK_Y: Int = 1

    /** A pad's south face button - A on an Xbox pad, cross on a PlayStation one. */
    private const val GAMEPAD_RUN_BUTTON: Int = 0

    /** The west face button - X on an Xbox pad, square on a PlayStation one. */
    private const val GAMEPAD_ATTACK_BUTTON: Int = 2

    /** The east face button - B, or circle. */
    private const val GAMEPAD_DASH_BUTTON: Int = 1

    /** The north face button - Y, or triangle. */
    private const val GAMEPAD_HEAL_BUTTON: Int = 3
}
