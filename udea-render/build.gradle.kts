import dev.wildware.udea.build.ModuleGraphRules

plugins {
    id("udea.kotlin-library-gl")
}

dependencies {
    api(project(":udea-core"))
    implementation(project(":udea-assets"))

    // The gdx desktop natives. Runtime-only because nothing compiles against them: the
    // LWJGL3 backend loads `gdx64.dll`/`libgdx64.so` through `SharedLibraryLoader` at
    // context creation, and without this artifact `Lwjgl3Application` dies in its own
    // constructor with an UnsatisfiedLinkError rather than anywhere a stack trace explains.
    // `natives-desktop` is a classifier, which `gradle/libs.versions.toml` cannot express,
    // so the variant is selected here.
    runtimeOnly(variantOf(libs.gdx.platform) { classifier("natives-desktop") })

    // Test-only, deliberately. `udeaVerifyHeadless` reports through the one UdeaDiagnostic
    // (spec 5) so its output has the same rule ids, spans and cap as every other producer;
    // nothing in the shipped module needs diagnostics, so it must not reach the runtime
    // classpath. RenderModuleGraphTest asserts that this stays a testImplementation.
    testImplementation(project(":udea-diagnostics"))

    // Fleks worlds and a wired GameContext, so the pipeline tests drive the real kernel
    // rather than a mock of it.
    testImplementation(testFixtures(project(":udea-core")))

    // The bytecode gate. ASM is a test dependency because the gate is a check, not a
    // runtime feature: putting a class-file parser on a renderer's classpath would be the
    // sort of thing this module exists to stop.
    testImplementation(libs.asm)
}

// --- udeaVerifyHeadless (issue #117) -----------------------------------------------------
//
// The bytecode half of the "no GL in the kernel" rule. It EXTENDS `UDEA-MG-002`, the
// configuration-level rule owned by `udeaVerifyModuleGraph` in the build tooling, and does
// not restate it: that rule fails when a GL *dependency* resolves onto a headless module's
// compile classpath, and this one fails when a compiled class *names* a GL type, which is
// the case a configuration check structurally cannot see (a transitive type from an allowed
// jar, or a `compileOnly` dependency). A configuration-level failure is the clearer message
// of the two, which is why it is checked first, by that task.
//
// It runs as a Test task rather than a bespoke one so that the scan itself has unit tests
// that can fail (`HeadlessScanTest`), which a `doLast` block would not.

/**
 * Modules that must stay free of GL, read from the one place that decides it.
 *
 * `ModuleGraphRules.HEADLESS_PROJECTS` is also what `UDEA-MG-002` -- the configuration-level
 * half of this rule -- governs, so the two levels cannot drift apart. Before this was
 * derived, the list here and `HeadlessScan.HEADLESS_MODULES` disagreed in both directions:
 * the gate compiled modules it never scanned, scanned modules it never compiled (reading
 * whatever stale `build/classes` happened to be present), and four modules were in neither.
 */
val headlessModules: List<String> =
    ModuleGraphRules.HEADLESS_PROJECTS.map { it.removePrefix(":") }.sorted()

/**
 * How [headlessModules] reaches `HeadlessScan`, which lives in this module's test sources
 * and therefore cannot see `build-logic`. `HeadlessScan` fails loudly when this is absent,
 * so a broken hand-off is a red gate rather than a scan of nothing.
 */
val headlessModulesProperty: String = ModuleGraphRules.HEADLESS_MODULES_PROPERTY

/**
 * The compiled output the scan reads. Declared as an input so the gate is up-to-date-checked.
 *
 * Narrowed to the `main` source set of each language directory, matching
 * `RepoLayout.classFiles`, which only ever walks `build/classes/<lang>/main`. The whole of
 * `build/classes` would also cover `<lang>/test` and `<lang>/testFixtures`, and Gradle then
 * (correctly) refuses the build: this task would be consuming the output of every module's
 * `compileTestKotlin`/`compileTestJava` without depending on it. Those source sets are not
 * part of the rule -- a GL reference in a test is legal, and a headless module's *shipped*
 * bytecode is what "no GL in the kernel" is about.
 */
