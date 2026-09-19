package dev.wildware.udea.assets.compiler.edit

import dev.wildware.udea.assets.compiler.Ref
import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.assets.compiler.atlas.Png
import dev.wildware.udea.assets.compiler.atlas.RgbaImage
import dev.wildware.udea.assets.compiler.pipeline.AssetPipeline
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A new asset saved from the editor (issue #195): generated with KotlinPoet from an existing asset
 * of the same kind, and accepted by the whole asset compiler - pass 1's scan, the script compile with
 * the K2 loop checker on its classpath, and every validator - with no diagnostic at all.
 */
class NewAssetScriptTest {

    /** A compilable asset tree: the soldier's script and the one sheet it names. */
    private fun tree(name: String): Pair<Path, Path> {
        val root = TestPaths.scratch("new-asset-$name")
        val assets = root.resolve("assets")
        assets.resolve("character").createDirectories()
        assets.resolve("character/soldier.udea.kts").writeText(SOLDIER)
        val sheet = assets.resolve("sprites/soldier/Soldier-Idle.png")
        sheet.parent.createDirectories()
        Files.write(sheet, Png.encode(RgbaImage.blank(FRAME * COLUMNS, FRAME)))
        return root to assets
    }

    @Test
    fun `a new asset copied from an existing one passes the asset compiler with no diagnostics`() {
        val (root, assets) = tree("clean")
        val sources = AssetSources(root, assets)

        val created = assertIs<CreateResult.Created>(sources.create("character/soldier_idle_sheet", "soldier_idle_copy"))
        assertEquals(assets.resolve("character/soldier_idle_copy.udea.kts"), created.file)
        assertEquals("character/soldier_idle_copy", created.id)
        assertFalse(created.file.exists(), "create describes the file; writing it is the caller's step")
        created.file.writeText(created.text)

        val compiled = AssetPipeline.compileAndValidate(root, assets, TestPaths.compilerClasspath, root.resolve("cache"))
        assertEquals(emptyList(), compiled.report.diagnostics, "the new asset:\n${created.text}")

        // The copy is the template's value for value, including the one a named constant set.
        val copy = assertNotNull(compiled.graph.assets["character/soldier_idle_copy"], "the new asset is not in the graph")
        val template = compiled.graph.assets.getValue("character/soldier_idle_sheet")
        assertEquals(template.kind, copy.kind)
        assertEquals(template.fields - "name", copy.fields - "name")
        assertEquals(1.58F, copy.fields["scale"])
    }

    @Test
    fun `the new file is the template's call with its own name and plain literals, and names where it came from`() {
        val (root, assets) = tree("shape")
        val created = assertIs<CreateResult.Created>(AssetSources(root, assets).create("character/soldier_idle_sheet", "soldier_idle_copy"))

        assertEquals(
            """
            |// Created by the Udea editor from `character/soldier_idle_sheet`.
            |spriteSheet(
            |    name = "soldier_idle_copy",
            |    spritePath = "sprites/soldier/Soldier-Idle.png",
            |    rows = 1,
            |    columns = 6,
            |    scale = 1.58F,
            |)
            |
            """.trimMargin(),
            created.text,
        )
    }

    @Test
    fun `a reference is copied as a reference`() {
        val (root, assets) = tree("reference")
        val sources = AssetSources(root, assets)
        val created = assertIs<CreateResult.Created>(sources.create("character/soldier_idle", "soldier_idle_again"))
        created.file.writeText(created.text)

        val compiled = AssetPipeline.compileAndValidate(root, assets, TestPaths.compilerClasspath, root.resolve("cache"))
        assertEquals(emptyList(), compiled.report.diagnostics, "the new asset:\n${created.text}")
        assertEquals("character/soldier_idle_sheet", (compiled.graph.assets.getValue("character/soldier_idle_again").fields["sheet"] as Ref).id)
    }

    @Test
    fun `a template with a value a new file cannot carry is refused, naming the field, why and where`() {
        val (root, assets) = tree("refused")
        val refused = assertIs<CreateResult.Refused>(AssetSources(root, assets).create("character/soldier_attack", "soldier_attack_copy"))
        assertEquals("`notifies` is built by `mapOf(...)` on line $ATTACK_NOTIFIES_LINE, and a new asset copies plain values only", refused.message)
    }

    @Test
    fun `a name another asset already has is refused, and so is one that is not a plain name`() {
        val (root, assets) = tree("taken")
        val sources = AssetSources(root, assets)
        val taken = assertIs<CreateResult.Refused>(sources.create("character/soldier_idle_sheet", "soldier_idle"))
        assertTrue("character/soldier_idle" in taken.message, taken.message)

        val path = assertIs<CreateResult.Refused>(sources.create("character/soldier_idle_sheet", "../escape"))
        assertTrue("../escape" in path.message, path.message)
        assertEquals(SOLDIER, assets.resolve("character/soldier.udea.kts").readText())
    }

    private companion object {
        const val FRAME = 8
        const val COLUMNS = 6
        const val ATTACK_NOTIFIES_LINE = 20

        val SOLDIER: String = """
            |// The soldier. A comment the copy does not need.
            |
            |val soldierScale = 1.58F
            |
            |spriteSheet(
            |    name = "soldier_idle_sheet",
            |    spritePath = "sprites/soldier/Soldier-Idle.png",
            |    rows = 1,
            |    columns = 6,
            |    scale = soldierScale,
            |)
            |
            |spriteAnimation(name = "soldier_idle", sheet = reference("character/soldier_idle_sheet"))
            |
            |spriteAnimation(
            |    name = "soldier_attack",
            |    sheet = reference("character/soldier_idle_sheet"),
            |    loop = false,
            |    // Which frame each sound plays on.
            |    notifies = mapOf("swoosh" to 3),
            |)
            |
        """.trimMargin()
    }
}
