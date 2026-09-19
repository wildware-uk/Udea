package dev.wildware.udea.assets.compiler.gen

import dev.wildware.udea.assets.compiler.TestPaths
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a model's named nodes are, read out of the glTF JSON with no renderer and no Kool
 * (issue #260).
 *
 * A socket is a node - a Blender Empty, a bone, a mount point - and the asset build has to say
 * where it is before anything draws, because the simulation resolves mounts headlessly. Two
 * things have to be right, and the tests below check each on numbers anybody can redo:
 *
 * - a node's place is its own transform under all of its ancestors', and
 * - the file is Y-up while the world is Z-up, so what comes out is `(x, -z, y)` of what went in.
 */
class GltfNodesTest {

    private val fox: Path = TestPaths.exampleAssets.resolve("models/fox/Fox.glb")

    /** The socket fixture of issue #260: `example-assets/models/chassis/build_chassis.py` made it. */
    private val chassis: Path = TestPaths.exampleAssets.resolve("models/chassis/chassis.glb")

    // ---- the frame ----------------------------------------------------------------------

    @Test
    fun `a node's place comes out in the world's Z-up frame, not the file's Y-up one`() {
        // Two metres up the file's +Y and three along its +Z: up in the world, and away from
        // the camera, which is the world's -Y.
        val file = gltf("""[{"name": "socket_roof", "translation": [1.0, 2.0, 3.0]}]""")

        val socket = GltfNodes.read(file).getOrThrow().single()

        assertEquals(1f, socket.x, "the file's X is the world's X")
        assertEquals(-3f, socket.y, "the file's +Z is the world's -Y")
        assertEquals(2f, socket.z, "the file's +Y is the world's +Z")
    }

    @Test
    fun `a socket turned about the file's up axis comes out turned about the world's`() {
        // A quarter turn about the file's +Y. In the world that is a quarter turn about +Z:
        // the heading a chassis's socket is pointed by.
        val turn = """[0.0, ${sin(PI / 4)}, 0.0, ${cos(PI / 4)}]"""
        val file = gltf("""[{"name": "socket_flank", "rotation": $turn}]""")

        val socket = GltfNodes.read(file).getOrThrow().single()

        assertEquals(0f, socket.qx, TOLERANCE, "in $socket")
        assertEquals(0f, socket.qy, TOLERANCE, "in $socket")
        assertEquals(sin(PI / 4).toFloat(), socket.qz, TOLERANCE, "in $socket")
        assertEquals(cos(PI / 4).toFloat(), socket.qw, TOLERANCE, "in $socket")
    }

    @Test
    fun `a node under a parent carries the parent's turn and scale`() {
        // The hull is half size and turned a quarter turn about the file's +Y - the world's +Z -
        // and the socket sits two along the hull's own X.
        val turn = """[0.0, ${sin(PI / 4)}, 0.0, ${cos(PI / 4)}]"""
        val file = gltf(
            """[
              {"name": "hull", "rotation": $turn, "scale": [0.5, 0.5, 0.5], "children": [1]},
              {"name": "socket_roof", "translation": [2.0, 0.0, 0.0]}
            ]""",
        )

        val socket = GltfNodes.read(file).getOrThrow().single { it.name == "socket_roof" }

        // Half of two is one, and the quarter turn about the world's +Z takes +X onto +Y.
        assertEquals(0f, socket.x, TOLERANCE, "in $socket")
        assertEquals(1f, socket.y, TOLERANCE, "in $socket")
        assertEquals(0f, socket.z, TOLERANCE, "in $socket")
        assertEquals(0.5f, socket.scaleX, TOLERANCE, "the hull's scale reaches the socket, in $socket")
    }

    @Test
    fun `a node placed by a matrix is read the same as one placed by translate-rotate-scale`() {
        // The same place twice: glTF lets a node state either, and a file from a converter
        // usually states the matrix. Column-major, as the spec says.
        val byMatrix = gltf(
            """[{"name": "socket", "matrix": [
                 2.0, 0.0, 0.0, 0.0,
                 0.0, 2.0, 0.0, 0.0,
                 0.0, 0.0, 2.0, 0.0,
                 1.0, 2.0, 3.0, 1.0]}]""",
            name = "by-matrix",
        )
        val byParts = gltf(
            """[{"name": "socket", "translation": [1.0, 2.0, 3.0], "scale": [2.0, 2.0, 2.0]}]""",
            name = "by-parts",
        )

        assertEquals(GltfNodes.read(byParts).getOrThrow(), GltfNodes.read(byMatrix).getOrThrow())
    }

    // ---- what is and is not a node --------------------------------------------------------

    @Test
    fun `an unnamed node is not offered, because nothing could ask for it`() {
        val file = gltf("""[{"name": "socket"}, {"translation": [1.0, 0.0, 0.0]}, {"name": ""}]""")

        assertEquals(listOf("socket"), GltfNodes.read(file).getOrThrow().map { it.name })
    }

    @Test
    fun `a node keeps its own index in the file, which is its identity`() {
        val file = gltf("""[{"name": "a"}, {}, {"name": "b"}]""")

        assertEquals(listOf(0, 2), GltfNodes.read(file).getOrThrow().map { it.index })
    }

    @Test
    fun `a file with no nodes has none`() {
        assertEquals(emptyList(), GltfNodes.read(gltf(null)).getOrThrow())
    }

    // ---- what is refused --------------------------------------------------------------------

    @Test
    fun `a child claimed by two parents is refused, naming both`() {
        val file = gltf("""[{"name": "a", "children": [2]}, {"name": "b", "children": [2]}, {"name": "c"}]""")

        val problem = GltfNodes.read(file).exceptionOrNull()?.message

        assertTrue(problem.orEmpty().contains("two parents"), "was: $problem")
    }

    @Test
    fun `a child the file does not have is refused`() {
        val file = gltf("""[{"name": "a", "children": [7]}]""")

        val problem = GltfNodes.read(file).exceptionOrNull()?.message

        assertTrue(problem.orEmpty().contains("which the file does not have"), "was: $problem")
    }

    @Test
    fun `a file whose JSON does not parse is refused rather than read as empty`() {
        val file = TestPaths.scratch("gltf-nodes-broken").resolve("broken.gltf")
        file.parent.toFile().mkdirs()
        file.writeText("{ this is not json")

        assertNull(GltfNodes.read(file).getOrNull())
    }

    // ---- a real file ------------------------------------------------------------------------

    /**
     * The Khronos Fox, whose 26 nodes are a mesh, a scene root and 24 bones - the "socket on an
     * animated part" case the issue names, read from a file nobody here wrote.
     *
     * The head's numbers are the fox's own units, about 80 tall and 155 long: the head is 60.7
     * up and 36.2 forward of the origin at rest, and forward is the world's -Y because a glTF
     * model faces its own +Z (`ImportedModel`).
     */
    @Test
    fun `the fox's bones are read, each placed under every bone above it`() {
        val nodes = GltfNodes.read(fox).getOrThrow()

        assertEquals(26, nodes.size, "the fox's nodes, all of them named")
        val head = nodes.single { it.name == "b_Head_05" }
        assertEquals(8, head.index)
        assertEquals(0f, head.x, FOX_TOLERANCE, "in $head")
        assertEquals(-36.15f, head.y, FOX_TOLERANCE, "the head is forward of the origin, in $head")
        assertEquals(60.73f, head.z, FOX_TOLERANCE, "the head is above the ground, in $head")
        assertTrue(
            nodes.single { it.name == "b_Hip_01" }.z < head.z,
            "the hip is below the head: ${nodes.single { it.name == "b_Hip_01" }}",
        )
    }

    /**
     * The chassis fixture, exported from Blender by `build_chassis.py`, which put each socket at
     * a number a person chose: the roof socket 0.87 up on the ring, the flank sockets 0.7 out to
     * each side, and the end sockets 1.2 along, scaled to 0.6 because they take a small module.
     *
     * This is the file `udea-render`'s `GlSocketMountTest` mounts parts on, and that test carries
     * the same numbers by hand - it cannot read them, because the asset build is not on a
     * renderer's classpath. So they are checked here, against the file, and a rebuilt fixture
     * that moved a socket fails here rather than drawing a part in the wrong place there.
     */
    @Test
    fun `the chassis fixture's sockets are where its build script put them`() {
        val nodes = GltfNodes.read(chassis).getOrThrow()

        assertEquals(
            listOf(
                "mount_notch", "socket_roof", "mount_ring", "socket_front", "socket_left",
                "socket_rear", "socket_right", "wheel_0", "wheel_1", "wheel_2", "wheel_3", "hull",
            ),
            nodes.map { it.name },
            "the fixture's nodes, in file order: an index below is a position in this list",
        )

        val roof = nodes.single { it.name == "socket_roof" }
        assertEquals(1, roof.index)
        assertEquals(0f, roof.x, FIXTURE_TOLERANCE, "in $roof")
        assertEquals(0f, roof.y, FIXTURE_TOLERANCE, "in $roof")
        assertEquals(0.87f, roof.z, FIXTURE_TOLERANCE, "on top of the ring, in $roof")
        assertEquals(1f, roof.qw, FIXTURE_TOLERANCE, "the roof socket faces straight up, in $roof")

        val left = nodes.single { it.name == "socket_left" }
        assertEquals(4, left.index)
        assertEquals(0.7f, left.y, FIXTURE_TOLERANCE, "out of the left flank, in $left")
        // Its own +Z points along the world's +Y: a quarter turn about X, the negative way.
        assertEquals(-QUARTER_TURN_AXIS, left.qx, FIXTURE_TOLERANCE, "in $left")
        assertEquals(QUARTER_TURN_AXIS, left.qw, FIXTURE_TOLERANCE, "in $left")

        val front = nodes.single { it.name == "socket_front" }
        assertEquals(3, front.index)
        assertEquals(1.2f, front.x, FIXTURE_TOLERANCE, "off the nose, in $front")
        assertEquals(QUARTER_TURN_AXIS, front.qy, FIXTURE_TOLERANCE, "its +Z points along +X, in $front")
        assertEquals(0.6f, front.scaleX, FIXTURE_TOLERANCE, "a small socket takes a small module, in $front")
    }

    /** The turret module, whose own two sockets are what a part mounted on a part hangs off. */
    @Test
    fun `the turret fixture has a half-scale socket on its roof and one at its muzzle`() {
        val nodes = GltfNodes.read(TestPaths.exampleAssets.resolve("models/chassis/turret.glb")).getOrThrow()

        val top = nodes.single { it.name == "socket_top" }
        assertEquals(1, top.index)
        assertEquals(0.42f, top.z, FIXTURE_TOLERANCE, "on top of the turret body, in $top")
        assertEquals(0.5f, top.scaleX, FIXTURE_TOLERANCE, "half scale, in $top")

        val muzzle = nodes.single { it.name == "socket_muzzle" }
        assertEquals(0, muzzle.index)
        assertEquals(0.85f, muzzle.x, FIXTURE_TOLERANCE, "at the end of the barrel, in $muzzle")
        assertEquals(QUARTER_TURN_AXIS, muzzle.qy, FIXTURE_TOLERANCE, "its +Z points the way the barrel does, in $muzzle")
    }

    // ---- fixture -----------------------------------------------------------------------------

    /** A glTF file holding [nodes] and nothing else, written where the reader can open it. */
    private fun gltf(nodes: String?, name: String = "nodes"): Path {
        val body = if (nodes == null) "" else ""","nodes": $nodes"""
        val file = TestPaths.scratch("gltf-nodes-$name").resolve("$name.gltf")
        file.parent.toFile().mkdirs()
        file.writeText("""{"asset": {"version": "2.0"}$body}""")
        return file
    }

    private companion object {
        const val TOLERANCE = 1e-6f

        /** The fox's units are centimetres of fox; a hundredth of one is well inside a bone. */
        const val FOX_TOLERANCE = 0.01f

        /** The chassis is metres, and a socket is placed to the centimetre. */
        const val FIXTURE_TOLERANCE = 1e-4f

        /** `sin(45 degrees)` and `cos(45 degrees)`: a quarter turn about one axis, as a quaternion. */
        val QUARTER_TURN_AXIS = sin(PI / 4).toFloat()
    }
}
