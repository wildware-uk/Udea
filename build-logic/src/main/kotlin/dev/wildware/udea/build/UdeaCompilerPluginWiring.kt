package dev.wildware.udea.build

/**
 * Which compilations the K2 plugin is applied to, and with which options — as data, so the
 * decisions are testable without a Gradle build.
 *
 * `UdeaCompilerPluginSupport` is the adapter that hands these answers to the Kotlin Gradle
 * plugin. Everything that can be decided from a project path and a flag lives here instead,
 * because a rule expressed inside `isApplicable` is a rule no test can execute — and the
 * whole point of issue #164 is that the previous arrangement (a flag read into
 * `extraProperties` and consumed by nobody) could not fail.
 *
 * ### Why the strings are repeated rather than imported
 *
 * `UdeaCompilerPlugin` in `udea-compiler-plugin` mints the same plugin id and option names.
 * `build-logic` cannot depend on it: it is a subproject of the *outer* build, and a build
 * script cannot see a project it is about to compile. So the CLI contract is written twice,
 * and [dev.wildware.udea.build.UdeaCompilerPluginWiring] is the second copy. The parity of
 * the two copies is asserted by `CompilerPluginSwitchTest`, which reads
 * `udea-compiler-plugin`'s source and compares the literals — a hand-copied contract that
 * nothing compares is how the wiring silently stops applying anything.
 */
public object UdeaCompilerPluginWiring {

    /** `-Xplugin` plugin id; must equal `UdeaCompilerPlugin.PLUGIN_ID`. */
    public const val PLUGIN_ID: String = "dev.wildware.udea"

    /** Maven group of the plugin artifact, which is [UdeaCompilerPluginWiring] `group`. */
    public const val ARTIFACT_GROUP: String = "dev.wildware.udea"

    /** Maven name of the plugin artifact, and the Gradle path it is substituted with. */
    public const val ARTIFACT_NAME: String = "udea-compiler-plugin"

    /** Gradle path of the project that builds the plugin. */
    public const val PLUGIN_PROJECT_PATH: String = ":$ARTIFACT_NAME"

    /**
     * The version half of the coordinate `UdeaCompilerPluginSupport.getPluginArtifact`
     * returns — deliberately a version that exists in no repository.
     *
     * The coordinate is substituted to [PLUGIN_PROJECT_PATH] before anything resolves, so its
     * version is never looked up. Writing the real `1.0-SNAPSHOT` here would mean that a
     * substitution which silently stopped being registered could resolve a **stale published
     * jar** out of `mavenLocal()` instead — the build would be green and the checkers would be
     * whatever somebody published months ago. With a version nobody can publish, the same
     * breakage is a `Could not find dev.wildware.udea:udea-compiler-plugin` failure naming
     * this constant.
     */
    public const val ARTIFACT_VERSION: String = "substituted-to-project"

    /**
     * Prefix of the Kotlin Gradle plugin's per-compilation plugin classpaths
     * (`kotlinCompilerPluginClasspathMain`, `…Test`, `…TestFixtures`).
     *
     * `UdeaStdlibPin.TOOL_CONFIGURATIONS` already classifies `kotlin*` as a tool classpath,
     * so these do not need a new exemption from the stdlib pin — and must not get one:
     * forcing the project's stdlib onto the compiler that compiles the project is the exact
     * inversion that object exists to prevent.
     */
    public const val PLUGIN_CLASSPATH_PREFIX: String = "kotlinCompilerPluginClasspath"

    /**
     * A `udea-*` project the plugin is deliberately **not** applied to.
     *
     * @param projectPath Gradle path, e.g. `:udea-annotations`.
     * @param reason why applying it there is impossible or pointless. Blank fails
     *   `UdeaCompilerPluginWiringTest`.
     */
    public data class Exclusion(
        public val projectPath: String,
        public val reason: String,
    )

