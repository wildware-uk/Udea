# BRIEF-265 — a game that builds and runs outside the Udea repository

SHA: `65029e7`

Branch `issue-265-outside-game`, off `origin/master` at `a45636c`.

History: `06843c6` the work, `b8b0ac9` this brief, `0b1d0df` its SHA line — that is the tree
review-265-r1 was handed. §0 is the round-2 change on top of it, and the SHA above is the tree
everything in this brief was last measured on.

---

## 0. Round 2 — a game builds against a pinned snapshot

Owner follow-up: *"Use the same setup as composegl, we build against a snapshot release and only
update the version when I say."* The publishing half already matched — `release.yml` has
ComposeGL's `kind` choice with `snapshot`. What was missing was the consumer half: the template
resolved the engine from `mavenLocal()` alone, which only works on a machine that has published
it.

Four edits, no production code touched:

- **`templates/new-game/settings.gradle.kts`** declares Central's snapshot repository and the two
  `oss.sonatype.org` fallbacks, in both `pluginManagement` and `dependencyResolutionManagement`,
  copied from this repository's own root `build.gradle.kts`. `mavenLocal()` stays first in both,
  so an engine published locally still wins — which is what the proof script relies on.
- **`templates/new-game/gradle.properties`** keeps `udeaVersion=0.1.0-SNAPSHOT` and now says why
  it is pinned: the number changes when the owner says a new snapshot is the one to build
  against, not when the engine moves.
- **`docs/new-game.md`** gains "Where the engine comes from, and which one" (the repository order,
  as a table) and "Getting a newer engine" (the owner runs Release with `kind = snapshot`; the
  game bumps `udeaVersion` in a commit of its own), plus the 24-hour snapshot cache and
  `--refresh-dependencies` as the escape.
- **`templates/new-game/README.md`** and the `AGENTS.md` bullet say the same in one line each.

Nothing was published. The proof script still publishes to `mavenLocal()` only.

Re-run on this tree:

```
$ sh scripts/outside-game-proof.sh
=== PROOF GREEN
a game outside this repository resolved the engine from a repository, built, ran, and
inherited gates that fail when broken.

$ sh gradlew build --continue --console=plain
BUILD SUCCESSFUL in 13s
994 actionable tasks: 19 executed, 975 up-to-date
```

19 tasks executed because this round changes a template, two documents and a properties file —
the from-clean figures in §3 are the ones that measure the engine.

**What this round does not prove:** that Central's snapshot repository actually serves
`dev.wildware.udea:udea-core:0.1.0-SNAPSHOT`, because nothing has been published to it. The
repository is declared and ordered correctly; the first `kind = snapshot` release is what will
exercise it. The proof resolves from `mavenLocal()`, which is first in the list by design.

---

## 1. The evidence command

```
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh scripts/outside-game-proof.sh
```

It publishes this engine to the local Maven repository, copies `templates/new-game` to
`/tmp/udea-outside-game-proof/my-game` — outside this repository, with no path back into it —
builds it, runs it, reads the agent port range out of the `gamebridge.json` it generated, and then
breaks the game twice and requires the gates it inherited to fail with `DET001` and `UDEA-MG-005`.
Transcripts land in `build/reports/udea/outside-game/`.

Last run on the committed tree:

```
=== publish: ./gradlew publishToMavenLocal -PudeaVersion=0.1.0-SNAPSHOT
  green  -> .../build/reports/udea/outside-game/publish.log

=== publish-build-logic: ./gradlew -p build-logic publishToMavenLocal -PudeaVersion=0.1.0-SNAPSHOT
  green  -> .../build/reports/udea/outside-game/publish-build-logic.log
  published 72 artifacts at 0.1.0-SNAPSHOT -> .../published-artifacts.txt

=== build: ./gradlew build
  green  -> .../build/reports/udea/outside-game/build.log

=== run: ./gradlew run
  green  -> .../build/reports/udea/outside-game/run.log

=== bridge: gamebridge.json
{
  "name": "new-game",
  "launch": {
    "command": "./gradlew :game:run -PdebugPort={port} --console=plain",
    "cwd": ".",
    "portRange": "7860-7879",
    "readyTimeoutMs": 180000,
    "env": {}
  }
}

=== det-red: ./gradlew udeaVerifyDeterminism
  red    -> .../build/reports/udea/outside-game/det-red.log

=== graph-red: ./gradlew :game:udeaVerifyModuleGraph
  red    -> .../build/reports/udea/outside-game/graph-red.log

=== PROOF GREEN
a game outside this repository resolved the engine from a repository, built, ran, and
inherited gates that fail when broken.
```

