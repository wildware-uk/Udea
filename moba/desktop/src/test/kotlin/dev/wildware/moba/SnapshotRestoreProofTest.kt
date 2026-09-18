package dev.wildware.moba

import com.github.quillraven.fleks.Entity
import dev.wildware.moba.ability.CharacterAttributes
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.loop.RewindResult
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.snapshot.SnapshotCoverage
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.gas.Attributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A rewind on the **real** level restores the world it captured, and not a silhouette of it.
 *
 * ## The measurement this exists to make impossible again
 *
 * Phase 0 proved snapshot and restore against synthetic fixtures - a handful of entities each
 * carrying one hand-written component - and every one of those tests passed while the mechanism
 * was, on the real game, broken in three ways at once. A play agent drove the assembled level and
 * read this off it:
 *
 * ```text
 * before rewind t421: units=22 abil=22 attr=22 view=22 activeCasts=14 health=1285.0
 * after  rewind t121: units=27 abil=22 attr=22 view=22 activeCasts=14 health=1285.0
 * ```
 *
 * Five units that had died since the keyframe came back as bare `Position`+`GameUnit` entities,
 * activations did not rewind, and total health did not move. None of it was a defect in the
 * snapshot spine. `MobaGame.componentRegistry` held two of the nine components a dressed unit
 * carries, and capture walks the registry - so the other seven were never in a snapshot to
 * restore, and an entity the restore had to rebuild was rebuilt out of the two it knew about.
 *
 * A fixture cannot catch that, because a fixture registers everything it spawns by construction.
 * Only the real roster can, which is why this test loads the real scene and lets the real fight
 * run before it looks at anything.
 *
 * ## What it asserts, and why each one is separate
 *
 * - **unit count** - the failure that was visible from outside: 22 became 27.
 * - **component sets, per entity** - the failure underneath it. Counting units would have gone
 *   green the moment the restore stopped resurrecting people, while still handing back shells.
 * - **active casts** - `udea-gas` activation state, which lives on `Abilities` and nowhere else.
 * - **total health** - the authoritative value on `Attributes`, not the `Position.hp` window that
 *   `DeathSystem` copies onto it once a tick. Asserting the window would pass with the truth
 *   behind it unrestored, which is exactly the shape of the original bug.
 * - **coverage** - `SnapshotCoverage` over the live world, in its own test below. The four
 *   assertions above are about the components that exist *today*; that one fails the moment a
 *   component is added to the game and not to the registry, which is the mistake itself rather
 *   than one of its symptoms.
 *
 * ## The keyframe tick is searched for, not hard-coded
 *
 * The interesting moment is "units have died **and** abilities are mid-cast", because those are
 * the two things the original bug destroyed. Which tick that is depends on balance, on the AI and
 * on the roster, all of which move; a hard-coded 420 would turn every balance edit into a failure
 * here, or - far worse - would quietly stop covering the case it was chosen for and keep passing.
 * So the fight is run one tick at a time until the world is in that state, and the tick it landed
 * on is reported in every failure message.
 */
class SnapshotRestoreProofTest {

    private fun booted(): GameHost {
        val host = MobaGame.host(RenderMode.Headless)
        MobaEntry.seed(host)
        return host
    }

    /** Everything the proof compares, read straight off the live world. */
    private data class Census(
        val units: Int,
        val components: Map<Int, List<String>>,
        val activeCasts: Int,
        val totalHealth: Float,
    ) {
        /**
         * The census in the exact shape the play agent read off the broken build, so the two are
         * comparable line for line rather than by eye.
         *
         * Printed rather than only asserted because the assertion answers "did it change", and
         * the question this test was commissioned to answer out loud is "by how much, and from
         * what". `abil`/`attr`/`view` are the three component-set populations the original bug
         * hollowed out; they come out of [components] rather than being counted separately, so a
         * line that disagrees with the assertion below it is impossible.
         */
        fun line(label: String, tick: Any): String {
            fun wearing(name: String) = components.values.count { name in it }
            return "$label $tick: units=$units abil=${wearing("Abilities")} " +
                "attr=${wearing("Attributes")} view=${wearing("CharacterView")} " +
                "activeCasts=$activeCasts health=$totalHealth"
        }
    }

    private fun census(host: GameHost): Census {
        val health = CharacterAttributes.create().health
        val components = LinkedHashMap<Int, List<String>>()
        var casts = 0
        var hp = 0f
        var units = 0
        val world = host.world
        host.ctx[CoreModule.NET_IDS].forEachLive { netId: NetId, entity: Entity ->
            units++
            components[netId.raw] = world.snapshotOf(entity).components
                .map { it::class.simpleName ?: "?" }
                .filterNot { it in UNRESTORED }
                .sorted()
            with(world) {
                entity.getOrNull(Abilities)?.let { abilities ->
                    for (slot in 0 until abilities.slotCount) {
                        if (abilities.instanceAt(slot).isActive) casts++
                    }
                }
                entity.getOrNull(Attributes)?.let { hp += it.base(health) }
            }
        }
        return Census(units, components, casts, hp)
    }

