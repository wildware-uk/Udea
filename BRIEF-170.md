# BRIEF-170 — a clone builds `:moba`, and nobody types anything

`a966e29` — the last commit that touches anything but this file. `HEAD` is that commit plus this
brief (two commits of it), and a self-naming SHA is impossible: writing one into a file changes
it. `git log --oneline a966e29..HEAD` shows only `BRIEF-170.md`.

Branch `issue-170-moba-art-clean-clone`, off `origin/example` (`7942823`).

Every `logs/...` filename below is a real file in
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/a3ee2737-1b26-4f77-96b3-6805f45c796f/scratchpad/logs/`
on this box; every block quoted from one is spliced, not retyped.

Every transcript below was produced at `e0f4de6`, and the linked Actions run is `c035e1c`.
`e0f4de6` to `a966e29` is three comment corrections — a stale count in a KDoc, an over-reaching
"everything", and a recorded limit on the test's regex — and nothing else: `git diff
e0f4de6..a966e29` is four hunks, every one of them inside a comment.

---

## 1. The evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem python3 scripts/verify-art-staging.py
```

It creates a **fresh checkout of `HEAD`** in a temporary worktree, asks the validator for the old
failure back, checks that the checkout carries no paid-pack art, runs the command
`docs/art-assets.md` documents, and then checks what that produced. Its whole run at `e0f4de6`, spliced from `logs/issue170-evidence-GREEN.log`:

```
repository: /srv/ssd1/workspace/Udea/.claude/worktrees/agent-aae42d941ef837a54
verifying commit: e0f4de6 (a fresh checkout of HEAD, not the working tree)
clean tree: /tmp/udea-art-verify-39m9jw9k/clean
documented step, from docs/art-assets.md:
    ./gradlew :moba:build

[1/7] negative control: :moba:udeaValidateAssets must FAIL with -x udeaStageCharacterArt
  FAILED as required, 25 x UDEA0032

[2/7] a fresh checkout must carry no paid-pack art under moba/assets/sprites
  3 file(s) under moba/assets/sprites, all of them the documented exceptions

[3/7] running the documented step in the clean tree
  ...
  BUILD SUCCESSFUL in 15s
  66 actionable tasks: 35 executed, 11 from cache, 20 up-to-date

[4/7] the build must have staged the art, packed it, and left the tree clean
  33 sheet(s) staged, moba/build/udea/pack/assets.udeapak packed, `git status` clean

[5/7] LICENSE must exclude wherever the build put the art
  LICENSE covers all 33 staged file(s)

[6/7] README.md's licence claim must match LICENSE
  README.md's licence section and LICENSE agree on 'MIT'

[7/7] README.md must not name a staging script
  README.md names no script, consistent with docs/art-assets.md

OK: a fresh clone builds :moba with no manual step, and the licence covers the art.
```

(The elision in `[3/7]` is the Gradle output of `./gradlew :moba:build`, which the script prints
in full; the two lines named are in the saved log.)

### It goes red when the feature is reverted — twice, two different ways

Both were run in a throwaway worktree off this branch's HEAD, and the diffs below are `git diff`
from those runs, not descriptions of them.

**Revert A — the staging task exists, nothing runs it.**

```
diff --git a/build-logic/src/main/kotlin/dev/wildware/udea/build/CharacterArtStaging.kt b/build-logic/src/main/kotlin/dev/wildware/udea/build/CharacterArtStaging.kt
index 24d8d49..1b60df7 100644
--- a/build-logic/src/main/kotlin/dev/wildware/udea/build/CharacterArtStaging.kt
+++ b/build-logic/src/main/kotlin/dev/wildware/udea/build/CharacterArtStaging.kt
@@ -250,11 +250,5 @@ public fun Project.registerCharacterArtStaging() {
         stagedSheets.setFrom(CharacterArtStaging.PLAN.keys.map { destination.file(it) })
     }
 
-    for (consumer in listOf(
-        UdeaAssetsPlugin.SCAN_TASK,
-        UdeaAssetsPlugin.VALIDATE_TASK,
-        UdeaAssetsPlugin.PACK_TASK,
-    )) {
-        tasks.named(consumer) { dependsOn(stage) }
-    }
+    // REVERT PROOF ONLY: the wiring is removed so the task exists and nothing runs it.
 }
```

