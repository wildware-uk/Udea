# BRIEF-170 — a clone builds `:moba`, and nobody types anything

`d6a04a4` — the merge of `origin/example` into this branch, and the SHA the linked Actions run
below was built from. `HEAD` is that plus further commits of this file alone; a self-naming SHA is
impossible, since writing one into a file changes it. `git diff --stat d6a04a4..HEAD` is
`BRIEF-170.md` and nothing else.

Branch `issue-170-moba-art-clean-clone`, branched from `origin/example` at `7942823` and merged up
to `efab1d0` (which is `origin/example` with #171 and #173 in it). The merge was clean; `git diff
--name-only origin/example..HEAD` is this branch's fourteen files and nothing of theirs.

Every `logs/...` filename below is a real file in
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/a3ee2737-1b26-4f77-96b3-6805f45c796f/scratchpad/logs/`
on this box; every block quoted from one is spliced, not retyped.

The local transcripts below were produced at `e0f4de6` and re-run at `d6a04a4` after the merge;
where the numbers differ the `d6a04a4` ones are quoted. `e0f4de6` to `a966e29` is three comment
corrections and nothing else — `git diff e0f4de6..a966e29` is four hunks, every one inside a
comment — and `a966e29` to `d6a04a4` is the merge, which brings in #171 and #173 and touches none
of this branch's files.

---

## 1. The evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem python3 scripts/verify-art-staging.py
```

It creates a **fresh checkout of `HEAD`** in a temporary worktree, asks the validator for the old
failure back, checks that the checkout carries no paid-pack art, runs the command
`docs/art-assets.md` documents, and then checks what that produced. Its whole run at `d6a04a4`,
spliced from `logs/issue170-evidence-GREEN.log`:

```
repository: /srv/ssd1/workspace/Udea/.claude/worktrees/agent-aae42d941ef837a54
verifying commit: d6a04a4 (a fresh checkout of HEAD, not the working tree)
clean tree: /tmp/udea-art-verify-03rdurk3/clean
documented step, from docs/art-assets.md:
    ./gradlew :moba:build

[1/7] negative control: :moba:udeaValidateAssets must FAIL with -x udeaStageCharacterArt
  FAILED as required, 25 x UDEA0032

[2/7] a fresh checkout must carry no paid-pack art under moba/assets/sprites
  3 file(s) under moba/assets/sprites, all of them the documented exceptions

[3/7] running the documented step in the clean tree
  ...
  BUILD SUCCESSFUL in 8s
  66 actionable tasks: 23 executed, 23 from cache, 20 up-to-date

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

Red first. The original run's report has since been overwritten by later green runs, so rather
than quote a file that no longer exists, the stub was **re-enacted** at `d6a04a4` in a throwaway
worktree — `PLAN` emptied, the copy and the missing-sheet report removed from `stage()` — and the
result saved: `logs/issue170-red-reenacted.log` and `logs/issue170-red-reenacted.xml`. Same three
tests, same three reasons:

```
CharacterArtStagingTest > the plan stages every sprite the game names that a clone does not carry() FAILED
CharacterArtStagingTest > staging fails and names the sheet when the committed art is not there() FAILED
CharacterArtStagingTest > staging copies every planned sheet into the destination tree() FAILED
6 tests completed, 3 failed
> Task :test FAILED
BUILD FAILED in 8s
```

and, out of the XML beside it:

```
    org.opentest4j.AssertionFailedError: the build stages a different set of sheets from the one moba/assets/**/*.udea.kts names. Every sprite the game names has to be either committed or staged; one that is neither is a UDEA0032 on every clean clone. ==> expected: <[sprites/orc/Orc-Attack01.png, sprites/orc/Orc-Death.png, sprites/orc/Orc-Hurt.png, sprites/orc/Orc-Idle.png, sprites/orc/Orc-Walk.png, sprites/orc_elite/orc_elite_attack01.png, sprites/orc_elite/orc_elite_attack02.png, sprites/orc_elite/orc_elite_deat
```

(elided by the report writer, not by me — the list runs to all 33.)

That expected list is **derived from the real asset scripts**, not written next to the plan: the
test walks `moba/assets` for `spritePath = "sprites/..."`, subtracts the two committed exceptions,
and demands the plan equal what is left. A seventh character therefore fails a test that names the
sheets nobody wired up. The exception list is itself checked against the tree, so a wrong entry
cannot quietly excuse a sheet the build should have staged.

Green after, at `d6a04a4` in this worktree: `sh gradlew -p build-logic test --tests
'*CharacterArtStagingTest*'` is `BUILD SUCCESSFUL`, and the Windows artefact from the linked run's
predecessor says `6 tests, 0 failures, 100% successful`.

The two behaviour tests drive the real task over a synthetic tree — copying, including the nested
`wizard/Wizard/` case, and the failure path when a sheet is not there. The rest of the wiring is
proved by the evidence command rather than by a unit test, because "the build stages the art before
anything reads the asset root" is a claim about a task graph, and the honest way to check it is to
build a clean checkout.

---

## 4. `sh gradlew build`

`sh gradlew build`, no exclusions, at `d6a04a4` (`logs/issue170-build-merged-final.log`):

```
BUILD SUCCESSFUL in 12s
205 actionable tasks: 28 executed, 25 from cache, 152 up-to-date
```

**That green is partly a replay, and here is which part.** The wall-clock gates in it were
`UP-TO-DATE` or `FROM-CACHE`, because they had passed in the solo run below. So the honest
transcript is the cold one. `sh gradlew clean build --no-build-cache` at the same commit, started
at load 6.96 on a box shared with another project (`logs/issue170-build-cold-merged.log`):

```
> Task :udea-core:udeaBenchCharacterMover FAILED
> Task :udea-assets-compiler:udeaDaemonBudget FAILED
BUILD FAILED in 1m 21s
179 actionable tasks: 168 executed, 11 up-to-date
```
```
    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) median 4.954ms, budget 4.0ms
    warm reload decision: median 786ms over 4 samples [1473, 391, 451, 786]
    warm validate of one script: median 433ms over 4 samples [35, 632, 433, 221]
```

Two of the four tasks the developer contract names as load-sensitive, and the same two the earlier
cold build at `e0f4de6` failed. Re-run alone with `--rerun-tasks` a minute later
(`logs/issue170-budgets-solo-merged.log`):

```
    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) median 2.551ms, budget 4.0ms
    warm reload decision: median 145ms over 4 samples [199, 145, 141, 132]
    warm validate of one script: median 136ms over 4 samples [10, 165, 136, 110]
BUILD SUCCESSFUL in 17s
```

4.954 ms to 2.551 ms and 433 ms to 136 ms, same commit, minutes apart. It is contention inside the
build's own parallelism, not anything this branch does — these gates are *on* `check`
(`udea-assets-compiler/build.gradle.kts` line 126 wires `udeaDaemonBudget` into it), which is why
every full build meets them, and the dev-team contract names this exact set as the one to re-run
alone before concluding anything. Nothing in the diff is reachable from `CharacterMoverBudgetTest`
or `DaemonLatencyBudgetTest`: both drive their own fixtures.

The same pattern held three times in this session on two commits (`logs/issue170-budgets-alone.log`,
`logs/issue170-budgets-solo-final.log`, `logs/issue170-budgets-solo-merged.log`), and `dev-171`
reported it independently on a different branch.

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
at line 345. It is run by `sh gradlew -p build-logic check`, which on this box gives `248 tests completed,
2 failed` (`logs/issue170-buildlogic-final.log`).

The two are `KotlinPinCheckTest`, and they are the box: there is **no JDK 17 installed here**, and
those two spin up a TestKit build that asks for one. Out of
`logs/issue170-kotlinpin-here.xml`, re-captured at `d6a04a4`:

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

**Run: <https://github.com/wildware-uk/Udea/actions/runs/33432681337>** (`d6a04a4`, this branch
merged up to `origin/example`).

