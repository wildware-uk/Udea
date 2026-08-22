package dev.wildware.udea.build

/**
 * The rule behind `udeaVerifyKotlinPin`, kept out of the Gradle task so a test can execute
 * its failure paths.
 *
 * `udea-codegen`, `udea-compiler-plugin` and `udea-assets-compiler` are jars that are
 * *loaded by the Kotlin compiler itself* — a KSP processor and a K2 plugin (spec 7). Class
 * loading for `kotlin-stdlib` is parent-first, so at load time they get the **compiler's**
 * stdlib, [UdeaVersions.KOTLIN], whatever they were compiled against. Compiling them
 * against a newer stdlib than that is how you get a green build and a `NoSuchMethodError`
 * inside someone else's build.
 *
 * The version catalog does not prevent this on its own: Gradle resolves the *highest*
 * requested version, and KotlinPoet and Fleks both request a newer stdlib than the
 * compiler this project pins. `udea.kotlin-build-tool` therefore pins the resolved stdlib
 * back down, and this check is what proves the pin still holds.
 */
public object UdeaKotlinPin {

    /**
     * The message to fail the build with, or `null` when every resolved stdlib artifact is
     * at [pinned].
     *
     * @param projectPath the Gradle path of the module being checked, for the message.
     * @param pinned the compiler version this jar will be loaded by.
     * @param resolved `<configuration> <module>:<version>` for every `kotlin-stdlib*`
     *   artifact resolved by the module, across compile and runtime.
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
        return "$projectPath is loaded by the Kotlin $pinned compiler (spec 7) but resolves " +
            offenders.joinToString() + ". A jar compiled against a newer stdlib API than the " +
            "stdlib on the classloader at load time fails with NoSuchMethodError, not at " +
            "compile time. Bump the project Kotlin version, hold the offending library at a " +
            "compatible release, or record why the pin in udea.kotlin-build-tool no longer applies."
    }
}
