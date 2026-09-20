package dev.wildware.udea.editor

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.render.pick.EntityPicker
import dev.wildware.udea.render.pick.PickProjector
import dev.wildware.udea.render.pick.PickedBounds
import dev.wildware.udea.render.view.EditorCamera
import dev.wildware.udea.render.view.ViewPoint
import dev.wildware.udea.render.view.WorldViewport

/**
 * What is where in the Scene tab (issue #235): the entities the view's render systems report through
 * `PickBounds`, each turned into the rectangle of view pixels it covers.
 *
 * ## The rules live in [EntityPicker] now
 *
 * Which of two entities under the pointer is in front, what a selection box touches, that an entity
 * drawn by two systems is listed once - all of it is [EntityPicker]'s, in `udea-render`, because
 * issue #262 needed the same answers in a *game* and a second copy of them would have drifted. This
 * class is what is left once the editor's camera is lifted out: a [PickProjector] over the Scene
 * tab's [EditorCamera], and three methods that pass through.
 *
 * Positions are view pixels from the bottom left, as [WorldViewport.toView] maps a pointer. A
 * sprite's rectangle is projected through the view's 2D camera and a model's box through its 3D
 * orbit, because that is how each is drawn in a Scene view whichever camera a drag moves.
 *
 * Render thread only: it asks the render systems, which read the world.
 */
internal class ScenePicker(private val view: WorldViewport) : PickProjector {

    private val camera: EditorCamera = checkNotNull(view.camera) { "$view is the Game tab, which the editor does not pick in" }

    private val picker = EntityPicker(this) { view.pickBounds }

    /** Every entity drawn under view pixel ([x], [y]), front-most first, each once. */
    fun under(x: Float, y: Float): List<NetId> = picker.under(x, y)

    /**
     * Every entity whose drawn rectangle touches the view rectangle from ([left], [bottom]) to
     * ([right], [top]), in any corner order, each once, front-most first.
     */
    fun touching(left: Float, bottom: Float, right: Float, top: Float): List<NetId> =
        picker.touching(left, bottom, right, top)

    /** Each on-screen rectangle [entities] are drawn over, for outlining a selection. */
    fun bounds(entities: Collection<NetId>): List<PickedBounds> = picker.bounds(entities)

    /** A sprite's rectangle is seen through the Scene view's 2D camera, in world units. */
    override fun projectRect(x: Float, y: Float, out: ViewPoint): Boolean {
        val projection = camera.projection
        out.x = projection.pixelX(x)
        out.y = projection.pixelY(y)
        return true
    }

    /** A model's box is seen through the Scene view's 3D orbit, whichever camera a drag moves. */
    override fun projectBox(x: Float, y: Float, z: Float, out: ViewPoint): Boolean =
        camera.projectOrbit(x, y, z, out)

    override fun depthOf(x: Float, y: Float, z: Float): Float = camera.depthOf(x, y, z)

    override fun toString(): String = "ScenePicker(${view.pickBounds.size} pickable systems)"
}