Verdict (`logs/issue170-evidence-REVERTED.log`) — steps 1 and 2 still pass, which is what makes
the control worth having, and step 3 is where it dies:

```
[1/7] negative control: :moba:udeaValidateAssets must FAIL with -x udeaStageCharacterArt
  FAILED as required, 25 x UDEA0032

[2/7] a fresh checkout must carry no paid-pack art under moba/assets/sprites
  3 file(s) under moba/assets/sprites, all of them the documented exceptions

[3/7] running the documented step in the clean tree
  ...
  [udeaValidateAssets] error UDEA0032 moba/assets/character/skeleton.udea.kts:49:1 `character/skeleton_walk_sheet` names 1 file(s) that are not under the asset root /tmp/udea-art-verify-e833jihe/clean/moba/assets: `spritePath` names `sprites/skeleton/Skeleton-Walk.png`
```
```
FAILED: the step docs/art-assets.md documents exited 1: './gradlew :moba:build'. The documentation names a command a fresh clone cannot use.
```

**Revert B — no staging task at all, which is what `origin/example` is.**

```
diff --git a/moba/build.gradle.kts b/moba/build.gradle.kts
index 8b58c6f..e36ffd6 100644
--- a/moba/build.gradle.kts
+++ b/moba/build.gradle.kts
@@ -171,7 +171,7 @@ udea {
  * So the art lands where the manifest already says it lands, and the only thing that changed is
  * who puts it there.
  */
-registerCharacterArtStaging()
+// REVERT PROOF ONLY: registerCharacterArtStaging()
 
 /**
  * The release gate bans **this game's** agent package too, not only the engine's.
```

Verdict (`logs/issue170-evidence-REVERTED-notask.log`):

```
FAILED: there is no task called udeaStageCharacterArt to exclude, so the control could not be run. Nothing in this build stages the art, which is the state issue #170 was filed about:
```

That second message exists because of revert B. The first attempt at it reported *"failed, but
not for missing art"* — true, and about a build Gradle had refused to start. Commit `bd03433` adds
the branch that names the real reason.

Both reverts were run against `e0f4de6`, in a throwaway worktree that has since been removed. The diffs above are `git diff` output from those two runs.

---

## 2. Summary

### What the defect was

`moba/assets/sprites/` is gitignored paid-pack art, so a clone carries none of it and
`:moba:udeaValidateAssets` refused the manifest with one `UDEA0032` per sheet — capped at the
contract's 25. The only thing that fixed it was `scripts/stage-moba-art.py`, named in
`docs/art-assets.md` and run by **nothing**: not the build, not CI, not `AGENTS.md`. Every
`moba`-building CI job had therefore been red on every push since the characters landed.

Reproduced in this worktree before anything was changed
(`logs/repro-validate-unstaged.log`):

```
[udeaValidateAssets] error UDEA0032 moba/assets/character/soldier.udea.kts:70:1 `character/soldier_attack_sheet` names 1 file(s) that are not under the asset root /srv/ssd1/workspace/Udea/.claude/worktrees/agent-aae42d941ef837a54/moba/assets: `spritePath` names `sprites/soldier/Soldier-Attack01.png`

> Task :moba:udeaValidateAssets FAILED
```
```
[udeaValidateAssets] 147 asset(s), 25 diagnostic(s)
```

### What I did

`:moba:udeaStageCharacterArt` — a Gradle task registered from `moba/build.gradle.kts` by
`registerCharacterArtStaging()` in `build-logic` — copies 33 sheets out of
`example/src/main/resources/assets/sprites/` into `moba/assets/sprites/` and runs ahead of
`udeaScanAssets`, `udeaValidateAssets` and `udeaPackBundle`. `scripts/stage-moba-art.py` is
deleted; its mapping is `CharacterArtStaging.PLAN`, as data.

