package dev.wildware.udea.agent

/**
 * The JSON writer the whole agent surface renders through.
 *
 * ## Why it is written here rather than depended on
 *
 * `udea-agent` is compiled into every Udea game, so anything on its classpath is on the
 * game's. A serialiser is the wrong thing to put there: `kotlinx.serialization` brings a
 * compiler plugin and a runtime, Jackson brings reflection and a shaded tree of modules, and
 * neither earns its place to emit documents this shape. The reference implementation
 * (`FruitGameKTX/core/.../debug/Json.kt`) reached the same conclusion for the same reason and
 * this is its port.
 *
 * ## Reuse, and why the primitives are public
 *
 * One [Json] owns one [StringBuilder] and is meant to be [reset] and refilled, because the
 * Tier-0 digest is rebuilt on the simulation thread and its budget is measured in bytes
 * allocated (`DigestBudgets`). [obj], [arr] and [element] are `inline`, so a nested builder
 * allocates no lambda either - and that is only possible because [beginObject], [endObject]
 * and [key] are public. Writing unbalanced structure through them is a caller error that
 * [toString] refuses rather than publishes: an agent parsing truncated JSON reports a broken
 * game, which is the most expensive wrong answer this surface can give.
 *
 * ## Not thread-safe
 *
 * One instance belongs to one thread. The digest's instance belongs to the simulation
 * thread; a tool rendering its own result uses its own.
 */
public class Json(initialCapacity: Int = DEFAULT_CAPACITY) {

    private val out: StringBuilder = StringBuilder(initialCapacity)

    /** Open containers. Zero at the start and end of a complete document. */
    private var depth: Int = 0

    private var needsComma: Boolean = false

    /** Characters written so far. The digest's size budget is asserted against this. */
    public val length: Int get() = out.length

    /**
     * Empties the buffer, keeping its capacity, and returns this writer.
     *
     * The capacity is the point: after the first few documents the builder has reached its
     * high-water mark and no rebuild allocates again.
     */
    public fun reset(): Json {
        out.setLength(0)
        depth = 0
        needsComma = false
        return this
    }

    // --- structural primitives ---------------------------------------------------------

    /** Opens an object. Pair with [endObject]; prefer [obj] where the shape is static. */
    public fun beginObject() {
        separate()
        out.append('{')
        depth++
        needsComma = false
    }

    /** Closes the object opened by [beginObject]. */
    public fun endObject() {
        closeContainer('}')
    }

    /** Opens an array. Pair with [endArray]; prefer [arr] where the shape is static. */
    public fun beginArray() {
        separate()
        out.append('[')
        depth++
        needsComma = false
    }

    /** Closes the array opened by [beginArray]. */
    public fun endArray() {
        closeContainer(']')
    }

    /** Writes a member name and its colon. The next write supplies the value. */
    public fun key(name: String) {
        separate()
        quote(name)
        out.append(':')
        // A key and its value are one comma-separated member, so the value must not
        // separate itself.
        needsComma = false
    }

    // --- builders ----------------------------------------------------------------------

    /** The document root, or an anonymous object inside an array. */
    public inline fun obj(build: Json.() -> Unit): Json {
        beginObject()
        build()
        endObject()
        return this
    }

    /** A named object member. */
    public inline fun obj(name: String, build: Json.() -> Unit) {
        key(name)
        beginObject()
        build()
        endObject()
    }

    /** A named array member. */
    public inline fun arr(name: String, build: Json.() -> Unit) {
        key(name)
        beginArray()
        build()
        endArray()
    }

    /** An array element that is itself an object. */
    public inline fun element(build: Json.() -> Unit) {
        beginObject()
        build()
        endObject()
    }

    // --- scalars -----------------------------------------------------------------------

    /** A named string member. `null` is written as JSON `null`, never as `"null"`. */
    public fun put(name: String, value: String?) {
        key(name)
        value(value)
    }

    /** A named boolean member. */
    public fun put(name: String, value: Boolean) {
        key(name)
        value(value)
    }

    /** A named integer member. */
    public fun put(name: String, value: Int) {
        key(name)
        value(value)
    }

    /** A named long member. */
    public fun put(name: String, value: Long) {
        key(name)
        value(value)
    }

