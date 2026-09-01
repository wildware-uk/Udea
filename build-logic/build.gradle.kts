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

    // The bytecode reader behind `udeaVerifyDeterminism` (issue #150). `implementation`, not
    // `testImplementation`: the scan runs inside a Gradle task, not inside a test JVM, which
    // is the difference from `udea-render`'s `udeaVerifyHeadless` - that one is a Test task,
    // so its ASM dependency is test-scoped there. Nothing this puts on a build-logic classpath
    // reaches a shipped runtime classpath; `udeaVerifyRelease` (UDEA-REL-002) checks that
    // independently.
    implementation(libs.asm)

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

    // `ContractFreezeTest` reads the frozen documents, the lock that freezes them, and the
    // section of `AGENTS.md` that names the gate; all three are outside anything Gradle would
    // otherwise associate with this module. Without these lines the test stays UP-TO-DATE
    // across an edit to a contract, the deletion of the lock, and an `AGENTS.md` that stops
    // naming the route out - which are exactly the edits it exists to notice, and a freeze
    // gate that passes from cache is a freeze that is not happening.
    //
    // `AGENTS.md` also closes the same hole for `AgentsMdTest`, which has read the real file
    // since issue #138 without it being declared. That gate's *task* (`udeaVerifyAgentsMd`)
    // declares it properly, so nothing escaped - but the unit half could go stale across the
    // one edit it watches.
    rootDir.resolve("../AGENTS.md"),
    rootDir.resolve("../docs/contracts.lock"),
    fileTree(rootDir.resolve("../docs/contracts")),

    // The determinism pair, and the serious one of the five issue #180 found.
    //
    // `AuditTest` and `AllowlistParserTest` are the enforcement behind spec section 6's third
    // Phase 7 exit criterion, "the allowlist is a reviewed artefact, not a dumping ground":
    // they are what makes a malformed, stale or unreasoned entry fail the build. Undeclared,
    // they came back UP-TO-DATE across an edit to the allowlist - so the gate that keeps the
    // allowlist honest was absent at the only moment it does anything, and the build was green
    // while it was absent. `FloatPortabilityTest` reads the audit for the same reason: it
    // measures a divergence it cannot assert on, and asserts instead that the audit still tells
    // the reader which CI job is the only thing that can catch it.
    rootDir.resolve("../determinism-allowlist.txt"),
    rootDir.resolve("../determinism-audit.md"),

    // `UdeaVersionsTest` is the Kotlin-version pin read straight out of the catalog, and
    // `GradleFixture` copies the catalog into every TestKit fixture it builds. A version bump
    // is exactly the edit those want to run on, and exactly the edit that left them cached.
    rootDir.resolve("../gradle/libs.versions.toml"),

    // `TrelloMapTest` reads the spec and the map and asserts they account for each other, so
    // either one moving on its own is the whole point of it. Both were undeclared, which made
    // it a comparison of two files nothing re-read.
    rootDir.resolve("../docs/migration/trello-map.md"),
    rootDir.resolve("../docs/superpowers/specs/2026-08-22-udea-ai-native-rewrite-design.md"),
    fileTree(rootDir.resolve("..")) {
        include("*/build.gradle.kts")
        include("build.gradle.kts")
        include("udea-gradle/src/**")
        include("moba/src/**/*.kt")

        // `CharacterArtStagingTest` derives what the build has to stage from the sprites the real
        // asset scripts name, and checks every one of them against the committed tree it copies
        // out of. Both are outside anything Gradle would otherwise associate with this module, so
        // without these two lines the test stays UP-TO-DATE across a new character and across the
        // deletion of the art it stages - which are the two edits it exists to notice.
        include("moba/assets/**/*.udea.kts")
        include("example/src/main/resources/assets/sprites/**")
    },
)

/**
 * `build-logic`'s own sources, declared because several tests read them as *text*.
 *
 * They already reach `test` as compiled classes, which is a different object from the source
 * file: compile avoidance means an edit confined to a comment produces byte-identical classes
 * and leaves the task UP-TO-DATE. `CompilerPluginSwitchTest` and `OuterBuildInputsTest` both
 * scan the text, and `OuterBuildInputsTest` deliberately reads comments too, so a path named on
 * a commented-out line still has to be declared. Without these two lines that scan is served
 * from cache across exactly the edits it reads.
 */
val buildLogicSources: FileCollection = files(
    fileTree(rootDir.resolve("src/main/kotlin")),
    fileTree(rootDir.resolve("src/test/kotlin")),
)

tasks.test {
    useJUnitPlatform()
    inputs.files(outerBuildInputs)
        .withPropertyName("outerBuildSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(buildLogicSources)
        .withPropertyName("buildLogicSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // The manifest `OuterBuildInputsTest` checks itself against: every declared file above, as
    // a repository-relative path, under the system property that test names.
    //
    // It asks "is this file an input of the task I am running in?", and the only honest answer
    // is the collection Gradle actually resolved. Re-deriving it by regex over this script would
    // put a second, differently-wrong parser between the assertion and its subject, which is
    // this defect's own shape one level up.
    //
    // A `CommandLineArgumentProvider` rather than `systemProperty` so the trees are walked when
    // the task runs: walking them while this script is configured would make every edit under
    // `moba/src` invalidate the configuration cache for the whole build. The two locals exist
    // for the same reason - the lambda has to close over a FileCollection and a File and
    // nothing else, because a lambda that reaches back into the script cannot be serialized
    // into the configuration cache at all.
    //
    // The property name is spelt out on both sides rather than shared through a constant,
    // because a build script cannot use a class its own project compiles. That duplication is
    // safe in the direction that matters: if the two ever disagree, the test finds no manifest
    // and fails saying so, rather than finding an empty one and passing on anything.
    val manifestSources: FileCollection = outerBuildInputs + buildLogicSources
    val manifestRoot: File = rootDir.parentFile.canonicalFile
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            val manifest = manifestSources.files.asSequence()
                .map { it.canonicalFile }
                .filter { it.isFile }
                .map { it.relativeTo(manifestRoot).invariantSeparatorsPath }
                .sorted()
                .joinToString("\n")
            listOf("-Dudea.declaredTestInputs=$manifest")
        },
    )
}
