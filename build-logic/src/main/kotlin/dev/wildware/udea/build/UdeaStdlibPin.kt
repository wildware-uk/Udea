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
}
