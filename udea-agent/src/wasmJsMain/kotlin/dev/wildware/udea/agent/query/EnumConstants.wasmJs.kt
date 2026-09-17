package dev.wildware.udea.agent.query

/** Kotlin/Wasm has no reflection to list an enum's constants with; see the `expect`. */
internal actual fun enumConstantsOf(value: Enum<*>): List<Enum<*>>? = null
