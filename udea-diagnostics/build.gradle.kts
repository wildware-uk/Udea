plugins {
    // Multiplatform (issue #201). Every JVM consumer - the K2 plugin, the KSP processor, the
    // asset compiler - still resolves the `jvm` variant without asking for it.
    id("udea.kotlin-multiplatform")

    /*
     * `LatencyBudget` (issue #175), and why the shared home is this module.
     *
     * Six wall-clock budgets across `udea-core`, `udea-assets-compiler` and `udea-agent-host` end
     * their failure messages with the same sentence: that a latency measurement taken beside a
     * parallel build measures the build, what this machine's load was, and how to re-run the task
     * alone. Written out six times that is copy-pasted logic differing only in a task name, which
     * the engineering standards reject; written once it needs a module all three can see from
     * their test source sets.
     *
     * This module is the only one that costs nothing to reach. It is the zero-dependency leaf, so
     * a test-fixtures edge to it adds the fixture and the Kotlin stdlib and nothing else -
     * `udea-core`'s fixtures would have dragged Fleks onto the asset compiler's test classpath,
     * and `udea-annotations` is a compile-time vocabulary the codegen reads rather than a place
     * for a runtime helper. It is `testFixtures` and not `main` because none of this ships: a
     * contention note has no business in the jar a game loads.
     *
     * `udea.jvm-test-fixtures` rather than Gradle's `java-test-fixtures`, which cannot be applied
     * beside the multiplatform plugin. It publishes the same capability, so the consumers'
     * `testFixtures(project(":udea-diagnostics"))` is unchanged, and it is JVM-only: the helper
     * reads the JVM's management beans for the machine's load.
     */
    id("udea.jvm-test-fixtures")
}

// The one-line description Maven Central requires of a published artifact (issue #265).
description =
    "Udea's diagnostic model: one diagnostic type with a severity, a stable rule id, a " +
    "repository-relative source span and an optional fix, shared by the compiler checkers and " +
    "the asset validator so both name a problem the same way."
