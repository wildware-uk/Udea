package dev.wildware.moba

import dev.wildware.udea.render.input.InputBindings

/**
 * What this game binds, and the only place a key code appears in it.
 *
 * ## The bindings are authored, and this is the names they are addressed by
 *
 * `moba/assets/control/controls.udea.kts` declares `attack`, `attack_2` and the `move` axis over
 * WASD, it is packed into the `.udeapak` like every other asset, and [MobaControlAssets] reads it
 * back. Rebinding a key is an asset edit with no Kotlin recompiled.
 *
 * This file used to hard-code the same six bindings, under a KDoc claiming `control`, `binding`
 * and `axis2DBinding` "are not among the kinds `udeaPackBundle` publishes yet". That was never
 * true: those four kinds are `AssetKind.of<...>()` in `AssetScope` - published, unlike the
 * `AssetKind.Unpublishable` `character`, `gameplayEffect` and `effect` that actually do keep
 * `src/main/assets` a separate root - and `AssetCodecs` has always carried readers and writers
 * for them. What was missing was a copy of the script in the packed root and a loader. Both
 * exist now; see [MobaControlAssets] for the one thing the asset model still cannot express,
 * which is the gamepad half of a binding.
 *
 * What stays in code is the three *names*, as constants, because they are what the rest of this
 * game addresses an action by - and a name typed at a call site is checked by the compiler where
 * a string is checked by nothing.
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
     * The bindings, read out of the packed graph once.
     *
     * An `object`'s `val` and not a function, because the [dev.wildware.udea.render.input.InputCatalog]
     * inside it is an identity that an `Intent`, an `InjectedIntent` and every `ActionId` in this
     * game are checked against. Two catalogs with the same names are still two catalogs, and
     * `Intent.copyFrom` refuses to cross them - which is the check that stops an agent's injected
     * intent being sampled into a simulation that numbered its actions differently. That argument
     * survives the move to the asset graph unchanged and is the reason this is not re-read per
     * frame: a catalog rebuilt mid-session would renumber ids the running simulation holds.
     */
    public val BINDINGS: InputBindings = MobaControlAssets.load()

    /** The move axis's id in [BINDINGS]'s catalog. Resolved once; it is an array index. */
    public val MOVE_AXIS: dev.wildware.udea.render.input.AxisId = BINDINGS.catalog.axis(MOVE)

    /** [ATTACK]'s id. */
    public val ATTACK_ACTION: dev.wildware.udea.render.input.ActionId =
        BINDINGS.catalog.action(ATTACK)

    /** [ATTACK_2]'s id. */
    public val ATTACK_2_ACTION: dev.wildware.udea.render.input.ActionId =
        BINDINGS.catalog.action(ATTACK_2)
}
