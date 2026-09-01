import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")

    // Not applied: the root project has no sources of its own. It is declared so that the
    // Kotlin Gradle plugin is on this script's classpath, which is what makes the
    // `KotlinCompile` type below resolvable for the `allprojects` jvmTarget rule.
    kotlin("jvm") version "2.2.10" apply false

    // Phase 0 build gates from the `build-logic` included build. Applied to the rewrite
    // subprojects below, never to the root or to the old tree.
    id("udea.legacy-dependency-check") apply false
    id("udea.module-graph-check") apply false
    id("udea.release-check") apply false

    // The exception: the migration gates ask about the whole tree at once, so they are the one
    // pair that belongs on the root. See `docs/migration/ledger.md`.
    id("udea.migration-check")

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
}

group = "dev.wildware.udea"
version = "1.0-SNAPSHOT"

allprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
        mavenLocal()
        gradlePluginPortal()
        google()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
        maven("https://s01.oss.sonatype.org")
        maven("https://jitpack.io")
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
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
// which is the wrong person to be able to switch off the rule that stops the old tree leaking
// into the new one.

/** Gradle paths of the rewrite tree: everything the Phase 0 gates apply to. */
val rewriteProjects = subprojects.filter { it.path.startsWith(":udea-") || it.path == ":moba" }

subprojects {
    if (this in rewriteProjects) {
        apply(plugin = "udea.legacy-dependency-check")
        apply(plugin = "udea.module-graph-check")
    }
    // The release gate lives on the one project that actually ships a jar.
    if (path == ":moba") {
        apply(plugin = "udea.release-check")
    }
}

/**
 * Aggregates, so a developer can run one gate over the whole tree. Each depends on the
 * per-project task by path; the per-project tasks are also on their own `check`, so a plain
 * `./gradlew build` cannot pass while a rule is broken.
 */
val udeaVerifyNoLegacyDependencies by tasks.registering {
    group = "verification"
    description = "Runs udeaVerifyNoLegacyDependencies on every udea-* project and on moba."
    dependsOn(rewriteProjects.map { "${it.path}:udeaVerifyNoLegacyDependencies" })
}

val udeaVerifyModuleGraph by tasks.registering {
    group = "verification"
    description = "Runs udeaVerifyModuleGraph on every udea-* project and on moba."
    dependsOn(rewriteProjects.map { "${it.path}:udeaVerifyModuleGraph" })
}

val udeaVerifyRelease by tasks.registering {
    group = "verification"
    description = "Runs the release artifact scan on the shipping project."
    dependsOn(":moba:udeaVerifyRelease")
}

/**
 * `assemble` for the rewrite tree only.
 *
 * The clean-build budget (spec 6, Phase 0 exit: <90s) is measured against this. Budgeting
 * `assemble` instead would measure `common` and `example` resolving KryoNet, Box2D natives and
 * five `kotlin-scripting-*` artifacts, which would dominate the number and make the gate
 * meaningless - and it is a number the rewrite cannot move, because it belongs to code that is
 * on its way out.
 */
val udeaAssemble by tasks.registering {
    group = "build"
    description = "Assembles every udea-* project and moba, and nothing from the old tree."
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
// measurement taken during a parallel build measures the build. The same code, on this box:
// warm daemon reload medians 195ms alone and 646ms inside a full build; graph deserialisation
// medians 4.8ms alone and 18.1ms inside one, against a 15ms budget. Three waves of developers
// each rediscovered that by re-running the task solo.
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
val latencyBudgetTasks = listOf(
    ":udea-core:udeaSnapshotBudget",
    ":udea-core:udeaBenchTickLoop",
    ":udea-core:udeaBenchCharacterMover",
    ":udea-assets-compiler:udeaDaemonBudget",
    ":udea-assets-compiler:udeaGraphBudget",
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
        if (name in latencyBudgetTaskNames) {
            outputs.upToDateWhen { false }
            outputs.cacheIf("a wall-clock measurement is about this machine, not about these inputs") {
                false
            }
        }
    }
}

tasks.named("check") {
    dependsOn(udeaVerifyNoLegacyDependencies, udeaVerifyModuleGraph)
}
