plugins {
    `kotlin-dsl`
}

group = "dev.wildware.udea.build"

dependencies {
    implementation(libs.kotlin.gradle.plugin)

    // A third Kotlin version in this build, and a deliberate one.
    //
    // `kotlin-dsl` compiles build logic with the Kotlin the *Gradle distribution* embeds -
    // 2.0.21 for Gradle 8.13 - not with the catalog's 2.2.10, which is why every build prints
    // "Unsupported Kotlin plugin version". A 2.0.21 compiler cannot read kotlin-test 2.2.10's
    // metadata, so `libs.kotlin.test` here fails at compile time with a metadata-version
    // error. `embeddedKotlin("test")` resolves the kotlin-test that matches the compiler
    // actually running, which is the only version that can work.
    //
    // The catalog pin (UdeaVersions.KOTLIN, and UdeaStdlibPin for the resolved stdlib) governs
    // the udea-* tree. It cannot govern this module, because Gradle chooses this compiler. The
    // day build-logic needs the catalog's Kotlin, the fix is a Gradle upgrade, not a version
    // override here.
    testImplementation(embeddedKotlin("test"))
    testImplementation(gradleTestKit())
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.platform.launcher)
}

/**
 * The files in the *outer* build that these tests read.
 *
 * Several gates here are source scans of the repository rather than assertions about
 * `build-logic`'s own classes: `ModuleGraphRulesTest` re-derives the headless module set from
 * `settings.gradle.kts`, `CompilerPluginSwitchTest` walks the build scripts and `udea-gradle`
 * looking for compiler-plugin wiring, `UdeaProtocolLockTest` reads `udea-codegen`'s lock and
 * the emitter that writes its header, and `UdeaNetComponentsTest` reads the component
 * registry. Gradle cannot see any of that, so without declaring it the task stays
 * `UP-TO-DATE` across exactly the edits it exists to notice — a gate that passes from cache
 * is a gate that has stopped running.
 */
val outerBuildInputs: FileCollection = files(
    rootDir.resolve("../settings.gradle.kts"),
    rootDir.resolve("../net-components.lock"),
    rootDir.resolve("../udea-codegen/net-protocol.lock"),
    rootDir.resolve("../udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/protocol/ProtocolLock.kt"),
    rootDir.resolve("../.github/workflows/ci.yml"),
    rootDir.resolve("../docs/compiler-plugin.md"),
    rootDir.resolve("../docs/module-graph.md"),
    fileTree(rootDir.resolve("..")) {
        include("*/build.gradle.kts")
        include("build.gradle.kts")
        include("udea-gradle/src/**/*.kt")
        include("moba/src/**/*.kt")
    },
)

tasks.test {
    useJUnitPlatform()
    inputs.files(outerBuildInputs)
        .withPropertyName("outerBuildSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
