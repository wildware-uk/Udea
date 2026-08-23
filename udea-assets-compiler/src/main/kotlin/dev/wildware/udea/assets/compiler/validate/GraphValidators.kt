package dev.wildware.udea.assets.compiler.validate

import dev.wildware.udea.assets.compiler.Ref
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import dev.wildware.udea.diagnostics.UdeaRule

/**
 * Two declarations claim one asset id.
 *
 * The old tree could not even see this defect: `Asset`'s `equals`/`hashCode` were keyed on
 * `path` alone, so the thirteen assets declared in one file compared equal to each other and
 * collapsed to a single entry in any `Set` or `Map`. Here identity is the id, and two
 * declarations of one id is a build failure naming both.
 *
 * ### Why it reads the declaration list and not the graph
 *
 * `AssetGraph` is keyed by id, so the losing declaration is already gone by the time a graph
 * exists — `AssetGraph.of`'s own KDoc says "last writer wins on a duplicate id (pass 3 reports
 * those)". [ValidationContext.declared] is the ordered list with duplicates intact, which is
 * why the context is built from it.
 *
 * ### What it cannot see
 *
 * Nothing, given a full pass-2 declaration list. Given a context built from a graph instead
 * (`ValidationContext(graph.assets.values.toList(), ...)`), every duplicate is already
 * collapsed and this validator finds none — silently, because there is nothing left to find.
 * A caller that wants this check must pass `AssetCompileResult.declared`, which
 * [ValidationContext.of] does.
 */
public object DuplicateIdValidator : AssetValidator {

    override val rules: List<UdeaRule> = listOf(AssetValidationRules.DUPLICATE_ID)

    override fun validate(context: ValidationContext): List<UdeaDiagnostic> =
        context.declared
            .groupBy { it.id }
            .filterValues { it.size > 1 }
            .toSortedMap()
            .flatMap { (id, declarations) ->
                // Pass 2 knows *that* an id was declared twice; pass 1 knows *where* each one
                // was written. `DeclaredAsset.origin` is null unless origin capture was on, and
                // one span per id would anchor both diagnostics at the same line, so the two
                // lists are zipped by position - they are both in source order within a file.
                val spans = context.spansFor(id)
                val located = declarations.mapIndexed { index, declaration ->
                    declaration to (declaration.origin ?: spans.getOrNull(index))
                }
                val (first, firstSpan) = located.first()
                // One diagnostic per *extra* declaration, anchored at the extra one: that is
                // the line an author deletes or renames, and anchoring at the first would send
                // an editor to the declaration that is arguably correct.
                located.drop(1).map { (duplicate, span) ->
                    AssetValidationRules.DUPLICATE_ID.diagnostic(
                        message = "`$id` is declared more than once: `${duplicate.kind}` here and " +
                            "`${first.kind}` at ${firstSpan ?: "an unknown location"}. " +
                            "The later declaration wins, so the earlier one is unreachable.",
                        span = span,
                        assetId = id,
                    )
                }
            }
}

/**
 * An asset's `parent` chain comes back to itself.
 *
 * `Blueprint` flattens its parents at build time and the runtime does zero parent walking, so a
 * cycle is not a slow flatten — it is a flatten with no fixed point. The code this replaces
 * (`common/.../blueprints.kt`) recursed the chain on *every spawn*, which turns a cycle into a
 * `StackOverflowError` in the middle of a match rather than a diagnostic at build time.
 *
 * ### Iterative, deliberately
 *
 * The walk is an explicit loop over a three-colour map, not a recursion, so a ten-thousand-link
 * chain is a linear walk and not the stack overflow this rule exists to prevent. A validator
 * that crashed on the input it was written to detect would be a joke; `BlueprintCycleTest`
 * asserts a chain far longer than any JVM stack.
 *
 * ### Any `parent`, not only `blueprint`
 *
 * The edge is "a field named `parent` holding a reference", whatever the declaring kind. The
 * provisional DSL only puts one on `blueprint`, but a kind added later inherits the check
 * instead of inheriting the bug.
 */
public object BlueprintCycleValidator : AssetValidator {

    /** The field a parent edge lives in. */
    public const val PARENT_FIELD: String = "parent"

    override val rules: List<UdeaRule> = listOf(AssetValidationRules.BLUEPRINT_CYCLE)

    private const val UNVISITED = 0
    private const val IN_PROGRESS = 1
    private const val DONE = 2

    override fun validate(context: ValidationContext): List<UdeaDiagnostic> {
        val parents: Map<String, String> = context.graph.assets.values.mapNotNull { asset ->
            val parent = asset.fields[PARENT_FIELD] as? Ref ?: return@mapNotNull null
            // An edge to an id nothing declares is UDEA0004's diagnostic, and following it here
            // would report the same defect twice under a second rule id.
            if (context.resolve(parent.id) == null) return@mapNotNull null
            asset.id to parent.id
        }.toMap()
        if (parents.isEmpty()) return emptyList()

        val state = HashMap<String, Int>(parents.size * 2)
        val diagnostics = mutableListOf<UdeaDiagnostic>()

        for (start in parents.keys.sorted()) {
            if (state.getOrDefault(start, UNVISITED) != UNVISITED) continue
            val path = ArrayList<String>()
            var current: String? = start
            while (current != null) {
                when (state.getOrDefault(current, UNVISITED)) {
                    IN_PROGRESS -> {
                        // `current` is on the path we are still walking: everything from its
                        // first appearance onwards is the cycle. Reported once, from its
                        // entry point, and every member is then marked DONE below so the
                        // other two nodes of a three-node cycle do not each report it again.
                        val cycle = path.subList(path.indexOf(current), path.size) + current
                        diagnostics += report(context, cycle)
                        current = null
                    }
                    DONE -> current = null
                    else -> {
                        state[current] = IN_PROGRESS
                        path += current
                        current = parents[current]
                    }
                }
            }
            for (node in path) state[node] = DONE
        }
        return diagnostics
    }

    private fun report(context: ValidationContext, cycle: List<String>): UdeaDiagnostic {
        val head = cycle.first()
        val asset = context.resolve(head)
        val parentRef = asset?.fields?.get(PARENT_FIELD) as? Ref
        val shape = if (cycle.size == 2) "is its own parent" else "parent chain is a cycle"
        val length = cycle.size - 1
        return AssetValidationRules.BLUEPRINT_CYCLE.diagnostic(
            message = "`$head` $shape, a cycle of $length asset(s): ${render(cycle)}. Parents are " +
                "flattened at build time, so a cycle has nothing to flatten to; break the chain " +
                "by removing one `parent`.",
            span = asset?.let { context.spanFor(it, parentRef?.origin) },
            assetId = head,
        )
    }

    /**
     * The cycle as `` `a` -> `b` -> `a` ``, elided in the middle past [MAX_RENDERED] nodes.
     *
     * A diagnostic is read, and a ten-thousand-link chain rendered in full is a hundred kilobytes
     * of message that no author and no agent gets anything from. The two ends are what identify
     * the cycle; the count in the message is what says how much was left out.
     */
    private fun render(cycle: List<String>): String {
        if (cycle.size <= MAX_RENDERED) return cycle.joinToString(" -> ") { "`$it`" }
        val head = cycle.take(MAX_RENDERED - 2).joinToString(" -> ") { "`$it`" }
        val tail = cycle.last()
        return "$head -> ... (${cycle.size - (MAX_RENDERED - 1)} more) -> `$tail`"
    }

    /** How many nodes of a cycle a message spells out before eliding. */
    public const val MAX_RENDERED: Int = 12
}
