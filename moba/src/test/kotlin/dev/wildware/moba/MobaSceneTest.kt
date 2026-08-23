package dev.wildware.moba

import dev.wildware.moba.entry.MobaEntry
import dev.wildware.moba.level.TestLevelScene
import dev.wildware.udea.core.host.RenderMode
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What the scene promises, checked without a GL context.
 *
 * The GL half - that twenty-seven units actually reach a framebuffer - is proven by driving
 * `:moba:run` over HTTP and reading the PNG back. These are the parts that can fail at build time
 * instead, and each one is a failure a reviewer has seen shipped: a camera that does not contain
 * the game, and an overlay registered into a mode that gets captured.
 *
 * The animation-playhead tests that used to live here went with `ChampionRenderSystem`; the
 * roster's own playhead is `CharacterAnimator`, and `MobaCharacterTest` drives it.
 */
class MobaSceneTest {

    // --- the camera, and the level it has to frame -----------------------------------------

    /**
     * Every unit the level can spawn is inside the default camera.
     *
     * This replaced a set of tests about `DriftSystem`, which existed because the whole of this
     * game's motion was one unit sliding across a 90-unit field. The field is a real level now,
     * and the failure those tests were really guarding against is unchanged: a camera that does
     * not contain the game produces a perfectly valid screenshot of an empty framebuffer, which
     * `render.compare_artifacts` reports as `identical:true` for every pair - indistinguishable
     * from a broken renderer without booting an instance.
     *
     * Widen `TestLevelScene.SCATTER` past the margin, or move a cluster centre, and this fails
     * by name before anybody takes a capture.
     */
    @Test
    fun `the level sits inside the camera`() {
        val halfWidth = MobaScene.WORLD_WIDTH / 2f
        val halfHeight = MobaScene.WORLD_HEIGHT / 2f
        val scatter = TestLevelScene.SCATTER
        val left = TestLevelScene.ORC_CLEARING_X - scatter
        val right = TestLevelScene.SKELETON_CAMP_X + scatter
        val bottom = TestLevelScene.SOLDIER_CAMP_Y - scatter
        val top = scatter
        assertTrue(
            left > MobaScene.CAMERA_X - halfWidth,
            "the orc clearing is off the left of the camera: $left",
        )
        assertTrue(
            right < MobaScene.CAMERA_X + halfWidth,
            "the skeleton camp is off the right of the camera: $right",
        )
        assertTrue(
            bottom > MobaScene.CAMERA_Y - halfHeight,
            "the soldier camp is below the camera: $bottom",
        )
        assertTrue(top < MobaScene.CAMERA_Y + halfHeight, "the priest is above the camera: $top")
    }

    // --- the overlay, and the mode it is allowed in ------------------------------------------

    /**
     * Spec 3.7: the overlay exists only in `RenderMode.Windowed`.
     *
     * `runWithGl` refuses rather than skipping, and the refusal happens before a backend is
     * started - which is what makes this assertable with no display. Delete the `require` and
     * an `Offscreen` instance would happily draw the agent's own narration onto a surface
     * every capture reads.
     */
    @Test
    fun `an overlay is refused outside Windowed`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            MobaEntry.runWithGl(RenderMode.Offscreen, overlay = { error("never constructed") }) {
                    _, _ ->
                error("never attached")
            }
        }
        assertTrue("Windowed" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    /** And `Headless` is refused first, because it has no backend at all. */
    @Test
    fun `Headless has no GL backend to run with`() {
        assertFailsWith<IllegalArgumentException> {
            MobaEntry.runWithGl(RenderMode.Headless) { _, _ -> error("never attached") }
        }
    }
}
