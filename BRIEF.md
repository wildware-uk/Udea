# BRIEF: plugin markers publish inside the verified namespace (#265 follow-up)

    f77dd4b

SHA `f77dd4b` — the implementation commit, which is every source change described below. The
branch head is one commit later, because a file cannot name its own commit, and that commit adds
only this brief: `git diff f77dd4b HEAD --stat` names `BRIEF.md` and nothing else. **Review the
branch tip**; `f77dd4b` is what to read as the change.

Branch `issue-265-plugin-namespace`, from `origin/master` at `a81af62`.
Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a1c6f9b3733c59da9`.

Two commits behind `origin/master` (`eb24cfd`), and both are `.claude/WAVE.md` only —
`git diff --stat a81af62..origin/master` is `.claude/WAVE.md | 40 ++++++`, so there is nothing
to conflict with.

There is no issue number. The owner's standing rule is fix-don't-file; the decisions are recorded
at <https://github.com/wildware-uk/Udea/issues/265#issuecomment-5751458567>.

---

## 1. The evidence command

```sh
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh scripts/outside-game-proof.sh
```

It publishes the engine and `build-logic` to a local Maven repository, then - in the leg this
change adds - reads the coordinates of **every plugin marker that publish actually wrote** and
fails if any is outside `dev.wildware`, or if any plugin `templates/new-game` applies has no
marker at all. Report: `build/reports/udea/outside-game/`.

Run on `ccd31ca`, exit code `0` off the marker file:

```
=== namespace: the coordinates of every plugin marker this run published
  6 markers -> .../build/reports/udea/outside-game/plugin-markers.txt
  every plugin the template applies has a marker inside dev.wildware
