package dev.wildware.udea.render

import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.render.support.RepoLayout
import dev.wildware.udea.render.support.testTargets
import org.objectweb.asm.ClassReader
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
        // `RenderRegistry.requireNotAFleksSystem` builds its message from `::class.qualifiedName`
        // (dotted nesting, `Outer.Inner`), not `::class.java`'s binary name (`Outer$Inner`).
        assertTrue(checkNotNull(FleksAndRenderFixture::class.qualifiedName) in message, message)
        assertTrue("IntervalSystem" in message, message)
    }

    @Test
    fun `no compiled class in udea-render is both a presentation system and a Fleks system`() {
        val classes = RepoLayout.classFiles("udea-render", "main")
        check(classes.isNotEmpty()) { "udea-render has no compiled classes; this check is vacuous" }

        val byName = classes.associateBy { binaryName(it) }
        val offenders = byName.keys
            .filter { name ->
                (isA(name, RenderSystem::class.java, byName) || isA(name, OverlaySystem::class.java, byName)) &&
                    isA(name, IntervalSystem::class.java, byName)
            }
            .sorted()

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

    /** The binary name [file]'s path spells out, under its `main` output directory. */
    private fun binaryName(file: java.io.File): String {
        val root = generateSequence(file.parentFile) { it.parentFile }.first { it.name == "main" }
        return file.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.')
    }

    /**
     * Whether the class [name] is a [type], by loading it where this JVM can.
     *
     * Every class compiled from `commonMain` or `jvmMain` is on this test's runtime classpath and is
     * loaded, not wrapped in a `runCatching`: a class that should load and does not means the
     * classpath and the output directory disagree, which would make the scan silently skip classes.
     * The one exception is a class compiled from `androidMain` alone (`KoolKeyTable.android.kt`,
     * issue #228), which no JVM test classpath holds. Its header is read from the class file instead
     * and each of its supertypes checked the same way, so an Android-only class cannot slip past.
     */
    private fun isA(name: String, type: Class<*>, files: Map<String, java.io.File>): Boolean {
        val loaded = try {
            Class.forName(name, false, javaClass.classLoader)
        } catch (missing: ClassNotFoundException) {
            val file = files[name] ?: throw missing
            check("/android/main/" in file.invariantSeparatorsPath) {
                "$name is compiled for the JVM and is not on the JVM test classpath: $file"
            }
            val header = ClassReader(file.readBytes())
            val supertypes = listOfNotNull(header.superName) + header.interfaces
            return supertypes.any { isA(it.replace('/', '.'), type, files) }
        }
        return type.isAssignableFrom(loaded)
    }

    private val ctx: GameContext = testGameContext(seed = 3L)

    private fun world(): World = configureWorld { injectables { gameContext(ctx) } }
}
