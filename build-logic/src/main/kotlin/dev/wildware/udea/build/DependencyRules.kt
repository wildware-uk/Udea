package dev.wildware.udea.build

import java.io.Serializable

/**
 * A stable identifier for one dependency rule, quoted verbatim in the failure message and
 * in `docs/module-graph.md`.
 *
 * A rule id is what lets a person searching for `UDEA-MG-002` land on the paragraph
 * explaining why the arrow is banned, years after whoever added it left. That is a domain
 * concept, so it is a type and not a `String`.
 */
@JvmInline
public value class RuleId(public val value: String) : Serializable {
    init {
        require(value.isNotBlank()) { "a rule id must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * A glob over normalised dependency coordinates — `group:module` for an external module,
 * the Gradle path (`:common`) for a project.
 *
 * `*` matches any run of characters, which is what `org.lwjgl:*`,
 * `com.badlogicgames.gdx:*-platform` and `org.jetbrains.kotlin:kotlin-scripting-*` need.
 * Everything else is literal: a rule that accidentally matched more than it named would be
 * worse than no rule, because the failure would point at an innocent module.
 */
@JvmInline
public value class CoordinatePattern(public val pattern: String) : Serializable {
    init {
        require(pattern.isNotBlank()) { "a coordinate pattern must not be blank" }
    }

    /** True when [coordinate] is matched by this pattern. */
    public fun matches(coordinate: String): Boolean = toRegex().matches(coordinate)

    private fun toRegex(): Regex =
        pattern.split('*').joinToString(separator = ".*") { Regex.escape(it) }.toRegex()

    override fun toString(): String = pattern
}

/**
 * One banned-arrow rule, expressed as data so that adding a rule is a one-line change and
 * so that the rule set can be asserted against in a unit test rather than only observed
 * through a Gradle build.
 *
 * A rule bans coordinates one of two ways, and exactly one of them must be used:
 *
 * - [banned] — a deny list. Anything matching is a violation unless [allowed] excuses it.
 *   This is the normal shape: "no GL on `udea-core`".
 * - [allowOnly] — an allow list. Anything *not* matching is a violation. Reserved for the
 *   leaf modules, where the budget is the point and any addition is the thing to catch.
 *
 * @param id the stable id, printed in the failure and documented in `docs/module-graph.md`.
 * @param summary one line, printed in the failure message above the offending coordinate.
 * @param rationale why the arrow is banned — the text `docs/module-graph.md` carries.
 * @param specSection the design-spec section the rule comes from, so it can be traced back.
 * @param projects Gradle paths the rule applies to. Empty means every project the owning
 *   task is registered on.
 * @param configurations configuration names the rule applies to. Empty means all scanned.
 */
public data class DependencyRule(
    public val id: RuleId,
    public val summary: String,
    public val rationale: String,
    public val specSection: String,
    public val projects: Set<String> = emptySet(),
    public val configurations: Set<String> = emptySet(),
    public val banned: List<CoordinatePattern> = emptyList(),
    public val allowed: List<CoordinatePattern> = emptyList(),
    public val allowOnly: List<CoordinatePattern>? = null,
) : Serializable {

    init {
        require(summary.isNotBlank()) { "$id has no summary; the failure message would name no reason" }
        require(rationale.isNotBlank()) { "$id has no rationale; docs/module-graph.md would have nothing to say" }
        require(banned.isEmpty() != (allowOnly == null)) {
            "$id must use exactly one of `banned` or `allowOnly`; using neither is a rule that " +
                "cannot fail, and using both hides which one decided."
        }
    }

    /** True when this rule governs [projectPath] on [configuration]. */
    public fun appliesTo(projectPath: String, configuration: String): Boolean =
        (projects.isEmpty() || projectPath in projects) &&
            (configurations.isEmpty() || configuration in configurations)

    /** True when [coordinate] breaks this rule. The root project itself never does. */
    public fun isViolatedBy(coordinate: String, rootProjectPath: String): Boolean {
        if (coordinate == rootProjectPath) return false
        if (allowed.any { it.matches(coordinate) }) return false
        val allowList = allowOnly
        return if (allowList == null) {
            banned.any { it.matches(coordinate) }
        } else {
            allowList.none { it.matches(coordinate) }
        }
    }
}

/**
 * One broken rule, carrying everything needed to diagnose it without re-running anything.
 *
 * [resolutionPath] is the part that matters: the whole reason these checks read the
 * *resolved* graph rather than declared dependencies is that the dangerous case is the one
 * nobody declared, and a message that names only the offending coordinate leaves you
 * grepping build files for something that is not in any of them.
 */
public data class DependencyViolation(
    public val ruleId: RuleId,
    public val summary: String,
    public val projectPath: String,
    public val configuration: String,
    public val coordinate: String,
    public val resolutionPath: List<String>,
) : Serializable {

    /** A single human-readable line-plus-path describing this violation. */
    public fun describe(): String = buildString {
        append(ruleId.value)
        append(' ')
        append(projectPath)
        append(' ')
        append(configuration)
        append(" -> ")
        append(coordinate)
        appendLine()
        append("    ")
        append(summary)
        appendLine()
        append("    resolution path: ")
        append(if (resolutionPath.isEmpty()) coordinate else resolutionPath.joinToString(" -> "))
    }
}

/**
 * Evaluates [DependencyRule]s against a [ResolvedGraph]. Pure, so every branch is reachable
 * from a unit test; the Gradle tasks contribute nothing but the graph and the failure.
 */
public object DependencyRules {

    /**
     * Every violation of [rules] visible on [configuration] of [projectPath].
     *
     * Sorted by rule id then coordinate so that a failure message is stable across runs —
     * an unstable message is one nobody can diff.
     */
    public fun violations(
        projectPath: String,
        configuration: String,
        graph: ResolvedGraph,
        rules: List<DependencyRule>,
    ): List<DependencyViolation> =
        rules.filter { it.appliesTo(projectPath, configuration) }
            .flatMap { rule ->
                graph.components()
                    .filter { rule.isViolatedBy(it, graph.root) }
                    .map { coordinate ->
                        DependencyViolation(
                            ruleId = rule.id,
                            summary = rule.summary,
                            projectPath = projectPath,
                            configuration = configuration,
                            coordinate = coordinate,
                            resolutionPath = graph.pathFromRoot(coordinate),
                        )
                    }
            }
            .sortedWith(compareBy({ it.ruleId.value }, { it.coordinate }))

    /**
     * The build-failure message for [violations], or `null` when there are none.
     *
     * @param heading names the gate that failed, so the message says which task to re-run.
     */
    public fun report(heading: String, violations: List<DependencyViolation>): String? {
        if (violations.isEmpty()) return null
        return buildString {
            append(heading)
            append(": ")
            append(violations.size)
            append(if (violations.size == 1) " violation" else " violations")
            appendLine()
            violations.forEach {
                appendLine(it.describe())
            }
        }.trimEnd()
    }
}
