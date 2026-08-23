plugins {
    id("udea.kotlin-library")
}

/**
 * No build script logic here on purpose.
 *
 * This module's zero-dependency budget — the Kotlin stdlib and the `org.jetbrains:annotations`
 * artifact it drags in, and nothing else (spec 4) — is `UDEA-MG-001` in
 * `ModuleGraphRules.ANNOTATIONS_ARE_A_LEAF`, enforced on `runtimeClasspath` by
 * `udeaVerifyModuleGraph` from `check` like every other arrow in the graph.
 *
 * It used to be enforced *twice*: by that rule, and by a `udeaVerifyAnnotationsLeaf` task
 * registered here with its own allow list, its own message and no rule id. Two enforcements
 * of one invariant is two things to keep in step and one message that cannot be searched for
 * in `docs/module-graph.md`. The only part `UDEA-MG-001` did not have was the branch that
 * matters most — an *empty* `runtimeClasspath` is a broken check rather than a clean module,
 * and must fail rather than pass vacuously — so that moved into `DependencyRules.vacuity`,
 * where it now covers every `allowOnly` rule in the build instead of this one module.
 */
