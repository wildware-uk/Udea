package dev.wildware.moba

import dev.wildware.udea.assets.SpriteSheet
import dev.wildware.udea.generated.GameAssets
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `:moba` loads a build-time `.udeapak`, and nothing in it slices a PNG at runtime.
 *
 * ## Why this is a test and not a screenshot
 *
 * The screenshot is the other half and it is taken by driving `:moba:run` over HTTP. It proves
 * pixels reached a framebuffer and it needs a GL driver, so a machine without one stops checking
 * the claim entirely - which is the "silently skipped gate" failure this repository has already
 * been caught by once. Everything below runs anywhere: the bundle is on the test runtime
 * classpath because `processResources` put it there, which is the same door the game opens it
 * through.
 */
class MobaAssetsTest {

    /**
     * The bundle is on the classpath, and it is the one this build packed.
     *
     * Delete `id("dev.wildware.udea.assets")` from `moba/build.gradle.kts` and this fails with
     * the message `MobaAssets.open` writes, which names the task that did not run.
     */
    @Test
    fun `the packed bundle is on the runtime classpath`() {
        val bundle = MobaAssets.bundle
        assertTrue(bundle.registry.size > 0, "the bundle decoded no assets: $bundle")
        val ids = bundle.registry.ids.map { it.value }
        // Containment rather than the whole sorted list: the art tree next to this one grows a
        // character at a time, and a test that pinned every id would fail on an addition that
        // broke nothing. What the *game* cannot boot without is named exactly - the level, and
        // the six blueprints its twenty-seven entities point at.
        val required = listOf(
            "config",
            "level/test_level",
            "blueprint/soldier",
            "blueprint/priest",
            "blueprint/orc",
            "blueprint/orc_elite",
            "blueprint/wizard",
            "blueprint/skeleton",
        )
        assertTrue(ids.containsAll(required), "the bundle is missing ${required - ids.toSet()}: $ids")
    }

    /**
     * The packed level is the roster the old game had, entity for entity.
     *
     * The count is the whole point of the port: twenty-seven units over three sides is what
     * `example/.../level/test_level.udea.kts` spawned, and it is what
     * `TestLevelScene` walks. Delete a `repeat(10)` from the asset and this fails here rather
     * than as a battle that is quietly one side short.
     */
    @Test
    fun `the packed level carries the whole roster`() {
        val level = MobaAssets.registry[GameAssets.level.testLevel]
        assertEquals(27, level.entities.size, "the roster: ${level.entities.map { it.name }}")
        val byBlueprint = level.entities.groupingBy { it.blueprint?.id?.value }.eachCount()
        assertEquals(10, byBlueprint["blueprint/soldier"], "the soldier line")
        assertEquals(1, byBlueprint["blueprint/priest"])
        assertEquals(1, byBlueprint["blueprint/wizard"], "the sixth character, on the field at last")
        // Four orcs and one elite. The player *is* the elite - `blueprint/player` inherited
        // `orc_elite` in the old game - so `Team.ORC` still fields five bodies while
        // `blueprint/orc` names four of them. `MobaLevelTest` counts the team, which is why that
        // test is unchanged by a recomposition this one has to be told about.
        assertEquals(4, byBlueprint["blueprint/orc"])
        assertEquals(1, byBlueprint["blueprint/orc_elite"], "the player, and the only unit granted the spin")
        assertEquals(10, byBlueprint["blueprint/skeleton"])
        // Every entity carries a cluster centre; the scatter around it is the Spawn stream's.
        assertTrue(level.entities.all { it.position != null }, "an entity has no authored position")
    }

