package dev.wildware.udea.build

/**
 * The decision a zero-dependency-leaf gate makes, lifted out of the Gradle task that
 * enforces it.
 *
 * The enforcement itself (`udeaVerifyAnnotationsLeaf` in `udea-annotations`) is a
 * `doLast` block, which nothing in the test suite can execute. Keeping the rule here as a
 * pure function is what gives it a test that can fail: `UdeaLeafCheckTest` drives every
 * branch, including the one that matters most — an *empty* resolved classpath is a broken
 * check, not a clean module, and must fail rather than pass vacuously. That branch is
 * reachable in practice: `gradle.properties` carries
 * `kotlin.stdlib.default.dependency = true`, and flipping it empties the leaf's
 * `runtimeClasspath`.
 */
public object UdeaLeafCheck {

    /**
     * The message to fail the build with, or `null` when [resolved] is a legal leaf
     * classpath.
     *
     * @param projectPath the Gradle path of the module being checked, for the message.
     * @param resolved `group:module` for everything on the module's `runtimeClasspath`.
     * @param allowed the `group:module` values the module is permitted to drag in.
     */
    public fun violation(
        projectPath: String,
        resolved: Set<String>,
        allowed: Set<String>,
    ): String? {
        if (resolved.isEmpty()) {
            return "$projectPath resolved nothing on runtimeClasspath - the leaf check is " +
                "broken, not the tree. A gate that compares an empty set against an allow " +
                "list passes forever while the module quietly accumulates dependencies."
        }
        val offenders = resolved.toSortedSet() - allowed
        if (offenders.isEmpty()) return null
        return "$projectPath must stay a zero-dependency leaf (spec 4), but its runtimeClasspath " +
            "resolves ${offenders.size} disallowed dependency/dependencies: " +
            offenders.joinToString() + ". Allowed: " + allowed.sorted().joinToString() + "."
    }
}
