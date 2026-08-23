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

/**
 * The `CoreModule` system-order golden pins which systems run and in what order, so it has to
 * be regenerable on purpose and never by accident. `./gradlew :udea-core:test
 * -Dupdate.goldens=true` rewrites it; without the flag an order change is a failing diff.
 *
 * Gradle has no `--update-goldens` option for a plain `Test` task, so the flag is a system
 * property, matching `:udea-net`. `udea.projectDir` gives the test the source path to rewrite,
 * which the classpath alone cannot provide.
 */
val updateGoldens: Provider<String> = providers.systemProperty("update.goldens").orElse("false")

tasks.withType<Test>().configureEach {
    systemProperty("udea.projectDir", projectDir.absolutePath)
    systemProperty("update.goldens", updateGoldens.get())
}

/**
 * The sibling module sources `ReplicatorApiShapeTest` reads, declared as inputs.
 *
 * Found the hard way: adding `private val cached: FieldMask` to `udea-gas`'s
 * `AttributesReplicator` left `:udea-core:test` UP-TO-DATE and the build green. No sibling
 * module is on this module's test classpath, so nothing else makes the task rerun - the rule
 * only fired when some unrelated change happened to invalidate it. A source rule that reads a
 * tree it has not declared is a rule that reports whatever it last saw.
 *
 * A glob rather than a `listFiles()` scan so a module added later is covered without anyone
 * remembering this line. `.kts` is deliberately not matched: the asset corpus under
 * `moba/src/main/assets` is not Kotlin the rule reads.
 */
val fieldMaskScanSources: ConfigurableFileTree = fileTree(rootProject.layout.projectDirectory) {
    include("udea-*/src/main/**/*.kt")
    include("udea-*/src/testFixtures/**/*.kt")
    include("moba/src/main/**/*.kt")
    include("moba/src/testFixtures/**/*.kt")
}

tasks.named<Test>("test") {
    inputs.files(fieldMaskScanSources)
        .withPropertyName("fieldMaskScanSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

val budgetTestClasses = listOf(
    "dev.wildware.udea.core.snapshot.SnapshotBudgetTest",
    "dev.wildware.udea.core.snapshot.TickLoopBudgetTest",
    "dev.wildware.udea.core.movement.CharacterMoverBudgetTest",
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

/**
 * The Phase 3 movement gate: 200 movers replayed 60 times inside a quarter of a 60Hz frame.
 *
 * Its own task for the same reason the two above are: it is a timing measurement, it belongs in
 * the build log of whichever machine is slow, and a normal `test` run should not pay for it
 * twice. `CharacterMoverBudgetTest`'s KDoc has the remedy when it fails, and the remedy is never
 * a larger constant.
 */
val udeaBenchCharacterMover = tasks.register<Test>("udeaBenchCharacterMover") {
    group = "verification"
    description =
        "Gates CharacterMover at 200 movers x 60 replay steps: under a quarter of a 60Hz frame."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("dev.wildware.udea.core.movement.CharacterMoverBudgetTest")
    testLogging.showStandardStreams = true
}

tasks.named("check") {
    dependsOn(udeaSnapshotBudget, udeaBenchTickLoop, udeaBenchCharacterMover)
}