    /**
     * The sheet's frames were cut at pack time, and the runtime is handed rectangles.
     *
     * Six regions of 64x64 on one page: that is `columns = 6` in `champion.udea.kts` applied by
     * `AtlasPacker`, not `texture.width / 100` applied by a renderer. If the packer ever stopped
     * splitting sheets, this would report one region of 384x64 and the game would draw the whole
     * strip on every unit - which is exactly what a runtime slicer's absence looks like when
     * nothing checks for it.
     */
    @Test
    fun `the atlas holds one region per frame, cut at pack time`() {
        val frames = MobaAssets.atlas.framesOf(GameAssets.champion.idleSheet.id)
        assertEquals(6, frames.size, "six frames were declared; the atlas holds ${frames.size}")
        assertTrue(frames.all { it.width == 64 && it.height == 64 }, "frames: $frames")
        assertTrue(frames.all { it.page == 0 }, "one sheet fits on one page: $frames")
        // Sorted by name, and the name is `<id>#<frame>` zero-padded - so region order is frame
        // order and `frames[i]` is frame `i` rather than whatever the packer met first.
        assertEquals(frames.map { it.name }.sorted(), frames.map { it.name })
    }

    /**
     * The world size of a champion is an authored number, reachable through the generated accessor.
     *
     * Two claims in one assertion, and both are the point of the phase. `GameAssets.champion.idleSheet`
     * is generated Kotlin with the static type `Ref<SpriteSheet>` - so this file would not compile
     * if `udeaGenerateAccessors` had not run or had emitted the wrong type. And `scale` is the
     * number `ChampionRenderSystem` multiplies a region's pixel size by every frame, which is what
     * replaced the renderer's own `WORLD_SCALE`.
     */
    @Test
    fun `the sheet carries the authored scale`() {
        val sheet = assertIs<SpriteSheet>(MobaAssets.registry[GameAssets.champion.idleSheet])
        assertEquals(6, sheet.columns)
        assertEquals(1, sheet.rows)
        assertEquals(0.53125f, sheet.scale)
        // 64 pixels at 0.53125 world units per pixel is 34 world units - the size the
        // hand-written renderer drew a champion at before any of this existed.
        assertEquals(34f, 64 * sheet.scale)
    }

    /**
     * The runtime slicing path is gone from the source, not merely unused.
     *
     * Issue #123's first acceptance criterion is a grep, and a grep in a review checklist runs
     * when somebody remembers. `Gdx.files.classpath` and a division by a frame size are the two
     * spellings the deleted code had; either one reappearing means a renderer decided a frame
     * grid for itself again, which is the defect the pack-time atlas exists to make impossible.
     */
    @Test
    fun `MobaScene does not read or slice an image at runtime`() {
        // Through a property rather than a relative path: a test's working directory is the
        // project directory under Gradle and the daemon's under an IDE, and a source scan that
        // silently found no file would pass by reading nothing.
        val projectDir = Path.of(
            requireNotNull(System.getProperty("udea.moba.projectDir")) {
                "system property 'udea.moba.projectDir' is not set; moba's test task sets it"
            },
        )
        val file = projectDir.resolve("src/main/kotlin/dev/wildware/moba/MobaScene.kt")
        // Comments stripped first. The KDoc names the deleted spellings on purpose - saying what
        // was removed is the point of it - and a scan that matched prose would either fail on a
        // correct file or force the explanation out of the code it explains.
        val source = withoutComments(file.readText())
        assertTrue(source.isNotBlank(), "the source scan read nothing from ${'$'}file")
        assertFalse("Gdx.files" in source, "MobaScene reads a file at runtime again")
        assertFalse("FRAME_SIZE" in source, "MobaScene divides a texture into a frame grid again")
        assertFalse("WORLD_SCALE" in source, "MobaScene has a hardcoded world scale again")
        // And it does read the live graph every frame, which is what makes a hot reload visible.
        assertTrue("registry.at(sheetIndex)" in source, "MobaScene stopped reading the live graph")
    }

    /** [text] with `/* */` blocks and `//` lines removed. Strings are not parsed; nothing here needs it. */
    private fun withoutComments(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines()
        .joinToString(separator = "\n") { it.substringBefore("//") }
}
