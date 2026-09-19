import com.vanniktech.maven.publish.MavenPublishBaseExtension
import dev.wildware.udea.build.ProjectScope
import dev.wildware.udea.build.UdeaPom
import dev.wildware.udea.build.UdeaVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")

    // Not applied: the root project has no sources of its own. It is declared so that the
    // Kotlin Gradle plugin is on this script's classpath, which is what makes the
    // `KotlinCompile` type below resolvable for the `allprojects` jvmTarget rule.
    kotlin("jvm") version "2.4.20" apply false

    // Phase 0's build gates, as the one plugin a Udea build applies to get them (issue #265):
    // the module-graph check on every project that has a build script, the determinism scan over
    // the scopes `udeaGates { }` below declares, and the release scan on the project that ships.
    // A game in its own repository applies this same plugin and writes the same block, so the
    // gates `moba` is held to are the gates any game is held to, by one code path rather than by
    // a list of `:moba:*` paths inside `build-logic` that only this repository could satisfy.
    id("udea.game-gates")

    // The exception: `AGENTS.md` and the Trello map are documents about the whole tree, so the
    // gates that hold them to it belong on the root.
    id("udea.docs-check")

    // And once more, for the same reason (issue #174): `docs/contracts/` is declared frozen in
    // `AGENTS.md` and nothing enforced it, so a contract several modules independently
    // implement could move in any commit and the build stayed green. The question is about the
    // repository rather than about a module, so the gate belongs where the other two do.
    id("udea.contract-freeze")

    // Root for the same reason again (issue #181): the clean-build-budget CI job asks whether a
    // commit made `udeaAssemble` as a whole slower, and this is where it asks for the verdict.
    id("udea.clean-build-budget")

    // Publishing to Maven Central (issue #265). Declared here and applied below to the modules
    // the `published` set names, which is how a game in its own repository gets the engine at
    // all: it resolves `dev.wildware.udea:udea-core` from a repository rather than from this
    // checkout. Nothing has been published from this branch - see `BRIEF-265.md`.
    alias(libs.plugins.mavenPublish) apply false
}

/**
 * The version every module of this build carries, and the one every published artifact gets.
 *
 * The rule is [UdeaVersion.resolve], where each of its branches is executed by `UdeaVersionTest`:
 * `-PudeaVersion` wins, else a `v1.4.2` tag on this exact commit, else a snapshot of the next
 * minor, else [UdeaVersion.FIRST_SNAPSHOT]. This repository has no release tag today, so an
 * ordinary build here is `0.1.0-SNAPSHOT`.
 *
 * It used to be the literal `1.0-SNAPSHOT`, written once here and once again in
 * `udea.kotlin-base`. The second copy was the one that mattered, because a convention plugin sets
 * it on whatever project applies the convention - so a game in its own repository was published
 * under the engine's group and the engine's version by a plugin it merely applied. Group and
 * version are this build's own business, and they are set on this build's projects only.
 */
val udeaVersion: String = UdeaVersion.resolve(
    asked = providers.gradleProperty(UdeaVersion.PROPERTY).orNull,
    // `--always` so a repository with no tag answers a commit id instead of failing, and
    // `isIgnoreExitValue` plus the catch so a tree exported without its `.git`, or a machine with
    // no git on it, publishes the first snapshot rather than failing to configure. Neither is an
    // error: the release workflow passes the property.
    described = runCatching {
        providers.exec {
            commandLine("git", "describe", "--tags", "--always", "--dirty")
            isIgnoreExitValue = true
        }.standardOutput.asText.get()
    }.getOrDefault(""),
)

allprojects {
    group = UdeaPom.GROUP
    version = udeaVersion

    repositories {
        mavenCentral()
        mavenLocal()
        gradlePluginPortal()
        google()
        // ComposeGL's snapshots. Sonatype moved its snapshot hosting here; the two `oss.sonatype.org`
        // hosts below answer 404 for `dev.wildware.composegl` today, so this is the line that makes
        // `composegl-kool:0.7.0-SNAPSHOT` resolve at all (issue #224). It is the host ComposeGL's own
        // `docs/wiki/Kool.md` names.
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
        maven("https://s01.oss.sonatype.org")
        maven("https://jitpack.io")
    }

    // Configured wherever a module has the `java` plugin, and no longer applied to every project
    // to get there (issue #201). The Kotlin multiplatform plugin refuses to share a project with
    // `java`, and every JVM module gets `java` from `kotlin("jvm")` already.
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
}

