package dev.wildware.udea.codegen.replicator

import dev.wildware.udea.core.replication.ComponentTypeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `TypeIds.assignIds` is one half of a cross-module seam, and the id it hands out is a wire
 * contract.
 *
 * `udea-core` declares `Replicator.typeId` as a `ComponentTypeId`, whose `init` block requires
 * `raw >= 0`. `udea-codegen` emits `ComponentTypeId(<literal>)` into every generated replicator,
 * where the literal is whatever this function returned at generation time. So an id that came
 * out negative would not produce a compile error and not a failed generation — it would produce
 * a generated file that compiles cleanly and throws `IllegalArgumentException` from its own
 * class initialiser the first time the game touches that component.
 *
 * `GeneratedReplicatorRoundTripTest` asserts the fixture components' ids are non-negative, but
 * that cannot catch this: if any of them were negative, the fixture's class initialiser would
 * already have thrown and the test would error before its assertion ran. The property that
 * actually has to hold is over *all* inputs, and it is tested here, against the real
 * `ComponentTypeId` so the two halves cannot drift apart.
 */
class TypeIdsTest {

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
    fun `ids are dense from zero, in ascending name order`() {
        // Dense and small is the whole reason ids come from sorted names rather than from a
        // hash: the id is a u16 in every packet.
        assertEquals(
            mapOf("a.Alpha" to 0, "a.Beta" to 1, "b.Gamma" to 2),
            TypeIds.assignIds(listOf("b.Gamma", "a.Alpha", "a.Beta")),
        )
    }

    @Test
    fun `every assigned id is non-negative, so ComponentTypeId always accepts it`() {
        for ((name, raw) in TypeIds.assignIds(names().distinct())) {
            assertTrue(raw >= 0, "TypeIds.assignIds gave '$name' the negative id $raw")
            // Constructed for real: this is the exact call the emitter writes into the
            // generated file, so its precondition is exercised rather than restated.
            assertEquals(raw, ComponentTypeId(raw).raw)
        }
    }

    @Test
    fun `assignment does not depend on the order the components were discovered in`() {
        // KSP hands symbols over in whatever order it pleases, and Gradle evaluates projects
        // in whatever order it pleases. If either could change an id, `net-protocol.lock`
        // would differ between two builds of identical sources and the CI drift check would
        // fire at random.
        val names = names().distinct()

        assertEquals(TypeIds.assignIds(names), TypeIds.assignIds(names.reversed()))
        assertEquals(TypeIds.assignIds(names), TypeIds.assignIds(names.shuffled()))
    }

    @Test
    fun `inserting a component renumbers its successors and nothing before it`() {
        // The stated cost of dense ids, pinned so that nobody "fixes" it later without
        // noticing that the fix changes the wire format of every existing component.
        val before = TypeIds.assignIds(listOf("a.Alpha", "c.Gamma"))
        val after = TypeIds.assignIds(listOf("a.Alpha", "b.Beta", "c.Gamma"))

        assertEquals(before.getValue("a.Alpha"), after.getValue("a.Alpha"))
        assertEquals(1, before.getValue("c.Gamma"))
        assertEquals(2, after.getValue("c.Gamma"))
    }

    @Test
    fun `a repeated name is a failure naming it, not one id handed to two components`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            TypeIds.assignIds(listOf("a.Alpha", "b.Beta", "a.Alpha"))
        }

        assertTrue("a.Alpha" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `more components than the u16 id space is a failure, not a wrapped id`() {
        val tooMany = List(TypeIds.MAX_ID + 2) { "pkg.C%06d".format(it) }

        val failure = assertFailsWith<IllegalArgumentException> { TypeIds.assignIds(tooMany) }

        assertTrue("u16" in failure.message.orEmpty(), failure.message.orEmpty())
        // And the boundary on the good side, so the check is not simply rejecting everything.
        assertEquals(
            TypeIds.MAX_ID,
            TypeIds.assignIds(tooMany.dropLast(1)).values.max(),
        )
    }
}
