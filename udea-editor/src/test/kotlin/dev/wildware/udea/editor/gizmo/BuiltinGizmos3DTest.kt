package dev.wildware.udea.editor.gizmo

import com.github.quillraven.fleks.Component
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.spatial.Transform3D
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The built-in 3D gizmos (issue #237) as values: which handles each declares, where, and what a drag
 * on each writes to a `Transform3D` - against numbers worked out here, not read off the code.
 *
 * Each gizmo below is hand-written against the public API alone and calls one built-in, exactly as
 * the gizmos `udea-codegen` generates from `Transform3D`'s own handle annotations do.
 */
class BuiltinGizmos3DTest {

    private object Translate : Gizmo<Transform3D> {
        override val component = Transform3D

        override fun GizmoScope<Transform3D>.build(target: GizmoTarget<Transform3D>) {
            val t = target.component
            translateHandles(target, Transform3D::x, t.x, Transform3D::y, t.y, Transform3D::z, t.z)
        }
    }

    private object Rings : Gizmo<Transform3D> {
        override val component = Transform3D

        override fun GizmoScope<Transform3D>.build(target: GizmoTarget<Transform3D>) {
            val t = target.component
            rotationRings(target, Transform3D::rotationX, t.rotationX, Transform3D::rotationY, t.rotationY, Transform3D::rotationZ, t.rotationZ)
        }
    }

    private object Scale : Gizmo<Transform3D> {
        override val component = Transform3D

        override fun GizmoScope<Transform3D>.build(target: GizmoTarget<Transform3D>) {
            val t = target.component
            scaleHandles(target, Transform3D::scaleX, t.scaleX, Transform3D::scaleY, t.scaleY, Transform3D::scaleZ, t.scaleZ)
        }
    }

    private val entity = NetId.of(index = 4, generation = 2)

    private fun target(transform: Transform3D, axes: AxisFrame = AxisFrame.WORLD): GizmoTarget<Transform3D> =
        GizmoTarget(entity, transform, WorldPoint(transform.x, transform.y, transform.z), axes)

    private fun <C : Component<C>> Handle<C>.writes(drag: Drag): Map<String, Float> =
        drag(drag).associate { it.field.value to it.value }

    private fun WorldPoint.plus(dx: Float, dy: Float, dz: Float): WorldPoint = WorldPoint(x + dx, y + dy, z + dz)

    // --- translate ------------------------------------------------------------------------------

    @Test
    fun `translate is an arrow along each axis and a square in each plane, all on the entity`() {
        val handles = Translate.handles(target(Transform3D(x = 1f, y = 2f, z = 3f)))

        assertEquals(
            listOf(
                HandleShape.Arrow(Axis.X), HandleShape.Arrow(Axis.Y), HandleShape.Arrow(Axis.Z),
                HandleShape.PlaneTab(Plane.XY), HandleShape.PlaneTab(Plane.YZ), HandleShape.PlaneTab(Plane.XZ),
            ),
            handles.map { it.shape },
        )
        assertEquals(
            listOf(
                DragConstraint.Along(Axis.X), DragConstraint.Along(Axis.Y), DragConstraint.Along(Axis.Z),
                DragConstraint.Across(Plane.XY), DragConstraint.Across(Plane.YZ), DragConstraint.Across(Plane.XZ),
            ),
            handles.map { it.constraint },
        )
        assertTrue(handles.all { it.at == WorldPoint(1f, 2f, 3f) }, "a translate handle is not on the entity: ${handles.map { it.at }}")
    }

    @Test
    fun `each arrow moves only its own axis, and each plane square only its two, by the drag`() {
        val (x, y, z, xy, yz, xz) = Translate.handles(target(Transform3D(x = 1f, y = 2f, z = 3f)))
        // Grabbed off-centre, as a person does, and moved 3, -2 and 5: the offset cancels.
        val grabbed = WorldPoint(1.3f, 2.2f, 2.9f)
        val drag = Drag(grabbed, grabbed.plus(3f, -2f, 5f))

        assertEquals(mapOf("x" to 4f), x.writes(drag))
        assertEquals(mapOf("y" to 0f), y.writes(drag))
        assertEquals(mapOf("z" to 8f), z.writes(drag))
        assertEquals(mapOf("x" to 4f, "y" to 0f), xy.writes(drag))
        assertEquals(mapOf("y" to 0f, "z" to 8f), yz.writes(drag))
        assertEquals(mapOf("x" to 4f, "z" to 8f), xz.writes(drag))
        assertTrue(listOf(x, y, z, xy, yz, xz).flatMap { it.drag(drag) }.all { it.snap == Snap.Grid }, "a position snaps to the grid")
    }

    @Test
    fun `translate follows the target's axes, and an arrow turned off a world axis writes every field it crosses`() {
        val turned = AxisFrame.euler(0f, 0f, (PI / 4).toFloat())
        val handles = Translate.handles(target(Transform3D(), axes = turned))

        assertTrue(handles.all { it.axes == turned }, "a translate handle ignored the target's axes")
        val (x, _, z) = handles
        // An eighth of a turn about Z: the X arrow runs across world X and Y, and Z is still Z.
        assertEquals(listOf("x", "y"), x.drag(Drag(x.at, x.at)).map { it.field.value })
        assertEquals(listOf("z"), z.drag(Drag(z.at, z.at)).map { it.field.value })
    }

    // --- rotate ---------------------------------------------------------------------------------

    @Test
    fun `rotate is a ring about each axis, and a quarter turn round a ring adds a quarter to its own field alone`() {
        val transform = Transform3D(x = 1f, y = 1f, z = 1f, rotationX = 0.1f, rotationY = 0.2f, rotationZ = 0.3f)
        val handles = Rings.handles(target(Transform3D(x = 1f, y = 1f, z = 1f)))
        assertEquals(listOf(HandleShape.Ring(Axis.X), HandleShape.Ring(Axis.Y), HandleShape.Ring(Axis.Z)), handles.map { it.shape })
        assertEquals(
            listOf(DragConstraint.Across(Plane.YZ), DragConstraint.Across(Plane.XZ), DragConstraint.Across(Plane.XY)),
            handles.map { it.constraint },
        )

        val (aboutX, aboutY, aboutZ) = Rings.handles(target(transform))
        val centre = WorldPoint(1f, 1f, 1f)
        // Each ring's quarter turn, right-handed about the axis it is drawn round: +Y to +Z about X,
        // +Z to +X about Y, +X to +Y about Z. With the entity turned, those axes are its gimbal's.
        for ((ring, field, start) in listOf(Triple(aboutX, "rotationX", 0.1f), Triple(aboutY, "rotationY", 0.2f), Triple(aboutZ, "rotationZ", 0.3f))) {
            val shape = ring.shape as HandleShape.Ring
            val (u, v) = inPlaneOf(ring.axes, shape.normal)
            val written = ring.writes(Drag(centre.plus(u, 2f), centre.plus(v, 2f)))
            assertEquals(setOf(field), written.keys, "the ${shape.normal} ring wrote another field")
            assertNear(start + (PI / 2).toFloat(), written.getValue(field), "the ${shape.normal} ring did not add a quarter turn")
            assertTrue(ring.drag(Drag(ring.at, ring.at)).all { it.snap == Snap.Angle }, "a turn snaps to the angle step")
        }
    }

    @Test
    fun `each ring turns about the axis its field turns the model about, whatever the world or local switch says`() {
        val transform = Transform3D(rotationX = 0.4f, rotationY = 0.5f, rotationZ = (PI / 2).toFloat())
        val (aboutX, aboutY, aboutZ) = Rings.handles(target(transform, axes = AxisFrame.WORLD))

        // rotationZ is applied last, so its ring is about world Z. rotationY is applied before it, so
        // its ring is about Y turned by the heading: a quarter turn puts it on world -X. rotationX is
        // applied first, about X turned by both of the others.
        assertDirection(WorldPoint(0f, 0f, 1f), aboutZ.axes.direction(Axis.Z), "the Z ring")
        assertDirection(WorldPoint(-1f, 0f, 0f), aboutY.axes.direction(Axis.Y), "the Y ring")
        assertDirection(AxisFrame.euler(0.4f, 0.5f, (PI / 2).toFloat()).x, aboutX.axes.direction(Axis.X), "the X ring")
        // A quarter turn right-handed about world -X - from world +Z to world +Y - is a quarter on rotationY.
        val written = aboutY.writes(Drag(WorldPoint(0f, 0f, 2f), WorldPoint(0f, 2f, 0f)))
        assertNear(0.5f + (PI / 2).toFloat(), written.getValue("rotationY"), "a quarter about the Y ring's axis")
    }

    // --- scale ----------------------------------------------------------------------------------

    @Test
    fun `scale is a box out along each axis and one in the middle, all on the entity`() {
        val handles = Scale.handles(target(Transform3D(x = 5f)))

        assertEquals(
            listOf(HandleShape.ScaleBox(Axis.X), HandleShape.ScaleBox(Axis.Y), HandleShape.ScaleBox(Axis.Z), HandleShape.UniformBox),
            handles.map { it.shape },
        )
        assertEquals(
            listOf(DragConstraint.Along(Axis.X), DragConstraint.Along(Axis.Y), DragConstraint.Along(Axis.Z), DragConstraint.ViewPlane),
            handles.map { it.constraint },
        )
        assertTrue(handles.all { it.at == WorldPoint(5f, 0f, 0f) })
    }

    @Test
    fun `an axis box scales its own axis by how much further from the centre the drag is than where it began`() {
        val (x, y, z) = Scale.handles(target(Transform3D(x = 5f, scaleX = 2f, scaleY = 3f, scaleZ = 4f)))

        // Grabbed 2 out along X and pulled to 3: half as far again, so half as big again.
        assertEquals(mapOf("scaleX" to 3f), x.writes(Drag(WorldPoint(7f, 0f, 0f), WorldPoint(8f, 0.5f, 0f))))
        // Grabbed 4 out along Y and pushed in to 1: a quarter the size.
        assertEquals(mapOf("scaleY" to 0.75f), y.writes(Drag(WorldPoint(5f, 4f, 0f), WorldPoint(5f, 1f, 0f))))
        // Pushed through the centre and out the other side: never below nothing.
        assertEquals(mapOf("scaleZ" to 0f), z.writes(Drag(WorldPoint(5f, 0f, 2f), WorldPoint(5f, 0f, -1f))))
    }

    @Test
    fun `the middle box scales every axis by the same factor, doubling for a drag as long as the axis boxes stand off`() {
        val uniform = Scale.handles(target(Transform3D(scaleX = 1f, scaleY = 2f, scaleZ = 0.5f))).last()
        val pixel = 0.01f
        val reach = HandlePainter.SCALE_REACH * pixel
        // Up and to the right, as far as the axis boxes stand from the middle on screen.
        val along = WorldPoint(reach / sqrt(2f), 0f, reach / sqrt(2f))

        val grown = uniform.writes(Drag(WorldPoint(0f, 0f, 0f), along, unitsPerPixel = pixel))
        assertNear(2f, grown.getValue("scaleX"), "scaleX")
        assertNear(4f, grown.getValue("scaleY"), "scaleY")
        assertNear(1f, grown.getValue("scaleZ"), "scaleZ")
        // The same drag the other way is nothing left, and never less.
        val gone = uniform.writes(Drag(along, WorldPoint(0f, 0f, 0f).plus(-along.x, 0f, -along.z), unitsPerPixel = pixel))
        assertEquals(mapOf("scaleX" to 0f, "scaleY" to 0f, "scaleZ" to 0f), gone)
        assertTrue(uniform.drag(Drag(uniform.at, uniform.at)).all { it.snap == Snap.None }, "a scale is a factor, not a distance on the grid")
    }

    // --- the frames and the drag helpers ----------------------------------------------------------

    @Test
    fun `a Transform3D's frame is X turned first, then Y, then Z, the order its model is drawn in`() {
        assertEquals(AxisFrame.WORLD, AxisFrame.euler(0f, 0f, 0f), "no turn is the world's axes exactly")
        assertEquals(AxisFrame.heading(0.7f), AxisFrame.euler(0f, 0f, 0.7f), "a turn about Z alone is a heading")
        // A quarter about Y takes X down to -Z, right-handed.
        val pitched = AxisFrame.euler(0f, (PI / 2).toFloat(), 0f)
        assertDirection(WorldPoint(0f, 0f, -1f), pitched.x, "X after a quarter about Y")
        // A quarter about X first, then a quarter about Z: Y goes up to Z, and stays there; X goes to Y.
        val both = AxisFrame.euler((PI / 2).toFloat(), 0f, (PI / 2).toFloat())
        assertDirection(WorldPoint(0f, 1f, 0f), both.x, "X")
        assertDirection(WorldPoint(0f, 0f, 1f), both.y, "Y")
        assertDirection(WorldPoint(1f, 0f, 0f), both.z, "Z")
    }

    @Test
    fun `a turn about any axis is measured right-handed, the short way round`() {
        val centre = WorldPoint(1f, 2f, 3f)
        val xAxis = WorldPoint(1f, 0f, 0f)
        assertNear((PI / 2).toFloat(), Drag(centre.plus(0f, 1f, 0f), centre.plus(0f, 0f, 1f)).turnAbout(centre, xAxis), "+Y to +Z about X")
        assertNear(-(PI / 2).toFloat(), Drag(centre.plus(0f, 0f, 1f), centre.plus(0f, 1f, 0f)).turnAbout(centre, xAxis), "+Z to +Y about X")
        // Off the plane, the drag is measured where it crosses it: moving along the axis is no turn.
        assertNear(0f, Drag(centre.plus(0f, 1f, 0f), centre.plus(5f, 1f, 0f)).turnAbout(centre, xAxis), "along the axis")
        // Three quarters one way is a quarter the other.
        assertNear(-(PI / 2).toFloat(), Drag(centre.plus(0f, 1f, 0f), centre.plus(0f, 0f, -1f)).turnAbout(centre, xAxis), "the short way")
    }

    // --- fixture -------------------------------------------------------------------------------

    private operator fun <T> List<T>.component6(): T = this[5]

    private fun WorldPoint.plus(direction: WorldPoint, by: Float): WorldPoint =
        WorldPoint(x + direction.x * by, y + direction.y * by, z + direction.z * by)

    /** The two directions a ring about [normal] of [axes] lies across, in right-handed order. */
    private fun inPlaneOf(axes: AxisFrame, normal: Axis): Pair<WorldPoint, WorldPoint> = when (normal) {
        Axis.X -> axes.y to axes.z
        Axis.Y -> axes.z to axes.x
        Axis.Z -> axes.x to axes.y
    }

    private fun assertNear(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) <= TOLERANCE, "$message: expected $expected, was $actual")
    }

    private fun assertDirection(expected: WorldPoint, actual: WorldPoint, message: String) {
        assertTrue(expected.distanceTo(actual) <= TOLERANCE, "$message: expected $expected, was $actual")
    }

    private companion object {
        const val TOLERANCE = 1e-5f

        init {
            // The quarter turns above are exact only if these are.
            check(abs(cos(PI / 2)) < 1e-15 && abs(sin(PI / 2) - 1) < 1e-15)
        }
    }
}
