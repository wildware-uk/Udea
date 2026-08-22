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
    fun `a disambiguated constant name is itself checked for collision`() {
        // The three-name case the two-name test cannot reach. Sorted by name ('B' < '_'), the
        // properties are fooBar(0), foo_bar(1), foo_bar_0(2). The first two collide on
        // FIELD_FOO_BAR and get the index appended, producing FIELD_FOO_BAR_0 — which is
        // *also* what foo_bar_0 screams to on its own, and it looked unique so it was left
        // alone. Two `const val FIELD_FOO_BAR_0` in one object is a conflicting-declarations
        // error inside a generated file, with nothing pointing back at the property that
        // caused it: exactly the failure mode the emitter claims to have eliminated.
        val names = FieldOrder.constantNames(listOf("fooBar", "foo_bar", "foo_bar_0"))

        assertEquals(names.size, names.toSet().size, "constant names collided: $names")
    }

    @Test
    fun `uniquification is closed however many names pile onto one constant`() {
        val names = FieldOrder.constantNames(
            listOf("fooBar", "foo_bar", "foo_bar_0", "foo_bar_1", "FOO_BAR"),
        )

        assertEquals(names.size, names.toSet().size, "constant names collided: $names")
        assertTrue(names.all { it.startsWith("FIELD_FOO_BAR") }, names.toString())
    }

    @Test
    fun `a lowered composite name screams to one constant per component`() {
        // `position` lowers to `position.x` and `position.y`, and the dot is a word boundary
        // like an underscore. FIELD_POSITION_X is the name the frozen contract's worked
        // example uses, and `udea-core`'s hand-written TransformReplicator declares.
        assertEquals("FIELD_POSITION_X", FieldOrder.constantName("position.x"))
        assertEquals("FIELD_MUZZLE_OFFSET_Y", FieldOrder.constantName("muzzleOffset.y"))
    }

    @Test
    fun `a lowered component sorts immediately after its own property and before any other`() {
        // '.' is code unit 46, below every character a Kotlin identifier may contain, so a
        // property's components always sort adjacent — even against a property whose name is
        // this one plus a suffix. If they did not, `fieldNames` would not be sorted and the
        // dotted names would interleave with unrelated fields.
        assertEquals(
            listOf("position.x", "position.y", "positional", "rotation"),
            FieldOrder.assign(
                listOf("rotation", "positional", "position.y", "position.x"),
            ) { it },
        )
    }

    @Test
    fun `a lowered component and a flat property can collide on a constant, and are separated`() {
        // `position.x` and `positionX` both scream to FIELD_POSITION_X, and two of those in
        // one generated object does not compile.
        val names = FieldOrder.constantNames(listOf("position.x", "positionX"))

        assertEquals(names.size, names.toSet().size, "constant names collided: $names")
        assertTrue(names.all { it.startsWith("FIELD_POSITION_X") }, names.toString())
    }

    @Test
    fun `constant names are left alone when nothing collides`() {
        assertEquals(
            listOf("FIELD_CURRENT", "FIELD_MAXIMUM"),
            FieldOrder.constantNames(listOf("current", "maximum")),
        )
    }
}
