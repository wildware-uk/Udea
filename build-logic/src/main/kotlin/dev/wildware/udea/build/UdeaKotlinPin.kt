package dev.wildware.udea.build

/**
 * The rule behind `udeaVerifyKotlinPin`, kept out of the Gradle task so a test can execute
 * its failure paths.
 *
 * The catalog's `kotlin` version is the version the build *compiles* with. On its own it
 * says nothing about the `kotlin-stdlib` that ends up on a classpath, because Gradle
 * resolves the highest requested version and Fleks and KotlinPoet each request a newer one.
 * Left alone, every `udea-*` module compiled with 2.2.10 against a 2.3.21 stdlib and no
 * message said so.
 *
 * That is the wrong direction. Metadata from a newer stdlib is read by the older compiler
 * under a tolerance warning, and a call site resolved against a 2.3 signature fails with
 * `NoSuchMethodError` on whatever stdlib the classloader actually hands over. For the jars
 * loaded *inside* the compiler — `udea-codegen`, `udea-compiler-plugin`,
 * `udea-assets-compiler` (spec 7) — that classloader is the compiler's own and stdlib
 * loading is parent-first, so the mismatch is certain rather than merely possible.
 *
 * [UdeaStdlibPin] pins the resolved version back down in one place. This is the check that
 * proves the pin still holds, and it names both versions so the message alone says what to
 * change.
 */
public object UdeaKotlinPin {

    /** Where [pinned] comes from, quoted in the failure message so the fix is obvious. */
    private const val CATALOG: String = "gradle/libs.versions.toml"

    /**
     * The message to fail the build with when a module has a resolvable classpath nobody has
     * classified, or `null` when every one of them is accounted for.
     *
     * [violation] can only ever look at the configurations [UdeaStdlibPin] already forces,
     * because that is the only place a resolved stdlib is collected from. That makes it
     * structurally blind to the case it exists for: a resolvable classpath outside
     * [UdeaStdlibPin.PINNED_CONFIGURATIONS] resolves whatever Gradle's highest-wins picks,
     * and neither the force nor the check says a word. It is not hypothetical — it is the
     * original bug, where the pin covered compile and runtime only.
     *
     * So this is the check that closes it: every resolvable configuration must be pinned,
     * exempt, or a declared tool classpath. Adding a source set therefore fails the build
     * until somebody says which of the three its classpaths are.
     *
     * @param projectPath the Gradle path of the module being checked, for the message.
     * @param resolvable every `canBeResolved` configuration the module has. An **empty** set
     *   is a broken hand-off, not a clean module: `resolvableConfigurationNames` is a
     *   `SetProperty` whose unset value is the empty set, so an `afterEvaluate` block that
     *   never ran, or one that ran before a plugin created its configurations, would leave
     *   this gate passing on every build forever. [violation] refuses an empty input for the
     *   same reason and so does `HeadlessScan`; this is the third instance of one rule.
     * @param unclassified the output of [UdeaStdlibPin.unclassified] for that module.
     */
    public fun coverageViolation(
        projectPath: String,
        resolvable: Collection<String>,
        unclassified: List<String>,
    ): String? {
        if (resolvable.isEmpty()) {
            return "$projectPath reported no resolvable configurations at all - the coverage " +
                "check is broken, not the tree. Every JVM Kotlin module has at least a " +
                "compileClasspath; a check that classifies an empty list passes forever, and " +
                "the hand-off it depends on is an afterEvaluate block that may simply not " +
                "have run."
        }
        if (unclassified.isEmpty()) return null
        return "$projectPath has ${unclassified.size} resolvable configuration(s) the " +
            "kotlin-stdlib pin neither covers nor excuses: " + unclassified.joinToString() +
            ". Such a classpath resolves whatever version Gradle's highest-wins rule picks, " +
            "and udeaVerifyKotlinPin cannot see it, so a stdlib newer than the compiler gets " +
            "in exactly the way the pin exists to stop. Classify each one: add it to " +
            "UdeaStdlibPin.PINNED_CONFIGURATIONS if the module compiles or runs against it, " +
            "record a UdeaStdlibPin.Exemption with a reason if it must resolve something " +
            "else, or a UdeaStdlibPin.ToolClasspath if it belongs to the Kotlin plugin's " +
            "own tooling rather than to this project."
    }

    /**
     * The message to fail the build with, or `null` when every resolved stdlib artifact is
     * at [pinned].
     *
     * @param projectPath the Gradle path of the module being checked, for the message.
     * @param pinned the catalog Kotlin version, which is also the compiler version.
     * @param resolved `<configuration> <module>:<version>` for every `kotlin-stdlib*`
     *   artifact resolved by the module across its pinned configurations.
     */
    public fun violation(
        projectPath: String,
        pinned: String,
        resolved: List<String>,
    ): String? {
        if (resolved.isEmpty()) {
            return "$projectPath resolved no kotlin-stdlib artifact at all - the pin check is " +
                "broken, not the tree. Every JVM Kotlin module has a stdlib; a check that " +
                "inspects an empty list passes forever."
        }
        val offenders = resolved.filterNot { it.endsWith(":$pinned") }
        if (offenders.isEmpty()) return null
        return "$projectPath compiles with Kotlin $pinned ($CATALOG) but resolves " +
            offenders.joinToString() + ". Gradle takes the highest requested version, so a " +
            "transitive dependency can drag the stdlib forward silently; code compiled " +
            "against the newer stdlib API then fails with NoSuchMethodError at run time, not " +
            "at compile time. Raise the catalog kotlin version to match, hold the offending " +
            "library at a compatible release, or record a UdeaStdlibPin.Exemption with a reason."
    }
}