(The long paths are elided to `...` at the same point on each line; the full text is
`scratchpad/issue265/proof-committed.log` on this box, and the run reproduces it.)

### It goes red when the feature is reverted

Three mutations, each applied to the committed tree, run through the evidence command above, then
restored. Every diff below is the literal `git diff` of that mutation, taken by the script that
applied it (`scratchpad/issue265/mutate.sh`), and every quoted failure is from that run's own
archived transcript.

#### M1 — the engine's modules are not put on the publishing plugin

```diff
diff --git a/build.gradle.kts b/build.gradle.kts
index 7533a9b..67aad39 100644
--- a/build.gradle.kts
+++ b/build.gradle.kts
@@ -193,7 +193,7 @@ val KSP_OUTPUT_CONSUMERS: List<(String) -> Boolean> = listOf(
 )
 
 configure(subprojects.filter { it.path in published }) {
-    apply(plugin = "com.vanniktech.maven.publish")
+    // apply(plugin = "com.vanniktech.maven.publish")
 
     /**
      * What Central insists on: a name, a description, a home, a licence, a human, and where the
```

`m1 EXIT=1`, red at the first leg — `PROOF FAILED: the engine did not publish`. The engine's own
build stops before it gets there:

```
* What went wrong:
A problem occurred configuring project ':udea-agent'.
> Extension of type 'MavenPublishBaseExtension' does not exist.
```

#### M2 — the compiler-plugin gate goes back to demanding a project component

```diff
diff --git a/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaCompilerPluginWiring.kt b/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaCompilerPluginWiring.kt
index 303439e..f0ac76b 100644
--- a/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaCompilerPluginWiring.kt
+++ b/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaCompilerPluginWiring.kt
@@ -262,7 +262,7 @@ public object UdeaCompilerPluginWiring {
                     "returned false for a project UdeaCompilerPluginWiring.appliesTo accepts, " +
                     "so no -Xplugin argument is produced and the FIR checkers are silently off."
 
-            expected && buildCompilesPlugin && fromAnyProject.isEmpty() ->
+            expected && fromAnyProject.isEmpty() ->
                 "$projectPath resolves $ARTIFACT_NAME from $present instead of from " +
                     "'$fromProject' or the same project in an included build. The dependency " +
                     "substitution in UdeaCompilerPluginSupport.apply is not in effect, so the " +
```

`m2 EXIT=1`, red at the build leg. The engine still publishes; the outside game refuses to build:

```
* What went wrong:
Execution failed for task ':game:udeaVerifyCompilerPlugin'.
> :game resolves udea-compiler-plugin from [dev.wildware.udea:udea-compiler-plugin:0.1.0-SNAPSHOT] instead of from 'project :udea-compiler-plugin' or the same project in an included build.
```

#### M3 — `UDEA-MG-005` goes back to naming this repository's own game projects

```diff
diff --git a/build-logic/src/main/kotlin/dev/wildware/udea/build/ModuleGraphRules.kt b/build-logic/src/main/kotlin/dev/wildware/udea/build/ModuleGraphRules.kt
index d7822c6..580b1f5 100644
--- a/build-logic/src/main/kotlin/dev/wildware/udea/build/ModuleGraphRules.kt
+++ b/build-logic/src/main/kotlin/dev/wildware/udea/build/ModuleGraphRules.kt
@@ -294,7 +294,7 @@ public object ModuleGraphRules {
             "reflection-on-hot-paths smell the rewrite exists to kill. Asset scripts are compiled " +
             "at build time; discovery is a generated registry, not classpath scanning.",
         specSection = "6 (Phase 2 exit), 3.6",
-        scope = ProjectScope.GAME,
+        projects = setOf(":moba:game", ":moba:desktop", ":moba:android"),
         configurations = setOf("runtimeClasspath"),
         banned = listOf(
             CoordinatePattern("org.jetbrains.kotlin:kotlin-scripting-*"),
```

