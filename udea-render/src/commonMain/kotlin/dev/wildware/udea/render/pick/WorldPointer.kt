package dev.wildware.udea.render.pick

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.input.Intent
import dev.wildware.udea.render.input.IntentSource
import dev.wildware.udea.render.input.PointerPosition
import dev.wildware.udea.render.input.PointerState
import dev.wildware.udea.render.input.PointerWheel
import dev.wildware.udea.render.model.ModelCamera
import dev.wildware.udea.render.view.PickBounds

/**
 * What the player is pointing at, in world units (issue #262): **the one place a pixel becomes a
 * world point, and the place it stops being a pixel.**
 *
 * An RTS is driven by the mouse - move there, build here, attack that - and every one of those
 * orders is a world point or an entity that only presentation can work out, because only
 * presentation has the camera. So:
 *
 * ```
 * a pixel  ->  WorldPointer.aim  ->  a world point and a NetId  ->  Intent  ->  the tick
 *                (render thread)                                 (source)
 * ```
 *
 * The simulation reads the right-hand end and nothing else. It never sees the pixel, it never sees
 * the camera, and a recording of the right-hand end replays identically on a window of another size.
 * That is not a convention somebody keeps: there is no route from an `Intent` back to a camera, and
 * `PointerIntentTest` fails if a screen coordinate is ever added to one.
 *
 * ## Wiring it
 *
 * Register it in [dev.wildware.udea.render.RenderPhase.PreRender], **after** the camera rig that
 * places the camera, and hand [source] to the game's `IntentState`:
 *
 * ```kotlin
 * val camera = ModelCamera()
 * lateinit var pointer: WorldPointer
 * registry.register(RenderPhase.PreRender, { r -> IsometricRig(r, registry.frameTime, camera) })
 * registry.register(RenderPhase.PreRender, { r ->
 *     WorldPointer(r, camera, koolPointer, koolPointer, koolPointer) { pipeline.pickable }
 *         .also { pointer = it }
 * })
 * // and once the host exists:
 * host.ctx[IntentState.KEY].source = CompositeIntent(deviceIntent, pointer.source)
 * ```
 *
 * ## The frame clock and the tick clock
 *
 * [aim] runs once a **frame**; [source] is sampled once a **tick**, and the two rates differ. So the
 * things that are *events* - a wheel turn, a drag beginning, a drag ending - are latched here and
 * spent by the sample, exactly as `PointerState` counts presses and the tick spends them. Nothing is
 * lost when a frame is long, and nothing is delivered twice when a frame is short. The things that
 * are *levels* - where the cursor is, what is under it - are simply the latest reading.
 *
 * ## What it does not do
 *
 * It writes nothing into the world, holds no Fleks state and is not a Fleks system, like every other
 * [RenderSystem]. A dedicated server has no camera and no pointer, and its `IntentSource` is
 * whatever the network gives it.
 *
 * The pixels it is handed are the **window's** - `y` down from the top - and the picture it is told
 * about is the capturable frame, which on a plain game is the window itself (`KoolBackend` sizes the
 * frame from `window.renderWidth`). Under an open editor the Game tab is a rectangle inside the
 * window and the two stop agreeing; that case is the editor's own picking (`ScenePicker`), and a
 * game being inspected is not a game being played.
 *
 * ## Threads
 *
 * The render thread, like every [RenderSystem] - except [source], which is sampled on the simulation
 * thread. On every host this engine ships those are the same thread (`KoolThread` ticks from inside
 * Kool's frame callback, and `GlKoolInputTest` asserts it), which is why nothing here is
 * synchronised - the same bargain `KoolPointer` states at greater length.
 */
