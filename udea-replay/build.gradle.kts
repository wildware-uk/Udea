plugins {
    id("udea.kotlin-library")
    // The replay toolset goes through the same `@AgentTool` KSP pass every other toolset does.
    // `EngineToolModules` deliberately does not name these tools - see `ReplayToolModules` for
    // why a replay session is a thing only a host that has one can register.
    id("com.google.devtools.ksp") version libs.versions.ksp.get()

    // The `replay-equality` fixture world (issue #152). A published variant rather than this
    // module's private test source for the same reason `udea-core` publishes `TransformReplicator`:
    // the CI job runs it as a `JavaExec` main class, and a `src/test` main class is an entry point
    // living inside the thing that is supposed to be testing it.
    `java-test-fixtures`
}

dependencies {
    // `Tick`, `WorldSnapshot`, `WorldHasher`, `DivergenceReport`, `RngService`. The whole point
    // of Phase 7 is that these already exist and a recording only has to name them.
    api(project(":udea-core"))

    // `AgentResult`, `AgentToolDef`, `ToolModule`: the bisect tools of issue #149.
    //
    // **compileOnly, and that is a release gate rather than a preference.**
    // `ReleaseRules.CLASSPATH_RULE` (UDEA-REL-002) forbids `:udea-agent` on `:moba`'s
    // `runtimeClasspath`, because the agent surface mutates the live simulation and debug-only
    // means absent from the shipped classpath rather than disabled in it. `moba` needs the
    // *recording* half of this module in `src/main` - a shipped game records matches - so an
    // `api` or `implementation` edge here would drag the agent surface into every release and
    // fail that gate.
    //
    // The consequence, stated plainly: `ReplayToolset` and `ReplayToolModules` are simply
    // unloadable in a process with no `udea-agent` on its classpath. That is the same bargain
    // `udea-agent` itself strikes with `udea-assets-compiler` for `AssetsToolset`, and it is
    // correct rather than a compromise - only a debug host serves `replay.*`, and `moba`'s
    // `agent` source set is the one classpath in that project which resolves the agent surface.
    compileOnly(project(":udea-agent"))

    // `@AgentTool` and `@Arg`, on `ReplayToolset`.
    implementation(project(":udea-annotations"))

    ksp(project(":udea-codegen"))

    // `QueueingSceneManager`, `RecordingCueSink`, `RecordingPhysicsWorld`: the equality fixture is
    // a real Fleks world running a real `WorldSimulation`, and those are the two services a
    // headless world has no device for.
    testFixturesImplementation(testFixtures(project(":udea-core")))

    // Real Fleks components on real entities and a wired `GameContext`, so a replay test drives
    // a real world through a real snapshot service rather than a mock of one.
    testImplementation(testFixtures(project(":udea-core")))

    // The agent surface is `compileOnly` above, so this module's own tests have to put it back
    // on the classpath they run against - otherwise `ReplayToolset` could not be exercised here
    // at all and the bisect tools would be proven by nothing.
    testImplementation(project(":udea-agent"))
}

// The manifest fragment is named per module, or two modules emit `udea/-agent-tools.json` and
// the second overwrites the first. No `udea.toolModuleService`: `ReplayToolset` needs a
// `ReplaySession` that only a host can build, and a `ServiceLoader` entry would make every
// process with this module on its classpath fail `ToolIndex.Builder.build`.
ksp {
    arg("udea.moduleName", "UdeaReplay")
}

/**
 * `udea.projectDir` and `update.goldens`, the same two `udea-core` and `udea-net` pass.
 *
 * `CrossPlatformDivergenceTest` pins the rendered cross-platform failure against a checked-in
 * expected-output fixture, and `ReplayEqualityProofTest` reads this build script and `ci.yml`.
 * Neither path is on the classpath, so both need the source directory.
 */
tasks.withType<Test>().configureEach {
    systemProperty("udea.projectDir", projectDir.absolutePath)
    systemProperty(
        "update.goldens",
        providers.systemProperty("update.goldens").orElse("false").get(),
    )
    // The build script and the workflow are read by `ReplayEqualityProofTest`, so an edit to
    // either has to make the task rerun. Found the same way `udea-core`'s FieldMask scan was: a
    // source rule that reads a tree it has not declared reports whatever it last saw.
    inputs.file(layout.projectDirectory.file("build.gradle.kts"))
        .withPropertyName("replayEqualityBuildScript")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.layout.projectDirectory.file(".github/workflows/ci.yml"))
        .withPropertyName("ciWorkflow")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// --- the cross-OS replay-equality gate (issue #152) -------------------------------------------
