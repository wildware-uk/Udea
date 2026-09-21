package dev.wildware.udea.assets.compiler.gen

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * A glTF `extras` object as the plain values a game reads through `ModelExtras` (issue #271).
 *
 * `extras` is where a modelling tool writes what the artist attached to an object - Blender's
 * Custom Properties, with the exporter's "Custom Properties" box ticked - and glTF lets it hold
 * any JSON at all. What comes out of here is what a game can use without a JSON library, as the
 * Kotlin values the asset build hands on (they cross the worker boundary, and `GraphPacker`
 * packs them):
 *
 * - a string is a `String`, and `true` or `false` a `Boolean`;
 * - a number written with no point and no exponent that fits an `Int` is an `Int`, and any other
 *   finite number a `Float` - Blender writes an Int property as `3` and a Float one as `3.0`, so
 *   the artist's choice survives;
 * - an array of numbers is a `List<Float>`: a vector or a colour;
 * - an object - a Custom Property group - is read into dotted keys, `fitting.slots`, as deep as
 *   it goes.
 *
 * Everything else is left out, and the rest of the object kept: an array holding anything but
 * numbers, a `null`, a number too large for a `Float`, and `extras` that is not an object at all.
 * None of them is a value `ModelExtras` could hand a game, and refusing the whole model over one
 * property the game may never ask for would fail a build over the artist's tool rather than the
 * game. If a dotted key and a group spell the same name, the first in the file is kept.
 *
 * The result is sorted by key, so two builds of one file emit the same generated source.
 */
internal object GltfExtras {

    /** [extras] as plain values by dotted key, sorted; empty for anything that is not an object. */
    fun read(extras: JsonElement?): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        (extras as? JsonObject)?.let { flatten("", it, out) }
        return out.toSortedMap()
    }

    private fun flatten(prefix: String, group: JsonObject, into: MutableMap<String, Any>) {
        for ((name, element) in group) {
            val key = prefix + name
            if (element is JsonObject) {
                flatten("$key$SEPARATOR", element, into)
                continue
            }
            val value = plain(element) ?: continue
            if (key !in into) into[key] = value
        }
    }

    /** [element] as a plain value, or `null` when it is none that a game can read. */
    private fun plain(element: JsonElement): Any? = when (element) {
        is JsonPrimitive -> scalar(element)
        is JsonArray -> numbers(element)
        else -> null
    }

    private fun scalar(primitive: JsonPrimitive): Any? {
        if (primitive.isString) return primitive.content
        primitive.booleanOrNull?.let { return it }
        return number(primitive)
    }

    /** A JSON number: an `Int` when written whole and in range, otherwise a finite `Float`. */
    private fun number(primitive: JsonPrimitive): Any? {
        if (primitive.isString) return null
        val text = primitive.content
        if (WHOLE.matches(text)) text.toIntOrNull()?.let { return it }
        return text.toDoubleOrNull()?.toFloat()?.takeIf { it.isFinite() }
    }

    /** An array of numbers as floats, or `null` when anything in it is not a finite number. */
    private fun numbers(array: JsonArray): List<Float>? = array.map { item ->
        val number = (item as? JsonPrimitive)?.let(::number) ?: return null
        (number as? Int)?.toFloat() ?: number as Float
    }

    /** What a group's name and its member's are joined with: `fitting.slots`. */
    private const val SEPARATOR = "."

    /** A number with no point and no exponent. */
    private val WHOLE = Regex("-?[0-9]+")
}
