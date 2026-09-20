import dev.wildware.udea.build.UdeaVerifyAgentsMdTask
import dev.wildware.udea.build.UdeaVerifyTrelloMapTask
import dev.wildware.udea.build.UdeaVerifyWikiTask
import dev.wildware.udea.build.WikiCheck

/**
 * Registers `udeaVerifyAgentsMd`, `udeaVerifyTrelloMap` and `udeaVerifyWiki`, and wires them into
 * `check`. `udeaVerifyWiki` holds `docs/wiki/` to the tree the same way: every page, path and
 * project a wiki page names has to exist.
 *
 * Applied to the **root** project, not to each module: both gates ask whether a document still
 * describes the tree as a whole - `AGENTS.md` against `settings.gradle.kts`, and the Trello map
 * against the spec - and a per-module answer to either would be meaningless.
 *
 * On `check` rather than a task somebody remembers to run, because bookkeeping that spans two
 * files decays unless something goes red when they drift.
 *
 * Until issue #213 this was `udea.migration-check`, and it also registered `udeaLegacyReport`
 * and `udeaVerifyMigration`, which policed the old tree. The old tree is gone and so are they.
 */

plugins {
    base
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

val udeaVerifyWiki by tasks.registering(UdeaVerifyWikiTask::class) {
    group = "verification"
    description = "Fails if a docs/wiki page links to a page, repository path or Gradle project that does not exist."
    repoRoot.set(layout.projectDirectory)
    wikiDirectory.set(layout.projectDirectory.dir(WikiCheck.WIKI_DIRECTORY))
    settingsScript.set(layout.projectDirectory.file("settings.gradle.kts"))
    report.set(layout.buildDirectory.file("reports/udea/wiki.txt"))
}

tasks.named("check") {
    dependsOn(udeaVerifyAgentsMd, udeaVerifyTrelloMap, udeaVerifyWiki)
}
