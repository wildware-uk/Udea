package dev.wildware.moba

import com.badlogic.gdx.Input
import dev.wildware.udea.render.input.ActionBinding
import dev.wildware.udea.render.input.Axis2DBinding
import dev.wildware.udea.render.input.InputBindings

/**
 * What this game binds, and the only place a key code appears in it.
 *
 * ## Ported from the asset, and where the asset went
 *
 * `moba/src/main/assets/control/controls.udea.kts` is the mechanically migrated version of the
 * old game's control script and declares exactly these names: `attack`, `attack_2` and the
 * `move` axis over WASD. It is in the **migrated corpus** root, not the packed one, because
 * `control`, `binding` and `axis2DBinding` are not among the kinds `udeaPackBundle` publishes
 * yet (see `moba/build.gradle.kts`, `udea { assetRoots }`), so nothing can load it at runtime.
 * These bindings are that script, in code, until the pack learns the kind - the shapes are
 * deliberately one-to-one so that becomes a loader rather than a redesign.
 *
 * The key codes are `com.badlogic.gdx.Input.Keys` values, which is what the asset script writes
 * as bare integer literals with a comment explaining why it cannot import them.
 *
 * ## The names are namespaced
 *
 * `moba/move`, not `move`. [dev.wildware.udea.render.input.InputCatalog] assigns ids by sorted
 * name across the whole game, so two modules that both called an axis `move` would collide - and
 * a collision here means one silently addressing the other's axis.
 */
public object MobaControls {

    /** Walk. WASD, or the left stick. */
    public const val MOVE: String = "moba/move"

    /** Primary attack. Space, or the south face button. */
    public const val ATTACK: String = "moba/attack"

    /** Secondary attack. Q, or the west face button. */
    public const val ATTACK_2: String = "moba/attack_2"

    /**
     * The bindings, built once.
     *
     * An `object`'s `val` and not a function, because the [dev.wildware.udea.render.input.InputCatalog]
     * inside it is an identity that an `Intent`, an `InjectedIntent` and every `ActionId` in this
     * game are checked against. Two catalogs with the same names are still two catalogs, and
     * `Intent.copyFrom` refuses to cross them - which is the check that stops an agent's injected
     * intent being sampled into a simulation that numbered its actions differently.
     */
    public val BINDINGS: InputBindings = InputBindings(
        actions = listOf(
            ActionBinding(ATTACK, keys = intArrayOf(Input.Keys.SPACE), buttons = intArrayOf(0)),
            ActionBinding(ATTACK_2, keys = intArrayOf(Input.Keys.Q), buttons = intArrayOf(2)),
        ),
        axes = listOf(
            Axis2DBinding(
                name = MOVE,
                negativeX = Input.Keys.A,
                positiveX = Input.Keys.D,
                negativeY = Input.Keys.S,
                positiveY = Input.Keys.W,
                // The left stick on every pad LibGDX has ever mapped. Inert today: nothing
                // implements `GamepadState` against hardware, and that interface says so in as
                // many words rather than leaving it to be discovered from a dead controller.
                gamepadAxisX = 0,
                gamepadAxisY = 1,
            ),
        ),
    )

    /** The move axis's id in [BINDINGS]'s catalog. Resolved once; it is an array index. */
    public val MOVE_AXIS: dev.wildware.udea.render.input.AxisId = BINDINGS.catalog.axis(MOVE)

    /** [ATTACK]'s id. */
    public val ATTACK_ACTION: dev.wildware.udea.render.input.ActionId =
        BINDINGS.catalog.action(ATTACK)

    /** [ATTACK_2]'s id. */
    public val ATTACK_2_ACTION: dev.wildware.udea.render.input.ActionId =
        BINDINGS.catalog.action(ATTACK_2)
}