| Job | Verdict | Why, if red |
|---|---|---|
| `build (ubuntu-latest)` | red | `udeaBenchCharacterMover` 4.873 ms against a 4.0 ms budget |
| `build (windows-latest)` | red | `udeaDaemonBudget` over budget; `ExampleScanTest` x2 on CRLF |
| `build with the K2 plugin disabled` | red | `udeaDaemonBudget` warm reload 960 ms, warm validate 561 ms |
| `determinism (windows-latest, temurin)` | red | `AgentsMdTest` on CRLF |
| `determinism (windows-latest, corretto)` | red | `AgentsMdTest` on CRLF |
| `the FIR checkers fail a real build` | red | its own new guard, on a Kotlin daemon that died at startup |
| `clean build under budget` | **green** | |
| `game-bridge-mcp conformance` | **green** | was red before #171 merged |
| `determinism (ubuntu-latest, temurin)` | **green** | |
| `determinism (ubuntu-latest, corretto)` | **green** | |
| `gl tests (xvfb)` | **green** | |
| `migration ledger` | **green** | includes `-p build-logic check` |
| `agent brief matches the tree` | **green** | `udeaVerifyAgentsMd` over the edited `AGENTS.md` |
| `KSP stays incremental` | **green** | |
| `replay-equality` (all four legs + join) | **green** | |