    /** A named float member, rounded by [value]. */
    public fun put(name: String, value: Float) {
        key(name)
        value(value)
    }

    /** An anonymous string, for an array element. */
    public fun value(value: String?) {
        separate()
        if (value == null) out.append("null") else quote(value)
    }

    /**
     * An anonymous string, truncated to [maxChars] characters.
     *
     * Truncation happens here rather than at the call site because `text.take(n)` allocates a
     * second string, and the caller doing it is on the digest's zero-allocation path. A
     * truncated value ends in a single `~`, so an agent can tell a clipped event from a short
     * one instead of silently reasoning about half a message.
     */
    public fun value(value: String, maxChars: Int) {
        require(maxChars > 0) { "maxChars must be positive, was $maxChars" }
        separate()
        if (value.length <= maxChars) quote(value) else quote(value, maxChars - 1, TRUNCATION_MARK)
    }

    /** An anonymous boolean, for an array element. */
    public fun value(value: Boolean) {
        separate()
        out.append(if (value) "true" else "false")
    }

    /** An anonymous integer, for an array element. */
    public fun value(value: Int) {
        separate()
        out.append(value)
    }

    /** An anonymous long, for an array element. */
    public fun value(value: Long) {
        separate()
        out.append(value)
    }

    /**
     * An anonymous float, rounded to [FLOAT_DECIMALS] decimal places.
     *
     * Rounding is a token saving before it is anything else: physics noise past four decimal
     * places costs the agent context window and tells it nothing. `NaN` and both infinities
     * are written as `null` because JSON has no spelling for them - emitting the Java one
     * produces a document no parser accepts, which is how a healthy game comes to look broken.
     *
     * The digits are written one at a time rather than through `String.format` or
     * `Double.toString`, for two reasons that happen to point the same way.
     *
     * `String.format("%.4f", …)` is **locale-sensitive**: under `Locale.GERMANY` it renders
     * `46,0`, and a decimal comma inside a comma-separated array does not fail - it parses as
     * *two* array elements, so the agent silently reads a different document from the one the
     * game meant. `JsonWriterTest` pins that with a locale swap.
     *
     * `Double.toString` is locale-safe but **allocates**, and this method is on the path
     * `DigestAllocationTest` gates at zero bytes: `timeScale`, `fps` and two network rates are
     * floats in every Tier-0 document. Appending digits to the existing builder allocates
     * nothing at all.
     *
     * The one fallback is magnitude: past [FLOAT_EXACT_LIMIT] the scaled value no longer fits
     * a `Long`, so the value goes through `Double.toString` (allocating, and in exponent form).
     * No coordinate, rate or scale in a game reaches it; a corrupted field might, and printing
     * it wrongly would be worse than printing it slowly.
     */
    public fun value(value: Float) {
        separate()
        if (value.isNaN() || value.isInfinite()) {
            out.append("null")
            return
        }
        if (value > FLOAT_EXACT_LIMIT || value < -FLOAT_EXACT_LIMIT) {
            out.append(value.toDouble().toString())
            return
        }
        // Ties round towards positive infinity, matching Math.round on a Double.
        val scaled = Math.round(value.toDouble() * FLOAT_SCALE)
        if (scaled == 0L) {
            // Covers -0.0f as well: JSON has one zero, and "-0" is noise an agent would have
            // to reason about.
            out.append('0')
            return
        }
        if (scaled < 0L) out.append('-')
        val magnitude = if (scaled < 0L) -scaled else scaled
        out.append(magnitude / FLOAT_SCALE_LONG)
        val fraction = (magnitude % FLOAT_SCALE_LONG).toInt()
        if (fraction != 0) appendFraction(fraction)
    }

    /**
     * The fractional part of a 4dp value: a dot, then its digits with trailing zeros dropped.
     *
     * `0.5f` is `5000` here and must print as `.5`, not `.5000` - four significant-looking
     * digits of nothing are four tokens of nothing.
     */
    private fun appendFraction(fraction: Int) {
        out.append('.')
        var significant = FLOAT_DECIMALS
        var trimmed = fraction
        while (trimmed % DECIMAL_RADIX == 0) {
            trimmed /= DECIMAL_RADIX
            significant--
        }
        var divisor = FLOAT_SCALE_INT / DECIMAL_RADIX
        var written = 0
        while (written < significant) {
            out.append('0' + (fraction / divisor) % DECIMAL_RADIX)
            divisor /= DECIMAL_RADIX
            written++
        }
    }