The same tree, after (`logs/wired-validate.log`):

```
> Task :moba:udeaStageCharacterArt
[udeaStageCharacterArt] staged 33 sheet(s) into /srv/ssd1/workspace/Udea/.claude/worktrees/agent-aae42d941ef837a54/moba/assets/sprites

> Task :moba:udeaValidateAssets
[udeaValidateAssets] 147 asset(s), 0 diagnostic(s)
```

### The decisions, and what I rejected

**Approach 2 over approach 1.** Resolving a `spritePath` against `example/` as a fallback means
the pipeline supporting a second asset root, which `UdeaAssetsExtension` declines to do for a
reason that has not changed — ids are relative to a root, so two roots need a rule for a colliding
id — and it puts an old-tree path inside the engine's asset compiler, weakening `UDEA0032` for
every game to fix one game's art. Approach 3 (a CI step) was ruled out by the lead before I
started, and would have made #154's fresh-clone proof a lie by omission.

**The script is deleted, not invoked.** `build (windows-latest)` is a required job and a
`python3` on `PATH` is not something a Kotlin build should acquire a dependency on to compile. A
Gradle task is cross-platform, up-to-date-checked, and testable — which the script was not.

**Staging writes into the source tree, not into `build/`.** The alternative is to copy the whole
asset root into a generated directory and pack from there. That would make `:moba:run`'s
`udea.assets.root`, the asset daemon's hot-reload path and every repo-relative span in a
diagnostic name a directory under `build/` instead of the tree a developer edits — and it would
leave `:udea-assets-compiler`'s corpus tests reading an incomplete tree for ever.

**The plan is data, not a filesystem search.** The script searched two locations per sheet
(`<char>/<sheet>`, then `<char>/<Char>/<sheet>`). A configuration-time probe is a decision the
configuration cache makes once and reuses, and a build that stages a different set depending on
what it found is a build whose output depends on the machine. The one character the committed tree
nests is named in `NESTED` instead.

