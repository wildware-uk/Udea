a3dfb1a

# Issue #229: the nightly replay check covers `kmp`, not a retired branch

Branch `issue-229-nightly-branch-trigger`, cut from `origin/kmp` at `3002076`. `a3dfb1a` is the
last change commit, and its tree is identical to `1924e4b`, the fix: `git diff 1924e4b a3dfb1a`
is empty. The two commits between them are the probe and its revert (see Evidence). This brief
is committed on top of `a3dfb1a`.

Every artefact quoted below is under
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue229/ev/`
(written as `ev/` from here on). Blocks marked "spliced" were pasted in by `issue229/splice.py`
from those files. None was typed by hand.

## 1. Evidence

**The evidence run:** https://github.com/wildware-uk/Udea/actions/runs/35401442677

This is a `workflow_dispatch` of `ci.yml` on this branch at `a3dfb1a`. `replay-equality-nightly`
ran all three legs. Each leg checked out `a3dfb1a`, the port tree, and ran the 36000-tick replay
command. Each then stopped at `:moba:compileKotlin`, the authorised D9 red: moba still draws with
LibGDX until #212. So the conclusion is **failure**, and that failure is the true answer about
`kmp` today. The scheduled run on `master` has been passing only because it replays the old tree.

Spliced from `ev/runD-dispatch-job105782328496.log` (ubuntu, temurin), `grep -E` for the checkout,
the command, the failure and the leg time:

```
2026-09-18T22:26:37.4338809Z [command]/usr/bin/git -c protocol.version=2 fetch --no-tags --prune --no-recurse-submodules --depth=1 origin +a3dfb1a253faf6406e66f7f291f37decdc9adea2:refs/remotes/origin/issue-229-nightly-branch-trigger
2026-09-18T22:26:47.0193194Z ##[group]Run ./gradlew :moba:udeaReplayDigest -Pudea.replay.fixture=moba-36000.udearep -Pudea.replay.label=nightly/ubuntu-latest/temurin-17 -Pudea.replay.jvmVendor=Adoptium -Pudea.replay.out=nightly-digests/ubuntu-latest-temurin.udeaeq --stacktrace
2026-09-18T22:27:50.5754786Z > Task :moba:compileKotlin FAILED
2026-09-18T22:27:50.6332465Z BUILD FAILED in 1m 3s
2026-09-18T22:27:51.0726139Z replay-equality-nightly leg wall time: 74s
```

The windows and corretto legs show the same thing in `ev/runD-dispatch-job105782328460.log` and
`ev/runD-dispatch-job105782328528.log`.

### Before: the defect, in two real runs

Each block below is `grep -E 'branch=|nightly'` over a saved `gh run view` listing.

The nightly passed last night on `master` at `409c0442`, the pre-port tree. That is the same SHA
as `refs/heads/example`. Spliced from `ev/before-schedule-master-35323890628.txt`:

```
branch=master sha=409c0442b41a6bfb71e0f92a03d216d1068870ac event=schedule url=https://github.com/wildware-uk/Udea/actions/runs/35323890628
  success  replay-equality-nightly (ubuntu-latest, corretto)
  success  replay-equality-nightly (ubuntu-latest, temurin)
  success  replay-equality-nightly (windows-latest, temurin)
  success  replay-equality-nightly (join)
```

A push to `kmp` at `3002076`, the commit this branch was cut from, skipped the job outright.
Spliced from `ev/before-push-kmp-35397674705.txt`:

```
branch=kmp sha=3002076f3c01ed9a921f809af52d199fc6f03903 event=push url=https://github.com/wildware-uk/Udea/actions/runs/35397674705
  skipped  replay-equality-nightly (${{ matrix.os }}, ${{ matrix.distribution }})
  skipped  replay-equality-nightly (join)
