package dev.wildware.udea.assets.compiler

import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.UdeaRule
import dev.wildware.udea.diagnostics.UdeaRules

/**
 * The rule ids the asset compiler's front ends raise.
 *
 * ### Why this is not in `UdeaRules`, and what a reviewer should decide
 *
 * `UdeaRules` says plainly that "a producer-local id is not an id", and it is right. These
 * four ids belong in that registry. They are here because `udea-diagnostics` is another
 * module being worked on concurrently and this wave was scoped to `udea-assets-compiler`
 * only; minting them in the shared file would have been an edit outside the module.
 *
 * The compromise is made safe rather than left implicit:
 *
 * - the ids sit in a reserved **UDEA002x band**, away from the sequential numbering
 *   `UdeaRules` is growing through, so a concurrent producer minting `UDEA0013` cannot
 *   collide with one of these;
 * - `AssetCompilerRulesTest` asserts the two id spaces are disjoint and that every id here
 *   matches [UdeaRules.ID_FORMAT], so the day someone moves them the move is mechanical;
 * - nothing here re-declares a defect `UdeaRules` already names. An unresolved
 *   `reference("...")` is [UdeaRules.UNRESOLVED_REFERENCE] and is raised from there.
 *
 * The intended end state is that this object is deleted and these four constants move into
 * `UdeaRules` **keeping their ids**, which is why the ids are already in that registry's
 * format and not a `udea:non-literal-id` style string.
 */
public object AssetCompilerRules {

    /**
     * A declaration's name is not a compile-time string literal, so pass 1 cannot know the id.
     *
     * This is a warning, not an error: the script may still evaluate perfectly in pass 2 and
     * produce a real id. What is lost is the *syntactic* knowledge — the id cannot be
     * validated, completed, or renamed before anything is compiled, and an editor cannot jump
     * to it. Authors who need a computed name have the sanctioned `forEach` over a constant
     * list of literals, which pass 1 does understand.
     */
    public val NON_LITERAL_ID: UdeaRule = UdeaRule(
        id = "UDEA0020",
        defaultSeverity = Severity.Warning,
        description = "an asset declaration's name is not a string literal, so its id is not " +
            "statically known",
    )

    /**
     * The Kotlin script compiler rejected a `.udea.kts`.
     *
     * Carries the compiler's own message with a repo-relative span, which is the whole point:
     * the runtime host this replaces answered a syntax error with
     * `error("Failed to compile ... ${e.stackTraceToString()}")`.
     */
    public val SCRIPT_COMPILATION_FAILED: UdeaRule = UdeaRule(
        id = "UDEA0021",
        defaultSeverity = Severity.Error,
        description = "the Kotlin script compiler rejected a .udea.kts source file",
    )

    /**
     * A `.udea.kts` compiled but threw while being evaluated, or the worker that was
     * evaluating it died.
     *
     * Separate from [SCRIPT_COMPILATION_FAILED] because the fixes are different in kind: a
     * compilation failure is a source edit, and an evaluation failure is usually a bad value
     * or — the case this rule exists for — a compiler that exhausted the worker heap.
     */
    public val SCRIPT_EVALUATION_FAILED: UdeaRule = UdeaRule(
        id = "UDEA0022",
        defaultSeverity = Severity.Error,
        description = "a .udea.kts threw during evaluation, or the isolated worker evaluating " +
            "it terminated abnormally",
    )

    /**
     * The transpiler (issue #87) met a construct it cannot rewrite into a
     * `fun build(scope: AssetScope)` body faithfully.
     *
     * Raised instead of emitting output that compiles and means something subtly different.
     * Only ever reachable in `ScriptMode.Transpiled`.
     */
    public val TRANSPILE_UNSUPPORTED: UdeaRule = UdeaRule(
        id = "UDEA0023",
        defaultSeverity = Severity.Error,
        description = "a .udea.kts uses a construct the transpiled front end cannot rewrite " +
            "faithfully into a build(scope) body",
    )

    /** Every rule this module raises that `UdeaRules` does not already own, in id order. */
    public val all: List<UdeaRule> = listOf(
        NON_LITERAL_ID,
        SCRIPT_COMPILATION_FAILED,
        SCRIPT_EVALUATION_FAILED,
        TRANSPILE_UNSUPPORTED,
    ).sortedBy { it.id }
}