//
// ## Why the logic is in tasks and classes rather than in the workflow YAML
//
// Nobody can run GitHub Actions locally, so anything expressed only in `ci.yml` is unverifiable
// until it has already gone wrong once on a branch somebody merged. Everything the job does -
// replay, write the digest, compare two of them, render the divergence, choose the exit code - is
// therefore a class with a `main` and a task that drives it, and `ci.yml` is a few `./gradlew`
// lines over the top. `udeaReplayEqualityProof` runs the whole shape on one machine, including
// the half that has to fail.
//
// None of these is wired into `check`. `udeaReplayDigest` is a multi-second replay whose output is
// only meaningful beside another machine's, and `udeaReplayEqualityProof` starts five JVMs - the
// same reason `runUdpProof` and `runLaneShot` are named tasks rather than `check` dependencies.
// What `check` does carry is `:udea-replay:test`, which holds `CrossPlatformDivergenceTest`,
// `DivergenceReportFormatTest`, `ReplayDigestTest` and `ReplayEqualityProofTest`.

val replayEqualityDir: Provider<Directory> =
    layout.buildDirectory.dir("reports/udea/replay-equality")

/** The classpath every entry point below runs on: this module, its fixtures and `udea-core`. */
val equalityClasspath: FileCollection = sourceSets["testFixtures"].runtimeClasspath

/**
 * The launcher a digest runs on, honouring `-Pudea.replay.jvm=<feature version>`.
 *
 * The second axis of the matrix is a second JVM, and it is not decoration:
 * `determinism-audit.md` §3.1 measured `Math.sin` disagreeing with `StrictMath.sin` on 3.4% of
 * sampled inputs on a single JVM, and two implementations are under no obligation to disagree in
 * the same places. Making the launcher selectable is what lets that axis be exercised on one
 * developer machine as well as across a CI matrix.
 *
 * `-Pudea.replay.jvmVendor` is not optional decoration on a CI matrix. `actions/setup-java` puts
 * its JDK on `PATH` and in `JAVA_HOME`, but Gradle's toolchain auto-detection also finds whatever
 * the runner image ships - so two legs asking only for "17" can both resolve to the *same* vendor
 * and the second axis quietly stops existing while both legs go green. Naming the vendor makes a
 * missing one a loud failure instead. The digest header records the vendor it actually ran on, and
 * the join step prints it, so the claim is checkable after the fact rather than assumed.
 */
val digestLauncher: Provider<JavaLauncher> = providers.provider {
    val version = providers.gradleProperty("udea.replay.jvm").orNull
    val vendor = providers.gradleProperty("udea.replay.jvmVendor").orNull
    if (version == null && vendor == null) {
        javaToolchains.launcherFor(java.toolchain).get()
    } else {
        javaToolchains.launcherFor {
            languageVersion.set(
                version?.let { JavaLanguageVersion.of(it) } ?: java.toolchain.languageVersion.get(),
            )
            if (vendor != null) this.vendor.set(JvmVendorSpec.matching(vendor))
        }.get()
    }
}

/**
 * Replays the checked-in fixture and writes this machine's digest stream.
 *
 * One of these runs per matrix leg in CI, each with its own `--label`. `-Pudea.replay.label` and
 * `-Pudea.replay.out` are how the workflow names them; the defaults describe a local run, so the
 * task is runnable by hand with no properties at all.
 */
tasks.register<JavaExec>("udeaReplayDigest") {
    group = "verification"
    description = "Replays the checked-in .udearep fixture and writes this machine's .udeaeq digest."
    classpath = equalityClasspath
    mainClass.set("dev.wildware.udea.replay.equality.fixture.DriftDigestMain")
    javaLauncher.set(digestLauncher)
    val label = providers.gradleProperty("udea.replay.label").orElse("local")
    val out = providers.gradleProperty("udea.replay.out")
        .orElse(replayEqualityDir.map { "${it.asFile}/local.udeaeq" })
    val plantAt = providers.gradleProperty("udea.replay.plantUlpAt")
    val reports = replayEqualityDir
    argumentProviders.add {
        buildList {
            add("--label")
            add(label.get())
            add("--out")
            add(out.get())
            add("--timing")
            add("${reports.get().asFile}/${label.get().replace('/', '-')}.timing.txt")
            if (plantAt.isPresent) {
                add("--plant-ulp-at")
                add(plantAt.get())
            }
        }
    }
}

