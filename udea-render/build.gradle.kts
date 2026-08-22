plugins {
    id("udea.kotlin-library-gl")
}

dependencies {
    api(project(":udea-core"))
    implementation(project(":udea-assets"))

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

/** Modules that must stay free of GL. Mirrored by `HeadlessScan.HEADLESS_MODULES`. */
val headlessModules = listOf(
    "udea-agent",
    "udea-annotations",
    "udea-assets",
    "udea-core",
    "udea-gas",
    "udea-net",
)

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

/** Build scripts the module-graph tests read; without these they would be checked stale. */
val moduleBuildScripts = fileTree(rootDir) {
    include("udea-*/build.gradle.kts", "moba/build.gradle.kts")
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

    dependsOn(headlessModules.map { ":$it:classes" })
    inputs.files(headlessModuleClasses)
        .withPropertyName("headlessModuleClasses")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.test {
    // The gate is `udeaVerifyHeadless`'s job; running it twice per `check` buys nothing.
    filter { excludeTestsMatching(gateTestClass) }

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

tasks.check {
    dependsOn(udeaVerifyHeadless)
}
