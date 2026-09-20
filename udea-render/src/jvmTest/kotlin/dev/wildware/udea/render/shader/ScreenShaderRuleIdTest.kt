package dev.wildware.udea.render.shader

import dev.wildware.udea.diagnostics.UdeaRules
import dev.wildware.udea.render.draw.Rgba
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The two halves of the shader vocabulary, held together: the ids `udea-render` states and the
 * rules `udea-diagnostics` registers.
 *
 * ## Why this test has to exist
 *
 * `udea-render` ships inside every game, so `udea-diagnostics` is deliberately **test-only** here
 * (`RenderModuleGraphTest`) and [ScreenShaderException] names its rule ids as plain string
 * constants. `UdeaRules`' own documentation is blunt about what that risks: "a producer-local id
 * is not an id at all". This is what makes them the same id anyway - the test classpath can see
 * both registries, so renumbering a rule, or restating one wrongly here, fails a test rather than
 * shipping two names for one defect.
 *
 * The rest of the class covers the checks [UdeaShader.fragment] makes before a driver is ever
 * asked, which need no graphics context: they are about the text and the declarations.
 */
class ScreenShaderRuleIdTest {

    @Test
    fun `the ids udea-render states are the ids udea-diagnostics registers`() {
        assertEquals(
            UdeaRules.SHADER_COMPILE_FAILED.id,
            ScreenShaderException.COMPILE_FAILED,
            "udea-render states this id as a literal because it may not ship udea-diagnostics; " +
                "the two have drifted, so a game and the build now name one defect differently",
        )
        assertEquals(
            UdeaRules.SHADER_UNIFORM_NOT_DECLARED.id,
            ScreenShaderException.UNIFORM_NOT_DECLARED,
            "as above, for the uniform rule",
        )
    }

    @Test
    fun `a shader that states its own version is refused, with the reason`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            UdeaShader.fragment(
                path = "shaders/versioned.frag",
                source = "#version 330\nvec4 udeaMain(vec2 uv) { return vec4(uv, 0.0, 1.0); }",
            )
        }
        assertTrue(
            "OpenGL ES" in refused.message.orEmpty(),
            "the refusal has to say why the engine owns that line, or the author deletes it and " +
                "learns nothing: ${refused.message}",
        )
    }

    @Test
    fun `a source that mentions version only in a comment is refused too, and the reason is stated`() {
        // The control for the check above: a fence that fails on prose is as wrong as one that
        // passes on a real breach, so this pins which way round this one is. It refuses, and
        // `UdeaShader`'s documentation says why - a `#version` in a comment is about to be copied
        // down one line by whoever reads it.
        assertFailsWith<IllegalArgumentException> {
            UdeaShader.fragment(
                path = "shaders/commented.frag",
                source = "// no #version here, the engine writes it\nvec4 udeaMain(vec2 uv) { return vec4(1.0); }",
            )
        }
    }

    @Test
    fun `a body with no udeaMain is refused`() {
        assertFailsWith<IllegalArgumentException> {
            UdeaShader.fragment("shaders/empty.frag", "vec4 notTheEntryPoint(vec2 uv) { return vec4(1.0); }")
        }
    }

    @Test
    fun `an absolute path is refused, so a build machine's layout cannot reach a shipped error`() {
        assertFailsWith<IllegalArgumentException> {
            UdeaShader.fragment("/home/someone/game/shaders/a.frag", VALID_SOURCE)
        }
        assertFailsWith<IllegalArgumentException> {
            UdeaShader.fragment("C:\\game\\shaders\\a.frag", VALID_SOURCE)
        }
    }

    @Test
    fun `a uniform cannot take a name the engine supplies, or repeat one, or be a non-identifier`() {
        val supplied = assertFailsWith<IllegalArgumentException> {
            UdeaShader.fragment("shaders/clash.frag", VALID_SOURCE) { float("uTime") }
        }
        assertTrue("uTime" in supplied.message.orEmpty(), supplied.message.orEmpty())

        assertFailsWith<IllegalArgumentException> {
            UdeaShader.fragment("shaders/twice.frag", VALID_SOURCE) {
                float("uLevels")
                float("uLevels")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            UdeaShader.fragment("shaders/odd.frag", VALID_SOURCE) { float("2levels") }
        }
    }

    @Test
    fun `a palette needs at least two colours and an outline needs a positive width`() {
        assertFailsWith<IllegalArgumentException> { ScreenEffects.palette(listOf(Rgba.WHITE)) }
        assertFailsWith<IllegalArgumentException> { ScreenEffects.outline(width = 0f) }
    }

    @Test
    fun `the built-in effects declare the uniforms their own sources declare`() {
        // The same check `GlScreenProgram` makes before it asks a driver for anything, applied to
        // the engine's own two: a built-in whose Kotlin and GLSL disagreed would fail at startup
        // in every game that registered it, and this catches it without a graphics context.
        for (shader in listOf(ScreenEffects.palette(listOf(Rgba.BLACK, Rgba.WHITE)), ScreenEffects.outline())) {
            val inSource = DECLARATION.findAll(shader.source).map { it.groupValues[1] }.toList()
            for (uniform in shader.declared) {
                assertTrue(
                    uniform.name in inSource,
                    "${shader.path} declares '${uniform.name}' in Kotlin, but its source declares " +
                        "only ${inSource.joinToString()}",
                )
            }
        }
    }

    private companion object {
        const val VALID_SOURCE: String = "vec4 udeaMain(vec2 uv) { return vec4(uv, 0.0, 1.0); }"

        /** The same shallow reading `GlScreenProgram` does; see its own note on why it is shallow. */
        val DECLARATION: Regex = Regex("""\buniform\s+(?:lowp\s+|mediump\s+|highp\s+)?\w+\s+(\w+)""")
    }
}
