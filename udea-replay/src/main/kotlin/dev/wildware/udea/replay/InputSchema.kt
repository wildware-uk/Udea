package dev.wildware.udea.replay

/**
 * Which peer an input frame belongs to. A domain primitive, never a bare `Int`.
 *
 * Server-assigned and dense from zero, because a recording indexes its frame table by it:
 * `frames[tick * peerCount + peer]`. It is deliberately **not** a `NetId` - a `NetId` names an
 * entity, and the entity a peer drives changes when it dies, respawns or is given a second
 * champion, while the peer sending the input does not.
 */
@JvmInline
public value class PeerId(public val value: Int) {

    init {
        require(value >= 0) { "a PeerId is dense from zero, was $value" }
    }

    override fun toString(): String = "peer#$value"
}

/**
 * The vocabulary one recording's inputs are written in: what axes and what actions exist.
 *
 * ## Why the schema is in the header rather than assumed
 *
 * `Intent` in `udea-render` indexes actions and axes by position, and the positions come from
 * `InputCatalog`, which assigns ids **by sorted name across the whole game**. Bind one new key
 * and every id after it shifts. A recording that stored only the numbers would then replay a
 * player's `attack` as their `attack_2` with nothing to notice - the arrays are the same length
 * and every value is in range. So the names travel with the recording, [hash] is over them, and
 * a replay whose build numbers them differently is refused by name.
 *
 * ## It names no `Intent`, and that is a module rule rather than taste
 *
 * `Intent` lives in `udea-render`, which is the one module on the GL convention.
 * `ModuleGraphRules.HEADLESS_PROJECTS` includes `:udea-replay`, so this module cannot name a
 * `udea-render` type without moving itself into `GL_ALLOWED_PROJECTS` - which would be exactly
 * backwards for the module whose whole job is to run a match with no device attached. The game
 * owns the six lines that copy an `Intent` into an [InputSample] and back; see `moba`'s
 * `IntentReplay`.
 */
public class InputSchema(
    /** Axis names, in the order a sample's axis indices refer to. */
    public val axes: List<String>,
    /** Action names, in the order a sample's action indices refer to. */
    public val actions: List<String>,
) {

    init {
        require(axes.size <= ReplayFormat.MAX_NAMES) {
            "an input schema may carry at most ${ReplayFormat.MAX_NAMES} axes, got ${axes.size}"
        }
        require(actions.size <= ReplayFormat.MAX_NAMES) {
            "an input schema may carry at most ${ReplayFormat.MAX_NAMES} actions, " +
                "got ${actions.size}"
        }
        require(axes.toSet().size == axes.size) { "axis names must be unique: $axes" }
        require(actions.toSet().size == actions.size) { "action names must be unique: $actions" }
    }

    /** How many axes a sample of this schema carries. */
    public val axisCount: Int get() = axes.size

    /** How many actions a sample of this schema carries. */
    public val actionCount: Int get() = actions.size

    /** Bytes one sample's held-action bitset occupies. */
    public val heldByteCount: Int get() = (actionCount + 7) / 8

    /**
     * FNV-1a over every name, axes first, each length-prefixed.
     *
     * Length-prefixed rather than concatenated, or `["ab","c"]` and `["a","bc"]` would hash the
     * same - the classic length-extension mistake, which here would be a recording that replayed
     * a rebind as if nothing had happened.
     */
    public val hash: Long = run {
        var value = OFFSET_BASIS
        value = fold(value, axes.size.toLong())
        for (name in axes) value = foldString(value, name)
        value = fold(value, actions.size.toLong())
        for (name in actions) value = foldString(value, name)
        value
    }

    /** The index of [name] in [axes], or `-1`. Resolved once by a caller; never per tick. */
    public fun axisIndex(name: String): Int = axes.indexOf(name)

    /** The index of [name] in [actions], or `-1`. */
    public fun actionIndex(name: String): Int = actions.indexOf(name)

    override fun toString(): String =
        "InputSchema(${axes.size} axes, ${actions.size} actions, hash=$hash)"

    /** Two schemas are the same schema when they carry the same names in the same order. */
    override fun equals(other: Any?): Boolean =
        other is InputSchema && other.axes == axes && other.actions == actions

    override fun hashCode(): Int = hash.toInt() xor (hash ushr 32).toInt()

    public companion object {

        /** A schema with nothing in it: what a dedicated-server recording carries. */
        public val EMPTY: InputSchema = InputSchema(emptyList(), emptyList())

        private const val OFFSET_BASIS: Long = -0x340d631b7bdddcdbL
        private const val PRIME: Long = 0x100000001b3L

        private fun fold(hash: Long, value: Long): Long {
            var result = hash
            var remaining = value
            repeat(Long.SIZE_BYTES) {
                result = (result xor (remaining and 0xFFL)) * PRIME
                remaining = remaining ushr 8
            }
            return result
        }

        private fun foldString(hash: Long, value: String): Long {
            val bytes = value.encodeToByteArray()
            var result = fold(hash, bytes.size.toLong())
            for (byte in bytes) result = (result xor (byte.toLong() and 0xFFL)) * PRIME
            return result
        }
    }
}