`m3 EXIT=1`, and this is the interesting one: it fails because the **gate stops failing**. The
outside game builds, runs, and then accepts a Kotlin scripting host on the classpath it ships —

```
> Task :game:udeaVerifyModuleGraph

BUILD SUCCESSFUL in 3s
1 actionable task: 1 executed
```

— and the proof catches the silence: `PROOF FAILED: udeaVerifyModuleGraph passed a Kotlin
scripting host on the outside game's runtime classpath`. That is the defect the issue is about:
an ungoverned project is not reported as skipped, so before this branch a game outside this
repository shipped whatever it liked with a green build.

An earlier attempt at M3 was not a valid mutation and is recorded here rather than quietly
dropped: it replaced `scope = ProjectScope.GAME,` with `projects = listOf(...)`, which does not
compile (`Type mismatch: inferred type is List<String> but Set<String> was expected`), so the
proof went red for the wrong reason. `setOf` is the real shape and is what the table above uses.

---

## 2. Summary

### What the change does

**The engine publishes.** Every `udea-*` module goes to Maven Central as
`dev.wildware.udea:<module>` through `com.vanniktech.maven.publish`, configured exactly as
ComposeGL's root build configures it: `publishToMavenCentral(automaticRelease = false)` so a
deployment is uploaded and left for a person to press publish on, `signAllPublications()` only
when `signingInMemoryKey` is present, `coordinates(...)`, a POM block, and an error when a
published module has no `description`. `build-logic` publishes alongside it — the convention
plugins a game applies, and a version catalog — so an outside game can apply `dev.wildware.udea.game-gates`
at all.

**Nothing has been published from this branch, and nothing can be by accident.** The deliverable
is `publishToMavenLocal` working on this box and a CI path that stops short of Central:
`.github/workflows/release.yml` defaults to `rehearse`, and the permanent step is a second
workflow, `central-publish.yml`, run by hand with a deployment id and a typed confirmation. No
push, tag or merge publishes anything.

**The gates are configuration, not a list of `:moba:*` paths.** `dev.wildware.udea.game-gates` is the single
plugin a Udea build applies to get the module-graph, determinism, editor-absent and release
checks. This repository's root build script and a game's own root build script apply the same
plugin and write the same `udeaGates { }` block:

```kotlin
udeaGates {
    ships(":moba:desktop")
    simulation(project = ":moba:game", packagePrefixes = listOf(...), why = "...")
}
```

`UDEA-MG-005` and `UDEA-MG-013` are scoped by *role* (`ProjectScope.GAME` / unscoped with an
allow-list) rather than by path, so a game's `:game` and `:desktop` are governed wherever they
live. `DeterminismRules.SIMULATION_SCOPES` no longer names `:moba:game` at all: the engine's own
scopes are selected by `engineScopesIn(projectPaths)` — all of them or none, never a subset — and
a game declares its own through `simulation(...)`, which refuses a scope with no argued reason, a
scope naming a project the build does not contain, and a scope that compiled nothing.

**A per-game agent port range.** `udeaAgent { portRange.set("7860-7879") }` in the template, read
back out of the generated `gamebridge.json` by leg 3 of the proof.

**`docs/new-game.md`** replaces `docs/getting_started.md`, which was deleted: it described
`UdeaGameManager`, a LibGDX `ApplicationListener` and a `script/assets` layout, none of which have
existed since #213.

### Decisions I had to make, and what to change if they are wrong

Each of these is also an issue comment on #265, so it is reviewable after this branch.