**Outputs are the 33 files, not the destination directory.** `moba/assets/sprites/` contains two
committed files (`champion_idle.png` and the free demo pack's arrow). An `@OutputDirectory` invites
Gradle's stale-output cleanup to delete what the task did not write, and deleting a file that is in
git, out of a build, is not a failure anybody would think to look for.

All four are recorded on the issue: <https://github.com/wildware-uk/Udea/issues/170#issuecomment-5482932358>

### The surprise, and it is the valuable part

Unblocking `:moba` let CI run past the art for the first time since the characters landed, and
failures appeared that nobody could previously see. None is caused by this branch and none is fixed
by it. They are enumerated with evidence on the issue, in two comments:
<https://github.com/wildware-uk/Udea/issues/170#issuecomment-5483250078> and
<https://github.com/wildware-uk/Udea/issues/170#issuecomment-5483353169>

- `game-bridge-mcp conformance` — fails compiling the vendored client, before any step touches a
  game. **#171 owns it, in flight.**
- `the FIR checkers fail a real build` — `CheckerProbe.kt` cannot resolve
  `dev.wildware.udea.annotations`, so the probe never reaches the checkers. **#173 owns it, in
  flight.**
- **The wall-clock budget gates do not pass on a GitHub runner, and it is systematic rather than
  flaky.** Two runs, two samples each and the same direction: `udeaPhase2Exit` 1485 ms then
  1250 ms against 1000 ms; `udeaDaemonBudget` warm validate 377 ms then 475 ms against 300 ms;
  `udeaPackGate` graph deserialisation over 15 ms both times, and `udeaPhase2Exit` again at
  1217 ms on the third run. All three drive their own synthetic fixtures and read nothing this
  branch stages. **Unowned.**
- **`clean build under budget` is measuring the runner.** Four samples of the same command on the
  same code: 94324, 89897, 60405, 93867 ms against 90000. **Unowned.**
- **Line endings defeat three tests on Windows, in two modules.** `ExampleScanTest` twice in
  `udea-assets-compiler` and `AgentsMdTest` once in `build-logic`. Proven from the uploaded
  report's bytes rather than inferred — the golden side carries 169 CR bytes and the generated side
  1 — and confirmed by the second instance's exception type: `AgentsMdTest` throws
  `NoSuchElementException` on `.single()` because a `replace("include(\"udea-gas\")\n", "")`
  matched nothing on a CRLF checkout, so no finding existed to inspect. The repository has no
  `.gitattributes` and Git for Windows checks out `core.autocrlf=true`. **Unowned.**

Not a path-separator bug, which was the first hypothesis: `SourceSpan.normalize` and
`UdeaDeclarationScanner` both convert `\` to `/`. I checked before publishing a cause.

The last three are deliberately not fixed here. `DaemonLatencyBudgetTest`'s own KDoc forbids
widening its budget, and a repo-wide `.gitattributes` is not obviously safe — the `example` corpus
is committed CRLF *on purpose*, which `UdeaDeclarationScanner.normalizeLineEndings` exists for.
They want decisions, in tickets somebody would think to look in.

**The test this branch adds is not in that class**, and that is checked rather than argued: its
regex captures inside the quotes so no line ending reaches an assertion, and the artefact from
`determinism (windows-latest, temurin)` on run 33429732331 says
`CharacterArtStagingTest: 6 tests, 0 failures, 100% successful`.

### A second, smaller surprise, and it is in the diff

`:udea-assets-compiler`'s `Test` tasks declare `moba/assets` as an input — deliberately;
`MigratedCorpusCompilesTest` and friends compile and pack the game's real corpus. Once part of
that tree is *produced*, Gradle rejects the whole graph:

```
Reason: Task ':udea-assets-compiler:test' uses this output of task ':moba:udeaStageCharacterArt' without declaring an explicit or implicit dependency. This can lead to incorrect results being produced, depending on what order the tasks are executed.
```

Fixed in `2373040` by ordering those tasks after the staging task. Naming a `:moba` task from an
engine module is worth the comment it carries: it is not a classpath edge — `UDEA-MG-*` and
`UDEA-LEGACY-001` read resolved configurations and this module resolves nothing of `:moba` — it is
ordering for a tree those tests already read by path.

---

## 3. The tests, and watching them fail first

`build-logic/src/test/kotlin/dev/wildware/udea/build/CharacterArtStagingTest.kt`, six tests.
Written before the implementation; the production file existed as a stub with `PLAN = emptyMap()`
and an empty task action. **Note `build-logic` is an included build, so `sh gradlew build` does
not run these** — CI does, in `migration ledger` (`./gradlew -p build-logic check`) and in
`determinism` (`./gradlew -p build-logic test`). On the linked run `migration ledger` and both
ubuntu `determinism` legs are green; the two Windows `determinism` legs are red on `AgentsMdTest`,
which is the line-endings class above and not this test — the Windows artefact reports
`CharacterArtStagingTest: 6 tests, 0 failures`.

Red first (`logs/red-1.log`, and the message out of the JUnit XML):

```
CharacterArtStagingTest > the plan stages every sprite the game names that a clone does not carry() FAILED
    org.opentest4j.AssertionFailedError at CharacterArtStagingTest.kt:72

CharacterArtStagingTest > staging fails and names the sheet when the committed art is not there() FAILED
    org.opentest4j.AssertionFailedError at CharacterArtStagingTest.kt:142

CharacterArtStagingTest > staging copies every planned sheet into the destination tree() FAILED
    java.io.FileNotFoundException at CharacterArtStagingTest.kt:131

6 tests completed, 3 failed
```
```
org.opentest4j.AssertionFailedError: the build stages a different set of sheets from the one moba/assets/**/*.udea.kts names. Every sprite the game names has to be either committed or staged; one that is neither is a UDEA0032 on every clean clone. ==> expected: <[sprites/orc/Orc-Attack01.png, ... sprites/wizard/Wizard-Walk.png]>
```

That expected list is **derived from the real asset scripts**, not written next to the plan: the
test walks `moba/assets` for `spritePath = "sprites/..."`, subtracts the two committed exceptions,
and demands the plan equal what is left. A seventh character therefore fails a test that names the
sheets nobody wired up. The exception list is itself checked against the tree, so a wrong entry
cannot quietly excuse a sheet the build should have staged.

Green after (`logs/green-1.log`): `BUILD SUCCESSFUL`, 6 tests.

The two behaviour tests drive the real task over a synthetic tree — copying, including the nested
`wizard/Wizard/` case, and the failure path when a sheet is not there. The rest of the wiring is
proved by the evidence command rather than by a unit test, because "the build stages the art before
anything reads the asset root" is a claim about a task graph, and the honest way to check it is to
build a clean checkout.

---

## 4. `sh gradlew build`

`sh gradlew build`, no exclusions, at `e0f4de6` (`logs/issue170-build-final.log`):

```
BUILD SUCCESSFUL in 1m 53s
214 actionable tasks: 54 executed, 4 from cache, 156 up-to-date
```

**That green is partly a replay, and here is which part.** Three of the four wall-clock gates
were `UP-TO-DATE` in it, because they had passed in the solo run below and their inputs had not
moved since:

```
> Task :moba:udeaStageCharacterArt UP-TO-DATE
> Task :udea-assets-compiler:udeaDaemonBudget UP-TO-DATE
> Task :udea-core:udeaBenchCharacterMover UP-TO-DATE
> Task :udea-assets-compiler:udeaPackGate UP-TO-DATE
> Task :udea-agent-host:udeaPhase2Exit
```

So the honest transcript is the cold one. `sh gradlew clean build --no-build-cache` at the same
commit, started at load 14.86 on a box shared with another project
(`logs/issue170-build-cold-final.log`):

```
> Task :udea-core:udeaBenchCharacterMover FAILED
> Task :udea-assets-compiler:udeaDaemonBudget FAILED
BUILD FAILED in 1m 54s
179 actionable tasks: 169 executed, 10 up-to-date
```
```
    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) median 15.245ms, budget 4.0ms
    warm reload decision: median 844ms over 4 samples [844, 621, 886, 642]
    warm validate of one script: median 471ms over 4 samples [38, 471, 456, 577]
