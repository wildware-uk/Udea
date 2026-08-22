package dev.wildware.udea.build

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The K2 plugin switch spec 7 asks CI to exercise. Reading a build flag looks too small to
 * test until you consider what the wrong answer costs: a developer who believes the checkers
 * are off and a CI run that believes they are on.
 */
class UdeaBuildFlagsTest {

    @Test
    fun `the compiler plugin is enabled when the property is absent`() {
        assertTrue(UdeaBuildFlags.compilerPluginEnabled(null))
    }

    @Test
    fun `false disables it and true enables it`() {
        assertFalse(UdeaBuildFlags.compilerPluginEnabled("false"))
        assertTrue(UdeaBuildFlags.compilerPluginEnabled("true"))
    }

    @Test
    fun `a typo fails the build rather than defaulting to enabled`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            UdeaBuildFlags.compilerPluginEnabled("flase")
        }
        assertTrue("flase" in failure.message.orEmpty(), failure.message.orEmpty())
        assertTrue(UdeaBuildFlags.COMPILER_PLUGIN_ENABLED in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `case and whitespace are not silently accepted`() {
        // `-Pudea.compilerPlugin.enabled=FALSE` meaning "enabled" is precisely the silent
        // failure the strict parse exists to prevent.
        assertFailsWith<IllegalArgumentException> { UdeaBuildFlags.compilerPluginEnabled("FALSE") }
        assertFailsWith<IllegalArgumentException> { UdeaBuildFlags.compilerPluginEnabled(" false") }
    }
}
