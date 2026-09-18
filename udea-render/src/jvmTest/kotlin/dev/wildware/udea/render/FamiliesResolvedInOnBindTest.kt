package dev.wildware.udea.render

import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.render.bytecode.FamilyInRenderRule
import dev.wildware.udea.render.support.RepoLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `Family` handles are resolved once in [RenderSystem.onBind], never inside a frame.
 *
 * The rule is checked against bytecode rather than against source, because
 * `world.family { }` inside `render` compiles, works, and looks perfectly reasonable in a
 * diff -- it is just a lookup and an allocation on the hottest path in the module.
 *
 * The assertion is an exact list rather than "is empty", and that is deliberate: it fails if
 * a production renderer starts calling `family` in `render`, **and** it fails if the rule
 * stops detecting the one class that deliberately does. A rule nobody has watched fire is a
 * rule that might be matching nothing at all.
 */
class FamiliesResolvedInOnBindTest {

    @Test
    fun `the only class resolving a family inside render is the fixture that exists to prove the rule fires`() {
        val classFiles = RepoLayout.classFiles("udea-render", "main") +
            RepoLayout.classFiles("udea-render", "test")
        check(classFiles.isNotEmpty()) { "udea-render has no compiled classes; this check is vacuous" }

        val offenders = FamilyInRenderRule.violations(classFiles)

        assertEquals(
            listOf(FamilyInRenderFixture::class.java.name),
            offenders.map { it.className }.distinct(),
        )
        assertTrue(offenders.all { it.member.startsWith("render(") }, "${offenders.map { it.member }}")
    }

    @Test
    fun `a renderer that resolves its family in onBind is not flagged`() {
        // The control: the correct shape uses the same World and the same call, in the method
        // where doing it once is the whole point.
        val correct = RepoLayout.classFiles("udea-render", "test")
            .filter { it.name.startsWith("FamilyInOnBindFixture") }
        check(correct.isNotEmpty()) { "the compiled FamilyInOnBindFixture was not found" }

        assertEquals(emptyList(), FamilyInRenderRule.violations(correct))
    }
}

/**
 * The correct shape: one lookup, in [onBind], held in a field.
 *
 * Never rendered by any test -- it exists to be *compiled*, so that the rule above has a
 * negative control sitting right next to its positive one.
 */
internal class FamilyInOnBindFixture : RenderSystem {

    private var sprites: Family? = null

    override fun onBind(world: World, ctx: GameContext) {
        sprites = world.family { }
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        sprites?.numEntities
    }
}

/**
 * The mistake, written out: the family is resolved inside the frame.
 *
 * This class is the only violation [FamilyInRenderRule] is allowed to find in this module.
 * Deleting it does not make the suite greener -- it makes the test fail, because a rule with
 * nothing to catch has not been shown to catch anything.
 */
internal class FamilyInRenderFixture : RenderSystem {

    private var world: World? = null

    override fun onBind(world: World, ctx: GameContext) {
        this.world = world
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        world?.family { }
    }
}
