package dev.wildware.moba.level

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity
import dev.wildware.moba.MobaGame
import dev.wildware.moba.SpriteView
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.level.LevelOutcome
import dev.wildware.udea.core.level.LevelSaveException
import dev.wildware.udea.core.loop.barrier
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.snapshot.DivergenceReport
import dev.wildware.udea.core.snapshot.SnapshotService
import dev.wildware.udea.core.snapshot.WorldHasher
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.gas.GameplayEffects
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A real `moba` match, saved to a level file mid-fight and loaded into a **fresh** world, is the
 * world it was saved from (issue #191).
 *
 * ## Why a real match and not a fixture
 *
 * `SnapshotRestoreProofTest` exists because a fixture registers everything it spawns by
 * construction, so it cannot notice a component the real game carries and the mechanism forgot.
 * A level file has exactly that failure available to it - a component nobody made serializable,
 * or one left out of the generated list - so the proof runs the real level until the fight is in
 * the state where the most kinds of component are alive at once.
 *
 * ## Several comparisons, because each one is blind where another sees
 *
 * - **`WorldHasher.hash` of a full capture**: every registered component field, the entity roster,
 *   the clock, every random stream and the `NetId` allocator. This is the criterion as the issue
 *   states it.
 * - **component sets per entity, read off Fleks**: the hash only sees the snapshot registry, and
 *   `SpriteView` is outside it (`SnapshotRestoreProofTest.UNCOVERED`). A level that dropped every
 *   hit flash would hash identical; it would not census identical.
 * - **the level saved again from the loaded world, byte for byte**: a field the serializer writes
 *   but the load did not put back shows up as a different second file.
 * - **every JVM field of every component, compared object to object** ([FieldByField]): the one
 *   comparison that reads a component without going through a description of it - a `Replicator`
 *   or a serializer. A field the serializer
 *   never writes is missing from both files alike, so the byte comparison agrees about a world
 *   that lost it; measured with `@Transient` on `GameUnit.movingTick`, which left the other
 *   comparisons green and this one red.
 *
 * Set the Gradle property `udea.levelEvidenceDir` to keep the saved `.udealevel` and a transcript
 * of the measured values.
 */
class LevelSaveLoadProofTest {

    private fun booted(): GameHost = MobaGame.host(RenderMode.Headless).also { MobaEntry.seed(it) }

    /**
     * One registry for every host in a test, because `DivergenceReport.compare` diffs two captures
     * only when they were built from the same one. `MobaGame.componentRegistry` is built over an
     * attribute table with the game's layout, which is all `AttributesReplicator` reads from it.
     */
    private val registry = MobaGame.componentRegistry()

    /** A full capture of [host]: every registered component, the clock, the streams and the ids. */
    private fun capture(host: GameHost): WorldSnapshot =
        SnapshotService(registry, host.world, host.ctx, host.ctx[CoreModule.NET_IDS]).capture()

    private fun fullHash(host: GameHost): Long = WorldHasher.hash(capture(host))

    /** Every live entity's component class names, keyed by `NetId`, straight off Fleks. */
    private fun census(host: GameHost): Map<Int, List<String>> {
        val out = sortedMapOf<Int, List<String>>()
        host.ctx[CoreModule.NET_IDS].forEachLive { netId: NetId, entity: Entity ->
            out[netId.raw] = host.world.snapshotOf(entity).components
                .map { it::class.qualifiedName ?: it::class.toString() }
                .sorted()
        }
        return out
    }

    /** Every live entity's components, keyed by `NetId` and sorted by class, as objects. */
    private fun components(host: GameHost): Map<Int, List<Component<*>>> {
        val out = sortedMapOf<Int, List<Component<*>>>()
        host.ctx[CoreModule.NET_IDS].forEachLive { netId: NetId, entity: Entity ->
            out[netId.raw] = host.world.snapshotOf(entity).components.sortedBy { it::class.java.name }
        }
        return out
    }

    /** What [FieldByField] finds between two worlds' components, entity by entity. */
    private fun fieldDifferences(saved: Map<Int, List<Component<*>>>, loaded: GameHost): List<String> {
        val comparison = FieldByField(liveLength = mapOf("GameplayEffects" to "count"))
        return comparison.differences("world", saved.values.toList(), components(loaded).values.toList())
    }

    /**
     * Runs the fight until the most kinds of state are live at once - a unit has died, an ability
     * is mid-cast, an effect is applied and a hit flash is on screen - and [also] holds. Searched
     * for rather than hard-coded, for the reason `SnapshotRestoreProofTest` gives.
     */
    private fun fightUntilBusy(host: GameHost, also: (GameHost) -> Boolean = { true }): String {
        val startUnits = census(host).size
        repeat(SEARCH_LIMIT) {
            host.run(1)
            var casts = 0
            var effects = 0
            var flashes = 0
            host.world.family { any(Abilities, GameplayEffects, SpriteView) }.forEach { entity ->
                entity.getOrNull(Abilities)?.let { abilities ->
                    for (slot in 0 until abilities.slotCount) {
                        if (abilities.instanceAt(slot).isActive) casts++
                    }
                }
                effects += entity.getOrNull(GameplayEffects)?.count ?: 0
                if (entity.has(SpriteView)) flashes++
            }
            val units = census(host).size
            if (units < startUnits && casts > 0 && effects > 0 && flashes > 0 && also(host)) {
                return "tick=${host.tick} entities=$units (from $startUnits) activeCasts=$casts " +
                    "appliedEffects=$effects spriteViews=$flashes " +
                    "queuedBarrierActions=${host.ctx.barrier.pendingCount()}"
            }
        }
        throw AssertionError(
            "after $SEARCH_LIMIT ticks the fight never reached the state this proof needs; it " +
                "would be exercising a quiet world",
        )
    }

    /** Saves [host] through its barrier and drains it, without stepping the world. */
    private fun saveFrom(host: GameHost): ByteArray {
        val action = host.game.levels.save(host.ctx.barrier)
        host.ctx.barrier.drain(host.world, host.ctx)
        return assertIs<LevelOutcome.Completed<ByteArray>>(action.outcome, "the save failed: ${action.outcome}").value
    }

    /** Loads [bytes] into [host] through its barrier and drains it, without stepping the world. */
    private fun loadInto(host: GameHost, bytes: ByteArray) {
        val levels = host.game.levels
        val action = levels.load(levels.read(bytes), host.ctx.barrier)
        host.ctx.barrier.drain(host.world, host.ctx)
        assertIs<LevelOutcome.Completed<*>>(action.outcome, "the load did not apply: ${action.outcome}")
    }

    @Test
    fun `a match saved mid-fight loads into a fresh world identical to the one it was saved from`() {
        val original = booted()
        val moment = fightUntilBusy(original)
        original.time.pause()

        val bytes = saveFrom(original)
        val savedHash = fullHash(original)
        val savedCensus = census(original)
        val savedComponents = components(original)

        val fresh = MobaGame.host(RenderMode.Headless)
        val freshHash = fullHash(fresh)
        loadInto(fresh, bytes)

        val loadedHash = fullHash(fresh)
        val loadedCensus = census(fresh)
        val resaved = saveFrom(fresh)
        val differences = fieldDifferences(savedComponents, fresh)
        evidence(
            "level.udealevel" to bytes,
            "transcript.txt" to buildString {
                appendLine("saved at      $moment")
                appendLine("level bytes   ${bytes.size}")
                appendLine("saved hash    ${savedHash.toULong().toString(16)}")
                appendLine("fresh hash    ${freshHash.toULong().toString(16)} (the fresh world, before the load)")
                appendLine("loaded hash   ${loadedHash.toULong().toString(16)}")
                appendLine("entities      saved=${savedCensus.size} loaded=${loadedCensus.size}")
                appendLine("components    saved=${savedCensus.values.sumOf { it.size }} loaded=${loadedCensus.values.sumOf { it.size }}")
                appendLine("census equal  ${savedCensus == loadedCensus}")
                appendLine("resave equal  ${bytes.contentEquals(resaved)}")
                appendLine("field by field differences  ${differences.size}")
                appendLine("component kinds saved:")
                for (name in savedCensus.values.flatten().toSortedSet()) appendLine("  $name")
            }.toByteArray(),
        )

        assertTrue(freshHash != savedHash, "the fresh world already hashed like the saved one; nothing is proved")
        assertEquals(savedHash, loadedHash, "WorldHasher.hash after loading the level saved at $moment")
        assertEquals(savedCensus, loadedCensus, "component sets per entity after loading the level saved at $moment")
        assertContentEquals(bytes, resaved, "saving the loaded world again did not reproduce the level file")
        assertEquals(
            emptyList(),
            differences.take(40),
            "fields that differ between the saved world's components and the loaded world's",
        )
    }

    /**
     * The tick after a load is the tick after the save, including what was queued when it was saved.
     *
     * The save moment is searched for with a barrier action already queued - a spawn requested during
     * the last tick - because that is the state a save beside the barrier gets wrong: measured on this
     * game, a level saved that way lost the spawn and parted from the saved match one tick later.
     *
     * One tick, and deliberately not a long run. Fleks' `loadSnapshot` restores every live entity's id
     * and version but not the order its free list hands recycled ids back in, so after the first
     * spawn that reuses an id the two worlds hold the same `NetId`s under different Fleks ids; and some
     * `moba` systems sum forces in family order, so after a few hundred ticks positions differ in the
     * fourth decimal. That is a property of Fleks ids and of those systems - a rewind has it too - and
     * not of what a level carries, so a long run here would be testing something else.
     */
    @Test
    fun `the tick after loading a level is the tick after saving it`() {
        val original = booted()
        val moment = fightUntilBusy(original) { it.ctx.barrier.pendingCount() > 0 }
        original.time.pause()
        val bytes = saveFrom(original)

        val fresh = MobaGame.host(RenderMode.Headless)
        loadInto(fresh, bytes)

        original.run(1)
        fresh.run(1)
        val report = DivergenceReport.compare(original.tick, capture(original), capture(fresh))
        assertTrue(
            report.isIdentical,
            "one tick after loading the level saved at $moment, the worlds differ\n${report.describe()}",
        )
    }

    /** A component no module lists refuses the save by name - it is not dropped. */
    @Test
    fun `saving a match that holds a component no level can carry fails naming it`() {
        val host = booted()
        host.run(1)
        host.time.pause()
        val unit = host.world.family { all(GameUnit) }.first()
        with(host.world) { unit.configure { it += Unsaveable() } }

        val action = host.game.levels.save(host.ctx.barrier)
        host.ctx.barrier.drain(host.world, host.ctx)

        val failed = assertIs<LevelOutcome.Failed>(action.outcome)
        val cause = assertIs<LevelSaveException>(failed.cause)
        assertTrue(
            Unsaveable::class.qualifiedName!! in cause.message.orEmpty(),
            "the refusal does not name the component: ${cause.message}",
        )
    }

    /** A Fleks component with no `@Serializable`, so no generated level list can name it. */
    private class Unsaveable : Component<Unsaveable> {
        override fun type(): ComponentType<Unsaveable> = Unsaveable

        companion object : ComponentType<Unsaveable>()
    }

    private fun evidence(vararg files: Pair<String, ByteArray>) {
        val dir = System.getProperty(EVIDENCE_DIR)?.takeIf(String::isNotBlank)?.let(::File) ?: return
        dir.mkdirs()
        for ((name, bytes) in files) File(dir, name).writeBytes(bytes)
    }

    private companion object {
        const val SEARCH_LIMIT: Int = 2_000
        const val EVIDENCE_DIR: String = "udea.levelEvidenceDir"
    }
}
