package dev.wildware.udea.core.loop

/** Wasm runs one thread, so every caller is that thread. */
private object TheThread

internal actual fun currentThreadToken(): Any = TheThread

internal actual fun yieldToTickingThread() {
    error(
        "a tick is in flight on another thread, but Wasm has only one thread; GameLoop's " +
            "tick-in-flight bookkeeping is corrupt",
    )
}

internal actual fun parkWhilePaused(nanos: Long) {
    error(
        "GameHost.run() is paused on Wasm, which has one thread: with the loop paused nothing runs " +
            "that could resume it, so it would never return. Drive a Wasm game with frame(wallDelta).",
    )
}