1. **The published POM says MIT, not ComposeGL's Apache-2.0.** The instruction was "licence and
   developer as ComposeGL's". The developer block is ComposeGL's. The licence is not, because
   `LICENSE` in the root of this repository is `MIT License, Copyright (c) 2025-2026 Shaun Wild`,
   and a POM claiming Apache-2.0 over an MIT tree is a false statement in metadata that cannot be
   edited once published. It is also the answer `udea-fleks` needs: vendored Fleks is MIT. *To
   change:* `UdeaPom.LICENCE_NAME`/`LICENCE_URL` and the two literals in
   `build-logic/build.gradle.kts` that `UdeaPomTest` holds against them.

2. **`build-logic` publishes with Gradle's own `maven-publish`, not vanniktech.** Not a
   preference — applying it fails at configuration time:

   ```
   > Failed to apply plugin class 'com.vanniktech.maven.publish.MavenPublishBasePlugin'.
      > Make sure the Kotlin version 2.2.0 or newer is applied.Otherwise, detected Kotlin plugin org.jetbrains.kotlin.jvm but was not able to access Kotlin plugin classes.
   ```

   `kotlin-dsl` is what compiles a precompiled script plugin and it applies the Kotlin plugin the
   *Gradle distribution* embeds (2.0.21 for Gradle 8.13) from Gradle's own classloader, so no
   version bump fixes it and no project holding a `udea.*.gradle.kts` can avoid it. `build-logic`
   posts to the same two Central endpoints by URL instead — snapshots to
   `central.sonatype.com/repository/maven-snapshots/`, releases to the Portal's staging API, which
   stages and waits. *To change:* `build-logic/build.gradle.kts`; the alternative is porting four
   convention *scripts* to `Plugin<Project>` classes, which is a rewrite rather than this ticket.

3. **Every `udea-*` module is published; no `moba:*` project is.** `udea-agent-host` and
   `udea-editor` are debug-only and are published anyway, because they are the tools a game is
   *made* with — "debug-only" means they must not reach a release *classpath*, which is
   `UDEA-MG-012` and `UDEA-REL-002`'s job on the game's own build, and those gates now travel with
   the game. The set is a written-out list with a `check` beside it that every `:udea-*` project
   is in it and every entry is a real project, so a module added or renamed fails the build rather
   than dropping out of the release in silence.

4. **The Kotlin convention no longer sets `group` or `version`.** It set
   `dev.wildware.udea` / `1.0-SNAPSHOT`, which was harmless while it only reached this
   repository's modules and stops being harmless the moment it is published: a game applying
   `dev.wildware.udea.kotlin-library` would be given the engine's coordinates by a plugin it merely applied.
   The root build script sets both, for this build's projects only. The version rule is
   `UdeaVersion.resolve`, ComposeGL's rule with `-PudeaVersion` in place of `-PcomposeglVersion`,
   so an ordinary build here is now `0.1.0-SNAPSHOT` rather than `1.0-SNAPSHOT`. Nothing in the
   tree referenced the old string except the two declarations themselves.

5. **A new published artifact: `dev.wildware.udea:udea-version-catalog`.** It is a copy of
   `gradle/libs.versions.toml`, published from a subproject of `build-logic` rather than from a
   project of the outer build — a project there would have to be a module of the engine in
   `settings.gradle.kts` and in `AGENTS.md`'s table. A game needs the engine's Kotlin and KSP
   versions or it does not compile; it carries no `udea-*` alias, deliberately, because which
   engine release a game is on is that game's decision.

### Two things I found while driving it, one of which was mine

- **`/health` reported `paused: false` while the game was plainly frozen.** That was a hole in
  *my template*, not the engine: `AgentHostConfig.paused` is a lambda the launcher supplies and
  the template did not supply one, so it was the default `false` for ever. `moba` passes
  `{ host.time.paused }`; the template does now, and the second transcript in §5 shows it
  answering `true`. I nearly wrote this up as an engine defect.
- **`GET /command?cmd=<anything>` answers `{"accepted":true}`.** I started to write that up as a
  defect too, and it is not one: `accepted` means queued, and the *result* is in `/state`:
  `{"id":5,"ok":false,"error":{"kind":"no_such_tool","message":"no tool named
  no.such.tool.at.all is registered; call the tools listing to see what is"}}`. The contract was
  working and my reading of it was backwards.

### What I did not exercise

