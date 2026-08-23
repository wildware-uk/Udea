package dev.wildware.udea.gradle

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The generated flag: the two values it can take, and the fact that it takes them.
 *
 * The point of the whole file is that `AGENT_ALLOWED` is *not* a hand-written `true`, so the test
 * that matters is the `false` one - `udea-agent-host`'s own constant has passed every test ever
 * written about it while being `true` in every build there has ever been.
 */
class AgentBuildFlagsSourceTest {

    @Test
    fun `a development build is allowed to bind`() {
        val source = AgentBuildFlagsSource.render("dev.wildware.moba.agent", agentAllowed = true)
        assertContains(source, "package dev.wildware.moba.agent")
        assertContains(source, "public const val AGENT_ALLOWED: Boolean = true")
    }

    @Test
    fun `a release build is not`() {
        val source = AgentBuildFlagsSource.render("dev.wildware.moba.agent", agentAllowed = false)
        assertContains(source, "public const val AGENT_ALLOWED: Boolean = false")
    }

    /** `const`, so `if (!AGENT_ALLOWED)` folds and a release build has no reachable bind path. */
    @Test
    fun `the flag is a compile-time constant`() {
        assertContains(AgentBuildFlagsSource.render("a.b", true), "const val")
    }

    @Test
    fun `rendering is deterministic - the task that writes it is cacheable`() {
        val once = AgentBuildFlagsSource.render("a.b", false)
        assertEquals(once, AgentBuildFlagsSource.render("a.b", false))
    }

    @Test
    fun `a blank package is refused`() {
        assertFailsWith<IllegalArgumentException> { AgentBuildFlagsSource.render("  ", true) }
    }

    // --- the default package ---------------------------------------------------------------

    @Test
    fun `a group and a name make a package`() {
        assertEquals(
            "dev.wildware.udea.moba.agent",
            AgentBuildFlagsSource.defaultPackage("dev.wildware.udea", "moba"),
        )
    }

    /**
     * The two shapes that produced a file which did not compile.
     *
     * A project with no `group` gave `.bare-game.agent`, whose leading dot is a syntax error, and
     * a hyphenated project name gave an identifier Kotlin does not accept. Both were found by a
     * TestKit case failing on a missing file rather than by reading the code.
     */
    @Test
    fun `a blank group and a hyphenated name still make a legal package`() {
        assertEquals("bare_game.agent", AgentBuildFlagsSource.defaultPackage("", "bare-game"))
        assertEquals("my.game.agent", AgentBuildFlagsSource.defaultPackage("   ", "my.game"))
    }

    @Test
    fun `a segment that starts with a digit is prefixed`() {
        assertEquals("_2d.agent", AgentBuildFlagsSource.defaultPackage("", "2d"))
    }

    @Test
    fun `a name that sanitises to nothing falls back`() {
        assertEquals(
            AgentBuildFlagsSource.FALLBACK_PACKAGE,
            AgentBuildFlagsSource.defaultPackage("", "-"),
        )
    }

    /** The file name and the object name have to agree, or the generated file will not compile. */
    @Test
    fun `the file is named after the object`() {
        assertEquals("${AgentBuildFlagsSource.CLASS_NAME}.kt", AgentBuildFlagsSource.FILE_NAME)
        assertContains(
            AgentBuildFlagsSource.render("a.b", true),
            "public object ${AgentBuildFlagsSource.CLASS_NAME}",
        )
    }
}
