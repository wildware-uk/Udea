plugins {
    id("udea.kotlin-library")
    // The Replicator contract ships an executable specification: TransformReplicator and
    // ArrayFieldStore. udea-codegen's golden tests consume them, so they have to be a
    // published variant rather than this module's private test source (issue #28 scope).
    `java-test-fixtures`
}

dependencies {
    api(project(":udea-annotations"))

    // `api`, not `implementation`: SimSystem extends Fleks' IntervalSystem and NetIdIndex
    // resolves to a Fleks Entity, so both are part of udea-core's public surface. Fleks is
    // headless — this does not put GL on anyone's classpath (spec 4).
    api(libs.fleks)

    // ReplicatorApiShapeTest asserts the frozen signature exposes FieldMask and never a raw
    // Long. JVM erasure hides a value class, so the check has to run on Kotlin's reflection.
    testImplementation(kotlin("reflect"))
}

// --- Phase 0 budget gates (spec 6 exit criteria, spec 7 risk row) -----------------------------
//
// These are hard CI gates, not aspirations: one structure carries time travel, replication
// baselines and rollback, so a capture that allocates degrades three features at once. Both
// tasks are wired into `check`, and both are excluded from `test` so a normal test run does not
// pay for them twice.
//
// The documented remedy when one fails on slower hardware is `SnapshotRing.degrade()` — raise
// `sparseInterval`, keeping the full sixty-second rewind window at lower keyframe density.
// Never loosen a number in `SnapshotBudgets` and never disable a task here.

val budgetTestClasses = listOf(
    "dev.wildware.udea.core.snapshot.SnapshotBudgetTest",
    "dev.wildware.udea.core.snapshot.TickLoopBudgetTest",
)

tasks.named<Test>("test") {
    budgetTestClasses.forEach { filter.excludeTestsMatching(it) }
}

/** Capture under 1ms at 1000 entities, allocation-free, ring under 64MB. */
val udeaSnapshotBudget = tasks.register<Test>("udeaSnapshotBudget") {
    group = "verification"
    description = "Gates snapshot capture at 1000 entities: <1ms median, zero allocation, <64MB ring."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("dev.wildware.udea.core.snapshot.SnapshotBudgetTest")
    // The measured numbers are the point of the task, so they go to the build log rather than
    // into a report nobody opens.
    testLogging.showStandardStreams = true
}

/** The Phase 0 demo: 200 entities, 600 ticks, <50ms, zero allocation, identical hash stream. */
val udeaBenchTickLoop = tasks.register<Test>("udeaBenchTickLoop") {
    group = "verification"
    description =
        "Gates the assembled tick loop at 200 entities and 600 ticks: <50ms median, zero " +
        "steady-state allocation, identical hash stream across a snapshot restore."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("dev.wildware.udea.core.snapshot.TickLoopBudgetTest")
    testLogging.showStandardStreams = true
    // Published by the Phase 0 CI job as the gate's artifact.
    outputs.file(layout.buildDirectory.file("reports/udea/tick-loop.json"))
}

tasks.named("check") {
    dependsOn(udeaSnapshotBudget, udeaBenchTickLoop)
}
