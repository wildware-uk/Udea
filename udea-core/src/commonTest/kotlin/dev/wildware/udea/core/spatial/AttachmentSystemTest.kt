package dev.wildware.udea.core.spatial

import com.github.quillraven.fleks.Entity
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.generated.CoreUdeaRegistry
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Parts mounted on the named nodes of a parent's model follow it, including a part mounted on
 * another part's socket (issue #260).
 *
 * Every expected number here is arithmetic anybody can redo: the chassis stands at `(10, 0, 0)`
 * turned a quarter turn to the left, so a socket a metre along the chassis's own +X is a metre
 * along the world's +Y from it.
 */
class AttachmentSystemTest {

    private val host = GameHost(RenderMode.Headless, UdeaGameDef(CoreUdeaRegistry, emptyList()))
    private val netIds = host.ctx[CoreModule.NET_IDS]

    /** A socket a metre forward and half a metre up, facing the way the model does. */
    private val roof = ModelNode(index = 3, name = "socket_roof", x = 1f, y = 0f, z = 0.5f)

    /** A socket on the part mounted in [roof]: a third of a metre above its own origin. */
    private val turretTop = ModelNode(index = 1, name = "socket_top", x = 0f, y = 0f, z = 0.3f)

    private fun entity(transform: Transform3D = Transform3D(), mount: AttachedTo? = null): Entity =
        host.world.entity { entity ->
            entity += transform
            if (mount != null) entity += mount
        }.also(netIds::allocate)

    private fun netIdOf(entity: Entity): NetId = netIds.netIdOf(entity)

    private fun transformOf(entity: Entity): Transform3D = with(host.world) { entity[Transform3D] }

    private fun assertAt(x: Float, y: Float, z: Float, entity: Entity, what: String) {
        val at = transformOf(entity)
        assertEquals(x, at.x, TOLERANCE, "$what x, in $at")
        assertEquals(y, at.y, TOLERANCE, "$what y, in $at")
        assertEquals(z, at.z, TOLERANCE, "$what z, in $at")
    }

    @Test
    fun `a part mounted on a socket rides the parent as it moves and turns`() {
        val chassis = entity(Transform3D(x = 10f, y = 0f, z = 0f, rotationZ = QUARTER_TURN))
        val module = entity(mount = AttachedTo(netIdOf(chassis), roof))

        host.run(1)

        // The chassis faces +Y, so its own +X socket at (1, 0, 0.5) is a metre along the world's +Y.
        assertAt(10f, 1f, 0.5f, module, "the module on the roof socket")
        assertEquals(QUARTER_TURN, transformOf(module).rotationZ, TOLERANCE, "it faces the way the chassis does")

        transformOf(chassis).apply {
            x = -4f
            y = 2f
            rotationZ = 0f
        }
        host.run(1)

        assertAt(-3f, 2f, 0.5f, module, "the module after the chassis drove and turned back")
        assertEquals(0f, transformOf(module).rotationZ, TOLERANCE, "it turned with the chassis")
    }

    @Test
    fun `five modules on five sockets each sit where their own socket is`() {
        val chassis = entity(Transform3D(x = 0f, y = 0f, z = 0f))
        val sockets = listOf(
            ModelNode(0, "socket_roof", x = 0f, y = 0f, z = 1f),
            ModelNode(1, "socket_front", x = 1f, y = 0f, z = 0f),
            ModelNode(2, "socket_rear", x = -1f, y = 0f, z = 0f),
            ModelNode(3, "socket_left", x = 0f, y = 1f, z = 0f),
            ModelNode(4, "socket_right", x = 0f, y = -1f, z = 0f),
        )
        val modules = sockets.map { entity(mount = AttachedTo(netIdOf(chassis), it)) }

        host.run(1)

        for ((socket, module) in sockets.zip(modules)) {
            assertAt(socket.x, socket.y, socket.z, module, "the module on ${socket.name}")
        }
    }

    @Test
    fun `a part mounted on another part's socket lands two levels down`() {
        val chassis = entity(Transform3D(x = 10f, rotationZ = QUARTER_TURN))
        val turret = entity(mount = AttachedTo(netIdOf(chassis), roof))
        val gun = entity(mount = AttachedTo(netIdOf(turret), turretTop))

        host.run(1)

        // The turret is at (10, 1, 0.5); the gun is 0.3 above it, whichever way either faces.
        assertAt(10f, 1f, 0.8f, gun, "the gun on the turret's own socket")

        transformOf(chassis).z = 4f
        host.run(1)

        assertAt(10f, 1f, 4.8f, gun, "the gun after the chassis was lifted")
    }

