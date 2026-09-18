package dev.wildware.udea.render.input

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * An [IntentSource] written from outside the simulation: the agent's `input.*` tools, a replay,
 * a scripted test.
 *
 * ## Why it goes through the identical seam a keyboard does
 *
 * Because otherwise the agent is testing a different game. `udea-agent-host`'s Phase 1 injection
 * point was `Gdx.input.inputProcessor` - it posted synthetic key events into LibGDX and hoped the
 * game read them the same way. That only works while the game polls the device, it cannot work
 * at all in `RenderMode.Headless` (there is no `Gdx.input`), and it makes the agent's input
 * arrive at frame boundaries rather than tick boundaries, so an agent holding a key for "ten
 * ticks" held it for however many ticks ten frames happened to contain. Here the agent writes an
 * intent and the tick reads an intent, which is byte-for-byte what a human produces.
 *
 * ## Threading
 *
 * Written by the HTTP thread (or by any thread), read by the simulation thread once per tick.
 * One lock guards every field (atomicfu's, so it exists in common code), and [sample] takes it
 * once and allocates nothing: a press recorded at any moment is seen by the next tick and by
 * exactly one tick, and a tick never sees an action's level from one moment and its edge count
 * from another.
 *
 * A press is a **level** plus a counted **edge**, exactly like a keyboard: [press] does both, so
 * `input.press` then `input.release` two ticks later produces one `justPressed` and three ticks
 * of `pressed`, and [tap] produces the edge alone - the agent's version of a key pressed and
 * released between two frames.
 */
public class InjectedIntent(
    /** The names and ids this source can write. */
    public val catalog: InputCatalog,
) : IntentSource {

    private val lock = SynchronizedObject()

    /** True when held. */
    private val held = BooleanArray(catalog.actionCount)

    /** Edges not yet handed to a tick. Drained by [sample]. */
    private val pending = IntArray(catalog.actionCount)

    private val axisX = FloatArray(catalog.axisCount)

    private val axisY = FloatArray(catalog.axisCount)

    /** Holds [action] down and records one press edge. */
    public fun press(action: ActionId) {
        synchronized(lock) {
            held[action.value] = true
            pending[action.value]++
        }
    }

    /** Releases [action]. Records no edge; releasing something never held is not an error. */
    public fun release(action: ActionId) {
        synchronized(lock) { held[action.value] = false }
    }

    /**
     * Records one press edge without holding the action.
     *
     * The synthesised equivalent of a key tapped and released between two frames: the next tick
     * reports `justPressed`, and no tick reports it as held.
     */
    public fun tap(action: ActionId) {
        synchronized(lock) { pending[action.value]++ }
    }

    /** Sets [axis]. Values outside `-1..1` are clamped here rather than at the reader. */
    public fun setAxis(axis: AxisId, x: Float, y: Float) {
        val clampedX = clamp(x)
        val clampedY = clamp(y)
        synchronized(lock) {
            axisX[axis.value] = clampedX
            axisY[axis.value] = clampedY
        }
    }

    /** Whether [action] is currently held. What `input.state` reports. */
    public fun isHeld(action: ActionId): Boolean = synchronized(lock) { held[action.value] }

    /** The current x of [axis]. What `input.state` reports. */
    public fun axisX(axis: AxisId): Float = synchronized(lock) { axisX[axis.value] }

    /** The current y of [axis]. */
    public fun axisY(axis: AxisId): Float = synchronized(lock) { axisY[axis.value] }

    /**
     * Releases everything and centres every axis.
     *
     * What a session teardown calls. Without it, an agent that disconnects while holding "move
     * right" leaves the character walking into a wall forever, and the next agent to connect
     * inherits it.
     */
    public fun releaseAll() {
        synchronized(lock) {
            held.fill(false)
            pending.fill(0)
            axisX.fill(0f)
            axisY.fill(0f)
        }
    }

    override fun sample(into: Intent) {
        require(into.catalog === catalog) {
            "this source samples into an Intent built over its own catalog"
        }
        synchronized(lock) {
            for (index in 0 until catalog.actionCount) {
                into.setPressed(ActionId(index), held[index])
                // Read and zeroed under one lock, so one recorded press reaches exactly one tick.
                into.setPressCount(ActionId(index), pending[index])
                pending[index] = 0
            }
            for (index in 0 until catalog.axisCount) {
                into.setAxis(AxisId(index), axisX[index], axisY[index])
            }
        }
    }

    override fun toString(): String = "InjectedIntent(${catalog.actionCount} action(s))"

    private fun clamp(value: Float): Float = when {
        !value.isFinite() -> 0f
        value < -1f -> -1f
        value > 1f -> 1f
        else -> value
    }
}
