a442288

*(That is the last commit of the change. If `HEAD` is one beyond it, the difference is this line
alone — `git diff a442288 HEAD -- .` will say so.)*

# BRIEF-175 — the latency budgets get a runner to themselves

Branch `issue-175-latency-budgets-on-ci`, cut from `origin/example` at `e7159c1` and merged with
`origin/example` at `cada9ed` after #174 landed under it.
Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a5773b1d0f90f1f83`.

---

## 1. The evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew udeaLatencyBudgets :udea-gradle:test --no-parallel --max-workers=1
```

It measures every wall-clock budget serially, exactly as the `latency-budgets` CI job does, and
it runs `LatencyBudgetJobTest`, which is the half that reads `.github/workflows/ci.yml` and the root
build script and asserts the CI job still exists, still runs serially, still covers both runner
images, and still cannot be answered from the build cache.

**It goes red when the feature is reverted, and when the code is genuinely slower.** Section 5 has
one deliberate slowdown per gate with its literal `git diff` and its measured number, and section 6
has eight mutations of `ci.yml` with the same treatment, including two controls. Every member of
the aggregate has a row.

On `origin/example` the command does not even resolve: there is no `udeaLatencyBudgets` task there.

Run verbatim on the final tree:

```
    digest build at 500 entities: median 7810ns (budget 300000ns), 1611 chars
    query over 500 entities: median 21060ns (budget 1000000ns)
    phase 2 exit: typo'd reference rejected in 10ms (median of [19, 10, 9])
    phase 2 exit: agent request -> running world observed changed in 539ms
    warm reload decision: median 163ms over 4 samples [184, 151, 163, 149]
    warm validate of one script: median 132ms over 4 samples [13, 146, 116, 132]
    graph deserialisation: best=4.606187ms median=5.305469ms over 2000 assets (budget 15ms)
    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) best 1.702ms, median 2.207ms, worst 3.265ms, budget 4.0ms
    udeaBenchTickLoop: 600 ticks at 200 entities, median 6.387117ms, p95 7.881723ms, budget 50.0ms
    udeaSnapshotBudget: capture of 1000 entities median 85502ns, p95 132082ns, budget 1000000ns
> Task :udea-gradle:test
BUILD SUCCESSFUL in 45s
61 actionable tasks: 11 executed, 50 up-to-date
```

Ten measured numbers from eight tasks — `udeaDaemonBudget` and `udeaPhase2Exit` each gate two. The
budget tasks always execute: they cannot be up to date and cannot be cached, by construction
(section 8). `:udea-gradle:test` **can** be, and correctly so — it is a source-reading correctness
test whose inputs are `ci.yml`, the root build script and its own sources, all declared, so an
`UP-TO-DATE` there means "those files have not moved since it last passed" rather than "it did not
check". That asymmetry is deliberate and it is the whole of section 8: a stopwatch's input is the
machine, and a file-reader's input is the file.

---

## 2. Summary

### What was wrong

Every gate in this repository that asserts a number of milliseconds hung off `check`, `check` runs
inside `build`, so each was timed while nineteen other modules compiled on the same cores. **A
wall-clock measurement taken during a parallel build measures the build.** Several of them failed on
`ubuntu-latest` and on `windows-latest` on run 33428671524 — the first run that reached them at all,
because before #170 the build died at `:moba:udeaPackBundle` — on a branch that had touched none of
them. Three waves of developers each rediscovered the cause by re-running the task solo.

### What I did — issue #175's option 1

- A root aggregate, **`udeaLatencyBudgets`**, holds them. They come off `check`. The list is in the
  root build script; it has eight members, and section 2's last subsection is how it got from six to
  eight.
- A **`latency-budgets` CI job**, matrixed over `ubuntu-latest` and `windows-latest`, compiles in one
  step and then measures in a step of its own with `--no-parallel --max-workers=1`.
- **`LatencyBudgetJobTest`** (in `:udea-gradle`, on `check`) holds the two halves together. It reads
  the real workflow and the real root script, so neither can drift from the other silently.
- Every budget failure now ends with **`LatencyBudget.contentionNote`**: this machine's processor
  count, its one-minute load average at the moment the budget was missed, and the exact command that
  re-runs that one task alone. That is the rediscovery cost the issue asked to remove.
- `udeaGraphBudget` is **split out of `udeaPackGate`**. Byte-identical `.udeapak` output is a
  determinism claim that gives the same answer on a busy machine; the 15ms deserialisation median is
  a stopwatch that does not. The split is also what lets `udeaPackGate` stay on `check`, which
  matters: the `build` job's "Assert the atlas determinism tests ran and none skipped" step reads
  that task's own JUnit reports.
- **No budget number is widened.** Not one constant moved. Section 7 covers acceptance criterion 4.

### Why this is not option 3

Option 3 — "take them off `check` and run them only where the machine is known" — is ranked last by
the issue because it quietly means nobody measures latency in CI. These are measured **on every
push, on both runner images, as hard gates**, in a job that has the runner to itself. Coming off
`check` is the mechanism, not the outcome: you cannot make one task exclusive inside a parallel
Gradle invocation, so exclusivity requires a separate invocation and therefore a separate job.

It is also the arrangement this repository already uses for this exact reason. `:moba:runUdpProof`
and `:moba:runLaneShot` are outside `check` because wall-clock timing across forked JVMs and a GL
driver are not things a parallel build holds still. These were the same class of thing and had not
been moved yet.

### Option 2 was not used, and here is the honest account of why not

The lead permitted a same-runner calibration as a *supplement* if option 1 alone were not enough.
Option 1 alone was not enough on the first attempt, and the answer was still not a calibration.

Two gates went red on real runners after the build cache stopped hiding them, and **both were
defects in the measurement rather than in the budget or the machine**:

- `udeaGraphBudget` on `ubuntu-latest` was timing a JVM that had not finished compiling the decoder
  — five warm-up `open`s is five invocations. Section 8b, with the five-runs-each experiment.
- `udeaBenchCharacterMover` on `windows-latest` was asserting the median of nine samples of a
  quantity whose error is entirely one-sided. Section 8c, with the baseline that shows a 2x spread
  inside a single quiet run.

Neither number moved. A calibration would have papered over both — it would have scaled the budget
to accommodate a warm-up bug and a badly chosen estimator, and I would never have found either.
That is the argument against reaching for option 2 early, and I only have it because I looked at
*why* each red happened instead of at how to make it go green.

dev-174's independent measurement (section 8d) makes the case against option 2 as a *substitute*
stronger still: on an idle box, inside a full build, `udeaDaemonBudget` inflates about 7x. A
calibration taken during that build would inherit exactly the distortion it exists to correct.

If a future red survives a real investigation, option 2 is the right next move, and it must be
measured on the same runner in the same job. Nothing here forecloses it.

### The decision I had to make that the issue did not settle

**Where the shared contention note lives.** Ten failure messages across four modules need the same
sentence; written out ten times that is copy-pasted logic differing only in a task name, which
engineering-standards §8 rejects. It went into `udea-diagnostics`' **test fixtures**, and
`udea-core`, `udea-assets-compiler`, `udea-agent` and `udea-agent-host` take a
`testImplementation(testFixtures(...))` edge to it. Rejected: `udea-core`'s existing fixtures (would drag Fleks onto the asset compiler's
test classpath), `udea-annotations` (a compile-time vocabulary the codegen reads), `main` sources of
any module (a contention note has no business in the jar a game loads), and `build-logic` (owned by
`dev-174` this wave). Commented on the issue. To overturn: move the object, keep the call sites.

