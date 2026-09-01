import dev.wildware.udea.build.CharacterArtStaging

plugins {
    id("udea.kotlin-build-tool")
}

dependencies {
    api(project(":udea-diagnostics"))

    // The runtime asset model (#84), which landed after #85/#86/#87 did. It is here for exactly
    // one reason: the compile-time catalog this module has to produce keys every entry by the
    // **fully qualified name of the `AssetData` implementation** a declaration yields, and the
    // only way to write that name without it drifting is to take it off the `KClass` itself.
    // `AssetKind` does that; `AssetKindTest` fails if a name ever has to be spelled by hand.
    // See `docs/contracts/asset-index.md` for the three-party agreement this closes.
    api(project(":udea-assets"))

    // Pass 1 needs only the PSI half of this jar; pass 2 needs the compiler proper. It is one
    // artifact, so the honest declaration is `implementation` on the whole thing and a test
    // (`IsolatedScanTest`) proving pass 1 does not touch the resolution half.
    // KotlinPoet, for the `GameAssets` accessor emission (issue #90). Build-time only: it
    // resolves a newer kotlin-stdlib than the catalog pins, which `udeaVerifyKotlinPin` forces
    // back down - the same arrangement `udea-codegen` already has.
    implementation(libs.kotlinpoet)

    implementation(libs.kotlin.compiler.embeddable)
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvm.host)
    // Not used by name anywhere in this module: it is the ScriptingCompilerConfigurationExtension
    // that teaches kotlin-compiler-embeddable how to compile a script at all. Without it on the
    // runtime classpath, every .udea.kts compile fails with "cannot find script definition".
    runtimeOnly(libs.kotlin.scripting.compiler.embeddable)

    // `LatencyBudget`, the contention note `DaemonLatencyBudgetTest` and `GraphBudgetTest` end
    // their failure messages with (issue #175). `udea-diagnostics` is already an `api` dependency
    // of this module; this line adds its test fixtures, and nothing else, to the test classpath.
    testImplementation(testFixtures(project(":udea-diagnostics")))
}

// The kotlin-reflect pin that used to live here is now in `UdeaStdlibPin.PINNED_MODULES`, where
// this file's own comment said it belonged. KotlinPoet drags `kotlin-reflect:2.3.20` in and 2.3's
// reflect references a stdlib class 2.2.10 has not got, so the mismatch is a `NoClassDefFoundError`
// at class load rather than a compile error. Pinning it per module fixed the module that had
// already been bitten and nothing else: `:moba:run` hit the identical error from `agentRuntimeClasspath`
// the moment the asset pipeline put this jar on the agent source set. One rule, in the convention.

/**
 * The example asset tree, the compiler classpath and the repo root, handed to tests as
 * properties.
 *
 * Tests here drive real compilation of real files, and none of the three can be discovered
 * from inside a test JVM: `user.dir` is the module directory under Gradle but the daemon's
 * working directory under an IDE, and the "compile these scripts against this classpath"
 * API deliberately takes a classpath rather than reading its own.
 */
val repoRoot: String = rootProject.layout.projectDirectory.asFile.absolutePath
val exampleAssets: String =
    rootProject.layout.projectDirectory.dir("example/src/main/resources/assets").asFile.absolutePath