```

### After: the push arm fires when, and only when, the ref matches

I cannot push `kmp`, so I proved the push arm with a probe. Commit `0642d05` swapped
`refs/heads/kmp` in the push arm for this branch's own ref. A push of it started all three legs,
which checked out `0642d05` and failed at the same compile step (`ev/runB-leg-ubuntu-temurin.log`,
line 113 names the SHA). `a3dfb1a` reverts it. Spliced from `ev/runB-probe-35400527981-final.txt`:

```
branch=issue-229-nightly-branch-trigger sha=0642d053135493d3610853b96e53975c8cf000d5 event=push conclusion=failure url=https://github.com/wildware-uk/Udea/actions/runs/35400527981
  failure  replay-equality-nightly (windows-latest, temurin)
  failure  replay-equality-nightly (ubuntu-latest, corretto)
  failure  replay-equality-nightly (ubuntu-latest, temurin)
  failure  replay-equality-nightly (join)
```

The fix itself, pushed to a branch it does not name, skips the job. This is the control: the arm
is not "run on every push". Spliced from `ev/runA-push-fix-35400489693.txt` and
`ev/runC-push-revert-35401417595.txt`:

```
branch=issue-229-nightly-branch-trigger sha=1924e4b92b3ba02c0a5d0a769715aaf1f1a78fe2 event=push url=https://github.com/wildware-uk/Udea/actions/runs/35400489693
  completed skipped  replay-equality-nightly (${{ matrix.os }}, ${{ matrix.distribution }})
  completed skipped  replay-equality-nightly (join)
branch=issue-229-nightly-branch-trigger sha=a3dfb1a253faf6406e66f7f291f37decdc9adea2 event=push url=https://github.com/wildware-uk/Udea/actions/runs/35401417595
  completed skipped  replay-equality-nightly (${{ matrix.os }}, ${{ matrix.distribution }})
  completed skipped  replay-equality-nightly (join)
```

So the chain is:
- the push arm fires on an exact ref match (probe run);
- it does not fire otherwise (runs A and C);
- `refs/heads/kmp` is a live ref that is pushed to (`git ls-remote --heads origin` lists it,
  and it moved from `3002076` to `f998f9c` during this ticket).

The one link not exercised is a push to `kmp` itself, which I may not make. The first merge of
this branch into `kmp` is that run. Its job list should show three `replay-equality-nightly`
legs rather than the unexpanded `${{ matrix.os }}` row that means "skipped".

### Can it fail? The test, and the mutation table

`ReplayEqualityProofTest` asserted that the nightly's condition *contains* `refs/heads/example`.
That test was pinning the defect in place. It is replaced by
`the nightly covers every integration branch and names no other`, in `:udea-replay:jvmTest`. The
test reads the integration branches from the one place `ci.yml` already lists them: the
`clean-build-base.sh kmp master` call in `clean-build-budget` (#218). It checks both directions:
- every branch the push arm names is on that list;
- every listed branch except `master`, which the cron reaches, is named.

Its first version, before I added the reverse check, went red against the unmodified `ci.yml`
before the fix was applied (`ev/red-before-fix.xml`). `M0-base` below repeats that against the
final test. The full table was run on the final files by
`issue229/run-variants.sh`. Each variant is written by `issue229/variants.py` from the good
`ci.yml`, and each diff below is against that good copy. The summary is spliced from
`ev/mutations/SUMMARY.txt`. `issue229/summarise.sh` cuts each assertion message at 200 characters:

```
== good: all passed
== M0-base: 21 tests completed, 1 failed
     the nightly covers every integration branch and names no other()[jvm] FAILED
     message="org.opentest4j.AssertionFailedError: the nightly runs on a push to 'example', which is not one of the integration branches this workflow names ([kmp, master]). A branch nothing is pushed to i
== M1-retired-branch: 21 tests completed, 1 failed
     the nightly covers every integration branch and names no other()[jvm] FAILED
     message="org.opentest4j.AssertionFailedError: the nightly runs on a push to 'example', which is not one of the integration branches this workflow names ([kmp, master]). A branch nothing is pushed to i
== M2-naked-push: 21 tests completed, 1 failed
     the nightly covers every integration branch and names no other()[jvm] FAILED
     message="org.opentest4j.AssertionFailedError: the nightly's condition admits a push without naming a branch, so the ten-times recording would be replayed on every push to every branch:&#10;      githu
