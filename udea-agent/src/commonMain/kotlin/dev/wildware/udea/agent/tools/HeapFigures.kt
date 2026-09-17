package dev.wildware.udea.agent.tools

/** The heap as the runtime reports it: what `diag.memory` publishes. */
internal class HeapFigures(
    val usedBytes: Long,
    val committedBytes: Long,
    val maxBytes: Long,
    val processors: Int,
)

/**
 * This process's heap figures, or `null` where the runtime reports none.
 *
 * The JVM and Android answer through `Runtime`. Kotlin/Wasm has no API for its own heap, so it
 * answers `null` and `diag.memory` refuses with a typed error rather than publishing zeros an
 * agent would read as a measurement (issue #208).
 */
internal expect fun heapFigures(): HeapFigures?
