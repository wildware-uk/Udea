package dev.wildware.udea.core.spatial

import com.github.quillraven.fleks.Entity
import dev.wildware.udea.assets.ModelNode
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.generated.CoreUdeaRegistry
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Asking what is mounted **on** an entity, and what is in one of its sockets (issue #270).
 *
 * The questions a game actually asks - swap the part in this socket, blow the parts off a wreck,
 * count what a unit is carrying - each answered from the index `AttachmentSystem` builds as it
 * places the parts, rather than by walking every entity in the world.
 *
 * `AttachmentIndexCostTest` is the other half: that the answer does not come from a walk.
 */
class AttachmentIndexTest {

    private val host = GameHost(RenderMode.Headless, UdeaGameDef(CoreUdeaRegistry, emptyList()))
    private val netIds = host.ctx[CoreModule.NET_IDS]
    private val attachments = host.ctx[CoreModule.ATTACHMENTS]

    private val roof = ModelNode(index = 3, name = "socket_roof", z = 0.5f)
    private val front = ModelNode(index = 7, name = "socket_front", x = 1f)
    private val turretTop = ModelNode(index = 1, name = "socket_top", z = 0.3f)

    private fun entity(transform: Transform3D? = Transform3D(), mount: AttachedTo? = null): Entity =
        host.world.entity { entity ->
            if (transform != null) entity += transform
            if (mount != null) entity += mount
        }.also(netIds::allocate)

    private fun idOf(entity: Entity): NetId = netIds.netIdOf(entity)

    /** Everything mounted on [parent], in the order the index reports it. */
    private fun childrenOf(parent: NetId): List<NetId> =
        (0 until attachments.childCount(parent)).map { attachments.childAt(parent, it) }

    @Test
    fun `the parts mounted on a chassis are its parts and nobody else's`() {
        val chassis = entity()
        val other = entity()
        val turret = entity(mount = AttachedTo(idOf(chassis), roof))
        val plough = entity(mount = AttachedTo(idOf(chassis), front))
        val otherPart = entity(mount = AttachedTo(idOf(other), roof))

        host.run(1)

        assertEquals(2, attachments.childCount(idOf(chassis)), "the chassis carries two parts")
        assertContentEquals(
            listOf(idOf(turret), idOf(plough)).sorted(),
            childrenOf(idOf(chassis)),
            "the chassis's own parts",
        )
        assertContentEquals(listOf(idOf(otherPart)), childrenOf(idOf(other)), "the other unit's part")
        assertEquals(0, attachments.childCount(idOf(turret)), "nothing is mounted on the turret")
    }

    @Test
    fun `the part in a socket is found by the node it is mounted on`() {
        val chassis = entity()
        val turret = entity(mount = AttachedTo(idOf(chassis), roof))
        val plough = entity(mount = AttachedTo(idOf(chassis), front))

        host.run(1)

        assertEquals(idOf(turret), attachments.childOf(idOf(chassis), roof), "the roof socket")
        assertEquals(idOf(plough), attachments.childOf(idOf(chassis), front), "the front socket")
        assertEquals(
            NetId.NONE,
            attachments.childOf(idOf(chassis), ModelNode(index = 9, name = "socket_empty")),
            "a socket nothing is mounted on",
        )
    }

    /**
     * The swap the issue names: hijacking replaces what is in a socket, and the question and the
     * answer are about the socket rather than about whichever part happens to be there.
     */
    @Test
    fun `swapping the part in a socket changes what that socket answers`() {
        val chassis = entity()
        val original = entity(mount = AttachedTo(idOf(chassis), roof))
        host.run(1)
        assertEquals(idOf(original), attachments.childOf(idOf(chassis), roof), "before the swap")

        with(host.world) { original.configure { it -= AttachedTo } }
        val hijacked = entity(mount = AttachedTo(idOf(chassis), roof))
        host.run(1)

        assertEquals(idOf(hijacked), attachments.childOf(idOf(chassis), roof), "after the swap")
        assertContentEquals(listOf(idOf(hijacked)), childrenOf(idOf(chassis)), "only the new part")
    }

    /** Blowing the parts off a wreck: the detached part leaves the index on the next tick. */
    @Test
    fun `a part that is detached is no longer mounted on anything`() {
        val chassis = entity()
        val turret = entity(mount = AttachedTo(idOf(chassis), roof))
        host.run(1)
        assertEquals(1, attachments.childCount(idOf(chassis)), "mounted")

        with(host.world) { turret.configure { it -= AttachedTo } }
        host.run(1)

        assertEquals(0, attachments.childCount(idOf(chassis)), "blown off")
        assertEquals(NetId.NONE, attachments.childOf(idOf(chassis), roof), "the socket is empty")
    }

    /** A gun on a turret on a chassis: each level answers for itself, and only for itself. */
    @Test
    fun `a part mounted on a part belongs to that part and not to the chassis`() {
        val chassis = entity()
        val turret = entity(mount = AttachedTo(idOf(chassis), roof))
        val gun = entity(mount = AttachedTo(idOf(turret), turretTop))

        host.run(1)

        assertContentEquals(listOf(idOf(turret)), childrenOf(idOf(chassis)), "the chassis carries the turret")
        assertContentEquals(listOf(idOf(gun)), childrenOf(idOf(turret)), "the turret carries the gun")
    }

    /**
     * A part with no `Transform3D` is mounted all the same: "what is on this chassis" is not the
     * same question as "what on it can be drawn". The placing half of `AttachmentSystem` is what
     * needs a transform.
     */
    @Test
    fun `a part with no transform is still mounted`() {
        val chassis = entity()
        val marker = entity(transform = null, mount = AttachedTo(idOf(chassis), roof))

        host.run(1)

        assertContentEquals(listOf(idOf(marker)), childrenOf(idOf(chassis)), "the transformless part")
    }

    /**
     * The order is [NetId] order, and deliberately not the family's. Fleks walks a family in
     * entity-id order, and a Fleks entity id is re-minted by a snapshot restore while a [NetId]
     * is rebound exactly as captured - so a caller who took "the first part" from family order
     * would get a different part after a rewind.
     *
     * The two orders have to be made to **disagree**, or this test could not fail: parts created
     * in order get Fleks ids and [NetId]s that ascend together, and an index that did not sort at
     * all would pass. So `second` takes an id reserved before `first` was created - which is what
     * `BlueprintSpawner` does for real, and what `AttachmentSystemTest` uses for the same reason -
     * leaving it **earlier** by [NetId] and **later** in the family.
     */
    @Test
    fun `the parts come back in NetId order and not in the family's`() {
        val chassis = entity()
        val reserved = netIds.reserve()
        val first = entity(mount = AttachedTo(idOf(chassis), roof))
        val second = host.world.entity {
            it += Transform3D()
            it += AttachedTo(idOf(chassis), front)
        }
        netIds.attach(second, reserved)

        assertTrue(reserved < idOf(first), "the reserved id is the earlier NetId")
        assertTrue(first.id < second.id, "and `first` is the earlier entity, so the orders disagree")

        host.run(1)

        assertContentEquals(listOf(reserved, idOf(first)), childrenOf(idOf(chassis)), "ascending NetId")
    }

    /** Which socket each part is on travels with it, so a caller can answer both at once. */
    @Test
    fun `each reported part carries the node it is mounted on`() {
        val chassis = entity()
        val turret = entity(mount = AttachedTo(idOf(chassis), roof))
        val plough = entity(mount = AttachedTo(idOf(chassis), front))

        host.run(1)

        val seen = HashMap<NetId, Int>()
        attachments.forEachChild(idOf(chassis)) { child, node -> seen[child] = node }
        assertEquals(mapOf(idOf(turret) to roof.index, idOf(plough) to front.index), seen, "part to socket")
        assertEquals(
            (0 until 2).map { attachments.nodeAt(idOf(chassis), it) }.toSet(),
            setOf(roof.index, front.index),
            "nodeAt agrees with forEachChild",
        )
    }

    /**
     * A destroyed parent's id is stale, and a stale id answers empty rather than answering with
     * whatever has since been given its index. The generation is what separates the two, which is
     * the whole of spec 5's entity identity contract.
     */
    @Test
    fun `a stale parent id answers nothing even when its index has been handed out again`() {
        val chassis = entity()
        val stale = idOf(chassis)
        entity(mount = AttachedTo(stale, roof))
        host.run(1)
        assertEquals(1, attachments.childCount(stale), "while it is alive")

        netIds.free(stale)
        host.world -= chassis
        // Fill the index space back to the freed slot, so `reborn` really does reuse it.
        var reborn = netIds.allocate(host.world.entity { it += Transform3D() })
        while (reborn.index != stale.index) reborn = netIds.allocate(host.world.entity { it += Transform3D() })
        entity(mount = AttachedTo(reborn, roof))
        host.run(1)

        assertTrue(stale.index == reborn.index, "the same index, by construction")
        assertTrue(stale != reborn, "a different generation")
        assertEquals(0, attachments.childCount(stale), "the stale id carries nothing")
        assertEquals(1, attachments.childCount(reborn), "the new occupant carries its own part")
    }

    @Test
    fun `an id nothing is mounted on answers nothing rather than throwing`() {
        val lonely = entity()
        host.run(1)

        assertEquals(0, attachments.childCount(lonely.let(::idOf)), "no parts")
        assertEquals(0, attachments.childCount(NetId.NONE), "NetId.NONE")
        assertEquals(NetId.NONE, attachments.childOf(NetId.NONE, roof), "NetId.NONE has no sockets")
        assertFailsWith<IndexOutOfBoundsException>("there is no part to return") {
            attachments.childAt(idOf(lonely), 0)
        }
    }

    /**
     * An empty world is a specific state, not a neutral one: before the first tick the index has
     * never been built, and it has to say "nothing" rather than read an array that was never
     * written.
     *
     * This one passes whether or not the index's two "never" sentinels collide, and the case
     * below is the one that tells them apart. Both are kept: this is the state a game meets, that
     * is the state a refactor breaks.
     */
    @Test
    fun `before the first tick the index answers nothing`() {
        val chassis = entity()
        entity(mount = AttachedTo(idOf(chassis), roof))

        assertEquals(0, attachments.mountCount, "no rebuild has run")
        assertEquals(0, attachments.childCount(idOf(chassis)), "and so nothing is mounted yet")
    }

    /**
     * An index that has recorded entries but **published** no rebuild answers nothing - and this
     * is the case that can tell [AttachmentIndex]'s two "never"s apart.
     *
     * There are two of them and they must not be the same number. A parent index no rebuild has
     * touched reads **zero**, which costs nothing because the arrays are zero-filled and no
     * rebuild is ever epoch zero. "No rebuild has been published" is a different fact and is
     * `-1`. They were both zero when this class was written, and every answer was still correct,
     * because two accidents lined up: the query fell through into the never-written arrays, and
     * what it found there was a bucket count of zero.
     *
     * So the discriminator has to put a **non-zero** count behind a never-published index, which
     * is what recording an entry with no `beginRebuild` and no `endRebuild` does. The parent is
     * deliberately `NetId.of(0, 0)`, whose raw word is `0`: any other parent would be saved by the
     * `parentRaw` comparison against a zero-filled array, and the test would pass while saying
     * nothing.
     *
     * With the sentinels collided this reports **one** part mounted, and hands back a `NetId` read
     * out of an array nothing ever wrote. Today no caller can reach that state - `AttachmentSystem`
     * always brackets its entries - so this pins a trap rather than a live defect. It is worth
     * pinning because the trap is invisible: restore the collision and nothing else in the suite
     * objects, and the failure would surface in whatever later change first seeds an index outside
     * a rebuild.
     *
     * The name carries no comma on purpose. A backtick-quoted name containing one compiles on the
     * JVM and fails Kotlin/Native with `Name contains illegal characters: ","`, which is what
     * `:udea-core:compileTestKotlinIosArm64` caught here - `udea-core` has iOS targets (issue
     * #215), so a `commonTest` name is compiled for them on this Linux box even though the tests
     * cannot be run there.
     */
    @Test
    fun `an index that has published no rebuild answers nothing even with entries recorded in it`() {
        val fresh = AttachmentIndex()
        val parent = NetId.of(0, 0)
        val child = NetId.of(1, 0)
        fresh.add(child, parent, node = roof.index)

        assertEquals(0, fresh.mountCount, "nothing has been published")
        assertEquals(0, fresh.childCount(parent), "an unpublished entry was answered with")
        assertEquals(NetId.NONE, fresh.childOf(parent, roof.index), "and so was its socket")
    }
}