...
=== PROOF GREEN
a game outside this repository resolved the engine from a repository, built, ran, and
inherited gates that fail when broken.
```

*(Elision marked: the five legs between - build, run, bridge, det-red, graph-red - are in
`build/reports/udea/outside-game/` and in `scratchpad/proof.log`.)*

The six markers it wrote, from `build/reports/udea/outside-game/plugin-markers.txt` — and note
that the game then **resolved and applied** them, which is the end-to-end half the coordinate
check alone does not give:

```
dev/wildware/udea/agent/dev.wildware.udea.agent.gradle.plugin/0.1.0-SNAPSHOT/dev.wildware.udea.agent.gradle.plugin-0.1.0-SNAPSHOT.pom
dev/wildware/udea/assets/dev.wildware.udea.assets.gradle.plugin/0.1.0-SNAPSHOT/dev.wildware.udea.assets.gradle.plugin-0.1.0-SNAPSHOT.pom
dev/wildware/udea/game-gates/dev.wildware.udea.game-gates.gradle.plugin/0.1.0-SNAPSHOT/dev.wildware.udea.game-gates.gradle.plugin-0.1.0-SNAPSHOT.pom
dev/wildware/udea/kotlin-library/dev.wildware.udea.kotlin-library.gradle.plugin/0.1.0-SNAPSHOT/dev.wildware.udea.kotlin-library.gradle.plugin-0.1.0-SNAPSHOT.pom
dev/wildware/udea/kotlin-multiplatform/dev.wildware.udea.kotlin-multiplatform.gradle.plugin/0.1.0-SNAPSHOT/dev.wildware.udea.kotlin-multiplatform.gradle.plugin-0.1.0-SNAPSHOT.pom
dev/wildware/udea/kotlin-multiplatform-render/dev.wildware.udea.kotlin-multiplatform-render.gradle.plugin/0.1.0-SNAPSHOT/dev.wildware.udea.kotlin-multiplatform-render.gradle.plugin-0.1.0-SNAPSHOT.pom
```

The fast inner loop, which fences the same rule at source level:

```sh
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew -p build-logic test --tests '*PluginNamespaceTest*'
```

### Proof that it goes red — and it is the production shape, not an invented one

**The artefact fence.** The "before" here is not a mutation I wrote. It is `origin/master`, the
commit the Release workflow actually failed on. The script published `build-logic` from each tree
into a throwaway repository and ran the leg's two commands verbatim
(`/tmp/.../scratchpad/mutation2.sh`; the full output is in `census-final.txt` and
`markers-*.txt`):

```
before = eb24cfd
publish-before exit 0
== before ==
markers published: 19
markers OUTSIDE dev.wildware: 17
after = ccd31ca
publish-after exit 0
== after ==
markers published: 6
markers OUTSIDE dev.wildware: 0
```

The first line of `outside-before.txt` is the plugin the real release died on:

```
udea/android-application/udea.android-application.gradle.plugin/0.0.0-namespace-proof/udea.android-application.gradle.plugin-0.0.0-namespace-proof.pom
```

**Three numbers here are different and must not be conflated.** The lead expected the red count to
be **4**; it is **17**, and the difference is the whole shape of the fix:

| Number | What it counts |
|---|---|
| **4** | plugin ids renamed into `dev.wildware.udea.*`, because something outside applies them |
| **13** | internal `udea.*` conventions whose markers are now suppressed rather than renamed |
| **17** | markers the pre-fix tree publishes outside the namespace — 4 + 13, i.e. every precompiled script plugin |
| **6** | markers the fixed tree publishes: the 4 renamed plus the 2 already-correct hand-registered ones |

4 could never have been the red count, because the marker that took the release down
(`udea.android-application`) is one of the 13, not one of the 4. A fix that renamed only the four
would still have published thirteen bad markers.

**The source fence.** Mutation: undo only the four renames, leave everything else — the literal
`git diff --stat` of that mutation is `55 files changed, 129 insertions(+), 129 deletions(-)`, and
the id-level diff is:

```diff
--- a/templates/new-game/settings.gradle.kts
+++ b/templates/new-game/settings.gradle.kts
@@
-        id("dev.wildware.udea.kotlin-library") version udeaVersion
-        id("dev.wildware.udea.kotlin-multiplatform") version udeaVersion
-        id("dev.wildware.udea.kotlin-multiplatform-render") version udeaVersion
-        id("dev.wildware.udea.game-gates") version udeaVersion
+        id("udea.kotlin-library") version udeaVersion
+        id("udea.kotlin-multiplatform") version udeaVersion
+        id("udea.kotlin-multiplatform-render") version udeaVersion
+        id("udea.game-gates") version udeaVersion
```

`PluginNamespaceTest` then reports **28 sites, 4 distinct ids, across 5 files** (spliced from
`build-logic/build/test-results/test/TEST-...PluginNamespaceTest.xml` of that run):

```
PluginNamespaceTest > every convention plugin a game outside this repository applies is published() FAILED
BUILD FAILED in 28s
```

```
expected: <[]> but was: <[templates/new-game/build.gradle.kts:13 udea.game-gates,
templates/new-game/settings.gradle.kts:43 udea.game-gates,
templates/new-game/settings.gradle.kts:45 udea.kotlin-library,
templates/new-game/settings.gradle.kts:46 udea.kotlin-multiplatform,
templates/new-game/settings.gradle.kts:47 udea.kotlin-multiplatform-render,
templates/new-game/settings.gradle.kts:48 udea.game-gates,
templates/new-game/game/build.gradle.kts:16 udea.kotlin-multiplatform-render,
templates/new-game/game/build.gradle.kts:21 udea.kotlin-library,
docs/new-game.md:60 udea.game-gates, docs/new-game.md:60 udea.kotlin-library,
docs/new-game.md:100 udea.kotlin-library, docs/new-game.md:101 udea.game-gates,
docs/new-game.md:113 udea.game-gates, docs/new-game.md:159 udea.game-gates,
docs/new-game.md:172 udea.game-gates, docs/new-game.md:195 udea.kotlin-library,
docs/new-game.md:195 udea.kotlin-multiplatform-render,
docs/new-game.md:311 udea.kotlin-multiplatform-render, docs/new-game.md:316 udea.game-gates,
docs/wiki/Tutorial-Make-a-Game.md:71 udea.game-gates,
docs/wiki/Tutorial-Make-a-Game.md:71 udea.kotlin-library,
docs/wiki/Tutorial-Make-a-Game.md:109 udea.game-gates,
docs/wiki/Tutorial-Make-a-Game.md:122 udea.game-gates,
docs/wiki/Tutorial-Make-a-Game.md:144 udea.kotlin-library,
docs/wiki/Tutorial-Make-a-Game.md:187 udea.kotlin-multiplatform-render,
docs/wiki/Tutorial-Make-a-Game.md:187 udea.kotlin-library,
docs/wiki/Tutorial-Make-a-Game.md:401 udea.kotlin-multiplatform-render,
docs/wiki/Tutorial-Make-a-Game.md:405 udea.game-gates]>
```

*(That block is one `expected:/but was:` value, re-wrapped at the commas so it fits the page; no
entry is reordered and none is elided. The unwrapped original is in the XML named above.)*

The tree was restored with `git reset --hard` after each mutation, and `git status` is clean.
`ccd31ca` in the transcripts above is this change one amend earlier: `f77dd4b` differs from it only
by extracting a shared `misspelt(...)` helper out of two identical test bodies in
`PluginNamespaceTest` (`git diff ccd31ca f77dd4b --stat` names that one test file and nothing
else), which is why the marker counts quoted against `ccd31ca` still hold — no build script, no
plugin id and no publish task is in that diff.

**Honest note on TDD order.** The test was written before it could be run — the box was on hold
for another project's suite for the first hour — so its first *execution* was after the rename,
not before. The red above is therefore produced by restoring the pre-fix shape rather than by
having run it on the pre-fix tree at the time. The artefact half has no such caveat: its "before"
is `origin/master` itself.

---

## 2. What the defect was

The first real run of `.github/workflows/release.yml` (snapshot run 35523838813) published the
engine and was refused on its build plugins:

```
> Failed to publish publication 'udea.android-applicationPluginMarkerMaven' to repository 'mavenCentral'
   > Could not PUT 'https://central.sonatype.com/repository/maven-snapshots/udea/android-application/udea.android-application.gradle.plugin/0.1.0-SNAPSHOT/udea.android-application.gradle.plugin-0.1.0-20260920.171606-1.pom'. Received status code 403 from server: Forbidden
