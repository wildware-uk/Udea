package dev.wildware.udea.render

import com.badlogic.gdx.Gdx
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.core.loop.GameLoop
import dev.wildware.udea.core.loop.WorldSimulation
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.render.interp.InterpSnapshotSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ten seconds of simulation in a JVM with no window, no GL context and no LibGDX
 * `Application` -- run from *inside* the one module that has gdx and LWJGL3 on its classpath.
 *
 * That last part is why this test lives here rather than in `udea-core`. `udea-core` cannot
 * see GL at all, so it proving "no GL was needed" is nearly tautological. Here every GL class
 * is one import away and `Gdx.gl` is a field this test could read: the simulation still runs
 * to completion without one, because presentation is behind [dev.wildware.udea.core.loop.Presentation]
 * and `null` is a legitimate value for it (spec 3.5, `RenderMode.Headless`).
 *
 * In the old tree this was structurally impossible: every drawing system was a Fleks system
 * in `GameScreen.world`, so `world.update(delta)` (`common/UdeaGameManager.kt:222`) issued GL
 * calls and a dedicated server had to boot a window.
 */
class PureSimulationTest {

    @Test
    fun `600 ticks run with no presentation, no window and no GL context`() {
        val ctx = testGameContext(seed = 5L)
        // Constructed inside the configuration block: a SimSystem resolves its context from
        // the world being built, so there is no way to hand one a global.
        val world = configureWorld {
            injectables { gameContext(ctx) }
            systems { add(CountingSimSystem()) }
        }
        val counter = world.system<CountingSimSystem>()
        val sim = WorldSimulation(ctx, world)
        val loop = GameLoop(sim, view = null)

        repeat(TICKS) { loop.frame(1f / 60f) }

        assertEquals(TICKS, counter.runCount)
        assertEquals(TICKS.toLong(), loop.totalTicks)
        assertEquals(Tick(TICKS.toLong()), ctx.tick)
        assertEquals(0L, loop.truncatedFrames)
    }

    @Test
    fun `no GL context existed while those ticks ran`() {
        // The control for the test above. If a LibGDX Application had been booted -- by this
        // test, or by anything else sharing the JVM -- these statics would be populated, and
        // "it ran headless" would be an unproven claim.
        assertNull(Gdx.gl, "a GL context was live; this test can no longer prove anything")
        assertNull(Gdx.graphics, "a graphics backend was live")
        assertNull(Gdx.app, "a LibGDX Application was live")
    }

    @Test
    fun `the shipped render module contributes no drawing system to the world`() {
        // Built from the real definition, including RenderModule, rather than from a two-line
        // fixture. The previous version of this configured a world holding one CountingSimSystem
        // and asserted `none { it is RenderSystem || it is OverlaySystem }` over it -- true of
        // any world that compiles, since neither interface extends IntervalSystem and
        // `systems { add(...) }` takes nothing else. It could not fail, which is what §8 calls
        // a test that cannot fail. The property worth checking is that the *shipped*
        // configuration contributes nothing that draws, and that is what runs here.
        val game = UdeaGameDef(modules = listOf(RenderModule())).build()

        assertTrue(
            game.world.systems.isNotEmpty(),
            "the world has no systems at all, so this asserts nothing",
        )
        assertTrue(
            game.world.systems.any { it is InterpSnapshotSystem },
            "RenderModule's one simulation system is missing, so this is not the shipped " +
                "configuration: ${game.world.systems.map { it::class.simpleName }}",
        )
        assertTrue(
            game.world.systems.none { it is RenderSystem || it is OverlaySystem },
            "a presentation system reached the world's system list: ${game.world.systems}",
        )
        // And the other half of spec 3.3: what the module *does* contribute holds no GL and
        // draws nothing, so a RenderMode.Headless server can include it. The bytecode gate
        // (`udeaVerifyHeadless`) makes the same statement about udea-core; this is the one
        // module in the tree allowed to fail it, so the check here is over the instance.
        assertTrue(
            game.world.systems.filterIsInstance<InterpSnapshotSystem>().size == 1,
            "the interpolation system was registered more than once",
        )
    }

    /** Counts ticks. Advances nothing, so this measures the loop rather than a game. */
    private class CountingSimSystem : SimSystem() {
        var runCount: Int = 0
            private set

        override fun onTick() {
            runCount++
        }
    }

    private companion object {
        /** Ten seconds at 60Hz -- long enough for an accumulator defect to show up. */
        const val TICKS = 600
    }
}