val headlessModuleClasses = files(
    headlessModules.map { module ->
        fileTree(rootDir.resolve("$module/build/classes")) { include("*/main/**") }
    },
)

/**
 * Build scripts the module-graph tests read; without these they would be checked stale.
 *
 * `settings.gradle.kts` is in here because `UdeaVerifyHeadlessTest` re-derives the designated
 * module set from it: including a new `udea-*` module has to make the gate out of date, or
 * the assertion that the set is complete is checked against a cached pass.
 */
val moduleBuildScripts = fileTree(rootDir) {
    include("udea-*/build.gradle.kts", "moba/build.gradle.kts", "settings.gradle.kts")
}

val gateTestClass = "dev.wildware.udea.render.headless.UdeaVerifyHeadlessTest"

val udeaVerifyHeadless = tasks.register<Test>("udeaVerifyHeadless") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails if any headless module's bytecode references a GL type " +
        "(bytecode extension of UDEA-MG-002)."

    val testSourceSet = sourceSets.test.get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching(gateTestClass) }
    systemProperty(headlessModulesProperty, headlessModules.joinToString(","))

    dependsOn(headlessModules.map { ":$it:classes" })
    inputs.files(headlessModuleClasses)
        .withPropertyName("headlessModuleClasses")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(moduleBuildScripts)
        .withPropertyName("moduleBuildScripts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.test {
    // The gate is `udeaVerifyHeadless`'s job; running it twice per `check` buys nothing.
    filter { excludeTestsMatching(gateTestClass) }

    // The GL tests belong to `udeaGlTest`, in a JVM of their own: they boot a real LWJGL3
    // application, which populates `Gdx.gl`/`Gdx.graphics`/`Gdx.app` for the whole process and
    // would make `PureSimulationTest`'s "no context existed" assertion meaningless here.
    filter { excludeTestsMatching("dev.wildware.udea.render.gl.*") }

    // HeadlessScanTest reads the same designated list, so it needs the same hand-off.
    systemProperty(headlessModulesProperty, headlessModules.joinToString(","))

    // HeadlessScanTest and RenderModuleGraphTest read the compiled output and the build
    // scripts of modules this one does not depend on.
    dependsOn(headlessModules.map { ":$it:classes" })
    inputs.files(headlessModuleClasses)
        .withPropertyName("headlessModuleClasses")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(moduleBuildScripts)
        .withPropertyName("moduleBuildScripts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// --- udeaGlTest (issues #118, #121) ------------------------------------------------------
//
// The tests that need a real LWJGL3 context, in a JVM of their own.
//
// Not a preference. `PureSimulationTest` proves the simulation runs with no context by
// asserting `Gdx.gl`, `Gdx.graphics` and `Gdx.app` are null -- and those are JVM-wide statics
// that `Lwjgl3Application` populates on start and does not fully clear on exit. Run in one
// JVM, a backend test that had already booted a window would make that assertion fail, or
// (worse, depending on order) make it pass while proving nothing. Two JVMs makes the claim
// true again in both directions.
//
// It buys a second thing: a driver that segfaults takes down a JVM that contains only the
// tests that asked for a driver.

val glTestPackage = "dev.wildware.udea.render.gl"

val udeaGlTest = tasks.register<Test>("udeaGlTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the render tests that need a real LWJGL3 context and a display."

    val testSourceSet = sourceSets.test.get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("$glTestPackage.*") }

    // A machine with no display cannot run these, and they say so out loud and skip. Set this
    // on any CI job that *does* have one, so that a backend which quietly stops booting fails
    // the build instead of hiding behind a skip forever.
    systemProperty(
        "udea.render.requireGl",
        providers.gradleProperty("udea.render.requireGl").getOrElse("false"),
    )
}

tasks.check {
    dependsOn(udeaVerifyHeadless, udeaGlTest)
}
