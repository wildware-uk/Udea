package dev.wildware.udea.assets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Hot reload, and the invariant issue #64 depends on: a value-only reload swaps data at the same
 * slots, so every [AssetIndex] already recorded - in a component, in a `Ref`, in a snapshot taken
 * before the reload - still names the right asset and now reads the *new* value.
 */
class HotReloadTest {

    private val fireballId = AssetId("ability/fireball")
    private val orcId = AssetId("character/orc_elite")

    private fun fireball(damage: Float) =
        Ability(fireballId, uClass("moba.FireballExec"), params = mapOf("damage" to AssetValue.FloatValue(damage)))

    private val orc = Blueprint(orcId)

    @Test
    fun `a value swap keeps the slot, so a cached reference reads the new data`() {
        val registry = registryOf(orc, fireball(30F))
        val ref = reference<Ability>("ability/fireball")
        val slotBefore = registry.indexOf(fireballId)
        assertSame(registry.find(fireballId), registry[ref])

        val result = registry.applyDelta(GraphDelta(listOf(ChangedAsset(fireballId, fireball(40F)))))

        assertIs<DeltaResult.Applied>(result)
        assertEquals(setOf(fireballId), result.changedIds)
        assertEquals(slotBefore, registry.indexOf(fireballId), "the reload moved the asset's slot")
        assertEquals(AssetValue.FloatValue(40F), registry[ref].params["damage"])
        assertEquals(AssetValue.FloatValue(40F), (registry.at(slotBefore) as Ability).params["damage"])
    }

    @Test
    fun `an untouched asset is untouched`() {
        val registry = registryOf(orc, fireball(30F))

        registry.applyDelta(GraphDelta(listOf(ChangedAsset(fireballId, fireball(40F)))))

        assertSame(orc, registry.find(orcId))
    }

    @Test
    fun `an applied delta bumps the version and reports what changed`() {
        val registry = registryOf(orc, fireball(30F))
        assertEquals(0, registry.current())

        registry.applyDelta(GraphDelta(listOf(ChangedAsset(fireballId, fireball(40F)))))

        assertEquals(1, registry.current())
        assertEquals(AssetChangeSet(setOf(fireballId), requiresRestart = false), registry.changesSince(0))
        assertEquals(AssetChangeSet.None, registry.changesSince(1))
    }

    @Test
    fun `listeners are told what changed`() {
        val registry = registryOf(orc, fireball(30F))
        val seen = mutableListOf<AssetChangeSet>()
        registry.addListener { seen += it }

        registry.applyDelta(GraphDelta(listOf(ChangedAsset(fireballId, fireball(40F)))))

        assertEquals(listOf(AssetChangeSet(setOf(fireballId), requiresRestart = false)), seen)
    }

    @Test
    fun `a removed listener is not told`() {
        val registry = registryOf(fireball(30F))
        val seen = mutableListOf<AssetChangeSet>()
        val listener = AssetGraphListener { seen += it }
        registry.addListener(listener)

        assertTrue(registry.removeListener(listener))
        registry.applyDelta(GraphDelta(listOf(ChangedAsset(fireballId, fireball(40F)))))

        assertEquals(emptyList(), seen)
    }

    @Test
    fun `a listener that unregisters itself while being notified does not break the notification`() {
        val registry = registryOf(fireball(30F))
        val seen = mutableListOf<AssetChangeSet>()
        lateinit var listener: AssetGraphListener
        listener = AssetGraphListener {
            seen += it
            registry.removeListener(listener)
        }
        registry.addListener(listener)

        registry.applyDelta(GraphDelta(listOf(ChangedAsset(fireballId, fireball(40F)))))
        registry.applyDelta(GraphDelta(listOf(ChangedAsset(fireballId, fireball(50F)))))

        assertEquals(1, seen.size)
    }

