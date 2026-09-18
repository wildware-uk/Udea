package dev.wildware.moba

import dev.wildware.udea.render.input.InputBindings

/**
 * What this game binds, and the only place a key code appears in it.
 *
 * ## The bindings are authored, and this is the names they are addressed by
 *
 * `moba/game/assets/control/controls.udea.kts` declares `attack`, `attack_2` and the `move` axis over
 * WASD, it is packed into the `.udeapak` like every other asset, and [MobaControlAssets] reads it
 * back. Rebinding a key is an asset edit with no Kotlin recompiled.
 *
 * This file used to hard-code the same six bindings, under a KDoc claiming `control`, `binding`
 * and `axis2DBinding` "are not among the kinds `udeaPackBundle` publishes yet". That was never
 * true: those four kinds are `AssetKind.of<...>()` in `AssetScope` and `AssetCodecs` has always
 * carried readers and writers for them. What was missing was a copy of the script in the packed
 * root and a loader. Both exist now; see [MobaControlAssets] for the one thing the asset model
 * still cannot express, which is the gamepad half of a binding.
 *
 * `character`, `gameplayEffect` and `effect` were the kinds that really did keep a second asset
 * root alive. They are published too, the roots are one, and `moba/src/main/assets` is gone.
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

    /** Primary attack. Space, or the south face button. Fires [PlayerControlSystem.SLOT_PRIMARY]. */
    public const val ATTACK: String = "moba/attack"

    /**
     * Secondary attack. Q, or the west face button. Fires [PlayerControlSystem.SLOT_SECONDARY].
     *
     * This was declared, packed, resolved into [ATTACK_2_ACTION] and **read by nothing** until
     * [PlayerControlSystem] was given a second slot to point it at. A bound control that no system
     * asks about is indistinguishable, from the player's side of the window, from a key that is
     * not bound at all - so what proves this is wired is `MobaHudTest`, over the activation and
     * the cooldown it produces, rather than anything about the presence of the binding.
     */
    public const val ATTACK_2: String = "moba/attack_2"

    /**
     * The first item active. E, and it fires `UnitBlueprint.ITEM_SLOT_FIRST`.
     *
     * An item's active is granted into a slot on the same ability bar a champion's own two sit on
     * (issue #166), so casting one is a key press through the same `IntentSource` seam as
     * [ATTACK] - not a second input path, and not a shop click.
     */
    public const val ITEM_1: String = "moba/item_1"

    /** The second item active. R, and it fires the slot above [ITEM_1]. */
    public const val ITEM_2: String = "moba/item_2"

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

    /** [ITEM_1]'s id. */
    public val ITEM_1_ACTION: dev.wildware.udea.render.input.ActionId =
        BINDINGS.catalog.action(ITEM_1)

    /** [ITEM_2]'s id. */
    public val ITEM_2_ACTION: dev.wildware.udea.render.input.ActionId =
        BINDINGS.catalog.action(ITEM_2)

    /**
     * The action that fires ability [slot], in slot order.
     *
     * A list and not a `when`, because the HUD wants the same mapping to label a box and
     * `PlayerControlSystem` wants it to read a press. Its length is
     * `UnitBlueprint.ABILITY_SLOTS`, and `MobaHudTest` is what says so - a slot with no action
     * would be a box a player presses nothing to fire.
     */
    public val SLOT_ACTIONS: List<dev.wildware.udea.render.input.ActionId> =
        listOf(ATTACK_ACTION, ATTACK_2_ACTION, ITEM_1_ACTION, ITEM_2_ACTION)

    /**
     * The key codes this game binds, written out as the characters they are.
     *
     * ## Why these numbers changed with the renderer, and why that was nearly silent
     *
     * They were `com.badlogic.gdx.Input.Keys` values, and LibGDX's numbering is its own: gdx's
     * `W` is 51. Kool's desktop backend puts the raw GLFW key in the event for any key outside
     * its special-key map, and `GLFW_KEY_3` is 51 - so every one of these bindings would have
     * gone on compiling, gone on validating, and started answering to a different key. Nothing
     * in the build catches a binding that is merely *wrong*, and a wrong binding does not show
     * up in a screenshot, which is why `MobaKeyBindingTest` drives all eight of them through the
     * real `IntentSource` seam and asserts what each one produces.
     *
     * ## Why `'W'.code` and not `87`
     *
     * GLFW's letter keys are ASCII uppercase and its space is 32, so every code here *is* the
     * character. Written as the character a reader checks the table by looking at it; written as
     * an integer a reader has to trust it - and trusting it is how gdx's numbers came to be here
     * in the first place. `moba/game/assets/control/controls.udea.kts` writes the same eight the
     * same way for its own reason (an asset script compiles against the asset model alone and
     * must not drag a renderer in), and `MobaFieldTest` compares the two sides.
     *
     * ## These are the *backend's* numbers, and that is a gap rather than a design
     *
     * A key code is a renderer's vocabulary, not a game's. `InputBindings.keys` in `udea-render`
     * still documents these as gdx codes, which is false since #211, and nothing in the engine
     * offers a table to bind against - so each game writes the codes of whatever backend it
     * happens to run on. Swap the desktop backend, or add one whose codes differ, and this
     * object is wrong again in exactly the same silent way. Closing it is a `udea-render`
     * change, that module belongs to issue #224 this wave, and issue #212 is not the ticket to
     * make it in; when an engine-owned table exists this object becomes an alias for it.
     */
    public object Keys {

        public val Q: Int = 'Q'.code
        public val W: Int = 'W'.code
        public val A: Int = 'A'.code
        public val S: Int = 'S'.code
        public val D: Int = 'D'.code
        public val E: Int = 'E'.code
        public val R: Int = 'R'.code
        public val SPACE: Int = ' '.code
    }

    /**
     * What the HUD prints on the box for [code].
     *
     * `Input.Keys.toString` was this, and it is gone with LibGDX. A `when` over the keys this game
     * actually binds rather than a table of a hundred and thirty: a code outside the set prints its
     * number, which is legible, honest and impossible to mistake for a letter - and the set is
     * pinned by `MobaFieldTest`, so a binding added without a name here shows up as a digit on a
     * box in the very next capture.
     */
    public fun keyName(code: Int): String = when (code) {
        Keys.Q -> "Q"
        Keys.W -> "W"
        Keys.A -> "A"
        Keys.S -> "S"
        Keys.D -> "D"
        Keys.E -> "E"
        Keys.R -> "R"
        Keys.SPACE -> "SPACE"
        else -> code.toString()
    }
}
