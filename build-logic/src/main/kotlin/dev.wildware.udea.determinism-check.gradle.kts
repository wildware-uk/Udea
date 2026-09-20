import dev.wildware.udea.build.ModuleGraphRules
import dev.wildware.udea.build.determinism.DeterminismLayout
import dev.wildware.udea.build.determinism.DeterminismRules
import dev.wildware.udea.build.determinism.SimScope
import dev.wildware.udea.build.determinism.UdeaVerifyDeterminismTask
import dev.wildware.udea.build.determinism.VendoredFleks
import dev.wildware.udea.build.udeaCatalog
import dev.wildware.udea.build.udeaGates

/**
 * Registers `udeaVerifyDeterminism` and wires it into `check` (issue #150).
 *
 * Applied to the **root** project, not to each module, for the same reason
 * `dev.wildware.udea.docs-check` is: the question is about a *set* of source sets across several
 * modules, and a per-module answer to "does simulation read the wall clock" would be four
 * answers to a question that has one. It also keeps the gate off the build script of the
 * module it polices, which is the wrong place for the switch that turns it off.
 *
 * ## What it scans
 *
 * The engine's declared scopes when this build contains the engine, plus whatever the build
 * declares for itself through `udeaGates { simulation(...) }` (issue #265). `moba`'s rules come
 * through that block like any other game's, so the game in this repository and a game in its own
 * repository are scanned by one code path rather than by a table and an exception to it.
 *
 * ## What green means here
 *
 * Not "the simulation is deterministic". Spec section 7 is explicit that this is a cheap first
 * filter and that its green light will be trusted anyway, so the task prints
 * `DeterminismScan.NOT_THE_GATE` on every run - pass and fail - and `determinism-audit.md`
 * lists what it structurally cannot see. The real gate is `WorldHasher` snapshot equivalence
 * and the cross-OS `replay-equality` job in `ci.yml`.
 */

plugins {
    base
}

/** This build's declaration of itself: which projects are simulation, and which one ships. */
val gates = udeaGates()

/** Every project in this build, which is what decides whether the engine is part of it. */
val projectPaths: Set<String> = rootProject.allprojects.map { it.path }.toSet()

/**
 * The engine's own scopes, when the engine is in this build.
 *
 * Empty in a game's own build, where the engine is an included build that runs this same gate
 * over its own modules in its own `check`. [DeterminismRules.engineScopesIn] fails rather than
 * quietly shrinking when the engine is here and a declared module is not.
 */
val engineScopes: List<SimScope> = DeterminismRules.engineScopesIn(projectPaths)

/** The engine's scopes and the build's own, as the task and every derived input read them. */
val scannedScopes: Provider<List<SimScope>> = gates.simulationScopes.map { engineScopes + it }

/**
 * Where each scanned project is on disk, by Gradle path.
 *
 * Read off the projects rather than derived from the path, because a build is free to lay its
 * projects out however it likes and only it knows (issue #265).
 */
val scopeDirectories: Provider<Map<String, String>> = scannedScopes.map { declared ->
    declared.associate { it.project to project(it.project).projectDir.absolutePath }
}

/**
 * The compiled output the scan reads, narrowed to the declared source set of each scope.
 *
 * Narrow rather than the whole of `build/classes` on purpose: consuming another module's
 * `compileTestKotlin` output without depending on it is a Gradle error, and a determinism rule
 * about test code would be wrong anyway - a test is allowed to plant a clock read, and one of
 * this gate's own tests does exactly that.
 *
 * The directories come from [DeterminismLayout], the same place the task reads them from, so a
 * multiplatform module's `build/classes/kotlin/jvm/main` is both what is declared here and what is
 * scanned (issue #203).
 */
val simulationClassDirs = files(
    scannedScopes.map { declared ->
        declared.flatMap { scope ->
            DeterminismLayout.scopeInput(rootDir, scope, project(scope.project).projectDir).classRoots
                .map { root -> fileTree(root) { include("**/*.class") } }
        }
    },
)

/**
 * Versions the audit in `determinism-audit.md` was performed against.
 *
 * Read from the catalog rather than written down here, so bumping `gradle/libs.versions.toml`
 * is what makes the pin drift - which is the whole mechanism issue #151 asks for.
 *
 * Empty unless the engine is in this build: the audit, the allowlist that pins it and the
 * vendored Fleks source it describes are all the engine's, and a game's build has none of the
 * three to compare against (issue #265).
 */
val auditedVersions: Map<String, String> = if (engineScopes.isEmpty()) {
    emptyMap()
} else {
    UdeaVerifyDeterminismTask.PINNED_ALIASES.associateWith { alias ->
        udeaCatalog.findVersion(alias)
            .orElseThrow {
                IllegalStateException(
                    "No version '$alias' in gradle/libs.versions.toml, but " +
                        "determinism-allowlist.txt pins it. The audit cannot be stamped " +
                        "against a version the build does not resolve.",
                )
            }
            .requiredVersion
    }
}

val udeaVerifyDeterminism =
    tasks.register<UdeaVerifyDeterminismTask>(UdeaVerifyDeterminismTask.TASK_NAME) {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description =
            "Fails if declared simulation code reads the wall clock, draws unseeded randomness, " +
                "iterates a hash-ordered collection, or reaches the device. A first filter, " +
                "not the determinism gate."

        repoRootPath.set(rootDir.absolutePath)
        // Set only when there is one. A build with no allowlist excuses nothing, which is the
        // strict end of the range; the task says on every run which of the two it had.
        layout.projectDirectory.file(UdeaVerifyDeterminismTask.ALLOWLIST_FILE)
            .takeIf { it.asFile.isFile }
            ?.let { allowlistFile.set(it) }
        scopes.set(scannedScopes)
        moduleDirectories.set(scopeDirectories)
        simulationClasses.from(simulationClassDirs)
        resolvedVersions.set(auditedVersions)
        vendoredFleksSources.from(fileTree(layout.projectDirectory.dir(VendoredFleks.SOURCE_DIRECTORY)))
        report.set(layout.buildDirectory.file("reports/udea/determinism.txt"))

        // The bytecode has to exist before it can be scanned, and a gate that reads whatever
        // stale `build/classes` happened to be lying around is the defect `udeaVerifyHeadless`
        // had before `HEADLESS_PROJECTS` was derived rather than written twice.
        // `udeaMainBytecode` rather than `classes`, which a multiplatform module does not have.
        dependsOn(
            scannedScopes.map { declared ->
                declared.map { "${it.project}:${ModuleGraphRules.MAIN_BYTECODE_TASK}" }.distinct()
            },
        )
    }

tasks.named("check") {
    dependsOn(udeaVerifyDeterminism)
}