tasks.withType<Test>().configureEach {
    val runtime = sourceSets.test.map { it.runtimeClasspath }
    systemProperty("udea.repoRoot", repoRoot)
    // The Kotlin version the *catalog* declares, so ModuleContractTest can compare it against
    // the version kotlin-compiler-embeddable actually resolved. Comparing a constant in this
    // module to itself would prove nothing.
    systemProperty("udea.pinnedKotlinVersion", libs.versions.kotlin.get())
    systemProperty("udea.exampleAssets", exampleAssets)

    // The two asset trees these tests read are **inputs**, and saying so is not a tidiness
    // measure. Without it the test task's up-to-date check sees only Kotlin sources and the
    // classpath, so editing a `.udea.kts` and re-running leaves the task UP-TO-DATE and Gradle
    // re-publishes the previous, passing report. That was observed here, not theorised: a
    // deliberately broken reference in `moba/assets/character/orc.udea.kts` produced a
    // green `MigratedCorpusCompilesTest` twice, `cleanTest` included, until this was declared.
    // A corpus check whose corpus is not an input is a check that silently stops running.
    inputs.dir(rootProject.layout.projectDirectory.dir("moba/assets"))
        .withPropertyName("migratedAssetCorpus")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.layout.projectDirectory.dir("example/src/main/resources/assets"))
        .withPropertyName("exampleAssetCorpus")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // Part of that first tree is **produced**, so these tasks have to be ordered after the task
    // that produces it. `moba/assets/sprites` is gitignored licensed art and
    // `:moba:udeaStageCharacterArt` copies it in from the tree that holds it (issue #170); Gradle
    // rejects the whole graph rather than the tests, with "uses this output of task
    // ':moba:udeaStageCharacterArt' without declaring an explicit or implicit dependency".
    //
    // Naming a task of `:moba` from an engine module is worth a sentence, because it looks like
    // an arrow pointing the wrong way and is not one. This is not a classpath edge -
    // `UDEA-MG-*` and `UDEA-LEGACY-001` read resolved configurations, and this module resolves
    // nothing of `:moba` - it is build ordering for a corpus these tests already read by path,
    // deliberately, and have since `MigratedCorpusCompilesTest` was written. The alternative is
    // an undeclared read of a file another task writes, which is the flake Gradle is describing.
    dependsOn(":moba:${CharacterArtStaging.TASK}")

    // The script classpath used by AssetCompilerTest, and the classpath the forked worker is
    // launched with. It is this module's own test runtime classpath, which is what makes the
    // fixture scripts able to see AssetScope.
    doFirst {
        systemProperty("udea.assetsCompiler.classpath", runtime.get().asPath)
    }
}

// --- Phase 2 budget gate (spec 6, Phase 2 exit) ----------------------------------------------
//
// "warm validate < 300ms" is an exit criterion, so it is a CI gate and not an advisory print,
// wired exactly like `udeaDigestBudget` in `udea-agent`: its own task, excluded from `test` so a
// normal run does not pay for it twice, and `showStandardStreams` on so the measured numbers
// reach the build log of whatever machine is actually slow.
//
// It hangs off the root's `udeaLatencyBudgets` and no longer off `check` (issue #175). Both of
// its numbers are wall-clock milliseconds, and the difference between measuring them alone and
// measuring them inside a parallel build is most of the number: on this box the warm reload
// medians 195ms alone and 646ms inside a full `build`, against a 500ms budget. That is not a
// slower daemon, it is a busier machine, and it is why this job now measures on a runner of its
// own rather than beside nineteen Kotlin compilations.
//
// If it fails, the remedy is the daemon's incremental scope - validate fewer files - never a
// wider budget. The number lives in `DaemonLatencyBudgetTest`, where moving it is a diff.
val daemonBudgetTestClass = "dev.wildware.udea.assets.compiler.daemon.DaemonLatencyBudgetTest"

/**
 * The two budgets issue #182 moved off `check`, and one that #175 had already moved.
 *
 * `MobaWarmEditBudgetTest` gates spec 6's Phase 2 edit-to-observe deadline over `moba`'s real
 * corpus, and `WarmScanBudgetTest` gates issue #85's warm pass-1 scan at 200ms. Both were inside
 * `:udea-assets-compiler:test` until #182, which put them on the same footing as the daemon
 * budget above: a wall-clock number measured beside nineteen Kotlin compilations is a measurement
 * of the compilations.
 *
 * The correctness halves stayed behind. `MobaWarmEditTest` asserts the delta a warm edit produces
 * and `ExampleScanTest` asserts everything else pass 1 does, both on `check` where they belong,
 * because neither answer changes with the machine.
 */
val budgetTestClasses = listOf(
    daemonBudgetTestClass,
    "dev.wildware.udea.assets.compiler.daemon.MobaWarmEditBudgetTest",
    "dev.wildware.udea.assets.compiler.scan.WarmScanBudgetTest",
)

tasks.named<Test>("test") {
    budgetTestClasses.forEach { filter.excludeTestsMatching(it) }
}

