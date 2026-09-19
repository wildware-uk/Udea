package dev.wildware.udea.render.gl

import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.ScreenTarget
import dev.wildware.udea.render.backend.KoolBackend
import org.lwjgl.glfw.GLFW
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertTrue

/*
 * A real keyboard, for the GL tests that need one: `GlKoolInputTest` and `GlKoolKeyTableTest`.
 * Shared rather than copied, because the one thing these helpers must get right - going in through
 * Kool's own GLFW callback, so Kool and not the test decides the key code - is the thing a copy
 * would drift on.
 */

/** Counts frames and remembers the thread one ran on. */
internal class FrameProbe : OverlaySystem {

    val count: AtomicInteger = AtomicInteger()

    val thread: AtomicReference<Thread?> = AtomicReference(null)

    override fun render(target: ScreenTarget, dtSeconds: Float) {
        thread.compareAndSet(null, Thread.currentThread())
        count.incrementAndGet()
    }
}

/** Waits until [probe] has counted [target] frames, and fails if the render thread stops. */
internal fun awaitFrames(probe: FrameProbe, target: Int) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while (probe.count.get() < target && System.nanoTime() < deadline) Thread.onSpinWait()
    assertTrue(probe.count.get() >= target, "the render thread stopped drawing")
}

/**
 * Presses [glfwKey] on Kool's own GLFW key callback, as a keyboard would.
 *
 * The point of going this far round rather than calling `KeyboardInput.handleKeyEvent` with an
 * event the test built: an event the test builds carries whatever code the test chose, so a
 * binding on the same code passes whatever the number is, and the *key table* - the thing a game's
 * bindings are resolved through - would never be measured at all. Here the raw GLFW key goes in and
 * Kool decides the code, in `GlfwInput`'s `KEY_CODE_MAP[key] ?: UniversalKeyCode(key)`.
 *
 * GLFW has no getter for a callback, only a setter that returns the previous one, so it is taken
 * off and put straight back. Render thread only: it is the thread that owns the window, and GLFW
 * requires its own.
 */
internal fun KoolBackend.press(glfwKey: Int) = key(glfwKey, GLFW.GLFW_PRESS)

/** Releases [glfwKey] on Kool's own GLFW key callback. See [press]. */
internal fun KoolBackend.release(glfwKey: Int) = key(glfwKey, GLFW.GLFW_RELEASE)

private fun KoolBackend.key(glfwKey: Int, action: Int) = onRenderThread {
    val window = GLFW.glfwGetCurrentContext()
    check(window != 0L) { "no GLFW window is current on the render thread" }
    invokeKeyCallback(window, glfwKey, action)
}

/** Kool's installed key callback, taken off, called once and put straight back. Render thread. */
internal fun invokeKeyCallback(window: Long, glfwKey: Int, action: Int) {
    val callback = checkNotNull(GLFW.glfwSetKeyCallback(window, null)) {
        "Kool installed no GLFW key callback, so this test would be driving nothing"
    }
    try {
        callback.invoke(window, glfwKey, NO_SCANCODE, action, NO_MODIFIERS)
    } finally {
        GLFW.glfwSetKeyCallback(window, callback)
    }
}

/** No scancode and no modifiers: GLFW passes both, and Kool's mapping reads neither. */
private const val NO_SCANCODE = 0
private const val NO_MODIFIERS = 0