    @Test
    fun `an added asset is a shape change and nothing is applied`() {
        val registry = registryOf(fireball(30F))
        val newcomer = Ability(AssetId("ability/frostbolt"), uClass("moba.FrostboltExec"))
        val seen = mutableListOf<AssetChangeSet>()
        registry.addListener { seen += it }

        val result = registry.applyDelta(
            GraphDelta(
                listOf(
                    ChangedAsset(fireballId, fireball(40F)),
                    ChangedAsset(newcomer.id, newcomer),
                ),
            ),
        )

        val refused = assertIs<DeltaResult.Refused>(result)
        assertEquals(
            listOf(ShapeChange(newcomer.id, RestartReason.AssetAdded)),
            refused.classification.changes,
        )
        assertEquals("reload_requires_restart", DeltaClassification.RequiresRestart.CODE)
        // The whole delta is refused: the hot-swappable half must not land on its own.
        assertEquals(AssetValue.FloatValue(30F), (registry.find(fireballId) as Ability).params["damage"])
        assertEquals(0, registry.current())
        assertEquals(emptyList(), seen)
        assertEquals(1, registry.size)
    }

    @Test
    fun `a removed asset is a shape change`() {
        val registry = registryOf(orc, fireball(30F))

        val result = registry.applyDelta(GraphDelta(listOf(ChangedAsset(fireballId, null))))

        val refused = assertIs<DeltaResult.Refused>(result)
        assertEquals(listOf(ShapeChange(fireballId, RestartReason.AssetRemoved)), refused.classification.changes)
        assertEquals(Ability::class, registry.find(fireballId)!!::class, "the asset was removed anyway")
    }

    @Test
    fun `an asset that changes kind is a shape change`() {
        // Every Ref to this id type-checked against Ability, and every one of those checks would
        // now be wrong - which is precisely what a cached slot cannot notice.
        val registry = registryOf(fireball(30F))

        val result = registry.applyDelta(
            GraphDelta(listOf(ChangedAsset(fireballId, Blueprint(fireballId)))),
        )

        val refused = assertIs<DeltaResult.Refused>(result)
        assertEquals(listOf(ShapeChange(fireballId, RestartReason.KindChanged)), refused.classification.changes)
    }

    @Test
    fun `deleting something already absent is not a change at all`() {
        val registry = registryOf(fireball(30F))
        val seen = mutableListOf<AssetChangeSet>()
        registry.addListener { seen += it }

        val result = registry.applyDelta(GraphDelta(listOf(ChangedAsset(AssetId("ability/gone"), null))))

        val applied = assertIs<DeltaResult.Applied>(result)
        assertEquals(emptySet(), applied.changedIds)
        assertEquals(0, registry.current(), "a version was burned for a change nobody can observe")
        assertEquals(emptyList(), seen)
    }

    @Test
    fun `classification is a property of the delta and of the graph it is applied to`() {
        val frostbolt = Ability(AssetId("ability/frostbolt"), uClass("moba.FrostboltExec"))
        val delta = GraphDelta(listOf(ChangedAsset(frostbolt.id, frostbolt)))

        assertIs<DeltaClassification.RequiresRestart>(registryOf(fireball(30F)).classify(delta))
        assertIs<DeltaClassification.HotSwappable>(registryOf(fireball(30F), frostbolt).classify(delta))
    }

    @Test
    fun `a delta entry may not rename the asset it changes`() {
        assertFailsWith<IllegalArgumentException> {
            ChangedAsset(fireballId, Ability(AssetId("ability/frostbolt"), uClass("moba.Exec")))
        }
    }

    @Test
    fun `a delta that names one asset twice is refused at construction`() {
        assertFailsWith<IllegalArgumentException> {
            GraphDelta(listOf(ChangedAsset(fireballId, fireball(40F)), ChangedAsset(fireballId, fireball(50F))))
        }
    }

    @Test
    fun `an empty delta is not a delta`() {
        assertFailsWith<IllegalArgumentException> { GraphDelta(emptyList()) }
    }
}