/**
 * Rewrites the checked-in `.udearep` fixture from `DriftFixtureRecorder`.
 *
 * Nothing depends on this and nothing in CI runs it: regenerating a fixture is how a gate gets
 * silenced, so it is a command somebody types on purpose. It exists because the alternative is a
 * checked-in binary nobody can reproduce, and a reviewer has to be able to rebuild the bytes to
 * check them - `java.util.Random`'s LCG is specified, so the same seed rebuilds the same input
 * stream on any machine.
 *
 * This is **not** issue #165's `--update-replay-fixtures`. That flag regenerates every fixture a
 * game has, across games; this rewrites one file for one fixture world.
 */
tasks.register<JavaExec>("udeaWriteReplayFixture") {
    group = "build"
    description = "Regenerates udea-replay's checked-in .udearep replay-equality fixture."
    classpath = equalityClasspath
    mainClass.set("dev.wildware.udea.replay.equality.fixture.DriftFixtureMain")
    val target = layout.projectDirectory.file("src/testFixtures/resources/fixtures/drift-3600.udearep")
    argumentProviders.add { listOf("--out", target.asFile.absolutePath) }
}

/**
 * The join step: compares every `.udeaeq` under `-Pudea.replay.streams` and fails on a divergence.
 *
 * Game-agnostic - `ReplayEqualsMain` reads nothing but the files - which is what lets one CI job
 * download several legs' artifacts into a directory and rule on them without a JVM that could
 * build the game's world at all.
 */
tasks.register<JavaExec>("udeaReplayEquals") {
    group = "verification"
    description = "Compares two or more .udeaeq digest streams and fails naming the differing field."
    classpath = equalityClasspath
    mainClass.set("dev.wildware.udea.replay.equality.ReplayEqualsMain")
    val streams = providers.gradleProperty("udea.replay.streams")
        .orElse(replayEqualityDir.map { it.asFile.absolutePath })
    val reports = replayEqualityDir
    argumentProviders.add {
        listOf("--summary", "${reports.get().asFile}/summary.md", streams.get())
    }
}

// --- the local proof --------------------------------------------------------------------------
//
// Five processes: three digests and two joins. The digests are separate JVMs on purpose - two runs
// inside one process share a warmed JIT, a loaded class hierarchy and one set of static
// initialisers, which is most of what a cross-process comparison is asking about.

val proofDir: Provider<Directory> = layout.buildDirectory.dir("reports/udea/replay-equality/proof")

/**
 * 1200 is `DriftFixture.PLANT_TICK`.
 *
 * A literal here because a Gradle script cannot read a Kotlin constant out of a source set it is
 * about to compile. `ReplayEqualityProofTest` asserts this file and that constant agree, so the
 * duplication fails a test rather than drifting quietly.
 */
val plantTick = "1200"

fun registerProofDigest(name: String, label: String, file: String, plantAt: String?) =
    tasks.register<JavaExec>(name) {
        group = "verification"
        description = "replay-equality proof: writes $file"
        classpath = equalityClasspath
        mainClass.set("dev.wildware.udea.replay.equality.fixture.DriftDigestMain")
        javaLauncher.set(digestLauncher)
        val root = proofDir
        argumentProviders.add {
            buildList {
                add("--label")
                add(label)
                add("--out")
                add("${root.get().asFile}/$file")
                if (plantAt != null) {
                    add("--plant-ulp-at")
                    add(plantAt)
                }
            }
        }
    }

