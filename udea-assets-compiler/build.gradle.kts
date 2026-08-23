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
    // deliberately broken reference in `moba/src/main/assets/character/orc.udea.kts` produced a
    // green `MigratedCorpusCompilesTest` twice, `cleanTest` included, until this was declared.
    // A corpus check whose corpus is not an input is a check that silently stops running.
    inputs.dir(rootProject.layout.projectDirectory.dir("moba/src/main/assets"))
        .withPropertyName("migratedAssetCorpus")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.layout.projectDirectory.dir("example/src/main/resources/assets"))
        .withPropertyName("exampleAssetCorpus")
        .withPathSensitivity(PathSensitivity.RELATIVE)

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
// wired exactly like `udeaDigestBudget` in `udea-agent`: its own task on `check`, excluded from
// `test` so a normal run does not pay for it twice, and `showStandardStreams` on so the measured
// numbers reach the build log of whatever machine is actually slow.
//
// If it fails, the remedy is the daemon's incremental scope - validate fewer files - never a
// wider budget. The number lives in `DaemonLatencyBudgetTest`, where moving it is a diff.
val daemonBudgetTestClass = "dev.wildware.udea.assets.compiler.daemon.DaemonLatencyBudgetTest"

tasks.named<Test>("test") {
    filter.excludeTestsMatching(daemonBudgetTestClass)
}

val udeaDaemonBudget = tasks.register<Test>("udeaDaemonBudget") {
    group = "verification"
    description = "Gates the warm daemon: validate and reload of one edited script under 300ms."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching(daemonBudgetTestClass)
    testLogging.showStandardStreams = true
}

tasks.named("check") {
    dependsOn(udeaDaemonBudget)
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
//       -Pudea.migrate.to=moba/src/main/assets

val migrateFrom: String = providers.gradleProperty("udea.migrate.from")
    .getOrElse("example/src/main/resources/assets")
val migrateTo: String = providers.gradleProperty("udea.migrate.to")
    .getOrElse("moba/src/main/assets")
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
// It also packs the real 327-sheet art corpus, which is tens of seconds of image decoding, so
// `test` excludes it and pays for it once here instead of on every unrelated run.
val packGateClasses = listOf(
    "dev.wildware.udea.assets.compiler.pack.ReproducibilityTest",
    "dev.wildware.udea.assets.compiler.pack.GraphBudgetTest",
    "dev.wildware.udea.assets.compiler.atlas.AtlasPackerTest",
)

tasks.named<Test>("test") {
    packGateClasses.forEach { filter.excludeTestsMatching(it) }
}

val udeaPackGate = tasks.register<Test>("udeaPackGate") {
    group = "verification"
    description =
        "Phase 2 exit: byte-identical .udeapak from two checkouts, the real atlas, and the " +
            "15ms graph deserialisation budget."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    packGateClasses.forEach { filter.includeTestsMatching(it) }
    // The measured numbers must reach the log of whatever machine is slow, exactly as
    // `udeaDaemonBudget` does.
    testLogging.showStandardStreams = true
}

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