public class WorldPointer(
    /** Tells this system when it is being drawn for an editor's Scene view rather than for the game. */
    private val resources: RenderResources,
    /** The camera the game is played through: what a pixel is un-projected with. */
    public val camera: ModelCamera,
    /** Where the cursor is. [PointerPosition.NONE] on a host with no mouse. */
    private val position: PointerPosition,
    /** Which buttons are down: what a drag is made of. [PointerState.NONE] reports no drag, ever. */
    private val buttons: PointerState = PointerState.NONE,
    /** The wheel. [PointerWheel.NONE] for none. */
    private val wheel: PointerWheel = PointerWheel.NONE,
    /**
     * The render systems that can say where their entities are, in drawing order - a pipeline's
     * `pickable`. Empty picks the ground and never an entity.
     */
    pickable: () -> List<PickBounds> = { emptyList() },
) : RenderSystem {

    /** Un-projects through [camera]. Public so a game can ask its own questions of the same camera. */
    public val pick: CameraPick = CameraPick(camera)

    private val projector = CameraPickProjector(pick)

    /** What is drawn under the cursor. Public for a game that wants a selection box of its own. */
    public val picker: EntityPicker = EntityPicker(projector, pickable)

    /** The height of the ground orders land on, in world units. */
    public var groundZ: Float
        get() = projector.groundZ
        set(value) {
            require(value.isFinite()) { "a ground height is a finite number of world units, was $value" }
            projector.groundZ = value
        }

    /**
     * The pointer button a drag is made with: `0` is the left button, `2` the middle, as
     * `PointerState` numbers them.
     *
     * One button, because a drag is one gesture. A game that wants a box-select on the left and a
     * camera pan on the middle reads the pan from [PointerState] itself, where it belongs - a pan is
     * a camera, and a camera is not an order.
     */
    public var dragButton: Int = LEFT_BUTTON
        set(value) {
            require(value >= 0) { "a pointer button is a non-negative index, was $value" }
            field = value
        }

    /** Whether the cursor was over the world at the last [aim]. */
    public var isOnWorld: Boolean = false
        private set

    /** Where on the ground the cursor was. Meaningless unless [isOnWorld]. */
    public var worldX: Float = 0f
        private set

    /** Where on the ground the cursor was. Meaningless unless [isOnWorld]. */
    public var worldY: Float = 0f
        private set

    /** The entity drawn under the cursor, or [NetId.NONE]. */
    public var entity: NetId = NetId.NONE
        private set

    // Latched between two ticks, and spent by `source`. See "The frame clock and the tick clock".
    private var pendingScroll = 0f
    private var dragDown = false
    private var dragStartPending = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragEndPending = false
    private var dragEndX = 0f
    private var dragEndY = 0f

    private val hit = WorldPoint()

    /**
     * Reads the cursor at window pixel ([pixelX], [pixelY]) of a [width] x [height] picture, `y`
     * **down** from the top as every backend reports a pointer, and works out what it is pointing at.
     *
     * Public, and not only called by [render], because a host that knows better about its own
     * geometry should say so rather than be guessed at - and because everything this class does can
     * then be driven, and tested, with no window and no device.
     */
    public fun aim(pixelX: Float, pixelY: Float, width: Int, height: Int) {
        pick.fit(width, height)
        // Window pixels run down from the top; view pixels run up from the bottom. This is the one
        // line that turns them round, so nothing below `CameraPick` has to know there are two.
        val viewY = height - pixelY
        isOnWorld = pick.groundUnder(pixelX, viewY, projector.groundZ, hit)
        if (isOnWorld) {
            worldX = hit.x
            worldY = hit.y
            entity = picker.under(pixelX, viewY).firstOrNull() ?: NetId.NONE
        } else {
            entity = NetId.NONE
        }
        readDrag()
    }

    /** The cursor is not over the picture: nothing is pointed at, and a drag in progress ends. */
    public fun away() {
        isOnWorld = false
        entity = NetId.NONE
        readDrag()
    }

    /**
     * Latches a drag beginning and ending, from the button's level.
     *
     * A level and not a press count, because a drag is bounded by both edges and [PointerState]
     * counts only the down one. The world point latched is the one under the cursor at that frame,
     * which is what "where the drag began" means; a drag begun off the world does not begin.
     */
    private fun readDrag() {
        val down = buttons.isButtonDown(dragButton)
        if (down && !dragDown && isOnWorld) {
            dragStartPending = true
            dragStartX = worldX
            dragStartY = worldY
        }
        if (!down && dragDown && isOnWorld) {
            dragEndPending = true
            dragEndX = worldX
            dragEndY = worldY
        }
        dragDown = down
        pendingScroll += wheel.scrollY
        wheel.spendScroll()
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        // An editor's Scene view is being drawn: the pipeline runs every system again for it. The
        // cursor is read once per frame, not once per view, and the Scene view has a picker of its
        // own. `IsometricRig` skips its easing here for the same reason.
        if (resources.viewing.current != null) return
        if (position.isPointerOver) {
            aim(position.pointerX, position.pointerY, target.width, target.height)
        } else {
            away()
        }
    }

    /**
     * The [IntentSource] that puts this into a tick's [Intent].
     *
     * Composed with the game's own device source (`CompositeIntent`), not instead of it: this writes
     * the pointer and nothing else, so a keyboard and a mouse reach the same tick.
     */
    public val source: IntentSource = IntentSource { into -> writeInto(into) }

    /**
     * Writes this tick's pointer into [into] and spends the events. Called once per tick, from the
     * simulation thread, through [source].
     */
    public fun writeInto(into: Intent) {
        if (isOnWorld) into.setPointer(worldX, worldY, entity)
        if (pendingScroll != 0f) {
            into.setScroll(pendingScroll)
            pendingScroll = 0f
        }
        if (dragStartPending) {
            into.setDragStart(dragStartX, dragStartY)
            dragStartPending = false
        }
        if (dragEndPending) {
            into.setDragEnd(dragEndX, dragEndY)
            dragEndPending = false
        }
    }

    override fun toString(): String = if (isOnWorld) {
        "WorldPointer(at ($worldX, $worldY)${if (entity.isNone) "" else " over $entity"})"
    } else {
        "WorldPointer(off the world)"
    }

    private companion object {

        /** The left button, as `PointerState` and Kool number them. */
        const val LEFT_BUTTON = 0
    }
}