```

*(Quoted from the lead's task brief, which took it from the run log. I have no access to GitHub
Actions logs from this box, so this block is a quotation of a written record rather than something
I spliced from a file — the one block in this document of which that is true, and it is flagged
here rather than presented as a transcript.)*

A Gradle **plugin marker** is a POM whose coordinates are the plugin id itself — group = the id,
artifact = `<id>.gradle.plugin` — so every plugin id that leaves the machine is a Maven group.
Sonatype authorises a publisher per namespace; ours is `dev.wildware`. Our 17 precompiled script
plugins were named `udea.*`, which asks Central for a group nobody here owns.

**Why a green #265 could not have predicted it.** `scripts/outside-game-proof.sh` and
`publishToMavenLocal` both stop at a local Maven repository, which enforces no namespace rule at
all. Central is the first place the rule exists.

---

## 3. What changed, and the decisions

### The rule

An id now says whether a plugin is published, with no hand-maintained list anywhere:

- **`dev.wildware.udea.*`** — published. A game outside this repository applies it by id, so its
  marker must resolve from a repository, so it must sit inside the verified namespace. Its id is a
  public API.
- **`udea.*`** — internal to this build. A precompiled script plugin that another one applies comes
  off the jar's own classpath, no marker involved, so nothing outside can apply it and nothing is
  uploaded.

`build-logic/build.gradle.kts` enforces it with one `onlyIf` on `AbstractPublishToMaven` keyed on
`publishedNamespace`, and `AGENTS.md` gains a **"Naming a convention plugin"** section stating the
rule and its tie-breaker (*prefer `udea.` when unsure: making one public later is additive,
un-publishing one is not*).

### Decision 1 — four ids move, not one. The brief's premise was wrong.

The task brief, quoting `AGENTS.md`, said `udea.game-gates` is "the one plugin a game outside this
repository applies". The tree says otherwise: `templates/new-game/settings.gradle.kts` declares
versions for four of ours, `templates/new-game/game/build.gradle.kts` applies
`udea.kotlin-library`, and `docs/new-game.md` offers `udea.kotlin-multiplatform-render` to any game
that draws.

Renaming only `game-gates` would have left the outside-game proof green — mavenLocal has no
namespace rule — and produced three more 403s at the next release: the same defect, one round
later. Renamed: `game-gates`, `kotlin-library`, `kotlin-multiplatform`, `kotlin-multiplatform-render`.

**Rejected:** renaming all 17. More uniform, but a published marker is a public API promise and
that makes thirteen of them by accident. The split also yields the prefix rule above, which needs
no list to maintain. **To reverse:** 13 `git mv`s plus one `perl -pi` sweep, after which the
`onlyIf` filter becomes a no-op that can be deleted.

### Decision 2 — `udea.android-application` stays internal

A game shipping on Android from its own repository would want it. No document offers it and nothing
outside this tree is tested on it. **Publishing a marker later is additive; un-publishing one is a
break**, so the reversible option was taken. **To reverse:** rename it like the four; nothing else
changes, because suppression is by prefix rather than by list.

### Decision 3 — `publishToMavenLocal` gets the same filter as the remote publish

The filter is on `AbstractPublishToMaven`, not only `PublishToMavenRepository`, so a local publish
produces the artifact set a release produces. Filtering only the remote would leave
`outside-game-proof.sh` standing on a laxer path than the one it is evidence about — which is
precisely how this defect reached production.

### Corrected because they had stopped being true

`AGENTS.md` and `release.yml` both said nothing had ever been published. `0.1.0-SNAPSHOT` of the
engine's modules is on Central's snapshot repository, signed and resolvable; the convention plugins
are not, until this lands; and no *release* has been made, only that snapshot.

---

## 4. A separate, larger finding: `sh gradlew build` does not run `:build-logic:test`

**`:build-logic:test` did not compile on `origin/master`.** Read from the committed blobs, not my
worktree:

```
$ git show origin/master:build-logic/src/test/kotlin/dev/wildware/udea/build/ModuleGraphRulesTest.kt | grep -n 'ModuleGraphRules::governs\|ModuleGraphRules.GAME_PROJECTS'
701:            included.filterNot(ModuleGraphRules::governs).sorted(),
712:        assertEquals(emptyList(), (games - ModuleGraphRules.GAME_PROJECTS).sorted(), "game projects the game rules skip")

