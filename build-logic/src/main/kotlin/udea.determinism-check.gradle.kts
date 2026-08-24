import dev.wildware.udea.build.determinism.DeterminismRules
import dev.wildware.udea.build.determinism.UdeaVerifyDeterminismTask
import dev.wildware.udea.build.udeaCatalog

/**
 * Registers `udeaVerifyDeterminism` and wires it into `check` (issue #150).
 *
 * Applied to the **root** project, not to each module, for the same reason
 * `udea.migration-check` is: the question is about a *set* of source sets across several
 * modules, and a per-module answer to "does simulation read the wall clock" would be four
 * answers to a question that has one. It also keeps the gate off the build script of the
 * module it polices, which is the wrong place for the switch that turns it off.
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

/** Module directories holding a declared simulation scope, derived from the one table. */
val simulationModules: List<String> =
    DeterminismRules.SIMULATION_SCOPES.map { it.project }.distinct().sorted()

/**
 * The compiled output the scan reads, narrowed to the declared source set of each scope.
 *
 * Narrow rather than the whole of `build/classes` on purpose: consuming another module's
 * `compileTestKotlin` output without depending on it is a Gradle error, and a determinism rule
 * about test code would be wrong anyway - a test is allowed to plant a clock read, and one of
 * this gate's own tests does exactly that.
 */
val simulationClassDirs = files(
    DeterminismRules.SIMULATION_SCOPES.flatMap { scope ->
        val module = scope.project.removePrefix(":").replace(':', '/')
        UdeaVerifyDeterminismTask.LANGUAGES.map { language ->
            fileTree(rootDir.resolve("$module/build/classes/$language/${scope.sourceSet}")) {
                include("**/*.class")
            }
        }
    },
)

/**
 * Versions the audit in `determinism-audit.md` was performed against.
 *
 * Read from the catalog rather than written down here, so bumping `gradle/libs.versions.toml`
 * is what makes the pin drift - which is the whole mechanism issue #151 asks for.
 */
val auditedVersions: Map<String, String> = UdeaVerifyDeterminismTask.PINNED_ALIASES
    .associateWith { alias ->
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

val udeaVerifyDeterminism =
    tasks.register<UdeaVerifyDeterminismTask>(UdeaVerifyDeterminismTask.TASK_NAME) {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description =
            "Fails if declared simulation code reads the wall clock, draws unseeded randomness, " +
                "iterates a hash-ordered collection, or reaches the device. A first filter, " +
                "not the determinism gate."

        repoRootPath.set(rootDir.absolutePath)
        allowlistFile.set(
            layout.projectDirectory.file(UdeaVerifyDeterminismTask.ALLOWLIST_FILE),
        )
        simulationClasses.from(simulationClassDirs)
        resolvedVersions.set(auditedVersions)
        report.set(layout.buildDirectory.file("reports/udea/determinism.txt"))

        // The bytecode has to exist before it can be scanned, and a gate that reads whatever
        // stale `build/classes` happened to be lying around is the defect `udeaVerifyHeadless`
        // had before `HEADLESS_PROJECTS` was derived rather than written twice.
        dependsOn(simulationModules.map { "$it:classes" })
    }

tasks.named("check") {
    dependsOn(udeaVerifyDeterminism)
}
