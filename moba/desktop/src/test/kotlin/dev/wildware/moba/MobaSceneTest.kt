package dev.wildware.moba

import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.moba.entry.MobaLaunch
import dev.wildware.moba.level.GameUnit
import dev.wildware.moba.level.MobaLevel
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.loop.barrier
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
     * Move a unit in the level file past the margin and this fails by name before anybody takes
     * a capture. The bounds are the units' own saved positions, read off the level as it loads and
     * before any tick has moved them, rather than a cluster centre plus a scatter radius: the level
     * has been a saved file since issue #192, and a file holds exact values.
     */
    @Test
    fun `the level sits inside the camera`() {
        val halfWidth = MobaScene.WORLD_WIDTH / 2f
        val halfHeight = MobaScene.WORLD_HEIGHT / 2f
        val host = MobaGame.host(RenderMode.Headless)
        host.ctx.scenes.requestScene(MobaLevel.SCENE_ID)
        host.ctx.barrier.drain(host.world, host.ctx)
        val units = host.world.family { all(GameUnit, Position) }.entities
        assertTrue(units.size > 0, "the launch level put no units in the world")
        val xs = with(host.world) { units.map { it[Position].x } }
        val ys = with(host.world) { units.map { it[Position].y } }
        val left = xs.min()
        val right = xs.max()
        val bottom = ys.min()
        val top = ys.max()
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
            MobaLaunch.runWithGl(RenderMode.Offscreen, overlay = { error("never constructed") }) {
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
            MobaLaunch.runWithGl(RenderMode.Headless) { _, _ -> error("never attached") }
        }
    }
}
