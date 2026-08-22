package dev.wildware.udea.build

/**
 * The structural invariants of the module graph (spec 4, spec 6), as data.
 *
 * Each of these is cheap to enforce now, while it passes trivially, and expensive to
 * retrofit once a module violates it — `UDEA-MG-005` in particular is a ratchet placed
 * before `moba` has any content, because `common/build.gradle.kts` shows exactly what
 * happens without it: five `kotlin-scripting-*` artifacts and `org.reflections:reflections`
 * on a shipped classpath.
 *
 * The `common` ban is deliberately *not* here. It is [LegacyDependencyRules], run by its
 * own task, because the two answer different questions and a single failure that could mean
 * either is a worse message than two that cannot.
 *
 * Every id is documented in `docs/module-graph.md`; [ALL] is asserted against that document
 * by `ModuleGraphRulesTest`, so a rule cannot be added without explaining itself.
 */
public object ModuleGraphRules {

    /**
     * Classpaths scanned. Same set as [LegacyDependencyRules.CONFIGURATIONS] — a rule
     * narrows it through [DependencyRule.configurations] rather than by being invisible on
     * a classpath nobody looked at.
     */
    public val CONFIGURATIONS: Set<String> = LegacyDependencyRules.CONFIGURATIONS

    /**
     * `udea-annotations` is on the compile classpath of the engine, the game, the KSP
     * processor and the K2 compiler plugin simultaneously, so anything it drags in is
     * dragged everywhere at once — including into the compiler's own classloader.
     */
    public val ANNOTATIONS_ARE_A_LEAF: DependencyRule = DependencyRule(
        id = RuleId("UDEA-MG-001"),
        summary = "udea-annotations resolves the Kotlin stdlib and nothing else",
        rationale = "The annotation vocabulary is consumed by the engine, the game, the KSP " +
            "processor and the K2 plugin at once. A dependency added here is added to all four, " +
            "and two of them are loaded inside the Kotlin compiler, where an extra jar is a " +
            "classloader conflict rather than an inconvenience.",
        specSection = "4",
        projects = setOf(":udea-annotations"),
        configurations = setOf("runtimeClasspath"),
        allowOnly = listOf(
            CoordinatePattern("org.jetbrains.kotlin:kotlin-stdlib"),
            CoordinatePattern("org.jetbrains:annotations"),
        ),
    )

    /**
     * The headless-kernel rule. Note what is *not* banned: `com.badlogicgames.gdx:gdx`
     * carries `Vector2` and the rest of gdx-math, which is headless. The ban is on GL and
     * on native loaders, not on maths.
     */
    public val NO_GL_OUTSIDE_RENDER: DependencyRule = DependencyRule(
        id = RuleId("UDEA-MG-002"),
        summary = "only udea-render may see a GL backend or a native platform artifact",
        rationale = "udea-core is a headless kernel: the simulation must run in a test JVM, in a " +
            "dedicated server and inside an agent harness with no display. Once a GL backend is " +
            "on the compile classpath, a static initialiser or a Gdx.gl reference gets written and " +
            "the headless path is gone. gdx-math (com.badlogicgames.gdx:gdx) is deliberately still " +
            "allowed - Vector2 is not GL.",
        specSection = "4, 3.5",
        projects = setOf(
            ":udea-core",
            ":udea-gas",
            ":udea-net",
            ":udea-agent",
            ":udea-assets",
            ":udea-assets-compiler",
            ":udea-codegen",
        ),
        configurations = setOf("compileClasspath", "runtimeClasspath"),
        banned = listOf(
            CoordinatePattern("com.badlogicgames.gdx:gdx-backend-lwjgl3"),
            CoordinatePattern("org.lwjgl:*"),
            CoordinatePattern("com.badlogicgames.gdx:*-platform"),
        ),
    )

