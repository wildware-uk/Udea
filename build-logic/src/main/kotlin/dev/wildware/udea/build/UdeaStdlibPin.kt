package dev.wildware.udea.build

/**
 * Where the `kotlin-stdlib` version pin applies, and the exemptions from it.
 *
 * `gradle/libs.versions.toml` names one Kotlin version, and until this object existed that
 * version controlled the *compiler* and nothing else. Gradle resolves the highest requested
 * version of a module, so Fleks 2.14 (which asks for `kotlin-stdlib:2.3.21`) and KotlinPoet
 * 2.3.0 (2.3.20) between them dragged every `udea-*` classpath forward to 2.3.21 while the
 * build kept compiling with 2.2.10. Nothing said so; `./gradlew :udea-core:dependencies`
 * was the only way to find out.
 *
 * Compiling against a *newer* stdlib than the compiler is the direction that hurts:
 * the 2.2.10 compiler reads 2.3.x metadata under a tolerance warning, and a call resolved
 * against a 2.3 signature becomes a `NoSuchMethodError` on whatever stdlib is actually on
 * the classloader. For `udea-compiler-plugin` and the other jars loaded *inside* the
 * compiler that classloader is the compiler's own, parent-first, so the mismatch is
 * guaranteed rather than merely possible (spec 7).
 *
 * The pin therefore lives here, in one place, and every module gets it from
 * `udea.kotlin-library`. Escaping it requires an [Exemption] with a stated reason, which is
 * the difference between a deliberate exception and the silence this replaces.
 */
public object UdeaStdlibPin {

    /**
     * A configuration that is deliberately left off the pin.
     *
     * @param projectPath Gradle path of the module, e.g. `:udea-codegen`.
     * @param configuration exact configuration name, e.g. `testRuntimeClasspath`.
     * @param reason why the pin must not apply. Reviewed like any other code; a blank one
     *   fails [UdeaStdlibPinTest].
     */
    public data class Exemption(
        public val projectPath: String,
        public val configuration: String,
        public val reason: String,
    )

    /**
     * The classpath `udea-agent`'s `udeaAssetTools` test task runs on.
     *
     * Named here rather than spelled in that module's build script alone, so that the pin and the
     * configuration cannot drift into two names for one classpath.
     */
    public const val ASSET_TOOLS_RUNTIME: String = "assetToolsRuntime"

    /**
     * The classpath `udea-agent-host`'s `udeaPhase2Demo` runs on.
     *
     * The same arrangement as [ASSET_TOOLS_RUNTIME] and for the same reason: the Phase 2 demo
     * needs a real `AssetDaemon`, the daemon carries the Kotlin scripting host, and that host may
     * not reach `udea-agent-host`'s ordinary `testRuntimeClasspath`.
     */
    public const val ASSET_DAEMON_RUNTIME: String = "assetDaemonRuntime"

    /** Compile classpath of `UdeaAgentPlugin`'s debug source set, at its default name. */
    public const val AGENT_SOURCE_SET_COMPILE_CLASSPATH: String = "agentCompileClasspath"

    /** Runtime classpath of `UdeaAgentPlugin`'s debug source set, at its default name. */
    public const val AGENT_SOURCE_SET_RUNTIME_CLASSPATH: String = "agentRuntimeClasspath"

    /**
     * The classpaths a module compiles against, runs on, and tests on.
     *
     * Deliberately *not* every resolvable configuration: `ksp`, `kotlinCompilerPluginClasspath`
     * and friends are the Kotlin plugin's own tool classpaths, and forcing the project's
     * stdlib onto the tool that compiles the project is how you break the compiler with a
     * rule meant to protect it.
     */
    public val PINNED_CONFIGURATIONS: Set<String> = setOf(
        "compileClasspath",
        "runtimeClasspath",
        "testCompileClasspath",
        "testRuntimeClasspath",
        "testFixturesCompileClasspath",
        "testFixturesRuntimeClasspath",
        // The debug-only source set `UdeaAgentPlugin` creates on a game module (`:moba` today).
        // It exists so `udea-agent-host` reaches the agent entry point without ever touching
        // `runtimeClasspath`, which is what `ReleaseRules.CLASSPATH_RULE` scans and what the jar
        // is packaged from. These two are classpaths the module compiles and runs against like
        // any other, so they are pinned rather than exempted - and adding them here was not
        // optional: `udeaVerifyKotlinPin` failed `:moba` the moment the source set appeared,
        // which is precisely the "the next source set somebody adds" case this list exists for.
        AGENT_SOURCE_SET_COMPILE_CLASSPATH,
        AGENT_SOURCE_SET_RUNTIME_CLASSPATH,
        // `udea-agent`'s `assetToolsRuntime`: the classpath the `assets.*` toolset's tests run on,
        // which carries `udea-assets-compiler` and therefore the Kotlin scripting host. It is a
        // separate configuration precisely so that host never reaches `testRuntimeClasspath`,
        // where `AgentModuleBoundaryTest` bans it - udea-agent is compiled into every game. It is
        // a classpath a test JVM genuinely runs on, so it is pinned rather than exempted, and it
        // is here for the same reason the two above are: `udeaVerifyKotlinPin` failed the moment
        // the configuration appeared.
        ASSET_TOOLS_RUNTIME,
        // `udea-agent-host`'s `assetDaemonRuntime`: the classpath the Phase 2 exit demo runs on.
        // Identical in kind to the line above - a JVM genuinely runs on it, so it is pinned - and
        // it is here for the same reason: `udeaVerifyKotlinPin` failed `:udea-agent-host` the
        // moment the configuration appeared, which is the rule doing its job.
        ASSET_DAEMON_RUNTIME,
    )

