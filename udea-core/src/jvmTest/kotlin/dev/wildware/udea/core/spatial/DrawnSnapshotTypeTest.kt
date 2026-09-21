package dev.wildware.udea.core.spatial

import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.AssetRegistry
import dev.wildware.udea.assets.AssetTypeMismatchException
import dev.wildware.udea.assets.Model
import dev.wildware.udea.assets.ResPath
import dev.wildware.udea.assets.SoundCue
import dev.wildware.udea.assets.UnknownAssetException
import dev.wildware.udea.assets.reference
import dev.wildware.udea.core.fixtures.ArrayFieldStore
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.rng.DefaultRngService
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.SnapshotService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `Drawn` names a model, reaches a client and survives a rewind (issue #270).
 *
 * The point of the component is that the *simulation* says what a spawned part looks like, so it
 * has to be the kind of thing that goes on a wire and into a snapshot. A `Drawn` that captured
 * but was not sent would leave a client drawing nothing where the server spawned a turret; one
 * that was sent but not captured would leave a rewind resurrecting an assembly with no art, which
 * is the cost `net-components.lock`'s `CharacterView` note measured for real.
 */
class DrawnSnapshotTypeTest {

    private val ctx = testGameContext(seed = 13L) { rng = DefaultRngService(13L) }
    private val world: World = configureWorld { injectables { gameContext(ctx) } }
    private val netIds = NetIdIndex(capacity = 8, entityCapacity = 8)
    private val registry = ComponentRegistry(listOf(Drawn.snapshotType()))
    private val service = SnapshotService(registry, world, ctx, netIds)

    private val chassis = Model(AssetId("models/chassis"), ResPath("models/chassis/chassis.glb"))
    private val turret = Model(AssetId("models/turret"), ResPath("models/turret/turret.glb"))

    private val engineNoise = SoundCue(AssetId("sound/engine"), listOf(ResPath("sound/engine.ogg")))

    /**
     * Two models and a sound. Two models so the slots are different numbers and a mix-up would
     * show; the sound so a reference of the wrong kind has something real to point at.
     */
    private val assets = AssetRegistry(arrayOf(chassis, turret, engineNoise), byteArrayOf())

    @Test
    fun `the model an entity draws comes back from a snapshot`() {
        val entity = world.entity { it += Drawn(reference<Model>("models/turret"), assets) }
        netIds.allocate(entity)
        val slot = with(world) { entity[Drawn].model }
        val captured = service.capture()

        with(world) { entity[Drawn].model = Drawn.NONE }
        service.applyNow(captured)

        assertEquals(slot, with(world) { entity[Drawn].model }, "the slot did not survive the rewind")
        assertEquals(assets.indexOf(turret.id).value, slot, "and it is the turret's slot")
    }

    /**
     * A `Drawn` a client never receives is a part a client cannot draw, which is the whole reason
     * the component exists rather than each game inventing one.
     */
    @Test
    fun `the model an entity draws is sent to clients, not only snapshotted`() {
        val replicator = registry.typeAt(0).replicator
        assertEquals(listOf("model"), replicator.fieldNames, "one lowered field, named `model`")
        assertTrue(MaskOps.test(replicator.netMask, 0), "`model` would not be sent to a client")
    }

    /** The `fieldNames[i]` == mask bit *i* invariant, for the one field there is. */
    @Test
    fun `changing the model sets the bit fieldNames puts it at`() {
        val replicator = DrawnReplicator
        val store = ArrayFieldStore(slotCount = 2, fieldCount = replicator.fieldNames.size)
        replicator.capture(Drawn.at(assets.indexOf(chassis.id)), store, 0)
        replicator.capture(Drawn.at(assets.indexOf(turret.id)), store, 1)

        assertEquals(MaskOps.single(replicator.fieldNames.indexOf("model")), replicator.diff(store, 0, 1))
    }

    /**
     * The typed constructor is where a bad reference fails, and it fails naming the asset - not
     * at the first frame that tried to draw it, by which time the entity is a mystery.
     */
    @Test
    fun `a reference that does not name a model is refused where it is written`() {
        // The id is real, so this is a kind mismatch and not a missing asset: exactly the mistake
        // a `Ref` carries a type token to catch.
        val wrongKind = assertFailsWith<AssetTypeMismatchException> {
            Drawn(reference<Model>("sound/engine"), assets)
        }
        assertTrue("sound/engine" in wrongKind.message.orEmpty(), "it names the asset: ${wrongKind.message}")

        assertFailsWith<UnknownAssetException>("an id no graph has") {
            Drawn(reference<Model>("models/nothing"), assets)
        }
    }

    /** A `Drawn` that names nothing is empty, and says so rather than naming slot -1 as a model. */
    @Test
    fun `a Drawn with no model is empty`() {
        assertTrue(Drawn().isEmpty(), "the default")
        assertTrue(Drawn(reference<Model>("models/turret"), assets).also { it.show(null) }.isEmpty(), "cleared")
        assertTrue(!Drawn(reference<Model>("models/turret"), assets).isEmpty(), "and a named one is not")
    }
}