- **Nothing was uploaded to Maven Central, so nothing proves Central *accepts* these artifacts.**
  What is proved is that they are produced, that they carry a name, description, licence,
  developer and scm, and that a real consumer resolves and compiles against them. The first
  release will be the first time the upload path runs.
- **Signing.** There is no GPG key on this box, so `signAllPublications()` and the `signing`
  block in `build-logic` are never entered here. The release workflow fails loudly if the key is
  absent (`nothing was signed; check SIGNING_KEY`).
- **iOS.** Cannot build on this Linux box and was not tested here. The KMP publications for
  `iosArm64`/`iosSimulatorArm64` do appear in the local repository for the modules that have those
  targets, which is a statement about what the publication *declares*, not about artifacts built
  on a Mac.
- **The asset pipeline and drawing, from outside.** The template is headless and has no assets.
  `docs/new-game.md` says so under "What the template does not cover yet", along with the
  `net-components.lock` id space, which is the real blocker for a game with replicated components
  and is a follow-up for the lead to place rather than something I invented here.

---

## 3. `sh gradlew build`

Run from clean, on the committed tree:

```
$ sh gradlew clean --console=plain
BUILD SUCCESSFUL in 16s
88 actionable tasks: 69 executed, 19 up-to-date

$ ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
    sh gradlew build --continue --console=plain
BUILD SUCCESSFUL in 27s
985 actionable tasks: 609 executed, 293 from cache, 83 up-to-date
```

609 tasks executed and 293 served from the build cache — the cache is what makes 27 seconds
possible after a `clean`, and it is why the number of tasks that *ran* is the honest figure rather
than the wall clock.

**One earlier run of this command failed, and the failure is part of the change.** Publishing is
the first thing in this build ever to ask for a sources jar or an Android release variant, and
that exposed KSP outputs being read by tasks with no dependency on the KSP task:

```
* What went wrong:
Some problems were found with the configuration of task ':udea-core:kspCommonMainKotlinMetadata' (type 'KspAATask').
  - Gradle detected a problem with the following location: '.../udea-core/build/generated/ksp/metadata/commonMain/kotlin'.
    Reason: Task ':udea-core:sourcesJar' uses this output of task ':udea-core:kspCommonMainKotlinMetadata' without declaring an explicit or implicit dependency.
```

`KSP_OUTPUT_CONSUMERS` in the root build script supplies those edges, for the sources jars and for
the Android variant's `bundle*` and `process*JavaRes` tasks. Two details are written down there
because both cost a build to find: the edge must exclude *test* KSP tasks or
`bundleAndroidMainClassesToCompileJar -> kspAndroidHostTest -> bundleAndroidMainClassesToCompileJar`
is a cycle; and the obvious `tasks.withType<Jar>()` filter silently matches nothing, because a
multiplatform `sourcesJar` is registered as `org.gradle.jvm.tasks.Jar` and `Jar` in a build script
is `org.gradle.api.tasks.bundling.Jar`, which *extends* it.

### GL

This ticket touches no GL code — the only edit inside `udea-render` is the one-line `description`
Central requires — but the run was done rather than argued:

```
$ xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
    sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest
> Task :udea-editor:udeaEditorGlTest

BUILD SUCCESSFUL in 1m 43s
126 actionable tasks: 12 executed, 114 up-to-date
```

### `build-logic`'s own tests

`sh gradlew build` does not run them — it is a separate build — so they are run directly:

```
$ sh gradlew -p build-logic check --console=plain
BUILD SUCCESSFUL in 1m 11s
13 actionable tasks: 4 executed, 9 up-to-date
```

345 tests across 41 classes, 0 failures, counted out of
`build-logic/build/test-results/test/*.xml` rather than off the console.

---

## 4. Images

**There are none, and that is not an omission I can fix with a screenshot.** This ticket is a
build and packaging change: what it produces is a Maven repository, a Gradle plugin marker and a
gate that fails. The outside game it proves itself with is `Headless` by design — it has no
renderer, so `render.screenshot` correctly answers `no_render_context`, and a picture of it would
be a picture of nothing.

