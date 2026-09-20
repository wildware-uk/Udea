package dev.wildware.udea.render.input

import dev.wildware.udea.core.identity.NetId

/**
 * What a controller asked for during **one tick**. The only thing simulation reads about input.
 *
 * ## Why this type exists at all
 *
 * `ControllerSystem` (`common/.../ecs/system/ControllerSystem.kt:29`) was a Fleks system that
 * called `Gdx.input` from inside the world tick. Three things followed, and all three are the
 * reason for this class:
 *
 * - the simulation read hardware, so a tick was not a function of its inputs and could not be
 *   replayed;
 * - it sampled once per **frame**, so how many samples a second of game time contained depended
 *   on the frame rate - 30 on a stalling machine, 144 on a fast one;
 * - nothing but a human at a keyboard could produce a value, so an agent could not drive the
 *   game and a recorded input stream could not be played back into it.
 *
 * An [Intent] is a plain value. It is produced by an [IntentSource] - a keyboard, a gamepad, an
 * agent's `input.*` tools, a replayed buffer - and consumed by ordinary simulation systems that
 * have never heard of a device.
 *
 * ## It is reused, never allocated per tick
 *
 * One instance lives in [IntentState] and is overwritten in place every tick. A fresh `Intent`
 * per tick would be 4 arrays of garbage 60 times a second, which is the allocation-free-per-tick
 * rule in standards section 4 aimed squarely at this path.
 */
