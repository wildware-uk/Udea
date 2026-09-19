package dev.wildware.udea.editor.gl

import dev.wildware.composegl.ui.geometry.Offset
import dev.wildware.composegl.ui.geometry.Rect
import dev.wildware.composegl.ui.geometry.Size
import dev.wildware.composegl.ui.testing.uiTest
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.editor.EditorSession
import dev.wildware.udea.editor.EditorSpawn
import dev.wildware.udea.editor.EditorTags
import dev.wildware.udea.editor.EditorTools
import dev.wildware.udea.editor.EditorViews
import dev.wildware.udea.render.OverlaySystem
import dev.wildware.udea.render.ScreenTarget
import dev.wildware.udea.render.backend.KoolBackend
import dev.wildware.udea.render.kool.KoolPointer
import dev.wildware.udea.render.view.ViewPoint
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
internal fun KoolBackend.moveTo(x: Double, y: Double) = onRenderThread {
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
internal fun KoolBackend.button(button: Int, action: Int) = onRenderThread {
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

/**
 * Presses the left button at [from], drags it to [to], runs [during] with the button held, and lets
 * go - unless [release] is false, when the button is left down for the caller.
 */
internal fun KoolBackend.drag(
    frames: FrameProbe,
    from: Offset,
    to: Offset,
    release: Boolean = true,
    during: () -> Unit = {},
) {
    moveTo(from.x.toDouble(), from.y.toDouble())
    awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
    button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS)
    awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
    // In steps, as a hand drags: one jump would be one move event.
    for (step in 1..DRAG_STEPS) {
        val t = step.toFloat() / DRAG_STEPS
        moveTo((from.x + (to.x - from.x) * t).toDouble(), (from.y + (to.y - from.y) * t).toDouble())
        awaitFrames(frames, frames.count.get() + 1)
    }
    awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
    during()
    if (!release) return
    button(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE)
    awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
}

/** A key on Kool's own GLFW key callback, as `udea-render`'s `GlKeys` presses one. */
internal fun KoolBackend.key(glfwKey: Int, action: Int) = onRenderThread {
    val window = currentWindow()
    val callback = checkNotNull(GLFW.glfwSetKeyCallback(window, null)) {
        "Kool installed no GLFW key callback, so this test would be pressing nothing"
    }
    try {
        callback.invoke(window, glfwKey, NO_SCANCODE, action, NO_MODIFIERS)
    } finally {
        GLFW.glfwSetKeyCallback(window, callback)
    }
}

/** Holds [glfwKey], on Kool's own GLFW key callback, for the length of [block]. */
internal fun KoolBackend.holding(frames: FrameProbe, glfwKey: Int, block: () -> Unit) {
    key(glfwKey, GLFW.GLFW_PRESS)
    awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
    try {
        block()
    } finally {
        key(glfwKey, GLFW.GLFW_RELEASE)
        awaitFrames(frames, frames.count.get() + SETTLE_FRAMES)
    }
}

/**
 * The Scene tab's rectangle on a [width] by [height] screen, read from the same window laid out with
 * no GL at the same size, as `GlEditorTabsTest` reads it: exact across, off down by the few pixels the
 * bundled font's line height differs from the headless one's.
 */
internal fun sceneViewOn(width: Int, height: Int): Rect {
    val twin = glEditorSession(EditorViews.detached())
    return uiTest(Size(width.toFloat(), height.toFloat())) { twin.window.content() }.use { ui ->
        ui.settle()
        ui.node(EditorTags.SCENE_VIEW).boundsInRoot
    }
}

/** Where world point ([x], [y]) is on the screen: through the editor camera into the view, then onto [view]. */
internal fun screenOfWorld(views: EditorViews, view: Rect, x: Float, y: Float): Offset {
    val at = ViewPoint()
    views.camera.project(x, y, 0f, at)
    return screenOfView(views, view, at)
}

/** Where view pixel [at] of the Scene tab is on the screen, [view] being the tab's rectangle there. */
internal fun screenOfView(views: EditorViews, view: Rect, at: ViewPoint): Offset =
    Offset(view.left + at.x * view.width / views.scene.width, view.bottom - at.y * view.height / views.scene.height)

/** Moves a drag is made of. */
private const val DRAG_STEPS = 6

/** GLFW passes a scancode with a key, and Kool's mapping reads the key alone. */
private const val NO_SCANCODE = 0

/** GLFW passes modifiers with a mouse button, and Kool's mapping reads none. */
private const val NO_MODIFIERS = 0

/** Frames from a GLFW event to the interface's verdict on it: see `GlKoolPointerTest`. */
internal const val SETTLE_FRAMES: Int = 4