```

Two of the four tasks the developer contract names as load-sensitive. Re-run alone with
`--rerun-tasks` minutes later, still at load 14 (`logs/issue170-budgets-solo-final.log`):

```
    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) median 2.467ms, budget 4.0ms
    warm reload decision: median 193ms over 4 samples [274, 193, 193, 165]
    warm validate of one script: median 140ms over 4 samples [14, 182, 140, 124]
BUILD SUCCESSFUL in 36s
```

15.245 ms to 2.467 ms and 471 ms to 140 ms is a factor of six on the same commit, minutes apart,
at the same load average — it is contention inside the build's own 24-way parallelism, not
anything this branch does.

The sharpest version of that control came at the end, on `a966e29` and by accident. `sh gradlew
build` failed `udeaDaemonBudget` at load 20.8 with `warm validate ... median 533ms` and `warm
reload ... median 764ms`; `sh gradlew :udea-assets-compiler:udeaDaemonBudget --rerun-tasks`,
immediately afterwards and at **load 20.7**, gave:

```
    warm reload decision: median 236ms over 4 samples [256, 236, 228, 233]
    warm validate of one script: median 128ms over 4 samples [12, 128, 125, 133]
BUILD SUCCESSFUL in 21s
```

Same commit, same minute, same load average, four times faster. So it is not "the box is loaded"
in general — it is this build's own parallelism competing with a task that measures latency. These
gates are *on* `check` (`udea-assets-compiler/build.gradle.kts` wires `udeaDaemonBudget` into it
explicitly), which is why every full build meets them and why the dev-team contract names this
exact set as the one to re-run alone before concluding anything. A final `sh gradlew build` after
that solo run is green in 1m 5s, with `udeaPhase2Exit` executing rather than cached, and passing.

Nothing in the diff is reachable from `CharacterMoverBudgetTest` or `DaemonLatencyBudgetTest`:
both drive their own fixtures.

The same two tasks failed under the same conditions earlier in the session and passed the same way
(`logs/issue170-budgets-alone.log`), and `dev-171` reported the identical pattern independently on
a different branch.

What the cold build proves about this ticket, from the same log:

```
[udeaStageCharacterArt] staged 33 sheet(s) into /srv/ssd1/workspace/Udea/.claude/worktrees/agent-aae42d941ef837a54/moba/assets/sprites
[udeaValidateAssets] 147 asset(s), 0 diagnostic(s)
[udeaPackBundle] assets.udeapak: 147 asset(s), 38 sheet(s), 1 atlas page(s), 101450 bytes
```

### The three gates outside `check`

`sh gradlew udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd
--rerun-tasks` (`--rerun-tasks` because all 33 were `UP-TO-DATE` otherwise, and an up-to-date gate
has not run):

```
> Task :udeaVerifyAgentsMd
BUILD SUCCESSFUL in 716ms
33 actionable tasks: 33 executed
```

`udeaVerifyAgentsMd` matters here because this branch edits `AGENTS.md`. It adds a bullet under
"Before you say it works"; the module table is untouched, and no module moved.

### `build-logic` is an included build

`sh gradlew build` does **not** run `CharacterArtStagingTest` — `ci.yml` says so in as many words
at line 345. It is run by `sh gradlew -p build-logic check`, which on this box gives:

```
248 tests completed, 2 failed
```

The two are `KotlinPinCheckTest`, and they are the box: there is **no JDK 17 installed here**, and
those two spin up a TestKit build that asks for one.

```
* What went wrong:
Could not determine the dependencies of task ':udea-core:udeaVerifyKotlinPin'.
> Could not resolve all dependencies for configuration ':udea-core:compileClasspath'.
   > Failed to calculate the value of task ':udea-core:compileJava' property 'javaCompiler'.
      > Cannot find a Java installation on your machine (Linux 6.8.0-138-generic amd64) matching: {languageVersion=17, vendor=any vendor, implementation=vendor-specific}. Toolchain download repositories have not been configured.
