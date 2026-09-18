package dev.wildware.udea.render.input

/**
 * One action and the physical inputs that trigger it.
 *
 * The asset half of this is `control(...)` plus `binding(...)` in a `.udea.kts`
 * (`moba/assets/control/controls.udea.kts`). That corpus is packed now - `control`
 * is not among the kinds `udeaPackBundle` publishes - so bindings are declared in code today and
 * this class is deliberately shaped like the asset: a name, a list of keys, a list of buttons,
 * and **no method that reads a device**. `Binding.BindingInput.pressed()` in the old tree called
 * `Gdx.input` from inside an asset type, which is what put a device read on the asset graph; the
 * asset describes the binding and only [DeviceIntent] reads hardware.
 */
public class ActionBinding(
    /** Namespaced, e.g. `moba/attack`. See [InputCatalog] for why the namespace matters. */
    public val name: String,
    /** `com.badlogic.gdx.Input.Keys` codes, any of which triggers it. */
    public val keys: IntArray = IntArray(0),
    /** Gamepad button indices, any of which triggers it. */
    public val buttons: IntArray = IntArray(0),
) {
    override fun toString(): String = "ActionBinding($name, ${keys.size} key(s))"
}

/**
 * One 2D axis: a composite of four keys, plus a gamepad stick.
 *
 * ## What it ports, and the one thing it changes
 *
 * `ControllerSystem` accumulated a `Vector2` per axis by adding each pressed binding's direction
 * and then calling `nor()` unconditionally (`:32`). The accumulation is kept. The unconditional
 * normalise is not, and the difference is a real bug rather than a tidy-up: `nor()` on a partly
 * deflected gamepad stick snaps every deflection to full speed, so a pad could not walk. Here the
 * vector is clamped to length 1 instead - a diagonal on the keyboard still comes out at exactly
 * 1, and a half-pushed stick still comes out at a half.
 *
 * ## The deadzone is radial
 *
 * Per axis would let a stick pushed to `(0.2, 0.2)` - well inside any reasonable dead area -
 * read as zero on each axis separately but produce drift the moment one of them crossed. The
 * magnitude is tested against [deadzone] and the whole vector is zeroed or rescaled together, so
 * "inside the deadzone reads exactly zero" is true of the vector and not only of its components.
 */
public class Axis2DBinding(
    /** Namespaced, e.g. `moba/move`. */
    public val name: String,
    /** Key driving -x. */
    public val negativeX: Int = UNBOUND,
    /** Key driving +x. */
    public val positiveX: Int = UNBOUND,
    /** Key driving -y. */
    public val negativeY: Int = UNBOUND,
    /** Key driving +y. */
    public val positiveY: Int = UNBOUND,
    /** Gamepad axis index for x, or [UNBOUND]. */
    public val gamepadAxisX: Int = UNBOUND,
    /** Gamepad axis index for y, or [UNBOUND]. */
    public val gamepadAxisY: Int = UNBOUND,
    /**
     * Pads report "up" as negative on the y stick; world space calls up positive. `true` flips
     * the raw value so a game never has to remember which convention it is reading.
     */
    public val invertGamepadY: Boolean = true,
    /** Stick magnitude below which the axis reads exactly zero. */
    public val deadzone: Float = DEFAULT_DEADZONE,
) {
    init {
        require(deadzone >= 0f && deadzone < 1f) {
            "a deadzone is a magnitude in [0, 1), was $deadzone"
        }
    }

    override fun toString(): String = "Axis2DBinding($name, deadzone=$deadzone)"

    public companion object {
        /** "No key or axis here." Not `0`, which is a real keycode (`Keys.ANY_KEY`). */
        public const val UNBOUND: Int = -1

        /** A quarter deflection. Wide enough for a worn stick, narrow enough to feel direct. */
        public const val DEFAULT_DEADZONE: Float = 0.25f
    }
}

/**
 * Every action and axis a game binds, and the [InputCatalog] their ids come from.
 *
 * Built once at start-up and then read-only: the catalog assigns ids from the sorted names, and
 * the two arrays here are indexed *by that id* rather than by declaration order, so
 * [DeviceIntent] can walk them with a plain `for` over an int range and no lookup.
 */
public class InputBindings(
    actions: List<ActionBinding>,
    axes: List<Axis2DBinding>,
) {

    /** Ids for every name declared here. */
    public val catalog: InputCatalog =
        InputCatalog.of(actions.map { it.name }, axes.map { it.name })

    /** Action bindings, indexed by [ActionId.value]. */
    internal val actionsById: Array<ActionBinding> =
        Array(actions.size) { index -> actions.first { it.name == catalog.actions[index] } }

    /** Axis bindings, indexed by [AxisId.value]. */
    internal val axesById: Array<Axis2DBinding> =
        Array(axes.size) { index -> axes.first { it.name == catalog.axes[index] } }

    /** The binding behind [id]. */
    public fun binding(id: ActionId): ActionBinding = actionsById[id.value]

    /** The binding behind [id]. */
    public fun binding(id: AxisId): Axis2DBinding = axesById[id.value]

    override fun toString(): String =
        "InputBindings(${actionsById.size} action(s), ${axesById.size} axis/axes)"
}
