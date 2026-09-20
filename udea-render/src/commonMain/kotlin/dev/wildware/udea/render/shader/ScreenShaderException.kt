package dev.wildware.udea.render.shader

/**
 * A screen shader the graphics driver would not take, reported the way every other Udea error is.
 *
 * ## Why an exception, rather than a log line
 *
 * A GLSL compile error is the one failure in this engine whose text belongs to somebody else, and
 * the temptation is to print the driver's message and carry on with the effect missing. The engine
 * does the opposite: this carries a stable [ruleId], the author's own [path] and [line], and the
 * driver's message quoted exactly - and it is thrown while the pipeline is being built, before the
 * first frame.
 *
 * Throwing is the important half. A shader that failed silently draws nothing, and "nothing" and
 * "the effect is subtle" look identical in a screenshot; the author then has one failing effect
 * and no idea which. Failing at startup means a shader either works from the first frame or stops
 * the game with a message naming the line.
 *
 * ## Why four plain fields and not a `UdeaDiagnostic`
 *
 * `UdeaDiagnostic` is the one diagnostic type in this engine (spec section 5), and this carries
 * exactly what it would: a stable rule id from the same registry, a repository-relative path, a
 * line, and the message. What it does not do is put `udea-diagnostics` on the runtime classpath of
 * every game that ever ships, which is what a public `UdeaDiagnostic` here would mean - `udea-render`
 * is inside all of them. `RenderModuleGraphTest` holds that fence and `ScreenShaderRuleIdTest`
 * holds these ids against `UdeaRules`, so the vocabulary is shared even though the class is not.
 * Handing out the richer type later is additive; un-shipping a dependency from released games is
 * not.
 *
 * [message] is the whole thing rendered for a terminal, so an unhandled throw is already readable.
 */
public class ScreenShaderException internal constructor(
    /** The stable rule id: [COMPILE_FAILED] or [UNIFORM_NOT_DECLARED]. */
    public val ruleId: String,
    /** The `.frag` the shader was authored in, repository-relative, as the game declared it. */
    public val path: String,
    /**
     * The line of [path] the failure is on, counting from 1, or `0` when there is no line to name.
     *
     * A driver reports a line of the text it was handed, which is the author's body with the
     * engine's header in front of it; the header's length is subtracted before it gets here. `0`
     * means the failure was in the header, or in the link, or in a check that is about the shader
     * as a whole rather than about one of its lines.
     */
    public val line: Int,
    /** What the driver said, or what the engine's own check found. Quoted, never paraphrased. */
    public val detail: String,
) : RuntimeException(render(ruleId, path, line, detail)) {

    public companion object {

        /**
         * The driver refused to compile or link the shader.
         *
         * `UdeaRules.SHADER_COMPILE_FAILED`, as a plain string. See the class documentation for
         * why this module states the id rather than importing the rule.
         */
        public const val COMPILE_FAILED: String = "UDEA0019"

        /**
         * A uniform declared in Kotlin that the shader's own source never declares.
         *
         * `UdeaRules.SHADER_UNIFORM_NOT_DECLARED`.
         */
        public const val UNIFORM_NOT_DECLARED: String = "UDEA0040"

        /** `path:line: [UDEA0019] detail`, the shape an editor and a terminal both parse. */
        private fun render(ruleId: String, path: String, line: Int, detail: String): String =
            "$path:$line: [$ruleId] $detail"
    }
}
