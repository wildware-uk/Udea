package dev.wildware.udea.render

import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.render.support.FrameLog
import dev.wildware.udea.render.support.RecordingOverlaySystem
import dev.wildware.udea.render.support.testTargets
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * That an [OverlaySystem] genuinely cannot reach a capturable target — spec 3.7's structural
 * guarantee, asserted rather than described.
 *
 * ## What this replaces
 *
 * A comment. `RenderPipelineTest` asserted "there is no expression that hands an OverlaySystem
 * the offscreen target, which is exactly the guarantee", `RenderTarget`'s KDoc said the
 * `ScreenTarget` was "deliberately missing" from the render side, and spec 3.7 said an overlay
 * "is never handed a capturable target". All three were false as written: `RenderRegistry.overlay`
 * took `(RenderResources) -> OverlaySystem`, and `RenderResources.offscreen` is a public val, so
 * an overlay could hold the [OffscreenTarget], size itself to the capture and read the shared
 * batch. Pixels still could not leak — the overlay runs after `buffer.end()` — but what held the
 * guarantee was frame ordering plus a separate FBO, which is the "remember to do it in the right
 * order" arrangement the type split exists to remove.
 *
 * ## Why reflection
 *
 * The guarantee is about what a *type* can express, and a runtime assertion cannot try to
 * compile an expression. What it can do is walk the public surface an overlay factory is handed
 * and prove there is no route from it to an [OffscreenTarget] — which is the same statement, and
 * which goes red the moment somebody adds one back.
 */
class OverlayResourcesTest {

    @Test
    fun `an overlay factory is handed OverlayResources and not RenderResources`() {
        val overlay = RenderRegistry::class.java.methods.single { it.name == "overlay" }

        val factory = overlay.genericParameterTypes[0] as ParameterizedType
        assertEquals(
            OverlayResources::class.java,
            erase(factory.actualTypeArguments[0]),
            "RenderRegistry.overlay takes ${factory.actualTypeArguments[0]}: an overlay handed " +
                "RenderResources can hold the OffscreenTarget, and spec 3.7's guarantee is back " +
                "to being frame ordering",
        )
    }

    @Test
    fun `nothing reachable from OverlayResources is a capturable target`() {
        val reachable = reachableTypes(OverlayResources::class.java)

        assertTrue(
            ScreenTarget::class.java in reachable,
            "OverlayResources no longer carries the ScreenTarget, so this proves nothing: " +
                "$reachable",
        )
        for (banned in listOf(OffscreenTarget::class.java, RenderTargets::class.java, RenderResources::class.java)) {
            assertTrue(
                banned !in reachable,
                "${banned.simpleName} is reachable from OverlayResources, so an OverlaySystem " +
                    "can hold a capturable target after all (spec 3.7). Reached: $reachable",
            )
        }
    }

    @Test
    fun `an overlay built by the registry is given the screen target and the shared batch`() {
        // The positive half: the split took nothing away that an overlay legitimately needs.
        // A batch is a vertex buffer, not a surface -- what a draw lands on is decided by what
        // is bound, and by the time an overlay draws the offscreen framebuffer has been unbound
        // and the capture already read.
        val log = FrameLog()
        val ctx = testGameContext(seed = 1L)
        val world = configureWorld { injectables { gameContext(ctx) } }
        val targets = testTargets()
        var handed: OverlayResources? = null
        val registry = RenderRegistry()
        registry.overlay({ resources ->
            handed = resources
            RecordingOverlaySystem("agent", log)
        })

        registry.build(world, ctx, targets)

        val resources = checkNotNull(handed) { "the overlay factory never ran" }
        assertSame(targets.screen, resources.screen)
        assertSame(targets.batch, resources.batch)
    }

    /**
     * Every type reachable from [root]'s public members, to a bounded depth.
     *
     * Bounded because the LibGDX types on the far side of `batch` open onto the whole of gdx,
     * and the question here is about the resources object's own shape: whether a capturable
     * target is one or two hops away from what an overlay factory is handed. Three hops is
     * comfortably past `screen.width`.
     */
    private fun reachableTypes(root: Class<*>, depth: Int = 3): Set<Class<*>> {
        val seen = LinkedHashSet<Class<*>>()
        var frontier = setOf(root)
        repeat(depth) {
            val next = LinkedHashSet<Class<*>>()
            for (type in frontier) {
                if (!type.name.startsWith("dev.wildware.udea")) continue
                for (method in type.methods) {
                    if (method.declaringClass == Any::class.java) continue
                    if (method.parameterCount != 0) continue
                    val returned = method.returnType
                    if (seen.add(returned)) next += returned
                }
            }
            frontier = next
        }
        return seen
    }

    /** The raw class behind a possibly-wildcarded generic argument. */
    private fun erase(type: Type): Class<*> = when (type) {
        is Class<*> -> type
        is WildcardType -> erase(type.lowerBounds.firstOrNull() ?: type.upperBounds.first())
        is ParameterizedType -> erase(type.rawType)
        else -> error("cannot erase $type")
    }
}
