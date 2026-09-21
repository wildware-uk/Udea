package dev.wildware.udea.assets

/**
 * The values an artist attached to a model, or to one node of it, in the modelling tool - in
 * Blender, an object's or the scene's Custom Properties - as a game reads them (issue #271).
 *
 * glTF carries these as `extras`, which may hold any JSON at all. A game is handed the part it
 * can use without a JSON library: numbers, text, flags and vectors of numbers, each read by name
 * with the type the game expects - `extras.float("mass")`, `extras.text("module_size")`. A
 * Custom Property group arrives as dotted keys, so `fitting.slots` is the `slots` inside the group
 * `fitting`. Anything else a file holds - a list of names, a list of lists, a null - is left out
 * by the asset build, because no read here could answer it.
 *
 * ## Three answers, each on purpose
 *
 * - The value, when the model has the key and it is of the type asked for. A whole number reads
 *   as a float too, and a float with nothing after the point reads as an int, because Blender
 *   writes an Int property as `3` and a Float one as `3.0` and a game should not have to know
 *   which box the artist ticked.
 * - `null`, when the model does not have the key. A game supplies its own default with `?:`.
 * - [ModelExtraTypeException], when it has the key and the value is something else. A `mass`
 *   typed as the text `"12"` is an authoring mistake, and reading it as "no mass" would hide it.
 *
 * A plain, immutable value: two sets of extras holding the same values are equal, so a
 * [ModelNode] read from a bundle equals the generated accessor for the same node.
 *
 * @param values each key's value: a [AssetValue.BoolValue], [AssetValue.IntValue],
 *   [AssetValue.FloatValue], [AssetValue.TextValue], or an [AssetValue.ListValue] of numbers.
 *   Anything else is refused, because none of the reads could return it.
 */
public class ModelExtras(values: Map<String, AssetValue>) {

    private val values: Map<String, AssetValue> = values.toMap()

    init {
        for ((key, value) in this.values) {
            require(isPlain(value)) {
                "extra '$key' is ${describe(value)}; a model's extras hold numbers, text, flags and lists of numbers"
            }
        }
    }

    /** Every key this model or node has, sorted. */
    public val keys: List<String> = this.values.keys.sorted()

    /** Whether the model has [key], of any type. */
    public operator fun contains(key: String): Boolean = key in values

    /** The number at [key], whole or not; `null` when there is no [key]. */
    public fun float(key: String): Float? = when (val value = values[key]) {
        null -> null
        is AssetValue.FloatValue -> value.value
        is AssetValue.IntValue -> value.value.toFloat()
        else -> throw ModelExtraTypeException(key, describe(value), NUMBER)
    }

    /** The whole number at [key], including a float with nothing after the point; `null` when there is no [key]. */
    public fun int(key: String): Int? = when (val value = values[key]) {
        null -> null
        is AssetValue.IntValue -> value.value
        is AssetValue.FloatValue -> value.value.takeIf { isWhole(it) }?.toInt()
            ?: throw ModelExtraTypeException(key, describe(value), WHOLE_NUMBER)
        else -> throw ModelExtraTypeException(key, describe(value), WHOLE_NUMBER)
    }

    /** The text at [key]; `null` when there is no [key]. */
    public fun text(key: String): String? = when (val value = values[key]) {
        null -> null
        is AssetValue.TextValue -> value.value
        else -> throw ModelExtraTypeException(key, describe(value), TEXT)
    }

    /** The flag at [key]; `null` when there is no [key]. */
    public fun bool(key: String): Boolean? = when (val value = values[key]) {
        null -> null
        is AssetValue.BoolValue -> value.value
        else -> throw ModelExtraTypeException(key, describe(value), FLAG)
    }

    /** The list of numbers at [key] - a vector or a colour, in Blender; `null` when there is no [key]. */
    public fun floats(key: String): List<Float>? = when (val value = values[key]) {
        null -> null
        // Every item is a number: `init` refused any other list.
        is AssetValue.ListValue -> value.values.mapNotNull(::numberOf)
        else -> throw ModelExtraTypeException(key, describe(value), NUMBERS)
    }

    override fun equals(other: Any?): Boolean = this === other || (other is ModelExtras && values == other.values)

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "ModelExtras($values)"

    public companion object {

        /** No extras: what a model or a node the artist wrote nothing on has. */
        public val EMPTY: ModelExtras = ModelExtras(emptyMap())

        private const val NUMBER = "a number"
        private const val WHOLE_NUMBER = "a whole number"
        private const val TEXT = "text"
        private const val FLAG = "a flag"
        private const val NUMBERS = "a list of numbers"

        /** The largest float every whole number up to is exactly representable: 2^24. */
        private const val EXACT_WHOLE_FLOAT = 16_777_216f

        private fun isWhole(value: Float): Boolean =
            value == value.toInt().toFloat() && value in -EXACT_WHOLE_FLOAT..EXACT_WHOLE_FLOAT

        private fun numberOf(value: AssetValue): Float? = when (value) {
            is AssetValue.FloatValue -> value.value
            is AssetValue.IntValue -> value.value.toFloat()
            else -> null
        }

        private fun isNumber(value: AssetValue): Boolean = numberOf(value) != null

        private fun isPlain(value: AssetValue): Boolean = when (value) {
            is AssetValue.BoolValue, is AssetValue.IntValue, is AssetValue.FloatValue, is AssetValue.TextValue -> true
            is AssetValue.ListValue -> value.values.all(::isNumber)
            else -> false
        }

        private fun describe(value: AssetValue): String = when (value) {
            is AssetValue.BoolValue -> "the flag ${value.value}"
            is AssetValue.IntValue -> "the number ${value.value}"
            is AssetValue.FloatValue -> "the number ${value.value}"
            is AssetValue.TextValue -> "the text \"${value.value}\""
            is AssetValue.ListValue -> "a list of ${value.values.size}"
            else -> "a ${value::class.simpleName}"
        }
    }
}