    /**
     * The family iterates in entity order, so mounting the gun *before* the turret exists is the
     * case where a child would otherwise be placed from last tick's parent.
     */
    @Test
    fun `a chain is right on the tick it is built whatever order the parts were made in`() {
        val chassis = entity(Transform3D(x = 10f, rotationZ = QUARTER_TURN))
        val turretId = netIds.reserve()
        val gun = entity(mount = AttachedTo(turretId, turretTop))
        val turret = host.world.entity { it += Transform3D(); it += AttachedTo(netIdOf(chassis), roof) }
        netIds.attach(turret, turretId)

        host.run(1)

        assertAt(10f, 1f, 0.8f, gun, "the gun, made before the turret it hangs off")
    }

    @Test
    fun `a socket that faces out of the hull turns the part with it`() {
        // A quarter turn about Y: the socket's own +Z points along the model's +X.
        val flank = ModelNode(index = 2, name = "socket_flank", y = 1f, qy = SIN_EIGHTH, qw = COS_EIGHTH)
        val chassis = entity(Transform3D())
        val module = entity(mount = AttachedTo(netIdOf(chassis), flank, offsetZ = 2f))

        host.run(1)

        // The offset is along the socket's +Z, which the socket's own turn points along the world's +X.
        assertAt(2f, 1f, 0f, module, "a module pushed out along a turned socket's own +Z")
    }

    @Test
    fun `a detached part stays where it was and becomes a free entity`() {
        val chassis = entity(Transform3D(x = 10f, rotationZ = QUARTER_TURN))
        val module = entity(mount = AttachedTo(netIdOf(chassis), roof))
        host.run(1)

        with(host.world) { module.configure { it -= AttachedTo } }
        transformOf(chassis).x = 40f
        host.run(1)

        assertAt(10f, 1f, 0.5f, module, "the detached module")
        assertTrue(with(host.world) { module hasNo AttachedTo }, "it is no longer mounted")
    }

    @Test
    fun `a part whose parent is gone keeps its last transform and its mount`() {
        val chassis = entity(Transform3D(x = 10f, rotationZ = QUARTER_TURN))
        val module = entity(mount = AttachedTo(netIdOf(chassis), roof))
        host.run(1)

        netIds.free(netIdOf(chassis))
        host.world -= chassis
        host.run(1)

        assertAt(10f, 1f, 0.5f, module, "the module whose chassis was destroyed")
        assertTrue(with(host.world) { module has AttachedTo }, "the mount is the game's to remove, not the system's")
    }

    @Test
    fun `swapping the part in a socket puts the new one where the old one was`() {
        val chassis = entity(Transform3D(x = 10f, rotationZ = QUARTER_TURN))
        val first = entity(mount = AttachedTo(netIdOf(chassis), roof))
        host.run(1)

        with(host.world) { first.configure { it -= AttachedTo } }
        val second = entity(mount = AttachedTo(netIdOf(chassis), roof))
        host.run(1)

        assertAt(10f, 1f, 0.5f, second, "the part swapped into the roof socket")
    }

    @Test
    fun `two parts mounted on each other fail loudly rather than recursing for ever`() {
        val first = entity(Transform3D())
        val second = entity(Transform3D())
        with(host.world) {
            first.configure { it += AttachedTo(netIdOf(second), roof) }
            second.configure { it += AttachedTo(netIdOf(first), roof) }
        }

        val failure = assertFailsWith<IllegalStateException> { host.run(1) }

        assertTrue(
            failure.message.orEmpty().contains("mounted on itself"),
            "the message should name the cycle, was: ${failure.message}",
        )
    }

    private companion object {
        const val TOLERANCE = 1e-4f
        val QUARTER_TURN = (PI / 2.0).toFloat()

        /** A quarter turn as a quaternion about one axis: `sin(45°)`, `cos(45°)`. */
        val SIN_EIGHTH = kotlin.math.sin(PI / 4.0).toFloat()
        val COS_EIGHTH = kotlin.math.cos(PI / 4.0).toFloat()
    }
}