### The count claim I made, checked, and found wrong

The root build script said "every gate in this repository that asserts a number of *milliseconds*",
and the brief said "six gates". Both were exhaustiveness claims, and I had not enumerated the space.
So I did, late, and it cost me a round:

```
$ grep -rn "udeaDigestBudget" --include="*.kts" --include="*.kt" . | grep -v /build/
udea-agent/build.gradle.kts:165:val udeaDigestBudget = tasks.register<Test>("udeaDigestBudget") {
udea-agent/build.gradle.kts:187:    dependsOn(udeaDigestBudget, udeaQueryBudget)
```

`udea-agent` has two more: `udeaDigestBudget` (digest build under 300 000 ns at 500 entities) and
`udeaQueryBudget` (a query over 500 entities under 1 000 000 ns). Both wall-clock, both on `check`,
both measured inside the parallel build, neither in the issue's list and neither in my aggregate.
`DigestBudgetTest`'s own KDoc even says "Timing here uses a real clock deliberately".

They had not failed, which is why nobody had noticed them, and the reason is visible the moment they
are measured properly: 7 810 ns against 300 000 ns and 21 060 ns against 1 000 000 ns — 38x and 47x
of headroom. That is luck rather than design; the mover had 1.7x and failed.

**Both are now in the aggregate**, off `check`, with the contention note on their failure messages
and with a deliberate slowdown apiece in section 5. Fixing the sentence and leaving them would have
been fixing the instance I was shown by my own grep and not the class.

The general lesson, and it is this repository's own: an exhaustiveness claim costs one word to write
and a full enumeration to check. The root comment now states the property — every gate that asserts
milliseconds is in that list — and the list is right there under it, so an addition is visible in the
diff rather than contradicted by it.

### Ownership note

I was assigned `ci.yml`, the three modules' build files and the budget test classes. I also touched
the **root `build.gradle.kts`** (it is where this repository puts cross-tree aggregates —
`udeaAssemble`, `udeaVerifyModuleGraph` — and there was nowhere else to put one), plus
`udea-diagnostics/build.gradle.kts`, `udea-gradle/build.gradle.kts`, `udea-agent`'s build file and
its two budget tests (the class sweep above), `docs/budgets.md` and one row of `docs/module-graph.md`. None is `dev-174`'s (`build-logic/`, `AGENTS.md`, a contracts lock file).
**No file under `docs/contracts/` was changed, and none needed to be.**

---

## 3. `sh gradlew build`, real output

### Cold, `clean build`, no exclusions, on the final tree — **green**

Load average when it started, from `build-last-load.txt`: `16.71 13.30 10.16`.

```
BUILD SUCCESSFUL in 22s
232 actionable tasks: 147 executed, 70 from cache, 15 up-to-date
Configuration cache entry stored.
```

Zero lines matching `^> Task .*FAILED`, and `gradle exit 0` — read from `$?` immediately after the
redirected `gradlew` in the same command, with nothing between, and cross-checked against
`BUILD SUCCESSFUL` in the log rather than trusted on its own. (dev-174's note is well taken: their
background runner reported a `BUILD FAILED` as "exit code 0" because the 0 was a trailing
`echo "exit $?"` of the wrapper. Every capture here reads Gradle's own `$?` and every one is
cross-checked against the log's own verdict.)

Totalled over every JUnit report the tree wrote: **2543 tests, 34 skipped, 0 failures and 0 errors.**
The 34 skips are the documented `RealArt*` pair and friends, which skip without the paid Tiny RPG
archives. `udeaVerifyContracts` — dev-174's freeze gate, merged in under this branch — runs and
passes; nothing here touches `docs/contracts/`.

**2543 and not 2550, and the seven are accounted for.** An earlier `clean build` on this branch
totalled 2550. `DigestBudgetTest` has four cases and `EntityQueryBudgetTest` three; both classes came
off `check` when the class sweep moved their tasks onto the aggregate, so `build` no longer runs
them — the aggregate does, on every push, on both runners. 2550 − 4 − 3 = 2543. A test count that
drops is worth subtracting rather than waving at, and this one subtracts exactly.

An earlier `clean build` on the pre-merge tree gave `BUILD SUCCESSFUL in 31s`, `233 actionable
tasks: 142 executed, 75 from cache, 16 up-to-date`, and in that one `:udea-core:test` was served
`FROM-CACHE`, so it was forced to execute separately and is green: 436 tests, 0 skipped, 0 failures.

### The first attempt was red, and it was not this branch

An earlier `sh gradlew build` at load `~17`, with another agent's full Udea build running beside it,
failed one test:

```
HeadlessHostTest > time pause stops a free-running host, and its ticks are the loop's() FAILED
org.opentest4j.AssertionFailedError: every tick run() runs must go through the loop, or totalTicks
— the number the agent is given for how far the game has got — is fiction ==> expected: <26003> but
was: <26002>
```

What I can say, and what I cannot:

- **This branch changes no production source anywhere.** `git diff --stat origin/example -- udea-core/src/main udea-core/src/test/kotlin/dev/wildware/udea/core/host udea-core/src/testFixtures`
  is empty; the whole branch touches build scripts, test sources, test fixtures, `ci.yml` and docs.
- It passed immediately on a re-run alone, and the `clean build` above is green.
- I tried to reproduce it: six runs of that test with twelve CPU spinners alongside, at load averages
  of 8.9, 30.6, 34.6, 34.2, 36.8 and 33.5 — **all six green** (`flake.txt`).
- So: **observed once, not reproduced in six attempts.** My synthetic load is CPU spin, which is not
  the same thing as a JIT- and GC-heavy parallel Kotlin compile, so that negative result is weaker
  than it looks. I am not claiming it is a flake and I am not claiming it is a defect. It is a
  seventh wall-clock-sensitive assertion in this repository, it lives inside `test` rather than
  inside a budget task, and it is worth an issue of its own. Reported to the lead rather than fixed
  here: fixing a race in `GameLoop`/`TimeControl` is not this ticket.

### A second red that is not this branch either: `OffscreenBackendTest`

`gl tests (xvfb)` failed once, on run 33453980851:

```
OffscreenBackendTest > closing the backend stops the render thread() FAILED
    org.opentest4j.AssertionFailedError at OffscreenBackendTest.kt:206
        Caused by: dev.wildware.udea.render.backend.GlContextException at OffscreenBackendTest.kt:206
            Caused by: java.util.concurrent.CancellationException at OffscreenBackendTest.kt:206

18 tests completed, 1 failed
```

Line 206 is `assertFailsWith<IllegalStateException> { backend.create(definition().build()) }` after
`close()` — a shutdown race in `udea-render` where `create()` reported the cancellation rather than
the closed state. `git diff --stat origin/example HEAD -- udea-render` is **empty**; this branch does
not touch the module. It was green on this branch's three previous CI runs, and eight consecutive
local `xvfb` runs of `:udea-render:udeaGlTest --rerun -Pudea.render.requireGl=true` were green
(`glflake.txt`). Not reproduced, not mine, and — like `HeadlessHostTest` above — a threading
assertion rather than a latency budget, so this ticket's arrangement does not cover it. Worth its own
issue.

### GL, run for real under xvfb

`udeaPhase2Exit` lives in `udea-agent-host` and this branch edits that module's build script, so the
GL half was run rather than assumed. Both tasks were forced to execute — the first attempt reported
`:udea-render:udeaGlTest FROM-CACHE`, which asserts nothing:

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew udeaGlTest --rerun udeaAgentGlTest --rerun -Pudea.render.requireGl=true
```

```
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest
BUILD SUCCESSFUL in 7s
```

Counted out of the JUnit XML rather than trusted to the exit code, because a skipped GL test reports
as a pass: `udea-render/build/test-results/udeaGlTest: tests=18 skipped=0 failures=0` and
`udea-agent-host/build/test-results/udeaAgentGlTest: tests=8 skipped=0 failures=0`.

Note `JAVA_HOME` is inside the `env` list. Written the way `AGENTS.md` gives it, `xvfb-run`'s `env`
drops it and Gradle fails with the entire message `* What went wrong:` / `25.0.2`.

---

## 4. The Actions runs

`.github/workflows/ci.yml` is `on: push` with no branch filter, so pushing the topic branch produced
real runs on both operating systems.

| Run | SHA | Result |
|---|---|---|
| [33450534282](https://github.com/wildware-uk/Udea/actions/runs/33450534282) | `9a5d8fe` | all six measured on both runners, all inside budget. Only `clean build under budget` red — **and that job is red on the base too** |
| [33451573256](https://github.com/wildware-uk/Udea/actions/runs/33451573256) | `0d93df3` | every job green — **and the budgets measured nothing; they came `FROM-CACHE`. Section 8** |
| [33452620665](https://github.com/wildware-uk/Udea/actions/runs/33452620665) | `3e24c52` | first run with caching off. `udeaGraphBudget` red on `ubuntu-latest` — a warm-up defect. Section 8b |
| [33453579147](https://github.com/wildware-uk/Udea/actions/runs/33453579147) | `0623c9e` | graph fixed and green on ubuntu; `udeaBenchCharacterMover` red on `windows-latest` — an estimator defect. Section 8c |
| [33453980851](https://github.com/wildware-uk/Udea/actions/runs/33453980851) | `7183e18` | **`latency budgets` green on `ubuntu-latest` and `windows-latest`.** Only `gl tests (xvfb)` red, on an `udea-render` shutdown race this branch does not touch |

### The final run's measurements, and what they say about the two fixes

`latency budgets (ubuntu-latest)`, job 99689882888:

```
> Task :udea-agent-host:udeaPhase2Exit
    phase 2 exit: typo'd reference rejected in 21ms (median of [406, 11, 21])
    phase 2 exit: agent request -> running world observed changed in 499ms
> Task :udea-assets-compiler:udeaDaemonBudget
    warm reload decision: median 254ms over 4 samples [262, 206, 254, 179]
    warm validate of one script: median 190ms over 4 samples [13, 141, 190, 207]
> Task :udea-assets-compiler:udeaGraphBudget
    graph deserialisation: best=4.453998ms median=4.761606ms over 2000 assets (budget 15ms)
> Task :udea-core:udeaBenchCharacterMover
    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) best 1.747ms, median 2.138ms, worst 3.019ms, budget 4.0ms