```

Checked against `origin/example` on the same box rather than assumed: a detached worktree at
`7942823`, `sh gradlew -p build-logic test --tests '*KotlinPinCheckTest*'`, same two tests, same
failure (`logs/issue170-control-kotlinpin.log`):

```
KotlinPinCheckTest > an unclassified resolvable configuration fails the gate(File) FAILED
KotlinPinCheckTest > a module whose classpaths are all classified passes(File) FAILED
2 tests completed, 2 failed
```

CI installs 17 with `actions/setup-java`, and both `-p build-logic` jobs — `migration ledger` and
`determinism (ubuntu-latest, *)` — are green on the branch.

### GL

This ticket touches no GL. Nothing in the diff reaches `udea-render` or the render half of
`udea-agent-host`; the change is a Gradle task that copies files, plus documentation. `udeaGlTest`
and `udeaAgentGlTest` therefore have nothing to say about it, and the `gl tests (xvfb)` CI job —
which runs them for real with `-Pudea.render.requireGl=true` — is **green** on the linked run
anyway.

The one thing this branch does put in front of a GL context is the art itself, and that is what
`issue170-roster-from-staged-art.png` below shows.

---

## 5. Images

Both in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`.

- **`issue170-roster-from-staged-art.png`** — `:moba:runShot` under xvfb in this worktree, whose
  `moba/assets/sprites/` was empty until the build filled it. All six characters draw: orc,
  orc_elite, priest, skeleton, soldier, wizard. It proves the staged files are the real sheets
  rather than empty or truncated ones — a validator only checks that a path resolves, and this is
  the frame that shows the pixels arrive.

---

## 6. The issue, criterion by criterion

### AC1 — "A real Actions run on the branch shows every `moba`-building job green. Link the run."

**Run: <https://github.com/wildware-uk/Udea/actions/runs/33431241769>** (`c035e1c`).

