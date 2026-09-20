import dev.wildware.udea.build.udeaGates

/**
 * Every Udea build gate that is about a *build* rather than about one module, applied to the
 * root project of the build it is applied to (issue #265).
 *
 * This is how a game outside this repository gets the checks `moba` gets. It is also how `moba`
 * gets them: this repository's root build script applies this plugin and declares itself through
 * `udeaGates { }` exactly as an outside game does, so the two are one code path and the one
 * anybody can run - `sh gradlew build` here - is the one an outside game uses.
 *
 * ## What it wires
 *
 * - `dev.wildware.udea.module-graph-check` on every project of the build that has a build script, which is
 *   what registers the per-project `udeaVerifyModuleGraph` and `udeaVerifyEditorAbsent` on their
 *   own `check`. A container project - `:moba` holds three projects and has no build script of
 *   its own - is excluded, because both gates refuse a project they would inspect nothing of.
 * - the `udeaVerifyModuleGraph` aggregate, so one command covers the whole build.
 * - `dev.wildware.udea.determinism-check`, which reads `udeaGates { simulation(...) }`.
 * - `dev.wildware.udea.release-check` on each project named by `udeaGates { ships(...) }`, and the
 *   `udeaVerifyRelease` aggregate over them.
 *
 * ## What it does not wire, and why
 *
 * `dev.wildware.udea.docs-check` and `dev.wildware.udea.contract-freeze` are this repository's own bookkeeping:
 * `udeaVerifyAgentsMd` holds `AGENTS.md`'s module table against `settings.gradle.kts` *and*
 * requires the nine spec section 5 contracts to be named, and `udeaVerifyContracts` freezes
 * `docs/contracts/`. Both are statements about the engine's documents, which a game's repository
 * does not have and should not be made to carry; the engine checks them in its own `check`, and
 * a game that vendored copies of them would be freezing a copy.
 */

plugins {
    base
}

/** Created here so a build script's `udeaGates { }` block has something to configure. */
udeaGates()

/**
 * The projects the per-module gates are registered on: every project of this build with a build
 * script of its own.
 *
 * `buildFile.exists()` is not a nicety. `:moba` is a container since issue #212 - it holds
 * `:moba:game`, `:moba:desktop` and `:moba:android` and has no build script, no plugins and no
 * configurations of its own - and both gates refuse a project they would inspect nothing of:
 * *"matched none of the configurations [...], so udeaVerifyModuleGraph inspected nothing. A gate
 * with no input passes forever"*. That refusal is right, so the container is excluded here rather
 * than the message being softened there.
 *
 * Every project rather than a prefix list (issue #265). The list was `:udea-*` and `:moba:*`,
 * which is this repository's tree written down a second time: a game's own projects matched
 * neither, and so did a new project here - `:hollow:*` would have had to be added to it.
 */
val gatedProjects: List<Project> = subprojects.filter { it.buildFile.exists() }

subprojects {
    if (this in gatedProjects) {
        apply(plugin = "dev.wildware.udea.module-graph-check")
    }
}

apply(plugin = "dev.wildware.udea.determinism-check")

/**
 * Aggregates, so a developer can run one gate over the whole build. Each depends on the
 * per-project task by path; the per-project tasks are also on their own `check`, so a plain
 * `./gradlew build` cannot pass while a rule is broken.
 */
val udeaVerifyModuleGraph = tasks.register("udeaVerifyModuleGraph") {
    group = "verification"
    description = "Runs udeaVerifyModuleGraph on every project of this build that has one."
    dependsOn(gatedProjects.map { "${it.path}:udeaVerifyModuleGraph" })
}

tasks.named("check") {
    dependsOn(udeaVerifyModuleGraph)
}

/**
 * The release scan over the projects that ship.
 *
 * Registered in `afterEvaluate` because `udeaGates { ships(...) }` runs after this plugin does,
 * and an aggregate over a list read too early would be an aggregate over nothing - which passes,
 * silently, exactly the way an ungoverned project does. A build that declares no shipping project
 * gets no task rather than an empty one.
 */
afterEvaluate {
    val shipping = udeaGates().shipping.toList()
    if (shipping.isNotEmpty()) {
        tasks.register("udeaVerifyRelease") {
            group = "verification"
            description = "Runs the release artifact scan on every project this build ships."
            dependsOn(shipping.map { "$it:udeaVerifyRelease" })
        }
    }
}