    /**
     * The three projects that cannot have the plugin applied to them, and why.
     *
     * All three are the same failure: the plugin's own runtime classpath. Compiling a module
     * with `-Xplugin=<udea-compiler-plugin.jar + its runtime classpath>` makes that module's
     * `compileKotlin` depend on `:udea-compiler-plugin:jar`, which depends on
     * `:udea-annotations:jar` and `:udea-diagnostics:jar`. Applying it to any of the three
     * therefore asks Gradle to build a jar in order to build itself, and Gradle answers with
     * a circular task dependency rather than with a diagnostic anybody can act on.
     *
     * The cost is bounded and stated: none of the three declares a `@Replicated` component,
     * so no checker has anything to say about them. `UdeaCompilerPluginWiringTest` asserts
     * this list is exactly the plugin project plus its own project dependencies, so adding a
     * fourth `implementation(project(...))` to `udea-compiler-plugin` without widening the
     * list is a failing test rather than a circular-dependency error days later.
     */
    public val EXCLUSIONS: List<Exclusion> = listOf(
        Exclusion(
            projectPath = PLUGIN_PROJECT_PATH,
            reason = "the plugin cannot be applied to the compilation that produces it: " +
                "compileKotlin would depend on this project's own jar.",
        ),
        Exclusion(
            projectPath = ":udea-annotations",
            reason = "on the plugin's runtime classpath, so applying the plugin here would " +
                "make :udea-annotations:compileKotlin depend on a jar built from it.",
        ),
        Exclusion(
            projectPath = ":udea-diagnostics",
            reason = "on the plugin's runtime classpath (it supplies the UdeaRules ids the " +
                "checkers report under), so the same cycle applies.",
        ),
    )

    /** Just the paths of [EXCLUSIONS]. */
    public val EXCLUDED_PROJECTS: Set<String> = EXCLUSIONS.map { it.projectPath }.toSet()

    /**
     * A `-P plugin:dev.wildware.udea:<key>=<value>` argument the build states explicitly.
     *
     * @param key the CLI option name, as minted by `UdeaCompilerPlugin`.
     * @param value `true`/`false`; the plugin's `UdeaCommandLineProcessor` rejects anything
     *   else outright.
     * @param reason why this build states the value rather than inheriting the plugin's
     *   default. Blank fails `UdeaCompilerPluginWiringTest`.
     */
    public data class PluginOption(
        public val key: String,
        public val value: String,
        public val reason: String,
    )

    /**
     * What the build asks the plugin for.
     *
     * `enabled` is deliberately absent. Its default is `true`, and the "off" case is served
     * by not applying the plugin at all — passing `enabled=false` would load the plugin into
     * every compilation in order to have it do nothing, which is a slower and less honest
     * way of saying the same thing than producing no `-Xplugin` argument.
     *
     * `kdocIndex`/`repoRoot` are absent for a different reason: nothing consumes a harvested
     * index yet, and wiring a producer with no consumer is the scaffolding this project
     * rejects. `repoRoot` would also put an absolute path into a compile task's input
     * properties, which makes the build cache non-relocatable.
     */
    public val OPTIONS: List<PluginOption> = listOf(
        PluginOption(
            key = "checkers",
            value = "true",
            reason = "the FIR checkers are the only reason the plugin is on a compilation at " +
                "all; stating it means a future change to the plugin's default cannot switch " +
                "this build's checkers off in silence.",
        ),
        PluginOption(
            key = "synthesis",
            value = "false",
            reason = "issue #43's IDE spike returned NO-GO: IntelliJ loads only its eleven " +
                "bundled K2 registrars, so a synthesised declaration would resolve in the " +
                "build and be red in the editor. Pinned here so a default flip cannot enable it.",
        ),
    )

    /**
     * True when the K2 plugin is applied to every compilation of [projectPath].
     *
     * @param projectPath Gradle path of the module.
     * @param enabled the value of `-Pudea.compilerPlugin.enabled`, as read by
     *   [UdeaBuildFlags.compilerPluginEnabled]. `false` is spec 7's degrade path and makes
     *   this return `false` for every project, so no `-Xplugin` argument is produced anywhere.
     */
    public fun appliesTo(projectPath: String, enabled: Boolean): Boolean =
        enabled &&
            LegacyDependencyRules.governs(projectPath) &&
            projectPath !in EXCLUDED_PROJECTS