    /**
     * Every configuration allowed to resolve a stdlib other than [UdeaVersions.KOTLIN].
     *
     * One entry, and it is load-bearing: `udea-codegen`'s tests run KSP2's standalone
     * compiler *in the test JVM*. That compiler is a newer Kotlin than this project's and
     * needs its own stdlib — `kotlin/jvm/internal/KotlinGenericDeclaration` does not exist
     * in ${UdeaVersions.KOTLIN}. The test JVM is not the classloader the processor ships
     * into, so pinning here would break a harness in order to protect nothing.
     */
    public val EXEMPTIONS: List<Exemption> = listOf(
        Exemption(
            projectPath = ":udea-codegen",
            configuration = "testCompileClasspath",
            reason = "KSP2's standalone compiler runs in this test JVM and needs its own newer stdlib; " +
                "the shipped processor jar is unaffected because compileClasspath and runtimeClasspath stay pinned.",
        ),
        Exemption(
            projectPath = ":udea-codegen",
            configuration = "testRuntimeClasspath",
            reason = "KSP2's standalone compiler runs in this test JVM and needs its own newer stdlib; " +
                "the shipped processor jar is unaffected because compileClasspath and runtimeClasspath stay pinned.",
        ),
    )

    /**
     * A resolvable configuration that is deliberately outside the pin because it is not a
     * classpath of *this project* at all.
     *
     * @param pattern the configuration name, with `*` allowed at either end.
     * @param reason why forcing the project's stdlib onto it would be wrong. Blank fails
     *   [UdeaStdlibPinTest].
     */
    public data class ToolClasspath(
        public val pattern: String,
        public val reason: String,
    ) {
        private val regex: Regex = Regex(
            pattern.split('*').joinToString(".*") { Regex.escape(it) },
        )

        /** True when [configurationName] is this tool classpath. */
        public fun matches(configurationName: String): Boolean = regex.matches(configurationName)
    }

    /**
     * Every resolvable configuration that is a *tool* classpath rather than a classpath the
     * project compiles or runs against.
     *
     * This list is what makes [PINNED_CONFIGURATIONS] checkable instead of merely declarative.
     * Before it existed, the pin forced six configuration names and the check inspected the
     * same six, so a resolvable classpath outside that list — the next source set somebody
     * adds — escaped the force *and* the check at once, silently. That is exactly the bug the
     * pin was introduced to fix: the previous pin covered compile and runtime only, and
     * `udea-codegen`'s tests quietly resolved 2.3.20. Whoever added `testFixtures` to
     * `udea-core` only got it covered by remembering to type two more names.
     *
     * With this list, every resolvable configuration must be one of: pinned, exempt with a
     * reason, or a tool classpath with a reason. Anything else fails `udeaVerifyKotlinPin`
     * and has to be classified, which is a decision someone makes rather than one that makes
     * itself.
     */
    public val TOOL_CONFIGURATIONS: List<ToolClasspath> = listOf(
        ToolClasspath(
            "kotlin*",
            "the Kotlin Gradle plugin's own tooling (the compiler, the build tools API, the " +
                "commonizer, compiler-plugin classpaths). Forcing the project's stdlib onto the " +
                "compiler that compiles the project is a rule meant to protect the compiler " +
                "breaking it instead.",
        ),
        ToolClasspath(
            "*KotlinScriptDefExtensions",
            "script-definition extensions for the Kotlin plugin, loaded by the compiler, not " +
                "by this project.",
        ),
        ToolClasspath(
            "ksp*",
            "KSP's processor and plugin classpaths run inside the compiler; udea-codegen's own " +
                "exemptions record why that JVM needs a newer stdlib than the project.",
        ),
        ToolClasspath(
            "annotationProcessor",
            "javac's annotation-processor path, a tool classpath with no Kotlin stdlib on it.",
        ),
        ToolClasspath(
            "*AnnotationProcessor",
            "javac's annotation-processor path for a non-main source set; same reasoning.",
        ),
        ToolClasspath(
            "*DependenciesMetadata",
            "the Kotlin plugin's multiplatform metadata views of the dependency declarations. " +
                "They resolve no JVM artifact, so there is no stdlib on them to pin.",
        ),
    )

    /** The exemptions recorded for [projectPath]. */
    public fun exemptionsFor(projectPath: String): List<Exemption> =
        EXEMPTIONS.filter { it.projectPath == projectPath }

    /**
     * The configurations of [projectPath] the pin applies to: [PINNED_CONFIGURATIONS] less
     * anything [EXEMPTIONS] excuses.
     */
    public fun pinnedConfigurationsFor(projectPath: String): Set<String> {
        val excused = exemptionsFor(projectPath).map { it.configuration }.toSet()
        return PINNED_CONFIGURATIONS - excused
    }

    /**
     * The resolvable configurations in [resolvableNames] that nothing has classified.
     *
     * A classified configuration is one of: in [PINNED_CONFIGURATIONS] (pinned, or exempt
     * from the pin with a reason), or matched by a [TOOL_CONFIGURATIONS] entry. Anything else
     * resolves whatever Gradle's highest-wins picks, with neither the force nor the check
     * looking at it — which is the drift the pin exists to prevent, escaping through a
     * configuration nobody listed.
     *
     * @param resolvableNames every configuration of the module with `canBeResolved = true`.
     */
    public fun unclassified(resolvableNames: Collection<String>): List<String> =
        resolvableNames
            .filterNot { it in PINNED_CONFIGURATIONS }
            .filterNot { name -> TOOL_CONFIGURATIONS.any { it.matches(name) } }
            .distinct()
            .sorted()
}