$ git show origin/master:build-logic/src/main/kotlin/dev/wildware/udea/build/ModuleGraphRules.kt | grep -c 'GAME_PROJECTS\|fun governs'
0
```

#265 removed both members in favour of `DependencyRule.appliesTo` and `ProjectScope`
(`UdeaCompilerPluginWiring.kt:189` documents the removal in past tense) and left the two tests
calling them.

**`build-logic` is an included build, so the outer `build` never reaches its `test` task.** A green
repository and a red suite can therefore coexist indefinitely. **#265's reviewer could not have
caught this**: it ran `./gradlew build` with no exclusions, got a genuine green, and passed — the
tests #265 broke are exactly the tests its own gate does not run.

**Be exact about which half is dark.** The `udeaVerify*` *tasks* — contracts, AGENTS.md, module
graph, determinism, wiki — are on the outer `check` and ran throughout. What was absent is the unit
tests **of** the rules, not the rules themselves. "The contract freeze gate was out of service"
would be false.

**I got the elapsed time wrong, and the way I got it wrong is worth more than the number.** I first
reported "three weeks". It was **four hours**: `4b2aca4` is dated 2026-09-20 12:25 and master's head
was 16:25 the same day. I never measured it — I inferred a long duration from the fact that nobody
had noticed, which assumes the conclusion. An unnoticed thing invites you to assume it was
unnoticed for a long time, and that inference is worthless: with the detector switched off, four
hours and three weeks are the same observation.

**Repaired here** (both tests assert the same property, through the same `appliesTo` the verify
task itself calls, via one new private helper `DependencyRule.governsAnyConfigurationOf` that also
de-duplicates three lines already inline in the test twenty lines above). **Wiring
`:build-logic:test` into the outer build is deliberately *not* on this branch** — the lead ruled it
a ticket of its own, because it is an unbounded diff in a branch about plugin namespaces.

**For that ticket:** with the two compile errors fixed, **the whole suite is green — 374 tests, 0
failed, 0 skipped**, counted from the 43 JUnit XML files in `build-logic/build/test-results/test/`
rather than from `BUILD SUCCESSFUL`, which on a `test` task is compatible with nothing having run.
So the scope is the wiring alone; there is no backlog of rot behind it. **That 374 is a baseline,
not a comparison** — nobody has seen it before, because the suite could not compile to produce it.
If wiring it in turns something red, the likely cause is the wiring newly exposing something always
true and never observed (a test reading a repository file undeclared as a Gradle input, say),
rather than a regression from this figure.

`AGENTS.md`'s "Before you say it works" section now states the gap and points at
`./gradlew -p build-logic check`.

---

## 5. `sh gradlew build` — the real output

The branch tip — `f77dd4b` plus this file, and nothing else in the tree differs from the run:

```
BUILD SUCCESSFUL in 27s
1118 actionable tasks: 45 executed, 3 from cache, 1070 up-to-date
```

Exit code `0`, read off the marker file, not the process list. That run is short because it
followed the substantive one below and almost everything was genuinely up to date. Among the 45
that did re-execute, spliced from the run's own log:

```
> Task :moba:android:lint
> Task :moba:game:udeaCheckProtocolLock
> Task :udea-codegen:udeaCheckProtocolLock
> Task :udea-core:udeaCheckProtocolLock
> Task :udea-gradle:test
```

`:udeaVerifyAgentsMd` and `:udeaVerifyWiki` are **not** in that list, and correctly so: they are
`UP-TO-DATE` here because the documents had not changed since the run before it. They re-executed
and passed on the run immediately after the `AGENTS.md` and wiki edits, which is the run that
matters for them:

```
> Task :udeaVerifyAgentsMd
> Task :udeaVerifyWiki
```

The substantive run, on `1262cdf` (this tree before the `AGENTS.md` paragraph in §4):

```
BUILD SUCCESSFUL in 11m 36s
1118 actionable tasks: 1096 executed, 13 from cache, 9 up-to-date
```

Command, both times, with no `-x` exclusions:

```sh
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew build --continue --no-configuration-cache --max-workers=6
```

`build-logic`'s own suite, which the command above does **not** reach:

```
build-logic suite: 374 tests, 0 failed, 0 skipped
PluginNamespaceTest: 7 tests, 0 failed
BUILD SUCCESSFUL in 1m 55s
```

### GL

The ticket changes `udea-render`'s convention plugin **id** and one comment, so the GL surface is
touched in the sense that `udea-render` is configured by a renamed plugin. Run for real rather than
argued:

```sh
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true
```

```
> Task :udea-editor:udeaEditorGlTest
BUILD SUCCESSFUL in 2m 56s
126 actionable tasks: 12 executed, 114 up-to-date
```

`BUILD SUCCESSFUL` on a GL task is compatible with every test *skipping*, which is the whole trap
`-Pudea.render.requireGl` exists for, so the tests were counted out of their own JUnit XML rather
than read off that line:

```
udeaGlTest:       26 classes, 27 tests, 0 failed, 0 skipped
udeaAgentGlTest:   2 classes,  2 tests, 0 failed, 0 skipped
udeaEditorGlTest:  6 classes,  6 tests, 0 failed, 0 skipped
```

### `:moba:desktop:runUdpProof` and the latency budget

Not run: nothing on this branch touches UDP, the simulation, or the asset daemon. The full `build`
above includes `:udea-assets-compiler:udeaDaemonBudget` and it passed.

---

## 6. The census the lead asked for

Every `udea.*` token left in the tree that is shaped like a plugin id, and what each one is.
Produced by `final-census.sh` / `final-census2.sh`; full output in `census-final.txt`.

### A. Every convention plugin this build declares, and whether its marker publishes

```
dev.wildware.udea.agent                        PUBLISHED
dev.wildware.udea.assets                       PUBLISHED
dev.wildware.udea.game-gates                   PUBLISHED
dev.wildware.udea.kotlin-library               PUBLISHED
dev.wildware.udea.kotlin-multiplatform         PUBLISHED
dev.wildware.udea.kotlin-multiplatform-render  PUBLISHED
udea.android-application                       not published
udea.clean-build-budget                        not published
udea.contract-freeze                           not published
udea.determinism-check                         not published
udea.docs-check                                not published
udea.gradle-plugin                             not published
udea.jvm-test-fixtures                         not published
udea.kotlin-base                               not published
udea.kotlin-build-tool                         not published
udea.kotlin-multiplatform-jvm-android          not published
udea.kotlin-multiplatform-no-ios               not published
udea.module-graph-check                        not published
udea.release-check                             not published
declared: 19   published markers: 6
```

Every one of the 13 `not published` entries is internal by the rule in §3: nothing outside this
repository applies it, and no document offers it. `PluginNamespaceTest` would fail if any of them
appeared in the template or the guides.

### B1. Tokens the test's own scan sees, and discards because the bare name is not a convention

```
  dev.wildware.udea.annotations
  dev.wildware.udea.build
  dev.wildware.udea.compiler
  udea.editor
  udea.migration-check
  udea.release
  udea.render
