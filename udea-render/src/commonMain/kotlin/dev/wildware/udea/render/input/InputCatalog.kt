package dev.wildware.udea.render.input

/**
 * A stable, build-order-independent id for one action ("attack", "interact").
 *
 * A value class over `Int` so an [Intent] is four primitive arrays rather than a map: the
 * simulation reads intent on every tick for every controlled entity, and a `HashMap<String, ...>`
 * lookup there is the allocation-and-hashing shape the standards forbid on a per-tick path.
 */
@JvmInline
public value class ActionId(public val value: Int) {
    override fun toString(): String = "ActionId($value)"
}

/** A stable id for one 2D axis ("move", "aim"). See [ActionId] for why it is an int. */
@JvmInline
public value class AxisId(public val value: Int) {
    override fun toString(): String = "AxisId($value)"
}

/**
 * The names of every action and axis a game has, and the ints they are addressed by.
 *
 * ## Why ids are assigned from a sorted name list
 *
 * The old engine minted them from a mutable static counter: `Control.controlId = ControlId++`
 * (`common/.../assets/controls.kt:11`) and `Axis2D.id = nextId++` (`:58`), both incremented as
 * asset scripts were *evaluated*. So the int identifying "attack" depended on the order the
 * script host happened to compile files in, and two processes that disagreed about that order
 * disagreed about which control an input referred to - silently, because both were internally
 * consistent. That is the identical failure mode `net-components.lock` exists to stop on the
 * wire, and it is worse here because nothing hashed the assignment.
 *
 * Here the list is **sorted by name and then indexed**. Feed the same set of names in any order
 * and every id is the same int, which is what makes a recorded input stream replayable by a
 * process that loaded its assets in a different order (spec 6, Phase 7). `InputCatalogTest`
 * shuffles the input list and asserts the assignment does not move.
 *
 * Names are namespaced by convention (`moba/move`, not `move`), because the sort is over the
 * whole game and two modules that both call an axis `move` would otherwise collide - and
 * "collide" here means one of them silently addressing the other's axis.
 */
public class InputCatalog private constructor(
    /** Action names, sorted. The index is the [ActionId]. */
    public val actions: List<String>,
    /** Axis names, sorted. The index is the [AxisId]. */
    public val axes: List<String>,
) {

    /** How many actions exist. Sizes an [Intent]'s arrays. */
    public val actionCount: Int get() = actions.size

    /** How many axes exist. */
    public val axisCount: Int get() = axes.size

    /**
     * The id of [name].
     *
     * @throws IllegalArgumentException when nothing declares it. Loud, because the alternative -
     *   an id of `-1` that reads as "never pressed" - is a control that silently does nothing
     *   and looks exactly like a game-logic bug.
     */
    public fun action(name: String): ActionId {
        val index = actions.binarySearch(name)
        require(index >= 0) {
            "no action named '$name'; this game declares ${actions.joinToString()}"
        }
        return ActionId(index)
    }

    /** The id of axis [name]. @throws IllegalArgumentException when nothing declares it. */
    public fun axis(name: String): AxisId {
        val index = axes.binarySearch(name)
        require(index >= 0) {
            "no axis named '$name'; this game declares ${axes.joinToString()}"
        }
        return AxisId(index)
    }

    /** The name [id] was assigned to, for diagnostics and for the agent's `input.state`. */
    public fun nameOf(id: ActionId): String = actions[id.value]

    /** The name [id] was assigned to. */
    public fun nameOf(id: AxisId): String = axes[id.value]

    override fun toString(): String =
        "InputCatalog(actions=${actions.size}, axes=${axes.size})"

    public companion object {

        /**
         * A catalog over these names, ids assigned by sorted position.
         *
         * @throws IllegalArgumentException on a duplicate name. Two declarations of one name
         *   would leave one of them unaddressable, which is the same silent-nothing failure
         *   [action] refuses.
         */
        public fun of(actions: Collection<String>, axes: Collection<String>): InputCatalog {
            return InputCatalog(sortedDistinct(actions, "action"), sortedDistinct(axes, "axis"))
        }

        private fun sortedDistinct(names: Collection<String>, kind: String): List<String> {
            names.forEach { name ->
                require(name.isNotBlank()) { "an $kind name must not be blank" }
            }
            val sorted = names.sorted()
            for (at in 1 until sorted.size) {
                require(sorted[at] != sorted[at - 1]) {
                    "'${sorted[at]}' is declared twice as an $kind; one of the two would be " +
                        "unaddressable, so this is refused rather than deduplicated"
                }
            }
            return sorted
        }
    }
}
