package dev.wildware.udea.agent.tools

/**
 * `Runtime` and not a JMX bean: this is called from a tool, off any per-tick path, and these are
 * the numbers that answer "is this run about to die" without pulling in a management interface a
 * headless CI container may not have.
 */
internal actual fun heapFigures(): HeapFigures? {
    val runtime = Runtime.getRuntime()
    val total = runtime.totalMemory()
    val free = runtime.freeMemory()
    return HeapFigures(
        usedBytes = total - free,
        committedBytes = total,
        maxBytes = runtime.maxMemory(),
        processors = runtime.availableProcessors(),
    )
}