    /**
     * True when [configurationName] is one compilation's plugin classpath.
     *
     * The Kotlin Gradle plugin also creates a bare `kotlinCompilerPluginClasspath` bucket that
     * declares dependencies without resolving them. Asking it for resolved artifacts is an
     * error, so the suffix is what separates the classpaths that can be inspected from the
     * one that cannot.
     */
    public fun isCompilationPluginClasspath(configurationName: String): Boolean =
        configurationName.length > PLUGIN_CLASSPATH_PREFIX.length &&
            configurationName.startsWith(PLUGIN_CLASSPATH_PREFIX)

    /**
     * What `udeaVerifyCompilerPlugin` fails on: the plugin classpath does not match what
     * [appliesTo] promised.
     *
     * This is the gate that makes the wiring un-rottable in both directions. Applying a
     * compiler plugin is invisible — a build in which `isApplicable` quietly started returning
     * `false`, or in which the dependency substitution stopped being registered, compiles
     * exactly as fast and exactly as green as one where the checkers ran. That is the state
     * issue #164 found the project in, and a wiring with no gate would drift back into it.
     *
     * @param projectPath Gradle path of the module being checked.
     * @param enabled the value of `-P${UdeaBuildFlags.COMPILER_PLUGIN_ENABLED}`.
     * @param pluginClasspaths names of the module's per-compilation plugin classpaths.
     * @param resolvedComponents `displayName` of every component resolved on those
     *   classpaths — `project :udea-compiler-plugin` when the substitution worked, a Maven
     *   coordinate when it did not.
     * @return the failure message, or `null` when the classpath matches the promise.
     */
    public fun classpathViolation(
        projectPath: String,
        enabled: Boolean,
        pluginClasspaths: Collection<String>,
        resolvedComponents: Collection<String>,
    ): String? {
        val expected = appliesTo(projectPath, enabled)
        val fromProject = "project $PLUGIN_PROJECT_PATH"
        val present = resolvedComponents.filter { ARTIFACT_NAME in it }
        return when {
            expected && pluginClasspaths.isEmpty() ->
                "$projectPath should have the K2 plugin applied, but it has no " +
                    "$PLUGIN_CLASSPATH_PREFIX* configuration at all, so this check inspected " +
                    "nothing. Either the Kotlin Gradle plugin is not applied to this module or " +
                    "UdeaCompilerPluginSupport was never applied to it."

            expected && present.isEmpty() ->
                "$projectPath should compile with the K2 plugin, but $ARTIFACT_NAME is on none " +
                    "of ${pluginClasspaths.sorted()}. UdeaCompilerPluginSupport.isApplicable " +
                    "returned false for a project UdeaCompilerPluginWiring.appliesTo accepts, " +
                    "so no -Xplugin argument is produced and the FIR checkers are silently off."

            expected && present.none { it == fromProject } ->
                "$projectPath resolves $ARTIFACT_NAME from $present instead of from " +
                    "'$fromProject'. The dependency substitution in " +
                    "UdeaCompilerPluginSupport.apply is not in effect, so the compilation is " +
                    "using a published jar rather than the plugin this build just compiled."

            !expected && present.isNotEmpty() ->
                "$projectPath must not compile with the K2 plugin (" +
                    (skipReason(projectPath, enabled) ?: "no reason recorded") +
                    "), but $present is on one of ${pluginClasspaths.sorted()}."

            else -> null
        }
    }

    /**
     * Why [appliesTo] said no, for a build report or a test failure message.
     *
     * @return `null` when the plugin *is* applied to [projectPath].
     */
    public fun skipReason(projectPath: String, enabled: Boolean): String? = when {
        !enabled ->
            "-P${UdeaBuildFlags.COMPILER_PLUGIN_ENABLED}=false: spec 7's degrade path, so no " +
                "module gets the K2 plugin"

        !LegacyDependencyRules.governs(projectPath) ->
            "$projectPath is not part of the rewrite tree; the plugin is applied to udea-* " +
                "and moba only"

        else -> EXCLUSIONS.firstOrNull { it.projectPath == projectPath }?.reason
    }
}
