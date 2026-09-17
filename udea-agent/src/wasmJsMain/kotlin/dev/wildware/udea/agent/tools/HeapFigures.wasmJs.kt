package dev.wildware.udea.agent.tools

/** Kotlin/Wasm exposes no figures for its own heap; see the `expect`. */
internal actual fun heapFigures(): HeapFigures? = null