fun registerProofJoin(name: String, first: String, second: String, summary: String, after: List<Any>) =
    tasks.register<JavaExec>(name) {
        group = "verification"
        description = "replay-equality proof: joins $first and $second"
        dependsOn(after)
        classpath = equalityClasspath
        mainClass.set("dev.wildware.udea.replay.equality.ReplayEqualsMain")
        // The planted half exists to exit non-zero, so the task has to survive that and let
        // `udeaReplayEqualityProof` decide what it meant.
        isIgnoreExitValue = true
        val root = proofDir
        argumentProviders.add {
            val dir = root.get().asFile
            listOf("--summary", "$dir/$summary", "$dir/$first", "$dir/$second")
        }
        // Written here rather than read through `executionResult` from the aggregate task: that
        // provider is only resolvable from inside the task that produced it, and querying it from
        // a sibling fails at execution time with "this provider has no value available".
        val exitFile = root.map { it.file("$summary.exit") }
        doLast {
            exitFile.get().asFile.writeText(executionResult.get().exitValue.toString())
        }
    }

val proofDigestA = registerProofDigest("udeaReplayProofDigestA", "proof/leg-a", "leg-a.udeaeq", null)
val proofDigestB = registerProofDigest("udeaReplayProofDigestB", "proof/leg-b", "leg-b.udeaeq", null)
val proofDigestPlanted =
    registerProofDigest("udeaReplayProofDigestPlanted", "proof/leg-planted", "planted.udeaeq", plantTick)

val proofJoinEqual = registerProofJoin(
    "udeaReplayProofJoinEqual", "leg-a.udeaeq", "leg-b.udeaeq", "equal.txt",
    listOf(proofDigestA, proofDigestB),
)
val proofJoinPlanted = registerProofJoin(
    "udeaReplayProofJoinPlanted", "leg-a.udeaeq", "planted.udeaeq", "planted.txt",
    listOf(proofDigestA, proofDigestPlanted),
)

/**
 * The evidence command: two honest legs agree, and a one-ulp leg is caught and named.
 *
 * Both halves matter and the second is the one usually missing. A gate that has only ever been
 * seen to pass is a gate nobody has watched fail, and `docs/engineering-standards.md` §8 lists
 * "a test that cannot fail" as a rejection.
 *
 * The four strings it insists on are the four things spec 7 asks a cross-OS failure to name: the
 * tick, the entity, the component and field, and the preceding five ticks of that field's history.
 */
tasks.register("udeaReplayEqualityProof") {
    group = "verification"
    description =
        "Proves the replay-equality gate both ways: two honest legs agree, and a one-ulp leg fails " +
            "with the tick, the entity, the component and the field named."
    dependsOn(proofJoinEqual, proofJoinPlanted)

    val equalSummary = proofDir.map { it.file("equal.txt") }
    val plantedSummary = proofDir.map { it.file("planted.txt") }
    val equalExitFile = proofDir.map { it.file("equal.txt.exit") }
    val plantedExitFile = proofDir.map { it.file("planted.txt.exit") }
    val expectedTick = plantTick

    doLast {
        val equalReport = equalSummary.get().asFile.readText()
        val plantedReport = plantedSummary.get().asFile.readText()
        val equalExit = equalExitFile.get().asFile.readText().trim().toInt()
        val plantedExit = plantedExitFile.get().asFile.readText().trim().toInt()

        println("=== two honest legs, two separate JVM processes ===")
        println(equalReport)
        println("=== a third leg carrying a planted one-ulp divergence ===")
        println(plantedReport)

        check(equalExit == 0) {
            "two honest legs of the same fixture disagreed. That is either a real determinism " +
                "defect in this build or a broken gate.\n" + equalReport
        }
        check(plantedExit == 1) {
            "a leg with a deliberately planted one-ulp divergence was NOT caught (exit " +
                plantedExit + "). A gate that cannot fail proves nothing.\n" + plantedReport
        }
        // `Tick.toString()` renders `t1200`, and the report has to name the tick, the entity, the
        // component and field, and five ticks of that field's history - spec 7's four.
        val required = listOf("at t$expectedTick", "Drifter.x", "NetId(", "the preceding 5 tick(s)")
        for (needle in required) {
            check(plantedReport.contains(needle)) {
                "the planted divergence report does not contain '$needle', so it does not name " +
                    "what issue #152 requires it to name.\n" + plantedReport
            }
        }
        println(
            "replay-equality proof PASSED: two honest legs agree (exit 0); the planted leg fails " +
                "(exit 1) naming Drifter.x at t$expectedTick, with five ticks of history.",
        )
    }
}
