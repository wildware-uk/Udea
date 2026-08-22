package dev.wildware.udea.codegen.replicator

import dev.wildware.udea.core.replication.ComponentTypeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `TypeIds.placeholder` is one half of a cross-module seam.
 *
 * `udea-core` declares `Replicator.typeId` as a `ComponentTypeId`, whose `init` block requires
 * `raw >= 0`. `udea-codegen` emits `ComponentTypeId(<literal>)` into every generated replicator,
 * where the literal is whatever this function returned at generation time. So a single FQN that
 * hashes negative does not produce a compile error or a failed generation — it produces a
 * generated file that compiles cleanly and throws `IllegalArgumentException` from its own
 * class initialiser the first time the game touches that component.
 *
 * `GeneratedReplicatorRoundTripTest` asserts the ids of the three fixture components are
 * non-negative, but that cannot catch this: if any of the three were negative, the fixture's
 * class initialiser would already have thrown and the test would error before its assertion
 * ran. The property that actually has to hold is over *all* names, and it is tested here,
 * against the real `ComponentTypeId` so the two halves cannot drift apart.
 */
class TypeIdsTest {

    /** Names chosen to drive the FNV-1a accumulator across the sign boundary many times over. */
    private fun names(): List<String> = buildList {
        add("")
        add("A")
        add("dev.wildware.udea.codegen.fixtures.Movement")
        for (i in 0 until 4_000) {
            add("dev.wildware.moba.component.Component$i")
            add("a".repeat(i % 97 + 1) + i)
        }
        for (ch in ' '..'~') add("pkg.C$ch")
    }

    @Test
    fun `a placeholder id is non-negative for every name, so ComponentTypeId always accepts it`() {
        for (name in names()) {
            val raw = TypeIds.placeholder(name)
            assertTrue(raw >= 0, "TypeIds.placeholder(\"$name\") returned the negative id $raw")
            // Constructed for real: this is the exact call the emitter writes into the
            // generated file, so its precondition is exercised rather than restated.
            assertEquals(raw, ComponentTypeId(raw).raw)
        }
    }

    @Test
    fun `a placeholder id depends only on the name, not on call order or on other names`() {
        // Spec 5 assigns real ids from a checked-in lock file; until that exists this stand-in
        // still has to be stable, or an unrelated component being added anywhere in the module
        // would silently renumber every other component on the wire.
        val once = names().associateWith { TypeIds.placeholder(it) }
        val again = names().reversed().associateWith { TypeIds.placeholder(it) }

        assertEquals(once, again)
    }

    @Test
    fun `distinct names overwhelmingly get distinct ids`() {
        // FNV-1a is explicitly not collision-free and this is not the wire contract, so the
        // bar is "usable as a stand-in", not "injective". A hash that had collapsed to a
        // constant, or that ignored part of the name, would fail this badly.
        val all = names().toSet()
        val ids = all.map { TypeIds.placeholder(it) }.toSet()

        assertTrue(
            ids.size >= all.size - 1,
            "expected near-distinct ids for ${all.size} names, got ${ids.size}",
        )
    }
}
