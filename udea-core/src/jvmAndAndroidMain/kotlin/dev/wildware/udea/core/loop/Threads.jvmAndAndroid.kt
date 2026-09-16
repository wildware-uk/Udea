package dev.wildware.udea.core.loop

import java.util.concurrent.locks.LockSupport

internal actual fun currentThreadToken(): Any = Thread.currentThread()

/**
 * `Thread.yield()` rather than `Thread.onSpinWait()`, which Android has only from API 33 while
 * this module's floor is the catalog's `androidMinSdk`. Both are hints to the scheduler; a wait here
 * lasts at most the remainder of one tick.
 */
internal actual fun yieldToTickingThread() {
    Thread.yield()
}

internal actual fun parkWhilePaused(nanos: Long) {
    LockSupport.parkNanos(nanos)
}