// --- publishing (issue #265) ------------------------------------------------------------------

/**
 * The projects that go to Maven Central, and nothing else.
 *
 * A written-out list rather than "everything that is not `moba`", because the cost of getting
 * this wrong is permanent: a version published to Central can never be deleted or replaced. The
 * example game published by accident is a 5v5 MOBA somebody can depend on forever.
 *
 * Two of these are debug-only and are published anyway, which is not a contradiction.
 * `udea-agent-host` and `udea-editor` are the tools a game is *made* with - the MCP surface and
 * the editor window - so a game that cannot resolve them cannot be developed at all. What
 * "debug-only" means here is that they must not reach a *release classpath*, which is
 * `UDEA-MG-012` and `UDEA-REL-002`'s job on the game's own build, and those gates travel with the
 * game because `udea.game-gates` publishes too.
 *
 * `udea-assets-compiler` and `udea-gradle` are build-time only for the same reason and ship for
 * the same reason: `dev.wildware.udea.assets` cannot run a pipeline whose compiler is absent.
 */
val published: Set<String> = setOf(
    ":udea-annotations",
    ":udea-diagnostics",
    ":udea-codegen",
    ":udea-compiler-plugin",
    ":udea-fleks",
    ":udea-core",
    ":udea-assets",
    ":udea-assets-compiler",
    ":udea-gas",
    ":udea-net",
    ":udea-physics2d",
    ":udea-render",
    ":udea-audio",
    ":udea-agent",
    ":udea-agent-host",
    ":udea-editor",
    ":udea-replay",
    ":udea-gradle",
)

/**
 * The list above is a snapshot, so the build re-derives it rather than trusting it.
 *
 * Every engine module has to appear, because the arrows point downward and a missing one is a
 * module somebody's game cannot resolve - found, if this were left to a reviewer, as a resolution
 * failure in a repository that is not this one. And every entry has to be a real project, so a
 * module that is renamed fails here instead of silently dropping out of the release.
 *
 * `ProjectScope.ENGINE_PREFIX` is the same `:udea-` the module-graph rules scope themselves by,
 * so "engine module" means one thing in this build rather than two.
 */
run {
    val paths = subprojects.filter { it.buildFile.exists() }.map { it.path }.toSet()
    val missing = paths.filter { ProjectScope.isEngineModule(it) } - published
    check(missing.isEmpty()) {
        "$missing are engine modules that nothing publishes. A game outside this repository " +
            "resolves the engine from a repository, so a module left out here is a module it " +
            "cannot compile against. Add it to `published`, or, if it genuinely must not ship, " +
            "say so here in a line that explains why."
    }
    val unknown = published - paths
    check(unknown.isEmpty()) {
        "$unknown are named in `published` but are not projects of this build."
    }
}

/**
 * Which tasks read a KSP output directory without Gradle knowing who wrote it.
 *
 * A list of predicates rather than a list of names, because the names are per-target and per-
 * variant - `sourcesJar`, `androidSourcesJar`, `bundleAndroidMainClassesToCompileJar`,
 * `processAndroidMainJavaRes` - and a module that gains a target would otherwise gain a silent
 * gap. Each one is here because a build failed on it by name; nothing is here speculatively.
 */
val KSP_OUTPUT_CONSUMERS: List<(String) -> Boolean> = listOf(
    // The sources jars: the generated sources are on the source set, so they are packed.
    { name: String -> name.endsWith("ourcesJar") },
    // The Android variant's bundling tasks - `bundleAndroidMainClassesToCompileJar`,
    // `bundleLibRuntimeToDirAndroidMain` - and its resource processing, which read the same
    // source set. Every `bundle*` task rather than the two the build named, because they are one
    // family reading one source set and the third would fail the same way on some later build.
    { name: String -> name.startsWith("bundle") },
    { name: String -> name.startsWith("process") && name.endsWith("JavaRes") },
)