    /**
     * Runs the fight until somebody has died and somebody is mid-cast, and returns that census.
     *
     * @throws AssertionError if the fight never reaches that state, which would mean this test had
     *   silently stopped exercising anything.
     */
    private fun fightUntilInteresting(host: GameHost): Census {
        val start = census(host)
        var current = start
        var ticks = 0
        while (ticks < SEARCH_LIMIT) {
            host.run(1)
            ticks++
            current = census(host)
            if (current.units < start.units && current.activeCasts > 0) return current
        }
        throw AssertionError(
            "after $SEARCH_LIMIT ticks no unit had died with an ability in flight " +
                "(units ${start.units} -> ${current.units}, casts ${current.activeCasts}); the " +
                "keyframe this test needs never happened",
        )
    }

    @Test
    fun `a rewind on the real level restores every component, every cast and every hit point`() {
        val host = booted()
        val before = fightUntilInteresting(host)
        host.time.pause()
        val keyframe = host.time.snapshot()

        assertTrue(before.totalHealth > 0f, "no unit carried health at ${keyframe.tick}")

        println(before.line("[rewind] before  ", keyframe.tick))

        host.time.step(SPAN)
        val drifted = census(host)
        println(drifted.line("[rewind] +$SPAN    ", host.tick))
        assertTrue(
            drifted != before,
            "$SPAN ticks after ${keyframe.tick} changed nothing, so rewinding proves nothing",
        )

        val result = host.time.rewind(SPAN)
        assertTrue(result is RewindResult.Rewound, "rewind refused: $result")
        assertEquals(keyframe.tick, host.tick, "the clock did not land on the keyframe")

        val after = census(host)
        println(after.line("[rewind] after   ", host.tick))
        assertEquals(before.units, after.units, "unit count at ${keyframe.tick}")
        assertEquals(before.components, after.components, "component sets at ${keyframe.tick}")
        assertEquals(before.activeCasts, after.activeCasts, "active casts at ${keyframe.tick}")
        assertEquals(before.totalHealth, after.totalHealth, "total health at ${keyframe.tick}")
    }

    /**
     * Nothing that lives in the world is outside the snapshot registry, except what is pinned.
     *
     * This is the gate the original bug needed and did not have. A component added to a blueprint
     * and not to `MobaGame.componentRegistry` is invisible to capture, and the game keeps working
     * perfectly right up until somebody rewinds - so the mistake has no local symptom at all and
     * is found, if it is found, by playing.
     *
     * Sampled **every tick** of a whole fight rather than once at the end, because the components
     * most likely to be forgotten are the ones on short-lived entities: an arrow lives three
     * seconds and a hit flash a fifth of one, so a single sample at an arbitrary tick sees neither
     * and reports a clean world.
     *
     * The pinned list is an allow-list and it is meant to shrink. Adding a name to it is a
     * decision to let a component not survive a rewind, and it needs its reason written beside it.
     */
    @Test
    fun `every component that lives in the world is in the snapshot registry`() {
        val host = booted()
        val registry = MobaGame.componentRegistry()
        val netIds = host.ctx[CoreModule.NET_IDS]
        val seen = sortedSetOf<String>()
        repeat(COVERAGE_TICKS) {
            seen += SnapshotCoverage.uncovered(registry, host.world, netIds)
            host.run(1)
        }
        assertEquals(
            UNCOVERED,
            seen.toList(),
            "the set of components outside the snapshot registry changed over $COVERAGE_TICKS " +
                "ticks; a new name here is a component that a time.rewind silently drops",
        )
    }

    private companion object {

        /** How long the fight may run before "somebody died mid-cast" is declared unreachable. */
        const val SEARCH_LIMIT: Int = 2_000

        /** The rewind distance the play agent measured. */
        const val SPAN: Int = 300

        /** Ticks the coverage sweep watches. Long enough for arrows and flashes to come and go. */
        const val COVERAGE_TICKS: Int = 900

        /**
         * Components a rewind is knowingly allowed to drop, by fully-qualified name.
         *
         * One entry, and it is not a judgement that presentation state may drift: `SpriteView`
         * holds an `AssetId`, which is a `String` in a value class. `udea-codegen` lowers scalars,
         * enums, `NetId` and `Tick` and refuses everything else, and a `FieldStore` column is a
         * primitive array with no representation for a string - so this component cannot be
         * `@Replicated` and cannot be given a hand-written codec either without a
         * `FieldKind.String` column or an interned asset-id table. Both are `udea-core` changes.
         *
         * The consequence, stated: an arrow or a hit flash that a restore has to **rebuild** comes
         * back without its sprite and is drawn as nothing until it expires. Units are unaffected -
         * they wear `CharacterView`, which is registered.
         */
        val UNCOVERED: List<String> = listOf("dev.wildware.moba.SpriteView")

        /** [UNCOVERED] by simple name, which is what a Fleks snapshot reports. */
        val UNRESTORED: Set<String> = UNCOVERED.mapTo(HashSet()) { it.substringAfterLast('.') }
    }
}