| Job | Verdict | Why, if red |
|---|---|---|
| `build (ubuntu-latest)` | red | `udeaPhase2Exit` 1217 ms against a 1000 ms budget |
| `build (windows-latest)` | red | `udeaDaemonBudget` over budget; `ExampleScanTest` x2 on CRLF |
| `clean build under budget` | red | 93867 ms against 90000; 60405 ms two runs earlier |
| `build with the K2 plugin disabled` | red | `udeaDaemonBudget` and `udeaPackGate` over budget |
| `determinism (ubuntu-latest, temurin)` | **green** | |
| `determinism (ubuntu-latest, corretto)` | **green** | |
| `determinism (windows-latest, temurin)` | red | `AgentsMdTest` on CRLF |
| `determinism (windows-latest, corretto)` | red | `AgentsMdTest` on CRLF |
| `game-bridge-mcp conformance` | red | compiling the vendored client — **#171** |
| `the FIR checkers fail a real build` | red | `CheckerProbe.kt` classpath — **#173** |
| `gl tests (xvfb)` | **green** | |
| `migration ledger` | **green** | includes `-p build-logic check` |
| `agent brief matches the tree` | **green** | `udeaVerifyAgentsMd` over the edited `AGENTS.md` |
| `KSP stays incremental` | **green** | |
| `replay-equality` (all four legs + join) | **green** | |

**The art cause is gone from every one of them.** `build (ubuntu-latest)` and `build
(windows-latest)` are the two the issue names first, and both now contain, on a runner that
cloned this repository and staged nothing by hand:

```
> Task :moba:udeaStageCharacterArt
[udeaStageCharacterArt] staged 33 sheet(s) into /home/runner/work/Udea/Udea/moba/assets/sprites
[udeaValidateAssets] 147 asset(s), 0 diagnostic(s)
[udeaPackBundle] assets.udeapak: 147 asset(s), 38 sheet(s), 1 atlas page(s), 101450 bytes
```
```
[udeaStageCharacterArt] staged 33 sheet(s) into D:\a\Udea\Udea\moba\assets\sprites
[udeaPackBundle] assets.udeapak: 147 asset(s), 38 sheet(s), 1 atlas page(s), 101450 bytes
```

`grep -c UDEA0032` over each of those two job logs returns **0**, against 25 on `origin/example`.
The two bundles are the same 101450 bytes on Linux and Windows, which is a free datum about the
staging being byte-faithful across platforms.

**Read that honestly: the art cause is gone from every one of those jobs, and the criterion as
written is not met, because unblocking them revealed failures that were behind the art.** The
evidence that this is what happened, rather than a regression, is in §2 and on the issue: on
`origin/example` (`7942823`, run 33425479983) `build (ubuntu-latest)` and `build
(windows-latest)` both die at `:moba:udeaPackBundle` with `UDEA0032` and their logs contain **no
line at all** for `udeaPhase2Exit`, `udeaDaemonBudget`, `udeaPackGate`, `:udea-assets-compiler:test`
or `-p build-logic test`. Those checks have not run in CI since the characters landed, so there is
no run in which they were green and this branch made them red.

`clean build under budget` deserves its own paragraph, because it is the one that looked like
mine. Four measurements of the same command on the same code, in run order:

| Run | Measured | Budget | Verdict |
|---|---|---|---|
| 33427840110 | 94324 ms | 90000 ms | red |
| 33428671524 | 89897 ms | 90000 ms | green |
| 33429732331 | 60405 ms | 90000 ms | green |
| 33431241769 | 93867 ms | 90000 ms | red |

60.4 s to 94.3 s — a 34-second spread on a build whose inputs did not move. The gate is measuring
the runner it was given, not the tree, and it cannot be compared against `origin/example` because
there the job dies at `UDEA0032` before it measures anything. That is a finding for somebody, and
it is not a thing this branch did: the staging task copies 33 files, which is milliseconds, and
what it genuinely adds to a clean build is the atlas pack the build used to abort before reaching
(101450 bytes, one page, from the cold-build log in §4).

### AC2 — "The fresh-clone proof #154 shipped still passes, and is extended to cover `:moba:build`"

