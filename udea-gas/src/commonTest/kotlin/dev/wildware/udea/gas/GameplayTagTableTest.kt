package dev.wildware.udea.gas

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tag ids come from ascending names, on every target.
 *
 * A tag id is simulation state - it sits in a replicated effect slot - so a JVM server and a Wasm
 * client must assign the same id to the same name. Issue #204 replaced the JVM's `toSortedSet()`
 * with common code, which makes the order the one thing the port could silently change.
 */
class GameplayTagTableTest {

    @Test
    fun `ids follow ascending name order, not declaration order`() {
        val forwards = GameplayTagTable.of(listOf("State.Stunned", "Ability.Fire", "Data.Damage"))
        val backwards = GameplayTagTable.of(listOf("Data.Damage", "Ability.Fire", "State.Stunned"))

        val expected = listOf("Ability.Fire", "Data.Damage", "State.Stunned")
        assertEquals(expected, List(forwards.size) { forwards.nameOf(GameplayTag(it)) })
        assertEquals(expected, List(backwards.size) { backwards.nameOf(GameplayTag(it)) })
    }

    @Test
    fun `a name declared twice is one tag`() {
        val table = GameplayTagTable.of(listOf("Data.Cooldown", "Ability.Fire", "Data.Cooldown"))

        assertEquals(2, table.size)
        assertEquals(GameplayTag(1), table.tagOf("Data.Cooldown"))
    }

    @Test
    fun `names compare by character code, so upper case sorts before lower case`() {
        val table = GameplayTagTable.of(listOf("ability.lower", "Ability.Upper", "_Underscore"))

        assertEquals(
            listOf("Ability.Upper", "_Underscore", "ability.lower"),
            List(table.size) { table.nameOf(GameplayTag(it)) },
        )
    }
}