The artefacts a reviewer should look at instead are transcripts, and every one of them is on this
box under `build/reports/udea/outside-game/` after a run of the evidence command:
`publish.log`, `publish-build-logic.log`, `published-artifacts.txt`, `build.log`, `run.log`,
`gamebridge.json`, `det-red.log`, `graph-red.log`, `no-path.txt`.

---

## 5. Driven for real

The outside game was started as an agent instance and driven over its own MCP surface, from
`/tmp/udea-outside-game-proof/my-game`, on the port range it declares:

```
$ sh ./gradlew :game:run -PdebugPort=7861 --console=plain
new-game serving the agent surface on 7861
```

Its `/tools` lists twelve tools in three toolsets — `events` (3), `game` (1, `close`) and `time`
(8) — which is what this game wires. `world.*` is deliberately absent: it needs an
`AgentComponentIndex` over `@Replicated` components and this game declares none.

```
--- health before
{"ok":true,"frame":548,"tick":549,"paused":false,...}

--- time.pause
{"accepted":true,"commandId":1,"frame":549}

--- health, paused
{"ok":true,"frame":668,"tick":550,"paused":true,...}

--- health, still paused three seconds later
{"ok":true,"frame":848,"tick":550,"paused":true,...}

--- time.step ticks=10
{"accepted":true,"commandId":2,"frame":848}

--- health after the step
{"ok":true,"frame":908,"tick":560,"paused":true,...}

--- time.resume
{"accepted":true,"commandId":4,"frame":909}

--- health, running again
{"ok":true,"frame":1029,"tick":681,"paused":false,...}
```

Read the ticks, not the frames: `549 → 550` and then **still 550** three seconds later while the
frame counter climbed from 668 to 848, so the simulation really was stopped rather than merely
slow; `time.step ticks=10` moved it 550 → 560, exactly ten; `time.resume` set it running again.
The `renderMode`, `role`, `sessionId` and `editor` fields are elided at the same point on each
line, marked `...`; the full lines are in `scratchpad/issue265/drive.txt` on this box.

The instance was stopped through its own `close` tool, not killed:
`{"accepted":true,"commandId":8,"frame":4186}`, after which `/health` does not answer and
`pgrep -af "debugPort=7861"` returns nothing.

---

## 6. The issue, criterion by criterion

| Criterion | What proves it |
|---|---|
| **A new repo with a desktop game builds against Udea without editing the Udea repository** | Evidence command, legs 0-1. The game is copied to `/tmp/udea-outside-game-proof/my-game`, resolves the engine from the local Maven repository, and leg 0 fails the whole proof if any file under it names `udea.path` or `includeBuild` (`no-path.txt`). `build.log` is green. |
| **...and runs** | Leg 2: `new-game ran 600 ticks; rovers at [10.000019, 20.000038, 29.999828]`. And §5: a live agent instance driven over its own tool surface. |
| **That game gets the same determinism check as moba** | Leg 4. A wall-clock read planted in the game's own simulation package: `game/src/main/kotlin/com/example/newgame/sim/RoverSystem.kt:35:1: error: [DET001] com.example.newgame.sim.RoverSystem.onTick is declared simulation (:game) and reads the wall clock: it references java.lang.System.currentTimeMillis.` M3 in §1 shows the same gate going *green* when the scoping is reverted. |
| **...and the same module-graph check** | Leg 5. A Kotlin scripting host added to what the game ships: `udeaVerifyModuleGraph: 2 violations / UDEA-MG-005 :game runtimeClasspath -> org.jetbrains.kotlin:kotlin-scripting-common` (and `-jvm`), with the resolution path printed. The green `build.log` also lists `Task :game:udeaVerifyModuleGraph`, `:game:udeaVerifyKotlinPin`, `:game:udeaVerifyCompilerPlugin`, `:game:udeaVerifyEditorAbsent`, `:game:udeaVerifyRelease` and, at the root of that build, `Task :udeaVerifyDeterminism` and `Task :udeaVerifyModuleGraph` — the proof fails if the module-graph, determinism, Kotlin-pin or compiler-plugin task is absent from the transcript, because a gate that did not run cannot fail. |
| **moba keeps exactly the checks it has today** | `build/reports/udea/determinism.txt` after `sh gradlew build`: the same five scopes, `:moba:game` among them, `706 class files`, `findings: 0`. `sh gradlew -p build-logic check` covers the rules themselves: `ModuleGraphRulesTest` still requires every rule to govern at least one project of this repository, now asked through `appliesTo`. |
| **A way to pick an agent port range per game** | Leg 3, from the file the plugin generated: `"portRange": "7860-7879"`. The proof fails if that string is not in it. `docs/new-game.md` says why it matters — a bridge lists whatever answers in its range, so two games sharing one range can stop each other's instances. |
| **`docs/new-game.md` matching the current engine, replacing `docs/getting_started.md`** | `docs/new-game.md` is new; `docs/getting_started.md` is deleted (`git rm`), and `docs/home.md`'s index points at the new page. The guide's quick-start is the same sequence `scripts/outside-game-proof.sh` executes, so it is checked rather than described. |
| **Publish to Maven Central, copying ComposeGL's setup** (owner override) | `build.gradle.kts`: the `published` set, `com.vanniktech.maven.publish`, `publishToMavenCentral(automaticRelease = false)`, `signAllPublications()` only when `signingInMemoryKey` is present, `coordinates(...)`, the POM block, and `error("$moduleName has no description, and Central requires one")`. `UdeaVersion.resolve` is ComposeGL's version rule with `-PudeaVersion`. `.github/workflows/release.yml` and `central-publish.yml` are the same two-step shape. `gradle/libs.versions.toml` gains the `mavenPublish` alias at the same 0.37.0. |
| **Nothing is published from this ticket** | No credential exists on this box and no command in this branch's history posts to Central. The two workflows are `workflow_dispatch` only (plus a `v*` tag for `release.yml`), `release.yml` defaults to `rehearse`, and the permanent publish is a separate workflow requiring a deployment id and a typed version. The `outside-game` CI job stops at the runner's own `~/.m2`. |

