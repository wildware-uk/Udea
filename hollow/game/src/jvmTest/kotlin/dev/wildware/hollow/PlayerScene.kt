package dev.wildware.hollow

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.physics.PhysicsBody
import dev.wildware.udea.core.spatial.Animator
import dev.wildware.udea.core.spatial.Transform3D
import dev.wildware.udea.render.input.IntentSource
import dev.wildware.udea.render.input.IntentState

/**
 * A headless Hollow game with one character in it, driven by an axis a test writes.
 *
 * The whole game: the real `HollowGame` definition, the real Box2D solver, the real four systems,
 * and the same `IntentSource` seam a keyboard and a datagram are wired through. Nothing here is a
 * stand-in for a part of the game - what a test drives is what a player drives.
 *
 * Built over an **empty** level rather than the bundled clearing, so a test places the one rock it
 * wants to walk into and knows exactly where it is, and with no fox waves unless a test asks for
 * them, so the only creatures in it are the ones a test placed (issue #251). `ClearingLevelTest` is what covers the real
 * clearing loading.
 */
internal class PlayerScene(
    /** The waves this game sends. None by default: a test about a player wants no foxes in it. */
    waves: FoxWaves? = null,
) : AutoCloseable {

    private val opened = HollowGame.build(RenderMode.Headless, level = ByteArray(0), waves = waves)

    val host: GameHost get() = opened.host

    val world: World get() = host.world

    val netIds: NetIdIndex get() = host.ctx[CoreModule.NET_IDS]

    /** What the character is asked for this tick. Written by a test, read by the real sampler. */
    var moveX: Float = 0f
    var moveY: Float = 0f
    var running: Boolean = false

    init {
        // The seam. `IntentSampleSystem` calls this once a tick at `SimPhase.Intent`, exactly as it
        // calls a `DeviceIntent` over a keyboard or the server's source over a datagram.
        host.ctx[IntentState.KEY].source = IntentSource { into ->
            into.setAxis(HollowControls.MOVE_AXIS, moveX, moveY)
            into.setPressed(HollowControls.RUN_ACTION, running)
        }
    }

    /** Puts a character at ([x], [y]) and hands back its id. */
    fun spawn(x: Float = 0f, y: Float = 0f): NetId = Player.spawn(world, netIds, x, y)

    /** Puts [prop] at ([x], [y]) at [scale], as the clearing's props stand. */
    fun prop(prop: Prop, x: Float, y: Float, scale: Float = 1f): NetId {
        val entity = world.entity {
            it += Transform3D(x = x, y = y, scaleX = scale, scaleY = scale, scaleZ = scale)
            it += Scenery(prop)
        }
        return netIds.allocate(entity)
    }

    fun run(ticks: Int) {
        host.run(ticks)
    }

    val tick: Tick get() = host.tick

    fun transformOf(id: NetId): Transform3D = with(world) { entity(id)[Transform3D] }

    fun bodyOf(id: NetId): PhysicsBody = with(world) { entity(id)[PhysicsBody] }

    fun animatorOf(id: NetId): Animator = with(world) { entity(id)[Animator] }

    fun playerOf(id: NetId): Player = with(world) { entity(id)[Player] }

    fun foxOf(id: NetId): Fox = with(world) { entity(id)[Fox] }

    private fun entity(id: NetId) = checkNotNull(netIds.resolveOrNull(id)) { "$id is not live" }

    override fun close() {
        opened.close()
    }
}