== M3-kmp-off-list-only: 21 tests completed, 1 failed
     the nightly covers every integration branch and names no other()[jvm] FAILED
     message="org.opentest4j.AssertionFailedError: the nightly runs on a push to 'kmp', which is not one of the integration branches this workflow names ([master]). A branch nothing is pushed to is a claus
== M4-push-arm-deleted-today: 21 tests completed, 1 failed
     the nightly covers every integration branch and names no other()[jvm] FAILED
     message="org.opentest4j.AssertionFailedError: 'kmp' is an integration branch this workflow names, and the nightly neither runs on a push to it nor can reach it by `schedule`, which GitHub runs only on
== C1-comment-only: all passed
== C2-after-214: all passed
```

`M0-base` is `ci.yml` exactly as `3002076` has it, so it is the defect itself. Its diff is the
fix reversed, 3052 bytes, at `ev/mutations/M0-base.diff`. Every other diff is spliced from
`ev/mutations/<variant>.diff`:

**M1-retired-branch**, red (named branch not on the list):
```diff
@@ -1768,7 +1768,7 @@
     if: >-
       github.event_name == 'schedule' ||
       github.event_name == 'workflow_dispatch' ||
-      (github.event_name == 'push' && github.ref == 'refs/heads/kmp')
+      (github.event_name == 'push' && github.ref == 'refs/heads/example')
     runs-on: ${{ matrix.os }}
     strategy:
       fail-fast: false
```

**M2-naked-push**, red (push admitted with no branch):
```diff
@@ -1768,7 +1768,7 @@
     if: >-
       github.event_name == 'schedule' ||
       github.event_name == 'workflow_dispatch' ||
-      (github.event_name == 'push' && github.ref == 'refs/heads/kmp')
+      github.event_name == 'push'
     runs-on: ${{ matrix.os }}
     strategy:
       fail-fast: false
```

**M3-kmp-off-list-only**, red (`kmp` retired from the list and still named):
```diff
@@ -999,7 +999,7 @@
         run: |
           set -euo pipefail
           head=$(git rev-parse HEAD)
-          base=$(bash .github/scripts/clean-build-base.sh kmp master)
+          base=$(bash .github/scripts/clean-build-base.sh master)
           git worktree add --detach "$RUNNER_TEMP/base" "$base"
           chmod +x "$RUNNER_TEMP/base/gradlew"
           echo "UDEA_CLEAN_BUILD_BASE=$RUNNER_TEMP/base" >> "$GITHUB_ENV"
```

**M4-push-arm-deleted-today**, red (`kmp` listed and covered by nothing):
```diff
@@ -1767,8 +1767,7 @@
     # run against the port tree was.
     if: >-
       github.event_name == 'schedule' ||
-      github.event_name == 'workflow_dispatch' ||
-      (github.event_name == 'push' && github.ref == 'refs/heads/kmp')
+      github.event_name == 'workflow_dispatch'
     runs-on: ${{ matrix.os }}
     strategy:
       fail-fast: false
```

**C1-comment-only**, green (control: the retired ref only in a YAML comment):
```diff
@@ -1765,6 +1765,7 @@
     # Never on a pull request. `workflow_dispatch` is here so the job can be proven to run without
     # waiting a day for the cron; it is how issue #165's own evidence was produced, and how #229's
     # run against the port tree was.
+    # (github.event_name == 'push' && github.ref == 'refs/heads/example')
     if: >-
       github.event_name == 'schedule' ||
       github.event_name == 'workflow_dispatch' ||
```

**C2-after-214**, green (control: the configuration #214 allows, with `kmp` off the list and the
push arm deleted):
```diff
@@ -999,7 +999,7 @@
         run: |
           set -euo pipefail
           head=$(git rev-parse HEAD)
