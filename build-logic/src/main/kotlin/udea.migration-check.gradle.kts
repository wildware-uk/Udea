import dev.wildware.udea.build.UdeaLegacyReportTask
import dev.wildware.udea.build.UdeaVerifyAgentsMdTask
import dev.wildware.udea.build.UdeaVerifyTrelloMapTask
import dev.wildware.udea.build.UdeaVerifyMigrationTask

/**
 * Registers `udeaLegacyReport` and `udeaVerifyMigration`, and wires both into `check`.
 *
 * Applied to the **root** project, not to each module: both gates ask questions about the tree
 * as a whole ("is every old file accounted for", "did anything in the new tree come out of the
 * old one"), and a per-module answer to either would be meaningless.
 *
 * On `check` rather than a task somebody remembers to run: `docs/migration/ledger.md` is the
 * artefact the Phase 6 exit closes out, and a ledger only verified when somebody thinks to
 * verify it is a ledger that is wrong by Phase 2.
 */

plugins {
    base
}

/** Old-tree modules. Deleted as replaced; see `docs/migration/ledger.md` for the order. */
val legacyModuleDirectories: List<java.io.File> =
    listOf("common", "example", "gradle-plugin").map { layout.projectDirectory.dir(it).asFile }

/**
 * The rewrite tree, taken from the project model rather than from a directory listing, so that
 * adding a module to `settings.gradle.kts` is the only step needed to bring it under the gate.
 */
val rewriteModuleDirectories: List<java.io.File> = subprojects
    .filter { it.path.startsWith(":udea-") || it.path == ":moba" }
    .sortedBy { it.path }
    .map { it.projectDir }

/**
 * Hand-written Kotlin sources under [directory].
 *
 * Excluding build output matters: KSP and the Kotlin compiler both write Kotlin files under a
 * `build` directory, and a generated file has no business in a ledger of hand-written ones -
 * nor is it evidence of anyone having copied anything.
 */
fun kotlinSourcesIn(directory: java.io.File) = fileTree(directory) {
    include("**/*.kt")
    exclude("**/build/**", "**/.gradle/**")
}

val legacyKotlin = files(legacyModuleDirectories.map { kotlinSourcesIn(it) })
val rewriteKotlin = files(rewriteModuleDirectories.map { kotlinSourcesIn(it) })
val ledgerFile = layout.projectDirectory.file("docs/migration/ledger.md")

val udeaLegacyReport by tasks.registering(UdeaLegacyReportTask::class) {
    group = "verification"
    description = "Fails if an old-tree Kotlin file has no row in docs/migration/ledger.md."
    ledger.set(ledgerFile)
    repoRoot.set(layout.projectDirectory)
    legacySources.from(legacyKotlin)
    retiredModules.set(listOf("compose-ui", "idea-plugin", "level-editor"))
    report.set(layout.buildDirectory.file("reports/udea/legacy-ledger.txt"))
}

val udeaVerifyMigration by tasks.registering(UdeaVerifyMigrationTask::class) {
    group = "verification"
    description = "Fails if a udea-* or moba source is an unreviewed or stale copy of an old-tree file."
    ledger.set(ledgerFile)
    repoRoot.set(layout.projectDirectory)
    legacySources.from(legacyKotlin)
    rewriteSources.from(rewriteKotlin)
    report.set(layout.buildDirectory.file("reports/udea/migration-copies.txt"))
}

val udeaVerifyAgentsMd by tasks.registering(UdeaVerifyAgentsMdTask::class) {
    group = "verification"
    description = "Fails if the AGENTS.md module table stops matching settings.gradle.kts."
    agentsMd.set(layout.projectDirectory.file("AGENTS.md"))
    settingsScript.set(layout.projectDirectory.file("settings.gradle.kts"))
    report.set(layout.buildDirectory.file("reports/udea/agents-md.txt"))
}

val udeaVerifyTrelloMap by tasks.registering(UdeaVerifyTrelloMapTask::class) {
    group = "verification"
    description = "Fails if spec section 9 names a Trello card docs/migration/trello-map.md omits."
    spec.set(layout.projectDirectory.file("docs/superpowers/specs/2026-08-22-udea-ai-native-rewrite-design.md"))
    map.set(layout.projectDirectory.file("docs/migration/trello-map.md"))
    report.set(layout.buildDirectory.file("reports/udea/trello-map.txt"))
}

tasks.named("check") {
    dependsOn(udeaLegacyReport, udeaVerifyMigration, udeaVerifyAgentsMd, udeaVerifyTrelloMap)
}
