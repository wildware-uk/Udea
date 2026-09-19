import dev.wildware.udea.build.ModuleGraphRules
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")

    // Not applied: the root project has no sources of its own. It is declared so that the
    // Kotlin Gradle plugin is on this script's classpath, which is what makes the
    // `KotlinCompile` type below resolvable for the `allprojects` jvmTarget rule.
    kotlin("jvm") version "2.4.20" apply false

    // Phase 0 build gates from the `build-logic` included build. Applied to the subprojects
    // below, never to the root.
    id("udea.module-graph-check") apply false
    id("udea.release-check") apply false

    // The exception: `AGENTS.md` and the Trello map are documents about the whole tree, so the
    // gates that hold them to it belong on the root.
    id("udea.docs-check")

    // The same exception, for the same reason (issue #150): `udeaVerifyDeterminism` asks about
    // a *set* of source sets spanning four modules, declared in
    // `DeterminismRules.SIMULATION_SCOPES`, so a per-module answer to "does simulation read the
    // wall clock" would be four answers to a question that has one. Root also keeps the switch
    // that disables the gate out of the build script of the module it polices.
    id("udea.determinism-check")

    // And once more, for the same reason (issue #174): `docs/contracts/` is declared frozen in
    // `AGENTS.md` and nothing enforced it, so a contract several modules independently
    // implement could move in any commit and the build stayed green. The question is about the
    // repository rather than about a module, so the gate belongs where the other two do.
    id("udea.contract-freeze")

    // Root for the same reason again (issue #181): the clean-build-budget CI job asks whether a
    // commit made `udeaAssemble` as a whole slower, and this is where it asks for the verdict.
    id("udea.clean-build-budget")
}

group = "dev.wildware.udea"
version = "1.0-SNAPSHOT"

allprojects {
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

// The root project deliberately declares no sources and no dependencies. It used to be a
// Compose Desktop application wrapping `:level-editor`, with an `integrationTest` source set
// over a checked-in copy of an entire sample project. D6 deleted the editor; the root is now
// only an aggregator for the subprojects and the gates below.

// --- Phase 0 build gates (spec 4, spec 6, spec 7) ------------------------------------
//
// Wired here rather than in each module's build script for two reasons: a gate a module opts
// into is a gate a new module forgets, and these files are owned by whoever owns the module,
// which is the wrong person to be able to switch off the rule that governs the module.

/**
 * Gradle paths of the engine and the games: everything the Phase 0 gates apply to.
 *
 * Which projects those are is `ModuleGraphRules.governs`, the one answer the rules themselves and
 * the compiler-plugin wiring use, so a game the rules cover is a game these gates run on: Hollow
 * (issue #249) joined by being added there, and a list written out here as well would be a second
 * thing to forget.
 *
 * `buildFile.exists()` is not a nicety. `:moba` is a container since issue #212 - it holds
 * `:moba:game`, `:moba:desktop` and `:moba:android` and has no build script, no plugins and no
 * configurations of its own, and `:hollow` is the same - and both gates refuse a project they
 * would inspect nothing of: *"matched none of the configurations [...], so udeaVerifyModuleGraph
 * inspected nothing. A gate with no input passes forever"*. That refusal is right, so the
 * container is excluded here rather than the message being softened there.
 */
val rewriteProjects = subprojects.filter { ModuleGraphRules.governs(it.path) && it.buildFile.exists() }

/**
 * The projects that ship a runnable process, and so get the release gate: each game's desktop
 * launcher. A game's library has no entry point in it, and the classpath `UDEA-REL-002` is about
 * is the one a player's or an agent's process runs on.
 */
val launcherProjects = listOf(":moba:desktop", ":hollow:desktop")

subprojects {
    if (this in rewriteProjects) {
        apply(plugin = "udea.module-graph-check")
    }
    if (path in launcherProjects) {
        apply(plugin = "udea.release-check")
    }
}

/**
 * Aggregates, so a developer can run one gate over the whole tree. Each depends on the
 * per-project task by path; the per-project tasks are also on their own `check`, so a plain
 * `./gradlew build` cannot pass while a rule is broken.
 */
val udeaVerifyModuleGraph by tasks.registering {
    group = "verification"
    description = "Runs udeaVerifyModuleGraph on every udea-* project and every game project."
    dependsOn(rewriteProjects.map { "${it.path}:udeaVerifyModuleGraph" })
}

val udeaVerifyRelease by tasks.registering {
    group = "verification"
    description = "Runs the release artifact scan on every shipping project."
    dependsOn(launcherProjects.map { "$it:udeaVerifyRelease" })
}

/**
 * `assemble` for the engine and the game.
 *
 * The clean-build budget (spec 6, Phase 0 exit: <90s) is measured against this. It was
 * introduced so the budget would not measure the old tree - `common` and `example` resolving
 * KryoNet, Box2D natives and five `kotlin-scripting-*` artifacts - and it stays after issue #213
 * deleted that tree, because the clean-build-budget CI job names this task.
 *
 * It is the engine and `moba`, and not Hollow (issue #249): that job compares this task's clean
 * build on a branch with the same task on its base, and a second game in it would read as the
 * engine's build slowing down on the branch that added the game.
 */
val udeaAssemble by tasks.registering {
    group = "build"
    description = "Assembles every udea-* project and every moba project."
    dependsOn(
        rewriteProjects
            .filter { it.path.startsWith(":udea-") || it.path.startsWith(":moba:") }
            .map { "${it.path}:assemble" },
    )
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

tasks.named("check") {
    dependsOn(udeaVerifyModuleGraph)
}
