package dev.wildware.udea.core.spatial

import com.github.quillraven.fleks.Entity
import dev.wildware.udea.assets.ModelNode
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.DivergenceReport
import dev.wildware.udea.core.snapshot.SnapshotService
import dev.wildware.udea.core.snapshot.WorldHasher
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.generated.CoreUdeaRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Mounting, swapping and detaching a part are deterministic: two independent runs of the same
 * script agree tick for tick, and a run resumed from a snapshot taken before the swap produces
 * the same world as the one it was captured from (issue #260).
 *
 * This is the property `AttachmentSystem` is built for and the one a replay depends on. Two
 * *independently built* worlds rather than one world replayed into itself, because a self-replay
 * shares its object graph with itself: it cannot see an answer derived from an address, an
 * identity hash or a `HashMap` iterated inside a system, and all three would sail through it.
 * `SnapshotEquivalenceTest` makes the same argument at length for the kernel as a whole.
 */
class AttachmentDeterminismTest {

    /** A whole game with a chassis, a turret on its roof, a gun on the turret and a spare part. */
    private class Rig {
        val host = GameHost(RenderMode.Headless, UdeaGameDef(CoreUdeaRegistry, emptyList()))
        val netIds = host.ctx[CoreModule.NET_IDS]
        val registry = ComponentRegistry(listOf(Transform3D.snapshotType(), AttachedTo.snapshotType()))
        val service = SnapshotService(registry, host.world, host.ctx, netIds)
        val attachments = host.ctx[CoreModule.ATTACHMENTS]

        /** Everything mounted on [parent] as the index has it, in the order it reports. */
        fun childrenOf(parent: Entity): List<NetId> {
            val id = netIdOf(parent)
            return (0 until attachments.childCount(id)).map { attachments.childAt(id, it) }
        }

        val chassis: Entity = spawn(Transform3D())
        val turret: Entity = spawn(Transform3D())
        val gun: Entity = spawn(Transform3D())
        val spare: Entity = spawn(Transform3D())

        private fun spawn(transform: Transform3D): Entity =
            host.world.entity { it += transform }.also(netIds::allocate)

        fun netIdOf(entity: Entity): NetId = netIds.netIdOf(entity)

        fun transformOf(entity: Entity): Transform3D = with(host.world) { entity[Transform3D] }

        fun mount(part: Entity, parent: Entity, node: ModelNode) {
            with(host.world) { part.configure { it += AttachedTo(netIdOf(parent), node) } }
        }

        fun unmount(part: Entity) {
            with(host.world) { part.configure { it -= AttachedTo } }
        }

        fun hash(into: WorldSnapshot): Long {
            service.captureInto(into)
            return WorldHasher.hash(into)
        }
    }

    /**
     * What happens on [tick]: the chassis drives and turns, the assembly is built, the roof part
     * is swapped for the spare, and the gun is blown off. A pure function of the tick number -
     * no clock, no random - so two rigs and a resumed run all do the same thing.
     */
    private fun script(rig: Rig, tick: Int) {
        rig.transformOf(rig.chassis).apply {
            x = tick * 0.25f
            y = -tick * 0.125f
            rotationZ = tick * 0.05f
        }
        when (tick) {
            BUILD_TICK -> {
                rig.mount(rig.turret, rig.chassis, ROOF)
                rig.mount(rig.gun, rig.turret, TOP)
            }
            SWAP_TICK -> {
                rig.unmount(rig.turret)
                rig.mount(rig.spare, rig.chassis, ROOF)
            }
            DETACH_TICK -> rig.unmount(rig.gun)
        }
    }

    @Test
    fun `two independent runs of a mount, a swap and a detach agree tick for tick`() {
        val first = Rig()
        val second = Rig()
        val left = first.service.newSnapshot()
        val right = second.service.newSnapshot()
        val seen = HashSet<Long>()

        for (tick in 0 until TICKS) {
            script(first, tick)
            script(second, tick)
            first.host.run(1)
            second.host.run(1)
            val hash = first.hash(left)
            if (hash != second.hash(right)) {
                fail(
                    "two runs of the same mount script diverged at tick $tick.\n" +
                        DivergenceReport.compare(left.tick, left, right).describe(),
                )
            }
            seen += hash
        }

        // A world that never changed would agree with itself for ever and prove nothing.
        assertTrue(seen.size > TICKS / 2, "the world barely moved: only ${seen.size} distinct hashes in $TICKS ticks")
    }

    @Test
    fun `a run resumed from a snapshot taken before the swap reproduces the world it was captured from`() {
        val played = Rig()
        val recorded = ArrayList<Long>(TICKS)
        val scratch = played.service.newSnapshot()
        lateinit var resumePoint: WorldSnapshot

        for (tick in 0 until TICKS) {
            if (tick == RESUME_TICK) resumePoint = played.service.capture()
            script(played, tick)
            played.host.run(1)
            recorded += played.hash(scratch)
        }

        // Back to the tick before the swap, and forward again through the same script.
        played.service.applyNow(resumePoint)
        val again = ArrayList<Long>(TICKS - RESUME_TICK)
        for (tick in RESUME_TICK until TICKS) {
            script(played, tick)
            played.host.run(1)
            again += played.hash(scratch)
        }

        assertEquals(recorded.subList(RESUME_TICK, TICKS), again, "the resumed run took a different path")
    }

    /**
     * The reverse index is **derived, never stored** (issue #270), and this is what that buys: a
     * rewind past a swap puts the original part back on the socket in the index too, because the
     * index after the restore is built from the restored components rather than carried across it.
     *
     * An index that were state would answer with the future's parts here - the spare that the
     * rewind un-mounted - and nothing else in the suite would notice, because every transform
     * would still be right.
     */
    @Test
    fun `a rewind past a swap re-derives the index from the restored world`() {
        val rig = Rig()
        lateinit var beforeTheSwap: WorldSnapshot
        for (tick in 0 until TICKS) {
            if (tick == RESUME_TICK) beforeTheSwap = rig.service.capture()
            script(rig, tick)
            rig.host.run(1)
        }
        assertEquals(listOf(rig.netIdOf(rig.spare)), rig.childrenOf(rig.chassis), "the spare is on the roof")

        rig.service.applyNow(beforeTheSwap)
        rig.host.run(1)

        assertEquals(
            listOf(rig.netIdOf(rig.turret)),
            rig.childrenOf(rig.chassis),
            "after the rewind the chassis carries the turret again, not the spare the future mounted",
        )
        assertEquals(
            listOf(rig.netIdOf(rig.gun)),
            rig.childrenOf(rig.turret),
            "and the gun the future blew off is back on the turret",
        )
    }

    private companion object {
        const val TICKS = 90

        /** The tick the assembly is built on, the one it is swapped on, the one the gun comes off. */
        const val BUILD_TICK = 5
        const val SWAP_TICK = 40
        const val DETACH_TICK = 60

        /** Before the swap and after the build: what the resumed run has to carry across. */
        const val RESUME_TICK = 20

        val ROOF = ModelNode(index = 3, name = "socket_roof", x = 1f, z = 0.5f)
        val TOP = ModelNode(index = 1, name = "socket_top", z = 0.3f)
    }
}
