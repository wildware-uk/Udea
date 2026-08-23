package dev.wildware.udea.assets

/**
 * A named digital input the game reacts to: `jump`, `primary_attack`.
 *
 * The old `Control` handed itself an id from a mutable companion counter (`val controlId =
 * ControlId++`, `common/.../controls.kt`), so a control's identity depended on the order the
 * script host happened to evaluate files in, and reloading one file renumbered the rest. Identity
 * is the [AssetId] the author wrote, and the runtime integer is the pack-time [AssetIndex], which
 * is stable by construction.
 */
public data class Control(override val id: AssetId) : AssetData

/**
 * A named two-axis input: `move`, `look`.
 *
 * Same story as [Control] - the old `Axis2D` had `val id: Int = nextId++`.
 */
public data class Axis2D(override val id: AssetId) : AssetData

/**
 * A physical input, as data.
 *
 * The old `BindingInput` declared `fun pressed(): Boolean` and implemented it as
 * `Gdx.input.isKeyPressed(key)` (`common/.../controls.kt`), so an *asset* read the live input
 * device. That is why the old asset tree could not exist without LibGDX, and it is behaviour on a
 * type whose job is to say which button was meant. Here it says which button was meant; the input
 * system in `udea-core` reads devices.
 *
 * Key and button codes are ints because that is what every backend speaks. They are validated at
 * build time against the backend's code table, which is a validator rule, not a range check.
 */
public sealed interface BindingInput {

    /** A keyboard key, by backend key code. */
    public data class Key(public val code: Int) : BindingInput

    /** A mouse button, by backend button code. */
    public data class MouseButton(public val code: Int) : BindingInput
}

/** Binds a physical input to a [Control]. */
public data class Binding(
    override val id: AssetId,
    public val control: Ref<Control>,
    public val input: BindingInput,
) : AssetData

/**
 * Binds a physical input to one direction of an [Axis2D].
 *
 * [direction] is what pressing it contributes: `Vec2(-1, 0)` for the key that walks left. Four of
 * these make a WASD stick.
 */
public data class Axis2DBinding(
    override val id: AssetId,
    public val axis: Ref<Axis2D>,
    public val input: BindingInput,
    public val direction: Vec2,
) : AssetData {

    init {
        require(direction != Vec2(0F, 0F)) {
            "axis binding '$id' contributes no direction, so pressing it would do nothing"
        }
    }
}