    /**
     * The asset compiler runs in two places — a Gradle task and the dev daemon — and both
     * must run identical code, or "it compiled in the IDE" and "it compiled in CI" stop
     * meaning the same thing.
     */
    public val ASSETS_COMPILER_HAS_NO_GRADLE: DependencyRule = DependencyRule(
        id = RuleId("UDEA-MG-003"),
        summary = "udea-assets-compiler holds zero Gradle types",
        rationale = "The five-pass asset compiler is one implementation behind both the Gradle " +
            "task and the dev daemon. A Gradle type in the compiler makes the daemon path either " +
            "impossible or a second implementation, and a second implementation is how CI and the " +
            "IDE come to disagree about whether an asset is valid.",
        specSection = "4",
        projects = setOf(":udea-assets-compiler"),
        banned = listOf(
            CoordinatePattern("org.gradle:*"),
            // gradleApi() and gradleTestKit() are file dependencies, not modules: they reach
            // the classpath as loose jars under one display name and are invisible to a
            // component-graph-only scan. This pattern is what catches the leak the old
            // `gradle-plugin` module shipped through `implementation(gradleApi())`.
            CoordinatePattern("file:Gradle *"),
        ),
    )

    /**
     * The old `gradle-plugin` module declared `implementation(gradleApi())` and `example`
     * depended on it, which shipped the Gradle API inside the game. `udea-gradle` is applied
     * as a plugin and depended on by nothing.
     */
    public val NOBODY_DEPENDS_ON_UDEA_GRADLE: DependencyRule = DependencyRule(
        id = RuleId("UDEA-MG-004"),
        summary = "no udea-* or moba runtime classpath may resolve udea-gradle",
        rationale = "A Gradle plugin is applied, never depended on. The old gradle-plugin module " +
            "was depended on by example, and its implementation(gradleApi()) put the whole Gradle " +
            "API on the shipped game's runtime classpath. Here gradleApi() is compileOnly and this " +
            "rule closes the other half of the same hole.",
        specSection = "4",
        configurations = setOf("runtimeClasspath", "testRuntimeClasspath"),
        banned = listOf(CoordinatePattern(":udea-gradle")),
    )

    /**
     * Passes trivially while `moba` is empty. That is the point: it is placed before Phase 2
     * has a reason to reach for `kotlin-scripting-jvm-host`.
     */
    public val NO_SCRIPTING_OR_REFLECTION_IN_THE_GAME: DependencyRule = DependencyRule(
        id = RuleId("UDEA-MG-005"),
        summary = "the shipped game carries no Kotlin scripting host and no classpath scanner",
        rationale = "common pulls in five kotlin-scripting-* artifacts and org.reflections:" +
            "reflections today, which is both a startup cost and the mechanism behind the " +
            "reflection-on-hot-paths smell the rewrite exists to kill. Asset scripts are compiled " +
            "at build time; discovery is codegen and ServiceLoader, not classpath scanning.",
        specSection = "6 (Phase 2 exit), 3.6",
        projects = setOf(":moba"),
        configurations = setOf("runtimeClasspath"),
        banned = listOf(
            CoordinatePattern("org.jetbrains.kotlin:kotlin-scripting-*"),
            CoordinatePattern("org.jetbrains.kotlin:kotlin-reflect"),
            CoordinatePattern("org.reflections:reflections"),
        ),
    )

    /** Every rule, in id order. */
    public val ALL: List<DependencyRule> = listOf(
        ANNOTATIONS_ARE_A_LEAF,
        NO_GL_OUTSIDE_RENDER,
        ASSETS_COMPILER_HAS_NO_GRADLE,
        NOBODY_DEPENDS_ON_UDEA_GRADLE,
        NO_SCRIPTING_OR_REFLECTION_IN_THE_GAME,
    )

    /** True when [projectPath] is part of the rewrite tree and therefore subject to [ALL]. */
    public fun governs(projectPath: String): Boolean = LegacyDependencyRules.governs(projectPath)

    /** Every violation visible on [configuration] of [projectPath]. */
    public fun violations(
        projectPath: String,
        configuration: String,
        graph: ResolvedGraph,
    ): List<DependencyViolation> =
        DependencyRules.violations(projectPath, configuration, graph, ALL)

    /** The build-failure message, or `null` when [violations] is empty. */
    public fun report(violations: List<DependencyViolation>): String? =
        DependencyRules.report("udeaVerifyModuleGraph", violations)
}
