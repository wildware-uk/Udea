9080c6f

That is the SHA of the change, and every run in this brief was executed against it. The commit on
top of it adds this file and nothing else — a brief cannot carry the SHA of the commit that
carries the brief. `git show --stat HEAD` is the check.

# `build-logic`'s own tests run inside `./gradlew build`

Branch `issue-265-buildlogic-gate`, off `origin/master` at `d1bf245` (fetched; `git rev-list
--count HEAD..origin/master` = 0, `origin/master..HEAD` = 1). No issue number: the owner's rule is
fix-don't-file, and `#265` is the ticket whose merge exposed this.

---

## 1. The evidence command

```
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue --no-configuration-cache --max-workers=6
```

That is the whole of it. The claim is not "it is green" — it was green before, over a red suite.
The claim is **that command now goes red when `build-logic` breaks, and did not before**, and the
two runs below are what establish it. Both were run on this branch minutes apart, same daemon,
same tree except for the stated mutation.

### 1a. The break, caught

`ModuleGraphRulesTest` calls two members that `#265` deleted — the exact shape of the original
defect. Literal `git diff` of the mutation (saved at
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/runs/mutation.diff`):

```diff
diff --git a/build-logic/src/test/kotlin/dev/wildware/udea/build/ModuleGraphRulesTest.kt b/build-logic/src/test/kotlin/dev/wildware/udea/build/ModuleGraphRulesTest.kt
index 4c975c9..dd08835 100644
--- a/build-logic/src/test/kotlin/dev/wildware/udea/build/ModuleGraphRulesTest.kt
+++ b/build-logic/src/test/kotlin/dev/wildware/udea/build/ModuleGraphRulesTest.kt
@@ -696,7 +696,7 @@ class ModuleGraphRulesTest {
         assertEquals(
             emptyList(),
             included.filterNot { project ->
-                ModuleGraphRules.ALL.any { it.governsAnyConfigurationOf(project) }
+                ModuleGraphRules.ALL.any { it.governs(project) }
             }.sorted(),
             "projects settings.gradle.kts includes that no module-graph rule governs",
         )
@@ -710,7 +710,7 @@ class ModuleGraphRulesTest {
         // of `:moba` paths - until issue #265 replaced it with `ProjectScope`, so "missing from a
         // list" is no longer the way they stop covering a project. The property is unchanged and
         // it is the property that is asserted, through the same `appliesTo` the task itself asks.
-        val games = includedProjects().filterNot { it.startsWith(ProjectScope.ENGINE_PREFIX) }
+        val games = ModuleGraphRules.GAME_PROJECTS
         assertTrue(games.isNotEmpty(), "no game project found in settings.gradle.kts")
         val skipped = listOf(
             ModuleGraphRules.NO_SCRIPTING_OR_REFLECTION_IN_THE_GAME,
```

The evidence command then fails. Spliced from `scratchpad/runs/broken.log` (1880 lines): lines
386-395, then lines 1866-1880, which is the end of the file. The one elision is marked.

```
> Task :build-logic:compileTestKotlin FAILED
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a8895f38eaaa458a7/build-logic/src/test/kotlin/dev/wildware/udea/build/ModuleGraphRulesTest.kt:699:42 Type mismatch: inferred type is Unit but Boolean was expected
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a8895f38eaaa458a7/build-logic/src/test/kotlin/dev/wildware/udea/build/ModuleGraphRulesTest.kt:699:47 Unresolved reference: governs
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a8895f38eaaa458a7/build-logic/src/test/kotlin/dev/wildware/udea/build/ModuleGraphRulesTest.kt:713:38 Unresolved reference: GAME_PROJECTS
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a8895f38eaaa458a7/build-logic/src/test/kotlin/dev/wildware/udea/build/ModuleGraphRulesTest.kt:718:11 Not enough information to infer type variable R
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a8895f38eaaa458a7/build-logic/src/test/kotlin/dev/wildware/udea/build/ModuleGraphRulesTest.kt:719:62 Unresolved reference: it
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a8895f38eaaa458a7/build-logic/src/test/kotlin/dev/wildware/udea/build/ModuleGraphRulesTest.kt:721:49 Unresolved reference: it
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a8895f38eaaa458a7/build-logic/src/test/kotlin/dev/wildware/udea/build/ModuleGraphRulesTest.kt:723:22 Type mismatch: inferred type is List<???> but Double was expected
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a8895f38eaaa458a7/build-logic/src/test/kotlin/dev/wildware/udea/build/ModuleGraphRulesTest.kt:723:44 Type mismatch: inferred type is String but Double was expected

[ ... ELISION: lines 396-1865, 1470 lines of other tasks, none of them failing ... ]

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':build-logic:compileTestKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
   > Compilation error. See log for more details

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights.
> Get more help at https://help.gradle.org.

BUILD FAILED in 8s
1121 actionable tasks: 23 executed, 4 from cache, 1094 up-to-date
```

**Magnitude, not merely redness.** The failing task is the one I broke —
`:build-logic:compileTestKotlin`, nothing downstream or incidental — and the first two errors name
the two members by name: `Unresolved reference: governs` and `Unresolved reference:
GAME_PROJECTS`. Those are the same two references and the same two messages the original defect
produced. The line numbers differ (699/713 here, 701/712 in the original report) because the
repair commit `f77dd4b` rewrote the surrounding comments; the call sites are the same two. The six
further errors are the type inference collapsing around the two unresolved calls, which is also
what the original produced.

### 1b. The control: the same break, with the one wiring line removed, is green

This is the half that proves the wiring is doing the work rather than something else. Same broken
`ModuleGraphRulesTest`, unchanged; the root's `tasks.named("check") { dependsOn(...) }` commented
out; `BuildLogicGateTest.kt` moved aside so its own failure could not be mistaken for the build
noticing. That is `origin/master`'s arrangement exactly.

Tail of `scratchpad/runs/control.log`:

```
BUILD SUCCESSFUL in 28s
1118 actionable tasks: 24 executed, 1094 up-to-date
```

And `grep -n "build-logic" control.log` returns **twelve lines, and these are all of them**:

```
1:> Task :build-logic:checkKotlinGradlePluginConfigurationErrors SKIPPED
2:> Task :build-logic:generateExternalPluginSpecBuilders UP-TO-DATE
3:> Task :build-logic:extractPrecompiledScriptPluginPlugins UP-TO-DATE
4:> Task :build-logic:compilePluginsBlocks UP-TO-DATE
5:> Task :build-logic:generatePrecompiledScriptPluginAccessors UP-TO-DATE
6:> Task :build-logic:generateScriptPluginAdapters UP-TO-DATE
7:> Task :build-logic:compileKotlin UP-TO-DATE
8:> Task :build-logic:compileJava NO-SOURCE
9:> Task :build-logic:pluginDescriptors UP-TO-DATE
10:> Task :build-logic:processResources UP-TO-DATE
11:> Task :build-logic:classes UP-TO-DATE
12:> Task :build-logic:jar UP-TO-DATE
```

Every one is the **main** source set, compiled so the outer build can configure itself. There is
no `compileTestKotlin` line and no `test` line at all. That is the defect, reproduced: a broken
test suite, a green build, and no trace in the log that the suite exists.

Say what that does not say: it is not "the outer build ignores `build-logic`". It compiles it,
twelve tasks' worth. What it never reached was the test half.

### 1c. The same command, on the branch as committed

`scratchpad/runs/final.log` (1864 lines), at `9080c6f`: lines 1853-1859, then lines 1863-1864,
which are the end of the file.

```
> Task :build-logic:test
> Task :build-logic:validatePlugins UP-TO-DATE
> Task :build-logic:check
> Task :build-logic:version-catalog:check UP-TO-DATE
> Task :build-logic:udeaBuildLogicCheck
> Task :check
> Task :build

[ ... ELISION: lines 1860-1862, a blank line, the problems-report link, a blank line ... ]

BUILD SUCCESSFUL in 1m 3s
1122 actionable tasks: 23 executed, 1 from cache, 1098 up-to-date
```

`> Task :check` and `> Task :build` sitting immediately under `udeaBuildLogicCheck` is the
dependency itself: the root's `check` is the thing waiting on the included build.

---

## 2. What I did, why, and what I rejected

**The wiring is one line in the root build script**, on `check` rather than on `build`, so
`./gradlew check` reaches it too:

```kotlin
tasks.named("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":udeaBuildLogicCheck"))
}
```

**`udeaBuildLogicCheck` is a new aggregate in `build-logic/build.gradle.kts`:**

```kotlin
dependsOn(allprojects.map { "${it.path}:check" })
```

Three decisions, each with what was rejected. All three are on `#265` as a comment
(<https://github.com/wildware-uk/Udea/issues/265#issuecomment-5752150039>).

| Decision | Rejected | Why |
|---|---|---|
| Depend on the included build's **`check`**, not its `test` | `.task(":test")` | `check` there is `test` **plus** `validatePlugins`, the `java-gradle-plugin` gate on the plugin descriptors this build publishes — exactly the surface `#265` touched. Taking only `test` lights one dark gate and leaves another. It is also what CI's `build-logic` job already runs, so the local command and the CI job now cover the same set. |
| The aggregate derives from **`allprojects`** | a literal list of `:` and `:version-catalog` | The defect is a suite nobody's habit reached. A hand-written list is the same defect one level in: the day somebody adds a project to the `build-logic` build, its tests are dark and nothing says so. |
| An **aggregate task** in `build-logic` rather than the outer build naming each project | `dependsOn(...task(":test"), ...task(":version-catalog:check"))` | What one build asks another for should be "verify yourself", not a list of that build's projects maintained from outside it — which is a list that goes stale in the other repository. |

**The regression fence is in `:udea-gradle`, not in `build-logic`.** A fence inside `build-logic`
would be reached only by the wiring it exists to check, so deleting the wiring would delete the
alarm with it. `:udea-gradle` is a project of the outer build and runs under `check` either way.
It follows the repository's own idiom for a two-halves-in-two-files gate (`LatencyBudgetJobTest`,
which reads the root script and `ci.yml` for the same reason), and I reused that test's
repository-root accessor by extracting it to `RepositoryUnderTest` rather than copying it.

**What the newly-lit suite showed red: nothing.** The first full `build` with the wiring in place
was green on the first attempt — 374 tests, 0 failures. I went in expecting the lead's predicted
category (a test that reads a repository file it never declared as an input, correct alone and red
once Gradle tracks it). It did not happen, and the reason is visible in
`build-logic/build.gradle.kts`: issue #180 already declared those outer-build files as task
inputs, at length and with the rationale written out. Nothing was excluded, quietly or otherwise.

**Documents that were made false by this change, and are fixed in the same commit:**
`AGENTS.md`'s "Before you say it works" section (which said in as many words that `./gradlew
build` does not run `:build-logic:test`) and `docs/wiki/Build-and-Verification.md` line 133.

**Grepped for the class, not just the instance.** `grep -rn "build-logic" --include='*.md'
--include='*.yml'` over `docs/`, `.claude/`, `AGENTS.md`, `CLAUDE.md` and the workflows. Besides
those two, the hits are: `docs/new-game.md` and `docs/wiki/Tutorial-Make-a-Game.md`, which say
`build-logic` is an included build and so needs its own `publishToMavenLocal` — still true, about
publishing rather than testing; `.claude/agents/engineer.md`, `.claude/agents/reviewer.md` and
`.claude/skills/dev-team/SKILL.md`, which offer `sh gradlew -p build-logic check` as the evidence
command for a build-logic ticket — still true and still the fastest way to run that suite alone,
so left alone (they are also agent configuration, which I do not edit); and the historical
`BRIEF-*.md` files, which are records of what was true when written and are not edited.
**Nothing else.**

### A correction to the record, from a check I ran rather than assumed

It is natural to write this up as "CI would have caught it". CI *does* have a `build-logic tests`
job — `.github/workflows/ci.yml:559`, `./gradlew -p build-logic check`, on every push and every
pull request, and its own comment says it is "the only place CI does". I nearly wrote that the
gap was local-only and CI had it covered.

Then I checked. GitHub has **zero** check runs recorded for the three commits in question:

```
== 4b2aca4 4b2aca4c5a9dbf50bdba04cd5989e47eff02cf1e
   total=0
== 06843c6 06843c6c3015ed616dfd29c88bcd7d8686a71d6e
   total=0
== f77dd4b f77dd4b7e0ec6bf1503d56da689c93db291b0970
   total=0
== d1bf245 d1bf2453f469d6497dabfe7e6da705f4b6890cb6
   total=28
   build-logic tests: success
```

(`gh api repos/{owner}/{repo}/commits/<sha>/check-runs`, saved at
`scratchpad/runs/checkruns.txt`. `06843c6` is #265's work, `4b2aca4` its merge, `f77dd4b` the
follow-up that repaired the two call sites, `d1bf245` today's `origin/master`.) So nothing
anywhere ran that suite while it was broken; the job exists and simply never ran on those SHAs. I
am not asserting *why* — that is a fact about GitHub's records, not a reconstruction.

`d1bf245`'s CI run (35529751827) is a `failure` overall, but `build-logic tests` is `success`
there and the three failing jobs are `build (windows-latest)` and both Windows `determinism` legs.
That is `origin/master`'s state on Windows and it is not something this branch touches or changes.

### The blast radius, stated the narrow way

The `udeaVerify*` **tasks** — `udeaVerifyContracts`, `udeaVerifyAgentsMd`,
`udeaVerifyModuleGraph`, `udeaVerifyDeterminism` — are on the outer `check` and have been running
correctly throughout. What was dark is the **unit** half: the tests *of* those rules. The rules
have been correctly applied while untested. This is not "the contract freeze gate was out of
service".

---

## 3. `sh gradlew build`'s real output, and the counts read out of XML

The full run with the wiring in place, cold enough to execute 745 tasks
(`scratchpad/runs/full.log`; same tree content as `9080c6f`, committed immediately after):

```
BUILD SUCCESSFUL in 6m 10s
1122 actionable tasks: 745 executed, 349 from cache, 28 up-to-date
```

and at `737b4b4`, the change plus this brief's body — the full run that covers every source file
on this branch (`scratchpad/runs/tip.log`; `tip.sha` holds the SHA that run recorded for itself,
taken by the script before it invoked Gradle):

```
BUILD SUCCESSFUL in 1m 6s
1122 actionable tasks: 92 executed, 1030 up-to-date
```

with `> Task :build-logic:test` at `tip.log:1853`, executed rather than `UP-TO-DATE`, and 374
tests / 0 failures in the XML afterwards; and at `9080c6f` itself (`scratchpad/runs/final.log`):

```
BUILD SUCCESSFUL in 1m 3s
1122 actionable tasks: 23 executed, 1 from cache, 1098 up-to-date
```

`> Task :build-logic:test` appears without an `UP-TO-DATE` suffix in both, so the suite actually
executed inside the build rather than being replayed.

**Counted out of the JUnit XML, not off `BUILD SUCCESSFUL`** — summing `tests`/`failures`/
`errors`/`skipped` over every `*.xml` in the results directory:

| Directory | XML files | tests | failures | errors | skipped |
|---|---|---|---|---|---|
| `build-logic/build/test-results/test` | 43 | **374** | 0 | 0 | 0 |
| `udea-gradle/build/test-results/test` | 11 | 63 | 0 | 0 | 0 |

374 matches the baseline in the ticket exactly, and it is now a number the outer build produces
rather than one somebody has to remember to go and get. The 63 in `:udea-gradle` includes the 5
new ones.

`:udea-assets-compiler:udeaDaemonBudget` is a latency budget and is not on `check`; it did not run
in this build and I did not need a solo re-run of it. Nothing timing-related failed.

**Up-to-dateness, because this cost is paid by every developer.** A second `build` with no edits
in between reports `> Task :build-logic:test UP-TO-DATE` and
`> Task :build-logic:udeaBuildLogicCheck UP-TO-DATE`, finishing in 8s
(`scratchpad/runs/final2.log`). The suite re-runs when one of its declared inputs moves — which
includes `AGENTS.md`, the root build script and `docs/wiki/**`, so a docs-only commit does pay for
it. That is issue #180's deliberate design, not a side effect of this change.

**Configuration cache, checked because the ticket's command line disables it and the repository
does not.** `gradle.properties:42` sets `org.gradle.configuration-cache = true`, so CI's `build`
job configures from cache while every run above passed `--no-configuration-cache`. A
`gradle.includedBuild(...).task(...)` dependency is exactly the shape that can fail to serialize,
so I ran `sh gradlew build --continue --max-workers=6` twice with the flag dropped
(`scratchpad/runs/cc.log`, `cc2.log`):

```
BUILD SUCCESSFUL in 1m 9s
1122 actionable tasks: 92 executed, 1030 up-to-date
Configuration cache entry stored.
```

```
BUILD SUCCESSFUL in 3s
1113 actionable tasks: 22 executed, 1091 up-to-date
Configuration cache entry reused.
```

`> Task :build-logic:test` is in both graphs — executed in the storing run (`cc.log:1856`),
`UP-TO-DATE` in the reusing one (`cc2.log:139`) — so the dependency survives being stored and
replayed. I am not going to explain why the storing run executed 92 tasks where the previous
non-cached run executed 23; I did not chase it, both runs are green, and asserting a cause I have
not tested is the thing this brief is otherwise trying not to do.

**No GL.** This branch touches `build.gradle.kts`, `build-logic/build.gradle.kts`,
`udea-gradle/build.gradle.kts`, three new test sources in `:udea-gradle` and two documents.
Nothing in `udea-render`, `udea-editor` or `udea-agent-host`, and the wiring adds no GL task to
`build`'s graph — the tasks it adds are the five `build-logic` ones listed in 1c. So the xvfb run
is not required and I did not run one; I am not claiming anything about GL.

---

## 4. Failing test first

`BuildLogicGateTest` was written and watched fail before either half of the wiring existed.
From `scratchpad/runs/red.log`, with the saved XML at `scratchpad/runs/red-BuildLogicGateTest.xml`:

```
> Task :udea-gradle:test FAILED

BuildLogicGateTest > build-logic declares that task over every project of its own build() FAILED
    org.opentest4j.AssertionFailedError at BuildLogicGateTest.kt:66

BuildLogicGateTest > the outer check depends on build-logic's aggregate verification task() FAILED
    org.opentest4j.AssertionFailedError at BuildLogicGateTest.kt:47

5 tests completed, 2 failed
```

The two wiring assertions were red; the three scanner controls were green from the start, which is
what they are for.

The assertion messages are each one long line in that XML's `message` attribute. **They are not
spliced below** — they are those two lines with the `org.opentest4j.AssertionFailedError: ` prefix
dropped, XML entities decoded and the text re-wrapped to fit this page, so read them as a quotation
rather than as a transcript. The bytes are in `red-BuildLogicGateTest.xml`.

> build.gradle.kts no longer configures `tasks.named("check")`, so nothing in the outer build
> reaches the included build `build-logic`. Its unit tests are then run by no habit anybody has,
> which is the defect issue #265's merge exposed. ==> expected: not \<null\>

> build-logic/build.gradle.kts does not register `udeaBuildLogicCheck`, which is the task the
> outer `check` depends on. The outer build then fails to configure rather than failing to notice,
> but the suite is unreached either way.

### Mutations, each with its literal diff

Taken from the runs themselves, not retyped. Diffs saved at `scratchpad/runs/mutation-b.diff` and
`mutation-c.diff`; the logs beside them. Each was produced by `git diff` against `9080c6f` while
the mutation was in the tree, and each is quoted from its third line — the `diff --git` and
`index` header lines are omitted, and nothing else is.

**Mutation B — the wiring line replaced by a plausible one.** Not deleted: replaced with a
`dependsOn` that still leaves a `tasks.named("check") { }` block there, so the test cannot pass by
noticing the block is gone.

```diff
--- a/build.gradle.kts
+++ b/build.gradle.kts
@@ -385,7 +385,7 @@ val udeaAssemble by tasks.registering {
 // yourself" rather than a list of that build's projects maintained from outside it.
 // `:udea-gradle`'s `BuildLogicGateTest` is what notices this line being removed.
 tasks.named("check") {
-    dependsOn(gradle.includedBuild("build-logic").task(":udeaBuildLogicCheck"))
+    dependsOn(udeaAssemble)
 }
```

```
BuildLogicGateTest > the outer check depends on build-logic's aggregate verification task() FAILED
    org.opentest4j.AssertionFailedError at BuildLogicGateTest.kt:54

5 tests completed, 1 failed
```

**Mutation C — the aggregate stops covering every project**, the realistic regression: a
hand-written list in place of the derivation.

```diff
--- a/build-logic/build.gradle.kts
+++ b/build-logic/build.gradle.kts
@@ -515,5 +515,5 @@ val udeaBuildLogicCheck by tasks.registering {
     description =
         "Verifies every project of the build-logic build. The outer build's `check` depends on " +
             "this, because nothing in the outer build can reach an included build's tasks by itself."
-    dependsOn(allprojects.map { "${it.path}:check" })
+    dependsOn(listOf(":check"))
 }
```

```
BuildLogicGateTest > build-logic declares that task over every project of its own build() FAILED
    org.opentest4j.AssertionFailedError at BuildLogicGateTest.kt:75

5 tests completed, 1 failed
```

Exactly one test red in each, and it is the one whose subject was mutated. Both blocks are
`mutation-b.log` and `mutation-c.log` lines 81-84, unelided.

### The controls, run rather than assumed

The scanner reads build scripts as text, so the two ways a text fence goes wrong are both asserted
in `BuildLogicGateTest` itself, as cases rather than as prose:

- **A `//` comment or a KDoc mentioning the wiring does not satisfy it.** `stripComments` is a
  string-aware state machine, so it also keeps the `https://` URLs these scripts are full of —
  that case is its own test.
- **A `dependsOn` hung on some other task does not answer for `check`.** The block is taken by
  matching braces from `tasks.named("check")`, and a fixture that puts the same expression on a
  task nothing runs is asserted to fail.

Both controls go the other way too: the same scanner is handed the wiring as real code and must
see it. A fence that fails on prose is as wrong as one that passes on it.

---

## 5. Images: none, deliberately

Nothing on screen changes. `moba` on this branch and on `origin/master` renders identical pixels,
and a screenshot here would imply evidence it does not carry. The artefacts are the transcripts
above. Nothing was copied to `/srv/ssd1/workspace/Udea/build/debug-screenshots/`.

---

## 6. What the outer `build` reaches that it did not before

Read off `sh gradlew :check --dry-run` and confirmed in every full-build log since:

| Task | Was it in `./gradlew build` before? |
|---|---|
| `:build-logic:compileTestKotlin` | no |
| `:build-logic:test` (374 tests) | no |
| `:build-logic:validatePlugins` | no |
| `:build-logic:check` | no |
| `:build-logic:version-catalog:check` | no |
| `:build-logic:udeaBuildLogicCheck` | did not exist |
| `:udea-gradle:test` — 5 new cases in `BuildLogicGateTest` | the task yes, the cases new |

Nothing was removed from the graph and nothing outside `build-logic` gained or lost a task. The
deliberately-excluded gates are untouched: `:moba:desktop:runUdpProof` and
`:moba:desktop:runLaneShot` are still outside `check`, and so are the latency budgets.

---

## 7. Regenerated files

None. This branch adds no replicated component and touches neither
`udea-codegen/net-protocol.lock` nor `udea-codegen/src/test/resources/expected-generated-hashes.txt`.
No file in `docs/contracts/` is touched, and `docs/contracts.lock` is unchanged.

---

## 8. My own near-misses, and what is weaker than it looks

Three, and the first is the one I would want a reviewer to know.

**I almost published "CI would have caught it."** CI has a job for exactly this and I read its
YAML, its name and its comment. That was a description of a thing within reach of the thing
itself, and the arithmetic did not have to work for me to believe it. Running
`gh api .../check-runs` took forty seconds and returned `total_count: 0` for all three commits.
The conclusion I nearly shipped was not merely unsupported; it was wrong.

**The unit fence is a text scan, and text is the weaker half.** `BuildLogicGateTest` reads two
build scripts; it does not resolve a task graph. It can be defeated by a wiring spelt differently
enough — `named<Task>("check")`, or the dependency attached from a plugin instead of from the
script. What it does reliably catch is the line being *deleted*, which is the realistic
regression, and it is deliberately not the whole of the evidence: 1a and 1b are executed runs. I
considered a TestKit test that resolves the real graph and rejected it as a nested build of this
whole repository inside a unit test, which is minutes per run for a stronger claim about a line
that two transcripts already cover.

**I mutated this worktree while the evidence runs were in flight**, including moving
`BuildLogicGateTest.kt` out of the tree for the control run. Nobody else reads this worktree, and
everything is restored — `git status` is clean at `9080c6f` and the two mutation diffs above were
taken against that commit. Saying it after the fact rather than before is the part I would do
differently.

**One thing I did not exercise:** whether a *second* project added to the `build-logic` build
would really be picked up. I asserted the property in source (`allprojects`) and confirmed the
existing second project is reached — `:build-logic:version-catalog:check` is in the dry-run graph
and in every build log — but I did not create a third project to watch it appear. The empty case
is covered by the existing one being non-empty; the growth case is covered by the derivation and
by Mutation C, not by an executed example.

---

## 9. The issue, criterion by criterion

The ticket has no numbered acceptance criteria, so this is its requirements as written.

| Asked | Where it is proved |
|---|---|
| `:build-logic:test` reachable from `sh gradlew build` | §1c and §6: `> Task :build-logic:test` in the full-build log, without `UP-TO-DATE`, and 374 tests in the XML |
| Reference `#265` in the commit message | `9080c6f`, subject line and body |
| Prove a break in `build-logic` now fails `sh gradlew build` | §1a: the mutation diff and `:build-logic:compileTestKotlin FAILED` naming `governs` and `GAME_PROJECTS` |
| ...and that it did not before | §1b: same break, wiring removed, `BUILD SUCCESSFUL`, and the twelve `build-logic` lines that are all main-source tasks |
| Failing test first | §4: `BuildLogicGateTest` red at two assertions before either half of the wiring existed |
| Do not weaken a test to make it pass | Nothing weakened. No test edited but `LatencyBudgetAggregate`, where one accessor was extracted, not changed |
| Nothing excluded quietly | Nothing excluded at all; §2 records that the newly-lit suite showed nothing red |
| Do not wire the deliberately-excluded gates into `check` | §6: `runUdpProof`, `runLaneShot` and the latency budgets are untouched |
| `sh gradlew build` still one command, no exclusions | §1, §3 — the evidence command has no `-x` |
| Count tests out of the XML | §3, the table |
| Say why there are no images | §5 |
| State what the outer `build` now reaches | §6 |
| Decisions commented on the issue | <https://github.com/wildware-uk/Udea/issues/265#issuecomment-5752150039> |

---

## 10. Files

```
 AGENTS.md                                          |  20 +--
 build-logic/build.gradle.kts                       |  38 ++++++
 build.gradle.kts                                   |  23 ++++
 docs/wiki/Build-and-Verification.md                |   2 +-
 udea-gradle/build.gradle.kts                       |   9 ++
 .../dev/wildware/udea/gradle/BuildLogicGate.kt     | 122 +++++++++++++++++
 .../dev/wildware/udea/gradle/BuildLogicGateTest.kt | 149 +++++++++++++++++++++
 .../wildware/udea/gradle/RepositoryUnderTest.kt    |  30 +++++
 .../udea/gradle/ci/LatencyBudgetAggregate.kt       |  13 +-
 9 files changed, 389 insertions(+), 17 deletions(-)
```

`BRIEF-namespace.md` is `origin/master`'s `BRIEF.md` — the plugin-namespace developer's, verbatim,
`git show origin/master:BRIEF.md`. Writing mine to `BRIEF.md` would otherwise have deleted it, and
it had not been archived under a `BRIEF-<name>.md` yet. Delete it if the lead archives it under
another name.

The saved run artefacts every transcript above is spliced from live in
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/runs/`:
`red.log`, `red-BuildLogicGateTest.xml`, `full.log`, `broken.log`, `mutation.diff`, `control.log`,
`final.log`, `final2.log`, `mutation-b.diff`, `mutation-b.log`, `mutation-c.diff`,
`mutation-c.log`, `dry.log`, `checkruns.txt`. They are on this box and outlive the processes that
made them, but not the machine.
