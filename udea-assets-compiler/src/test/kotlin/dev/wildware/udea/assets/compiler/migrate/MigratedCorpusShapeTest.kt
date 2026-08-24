package dev.wildware.udea.assets.compiler.migrate

import dev.wildware.udea.assets.compiler.AbilitySpecScope
import dev.wildware.udea.assets.compiler.AssetScope
import dev.wildware.udea.assets.compiler.ComponentScope
import dev.wildware.udea.assets.compiler.EntityScope
import dev.wildware.udea.assets.compiler.TestPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The syntactic properties of the game's asset tree, and the one trap its DSL could fall into.
 *
 * The tree is `moba/assets`, which is the *only* asset root this game has since `character`,
 * `gameplayEffect` and `effect` became published kinds and `moba/src/main/assets` was merged into
 * it and deleted.
 */
class MigratedCorpusShapeTest {

    private val scripts = dev.wildware.udea.assets.compiler.AssetCompiler
        .scriptsUnder(TestPaths.repoRoot.resolve("moba/assets"))

    /**
     * Issue #93's grep criterion: none of the three shapes the migration deletes survives.
     *
     * - `bundle { }` — the file is the bundle now, so the wrapper has nowhere to live.
     * - `lazy { }` — the top-level `fun lazy` in `common/.../assets/LazyList.kt` shadowed
     *   `kotlin.lazy` inside every script in the tree.
     * - a leading-slash resource path — `/sprites/orc/idle.png` and `sprites/orc/idle.png` were
     *   two keys for one file in the old loader (issue #84).
     */
    @Test
    fun `no migrated script contains a bundle wrapper, a lazy list or an absolute resource path`() {
        val offenders = scripts.mapNotNull { path ->
            // Line comments stripped first, for the reason `udeaVerifyGasTime` strips them: this
            // tree's scripts deliberately *quote* the old shapes they replaced - `arrow.udea.kts`
            // cites `loadSprite("/sprites/arrow/arrow.png", .1F)` - and a check that cannot tell a
            // citation from a declaration forces the documentation to go vague about what it fixed.
            val text = path.readText()
                .lineSequence()
                .filterNot { it.trimStart().startsWith("//") }
                .joinToString(separator = "\n")
            val found = buildList {
                if (Regex("""\bbundle\s*\{""").containsMatchIn(text)) add("bundle {")
                if (Regex("""\blazy\s*\{""").containsMatchIn(text)) add("lazy {")
                if (Regex(""""/[^"]*"""").containsMatchIn(text)) add("a leading-slash path")
            }
            if (found.isEmpty()) null else "${path.name}: ${found.joinToString()}"
        }
        assertEquals(emptyList<String>(), offenders)
    }

    /**
     * No nested builder shares a name with an [AssetScope] member.
     *
     * `UdeaTranspiler.qualifyScopeCalls` prefixes `scope.` onto every call whose callee name is
     * in [AssetScope.MEMBER_NAMES] — anywhere in the file, at any nesting depth. It is a
     * syntactic front end with no resolver, so it cannot tell an inner receiver from the outer
     * one. A builder method sharing a name with a scope member would therefore transpile into a
     * call on the wrong receiver, and it would still *compile*: `components = { spriteSheet(...) }`
     * would silently declare a sprite sheet instead of adding a component.
     *
     * That is the failure mode this test exists for, and it is why the animation-set builder was
     * dropped rather than named `spriteAnimation`.
     */
    @Test
    fun `no builder scope method collides with the transpiler's vocabulary`() {
        val builders = listOf(ComponentScope::class.java, AbilitySpecScope::class.java, EntityScope::class.java)
        val collisions = builders.flatMap { type ->
            type.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) }
                .map { it.name }
                .filterNot { "$" in it }
                .filter { it in AssetScope.MEMBER_NAMES }
                .map { "${type.simpleName}.$it" }
        }
        assertEquals(emptyList<String>(), collisions)
    }
}