configure(subprojects.filter { it.path in published }) {
    apply(plugin = "com.vanniktech.maven.publish")

    /**
     * What Central insists on: a name, a description, a home, a licence, a human, and where the
     * source is. [UdeaPom] holds the values, so the engine's modules and `build-logic`'s plugins
     * describe themselves as the same project.
     *
     * Signing and the upload are configured by properties rather than here, so the key and the
     * token live in CI's secrets and never in the repository. Without them this still builds -
     * `publishToMavenLocal` works on any machine, and that is what `scripts/outside-game-proof.sh`
     * uses - it simply cannot publish.
     */
    afterEvaluate {
        /**
         * A sources jar packs the generated sources too, and nothing told it who writes them.
         *
         * Publishing is the first thing in this build to ask for a sources jar, and it fails
         * immediately on `udea-core`:
         *
         *     Task ':udea-core:sourcesJar' uses this output of task
         *     ':udea-core:kspCommonMainKotlinMetadata' without declaring an explicit or implicit
         *     dependency.
         *
         * The source *directory* is on the source set, so the jar task reads
         * `build/generated/ksp/...`, but the generator is a KSP task the jar has no edge to.
         * Gradle refuses rather than racing, which is the right answer; this supplies the edge.
         * Every KSP task of the module rather than the one named in the message, because which
         * ones exist depends on the module's targets - `kspCommonMainKotlinMetadata`,
         * `kspKotlinJvm`, `kspKotlinAndroidRelease` - and a sources jar wants all of them.
         *
         * Matched by name, and not by `tasks.withType<Jar>()`, which silently matches nothing
         * here. A multiplatform module's `sourcesJar` is registered as `org.gradle.jvm.tasks.Jar`,
         * and `Jar` in a build script is `org.gradle.api.tasks.bundling.Jar`, which *extends* it -
         * so the filter that looks like the obvious one asks for a subtype the task is not. The
         * failure mode is a filter that matches nothing and a build that stays green until
         * something else notices, which is what this comment is for.
         */
        // Main-source KSP only. A test compilation's processor - `kspAndroidHostTest` - reads the
        // main variant's classes jar, so making that jar wait for every KSP task of the module is
        // a cycle: `bundleAndroidMainClassesToCompileJar` -> `kspAndroidHostTest` ->
        // `bundleAndroidMainClassesToCompileJar`. Nothing published packs a test source set.
        val kspTasks = tasks.matching { it.name.startsWith("ksp") && !it.name.contains("Test") }
        tasks.matching { name -> KSP_OUTPUT_CONSUMERS.any { it(name.name) } }
            .configureEach { dependsOn(kspTasks) }

        // Read out here: inside the `pom` block, `name` and `description` are the POM's own.
        val moduleName = name
        val moduleDescription = description
            ?: error("$moduleName has no description, and Central requires one")

        extensions.configure<MavenPublishBaseExtension> {
            // Upload a deployment and stop. A person presses publish, in the `central-publish`
            // workflow; nothing about a push or a tag releases anything.
            publishToMavenCentral(automaticRelease = false)
            if (project.hasProperty("signingInMemoryKey")) signAllPublications()

            coordinates(group.toString(), name, version.toString())

            pom {
                name.set(moduleName)
                description.set(moduleDescription)
                url.set(UdeaPom.URL)
                inceptionYear.set(UdeaPom.INCEPTION_YEAR)

                licenses {
                    license {
                        name.set(UdeaPom.LICENCE_NAME)
                        url.set(UdeaPom.LICENCE_URL)
                    }
                }

                developers {
                    developer {
                        id.set(UdeaPom.DEVELOPER_ID)
                        name.set(UdeaPom.DEVELOPER_NAME)
                        url.set(UdeaPom.DEVELOPER_URL)
                    }
                }

                scm {
                    url.set(UdeaPom.URL)
                    connection.set(UdeaPom.SCM_CONNECTION)
                    developerConnection.set(UdeaPom.SCM_DEVELOPER_CONNECTION)
                }
            }
        }
    }
}

// The root project deliberately declares no sources and no dependencies. It used to be a
// Compose Desktop application wrapping `:level-editor`, with an `integrationTest` source set
// over a checked-in copy of an entire sample project. D6 deleted the editor; the root is now
// only an aggregator for the subprojects and the gates below.

// --- Phase 0 build gates (spec 4, spec 6, spec 7) ------------------------------------
//
// Wired through `udea.game-gates` rather than in each module's build script for two reasons: a
// gate a module opts into is a gate a new module forgets, and these files are owned by whoever
// owns the module, which is the wrong person to be able to switch off the rule that governs the
// module. The plugin registers the module-graph check on every project of this build that has a
// build script, the determinism scan over the scopes declared below, and the release scan on
// each project `ships` names - plus the aggregates over all three.

