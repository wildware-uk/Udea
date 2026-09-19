package dev.wildware.udea.editor.gl

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.editor.EditorSession
import dev.wildware.udea.editor.EditorSpawn
import dev.wildware.udea.editor.EditorTools
import dev.wildware.udea.editor.EditorViews
import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.ScreenTarget
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.kool.KoolPointer
import org.lwjgl.glfw.GLFW
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue

/**
 * What the editor's GL tests share: an editor session over two views, a frame counter, and the mouse
 * driven through Kool's own GLFW callbacks, as in `udea-render`'s `GlKoolPointerTest`.
 */

/** A paused editor session at tick 0 over [views]. */
internal fun glEditorSession(views: EditorViews): EditorSession = EditorSession(
    tools = EditorTools(AgentBridge(), AgentSessions().intern("editor")),
    tick = { Tick(0) },
    paused = { true },
    spawn = EditorSpawn("Spawn", BlueprintId("skeleton"), 0f, 0f),
    views = views,
)

/** Counts frames: an overlay, so it runs once in every frame the pipeline draws. */
internal class FrameProbe : OverlaySystem {
    val count: AtomicInteger = AtomicInteger()

    override fun render(target: ScreenTarget, dtSeconds: Float) {
        count.incrementAndGet()
    }
}

/** Waits until [probe] has counted [target] frames, and fails if the render thread stops drawing. */
internal fun awaitFrames(probe: FrameProbe, target: Int) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while (probe.count.get() < target && System.nanoTime() < deadline) Thread.onSpinWait()
    assertTrue(probe.count.get() >= target, "the render thread stopped drawing")
}

/** Moves to [at], presses and releases the left button, and waits for Kool and the interface. */
internal fun KoolBackend.click(frames: FrameProbe, pointer: KoolPointer, at: Offset) {
    onRenderThread { pointer.endSample() }
    moveTo(at.x.toDouble(), at.y.toDouble())
    awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
    button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS)
    awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
    button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE)
    awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
}

/**
 * Moves the mouse on Kool's own GLFW cursor callback, twice, as `GlKoolPointerTest` does and for its
 * reason: Kool holds back the move that starts a drag.
 */
private fun KoolBackend.moveTo(x: Double, y: Double) = onRenderThread {
    val window = currentWindow()
    val callback = checkNotNull(GLFW.glfwSetCursorPosCallback(window, null)) {
        "Kool installed no GLFW cursor callback, so this test would be moving nothing"
    }
    try {
        callback.invoke(window, x, y)
        callback.invoke(window, x, y)
    } finally {
        GLFW.glfwSetCursorPosCallback(window, callback)
    }
}

/** Presses or releases a mouse button on Kool's own GLFW callback. */
private fun KoolBackend.button(button: Int, action: Int) = onRenderThread {
    val window = currentWindow()
    val callback = checkNotNull(GLFW.glfwSetMouseButtonCallback(window, null)) {
        "Kool installed no GLFW mouse button callback, so this test would be clicking nothing"
    }
    try {
        callback.invoke(window, button, action, NO_MODIFIERS)
    } finally {
        GLFW.glfwSetMouseButtonCallback(window, callback)
    }
}

private fun currentWindow(): Long {
    val window = GLFW.glfwGetCurrentContext()
    check(window != 0L) { "no GLFW window is current on the render thread" }
    return window
}

/** GLFW passes modifiers with a mouse button, and Kool's mapping reads none. */
private const val NO_MODIFIERS = 0

/** Frames from a GLFW event to the interface's verdict on it: see `GlKoolPointerTest`. */
internal const val SETTLE_FRAMES: Int = 4