**The art cause is gone from every one of them.** `grep -c UDEA0032` over the `build
(ubuntu-latest)`, `build (windows-latest)` and `build with the K2 plugin disabled` job logs
returns **0** on this run, against 25 on `origin/example`. Both `build` legs staged the art
themselves, on a runner that cloned this repository and did nothing by hand:

```
[udeaStageCharacterArt] staged 33 sheet(s) into /home/runner/work/Udea/Udea/moba/assets/sprites
[udeaValidateAssets] 147 asset(s), 0 diagnostic(s)
[udeaPackBundle] assets.udeapak: 147 asset(s), 38 sheet(s), 1 atlas page(s), 101450 bytes
```
```
[udeaStageCharacterArt] staged 33 sheet(s) into D:\a\Udea\Udea\moba\assets\sprites
[udeaPackBundle] assets.udeapak: 147 asset(s), 38 sheet(s), 1 atlas page(s), 101450 bytes
```

(Those two are from run 33431241769's `build` legs, which is where a Windows `udeaPackBundle` line
survives; on this run the Windows leg staged the same 33 sheets and failed later, on the two
reasons in the table. The bundles are the same 101450 bytes on Linux and Windows, which is a free
datum about the staging being byte-faithful across platforms.)

**Read the table honestly: the criterion as written is not met, because unblocking these jobs
revealed failures that were behind the art.** The evidence that this is what happened, rather than
a regression, is checkable: on `origin/example` at `7942823` (run 33425479983) `build
(ubuntu-latest)` and `build (windows-latest)` both die at `:moba:udeaPackBundle` with `UDEA0032`,
and their logs contain **no line at all** for `udeaPhase2Exit`, `udeaBenchCharacterMover`,
`udeaDaemonBudget`, `udeaPackGate`, `:udea-assets-compiler:test` or `-p build-logic test`. Those
checks have not run in CI since the characters landed, so there is no run in which they were green
and this branch made them red.

Two of the six reds have moved since I started, which is the shape of the wave working:
`game-bridge-mcp conformance` was red on my first three runs and is green here, because **#171
merged**. `the FIR checkers fail a real build` is still red, but for a different reason than
before: **#173 merged** and its rewritten job now says

```
##[error]the checkers fired, but :udea-assets also failed to compile for reasons that are not checkers, so the positions below would be read off a compilation that was
e: The daemon has terminated unexpectedly on startup attempt #1 with error code: 0.
```

— which is that job's own new guard, doing its job, over a Kotlin daemon that died on the runner.
Not the art, and not mine to chase.

`clean build under budget` deserves its own paragraph, because it is the one that looked like
mine. Four measurements of the same command on the same code, before this run:

| Run | Measured | Budget | Verdict |
|---|---|---|---|
| 33427840110 | 94324 ms | 90000 ms | red |
| 33428671524 | 89897 ms | 90000 ms | green |
| 33429732331 | 60405 ms | 90000 ms | green |
| 33431241769 | 93867 ms | 90000 ms | red |

60.4 s to 94.3 s — a 34-second spread on a build whose inputs did not move; it is green again on
the linked run. The gate is measuring the runner it was given, not the tree, and it cannot be
compared against `origin/example` because there the job dies at `UDEA0032` before it measures
anything. That is a finding for somebody, and it is not a thing this branch did: the staging task
copies 33 files, which is milliseconds, and what it genuinely adds to a clean build is the atlas
pack the build used to abort before reaching (101450 bytes, one page).

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
BRIEF-170.md
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

Text only: no binary, and nothing under `sprites/`. And the proof checks it from the other end on
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