### The tests written first

| Test | What it pins |
|---|---|
| `UdeaVersionTest` | every branch of the version rule, and that `build-logic/build.gradle.kts` carries the same default it cannot call |
| `UdeaPomTest` | `build-logic`'s hand-written POM says the same things `UdeaPom` does — with a control that a value present in only one of them is reported |
| `UdeaCompilerPluginWiringTest` (4 new) | a build that does not compile the plugin may resolve it from a repository; one that does still may not; neither may have no plugin at all; and the coordinate's version is the sentinel only where it is substituted |
| `SimulationScopesTest` | an engine build scans all engine scopes, a game's build scans none, a missing engine module fails, and a scope needs an argued reason and a Gradle path |
| `GameGatesTest` | the plugin puts the module-graph gate on every project; `UDEA-MG-005` fails a scripting host on `:game` through `check`; a scope that compiled nothing fails; a scope naming an absent project is refused |
| `ModuleGraphRulesTest`, `DependencyRulesTest` (new cases) | `UDEA-MG-005` and `UDEA-MG-013` govern an outside game's projects, and `ProjectScope` covers what it says it covers |

Each was watched failing before the code existed. `UdeaVersionTest` was run against a tree with no
`UdeaVersion` in it and would not compile — `Unresolved reference: UdeaVersion`, repeated for each
reference, and `BUILD FAILED in 35s` on `:compileTestKotlin`. The wiring tests failed on the
missing `buildCompilesPlugin` parameter. The three mutations in §1 are the same discipline applied
to the whole feature rather than to one assertion.

---

## 7. Regenerated files

**Neither `udea-codegen/net-protocol.lock` nor
`udea-codegen/src/test/resources/expected-generated-hashes.txt` moved.** This branch adds no
replicated component and changes no emitter, so no id shifted; `git status` on the committed tree
shows neither file, and `udeaCheckProtocolLock` is green inside the `build` above.

Two files are *new* and are the closest thing to a generated artifact here:
`build-logic/version-catalog/build.gradle.kts`, which republishes `gradle/libs.versions.toml` as
`dev.wildware.udea:udea-version-catalog`, and the `published` set in the root build script, which
is checked against the build's own project list rather than trusted.
