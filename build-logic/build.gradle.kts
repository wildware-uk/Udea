plugins {
    `kotlin-dsl`
}

group = "dev.wildware.udea.build"

/**
 * `udea-gradle`'s plugin sources, compiled a second time so that THIS build can apply them.
 *
 * A Gradle plugin that lives in a subproject cannot be applied to a sibling subproject of the
 * same build: the plugin has to be on the settings-level classpath, and `:udea-gradle` is not
 * built until after every build script has been evaluated. That is why `UdeaAgentPlugin` sat
 * unreachable - a real class with no id and no applier - while the working Phase 1 demo lived in
 * a test source set.
 *
 * The alternatives were worse. Moving the plugin into `build-logic` contradicts spec 4, which
 * gives `udea-gradle` this job and expects it to be publishable for real games. `includeBuild`ing
 * `udea-gradle` is circular: its own build script applies the `udea.gradle-plugin` convention from
 * here. Copying the wiring into a convention plugin is the duplication that produces two
 * implementations which disagree.
 *
 * So there is one source file and two compilations of it. `:udea-gradle:jar` remains the artifact
 * a game outside this repository consumes; this compilation is how `:moba` gets it. The cost is
 * real and worth stating: anything these sources reference must be resolvable from `build-logic`'s
 * classpath too, so `udea-gradle` cannot start using `:udea-assets-compiler` types from
 * `UdeaAgentPlugin` without splitting the file. `UdeaAgentPluginIdTest` pins the id and the
 * implementation class against `udea-gradle`'s own `META-INF/gradle-plugins` descriptor, so the
 * two declarations cannot drift.
 */
val udeaGradleSources: File = rootDir.resolve("../udea-gradle/src/main/kotlin")

sourceSets.main {
    kotlin.srcDir(udeaGradleSources)
}

gradlePlugin {
    plugins {
        register("udeaAgent") {
            id = "dev.wildware.udea.agent"
            implementationClass = "dev.wildware.udea.gradle.UdeaAgentPlugin"
            description = "gamebridge.json, the debug-only agent source set, and the run wiring."
        }
        register("udeaAssets") {
            id = "dev.wildware.udea.assets"
            implementationClass = "dev.wildware.udea.gradle.UdeaAssetsPlugin"
            description = "scan, compile, validate, pack and generate accessors for a .udea.kts tree."
        }
    }
}

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
 * `settings.gradle.kts`, `CompilerPluginSwitchTest` walks `build-logic`'s own sources,
 * `ci.yml` and `docs/compiler-plugin.md` checking the K2 wiring is still applied and still
 * described truthfully, `UdeaCompilerPluginWiringTest` reads `udea-compiler-plugin`'s build
 * script and its CLI contract, `UdeaProtocolLockTest` reads `udea-codegen`'s lock and
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
    rootDir.resolve(
        "../udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/UdeaCompilerPlugin.kt",
    ),
    rootDir.resolve("../.github/workflows/ci.yml"),
    rootDir.resolve("../docs/compiler-plugin.md"),
    rootDir.resolve("../docs/module-graph.md"),
    fileTree(rootDir.resolve("..")) {
        include("*/build.gradle.kts")
        include("build.gradle.kts")
        include("udea-gradle/src/**")
        include("moba/src/**/*.kt")
    },
)

tasks.test {
    useJUnitPlatform()
    inputs.files(outerBuildInputs)
        .withPropertyName("outerBuildSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
