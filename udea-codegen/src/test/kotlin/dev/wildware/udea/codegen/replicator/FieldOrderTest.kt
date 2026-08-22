package dev.wildware.udea.codegen.replicator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bit index rule, tested without a compiler.
 *
 * [FieldOrder] is the single source of bit indices, and a bit index is a wire contract: change
 * one and every already-deployed client misreads every packet for that component. So the rule is
 * a pure function of the property names, and it is pinned here rather than only observed
 * indirectly through generated output.
 */
class FieldOrderTest {

    @Test
    fun `indices are assigned by name, ascending, regardless of declaration order`() {
        assertEquals(
            listOf("alpha", "beta", "gamma"),
            FieldOrder.assign(listOf("gamma", "alpha", "beta")) { it },
        )
    }

    @Test
    fun `@Net and @Sim share one index space`() {
        // Indices are not "net fields first, sim fields after": fieldNames[i], mask bit i and
        // FieldStore field i are the same i, so a @Sim field occupies an index like any other.
        data class Field(val name: String, val net: Boolean)

        val ordered = FieldOrder.assign(
            listOf(Field("zeal", true), Field("apathy", false), Field("mirth", true)),
        ) { it.name }

        assertEquals(listOf("apathy", "mirth", "zeal"), ordered.map { it.name })
        assertEquals(listOf(false, true, true), ordered.map { it.net })
    }

    @Test
    fun `ordering is by UTF-16 code unit, so it does not vary with locale`() {
        // Uppercase sorts before lowercase. Deliberate: a locale-sensitive comparison would give
        // a different wire layout on a Turkish developer's machine than on CI.
        assertEquals(
            listOf("Zulu", "alpha", "zulu"),
            FieldOrder.assign(listOf("zulu", "alpha", "Zulu")) { it },
        )
    }

    @Test
    fun `ordering is stable for equal names`() {
        val input = listOf("a" to 1, "a" to 2, "a" to 3)
        assertEquals(listOf(1, 2, 3), FieldOrder.assign(input) { it.first }.map { it.second })
    }

    @Test
    fun `a constant name is the screaming-snake form of the property name`() {
        assertEquals("FIELD_ROTATION", FieldOrder.constantName("rotation"))
        assertEquals("FIELD_LAST_GROUNDED_TICK", FieldOrder.constantName("lastGroundedTick"))
        assertEquals("FIELD_HP", FieldOrder.constantName("hp"))
        assertEquals("FIELD_MAX_HP2", FieldOrder.constantName("maxHp2"))
        assertEquals("FIELD_ALREADY_SNAKE", FieldOrder.constantName("already_snake"))
    }

    @Test
    fun `colliding constant names are disambiguated rather than silently merged`() {
        // `fooBar` and `foo_bar` both scream to FIELD_FOO_BAR, and two `const val FIELD_FOO_BAR`
        // in one object does not compile. Suffixing the index is ugly and correct.
        val names = FieldOrder.constantNames(listOf("fooBar", "foo_bar", "other"))

        assertEquals(names.size, names.toSet().size, "constant names collided: $names")
        assertEquals("FIELD_OTHER", names[2])
        assertTrue(names[0].startsWith("FIELD_FOO_BAR"), names[0])
        assertTrue(names[1].startsWith("FIELD_FOO_BAR"), names[1])
    }

    @Test
    fun `constant names are left alone when nothing collides`() {
        assertEquals(
            listOf("FIELD_CURRENT", "FIELD_MAXIMUM"),
            FieldOrder.constantNames(listOf("current", "maximum")),
        )
    }
}
