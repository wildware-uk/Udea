package dev.wildware.udea.core.spatial

import com.github.quillraven.fleks.Entity
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.generated.CoreUdeaRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The half of issue #270's first acceptance criterion that is a cost claim: a game asks what is
 * mounted on an entity **without scanning the world**.
 *
 * ## Why the measurement is "how many entities does the answer read", and not a stopwatch
 *
 * An assertion that the right parts come back passes just as well over a full-world scan, so it
 * says nothing about cost. A stopwatch would say something, and would say it differently on a
 * busy machine - this repository has a whole apparatus (`LatencyBudget`, issue #175) built round
 * exactly that problem, and a ratio measured beside a parallel build measures the build.
 *
 * So the quantity measured here is **entity visits**, which is deterministic and is the thing
 * that actually differs. The scan the engine used to make a game write visits every entity that
 * carries an `AttachedTo`:
 *
 * ```
 * family { all(AttachedTo) }.forEach { part -> if (part[AttachedTo].parent == parent) found += part }
 * ```
 *
 * [an answer needs no entity in the world at all] drives that number to a place a scan cannot
 * follow: every entity is taken out of the world after the index is built, and the questions are
 * still answered. A scan has nothing left to walk and answers empty; the index reads no entity
 * either way, which is what "without scanning the world" means. The magnitude of the difference
 * is [MOUNTS] visits against nought, and `BRIEF.md` records the scan being put back and counted.
 *
 * The other half of the claim is a fact about the type rather than a measurement:
 * [AttachmentIndex] holds no `World`, no `Family` and no `NetIdIndex`, so there is no world for
 * a question to reach. [AttachmentIndexTest] is where the answers themselves are checked.
 *
 * ## The cost test does not stand alone, and must not
 *
 * "It still answers after every entity is gone" is the signature of an index - and it is equally
 * the signature of **a cache that is never invalidated**, which is the one failure mode this
 * index must not have, because it is derived and never stored. The two observations are the same
 * observation, so the cost case is deliberately paired with
 * [a part that has gone from the world is gone from the next rebuild] here, and with
 * `AttachmentIndexTest`'s detach and swap cases and `AttachmentDeterminismTest`'s
 * `a rewind past a swap re-derives the index from the restored world` elsewhere. A build-once
 * index passes the cost case on its own and fails all four.
 */
class AttachmentIndexCostTest {

    /**
     * [MOUNTS] parts plus a parent for each of them needs more ids than the kernel's default
     * 2048, so the capacity is stated here. A world this size is the point of the test: a scan
     * of it would be unmistakable, and the default would have quietly capped the measurement at
     * a thousandth of it.
     */
    private val host = GameHost(
        RenderMode.Headless,
        UdeaGameDef(CoreUdeaRegistry, emptyList(), entityCapacity = MOUNTS * 2 + 8),
    )
    private val netIds = host.ctx[CoreModule.NET_IDS]
    private val attachments = host.ctx[CoreModule.ATTACHMENTS]

    /** Every entity made here, so the world can be emptied without asking the world for a list. */
    private val made = ArrayList<Entity>()

    private val roof = ModelNode(index = 3, name = "socket_roof", z = 0.5f)

    private fun entity(mount: AttachedTo? = null): Entity =
        host.world.entity { entity ->
            entity += Transform3D()
            if (mount != null) entity += mount
        }.also(netIds::allocate).also(made::add)

    /**
     * A world of [MOUNTS] mounted parts, of which [CARRIED] are on the one chassis asked about.
     *
     * @return the chassis and its own parts, in ascending [NetId] order.
     */
    private fun crowdedWorld(): Pair<NetId, List<NetId>> {
        val chassis = entity()
        val carried = List(CARRIED) { entity(AttachedTo(netIds.netIdOf(chassis), roof)) }
        var mounts = CARRIED
        while (mounts < MOUNTS) {
            // Each on a chassis of its own, so the index holds many parents rather than one big one.
            val other = entity()
            entity(AttachedTo(netIds.netIdOf(other), roof))
            mounts++
        }
        host.run(1)
        return netIds.netIdOf(chassis) to carried.map(netIds::netIdOf).sorted()
    }

    @Test
    fun `the right parts come back out of a world of thousands of mounts`() {
        val (chassis, carried) = crowdedWorld()

        assertEquals(MOUNTS, attachments.mountCount, "every mount is indexed")
        assertEquals(CARRIED, attachments.childCount(chassis), "the chassis carries its own and no more")
        assertEquals(
            carried,
            (0 until CARRIED).map { attachments.childAt(chassis, it) },
            "its own parts, in NetId order, among ${MOUNTS - CARRIED} other mounts",
        )
    }

    /**
     * The measurement: after the index is built, **every entity is removed from the world** and
     * the same questions are asked again.
     *
     * A question that read even one entity could not answer here. The scan in this class's KDoc
     * reads [MOUNTS] of them and would answer empty; this reads none and answers exactly what it
     * answered a moment ago. That is the cost claim, in a form that cannot quietly pass over a
     * scan - and it is the assertion that goes red when the index is reverted to one.
     *
     * The ids are deliberately **not** freed: a freed id is stale by design and answers nothing,
     * which would make this pass for the wrong reason. The entities are gone; the names are not.
     *
     * **This is the opposite choice from the one the next test makes, and both are deliberate.**
     * Here the id has to stay live, or an empty answer would prove nothing about where the answer
     * came from. There the id is freed, because the point is that the part has genuinely gone from
     * the world - see [a part that has gone from the world is gone from the next rebuild].
     */
    @Test
    fun `an answer needs no entity in the world at all`() {
        val (chassis, carried) = crowdedWorld()
        val socket = attachments.childOf(chassis, roof)
        assertTrue(socket in carried, "the roof socket is answered while the world is whole")

        for (entity in made) host.world -= entity
        assertEquals(0, host.world.numEntities, "the world is empty")

        assertEquals(CARRIED, attachments.childCount(chassis), "the count survives an empty world")
        assertEquals(
            carried,
            (0 until CARRIED).map { attachments.childAt(chassis, it) },
            "and so do the parts themselves",
        )
        assertEquals(socket, attachments.childOf(chassis, roof), "and so does the socket's occupant")

        val visited = ArrayList<NetId>()
        attachments.forEachChild(chassis) { child, _ -> visited += child }
        assertEquals(carried, visited, "forEachChild visits exactly the answer, and nothing else")
    }

    /**
     * The other half of the pair. An index that answered from a cache nobody ever invalidated
     * would pass [an answer needs no entity in the world at all] perfectly, and this is where it
     * dies: a part that has been destroyed is gone from the very next rebuild, because the rebuild
     * starts from nothing and reads the components that are actually there.
     *
     * The same property under a rewind - where the index has to *gain back* a part the future had
     * removed - is `AttachmentDeterminismTest.a rewind past a swap re-derives the index from the
     * restored world`, which is the harder direction and the one a stale cache cannot fake.
     *
     * **The destroyed part's id is freed here, and deliberately not freed in the test above.**
     * Destroying an entity without giving its id back is not something a game does, and this case
     * is about a part that has really gone; [an answer needs no entity in the world at all] keeps
     * its ids live on purpose, because a stale id answers nothing anyway and would let that test
     * pass without saying anything about cost.
     */
    @Test
    fun `a part that has gone from the world is gone from the next rebuild`() {
        val (chassis, carried) = crowdedWorld()
        assertEquals(CARRIED, attachments.childCount(chassis), "all four are mounted to begin with")

        val doomed = carried.first()
        host.world -= checkNotNull(netIds.resolveOrNull(doomed)) { "$doomed should still be live" }
        netIds.free(doomed)
        host.run(1)

        assertEquals(CARRIED - 1, attachments.childCount(chassis), "the destroyed part is still indexed")
        assertEquals(
            carried.drop(1),
            (0 until CARRIED - 1).map { attachments.childAt(chassis, it) },
            "the three that are left, and only those",
        )
    }

    private companion object {
        /** Mounted parts in the world. Big enough that a scan of them would be unmistakable. */
        const val MOUNTS = 20_000

        /** How many of them are on the chassis the questions are about. */
        const val CARRIED = 4
    }
}