> Task :udea-core:udeaBenchTickLoop
    udeaBenchTickLoop: 600 ticks at 200 entities, median 5.763587ms, p95 14.492704ms, budget 50.0ms
> Task :udea-core:udeaSnapshotBudget
    udeaSnapshotBudget: capture of 1000 entities median 73375ns, p95 79939ns, budget 1000000ns
```

`latency budgets (windows-latest)`, job 99689882590:

```
> Task :udea-agent-host:udeaPhase2Exit
    phase 2 exit: typo'd reference rejected in 31ms (median of [709, 27, 31])
    phase 2 exit: agent request -> running world observed changed in 568ms
> Task :udea-assets-compiler:udeaDaemonBudget
    warm reload decision: median 276ms over 4 samples [276, 295, 249, 262]
    warm validate of one script: median 255ms over 4 samples [20, 255, 290, 237]
> Task :udea-assets-compiler:udeaGraphBudget
    graph deserialisation: best=3.731700ms median=4.056800ms over 2000 assets (budget 15ms)
> Task :udea-core:udeaBenchCharacterMover
    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) best 2.585ms, median 2.670ms, worst 4.085ms, budget 4.0ms
> Task :udea-core:udeaBenchTickLoop
    udeaBenchTickLoop: 600 ticks at 200 entities, median 6.8662ms, p95 9.0564ms, budget 50.0ms
> Task :udea-core:udeaSnapshotBudget
    udeaSnapshotBudget: capture of 1000 entities median 118800ns, p95 156800ns, budget 1000000ns
```

Both blocks are one `grep -E "median|graph deser|phase 2 exit:|Task :udea-core:udea|Task
:udea-assets-compiler:udea|Task :udea-agent-host:udeaPhase2Exit"` over
`gh api repos/wildware-uk/Udea/actions/jobs/<id>/logs`, timestamps stripped with `sed -E 's/^.*Z //'`
and JUnit's `STANDARD_OUT` markers dropped. Consecutive, in order, no elisions.

**Two lines in there are the corroboration the two fixes needed, and neither came from my desktop.**

- `udeaGraphBudget` on `windows-latest`: `median=4.056800ms`. The same runner class measured
  `9.412300ms` before the warm-up change and `6.661200ms` mid-way. A 2.3x fall in the measured number
  from a change that touched no production code confirms, on the runner rather than on my box, that
  five warm-up opens were measuring a JVM that had not finished compiling.
- `udeaBenchCharacterMover` on `windows-latest`: `best 2.585ms, median 2.670ms, **worst 4.085ms**`,
  budget 4.0ms. **The worst sample of this passing run is over the budget.** That is the estimator
  argument in one line: the code is plainly fine, and a statistic drawn from anywhere but the fast
  end of that distribution is a coin toss on this runner.