-          base=$(bash .github/scripts/clean-build-base.sh kmp master)
+          base=$(bash .github/scripts/clean-build-base.sh master)
           git worktree add --detach "$RUNNER_TEMP/base" "$base"
           chmod +x "$RUNNER_TEMP/base/gradlew"
           echo "UDEA_CLEAN_BUILD_BASE=$RUNNER_TEMP/base" >> "$GITHUB_ENV"
@@ -1767,8 +1767,7 @@
     # run against the port tree was.
     if: >-
       github.event_name == 'schedule' ||
-      github.event_name == 'workflow_dispatch' ||
-      (github.event_name == 'push' && github.ref == 'refs/heads/kmp')
+      github.event_name == 'workflow_dispatch'
     runs-on: ${{ matrix.os }}
     strategy:
       fail-fast: false
```

C2 caught a mistake of mine. My first version of the #165 test asserted `'push'` in the
condition, which would have failed the very #214 cleanup the workflow comment says is allowed.
That assertion is gone, and the reverse check (M4) does its job without blocking #214.

## 2. Summary

**What changed.** In `.github/workflows/ci.yml`, the `replay-equality-nightly` push arm now names
`refs/heads/kmp` instead of the retired `example` branch. A new section in the job's comment
block, `Which branch this covers, and what happens at #214`, says which branch the job covers,
why a schedule cannot, the run ids that showed it, and what #214 changes. In
`ReplayEqualityProofTest`, the assertion that pinned `example` is replaced as described above,
and the `if:` extraction moves into a shared `nightlyCondition`.

**Decided: the push arm, not the schedule.** GitHub runs a cron only on the default branch, and
only from that branch's copy of `ci.yml`. So nothing edited on `kmp` changes what tonight's cron
replays, and a checkout `ref:` override would be another clause that looks right and never fires.
The push arm fires on every merge into `kmp`, which is more often than nightly. Commented on the
issue: https://github.com/wildware-uk/Udea/issues/229#issuecomment-5736816738

**Rejected:** a `workflow_dispatch` plus a manual cadence (it depends on someone remembering);
spelling `kmp` as a literal in the test (it goes stale the same way); deleting the `example`
branch (not mine to decide, see below).

**Decided: one file outside `.github/`.** The lead said nothing in `udea-*`. But the only test
fencing this trigger pinned the retired branch. Changing the workflow alone turns
`:udea-replay:jvmTest` red, which is a baseline-green task. The edit is test-only, in a module
neither #212 nor #224 touches. Commented:
https://github.com/wildware-uk/Udea/issues/229#issuecomment-5736816871

**`example` branch.** It is still on origin at `409c0442`, identical to `master`. Nothing in
`.github/` names it now. My reading is that it could be deleted once the owner agrees, since
`clean-build-base.sh` already skips missing branches. I have not touched it.

**Stale references to the retired ref elsewhere.** Command:
`grep -rn "refs/heads/example" --exclude-dir=.git .` Before the change, it matched:
- `.github/workflows/ci.yml` (fixed);
- `ReplayEqualityProofTest.kt` (fixed);
- `CleanBuildBaseScriptTest.kt:52`, which pushes that ref into a throwaway local bare repo to
  model a retired branch that is still listed. That is deliberate, so I left it;
- `BRIEF-165.md` and `BRIEF-218.md`, which are historical records;
- `.claude/WAVE.md:292`, which is the lead's file.

`HANDOFF.md` line 3 says it was written "on branch `example`". That is true history, so I left it.

## 3. `sh gradlew build --continue`

**Tasks this ticket turned green:** none against the baseline. My change would have turned
`:udea-replay:jvmTest` red, which is why the test changed with it.
**Baseline failures, unchanged:** `:moba:compileKotlin`, the only failed task.

Run on `a3dfb1a`'s tree, which is identical to `1924e4b`, once three consecutive samples
showed no other Gradle client. Spliced from `issue229/build.log`, lines 1, 1357, 1473-1478
and 1492-1495:

