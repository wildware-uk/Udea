package dev.wildware.udea.render.input

import java.util.concurrent.atomic.AtomicIntegerArray

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
 * Every field is an atomic and [sample] allocates nothing: a press recorded at any moment is
 * seen by the next tick and by exactly one tick.
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

    /** 1 when held. */
    private val held = AtomicIntegerArray(catalog.actionCount)

    /** Edges not yet handed to a tick. Drained by [sample]. */
    private val pending = AtomicIntegerArray(catalog.actionCount)

    /** Axis x as raw float bits, so the whole state is atomics and nothing needs a lock. */
    private val axisXBits = AtomicIntegerArray(catalog.axisCount)

    private val axisYBits = AtomicIntegerArray(catalog.axisCount)

    /** Holds [action] down and records one press edge. */
    public fun press(action: ActionId) {
        held.set(action.value, 1)
        pending.incrementAndGet(action.value)
    }

    /** Releases [action]. Records no edge; releasing something never held is not an error. */
    public fun release(action: ActionId) {
        held.set(action.value, 0)
    }

    /**
     * Records one press edge without holding the action.
     *
     * The synthesised equivalent of a key tapped and released between two frames: the next tick
     * reports `justPressed`, and no tick reports it as held.
     */
    public fun tap(action: ActionId) {
        pending.incrementAndGet(action.value)
    }

    /** Sets [axis]. Values outside `-1..1` are clamped here rather than at the reader. */
    public fun setAxis(axis: AxisId, x: Float, y: Float) {
        axisXBits.set(axis.value, clamp(x).toRawBits())
        axisYBits.set(axis.value, clamp(y).toRawBits())
    }

    /** Whether [action] is currently held. What `input.state` reports. */
    public fun isHeld(action: ActionId): Boolean = held.get(action.value) != 0

    /** The current x of [axis]. What `input.state` reports. */
    public fun axisX(axis: AxisId): Float = Float.fromBits(axisXBits.get(axis.value))

    /** The current y of [axis]. */
    public fun axisY(axis: AxisId): Float = Float.fromBits(axisYBits.get(axis.value))

    /**
     * Releases everything and centres every axis.
     *
     * What a session teardown calls. Without it, an agent that disconnects while holding "move
     * right" leaves the character walking into a wall forever, and the next agent to connect
     * inherits it.
     */
    public fun releaseAll() {
        for (index in 0 until catalog.actionCount) {
            held.set(index, 0)
            pending.set(index, 0)
        }
        for (index in 0 until catalog.axisCount) {
            axisXBits.set(index, 0)
            axisYBits.set(index, 0)
        }
    }

    override fun sample(into: Intent) {
        require(into.catalog === catalog) {
            "this source samples into an Intent built over its own catalog"
        }
        for (index in 0 until catalog.actionCount) {
            into.setPressed(ActionId(index), held.get(index) != 0)
            // getAndSet, so one recorded press reaches exactly one tick.
            into.setPressCount(ActionId(index), pending.getAndSet(index, 0))
        }
        for (index in 0 until catalog.axisCount) {
            into.setAxis(
                AxisId(index),
                Float.fromBits(axisXBits.get(index)),
                Float.fromBits(axisYBits.get(index)),
            )
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
