plugins {
    id("udea.kotlin-library")
}

dependencies {
    api(project(":udea-core"))

    // Real Fleks components on real entities, a wired GameContext, and the ArrayFieldStore /
    // ArrayBitWriter pair, so the hand-written test replicators can implement the whole frozen
    // contract rather than only the two methods the agent surface calls.
    testImplementation(testFixtures(project(":udea-core")))
}

// --- Phase 1 budget gate (spec 6, Phase 1 exit) ----------------------------------------------
//
// "digest <0.3ms at 500 entities" is an exit criterion, so it is a CI gate and not an advisory
// print, wired exactly like the Phase 0 budgets in `udea-core`: its own task on `check`, and
// excluded from `test` so a normal run does not pay for it twice. The numbers live in
// `DigestBudgets`, where moving one is a diff a reviewer sees.
//
// If it fails on slower hardware the remedy is `DigestBudgets.REBUILD_INTERVAL_TICKS` - build
// the digest less often - never a wider budget. A budget that moves when it is missed measures
// nothing.

val budgetTestClasses = listOf(
    "dev.wildware.udea.agent.state.DigestBudgetTest",
    "dev.wildware.udea.agent.query.EntityQueryBudgetTest",
)

tasks.named<Test>("test") {
    budgetTestClasses.forEach { filter.excludeTestsMatching(it) }
}

/** The digest under 0.3ms at 500 entities, and allocating nothing but the document. */
val udeaDigestBudget = tasks.register<Test>("udeaDigestBudget") {
    group = "verification"
    description = "Gates the Tier-0 digest at 500 entities: <0.3ms median, zero render allocation."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("dev.wildware.udea.agent.state.DigestBudgetTest")
    // The measured numbers are the point of the task, so they go to the build log rather than
    // into a report nobody opens.
    testLogging.showStandardStreams = true
}

/** A query over 500 entities returning 20, under 1ms and with bounded allocation. */
val udeaQueryBudget = tasks.register<Test>("udeaQueryBudget") {
    group = "verification"
    description = "Gates entity query at 500 entities returning 20: <1ms median, bounded allocation."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("dev.wildware.udea.agent.query.EntityQueryBudgetTest")
    testLogging.showStandardStreams = true
}

tasks.named("check") {
    dependsOn(udeaDigestBudget, udeaQueryBudget)
}
