package dev.wildware.udea.core.loop

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.sched_yield
import platform.posix.usleep
import kotlin.native.concurrent.ThreadLocal

/**
 * One instance per thread: `@ThreadLocal` gives every thread its own copy of the object, so its
 * identity is the thread's.
 *
 * Compiled with the iOS targets, which are off until issue #215 is fixed. The source is here so
 * that switching them back on is the one-line change `udea-core/build.gradle.kts` describes.
 */
@ThreadLocal
private object ThisThread

internal actual fun currentThreadToken(): Any = ThisThread

@OptIn(ExperimentalForeignApi::class)
internal actual fun yieldToTickingThread() {
    sched_yield()
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun parkWhilePaused(nanos: Long) {
    usleep((nanos / NANOS_PER_MICRO).toUInt())
}

private const val NANOS_PER_MICRO: Long = 1_000L