public class Intent(
    /** The names and ids this intent is addressed by. */
    public val catalog: InputCatalog,
) {

    /** Whether each action is held **now**, at the moment this tick sampled. */
    private val held = BooleanArray(catalog.actionCount)

    /**
     * How many times each action went from up to down since the previous sample.
     *
     * A count and not a flag, and that is the whole of the edge-detection design. A key tapped
     * and released entirely between two rendered frames is never *held* at any sample point, so
     * a `pressed`-only model loses it outright - which at 30fps is a dropped attack roughly one
     * press in three. Counting presses as they arrive and consuming the count at the tick
     * boundary loses none and double-counts none. `EdgeDetectionTest` drives exactly that case.
     */
    private val presses = IntArray(catalog.actionCount)

    /** Axis x, in `-1..1`. */
    private val axisX = FloatArray(catalog.axisCount)

    /** Axis y, in `-1..1`. */
    private val axisY = FloatArray(catalog.axisCount)

    // --- the pointer (issue #262) ---------------------------------------------------------
    //
    // Plain fields rather than a block of their own, for the reason the arrays above are arrays:
    // one `Intent` is reused for ever, and a pointer object allocated per tick would be garbage
    // sixty times a second on the one path that must not produce any.
    //
    // **Every number here is in world units.** There is deliberately no pixel, no window size and
    // no camera: `WorldPointer` un-projects on the render thread and puts the answer here, so a
    // tick stays a function of its inputs and a recording replays the same orders on any window.

    /** Whether the player was pointing at the world at all this tick. */
    public var hasPointer: Boolean = false
        private set

    /** Where on the ground the pointer was, in world units. Meaningless unless [hasPointer]. */
    public var pointerX: Float = 0f
        private set

    /** Where on the ground the pointer was, in world units. Meaningless unless [hasPointer]. */
    public var pointerY: Float = 0f
        private set

    /**
     * The entity drawn under the pointer, or [NetId.NONE] for open ground.
     *
     * An id as well as the ground point, because they answer different orders: "attack **that**"
     * needs the unit, and a tall model's feet are not where the cursor is. A stale id resolves to
     * `null` through `NetIdIndex`, which is what makes one safe to carry into a tick at all.
     */
    public var pointerEntity: NetId = NetId.NONE
        private set

    /** Wheel notches since the previous tick. Positive is the wheel pushed away from the player. */
    public var scroll: Float = 0f
        private set

    /** Whether a drag began this tick. */
    public var dragStarted: Boolean = false
        private set

    /** Where on the ground a drag begun this tick began. Meaningless unless [dragStarted]. */
    public var dragStartX: Float = 0f
        private set

    /** Where on the ground a drag begun this tick began. Meaningless unless [dragStarted]. */
    public var dragStartY: Float = 0f
        private set

    /** Whether a drag ended this tick: the tick a box-select is decided on. */
    public var dragEnded: Boolean = false
        private set

    /** Where on the ground a drag ended this tick ended. Meaningless unless [dragEnded]. */
    public var dragEndX: Float = 0f
        private set

    /** Where on the ground a drag ended this tick ended. Meaningless unless [dragEnded]. */
    public var dragEndY: Float = 0f
        private set

    /** Whether [action] is held down as of this tick. */
    public fun isPressed(action: ActionId): Boolean = held[action.value]

    /**
     * Whether [action] went down at least once since the previous tick.
     *
     * True for a tap that was already released again by the time the tick ran - see [presses].
     */
    public fun isJustPressed(action: ActionId): Boolean = presses[action.value] > 0

    /** How many distinct presses of [action] this tick covers. Usually 0 or 1. */
    public fun pressCount(action: ActionId): Int = presses[action.value]

    /** Horizontal component of [axis], in `-1..1`. */
    public fun axisX(axis: AxisId): Float = axisX[axis.value]

    /** Vertical component of [axis], in `-1..1`. Positive is up, matching world space. */
    public fun axisY(axis: AxisId): Float = axisY[axis.value]

    /** Sets whether [action] is held. Called by an [IntentSource]. */
    public fun setPressed(action: ActionId, pressed: Boolean) {
        held[action.value] = pressed
    }

    /** Records [count] fresh presses of [action]. Called by an [IntentSource]. */
    public fun setPressCount(action: ActionId, count: Int) {
        require(count >= 0) { "a press count is never negative, was $count" }
        presses[action.value] = count
    }

    /** Sets [axis]. Values are stored as given; clamping is the source's job. */
    public fun setAxis(axis: AxisId, x: Float, y: Float) {
        axisX[axis.value] = x
        axisY[axis.value] = y
    }

    /**
     * Says the player is pointing at world point ([worldX], [worldY]) on the ground, with [entity]
     * drawn under the cursor ([NetId.NONE] for open ground). Called by an [IntentSource].
     *
     * @throws IllegalArgumentException for a point that is not a number. A `NaN` here compares equal
     *   to nothing, so it would make every later tick of a replay a mismatch pointing at the wrong
     *   place, and it can only arrive from an un-projection that has already gone wrong.
     */
    public fun setPointer(worldX: Float, worldY: Float, entity: NetId = NetId.NONE) {
        require(worldX.isFinite() && worldY.isFinite()) {
            "a pointer is at a finite world point, was ($worldX, $worldY)"
        }
        hasPointer = true
        pointerX = worldX
        pointerY = worldY
        pointerEntity = entity
    }

    /** Records [notches] of wheel since the previous tick. Positive is away from the player. */
    public fun setScroll(notches: Float) {
        require(notches.isFinite()) { "a wheel turn is a finite number of notches, was $notches" }
        scroll = notches
    }

    /** Says a drag began this tick, at world point ([worldX], [worldY]) on the ground. */
    public fun setDragStart(worldX: Float, worldY: Float) {
        require(worldX.isFinite() && worldY.isFinite()) {
            "a drag begins at a finite world point, was ($worldX, $worldY)"
        }
        dragStarted = true
        dragStartX = worldX
        dragStartY = worldY
    }

    /** Says a drag ended this tick, at world point ([worldX], [worldY]) on the ground. */
    public fun setDragEnd(worldX: Float, worldY: Float) {
        require(worldX.isFinite() && worldY.isFinite()) {
            "a drag ends at a finite world point, was ($worldX, $worldY)"
        }
        dragEnded = true
        dragEndX = worldX
        dragEndY = worldY
    }

    /** Back to "nothing held, nothing pressed, every axis centred". Allocates nothing. */
    public fun clear() {
        held.fill(false)
        presses.fill(0)
        axisX.fill(0f)
        axisY.fill(0f)
        hasPointer = false
        pointerX = 0f
        pointerY = 0f
        pointerEntity = NetId.NONE
        scroll = 0f
        dragStarted = false
        dragStartX = 0f
        dragStartY = 0f
        dragEnded = false
        dragEndX = 0f
        dragEndY = 0f
    }

    /**
     * Copies [other] into this one.
     *
     * How a recorded or received intent becomes the one the tick reads, and how a test asserts
     * what a sample produced without holding a reference to the live instance the next tick
     * overwrites.
     */
    public fun copyFrom(other: Intent) {
        require(other.catalog === catalog) {
            "an Intent can only be copied from one built over the same catalog"
        }
        other.held.copyInto(held)
        other.presses.copyInto(presses)
        other.axisX.copyInto(axisX)
        other.axisY.copyInto(axisY)
        hasPointer = other.hasPointer
        pointerX = other.pointerX
        pointerY = other.pointerY
        pointerEntity = other.pointerEntity
        scroll = other.scroll
        dragStarted = other.dragStarted
        dragStartX = other.dragStartX
        dragStartY = other.dragStartY
        dragEnded = other.dragEnded
        dragEndX = other.dragEndX
        dragEndY = other.dragEndY
    }

    /**
     * True when nothing is held, nothing was pressed, every axis is centred and the pointer says
     * nothing.
     *
     * The pointer counts, and that is load-bearing rather than tidy: a tick in which the player was
     * hovering over a unit is a tick whose input matters, and a recorder that called it idle would
     * drop the hover and replay a match in which nobody ever aimed at anything.
     */
    public fun isIdle(): Boolean {
        for (value in held) if (value) return false
        for (value in presses) if (value != 0) return false
        for (value in axisX) if (value != 0f) return false
        for (value in axisY) if (value != 0f) return false
        if (hasPointer || scroll != 0f || dragStarted || dragEnded) return false
        return true
    }

    override fun toString(): String = buildString {
        append("Intent(")
        var first = true
        for (index in 0 until catalog.actionCount) {
            if (!held[index] && presses[index] == 0) continue
            if (!first) append(", ")
            first = false
            append(catalog.actions[index])
            if (presses[index] > 0) append("!").append(presses[index])
        }
        for (index in 0 until catalog.axisCount) {
            if (axisX[index] == 0f && axisY[index] == 0f) continue
            if (!first) append(", ")
            first = false
            append(catalog.axes[index]).append("=(").append(axisX[index]).append(", ")
                .append(axisY[index]).append(")")
        }
        if (hasPointer) {
            if (!first) append(", ")
            first = false
            append("at (").append(pointerX).append(", ").append(pointerY).append(")")
            if (!pointerEntity.isNone) append(" over ").append(pointerEntity)
        }
        if (scroll != 0f) {
            if (!first) append(", ")
            first = false
            append("wheel ").append(scroll)
        }
        if (dragStarted) {
            if (!first) append(", ")
            first = false
            append("drag from (").append(dragStartX).append(", ").append(dragStartY).append(")")
        }
        if (dragEnded) {
            if (!first) append(", ")
            first = false
            append("drag to (").append(dragEndX).append(", ").append(dragEndY).append(")")
        }
        if (first) append("idle")
        append(")")
    }
}