`scripts/verify-art-staging.py`, §1 above: seven steps, all green, on a fresh checkout of `HEAD`.
The documented step it reads out of `docs/art-assets.md` and executes is now `./gradlew
:moba:build` rather than a shell script, and step 4 additionally asserts that
`moba/build/udea/pack/assets.udeapak` exists — so the proof covers compiling, validating, packing
and testing `:moba`, not validation alone.

#154's own assertions survive rather than being dropped: the negative control (now `-x
udeaStageCharacterArt`), the `LICENSE`-covers-the-destination check whose directory list is taken
from the files that actually appeared, the `README`/`LICENSE` licence-name agreement, and the
"README must not name a different step" drift check.

### AC3 — "No paid-pack file is added to the repository. `git log --stat` on the branch shows it."

```
$ git diff --name-only origin/example..HEAD
.gitignore
AGENTS.md
LICENSE
README.md
build-logic/build.gradle.kts
build-logic/src/main/kotlin/dev/wildware/udea/build/CharacterArtStaging.kt
build-logic/src/test/kotlin/dev/wildware/udea/build/CharacterArtStagingTest.kt
docs/art-assets.md
moba/build.gradle.kts
scripts/extract-art.py
scripts/stage-moba-art.py
scripts/verify-art-staging.py
udea-assets-compiler/build.gradle.kts

$ git diff --name-only origin/example..HEAD | grep -icE "\.png$|\.ogg$|sprites/"
0
```

Thirteen files, no binary, nothing under `sprites/`. And the proof checks it from the other end on
every run, against a fresh checkout rather than against the diff:

```
[2/7] a fresh checkout must carry no paid-pack art under moba/assets/sprites
  3 file(s) under moba/assets/sprites, all of them the documented exceptions
```

The three are `champion_idle.png`, `arrow/arrow.png` and `arrow/arrow.udea.kts` — the two
`.gitignore` exceptions plus the asset script that has to sit beside the arrow. A fourth file
appearing there fails that step.

### AC4 — "The chosen approach is commented on this issue with the alternative and how to overturn it"

<https://github.com/wildware-uk/Udea/issues/170#issuecomment-5482932358> — what was chosen, the two
alternatives and why each is worse, what is deliberately unchanged, and the two ways to overturn it
(point the destination at a generated directory, at the stated cost; or re-source the art and
delete the task).

Two further comments carry what the work turned up:
<https://github.com/wildware-uk/Udea/issues/170#issuecomment-5483250078> and
<https://github.com/wildware-uk/Udea/issues/170#issuecomment-5483353169>.

---

## 7. Regenerated files

**None.** No `@Replicated` component is added, removed or reordered by this branch, so
`udea-codegen/net-protocol.lock` and
`udea-codegen/src/test/resources/expected-generated-hashes.txt` are untouched — `git diff
origin/example --stat` lists neither, and `udeaCheckProtocolLock` runs on `check` in the build
above.

---

## 8. What I did not exercise

- **A machine with no `example/` tree.** The staging task's failure path is covered by a unit test
  over a synthetic source tree, not by deleting `example/src/main/resources/assets/sprites` and
  building. That deletion is #142's, and doing it here would have left the worktree unable to
  build anything else.
- **The second character that nests.** `NESTED` has one entry because the committed tree has one
  such character. The unit test drives the nested case (`wizard/Wizard/Wizard-Idle.png`) over a
  synthetic tree, so the *mechanism* is exercised; a second real one is not.
- **A green Windows job.** The staging itself is proven on Windows — the linked run's `build
  (windows-latest)` log carries `[udeaStageCharacterArt] staged 33 sheet(s) into
  D:\a\Udea\Udea\moba\assets\sprites` and packs the identical 101450-byte bundle — but the job
  as a whole is red for two unrelated reasons, so I cannot point at a green Windows job.
- **A machine with no Gradle build cache and no network.** Every run here reused a warm cache.
- **A second sprite tree.** The task is registered by one game. Whether
  `registerCharacterArtStaging()` generalises to a second game is untested, and it is written
  narrowly enough that it should be re-read rather than reused as-is if a second one appears.
