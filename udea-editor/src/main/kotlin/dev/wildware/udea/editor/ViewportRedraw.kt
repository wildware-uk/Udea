package dev.wildware.udea.editor

import dev.wildware.udea.core.Tick

/**
 * Whether the viewport's picture is out of date this frame.
 *
 * A `SceneView` draws only when invalidated, and issue #194 asks for exactly that while the world is
 * paused: draw again when something changed, not every frame. Two things change a paused world, and
 * both are readable without looking at it:
 *
 * - **the tick**, which moves when the simulation steps - every frame while running, once per
 *   `time.step` while paused;
 * - **the highest completed command**, which moves when any tool call has run, the editor's own or an
 *   agent's. Every mutation that is not a tick is a tool call applied by the `SimBarrier`.
 *
 * `AgentGameLoop.pump` drains posted commands *before* the frame renders, so by the time the editor's
 * frame hook sees either value move, the capturable pass Kool is about to draw already holds the
 * change: one invalidation, in the same frame, is enough.
 *
 * The first [WARM_UP_FRAMES] frames are due regardless. The very first picture may be drawn before
 * Kool has put the capturable pass on the GPU, and a paused world would otherwise keep that empty
 * picture until someone changed something.
 */
internal class ViewportRedraw {

    private var framesSeen = 0

    private var lastTick: Tick? = null

    private var lastCompleted = Long.MIN_VALUE

    /** Called once per frame; true when the viewport should draw again. */
    fun due(tick: Tick, completedCommand: Long): Boolean {
        val changed = tick != lastTick || completedCommand != lastCompleted
        lastTick = tick
        lastCompleted = completedCommand
        framesSeen++
        return changed || framesSeen <= WARM_UP_FRAMES
    }

    override fun toString(): String = "ViewportRedraw(tick=$lastTick, completed=$lastCompleted)"

    private companion object {

        /** Frames drawn unconditionally at start: see the class KDoc. */
        const val WARM_UP_FRAMES: Int = 2
    }
}