tasks.register<Test>("udeaDaemonBudget") {
    group = "verification"
    description = "Gates the warm daemon: validate and reload of one edited script under 300ms."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching(daemonBudgetTestClass)
    testLogging.showStandardStreams = true
}

tasks.register<Test>("udeaWarmEditBudget") {
    group = "verification"
    description = "Gates spec 6 Phase 2: an edit of moba's real corpus is observed under 3s."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("dev.wildware.udea.assets.compiler.daemon.MobaWarmEditBudgetTest")
    testLogging.showStandardStreams = true
}

tasks.register<Test>("udeaScanBudget") {
    group = "verification"
    description = "Gates issue #85's warm pass-1 scan of the example tree: under 200ms."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("dev.wildware.udea.assets.compiler.scan.WarmScanBudgetTest")
    testLogging.showStandardStreams = true
}

// --- udeaMigrateAssets (issue #93) --------------------------------------------------------
//
// Ports a `.udea.kts` tree onto the new model with the mechanical rewrites in `AssetMigrator`.
// Forked rather than run in the Gradle daemon: it holds a `KtParser`, and a `KotlinCoreEnvironment`
// that outlives one invocation inside a long-lived daemon is exactly the arrangement `KtParser`'s
// KDoc describes as making a later script compile fail for no visible reason.
//
//   ./gradlew :udea-assets-compiler:udeaMigrateAssets \
//       -Pudea.migrate.from=example/src/main/resources/assets \
//       -Pudea.migrate.to=moba/assets

val migrateFrom: String = providers.gradleProperty("udea.migrate.from")
    .getOrElse("example/src/main/resources/assets")
val migrateTo: String = providers.gradleProperty("udea.migrate.to")
    .getOrElse("moba/assets")
val migrateDryRun: Boolean = providers.gradleProperty("udea.migrate.dryRun").isPresent

tasks.register<JavaExec>("udeaMigrateAssets") {
    group = "udea"
    description = "Rewrites a .udea.kts asset tree onto the new model (issue #93)."
    mainClass.set("dev.wildware.udea.assets.compiler.migrate.AssetMigratorCli")
    classpath = sourceSets.main.get().runtimeClasspath
    val root = rootProject.layout.projectDirectory
    args(
        root.dir(migrateFrom).asFile.absolutePath,
        root.dir(migrateTo).asFile.absolutePath,
    )
    if (migrateDryRun) args("--dry-run")
}

// --- udeaPackGate (issue #89, Phase 2 exit) ---------------------------------------------------
//
// "Two clean builds produce a byte-identical .udeapak" is a Phase 2 exit criterion, so the tests
// that prove it get their own task on `check` rather than living anonymously inside `test` -
// the same arrangement `udeaDaemonBudget` above has, and for the same reason: a criterion nobody
// can run by name is a criterion nobody runs.
//
// It also packs a 327-sheet, 2269-frame art corpus, which is tens of seconds of image work, so
// `test` excludes it and pays for it once here instead of on every unrelated run.
//
// The `RealArt*` pair is the same two test bodies pointed at the paid Tiny RPG corpus instead of
// at the synthesised one (issue #168). They are listed here rather than left to `test` for one
// reason: they are the expensive ones on a machine that *has* the art, and splitting a gate's two
// halves across two tasks is how one half stops being run.
val packGateClasses = listOf(
    "dev.wildware.udea.assets.compiler.pack.ReproducibilityTest",
    "dev.wildware.udea.assets.compiler.pack.RealArtReproducibilityTest",
    "dev.wildware.udea.assets.compiler.atlas.AtlasPackerTest",
    "dev.wildware.udea.assets.compiler.atlas.RealArtAtlasPackerTest",
    // The control. It belongs in the same task as the tests it controls, so one run says both
    // "the property holds" and "the corpus is still one that could show it failing".
    "dev.wildware.udea.assets.compiler.atlas.SmallFixtureContrastTest",
)

