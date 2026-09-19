package dev.wildware.udea.assets.compiler.edit

import dev.wildware.udea.assets.compiler.TestPaths
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Saving one editor change into a `.udea.kts` (issue #195): the value's exact text is replaced, and
 * nothing else in the file moves.
 *
 * The fixture is written the way people write asset scripts rather than the way a generator would:
 * line comments, a block comment, a trailing comment on the value's own line, blank lines, a
 * one-line call beside a multi-line one, and spacing nobody would choose. Every assertion on a
 * rewrite compares the whole file, so a patcher that reformatted anything - a comment, a blank line,
 * the odd spacing around another argument - fails here, not in review.
 */
class AssetSourceSetTest {

    private fun sources(name: String, text: String): AssetSources {
        val root = TestPaths.scratch("asset-source-set-$name")
        val assets = root.resolve("assets")
        assets.resolve("character").createDirectories()
        assets.resolve("character/soldier.udea.kts").writeText(text)
        return AssetSources(root, assets)
    }

    private fun AssetSources.asset(id: String): AssetSource = assertNotNull(read(id), "no asset $id in the fixture")

    private fun changed(result: SetResult): SetResult.Changed = assertIs<SetResult.Changed>(result, "the set was refused: $result")

    @Test
    fun `changing one value rewrites exactly that value's text and nothing else`() {
        val sources = sources("one-value", SOLDIER)
        val result = changed(sources.asset("character/soldier_walk_sheet").set("columns", "8"))

        // The walk sheet's `columns = 6`, not the idle sheet's `columns   =   6` above it.
        val at = SOLDIER.indexOf("columns = 6, scale = 2F")
        val expected = SOLDIER.substring(0, at) + "columns = 8" + SOLDIER.substring(at + "columns = 6".length)
        assertEquals(expected, result.text)
        assertEquals(listOf(WALK_LINE), differingLines(SOLDIER, result.text))
    }

    @Test
    fun `a value inside a multi-line call keeps its line's odd spacing and trailing comment`() {
        val sources = sources("spacing", SOLDIER)
        val result = changed(sources.asset("character/soldier_idle_sheet").set("spritePath", "sprites/soldier/Idle-2.png"))

        assertEquals(
            SOLDIER.replace("\"sprites/soldier/Soldier-Idle.png\"", "\"sprites/soldier/Idle-2.png\""),
            result.text,
        )
        assertEquals(1, differingLines(SOLDIER, result.text).size)
        assertTrue("\"sprites/soldier/Idle-2.png\",   // the idle strip" in result.text, result.text)
    }

    @Test
    fun `a file with Windows line endings keeps every one of them`() {
        val crlf = SOLDIER.replace("\n", "\r\n")
        val sources = sources("crlf", crlf)
        val result = changed(sources.asset("character/soldier_walk_sheet").set("columns", "8"))

        val at = crlf.indexOf("columns = 6, scale = 2F")
        assertEquals(crlf.substring(0, at) + "columns = 8" + crlf.substring(at + "columns = 6".length), result.text)
    }

    @Test
    fun `a value set by a named constant is refused with the constant and its line`() {
        val sources = sources("constant", SOLDIER)
        val refused = assertIs<SetResult.ReadOnly>(sources.asset("character/soldier_idle_sheet").set("scale", "2"))

        assertEquals("set by `val soldierScale` on line $SCALE_VAL_LINE", refused.reason.message)
        assertEquals(SCALE_VAL_LINE, refused.reason.line)
    }

    @Test
    fun `a value built by an expression is refused with what builds it and its line`() {
        val sources = sources("expression", SOLDIER)
        val asset = sources.asset("character/soldier_attack")

        val notifies = assertIs<SetResult.ReadOnly>(asset.set("notifies", "{}"))
        assertEquals("built by `mapOf(...)` on line $ATTACK_LINE", notifies.reason.message)

        val speed = assertIs<SetResult.ReadOnly>(asset.set("speed", "3"))
        assertEquals("computed by `2F * 1.5F` on line $ATTACK_LINE", speed.reason.message)
    }

    @Test
    fun `the fields listing says which values are plain literals, of what type, and why the rest are not`() {
        val sources = sources("listing", SOLDIER)
        val fields = sources.asset("character/soldier_idle_sheet").fields.associateBy { it.name }

        assertEquals(listOf("name", "spritePath", "rows", "columns", "scale"), fields.keys.toList())
        assertEquals(LiteralType.String, (fields.getValue("spritePath").editability as Editability.Editable).value.type)
        assertEquals(LiteralType.Int, (fields.getValue("columns").editability as Editability.Editable).value.type)
        assertEquals("6", fields.getValue("columns").text)
        assertEquals(IDLE_COLUMNS_LINE, fields.getValue("columns").span.startLine)
        val scale = assertIs<Editability.ReadOnly>(fields.getValue("scale").editability)
        assertEquals(SCALE_VAL_LINE, scale.reason.line)
        // The name is the asset's id, and an id is not a value: renaming is not a save.
        assertIs<Editability.ReadOnly>(fields.getValue("name").editability)
    }

    @Test
    fun `a reference is edited as the id it names, and stays a reference call`() {
        val sources = sources("reference", SOLDIER)
        val result = changed(sources.asset("character/soldier_attack").set("sheet", "character/soldier_walk_sheet"))

        assertEquals(
            SOLDIER.replace("sheet = reference(\"character/soldier_idle_sheet\")", "sheet = reference(\"character/soldier_walk_sheet\")"),
            result.text,
        )
    }

    @Test
    fun `a new value is written in the literal's own type`() {
        val sources = sources("typed", SOLDIER)

        val float = changed(sources.asset("character/soldier_walk_sheet").set("scale", "2.5"))
        assertEquals(listOf("spriteSheet(name = \"soldier_walk_sheet\", spritePath = \"sprites/soldier/Soldier-Walk.png\", rows = 1, columns = 6, scale = 2.5F)"), changedLineTexts(float.text))

        // A whole number stays written the way the scripts write one: `3F`, not `3.0F`.
        val whole = changed(sources.asset("character/soldier_walk_sheet").set("scale", "3"))
        assertTrue("columns = 6, scale = 3F)" in whole.text, whole.text)

        val negative = changed(sources.asset("character/soldier_attack").set("offset", "7"))
        assertTrue("offset = 7," in negative.text, negative.text)

        val boolean = changed(sources.asset("character/soldier_attack").set("loop", "true"))
        assertTrue("loop = true)" in boolean.text, boolean.text)

        // A string is written escaped, so what lands in the file is the value typed and not code.
        val quoted = changed(sources.asset("character/soldier_idle_sheet").set("spritePath", "a \"b\" \$c"))
        assertTrue("spritePath = \"a \\\"b\\\" \${'$'}c\"," in quoted.text, quoted.text)
    }

    @Test
    fun `a value that is not the field's type is refused rather than written`() {
        val sources = sources("bad-value", SOLDIER)
        val refused = assertIs<SetResult.NotParseable>(sources.asset("character/soldier_walk_sheet").set("columns", "six"))
        assertEquals(LiteralType.Int, refused.type)
        assertEquals("six", refused.input)
    }

    @Test
    fun `a field the declaration does not write is refused with the fields it does`() {
        val sources = sources("no-field", SOLDIER)
        val refused = assertIs<SetResult.NoSuchField>(sources.asset("character/soldier_walk_sheet").set("colums", "8"))
        assertEquals(listOf("name", "spritePath", "rows", "columns", "scale"), refused.fields)
    }

    /** The texts of the lines [text] has that [SOLDIER] does not, in order. */
    private fun changedLineTexts(text: String): List<String> {
        val before = SOLDIER.lines()
        return text.lines().filterIndexed { index, line -> before.getOrNull(index) != line }
    }

    private companion object {

        /** The line numbers, 1-based, where [before] and [after] differ. Both must have as many lines. */
        fun differingLines(before: String, after: String): List<Int> {
            val a = before.lines()
            val b = after.lines()
            assertEquals(a.size, b.size, "the rewrite changed how many lines the file has")
            return a.indices.filter { a[it] != b[it] }.map { it + 1 }
        }

        const val SCALE_VAL_LINE = 3
        const val IDLE_COLUMNS_LINE = 11
        const val WALK_LINE = 16
        const val ATTACK_LINE = 20

        /** A commented asset script, written as a person writes one. */
        val SOLDIER: String = """
            |// The soldier's sheets. This comment must survive a save.
            |
            |val soldierScale = 1.58F
            |
            |/* A block comment,
            |   over two lines. */
            |spriteSheet(
            |    name = "soldier_idle_sheet",
            |    spritePath = "sprites/soldier/Soldier-Idle.png",   // the idle strip
            |    rows = 1,
            |    columns   =   6,
            |    scale = soldierScale,
            |)
            |
            |
            |spriteSheet(name = "soldier_walk_sheet", spritePath = "sprites/soldier/Soldier-Walk.png", rows = 1, columns = 6, scale = 2F)
            |
            |// An attack with values that are not plain literals.
            |
            |spriteAnimation(name = "soldier_attack", sheet = reference("character/soldier_idle_sheet"), notifies = mapOf("swoosh" to 3), speed = 2F * 1.5F, offset = -3, loop = false)
            |
        """.trimMargin()
    }
}
