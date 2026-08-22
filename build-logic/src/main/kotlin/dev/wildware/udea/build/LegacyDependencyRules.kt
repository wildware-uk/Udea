package dev.wildware.udea.build

/**
 * The `common` ban (spec 4), the one rule the rewrite cannot survive without.
 *
 * The old tree and the `udea-*` tree coexist until the Phase 6 exit. Spec 7 rates a leak
 * between them as the top structural risk and says why: the symptom appears far from the
 * cause. A `udea-*` module that ends up with `common` on its classpath does not fail —
 * it compiles, and then two `UdeaNetworked` declarations exist on one classpath, or the
 * `object Assets` global is reachable again, and the thing that breaks is somewhere else
 * entirely. Copying a file forward deliberately is the supported way to reuse old code.
 *
 * The check reads the **resolved** graph rather than declared dependencies precisely
 * because the dangerous case is the dependency nobody declared: one hop through a module
 * that still has an old-tree edge is enough.
 *
 * `:example` is banned along with `common` for a second reason: it depends on
 * `:gradle-plugin`, whose `implementation(gradleApi())` puts the whole Gradle API on the
 * runtime classpath of anything downstream of it.
 */
public object LegacyDependencyRules {

    /** Stable id for the ban, quoted in failures and in `docs/module-graph.md`. */
    public val ID: RuleId = RuleId("UDEA-LEGACY-001")

    /**
     * Classpaths scanned.
     *
     * The Kotlin plugin's own tool classpaths (`ksp`, `kotlinCompilerPluginClasspath`) are
     * deliberately excluded: what they carry is the compiler's business, not the module's
     * API, and an old-tree jar cannot reach shipped code through them.
     */
    public val CONFIGURATIONS: Set<String> = setOf(
        "compileClasspath",
        "runtimeClasspath",
        "testCompileClasspath",
        "testRuntimeClasspath",
        "testFixturesCompileClasspath",
        "testFixturesRuntimeClasspath",
    )

    /** The single rule, evaluated by [DependencyRules]. */
    public val RULE: DependencyRule = DependencyRule(
        id = ID,
        summary = "no udea-* or moba project may resolve an old-tree project",
        rationale = "The old tree is replaced module by module and deleted at the Phase 6 exit. " +
            "Two coexisting module trees on one classpath is spec 7's top structural risk: the " +
            "duplicate declarations and revived globals it produces surface far from the module " +
            "that added the edge. Anything needed from the old tree is copied forward file by " +
            "file, with the copy reviewed.",
        specSection = "4",
        configurations = CONFIGURATIONS,
        banned = listOf(
            CoordinatePattern(":common"),
            CoordinatePattern(":gradle-plugin"),
            CoordinatePattern(":level-editor"),
            CoordinatePattern(":idea-plugin"),
            CoordinatePattern(":compose-ui"),
            CoordinatePattern(":example"),
            CoordinatePattern(":example:*"),
        ),
    )

    /** True when [projectPath] is part of the rewrite tree and therefore subject to [RULE]. */
    public fun governs(projectPath: String): Boolean =
        projectPath.startsWith(":udea-") || projectPath == ":moba"

    /** Every violation of [RULE] on [configuration] of [projectPath]. */
    public fun violations(
        projectPath: String,
        configuration: String,
        graph: ResolvedGraph,
    ): List<DependencyViolation> =
        DependencyRules.violations(projectPath, configuration, graph, listOf(RULE))

    /** The build-failure message, or `null` when [violations] is empty. */
    public fun report(violations: List<DependencyViolation>): String? =
        DependencyRules.report("udeaVerifyNoLegacyDependencies", violations)
}
