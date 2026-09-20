8484d0c

# BRIEF-181 — the clean-build gate compares a commit with its base on the same runner

Branch `issue-181-clean-build-budget`, off `origin/example` at `f08db40`.
Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a44316c8736a5a6d5`.

`8484d0c` is the last commit of the change. Anything after it only adds this brief. The CI runs
below ran on `eb02425`, and two commits follow it. `eeabfdb` changes only visibility: in
`CleanBuildComparison` and `UdeaCleanBuildVerdictTask`, `public` becomes `internal`. `8484d0c`
changes only comments: two sentences that stated counts are rewritten, one in that KDoc and one
in the `ci.yml` job comment. Neither commit changes the steps the job runs or the rule it applies.
The local results (evidence command, mutations, both builds) were re-run on `8484d0c`. The local
verdicts on CI samples were re-run on `eeabfdb`. `origin/example` has since moved to `ec628e5` (#191). `git merge-tree`
of the two merges with no conflicts. I have not merged it into the branch.

Evidence files live under `/srv/ssd1/workspace/Udea/build/issue181-evidence/`, called `EV/` below.

---

## 1. The evidence command

```
sh gradlew -p build-logic test --tests dev.wildware.udea.build.CleanBuildComparisonTest
```

(On this box, prefix `JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem`.) `build-logic` is an
included build, so the root `build` does not run its tests. CI runs them in the `migration
ledger` job's `./gradlew -p build-logic check` step.

**Green on `8484d0c`**: `EV/evidence-green.log` ends with

```
BUILD SUCCESSFUL in 10s
12 actionable tasks: 12 executed
```

and its report `EV/evidence-green-TEST-CleanBuildComparisonTest.xml` shows `tests="8"
failures="0" errors="0" skipped="0"`.

**Red when the feature is reverted.** Mutation M4 below puts back the old rule, a fixed
90 000 ms line, inside the new code. Three of the eight tests fail. Each mutation was applied,
run and restored by a script. Each file under `EV/mutations/` holds the literal `git diff` of the
mutation and the failing test names read from that run's JUnit XML. Hunks below are copied from
those files:

| Mutation | Diff (from `EV/mutations/<name>.txt`) | Failing tests |
|---|---|---|
| M1-median | `-        return times.min()` / `+        return times.sorted()[times.size / 2]` | `head samples slowed by the machine do not fail the head()` |
| M2-mean | `-        return times.min()` / `+        return times.reduce { a, b -> a + b } / times.size` | `head samples slowed by the machine do not fail the head()` |
| M3-strict-tolerance | `-        return if (head / base <= TOLERANCE) Verdict.Within(base, head) else Verdict.Regressed(base, head)` / `+        return if (head / base < TOLERANCE) Verdict.Within(base, head) else Verdict.Regressed(base, head)` | `the tolerance is inclusive at the line and fails one millisecond past it()` |
| M4-absolute-90s (the old rule) | `-        return if (head / base <= TOLERANCE) Verdict.Within(base, head) else Verdict.Regressed(base, head)` / `+        return if (head.inWholeMilliseconds <= 90_000L) Verdict.Within(base, head) else Verdict.Regressed(base, head)` | `the summary states both estimates, the ratio and the verdict()`, `a head slower in every sample fails, on either runner()`, `the tolerance is inclusive at the line and fails one millisecond past it()` |
| M5-skip-malformed | `-        .map { row ->` `-            val match = requireNotNull(ROW.matchEntire(row.trim())) {` `-                "'$row' is not a clean-build sample; expected '<base\|head> <whole milliseconds>'"` `-            }` / `+        .mapNotNull { row ->` `+            val match = ROW.matchEntire(row.trim()) ?: return@mapNotNull null` | `samples parse from the file the workflow writes, and a malformed row is named()` |
| M6-no-minimum | `-        require(times.size >= MIN_SAMPLES_PER_SIDE) {` / `+        require(times.isNotEmpty()) {` | `too few samples of either side is refused rather than judged()` |

The workflow half has its own fence, `:udea-gradle:CleanBuildBudgetJobTest`, which reads the
real `ci.yml`. Run against `origin/example`'s `ci.yml` (copied into the worktree, then put back),
both of its tests failed. `EV/jobtest-red-on-example-ciyml.log`:

```
CleanBuildBudgetJobTest > the job times clean builds of both the base checkout and the head() FAILED
```
…
```
CleanBuildBudgetJobTest > the verdict is the comparison task, run after the samples are taken() FAILED
```
…
```
2 tests completed, 2 failed
```

With the branch's `ci.yml` it passes: `EV/TEST-dev.wildware.udea.gradle.ci.CleanBuildBudgetJobTest.xml`.

---

## 2. Summary

**What was wrong.** The job timed one `clean udeaAssemble` in a fresh runner against 90 000 ms.
I saved 51 past logs of the job (`EV/history/`). The 41 that reached a measurement ranged from
60 405 to 102 453 ms. Then I ran three probe workflows on this branch. The probe workflow was temporary
and is deleted in `eb02425`; its runs and logs remain in `EV/probe1..3/`. They answer the
question the issue asks: is this a badly conditioned measurement of a well-behaved build, or an
honest measurement of a build that varies? It was both, and one part cannot be fixed with a
better estimator:

- **Warm-up.** Probe 1 (run 35110741713): on one runner, the first clean build took 83 843–97 368 ms
  and the same command repeated took 18 303–26 006 ms. Most of the old number was cold daemons
  and the JIT.
- **Different machines under one label.** Probes 2 and 3 (runs 35112031164, 35112291861) drew AMD
  EPYC 9V74, 7763 and 9V45 and Intel Xeon Platinum 8573C and 6973P-C. The fastest warm builds of
  identical work ranged from 17 017 to 25 623 ms, about 1.5x. No estimator over one job's samples
  can take that out of an absolute number.

**What I did.** The job now checks out the commit's base in a second worktree on the same runner.
The base is the merge base with `origin/example`, or `HEAD^1` when that is `HEAD` itself. The job
warms both checkouts and records the head's first cold build for information only. It then takes
six samples per side in base/head/head/base order. Each sample is `clean udeaAssemble
--no-build-cache` with the configuration-cache entry deleted first. `udeaCleanBuildVerdict`
passes the samples to `CleanBuildComparison.judge`, which fails when the head's fastest sample
is more than **1.10x** the base's.

- `build-logic/.../CleanBuildComparison.kt`: the rule (parse, fastest-of, tolerance, summary). Unit-tested.
- `build-logic/.../UdeaCleanBuildVerdictTask.kt` and `dev.wildware.udea.clean-build-budget.gradle.kts`: the task, applied at the root. Not on `check`.
- `.github/workflows/ci.yml`: the job rewritten. The dispatch input `clean_build_budget_ms` is replaced by `clean_build_plant_functions`, which plants N functions in the head's `udea-core` to prove the gate goes red.
- `udea-gradle/.../CleanBuildBudgetJobTest.kt`: pins the job's shape (both sides timed, then the verdict task on the samples).
- `docs/budgets.md`: the clean-build section rewritten, with the reasoning, the numbers and what the gate does not catch. #184's rows are untouched.

**Decisions** (each commented on the issue:
https://github.com/wildware-uk/Udea/issues/181#issuecomment-5699741651):

- *Compare with the base, don't improve the estimator.* An estimator fixes warm-up but not the
  1.5x hardware spread. Rejected: warm-up plus the fastest of N against 90 s, a wider budget
  (ruled out by the issue), and a pinned reference commit (every legitimate code growth would
  force a re-pin, which is widening by another name).
- *Fastest sample, not median.* Disturbance only adds time. M1 and M2 show the tests tell them apart.
- *Tolerance 1.10.* On identical work, the ratios in probes and real runs fell between 0.971 and 1.029
  (section 5). The planted regressions measured 1.053 (+1 500 functions), 1.061 (+3 000),
  1.118 (+6 000) and 1.273 (+12 000). So 1.10 sits clear of the noise, and the price is that a
  regression of about 5% passes.
- *The 90 000 ms constant was not widened.* It is no longer gated. The cold first-build number is
  still printed in every run's step summary. Issue criterion 3 asks for the reasoning against the
  repository's no-widening convention; it is in `docs/budgets.md` under *Why the absolute number
  is not the gate*. In short: this is a different measurement, not a larger number, and gating
  90 s again needs fixed hardware.

**Known costs, stated.** Comparing each commit with its own base lets small regressions add up
across commits. The job now takes several minutes longer than the single build it replaces. A
base that does not build fails the job with "a clean build of … failed".

**Not mine:** `latency budgets (windows-latest)` failed on push run 35113580640 and on the
planted run 35120327074. That is #197, which the lead filed as pre-existing on `example`. It
passed on runs 35121489992 and 35122791512, which were green in every job.

---

## 3. `sh gradlew build`

`JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build` on `8484d0c`'s tree, with
no exclusions. The end of `EV/local-build.log`:

```
BUILD SUCCESSFUL in 1m 52s
213 actionable tasks: 149 executed, 64 up-to-date
Configuration cache entry stored.
exit=0
```

I summed the JUnit XML reports under the worktree after this build, excluding `build-logic`:
391 report files: 2 599 tests, 0 failures, 0 errors, 37 skipped. Some of those reports were left
by earlier runs of up-to-date test tasks rather than written by this one. There was no `udeaDaemonBudget` failure; that budget is no
longer on `build` since #175.

`sh gradlew -p build-logic check`, the suite holding the new rule's tests. End of `EV/build-logic-check.log`:

```
BUILD SUCCESSFUL in 32s
13 actionable tasks: 4 executed, 9 up-to-date
exit=0
```

**GL:** this ticket touches no GL code, so the xvfb run was not needed. In this build
`udeaGlTest` was `UP-TO-DATE` and `udeaAgentGlTest` ran with no display, which skips its cases. Neither says anything
about GL, and this brief makes no claim about GL.

---

## 4. Images

None. Nothing in this ticket renders. The evidence is CI run logs and step summaries, all saved
under `EV/`.

---

## 5. The issue, criterion by criterion

**"The job's verdict is a function of the commit, not of the runner. Show it: several runs on identical work, all agreeing."**

Real runs of the new job on `eb02425`, all against base `f08db40`. Samples were copied from each
log into `EV/ci-<run>-samples.txt`.

| Run | Event | Head fastest | Base fastest | Ratio | Verdict | Cold first build (old measurement) |
|---|---|---|---|---|---|---|
| 35113580640 | push | 29 238 ms | 29 361 ms | 0.996 | within | 96 803 ms (old gate: **red**) |
| 35121489992 | dispatch | 30 411 ms | 31 280 ms | 0.972 | within | 88 484 ms (old gate: green) |
| 35122791512 | dispatch | 27 653 ms | 27 785 ms | 0.995 | within | 80 837 ms (old gate: green) |

Three runs, three agreeing verdicts (0.972–0.996), on runners whose warm build speeds differed. The first two rows are the issue in one pair. The same commit on two runners would have gone red
and then green under the old gate, and the new verdict agreed both times. The probe legs back it
up across machines. Five legs of identical work in probe 2 on four CPU models gave ratios 0.971,
0.978, 0.992, 0.994 and 1.029 (`EV/probe-analysis.txt`, computed from `EV/probe2/*.log`).

**"It still goes red on a genuine build-time regression. Show that too."**

Run **35120327074**, `workflow_dispatch` with `clean_build_plant_functions=12000`, same commit and
base. `EV/ci-35120327074-clean-build-104876190605.log`: the job is `failure` with

```
a clean build of this commit took 31617 ms against 24830 ms for its base on the same runner, a ratio of 1.273 over the 1.1 tolerance. Both are the fastest of their samples, so this is the commit and not the machine: find what it added to udeaAssemble.
```

I re-ran the same verdict locally on that run's samples (`EV/local-verdict-on-planted-35120327074.log`):
the table shows `| Head / base | 1.273 |` and `| Verdict | **regressed** |`, followed by `BUILD FAILED in 18s` and `exit=1`. On run 35122791512's samples it prints
`| Head / base | 0.995 |` and `| Verdict | within tolerance |`, then `BUILD SUCCESSFUL in 3s` and `exit=0`
(`EV/local-verdict-on-honest-35122791512.log`).
Probe 3 shows where the line sits: +1 500 functions gave 1.053 and +3 000 gave 1.061, both green; +6 000 gave 1.118, red.

**"If the budget constant changes, the reasoning is stated against the KDoc convention this repository uses to forbid exactly that."**

The constant did not move; the gate stopped comparing against it. The reasoning is in
`docs/budgets.md` (*Why the absolute number is not the gate*), in the KDoc on
`CleanBuildComparison`, and in the `ci.yml` job comment. Each says plainly that this is not a wider
number and why.

---

## 6. Regenerated files

None. No replicated component changed. `net-protocol.lock` and `expected-generated-hashes.txt`
are untouched.
