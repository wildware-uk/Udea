package dev.wildware.udea.agent.query

import dev.wildware.udea.agent.Json
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.identity.NetId

/**
 * How a boxed field value from `Replicator.getField` is compared and rendered.
 *
 * One place, because the query engine and `describe_entity` must agree: an agent that filters
 * on `target=131073` and then reads `"target":131073` back has a coherent picture, and one that
 * reads `"target":"NetId(#1@2)"` has to reverse-engineer a `toString`.
 *
 * ## Entity references render as their packed word
 *
 * A [NetId] is written as `raw`, the 32-bit index-plus-generation word, and never as its index
 * alone. That is the whole reason the generation exists: an id an agent stores and sends back
 * ten seconds later must be *detectably* stale rather than silently addressing whatever now
 * occupies the slot. Rendering the index alone would throw the generation away at the surface
 * where staleness is most likely.
 */
internal object FieldValues {

    /** [value] as a number, or `null` when it is not one. */
    fun numericOrNull(value: Any?): Double? = when (value) {
        is Float -> value.toDouble()
        is Double -> value
        is Int -> value.toDouble()
        is Long -> value.toDouble()
        is Short -> value.toDouble()
        is Byte -> value.toDouble()
        is NetId -> value.raw.toDouble()
        is Tick -> value.value.toDouble()
        else -> null
    }

    /** [value] as the text an `=` comparison uses. */
    fun textOf(value: Any?): String = when (value) {
        null -> "null"
        is String -> value
        is Boolean -> if (value) "true" else "false"
        is NetId -> value.raw.toString()
        is Tick -> value.value.toString()
        is Enum<*> -> value.name
        else -> value.toString()
    }

    /** Writes [value] as a JSON value, using the narrowest honest representation. */
    fun renderInto(json: Json, value: Any?) {
        when (value) {
            null -> json.value(null as String?)
            is Float -> json.value(value)
            is Double -> json.value(value.toFloat())
            is Int -> json.value(value)
            is Long -> json.value(value)
            is Short -> json.value(value.toInt())
            is Byte -> json.value(value.toInt())
            is Boolean -> json.value(value)
            is String -> json.value(value)
            is NetId -> json.value(value.raw)
            is Tick -> json.value(value.value)
            is Enum<*> -> json.value(value.name)
            // A reference-typed field. `FieldKind.Object` requires a stable, value-based
            // toString-able type, so this is a readable answer rather than an address.
            else -> json.value(value.toString())
        }
    }
}
