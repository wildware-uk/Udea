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