    /**
     * Splices an already-rendered JSON value in as-is.
     *
     * The one escape hatch, and it exists for exactly one reason: an [AgentResult.Ok] carries
     * its value as text a tool rendered, and re-parsing it here to write it out again would
     * mean shipping a parser - the thing this class exists to avoid. The obligation moves to
     * [AgentResult.Ok], whose only sanctioned constructor is [Json.render], so the string is
     * something this writer produced.
     */
    public fun raw(value: String) {
        separate()
        out.append(value)
    }

    /**
     * The rendered document.
     *
     * @throws IllegalStateException if a container is still open. Publishing truncated JSON
     *   would tell an agent the game is broken when only the writer's caller is.
     */
    override fun toString(): String {
        check(depth == 0) { "JSON document has $depth unclosed container(s)" }
        return out.toString()
    }

    private fun closeContainer(close: Char) {
        check(depth > 0) { "no open container to close with $close" }
        out.append(close)
        depth--
        needsComma = true
    }

    private fun separate() {
        if (needsComma) out.append(',')
        needsComma = true
    }

    private fun quote(text: String, limit: Int = Int.MAX_VALUE, suffix: Char? = null) {
        out.append('"')
        var index = 0
        val end = if (limit < text.length) limit else text.length
        // Index-based rather than `for (c in text)`: a CharSequence iterator is an allocation
        // per string on a path budgeted at zero bytes.
        while (index < end) {
            when (val char = text[index]) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (char < ' ') appendUnicodeEscape(char) else out.append(char)
            }
            index++
        }
        if (suffix != null) out.append(suffix)
        out.append('"')
    }

    /**
     * `\u00XX` for a control character, written digit by digit.
     *
     * `Integer.toHexString(...).padStart(4, '0')` would be shorter and would allocate two
     * strings per control character, on the thread whose allocation is gated.
     */
    private fun appendUnicodeEscape(char: Char) {
        val code = char.code
        out.append("\\u")
        var shift = HEX_DIGIT_BITS * (HEX_DIGITS - 1)
        while (shift >= 0) {
            out.append(HEX[(code ushr shift) and HEX_MASK])
            shift -= HEX_DIGIT_BITS
        }
    }

    public companion object {
        /**
         * Decimal places a `Float` keeps. Four is the reference implementation's number and
         * the reason is unchanged: it is the precision at which two positions an agent should
         * treat as different still are.
         */
        public const val FLOAT_DECIMALS: Int = 4

        private const val FLOAT_SCALE: Double = 10_000.0
        private const val FLOAT_SCALE_LONG: Long = 10_000L
        private const val FLOAT_SCALE_INT: Int = 10_000
        private const val DECIMAL_RADIX: Int = 10

        /**
         * Above this the 4dp-scaled value overflows a `Long`, so [value] falls back to
         * `Double.toString`. `Long.MAX_VALUE / 10 000`, rounded down to something readable.
         */
        private const val FLOAT_EXACT_LIMIT: Float = 9.0e14f

        /**
         * Starting buffer size. Large enough for a Tier-0 digest (~2KB) without a resize, so
         * the very first build on the simulation thread does not pay for a copy.
         */
        private const val DEFAULT_CAPACITY: Int = 4096

        /** The last character of a value that did not fit its cap. */
        public const val TRUNCATION_MARK: Char = '~'

        private val HEX: CharArray = "0123456789abcdef".toCharArray()
        private const val HEX_DIGITS: Int = 4
        private const val HEX_DIGIT_BITS: Int = 4
        private const val HEX_MASK: Int = 0xF

        /**
         * Renders one document with a private writer.
         *
         * For call sites that are not on a budgeted path - a tool rendering its own result, a
         * test - where a fresh writer is clearer than a reused one.
         */
        public inline fun render(build: Json.() -> Unit): String = Json().obj(build).toString()
    }
}