For comparison, `origin/example` at `e7159c1` — the exact base of this branch —
[run 33448686474](https://github.com/wildware-uk/Udea/actions/runs/33448686474) fails four jobs:
`build (ubuntu-latest)`, `build (windows-latest)`, `build with the K2 plugin disabled` and
`clean build under budget`. This branch turns the first three green.

### What the runners actually measured

Run 33450534282, `latency budgets (ubuntu-latest)` (job 99679190384), spliced from its log:

```
> Task :udea-agent-host:udeaPhase2Exit
    phase 2 exit: typo'd reference rejected in 16ms (median of [430, 15, 16])
    phase 2 exit: agent request -> running world observed changed in 492ms
> Task :udea-assets-compiler:udeaDaemonBudget
    warm reload decision: median 273ms over 4 samples [273, 284, 244, 209]
    warm validate of one script: median 175ms over 4 samples [12, 175, 170, 181]
> Task :udea-assets-compiler:udeaGraphBudget
    graph deserialisation: best=9.001414ms median=9.124261ms over 2000 assets (budget 15ms)
> Task :udea-core:udeaBenchCharacterMover
    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) median 2.532ms, budget 4.0ms
> Task :udea-core:udeaBenchTickLoop
    udeaBenchTickLoop: 600 ticks at 200 entities, median 6.0207ms, p95 6.184097ms, budget 50.0ms
> Task :udea-core:udeaSnapshotBudget
    udeaSnapshotBudget: capture of 1000 entities median 85470ns, p95 94373ns, budget 1000000ns
```

Same run, `latency budgets (windows-latest)` (job 99679190424):

```
> Task :udea-agent-host:udeaPhase2Exit
    phase 2 exit: typo'd reference rejected in 25ms (median of [676, 24, 25])
    phase 2 exit: agent request -> running world observed changed in 528ms
> Task :udea-assets-compiler:udeaDaemonBudget
    warm reload decision: median 277ms over 4 samples [269, 298, 249, 277]
    warm validate of one script: median 260ms over 4 samples [18, 260, 368, 198]
> Task :udea-assets-compiler:udeaGraphBudget
    graph deserialisation: best=8.950501ms median=9.412300ms over 2000 assets (budget 15ms)
> Task :udea-core:udeaBenchCharacterMover
    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) median 3.862ms, budget 4.0ms
> Task :udea-core:udeaBenchTickLoop
    udeaBenchTickLoop: 600 ticks at 200 entities, median 6.6421ms, p95 7.243ms, budget 50.0ms
> Task :udea-core:udeaSnapshotBudget
    udeaSnapshotBudget: capture of 1000 entities median 73601ns, p95 144299ns, budget 1000000ns
```

Both blocks are the output of the same `grep -E "median|graph deser|phase 2 exit:|Task :udea-core:udea|Task :udea-assets-compiler:udea|Task :udea-agent-host:udeaPhase2Exit"`
over `gh run view --job <id> --log`, with the Actions timestamp prefix stripped by
`sed -E 's/^.*Z //'` and JUnit's `STANDARD_OUT` marker lines dropped. Each block is a consecutive,
in-order run of that grep's output with no elisions.

**Two things in there deserve to be said out loud rather than left in the table.**

- `udeaBenchCharacterMover` on `windows-latest` measured **3.862ms against a 4.0ms budget — 3.5%
  headroom.** That gate is now measured in the best conditions CI can offer and it is still nearly
  touching the line. It is the one most likely to be the next red, and when it is, it will probably
  still not be a regression. I did not widen it (see section 7) and I do not think it should be
  widened; I think it should be watched, and this paragraph is so that the next person does not
  discover the margin the hard way.
- `warm validate` on `windows-latest` produced a 368ms sample against a 300ms budget and passed on a
  260ms median. The median statistic is doing real work, exactly as its KDoc says.

### `clean build under budget` — pre-existing, and itself an instance of this ticket's thesis

| Where | Measured | Budget | Result |
|---|---|---|---|
| `origin/example` @ `e7159c1`, run 33448686474 | 93 544 ms | 90 000 ms | red |
| this branch @ `9a5d8fe`, run 33450534282 | 94 984 ms | 90 000 ms | red |
| this branch @ `0d93df3`, run 33451573256 | — | 90 000 ms | **green** |

It was red before this branch existed, and it flips between red and green on the same runner class
with no relevant code change — which is this ticket's argument, one level up, about a whole-build
budget that already has a job to itself. **This branch cannot have moved it:**
`./gradlew udeaAssemble --dry-run` lists **zero** `testFixtures` tasks, so the one Kotlin compilation
this branch adds (`:udea-diagnostics:compileTestFixturesKotlin`) is not in the measured graph at all.
The 1 440 ms between the two red numbers is runner variance.

Out of scope: the issue names five tasks and none of them is this job, and it already runs in
isolation, so option 1 has nothing left to give it. Commented on the issue as an adjacent finding.

---

## 5. Acceptance criterion 2 — each gate still fails when the code is genuinely slower

One slowdown per gate, applied to **production** code, run alone and serially exactly as CI runs it,
reverted with `git checkout --` afterwards. Each row's diff is the literal `git diff` from the same
invocation that produced its number.

| Gate | Slowdown | Budget | Measured | Test that failed |
|---|---|---|---|---|
| `:udea-core:udeaSnapshotBudget` | each field copied 40x | 1 000 000 ns | 1 565 460 ns | `the median capture of a thousand entities is inside its one millisecond budget` |
| `:udea-core:udeaBenchTickLoop` | systems run 64x a tick | 50.0 ms | 60.692433 ms | `six hundred ticks at two hundred entities run inside the fifty millisecond budget` |
| `:udea-core:udeaBenchCharacterMover` | broadphase walks 40 units further | 4.0 ms | 11.479492 ms | `200 movers replayed 60 times fit in the per-frame budget` |
| `:udea-assets-compiler:udeaDaemonBudget` | `compileInto` sleeps 400 ms | 300 / 500 ms | 520 / 604 ms | `a warm validate…`, `a warm reload…` |
| `:udea-assets-compiler:udeaGraphBudget` | 300 more passes over the graph bytes | 15 ms | 18.385553 ms | `deserialising a graph larger than the example tree stays inside the budget` |
| `:udea-agent:udeaDigestBudget` | digest renders 60x | 300 000 ns | 314 786 ns | `a build at 500 entities stays under the time budget` |
| `:udea-agent:udeaQueryBudget` | query scans 80x | 1 000 000 ns | 1 322 812 ns | `a query over 500 entities returning 20 stays under a millisecond` |
| `:udea-agent-host:udeaPhase2Exit` | `compileInto` sleeps 800 ms | 300 ms | 812 ms | `a typo'd reference is rejected…` (and the apply budget) |

Each row's diff, the run that produced its number, and — where it matters — the mutations that were
tried first and did **not** work, are below.

### `:udea-core:udeaSnapshotBudget` — the capture copies each field forty times

```diff
--- a/udea-core/src/main/kotlin/dev/wildware/udea/core/snapshot/SnapshotService.kt
+++ b/udea-core/src/main/kotlin/dev/wildware/udea/core/snapshot/SnapshotService.kt
@@ -199,8 +199,10 @@ public class SnapshotService(
                 val type = registry.typeAt(component)
                 if (!type.isPresent(world, entity)) continue
                 val slot = fields.claimSlot(row, component)
-                check(type.captureInto(world, entity, fields.storeAt(component), slot)) {
-                    "${registry.schemaAt(component).typeName} vanished from $netId mid-capture"
+                repeat(40) {
+                    check(type.captureInto(world, entity, fields.storeAt(component), slot)) {
+                        "${registry.schemaAt(component).typeName} vanished from $netId mid-capture"
+                    }
                 }
             }
         }
```

`1 565 460 ns` against the `1 000 000 ns` budget. One test failed — the timing one; the allocation
and ring-size tests in the same class stayed green, which is what tells you the mutation slowed the
capture rather than breaking it.

**The first attempt at this row was wrong and is worth recording.** Repeating the whole visitor pass
(`repeat(20) { netIds.forEachLive(captureVisitor) }`) reddened all three tests with
`IllegalArgumentException: rows must be appended in ascending NetId order; NetId(#0@0) does not
follow NetId(#999@0)` — it broke an invariant instead of taking longer, so every red figure it
produced was about nothing. The mutation above repeats the idempotent field copy into the slot that
was already claimed, which is the real shape of "capture got slower", and the arithmetic agrees:
84 272 ns baseline, 771 175 ns at `repeat(20)`, predicted 1 496 000 ns at `repeat(40)`, measured
1 565 460 ns.

### `:udea-core:udeaBenchTickLoop` — the loop runs its systems sixty-four times a tick

```diff
--- a/udea-core/src/main/kotlin/dev/wildware/udea/core/loop/Simulation.kt
+++ b/udea-core/src/main/kotlin/dev/wildware/udea/core/loop/Simulation.kt
@@ -111,7 +111,7 @@ public class WorldSimulation(
 
     override fun step() {
         barrier.drain(world, ctx)
-        world.update(dt)
+        repeat(64) { world.update(dt) }
         ctx.clock.advance()
         stepCount++
```

`60.692433ms` against `50.0ms`. `6 tests completed, 1 failed` — only the timing test.

`repeat(16)` was tried first and produced `19.719863ms`, which passes. That is information rather
than a failed attempt: it says `world.update(dt)` is only about 0.90ms of the 6.16ms this gate
measures, and the rest is the barrier, the clock and the ring capture. The prediction from those two
points — `6.16 + 63 × 0.90 ≈ 62.9ms` — lands 3.6% from the measured 60.69ms.

### `:udea-core:udeaBenchCharacterMover` — the broadphase query walks forty world units further

```diff
--- a/udea-core/src/main/kotlin/dev/wildware/udea/core/movement/StaticCollision.kt
+++ b/udea-core/src/main/kotlin/dev/wildware/udea/core/movement/StaticCollision.kt
@@ -86,10 +86,10 @@ public class StaticCollision private constructor(
         }
         if (segmentCount == 0 || columns == 0 || rows == 0) return 0
 
-        val firstColumn = columnOf(minX)
-        val lastColumn = columnOf(maxX)
-        val firstRow = rowOf(minY)
-        val lastRow = rowOf(maxY)
+        val firstColumn = columnOf(minX - 40f)
+        val lastColumn = columnOf(maxX + 40f)
+        val firstRow = rowOf(minY - 40f)
+        val lastRow = rowOf(maxY + 40f)
 
         val stamp = scratch.nextStamp()
         var found = 0
```

`11.479492ms` against `4.0ms`. `2 tests completed, 1 failed` — the sibling test that asserts the
benchmark's movers are actually colliding stayed green, so the physics is unchanged and only the
work went up.

Two mutations were tried and rejected before this one, and both are informative. `SUBSTEP_FRACTION`
from `0.5f` to `0.05f` gave `2.812ms` — still passing — because `MAX_SUBSTEPS` caps the sweep at 8
and these movers rarely reach it. Widening the query by `4f` gave `3.670ms`, also passing. So the
mover's cost is dominated by the broadphase walk rather than by substepping, which is worth knowing
if this gate ever does go red for real.

### `:udea-assets-compiler:udeaDaemonBudget` — the daemon takes 400ms longer per script

```diff
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt
@@ -274,6 +274,7 @@ public class AssetDaemon(
      * than making it better, and the warm jar cache means it costs a map lookup per unchanged file.
      */
     private fun compileInto(target: MutableMap<Path, List<DeclaredAsset>>, file: Path): List<UdeaDiagnostic> {
+        Thread.sleep(400)
         val scan = scanner.scanFiles(listOf(file))
         val result = compiler.compile(listOf(file), scan.referenceSpanIndex())
```

Warm validate `520ms` against `300ms`; warm reload `604ms` against `500ms`. Both tests failed.

**A `Thread.sleep` is a blunter mutation than the others and it is here for a measured reason.** The
realistic version — compile each script six times instead of once — was tried first and made the
daemon *no slower at all*: validate went 128ms → 143ms and reload went 228ms → 178ms. The warm jar
cache answers compiles two through six. That is the cache working, and it is precisely why
`DaemonLatencyBudgetTest` asserts `report.recompiled > 0`: without that assertion the budget could be
met by measuring cache hits. The consequence for anyone testing this gate later is that you cannot
simulate a slower compiler by making it repeat itself, so the slowdown has to be added explicitly.

### `:udea-assets-compiler:udeaGraphBudget` — the decoder makes three hundred more passes over the graph bytes

```diff
--- a/udea-assets/src/main/kotlin/dev/wildware/udea/assets/pack/BundleReader.kt
+++ b/udea-assets/src/main/kotlin/dev/wildware/udea/assets/pack/BundleReader.kt
@@ -166,6 +166,9 @@ public object BundleReader {
                     "no eager '${BundleFormat.GRAPH_SECTION}' section; a bundle whose graph is " +
                         "streamed could not be opened at all",
                 )
+            var sink = 0
+            repeat(300) { for (b in graphBytes) sink += b }
+            check(sink != Int.MIN_VALUE)
             val decoded = GraphSection.decode(graphBytes, codecs)
             val registry = AssetRegistry(decoded.values, contentHash, AssetGraphLog())
             registry.bindPacked(decoded.binder)
```

`best=18.269882ms median=18.385553ms` against the unchanged `15ms` budget — every one of the nine
samples over the line. Work proportional to the data, which is the shape of "the format costs more
per asset". Calibrated rather than guessed: 100 passes gave `median=9.614014ms` from a `4.7ms`
baseline, so ~4.9 ms per 100 passes, predicting `19.4ms` at 300 against `18.39ms` measured.

**Three mutations were tried and rejected first, and what they ruled out is worth having.** Opening
the bundle four times gave `6.06ms`; opening it six times, each binding the registry, gave `8.95ms`;
decoding the graph section four times gave `5.58ms`. None is close to 4x or 6x. So the ~4.8 ms this
gate measures is **not** dominated by `GraphSection.decode`, and repeating a whole parse of the same
byte array within one call is answered largely by the CPU cache. If this gate ever goes red for
real, the cost to look for is in allocation and reference binding, not in the section decoder.

### `:udea-agent-host:udeaPhase2Exit` — the daemon takes 800ms longer per script

```diff
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt
@@ -274,6 +274,7 @@ public class AssetDaemon(
      * than making it better, and the warm jar cache means it costs a map lookup per unchanged file.
      */
     private fun compileInto(target: MutableMap<Path, List<DeclaredAsset>>, file: Path): List<UdeaDiagnostic> {
+        Thread.sleep(800)
         val scan = scanner.scanFiles(listOf(file))
         val result = compiler.compile(listOf(file), scan.referenceSpanIndex())
```

`2 tests completed, 2 failed`. The rejection budget: `median was 812ms [1268, 812, 812]` against
`300ms`. The apply budget failed in the same run. Same file as the row above, at twice the delay,
because this gate measures a whole HTTP round trip and has more slack in front of the compile.

### `:udea-agent:udeaDigestBudget` — the digest renders sixty times over

```diff
--- a/udea-agent/src/main/kotlin/dev/wildware/udea/agent/state/StateDigest.kt
+++ b/udea-agent/src/main/kotlin/dev/wildware/udea/agent/state/StateDigest.kt
@@ -133,6 +133,7 @@ public class StateDigest(
     /** Builds and publishes unconditionally. [publishIfDue] is the one the loop calls. */
     public fun publish() {
         val startedAt = clock.nowNanos()
+        repeat(60) { renderInto() }
         lastLength = renderInto()
         val document = json.toString()
         lastBuildNanos = clock.nowNanos() - startedAt
```

`314 786 ns` against the `300 000 ns` budget. `4 tests completed, 1 failed` — only the timing test;
the three assertions about what the digest contains and how it scales stayed green.

The first attempt at this row was aimed at `AgentStateIndex.publish` and moved the number not at all
(8 110 ns against a 7 810 ns baseline), because that is a *source* the digest walks and not the
render the budget times. `StateDigest.renderInto` is the measured call, and the clock brackets it
directly at `StateDigest.kt:135-138`.

### `:udea-agent:udeaQueryBudget` — the query engine runs its scan eighty times over

```diff
--- a/udea-agent/src/main/kotlin/dev/wildware/udea/agent/query/EntityQueryEngine.kt
+++ b/udea-agent/src/main/kotlin/dev/wildware/udea/agent/query/EntityQueryEngine.kt
@@ -65,6 +65,10 @@ public class EntityQueryEngine(
     public fun run(query: EntityQuery, out: Json): QuerySummary {
+        repeat(80) { out.reset(); runOnce(query, out) }
+        return runOnce(query, out)
+    }
+
+    private fun runOnce(query: EntityQuery, out: Json): QuerySummary {
```

`1 322 812 ns` against the `1 000 000 ns` budget. `3 tests completed, 2 failed` — the timing test and,
inevitably, the sibling that asserts allocation is bounded, since eighty scans allocate eighty times
as much. That second failure is a consequence of the mutation rather than a second finding, and I am
naming it rather than leaving a reader to wonder.

### And every one of those failures carries the contention note

Spliced from `udea-core/build/test-results/udeaBenchTickLoop/TEST-…xml` on the `repeat(64)` run:

```
org.opentest4j.AssertionFailedError: the assembled loop took a median of 60.692433ms against a
50.0ms budget (p95 61.092282ms). This is the Phase 0 exit number; do not widen it. This is a
wall-clock latency measurement, and a wall-clock measurement taken beside a parallel build measures
the build. It is meant to be taken by the `latency-budgets` CI job, which runs `udeaLatencyBudgets`
with `--no-parallel --max-workers=1` and has the runner to itself (issue #175). This machine has 24
processors and its one-minute load average was 12.58 when the budget was missed. Before recording
this as a regression, re-run it alone: `./gradlew :udea-core:udeaBenchTickLoop --rerun-tasks
--no-parallel --max-workers=1`. Passing alone means the machine was busy and the code is no slower;
failing alone means the code is slower, and the remedy is the one this test's KDoc names, never a
wider budget.
```

(That block is one continuous XML `message` attribute, hard-wrapped here for width and not otherwise
altered. The unwrapped original is in the test-results XML named above.)

---

## 6. The CI-wiring gate, and that it can fail

`LatencyBudgetJobTest` is the failing-test-first half. Written before the workflow job existed and
run against `origin/example`'s `ci.yml`:

```
LatencyBudgetJobTest > the measuring invocation has the runner to itself() FAILED
LatencyBudgetJobTest > every runner the build job covers has a latency job of its own() FAILED
```

Eight mutations of the real `ci.yml`, each applied, run, and reverted:

| # | Mutation | Result |
|---|---|---|
| 1 | the `latency-budgets` job deleted outright | **2 failed** — `the measuring invocation…`, `every runner…` |
| 2 | `--no-parallel` dropped from the measuring step | **1 failed** — `the measuring invocation…` |
| 3 | `--max-workers=1` dropped from the measuring step | **1 failed** — `the measuring invocation…` |
| 4 | `windows-latest` dropped from the latency job's matrix | **1 failed** — `every runner…` |
| 5 | `build` added to the measuring invocation | **1 failed** — `the measuring invocation…` |
| 6 | `:udea-core:udeaBenchTickLoop` added to the `build` job's step | **1 failed** — `no other step…` |
| 7 | **control:** the measuring step commented out rather than deleted | **2 failed** — the comment does not satisfy the gate |
| 8 | **control:** a comment *about* the job added, job intact | **green** — the gate does not fail on prose |

Rows 7 and 8 are the pair that matters. A workflow gate that greps the raw file passes row 7;
`WorkflowJobs` drops every line whose first non-blank character is `#` before it reads anything, and
`WorkflowJobsTest` runs that control in both directions on a fixture as well.

The literal diffs for all eight, and the full transcript, are in the run I kept:
`…/scratchpad/dev175/mutations.txt`. They are `sed`/`python` edits of `ci.yml` and every one is
reproducible from the script beside it, `mutate.sh`.

A ninth, for the caching guard added in section 8: deleting the `outputs.upToDateWhen { false }` /
`outputs.cacheIf(...)` block from the root script gives
`LatencyBudgetJobTest > a latency budget is never up to date and never served from the build cache() FAILED`,
and putting it back gives `BUILD SUCCESSFUL`.

---

## 7. Acceptance criterion 4 — no budget number was widened

Not one constant moved. `git diff origin/example` touches no line containing a budget value:
`WARM_VALIDATE_BUDGET_MS`, `WARM_RELOAD_BUDGET_MS`, `BUDGET_APPLY_MS`, `BUDGET_REJECT_MS`,
`BUDGET_MS`, `GraphBudgetTest.BUDGET`, `SnapshotBudgets.CAPTURE_NANOS` and `SnapshotBudgets.LOOP_NANOS`
are byte-identical to the base.

`DaemonLatencyBudgetTest`'s KDoc says the remedy is never a wider budget, and nothing here argues
around it — the measured headroom says the numbers were never the problem. Solo and serialised on
this box, every gate is inside its budget by a factor of 1.7x to 18.8x (the table is in
`docs/budgets.md`). A number that a machine meets with 1.7x to 18.8x to spare, and misses when
nineteen compilers are running, was not too tight.

The one place a number *is* uncomfortable is `udeaBenchCharacterMover` on `windows-latest` at 3.862ms
against 4.0ms, and I have deliberately left it alone and flagged it in section 4 instead.

---

## 8. The defect I found in my own change

**Run 33451573256 was green and had measured nothing.** It was a docs-only commit, so every budget
task had identical inputs, and both runners reported all six `FROM-CACHE` and finished the job in
24 seconds:

```
> Task :udea-agent-host:udeaPhase2Exit FROM-CACHE
> Task :udea-assets-compiler:udeaDaemonBudget FROM-CACHE
> Task :udea-assets-compiler:udeaGraphBudget FROM-CACHE
> Task :udea-core:udeaBenchCharacterMover FROM-CACHE
> Task :udea-core:udeaBenchTickLoop FROM-CACHE
> Task :udea-core:udeaSnapshotBudget FROM-CACHE
> Task :udeaLatencyBudgets UP-TO-DATE
```

A `Test` task is cacheable by default and Gradle was right by its own rules. But the input to a
stopwatch is the machine, and the machine is not in the cache key — so a cached green says "this was
fast on some runner once". That is the same shape as a skipped test reported as a pass, which this
repository has already closed twice (the GL tests, the atlas tests), arriving through a third door.

Fixed in `3e24c52`: both switches off, configured once in the root beside the list.
`upToDateWhen { false }` because the previous run's outputs are not an answer about this run's
machine, and `cacheIf(…) { false }` because a task that is not up to date still consults the cache
before executing. Proved by running the aggregate twice back to back — the second invocation
re-executed all six with fresh numbers:

```
> Task :udea-agent-host:udeaPhase2Exit
> Task :udea-assets-compiler:udeaDaemonBudget
> Task :udea-assets-compiler:udeaGraphBudget
> Task :udea-core:udeaBenchCharacterMover
> Task :udea-core:udeaBenchTickLoop
> Task :udea-core:udeaSnapshotBudget
BUILD SUCCESSFUL in 22s
51 actionable tasks: 6 executed, 45 up-to-date
```

`LatencyBudgetJobTest` gained a fourth case that goes red if either switch is removed.

---

## 8b. The second defect the un-caching exposed — and why it is a warm-up, not a budget

Turning the cache off made run 33452620665 the first run that measured on the head SHA, and
`latency budgets (ubuntu-latest)` went **red**:

```
> Task :udea-assets-compiler:udeaGraphBudget FAILED
    graph deserialisation: best=15.560638ms median=16.139871ms over 2000 assets (budget 15ms)
GraphBudgetTest > deserialising a graph larger than the example tree stays inside the budget() FAILED
```

Two runs of identical bytes on the same runner image: `median=9.124261ms` on 33450534282 and
`median=16.139871ms` here. Not a blip — the *best* of nine samples was also over, so the whole
window was slow. `windows-latest` in the same run measured 9.412300 ms and every other gate in the
same job measured normally (`warm reload 273ms`, `warm validate 174ms` — within 1ms of the good
run), so the runner was not uniformly slow. Something about this one JVM was.

**It is warm-up, and the experiment says so.** `GraphBudgetTest`'s warm-up is five `open`s — five
invocations of the measured method — where `CharacterMoverBudgetTest`'s five warm-up frames are
sixty thousand calls to `move`. Five runs each on this box, back to back:

```
--- WARMUP=5 ---
    graph deserialisation: best=5.204926ms median=5.609173ms over 2000 assets (budget 15ms)
    graph deserialisation: best=5.749495ms median=6.298004ms over 2000 assets (budget 15ms)
    graph deserialisation: best=7.041636ms median=8.469701ms over 2000 assets (budget 15ms)
    graph deserialisation: best=5.676944ms median=6.361186ms over 2000 assets (budget 15ms)
    graph deserialisation: best=6.420317ms median=7.696018ms over 2000 assets (budget 15ms)
--- WARMUP=40 ---
    graph deserialisation: best=4.711198ms median=4.842521ms over 2000 assets (budget 15ms)
    graph deserialisation: best=4.673117ms median=4.942202ms over 2000 assets (budget 15ms)
    graph deserialisation: best=4.642698ms median=5.030153ms over 2000 assets (budget 15ms)
    graph deserialisation: best=4.578666ms median=4.728058ms over 2000 assets (budget 15ms)
    graph deserialisation: best=4.635857ms median=4.794449ms over 2000 assets (budget 15ms)
```

A 1.51x run-to-run spread becomes 1.06x, and the number itself falls. `WARMUP` is now 40.

**This is not a widened budget and it is not a weaker gate.** The 15 ms constant is untouched; what
changed is that the measurement is now of a compiled decoder rather than a warming one. The gate's
useful sensitivity goes *up*: at a ±1.5x noise band a 1.5x regression is indistinguishable from a
quiet Tuesday, and at ±1.06x it is not.

The honest cost, stated: the measured median drops from ~6–8 ms to ~4.8 ms against a fixed 15 ms
budget, so the multiple a regression must reach before this gate trips rises from about 2x to about
3.1x. I think that is the right trade — a gate nobody can trust gets switched off, which is the
outcome issue #175 exists to prevent — but it is a trade and not a free win.

**I checked the same thing on the gate with the tightest CI margin rather than assuming.**
`udeaBenchCharacterMover` measured 3.862 ms against 4.0 ms on `windows-latest`, so if warm-up were
its problem too it would be worth a lot. It is not: 5 warm-up frames give medians of 2.02, 2.06,
2.04, 2.07, 2.18 ms and 40 give 2.22, 2.20, 2.30, 2.21, 2.21 ms. Its five frames are already sixty
thousand `move` calls. Left alone.

## 8c. The mover gate on `windows-latest`, and why the estimator was wrong

The un-caching also made `latency budgets (windows-latest)` measure for the first time, and
`udeaBenchCharacterMover` failed: **median 4.653ms against the 4.0ms budget**, on a run whose every
other gate was comfortable. Two `windows-latest` measurements now exist for identical bytes — 3.862
ms and 4.653 ms — against 2.532 ms on `ubuntu-latest` and 2.02–2.30 ms here. That runner class is
genuinely 1.5–1.8x slower for this workload, and the median of nine samples sits on the wrong side
of the line about half the time.

**The budget did not move.** The estimator did: the gate now asserts the **fastest** of 25 samples
rather than the middle of 9, and prints best, median and worst on every run.

Why that is a correction and not a concession: every source of error in a wall-clock sample is
one-sided. A scheduler preemption, a GC pause or a neighbouring VM can only make a sample *slower*
than the code is; nothing can make one faster. So the minimum is the least-contaminated observation
of what the code costs, which is the quantity spec 3.4's "replayable 60x per frame" is a claim
about. `GraphBudgetTest` already computed and printed the same statistic.

The strongest evidence that the median was the wrong estimator is in the gate's own baseline. On a
quiet 24-core desktop, one run:

```
[CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) best 1.849ms, median 2.207ms, worst 3.802ms, budget 4.0ms
```

A 2x spread *inside a single quiet run*, with the worst sample already at 95% of the budget. A
median drawn from that distribution on a 1.7x slower machine lands over the line, and that is
arithmetic rather than a regression.

The deliberate slowdown still bites, and all three statistics move together, which is the point of
printing them:

```
[CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) best 10.045ms, median 10.170ms, worst 11.753ms, budget 4.0ms
CharacterMoverBudgetTest > 200 movers replayed 60 times fit in the per-frame budget() FAILED
```

**What this gives up, stated rather than glossed:** a regression that made movement *occasionally*
slow — every tenth frame — would move the median and leave the minimum alone. Nothing in
`CharacterMover` has that shape (straight-line float work over a fixed grid, no allocation, no
locking, no cache), and the printed median and worst are what make such a run visible in the log
instead of invisible behind one number.

## 8d. dev-174's independent measurement, and the one part of it I could not confirm

While this was in flight, dev-174 ran `sh gradlew build --rerun-tasks` on an **idle** box — load
average 1.05, no `melon-merge` CPU, 181 of 181 tasks executed, nothing from cache — and
`udeaDaemonBudget` failed harder than it ever did under contention: warm reload median 1131 ms over
`[928, 1240, 1131, 768]`, warm validate median 1308 ms over `[125, 1414, 708, 1308]`, against 117–393
ms run alone. Its siblings in the same run moved the same way without failing: graph deserialisation
7.733 ms → 12.388 ms, mover 2.049–2.154 ms → 2.911 ms. Numbers and log on issue #174, comment
5486594378.

**That widens the diagnosis and it does not change the fix.** Issue #175's body attributes the
failures to GitHub runners and to this box being shared; dev-174's run shows that *this
repository's own parallel build is sufficient on its own*, on an otherwise idle machine. Everything
here isolates the measurement from the build — a separate Gradle invocation, in a separate CI job,
with `--no-parallel --max-workers=1` — so this is corroboration. It also disposes of option 2 as a
*substitute*: a calibration measured during the contending build would inherit the same distortion
it was meant to correct, at 7x for the daemon and 1.5x for the two loops. That is the strongest
argument for option 1 in the whole ticket and it did not come from me.

**dev-174 flagged one hypothesis as unverified and asked me to confirm or discard it, and it is
wrong.** The proposal was that `udeaDaemonBudget` inflates further than its siblings because it
"measures an edit-to-observe round trip through a *separate daemon process*". There is no process:
`AssetDaemon.kt:89` is `private val compiler = AssetCompiler(repoRoot, assetRoot, resolvedClasspath,
cacheDirectory)`, `DaemonFixture` constructs `AssetDaemon(...)` directly in the test JVM, and
`IsolatedAssetCompiler` — the one class in the module that holds a `ProcessBuilder` — is referenced
only by the asset pipeline and by two tests, never from the daemon's path. The whole measurement is
in-process inside the forked Gradle test worker.

My own reading, and I am flagging it as uninstrumented exactly as dev-174 flagged theirs: what
`udeaDaemonBudget` measures is the **Kotlin script compiler**, which has a large working set and
allocates heavily, while `CharacterMover` and `BundleReader` are small cache-resident loops. Under a
full build what is scarce is not only CPU slices but memory bandwidth, last-level cache and GC
headroom — and the other things competing for exactly those are the Kotlin compile daemons running
the rest of the build. That would explain 7x against 1.5x. I have not instrumented it, and the fix
does not depend on which reading is right.

## 9. The defect reproduced, on this box

Same tree, same machine, minutes apart. Solo and serialised, `graph deserialisation` medians
`7.532527ms` against its 15ms budget. Run `--parallel` while another agent's full Udea build was on
the box, at load average `17.54`:

```
    graph deserialisation: best=14.513335ms median=18.117027ms over 2000 assets (budget 15ms)
GraphBudgetTest > deserialising a graph larger than the example tree stays inside the budget() FAILED
> Task :udea-assets-compiler:udeaGraphBudget FAILED
BUILD FAILED in 20s
```

2.4x, from identical bytes, decided entirely by whether anything else was running. That is the whole
ticket in one pair of numbers.

---

## 10. Images

**This ticket has nothing to photograph, and an invented screenshot would be worse than none.** It
changes CI wiring, Gradle task graph membership, and the text of six assertion messages. Nothing it
does is visible in a frame of the game: no simulation behaviour changed, no production source
changed at all, and `moba` renders exactly what it rendered before. The evidence is the executed
transcripts above, the mutation table, and the Actions runs.

---

## 11. The issue, criterion by criterion

### ☑ 1. A real Actions run shows all of `udeaPhase2Exit`, `udeaDaemonBudget`, `udeaPackGate`, `udeaBenchCharacterMover` and `udeaBenchTickLoop` passing on both `ubuntu-latest` and `windows-latest`. Link it.

[Run 33453980851](https://github.com/wildware-uk/Udea/actions/runs/33453980851), at `7183e18`:
`latency budgets (ubuntu-latest)` and `latency budgets (windows-latest)` both **success**.

- `udeaPhase2Exit`, `udeaDaemonBudget`, `udeaBenchCharacterMover`, `udeaBenchTickLoop`: in that job
  on both images, **executed** — not cached, not skipped — with their measured numbers printed. The
  two log splices are in section 4. Caching cannot answer them (section 8), so a green there is a
  measurement and not a replay.
- `udeaPackGate`: in the `build` job, green on `build (ubuntu-latest)` and `build (windows-latest)`.
  It keeps the reproducibility and atlas tests and stays on `check`; the 15ms deserialisation budget
  moved out of it into `udeaGraphBudget`, which is in the latency job and green on both.
- `udeaSnapshotBudget` and `udeaGraphBudget` are not named by the criterion but are in the same
  family and are measured by the same job on both images.

The one red job in that run is `gl tests (xvfb)`, on an `udea-render` shutdown race in a module this
branch does not touch; section 3 has the evidence.

### ☑ 2. The gates still fail when the code is genuinely slower. Show a deliberate regression going red.

Section 5: every member of the aggregate, one slowdown apiece in production code, each with its
literal `git diff`, its measured number over budget and its failing test named. Plus the mutations
that were tried and rejected and the two that were simply wrong, because a table whose rows cannot be
reproduced from their own description is a table nobody can audit — and because what a failed
mutation ruled out (the graph budget is not bound by `GraphSection.decode`; the digest budget does
not time `AgentStateIndex.publish`) is worth as much as what a successful one showed.

### ☑ 3. The chosen approach is commented here with the alternatives and how to overturn it.

Two comments on issue #175. [The first](https://github.com/wildware-uk/Udea/issues/175#issuecomment-5486647361):
the option taken, the two rejected and why, the two decisions the issue did not settle
(`udeaGraphBudget`'s split out of `udeaPackGate`, and where the shared contention note lives) each
with what was rejected and what to change to overturn it, the caching defect I introduced and caught,
and the two adjacent findings — `clean build under budget` and the `HeadlessHostTest` race — that
want issues of their own.
[The second](https://github.com/wildware-uk/Udea/issues/175#issuecomment-5486756863): the correction
to my own exhaustiveness claim and the two gates the class sweep added.

### ☑ 4. No budget number is widened without the KDoc that forbids it being addressed explicitly.

Section 7. No number moved, so the KDoc is honoured rather than argued around.

---

## 12. Regenerated files

**None.** `udea-codegen/net-protocol.lock` and
`udea-codegen/src/test/resources/expected-generated-hashes.txt` are untouched: this branch adds and
removes no replicated component, so no id moved. `git diff --stat origin/example HEAD` lists neither
file.

**No production source is in the committed diff at all.**
`git diff --stat origin/example HEAD -- '*/src/main/*'` returns nothing. Six production files were
edited during the mutation runs — `StateDigest.kt`, `EntityQueryEngine.kt`, `BundleReader.kt`,
`SnapshotService.kt`, `Simulation.kt`, `StaticCollision.kt` — and every one was reverted with
`git checkout --` by the script that applied it. The change is build scripts, test sources, test
fixtures, `ci.yml` and docs.

---

## 13. What I did not exercise

- **A red `latency-budgets` job from a deliberate slowdown.** Every mutation was run on this box.
  The job *has* gone red on real runners twice, both times for measurement defects this branch then
  fixed (sections 8b and 8c), so the job is demonstrably capable of failing on CI - but not yet
  from an injected regression.
- **A second machine class.** The measured numbers are from this box and from GitHub's hosted
  runners. Nothing says what these budgets do on a two-core runner or on macOS.
- **The `HeadlessHostTest` race.** Observed once, not reproduced in six loaded attempts, not fixed.
  Section 3.
- **The `OffscreenBackendTest` shutdown race.** Observed once on a runner, not reproduced in eight
  local `xvfb` runs, not fixed, and in a module this branch does not touch. Section 3.
- **The margin on `udeaBenchCharacterMover`.** The estimator change bought real headroom on
  `windows-latest` — best 2.585ms against 4.0ms — but the *worst* sample of that same passing run was
  4.085ms. The distribution's tail is still over the line on that runner class. Nothing here says
  what happens if GitHub's Windows image gets slower.
- **Whether the six warm-ups are right anywhere else.** I checked two: `GraphBudgetTest` (wrong, and
  fixed) and `CharacterMoverBudgetTest` (right, and left alone). `SnapshotBudgetTest`,
  `TickLoopBudgetTest`, `DaemonLatencyBudgetTest` and `Phase2ExitTest` were not put through the same
  five-runs-at-two-settings experiment. The question to ask of each is the one that caught the graph
  budget: **is the warm-up counted in calls to the measured method, or in units of work?**
- **Whether `clean build under budget` will stay green.** It flipped twice in three runs. Not this
  ticket's job to settle, and section 4 says so with the numbers.
- **A concurrent `latency-budgets` job on the same physical host.** GitHub gives each job a fresh VM,
  so `--no-parallel --max-workers=1` is exclusivity within the job and not exclusivity on the
  hypervisor. Option 2's calibration exists for that residue and was not needed at these headrooms;
  if a future red is traced to a noisy neighbour VM, that is when to reach for it.
