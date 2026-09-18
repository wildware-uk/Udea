package dev.wildware.udea.render

import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.render.support.RepoLayout
import dev.wildware.udea.render.support.testTargets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Presentation is not in the world's system list -- checked two ways, because they fail
 * differently.
 *
 * Spec 3.3 makes `world.update(dt)` pure simulation *by construction*. That only holds while
 * no drawing system can be in the list Fleks updates. [RenderSystem] and [OverlaySystem] are
 * not `IntervalSystem`s, so the ordinary way of getting one in there does not typecheck; what
 * remains is a class that implements both, which is what these tests are about.
 */
class NoRenderSystemIsAFleksSystemTest {

    @Test
    fun `a system that is both a renderer and a Fleks system is rejected at build time`() {
        val world = world()
        val registry = RenderRegistry()
        registry.register(RenderPhase.World, { FleksAndRenderFixture(world) })

        val failure = assertFailsWith<IllegalArgumentException> {
            registry.build(world, ctx, testTargets())
        }

        val message = failure.message.orEmpty()
        assertTrue(FleksAndRenderFixture::class.java.name in message, message)
        assertTrue("IntervalSystem" in message, message)
    }

    @Test
    fun `no compiled class in udea-render is both a presentation system and a Fleks system`() {
        val classes = RepoLayout.classFiles("udea-render", "main")
        check(classes.isNotEmpty()) { "udea-render has no compiled classes; this check is vacuous" }

        val offenders = classes
            .map { file -> loadClass(file) }
            .filter { candidate ->
                (RenderSystem::class.java.isAssignableFrom(candidate) ||
                    OverlaySystem::class.java.isAssignableFrom(candidate)) &&
                    IntervalSystem::class.java.isAssignableFrom(candidate)
            }
            .map { it.name }

        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `neither presentation interface extends IntervalSystem`() {
        // The property everything above rests on: if either interface gained an
        // `IntervalSystem` supertype, every renderer would become registrable into the world
        // and the two tests above would still pass.
        assertTrue(!IntervalSystem::class.java.isAssignableFrom(RenderSystem::class.java))
        assertTrue(!IntervalSystem::class.java.isAssignableFrom(OverlaySystem::class.java))
    }

    /**
     * The mistake this rule exists to catch, written out.
     *
     * A `World` is passed explicitly because Fleks otherwise resolves it from the world being
     * configured, and the whole point is that this thing is built outside one.
     */
    private class FleksAndRenderFixture(world: World) : IntervalSystem(world = world), RenderSystem {
        override fun onTick() = Unit
        override fun render(target: OffscreenTarget, alpha: Float) = Unit
    }

    /**
     * Loads a compiled class by the binary name its path spells out.
     *
     * Not wrapped in a `runCatching`: every class file under a `main` output directory is on this
     * test's runtime classpath, so a failure to load one means the classpath and the output
     * directory disagree -- which would make the scan above silently skip classes, and is
     * exactly the kind of quiet hole a gate must not have.
     */
    private fun loadClass(file: java.io.File): Class<*> {
        val root = generateSequence(file.parentFile) { it.parentFile }.first { it.name == "main" }
        val binaryName = file.relativeTo(root).invariantSeparatorsPath
            .removeSuffix(".class")
            .replace('/', '.')
        return Class.forName(binaryName, false, javaClass.classLoader)
    }

    private val ctx: GameContext = testGameContext(seed = 3L)

    private fun world(): World = configureWorld { injectables { gameContext(ctx) } }
}