/**
 * What this build tells the gates about itself (issue #265).
 *
 * Every line here used to be a `:moba` path inside `build-logic` - `MOBA_PROJECTS` for the
 * module-graph rules about a shipped game, `:moba:desktop` for the release scan, and
 * `DeterminismRules.SIMULATION_SCOPES`' last entry for the game's own rules. A game in its own
 * repository could satisfy none of them, and was gated by nothing while its build looked green.
 * They are configuration now, and this block is both this repository's declaration and the
 * worked example a game's own root build script follows; `docs/new-game.md` is the guide.
 */
udeaGates {
    // The release gate lives on the project that actually ships a runnable process. That is the
    // desktop launcher now (issue #212): `:moba:game` is a library with no entry point in it, and
    // the classpath `UDEA-REL-002` is about is the one a player's or an agent's process runs on.
    ships(":moba:desktop")

    // `:moba:game` since issue #212 split the launchers off. The game's rules are the whole of
    // what simulates; neither launcher holds a system.
    simulation(
        project = ":moba:game",
        packagePrefixes = listOf(
            "dev.wildware.moba.ability",
            "dev.wildware.moba.ai",
            "dev.wildware.moba.match",
        ),
        why = "The example game's own rules: abilities and combat, unit AI, and the match " +
            "lifecycle. Its HUD, audio cues, scene, animation and renderers live outside " +
            "these prefixes and are presentation, where seconds and PresentationRandom are " +
            "allowed (spec 3.3, spec 5).",
    )
}

/**
 * Gradle paths of the engine and the game: every project with a build script of its own.
 *
 * `buildFile.exists()` is not a nicety. `:moba` is a container since issue #212 - it holds
 * `:moba:game`, `:moba:desktop` and `:moba:android` and has no build script, no plugins and no
 * configurations of its own - and the gates refuse a project they would inspect nothing of:
 * *"matched none of the configurations [...], so udeaVerifyModuleGraph inspected nothing. A gate
 * with no input passes forever"*. That refusal is right, so the container is excluded here rather
 * than the message being softened there.
 *
 * `udea.game-gates` selects the same set for the gates themselves; this local is what
 * [udeaAssemble] below aggregates over.
 */
val rewriteProjects = subprojects.filter { it.buildFile.exists() }

/**
 * `assemble` for the engine and the game.
 *
 * The clean-build budget (spec 6, Phase 0 exit: <90s) is measured against this. It was
 * introduced so the budget would not measure the old tree - `common` and `example` resolving
 * KryoNet, Box2D natives and five `kotlin-scripting-*` artifacts - and it stays after issue #213
 * deleted that tree, because the clean-build-budget CI job names this task.
 */
val udeaAssemble by tasks.registering {
    group = "build"
    description = "Assembles every udea-* project and every moba project."
    dependsOn(rewriteProjects.map { "${it.path}:assemble" })
}