```

Package names, Gradle properties (`-Pudea.release`), and one historical mention:
`udea.migration-check` is named only in a KDoc line in `udea.docs-check.gradle.kts` recording that
it *used* to be called that before #213. Checked by hand; nothing applies it.

### B2. The false-positive class — tokens only a scan *without* the trailing guard produces

47 of them, and this is the part worth reading:

```
  udea.agent          <- from -Pudea.agent.port=7820        (udea-agent-host/build.gradle.kts:73)
  udea.assets         <- from "dev.wildware.udea.agent.assets.*"
  dev.wildware.udea.core, .editor, .gas, .gradle, .moba, .module, .nav, .net, .render,
  .replay, .spike     <- package names
  udea.bench .clean .compiler .example .gizmo .gradle .headless .hollow .jvm .kotlin
  .laneshot .level .levelshot .libgdx .matchshot .migrate .moba .modelshot .module .net
  .pack .physics2d .pinned .plugin .project .registry .replay .repo .shot .source .state
  .test .tool .update                                       <- Gradle properties and task prefixes
```

Two of those — `udea.agent` and `udea.assets` — **name plugins that really exist**. That is the
dangerous shape: a false positive that looks like garbage is deleted in seconds; one that names a
real thing is believed, and then somebody renames a plugin because a Gradle property mentioned it.
My first scan produced exactly those two. The fix is a trailing `(?![\w.-])` so an id must end
where it is written, and both real lines are now in the test's control fixture, so the guard cannot
be removed without a test going red.

---

## 7. Requirement by requirement

| What was asked | Where it is proved |
|---|---|
| 1. `udea.game-gates` → `dev.wildware.udea.game-gates` | §6 A: `PUBLISHED`. `templates/new-game/build.gradle.kts:13`, root `build.gradle.kts:22` |
| 2. The other plugins' markers not published at all | §1 before/after: 19 markers → 6. §6 A lists the 13 as `not published`. Achieved by suppression, with four renamed rather than one — argued in §3 Decision 1 |
| 3. The proof script can catch this class of fault, and would have gone red on the current tree | §1: the leg's two commands, run against `origin/master`, report **17** markers outside the namespace including `udea.android-application` — the exact one the release died on |
| Everything naming the old ids moves with them | §1 mutation diffstat (55 files). `udeaVerifyWiki` and `udeaVerifyAgentsMd` re-ran green (§5). `PluginNamespaceTest`'s "still exists" tests fail on a half-finished rename in the guides *or* in this repository's own build scripts |
| `docs/contracts/` untouched | `udeaVerifyContracts` is on `check` and the build is green; no file under `docs/contracts/` is in the diff |
| `sh gradlew build` green, no `-x` | §5 |
| Failing test first, watched red | §1, with the honest caveat about execution order stated there |

---

## 8. Images

**None, and this is not an omission to route around.** The change is plugin ids, a publish filter,
a source-scanning test and a shell leg. Nothing it does is visible in a frame: no simulation, no
renderer, no HUD, no editor surface. A screenshot of `moba` on this branch and on `origin/master`
would be the same pixels, and posting one would imply evidence it does not carry. The artefacts a
reviewer should open instead are the two marker listings in §1 and the census in §6.

---

## 9. Regenerated files

**None.** No `@Replicated` component was added or removed, so `udea-codegen/net-protocol.lock` and
`expected-generated-hashes.txt` are untouched and no id moved. `:udea-codegen:udeaCheckProtocolLock`
and `:moba:game:udeaCheckProtocolLock` both ran on the final build (§5) and passed.

---

## 10. What I did not exercise

- **A real publish to Central.** Impossible from this box — no credentials, no signing key — and
  the whole point is that the *local* repository cannot enforce the rule. What is checked here is
  the coordinates, which is the input Central rejects on; the rejection itself is Central's.
- **An outside game resolving the renamed plugins over the network.** The proof resolves them from
  `mavenLocal()`, which is what #265 established and what CI's `outside-game` job runs.
- **A game applying an internal `udea.*` plugin from outside.** That now cannot be set up at all —
  there is no marker to resolve — which is the intended behaviour and is why it has no test.
- **iOS.** Not buildable on this Linux box; not claimed.
- **Whether renaming `udea.android-application` would be wanted.** Left internal, reversibly;
  §3 Decision 2.
