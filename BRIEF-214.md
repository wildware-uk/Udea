14a7907

# #214 - docs for the tree as it is, CI green, ready for master

Branch `issue-214-docs-green`. It was cut from `origin/kmp` at `2046c79`, then merged with `origin/kmp` twice at the lead's request: first at `117f4c8` (#241), then at `bf714e6` (#234). The branch's commits:

- `35d6b4c` Give every forked test and JavaExec JVM its own temporary directory
- `f340fc5` Docs for the tree as it is after the port, ready for master
- `64bfb39` Warm the latency budgets past the JIT, budgets unchanged
- `b281244` Merge of `origin/kmp` (`117f4c8`); a clean merge that touched none of this branch's files
- `21cac4e` AGENTS.md: animation time is derived from a start Tick (#241)
- `e2bc87c` Merge of `origin/kmp` (`bf714e6`, #234). `ci.yml` conflicted: I kept #234's list of the three GL suites and this branch's "Kool's desktop backend" wording. #234's AGENTS.md editor paragraph auto-merged, and both sides' facts are kept. HANDOFF, the skill and the agents files now name `udeaEditorGlTest` wherever they list the GL suites, and `udea-editor` among the modules that need the xvfb run.
- `14a7907` Docs: the verifiers run on `check`, and only the two proofs are run by name. HANDOFF, the skill, and the engineer and reviewer agents listed `udeaVerifyModuleGraph` and `udeaVerifyAgentsMd` among the gates outside `check`. That was already wrong on `origin/kmp`, and my first pass carried it forward. Both are wired into `check`, by `udea.module-graph-check` and `udea.docs-check`, and so is `udeaVerifyContracts`. I searched the six documents for every other outside-`check` claim: the only ones left are `runUdpProof` and `runLaneShot`, which are genuinely left out.

Every log, diff and script named below is in `/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue214/`, and is called the scratchpad for short.

## Evidence command

```
sh gradlew udeaVerifyAgentsMd udeaVerifyContracts && ! grep -nP ':moba:(?!game|desktop|android|web|\*)|CURRENTLY RED|RED TODAY|origin/kmp|Kotlin/LibGDX|example/src/main/resources|gradlew .*(udeaVerifyNoLegacyDependencies|udeaLegacyReport|udeaVerifyMigration)' AGENTS.md HANDOFF.md docs/engineering-standards.md docs/module-graph.md .claude/skills/dev-team/SKILL.md .claude/agents/*.md
```

The two Gradle tasks are the issue's own criterion. The grep finds stale claims the verifiers cannot see:
- old one-project `:moba:` task paths;
- "runUdpProof is red";
- `origin/kmp` as the branch to start from;
- `Kotlin/LibGDX`;
- the deleted `example/src/main/resources` art path;
- a `gradlew` command line that runs one of the three deleted legacy gates.

Mentions of those gates as history, such as AGENTS.md's "The old tree is gone" and `docs/art-assets.md`'s "until #213", are intentionally not matched.

**Green on this branch at `14a7907`** (`scratchpad/issue214/evidence-green-final.log`; `evidence.sh` runs exactly the command above):

```
evidence-exit=0
```

**Red when a stale line comes back.** Each mutation was run with the same script, and its diff and log are saved beside it:

1. `ev-mut1-e2bc87c.diff`, re-run at `e2bc87c`: AGENTS.md's run line goes back to `:moba:run`. The grep prints it, and the command exits 1:
   ```
   -`moba`, `sh gradlew :moba:desktop:run -PdebugPort=<port>` starts it `Offscreen` with the
   +`moba`, `sh gradlew :moba:run -PdebugPort=<port>` starts it `Offscreen` with the
   ```
   ```
   AGENTS.md:237:`moba`, `sh gradlew :moba:run -PdebugPort=<port>` starts it `Offscreen` with the
   evidence-exit=1
   ```
2. `ev-mut2-14a7907.diff`, re-run at `14a7907`: the `udea-editor` row is deleted from the module table.
   ```
   -| `udea-editor` | The editor window: docked ComposeGL panels, the world in Scene and Game tabs (the Scene tab through its own editor camera), buttons that call `editor.*` tools. Debug-only and JVM; only a game's `editor` source set may depend on it (`UDEA-MG-010`) |
   ```
   ```
   Execution failed for task ':udeaVerifyAgentsMd'.
   > udeaVerifyAgentsMd found 1 problem(s):
   ...
       AGENTS.md:1:1: error: [UDEA-DOC-001] settings.gradle.kts includes 'udea-editor', which has no row in the module table. An agent starting cold would not know the module exists.
   ...
   evidence-exit=1
   ```
3. `ev-mut3.diff`, run at `64bfb39` (`docs/contracts/` has not changed since): the contract path fix is reverted without re-locking.
   ```
   -Declared in `udea-agent`'s `src/commonMain` (`AgentToolDef.kt`, `AgentStateSource.kt`), and on
   +Declared in `udea-agent`'s `src/main` (`AgentToolDef.kt`, `AgentStateSource.kt`), and on
   ```
   ```
   Execution failed for task ':udeaVerifyContracts'.
   > udeaVerifyContracts found 1 change(s) to the frozen contracts:
   ...
       docs/contracts/agent-tools.md:1:1: error: [UDEA-FRZ-001] this frozen contract no longer matches the digest in docs/contracts.lock, so its content has changed since it was frozen.
   ```

**Against `origin/kmp`:** the same pattern, run with `git grep -nP ... origin/kmp -- <same files>` at `bf714e6`, matches 85 lines:

| file | matches |
|---|---|
| `.claude/skills/dev-team/SKILL.md` | 37 |
| `.claude/agents/engineer.md` | 20 |
| `.claude/agents/team-lead.md` | 15 |
| `.claude/agents/reviewer.md` | 10 |
| `HANDOFF.md` | 2 |
| `docs/module-graph.md` | 1 |

So the command does not pass on the base.

## Summary

Three pieces of work.

### 1. The docs describe the tree as it is (`f340fc5`, plus `21cac4e` and the doc half of `e2bc87c`)

- **`AGENTS.md`**
  - The engine is now described as Kotlin/Kool/Fleks.
  - The port spec is added as the fourth document to read, and a "Branches" paragraph says `master` is where work integrates, and that `kmp` merged in #214 and is retired.
  - The module table and targets were already current on `kmp` from earlier tickets. `udeaVerifyAgentsMd` keeps them that way.
  - The render-mode rows now say "Kool over LWJGL", and `Windowed` lists `udea-editor` as a user.
  - A new section, "What the engine does today", covers each item with the issue that made it:
    - Transform3D, ModelRenderer and glTF (#240);
    - the one-thread render rule (#224);
    - UiLayer versus CapturedUi (#188);
    - InputKey (#228);
    - `.udealevel` (#191, #192);
    - the UDEA0015 loop ban (#192);
    - `.udearep` format 2 (#232);
    - web shelved (#223, #226).
  - "Driving a running game" names `:moba:desktop:run -PdebugPort`.
  - "The old tree is gone" explains `example-assets/`.
  - The tick model gains the lead's #241 line: animation time is `Animator.clipTime(now)`.
- **`docs/engineering-standards.md`**
  - New §2 subsection "GL and Kool live in `udea-render`, and run on one thread". It gives the GL rule, with `GL_ALLOWED_PROJECTS` as the exempt set and `UDEA-MG-002`/`-009`. It also gives the render-thread rule: `KoolThread` sets `asyncSceneUpdate = false`, and `PresentationControl`/`FrameCaptureSlot` show how another thread hands work over.
  - A matching §8 reject item.
  - §4 now says `udeaVerifyDeterminism` is on `check` and points to `determinism-audit.md`.
  - §7's LibGDX-shim line is generalised.
- **`docs/module-graph.md`**: one stale line.
- **`HANDOFF.md`**: reduced to a pointer. Decision commented on #214.
- **`docs/contracts/agent-tools.md`**: the `src/main` path became `src/commonMain`, and the lock was re-written. This was sanctioned by the lead's cleanup note.
- **`.github/workflows/ci.yml`**:
  - The replay-equality nightly's push arm now names `master` and `kmp`. Decision commented on #214.
  - Comments reworded to match the tree: two `Lwjgl3Backend` mentions now say Kool's desktop backend, the LibGDX `MathUtils$Sin` line is given as an example, and the "old tree included" line now says #213 deleted it.
- **`.claude/skills/dev-team/SKILL.md` and `.claude/agents/{engineer,reviewer,team-lead}.md`**: the post-merge rules. The agents files were added at the lead's direction, and a decision comment is on #214. What changed across the four files:
  - Integration branch: `master` everywhere `kmp` was named for branching from, diffing against or merging into (`origin/master`, `git rev-list ..origin/master`, "merge into `master`").
  - Launcher paths: `:moba:desktop:*` for `run`, `runShot`, `runLaneShot`, `runMatchShot`, `runNetProof`, `runUdpProof` and `runEditor`, with report paths under `moba/desktop/build/...`. `:moba:game:udeaStageCharacterArt` copies from `example-assets/sprites/`.
  - `runUdpProof`: green since #219, and re-run alone before anyone believes a red. "RED TODAY" and "CURRENTLY RED" are gone.
  - Legacy gates: `udeaVerifyNoLegacyDependencies`, `udeaLegacyReport` and `udeaVerifyMigration` are dropped from evidence commands.
  - Gates outside `check`: only `runUdpProof` and `runLaneShot` are listed there now; the verifiers are on `check` (`14a7907`).
  - The GL suites: `udeaEditorGlTest` is named next to `udeaGlTest` and `udeaAgentGlTest` (`e2bc87c`).
  - Reviewer list: item 8b (GL, Kool or a ComposeGL backend outside `udea-render`, or a call made off the render thread), and item 14 is now LibGDX (`UDEA-MG-009`) instead of "depends on `common`".
  - The build command includes `ANDROID_HOME`.
  - No `gh issue create`: out-of-scope notes become issue comments or report lines (the owner's rule).
  - Team lead:
    - the trial merge is `origin/master`, with `--detach`;
    - `git push origin master`;
    - the "merge this into master while I'm here" red-flag row is removed;
    - the WAVE and HANDOFF read-order lines are updated.
  - Engineer:
    - "Kotlin/Kool/Fleks";
    - the "branch is `kmp`" section is replaced by the master rule;
    - the metaspace symptom no longer names `:common:kspKotlin`.
  - `.claude/WAVE.md` is not touched; it is the lead's.

### 2. The `gl tests (xvfb)` SIGBUS, root-caused and fixed (`35d6b4c`)

**Cause.** `udeaGlTest` and `udeaAgentGlTest` run at the same time.
- LWJGL 3.4.3 and `box2d-jni` both extract their `.so` files into a fixed directory under the shared `java.io.tmpdir`.
- LWJGL checks the CRC, then does `Files.copy(REPLACE_EXISTING)`, and takes its lock only after the copy.
- If one JVM rewrites a library while another has it mapped, or is loading it, the dynamic loader faults with SIGBUS in `ld-linux`. That is run 35419154678's `SIGBUS (0x7) ... [ld-linux-x86-64.so.2+0x26bd0]`, followed by exit 134.

**Reproduction outside Gradle.** Two JVMs load the natives, 20 trials each way (`repro/result-*.txt`):
- one shared tmpdir: `crashed(signal)=10 failed(nonzero)=2`;
- separate tmpdirs: `crashed(signal)=0 failed(nonzero)=0`.

**Fix.** Every forked `Test` and `JavaExec` JVM gets its own task's `temporaryDir` as `java.io.tmpdir`. This is `ForkedJvmTmpdir`, applied from `udea.kotlin-base`, so every module gets it.
- End to end over the real GL tasks (`e2e/*-summary.txt`): 5 `.so` files landed in the shared directory before the fix, and 0 after.
- **Rejected:** serialising the two GL tasks. That hides the race for this pair only.
- **Not covered, and the KDoc says so:** JVMs a test starts itself with `ProcessBuilder`, and tasks with `maxParallelForks > 1`.

**Test first.** `ForkedJvmTmpdirTest` builds a two-project fixture on the real convention. Run with `sh gradlew -p build-logic test --tests dev.wildware.udea.build.ForkedJvmTmpdirTest`.
- It was red before the fix (`tdd-red.log`: `2 tests completed, 2 failed`). The second test's name changed after that run, when I rewrote its fixture.
- It is green after (`tdd-green.log`).

Mutations. Each diff was taken from the run, and each log is in the scratchpad.

| mutation | diff | result |
|---|---|---|
| remove the wiring (`mutation-wiring-21cac4e.diff`) | `-tasks.withType<Test>().configureEach { ForkedJvmTmpdir.isolate(this, this) }` `-tasks.withType<JavaExec>().configureEach { ForkedJvmTmpdir.isolate(this, this) }`, each re-added commented `// MUTATION ...` | `every forked JVM gets a temporary directory no other task shares(File) FAILED`, `the directory is there to use when a clean in the same build deleted it(File) FAILED`, `2 tests completed, 2 failed` |
| drop the `mkdirs` (`mutation-mkdirs.diff`) | `-        task.doFirst { directory.mkdirs() }` / `+        // MUTATION: task.doFirst { directory.mkdirs() }` | `the directory is there to use when a clean in the same build deleted it(File) FAILED`, `2 tests completed, 1 failed` |

### 3. The Windows `latency budgets` failures: miscalibration, not a regression (`64bfb39`)

**What I measured.** Two throwaway CI experiment runs, from a branch that has since been deleted:
- 35427097692, on two `windows-latest` runners;
- 35427618170, on six `windows-latest` and two `ubuntu-latest` runners.

**What they showed.** Every budget was timing a JIT that had not finished, not the code:

| budget | before (short warm-up) | after (longer warm-up) |
|---|---|---|
| character mover, 4ms | best 4.5ms after 5 warm-up frames, while the same JVM later ran at 1.8-2.2ms | best 1.91-2.46ms on all eight runners, after 200 frames |
| daemon validate, 300ms | medians 318 and 372ms after 1 warm-up | median of 9, after 5 warm-ups: 151-216ms |
| daemon reload, 500ms | medians 521 and 615ms | median of 9, after 5 warm-ups: 197-412ms |
| Phase 2 first patch, 1000ms | 569-907ms for a JVM's first patch; historical Windows failures at 1048ms (35390467276) and 1478ms (35415510928) | every later patch: 260ms or less (`phase2-samples.txt`) |
| warm scan, 200ms | a single sample of 80-225ms on Windows; the 225ms failed | median of 5, after 3 warm-ups |

**Fix.** The warm-ups are longer, and scan and daemon now gate on a median:
- `WARMUP_FRAMES = 200`;
- `WARMUP_ITERATIONS = 5` with `SAMPLES = 9`;
- `PATCH_WARMUPS = 2` untimed patch round trips;
- `WARMUP_SCANS = 3` with a median of 5.

**No budget number moved.** Each constant's KDoc carries the run ids and numbers.

**Rejected:** widening budgets. The KDocs forbid it, and it would hide a real regression.

**The gate still bites.** The mover mutation was run on this box with `:udea-core:udeaBenchCharacterMover` alone:

| mutation | diff | result |
|---|---|---|
| triple depenetration per substep (`mutation-mover-triple-resolve.diff`) | `+            resolveContacts(state, config, geometry)` twice after the existing call in `CharacterMover.move`'s substep loop | `best 5.224ms ... budget 4.0ms`, `BUILD FAILED` |
| double depenetration (`mutation-mover-double-resolve.diff`) | one extra `+            resolveContacts(state, config, geometry)` | `best 3.642ms`, passes |
| double, with the old `WARMUP_FRAMES = 5` | same diff, and the constant set to 5 | `best 3.380ms`, passes |

The last two rows show that the longer warm-up does not blunt the gate. A 1.7x slowdown passed before this change as well. How much headroom the gate has is a property of the 4ms budget, which this ticket leaves alone.

**Windows margins on the final green CI run.** From job `105870269219` of run 35432807405, at `14a7907` (`ci-105870269219.log`):

| budget | Windows result |
|---|---|
| validate | median 207ms of 300 |
| reload | 269ms of 500 |
| scan | 44ms of 200 |
| mover | best 2.064ms of 4.0 |
| Phase 2 apply | 78ms of 1000 |

The run before, at `e2bc87c`, gave 203, 323, 49, 2.433 and 93 in the same order. Validate has the thinnest margin.

**Decisions commented on #214.** One comment each:
- the SIGBUS root cause;
- the latency calibration;
- the nightly `if:`;
- HANDOFF as a pointer;
- the agents files.

**Left open, as out of scope.** CI job logs say that Node 20 actions (`actions/checkout@v4` and others) are deprecated. The run passes today. That warning is not this ticket's.

## `sh gradlew build`

These runs are at `e2bc87c`, the merged SHA, except where a line says otherwise. They ran on this box, using `scratchpad/issue214/fullbuild.sh` and `gl.sh`. Each output block below is copied from the log file named above it, and `...` marks lines left out.

`ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue`. This was a full build with no exclusions, and the box load was 1.3 when it started. From `fullbuild-e2bc87c.log`:

```
BUILD SUCCESSFUL in 1m 31s
959 actionable tasks: 93 executed, 4 from cache, 862 up-to-date
Configuration cache entry stored.
build-exit=0
```

Most of its tasks were up to date, because the same worktree had built `21cac4e` twenty minutes earlier. That earlier full build, at `21cac4e`, reads `BUILD SUCCESSFUL in 3m 55s` / `958 actionable tasks: 858 executed, 10 from cache, 90 up-to-date` (`fullbuild-21cac4e.log`).

At `14a7907`, where only the documents changed, the same script gives `BUILD SUCCESSFUL in 3s` / `950 actionable tasks: 18 executed, 3 from cache, 929 up-to-date` for the build, and `BUILD SUCCESSFUL in 813ms` for build-logic (`fullbuild-14a7907.log`).

`sh gradlew -p build-logic check`, from `fullbuild-e2bc87c.log.buildlogic`:

```
BUILD SUCCESSFUL in 52s
13 actionable tasks: 3 executed, 10 up-to-date
buildlogic-exit=0
```

**The GL tests, run for real.** This ticket changes the temporary directory of the JVMs these tests fork. The command:

`xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew udeaGlTest --rerun udeaAgentGlTest --rerun udeaEditorGlTest --rerun -Pudea.render.requireGl=true`

From `gl-e2bc87c.log`:

```
> Task :udea-editor:udeaEditorGlTest
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest
...
BUILD SUCCESSFUL in 1m 10s
...
gl-exit=0
```

None of these tests skipped. Summed over each suite's JUnit XML from this run:

| suite | tests | skipped | failures |
|---|---|---|---|
| `udeaGlTest` | 19 | 0 | 0 |
| `udeaAgentGlTest` | 2 | 0 | 0 |
| `udeaEditorGlTest` | 1 | 0 | 0 |

**The latency budgets, alone on this box.** This run was at `64bfb39`, with the four tasks in one invocation (`budgets-local.log`):

```
    warm scan of the example tree: median 35.721381ms over 19 files, samples [35.721381ms, 34.356374ms, 32.351604ms, 40.812332ms, 44.482755ms]
...
    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) best 2.135ms, median 2.176ms, worst 2.777ms, budget 4.0ms
...
    phase 2 exit: typo'd reference rejected in 14ms (median of [442, 14, 11])
...
    phase 2 exit: agent request -> running world observed changed in 266ms
...
    warm reload decision: median 151ms over 9 samples [187, 151, 143, 137, 202, 154, 142, 157, 124]
...
    warm validate of one script: median 122ms over 9 samples [140, 116, 109, 156, 123, 108, 128, 113, 122]
...
BUILD SUCCESSFUL in 35s
```

Each `...` marks lines left out: task headers and `STANDARD_OUT` markers.

**CI, final.** Run https://github.com/wildware-uk/Udea/actions/runs/35432807405 on `14a79071299df3760723b406f0b09e28368b9126`, the SHA at the top: **success**. It has the same job list as the run below, every non-skipped job passed, and its `gl tests (xvfb)` log has no SIGBUS (`ci-35432807405-final.txt`).

**CI on the merged SHA before the docs-only fix.** Run https://github.com/wildware-uk/Udea/actions/runs/35430883868 on `e2bc87c0755f553059423ce138f696b53cfdff63`: **success**. Every job passed except three, which were skipped by design: `kotlin upgrade probe (non-blocking)`, which is nightly only, and the two `replay-equality-nightly` jobs, which run only on schedule, dispatch, or a push to `master`/`kmp`. The passing jobs (`ci-35430883868-final.txt`):
- `build`, on ubuntu-latest and windows-latest;
- `build with the K2 plugin disabled`;
- `build-logic tests`;
- `gl tests (xvfb)`, which runs all three GL suites with no SIGBUS in its log;
- `iOS simulator tests`, on macos-latest;
- `latency budgets`, on ubuntu-latest and windows-latest;
- `determinism`, on four legs;
- `replay-equality`, on three legs plus the join;
- `clean build under budget`;
- `KSP stays incremental`;
- `the FIR checkers fail a real build`;
- `game-bridge-mcp conformance`;
- `agent brief matches the tree`.

Two earlier runs on this branch show as cancelled: 35428473662 at `64bfb39` and 35429979268 at `21cac4e`. In each, my next push cancelled the jobs still running. Every job that had finished in them passed.

## Images

None. This ticket changes documents, build logic and test calibration. Nothing a player or an agent sees on screen changed, so a screenshot would prove nothing. The evidence is the transcripts above and the CI run.

## The issue, criterion by criterion

From the issue:

- **"`sh gradlew build` green on `kmp` with no exclusions; macOS CI iOS job green."**
  - The local full build is green at `e2bc87c`, which is `kmp` at `bf714e6` plus this ticket. It is green again at `14a7907` (see above).
  - CI at `14a7907`: run 35432807405, **success**, and every non-skipped job is green. At `e2bc87c`: run 35430883868, also **success**. Its `iOS simulator tests` job is the macOS one; it runs on `macos-latest`. That job was also green on `64bfb39` and on `21cac4e`.
  - iOS was not built or tested on this Linux box.
- **"`udeaVerifyAgentsMd` and `udeaVerifyContracts` green."** These are the first half of the evidence command. The command is green at `14a7907` and goes red on a stale AGENTS.md row, on a stale path, and on an unlocked contract edit (see above).
- **"`kmp` merged into `master` and pushed."** The lead does this after the review, as the dispatch says. It is not claimed here.

From the lead's dispatch, beyond the issue text:

- **The docs name** Kool, the KMP targets, the moba split, the old tree and LibGDX being gone, `udea-editor`, `udea-physics2d`, Transform3D/ModelRenderer/glTF, CapturedUi, `.udearep` format 2, UDEA0015, InputKey, `.udealevel` and web shelved. See Summary 1. Each one is a line in AGENTS.md that the reviewer can read.
- **The GL rule and the one-thread rule** are in engineering standards §2 and §8.
- **The dev-team skill's branch rules**, and the agents files, have the edits listed in Summary 1. The stale-claim grep in the evidence command is empty over all of them, and it matches 85 lines on `origin/kmp`.
- **HANDOFF.md**: reduced to a pointer, with the decision commented on #214.
- **The `agent-tools.md` path fix and `udeaWriteContractLock`**: done. See Regenerated files.
- **The nightly `if:`**: it now names master, with the decision commented on #214.
- **(a) Windows `CharacterMoverBudgetTest`**: this was miscalibration, not a regression, and I say which in Summary 3, with the run data. The same too-short warm-up was fixed in the daemon, Phase 2 and scan budgets. The Windows `latency budgets` leg is green in the CI runs above.
- **(b) The `gl tests (xvfb)` SIGBUS**: root-caused, reproduced, fixed test-first, and green under xvfb here and in CI. See Summary 2.
- **The lead's #241 line**: it is in AGENTS.md's tick model (`21cac4e`). No doc touched here lists rule ids, so UDEA0016 and UDEA0027 needed no line.
- **The lead's #234 merge**: done in `e2bc87c`, keeping both sides' facts. `udeaEditorGlTest` is now named wherever the docs list the GL suites, and it ran for real under xvfb (above).

## Regenerated files

- `docs/contracts.lock` was rewritten by `sh gradlew udeaWriteContractLock`, as the lead's cleanup note sanctioned. The only line that moved is `agent-tools.md`'s digest (`2771fdac...` to `92d633e7...`), for the one-line path fix `src/main` to `src/commonMain`. That fix is the whole contract diff, so the contract's substance is unchanged.
- `net-protocol.lock` and `expected-generated-hashes.txt` are untouched. No component was added or removed, so no ids moved.