// --- the wall-clock latency budgets (issue #175) ----------------------------------------------
//
// Every gate in this repository that asserts a number of *milliseconds*, gathered under one task
// so that one CI job can measure them all with the runner to itself.
//
// ## Why they are not on `check`
//
// They were, and they could not pass on a GitHub runner. `check` runs inside `build`, so each of
// these was measured while nineteen other modules compiled on the same cores, and a wall-clock
// measurement taken during a parallel build measures the build.
//
// Measured on this box, same tree, minutes apart: graph deserialisation medians 4.8ms run alone
// and serialised, and 18.1ms run `--parallel` beside a full build - against a 15ms budget, so the
// same bytes pass and fail depending only on how they were invoked. The most extreme figure comes
// from dev-174's independent run on an *idle* box (`sh gradlew build --rerun-tasks`, 181 of 181
// tasks executed, recorded on issue #174): the warm daemon reload medianed 1131ms inside the build
// against 117-393ms alone. This repository's own parallel build is enough on its own; a shared
// machine is not required. Three waves of developers each rediscovered it by re-running solo.
//
// ## Why this is not "take them off `check` and forget them"
//
// Issue #175 lists that as option 3 and ranks it last, because it quietly means nobody measures
// latency in CI at all. This is option 1: they are measured on **every push, on both runner
// images**, by the `latency-budgets` job, which runs this task and nothing else with
// `--no-parallel --max-workers=1`. `:udea-gradle`'s `LatencyBudgetJobTest` is what stops the two
// halves drifting apart - it reads the list below out of this file and asserts the workflow still
// measures every member of it, serially, on every runner the `build` job covers.
//
// It is also the arrangement this repository already uses for exactly this reason. `runUdpProof`
// and `runLaneShot` sit outside `check` because wall-clock timing across forked JVMs and a GL
// driver are not things a parallel build can hold still. These are the same class of thing, and
// they were the ones that had not been moved yet.
//
// Adding a budget here is what puts it under the CI job and under that test. Do not add anything
// else: a task in this list is one whose number is a duration, and a correctness gate parked here
// would be a correctness gate nobody runs on `check`.
//
// ## Why the list is no longer the enumeration (issue #182)
//
// This list has been declared complete three times and been wrong twice. #175 enumerated a set,
// found `udeaDigestBudget` and `udeaQueryBudget` while wiring them, and left two behind.
// `review-175-r1` found those two and filed #182.
// #182's own work found three the issue had not named - a one-second warm compile in
// `AssetCompilerTest`, a 2ms rebuild in `PhysicsRebuildTest` and a two-second bound in
// `NetHarnessTest`. Each enumeration was honest and each was a snapshot.
//
// So the aggregate's description is now checked rather than trusted. `:udea-gradle`'s
// `WallClockBudgetCensusTest` reads every test source in the repository and requires each one
// that touches a wall clock to be either a member of this list or a row in its own census saying
// what the reading is instead. A new timing test is red until somebody decides which.
val latencyBudgetTasks = listOf(
    ":udea-core:udeaSnapshotBudget",
    ":udea-core:udeaBenchTickLoop",
    ":udea-core:udeaBenchCharacterMover",
    ":udea-core:udeaPhysicsRebuildBudget",
    ":udea-assets-compiler:udeaDaemonBudget",
    ":udea-assets-compiler:udeaGraphBudget",
    ":udea-assets-compiler:udeaScanBudget",
    ":udea-assets-compiler:udeaWarmEditBudget",
    ":udea-agent:udeaDigestBudget",
    ":udea-agent:udeaQueryBudget",
    ":udea-agent-host:udeaPhase2Exit",
)

val udeaLatencyBudgets by tasks.registering {
    group = "verification"
    description =
        "Measures every wall-clock latency budget. Run it with --no-parallel --max-workers=1 " +
            "and nothing else on the machine, or it measures the machine."
    dependsOn(latencyBudgetTasks)
}

/**
 * A latency budget is never up to date and is never served from the build cache.
 *
 * Found on the first two CI runs of this branch, which is the only reason it is written down as a
 * rule rather than assumed: run 33450534282 measured all six on both runners, and run
 * 33451573256 - a docs-only commit, so identical task inputs - reported every one of them
 * `FROM-CACHE` on **both** `ubuntu-latest` and `windows-latest` and finished the whole job in 24
 * seconds. Two green ticks, one measurement. A `Test` task is cacheable by default and Gradle was
 * entirely right by its own rules: same inputs, same outputs.
 *
 * But the input to a stopwatch is the machine, and the machine is exactly what is not in the
 * cache key. A cached green here says "this code was fast on some runner once", which is the
 * skip-reads-as-a-pass defect this repository has already closed twice - once for the GL tests
 * and once for the atlas tests - arriving through a third door. So both switches are off:
 * `upToDateWhen` because the previous run's outputs are not an answer about this run's machine,
 * and `cacheIf` because a task that is not up to date still consults the cache before executing.
 *
 * Configured here rather than six times over in three build scripts, and lazily through
 * `configureEach`, so a task that is never realised is never configured. Matching on the simple
 * name keeps [latencyBudgetTasks] the single list.
 */
val latencyBudgetTaskNames: Set<String> = latencyBudgetTasks.map { it.substringAfterLast(':') }.toSet()

subprojects {
    tasks.withType<Test>().configureEach {
        // Which task is running this JVM, for `LatencyBudget.measuredBy` (issue #182). A budget is
        // held out of `build` by one `filter.excludeTestsMatching` line in a build script, and
        // deleting that line puts it back inside the parallel build - where it may stay green for
        // a long time, because these gates are sized to catch a regression rather than to detect a
        // busy machine. Nothing else in the repository can see which task ran a test, so the
        // answer is handed to the JVM at the only moment it is known. Set on every test task, not
        // only the budgets: the value has to be wrong for the guard to fire, and an absent
        // property is how a budget run by `test` would look if only the budgets were told.
        systemProperty("udea.testTaskPath", path)

        if (name in latencyBudgetTaskNames) {
            outputs.upToDateWhen { false }
            outputs.cacheIf("a wall-clock measurement is about this machine, not about these inputs") {
                false
            }
        }
    }
}