```
starting build at 22:16:20, quiet samples=3, load: 5.54 13.51 13.43
[...]
> Task :moba:compileKotlin FAILED
[...]
FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':moba:compileKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.btapi.BuildToolsApiCompilationWork
   > Compilation error. See log for more details
[...]
BUILD FAILED in 1m 39s
760 actionable tasks: 451 executed, 201 from cache, 108 up-to-date
Configuration cache entry stored.
gradle exit 1
```

`grep -c '^> Task .* FAILED' build.log` gives 1. `:udea-replay:jvmTest` executed at line 1442.
`udeaVerifyContracts` and `udeaVerifyAgentsMd` ran at lines 103-104. The last line,
`gradle exit 1`, is written by my script. No GL code is touched, so I did no xvfb run.

## 4. CI jobs affected

**Affected:** `replay-equality-nightly` and, through `needs:`, `replay-equality-nightly-join`.
It now also runs on a push to `kmp`: three legs plus the join on every merge. The measured leg
cost is in the job's own comment block. Today each leg stops at the compile red: 74s, 79s and
171s on the dispatch run, and 345s on the probe run's ubuntu/temurin leg. The `if:` still admits `schedule` and `workflow_dispatch`, and never `pull_request`.

**Deliberately left alone:** every other job, including `clean-build-budget`, whose
`clean-build-base.sh kmp master` list the test now reads (read, not changed).

**Against the baseline.** The dispatch run's job table (`ev/runD-dispatch-35401442677-final.txt`)
was compared with the `kmp` push run at `3002076` (`ev/before-push-kmp-35397674705.txt`). The
diff is `ev/base-vs-dispatch.diff`:
- the four nightly jobs now run (red at the D9 compile);
- `kotlin upgrade probe` runs, because the event is a dispatch;
- `latency budgets (windows-latest)` went from success to failure.

That last one is not mine. It failed on `:udea-assets-compiler:udeaDaemonBudget`
(`ev/runD-latency-windows.log:848`). On the probe run it failed on a different budget,
`:udea-agent-host:udeaPhase2Exit` (`ev/runB-latency-windows.log:833`). That is the same budget
that failed on the lead's baseline run 35390467276
(`ev/baseline-35390467276-latency-windows.log:877`), where the lead's contract lists this job as
red. A different budget fails from run to run, which is the load signature. My diff touches
neither module. `sh gradlew :udea-assets-compiler:udeaDaemonBudget` alone on this box, at load
7.1, gave `BUILD SUCCESSFUL in 26s` (`ev/local-daemon-budget.log`).

## 5. Acceptance criteria

1. **The nightly runs against `kmp` while the port is in flight, shown doing so.** Shown in
   three parts:
   - dispatch run 35401442677 ran all three legs on the port tree at `a3dfb1a`;
   - probe run 35400527981 showed the push arm firing on an exact ref match;
   - runs 35400489693 and 35401417595 showed it staying off for a ref it does not name.

   The first push of `kmp` after merge is the remaining confirmation (section 1).
2. **No trigger references `refs/heads/example`, and nothing else in `.github/` does.**
   `grep -rn "refs/heads/example" .github/` prints nothing, exit 1. Enforced by
   `ReplayEqualityProofTest` (M0, M1).
3. **#214 is stated in the workflow.** The comment above the `if:` says the configuration is
   already correct for that day and why. C2 proves the test allows the cleanup.
4. **A short note in the job's comment block on which branch it covers and why.** The new
   section `Which branch this covers, and what happens at #214`, in the block's own `##` style,
   with the two run ids and SHAs as its measurement.

## 6. At #214

When `kmp` merges into `master` and is retired, no edit is required.
- `refs/heads/kmp` stops existing, so the push arm stops matching.
- `schedule` runs on `master`, which is by then the ported tree, so the cron is the coverage
  again.
- `clean-build-base.sh` already skips a missing `kmp`.

If someone removes `kmp` from that call, the test requires the push arm to stop naming it (M3),
and it accepts the arm being deleted (C2). If the push arm is deleted while `kmp` is still
listed, the test goes red (M4). That is the #229 defect in its other form.

## 7. Regenerated files

None. No component, protocol lock or generated hash is touched.
