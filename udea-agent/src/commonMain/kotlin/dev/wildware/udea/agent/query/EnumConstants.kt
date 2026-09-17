package dev.wildware.udea.agent.query

/**
 * Every constant of [value]'s enum class, in declaration order, or `null` where the platform
 * cannot list them.
 *
 * `set_component_field` writes an enum slot by constant *name*, and the only thing it holds is
 * the value already in the slot - `Replicator` carries field names, not types. Listing a class's
 * constants from one of its values is reflection, which the JVM and Android runtimes have and
 * Kotlin/Wasm does not. So on Wasm this answers `null`, and the write is refused as a
 * `bad_argument` naming the slot's type rather than guessed at (issue #208).
 */
internal expect fun enumConstantsOf(value: Enum<*>): List<Enum<*>>?