/**
 * The 15ms graph-deserialisation budget, split out of `udeaPackGate` by issue #175.
 *
 * The two halves of that task answered different questions and only one of them is a stopwatch.
 * "Two clean builds produce a byte-identical `.udeapak`" is a determinism claim: it gives the
 * same answer on a busy machine as on an idle one, so it belongs on `check` where every build
 * runs it. `GraphBudgetTest` asserts a *median of nine timings* against 15ms, which on this box
 * is 4.8ms alone and 18.1ms inside a parallel build - the same decoder, either side of the line,
 * because the measurement is of the machine. It belongs with the other latency gates, on the
 * root's `udeaLatencyBudgets`, measured by a CI job that has the runner to itself.
 *
 * Splitting it is what lets `udeaPackGate` stay on `check`, which matters: the `build` job's
 * "Assert the atlas determinism tests ran and none skipped" step reads that task's own JUnit
 * reports, and a task that no longer runs there writes no reports for it to read.
 */
val graphBudgetTestClass = "dev.wildware.udea.assets.compiler.pack.GraphBudgetTest"

tasks.named<Test>("test") {
    packGateClasses.forEach { filter.excludeTestsMatching(it) }
    filter.excludeTestsMatching(graphBudgetTestClass)
}

val udeaPackGate = tasks.register<Test>("udeaPackGate") {
    group = "verification"
    description =
        "Phase 2 exit: byte-identical .udeapak from two checkouts, and the real atlas."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    packGateClasses.forEach { filter.includeTestsMatching(it) }
    // The measured numbers must reach the log of whatever machine is slow, exactly as
    // `udeaDaemonBudget` does.
    testLogging.showStandardStreams = true
}

tasks.register<Test>("udeaGraphBudget") {
    group = "verification"
    description = "Gates .udeapak graph deserialisation at 2000 assets: 15ms median."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching(graphBudgetTestClass)
    testLogging.showStandardStreams = true
}

// `udeaPackGate` only. `udeaGraphBudget` is reached through the root's `udeaLatencyBudgets`,
// which the `latency-budgets` CI job runs serially on both runner images (issue #175).
tasks.named("check") {
    dependsOn(udeaPackGate)
}

// --- udeaPackBundle (issue #89, Phase 2 exit) -------------------------------------------------
//
// Writes one `.udeapak` to a file, so that "two clean builds produce a byte-identical bundle" can
// be run as two builds rather than as two calls inside one JVM. `ReproducibilityTest` is the
// other half and neither subsumes the other - see `AssetPackCli`'s KDoc for what each one catches
// that the other cannot.
//
//   ./gradlew :udea-assets-compiler:udeaPackBundle -Pudea.pack.out=build/out/game.udeapak
//
// Forked for `udeaMigrateAssets`'s reason: it holds a Kotlin script compiler, and one that
// outlives an invocation inside a long-lived Gradle daemon makes a later compile fail for no
// visible reason.
val packAssetRoot: String = providers.gradleProperty("udea.pack.assets")
    .getOrElse("udea-assets-compiler/src/test/resources/packassets")
val packSpriteRoot: String = providers.gradleProperty("udea.pack.sprites")
    .getOrElse("moba/src/main/resources/assets/sprites")
val packOut: String = providers.gradleProperty("udea.pack.out")
    .getOrElse("udea-assets-compiler/build/pack/game.udeapak")

tasks.register<JavaExec>("udeaPackBundle") {
    group = "udea"
    description = "Compiles an asset tree and writes one .udeapak (issue #89)."
    mainClass.set("dev.wildware.udea.assets.compiler.pack.AssetPackCli")
    val runtime = sourceSets.main.get().runtimeClasspath
    classpath = runtime
    val root = rootProject.layout.projectDirectory
    val spriteDir = root.dir(packSpriteRoot).asFile
    args(
        root.asFile.absolutePath,
        root.dir(packAssetRoot).asFile.absolutePath,
        if (spriteDir.isDirectory) spriteDir.absolutePath else "-",
        root.file(packOut).asFile.absolutePath,
    )
    // The classpath the `.udea.kts` are compiled against is this task's own runtime classpath -
    // the same arrangement the daemon's tests use, and for the same reason: a script compiled
    // against anything else fails with "Unresolved reference 'spriteSheet'".
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Dudea.assetsCompiler.classpath=${runtime.asPath}")
        },
    )
}
