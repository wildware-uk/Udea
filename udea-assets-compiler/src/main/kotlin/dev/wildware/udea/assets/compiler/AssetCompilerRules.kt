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


    /**
     * A field value the bundle format has no encoding for (issue #89).
     *
     * `UDEA0024` is the id `docs/contracts/asset-index.md` records as "the next free id in this
     * module's reserved band", and this is the first thing to need one. It is a *value* defect,
     * not a kind defect: [UNPACKABLE_KIND] is about a declaration the runtime has no type for,
     * which is legal and ends up in an `OpaqueAsset`, while this is a field holding something -
     * a lambda, a `Lazy`, a game's own object - that has no bytes at all.
     */
    public val UNPACKABLE_VALUE: UdeaRule = UdeaRule(
        id = "UDEA0024",
        defaultSeverity = Severity.Error,
        description = "an asset field holds a value the .udeapak format cannot encode",
    )

    /**
     * A declaration whose kind has no `AssetData` type, met where a *runtime value* is required.
     *
     * Distinct from [dev.wildware.udea.assets.compiler.AssetKind.Unpublishable], which is the
     * same fact reported into the compile-time catalog and is not an error there: a catalog may
     * legitimately be short an entry. Packing cannot be short one - a graph with a hole is not a
     * graph - so the daemon raises this rather than inventing a value or dropping the asset.
     */
    public val UNPACKABLE_KIND: UdeaRule = UdeaRule(
        id = "UDEA0025",
        defaultSeverity = Severity.Error,
        description = "an asset declaration's kind has no AssetData implementation, so it cannot " +
            "be packed into a runtime graph",
    )

    /**
     * `udeaMigrateAssets` met something in a `.udea.kts` it will not rewrite by guessing.
     *
     * A **warning**, and that is the whole design of the migrator: one that fails the build on
     * the first construct it does not understand migrates nothing, and one that guesses produces
     * a corpus that compiles and means something else. So the file is rewritten as far as the
     * rules reach, the undecided span is marked in place with a `// TODO(udea-migrate)` comment,
     * and this is raised so the count is visible in a report rather than only in the diff.
     */
    public val MIGRATION_UNDECIDED: UdeaRule = UdeaRule(
        id = "UDEA0026",
        defaultSeverity = Severity.Warning,
        description = "the asset migrator could not decide how to rewrite a construct and left " +
            "it as written, marked with a TODO(udea-migrate)",
    )

    /** Every rule this module raises that `UdeaRules` does not already own, in id order. */
    public val all: List<UdeaRule> = listOf(
        NON_LITERAL_ID,
        SCRIPT_COMPILATION_FAILED,
        SCRIPT_EVALUATION_FAILED,
        TRANSPILE_UNSUPPORTED,
        UNPACKABLE_VALUE,
        UNPACKABLE_KIND,
        MIGRATION_UNDECIDED,
    ).sortedBy { it.id }
}
