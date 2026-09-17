# Budgets

The single budgets document for this repository. Later epics **write their numbers into this
file** rather than creating a second one, and add their gate as a job or a matrix leg in
`.github/workflows/ci.yml` rather than a second workflow.

A budget here is a CI gate, not an aspiration. A number nobody fails a build over is a note,
and notes drift.

## Clean build — enforced as a comparison against the base

| | |
|---|---|
| **Spec target** | 90 000 ms (spec §6, Phase 0 exit: "clean build <90s") — **reported, not gated**; see *Why the absolute number is not the gate* |
| **Gate** | `clean-build-budget` job in `.github/workflows/ci.yml` |
| **Command timed** | `./gradlew clean udeaAssemble --no-build-cache`, configuration-cache entry deleted first |
| **Compared against** | the same command on the commit's base, in a second checkout on the same runner: the merge base with the branch the commit merges into - a pull request's target, or else the nearest of `kmp` and `master` - or the first parent for a commit already on it. The rule is `.github/scripts/clean-build-base.sh`, executed by `CleanBuildBaseScriptTest` (issue #218) |
| **Samples** | 6 per side, in base/head/head/base order, after one untimed clean build of each |
| **Rule** | the head's fastest sample may be at most `CleanBuildComparison.TOLERANCE` (1.10) times the base's fastest — `build-logic`, tested by `CleanBuildComparisonTest` |
| **Verdict task** | `./gradlew udeaCleanBuildVerdict -Pudea.cleanBuild.samples=<file>` |
| **Wiring gate** | `:udea-gradle:CleanBuildBudgetJobTest`, which reads the workflow |
| **Runner** | `ubuntu-latest`, Temurin JDK 21 |

### What is measured, and what is not

`udeaAssemble` assembles every `:udea-*` project and `:moba`, and nothing else —
`./gradlew udeaAssemble --dry-run` lists no old-module task. Budgeting plain `assemble` would
measure `common` and `example` resolving KryoNet, Box2D natives and five `kotlin-scripting-*`
artifacts; that number is real but it is not a number the rewrite can move — it belongs to code
on its way out — and a budget nobody can act on is a budget nobody looks at.

`--no-build-cache` is there because the build cache would let the gate pass by not compiling
anything, which is exactly the thing the gate is supposed to notice. The configuration cache
stays **on**, as it is in `gradle.properties`, but its entry is deleted before every sample, so
each sample configures and compiles from nothing on daemons that are already warm.

### Why the absolute number is not the gate (issue #181)

Until #181 the job timed one clean build in a fresh job and failed it over 90 000 ms. Its
verdict was about the runner rather than the commit: runs of it measured from 60 405 to
102 453 ms, and five runs on work that did not differ went three red and two green. Three
probe runs on the issue branch measured why. Their logs are listed in the issue's decision
comment.

- **The first clean build in a job is mostly warm-up.** On one runner, the first build measured
  83 843–97 368 ms and the same command repeated in the same job fell to 18 303–26 006 ms. The
  old number was mostly a cold Gradle daemon, a cold Kotlin daemon and JIT compilation.
- **`ubuntu-latest` is not one machine.** Under the one label the probes drew AMD EPYC 9V74,
  7763 and 9V45 and Intel Xeon Platinum 8573C and 6973P-C, and the fastest warm clean build
  differed by about 1.5x between them (17 017 ms against 25 623 ms). No estimator over one
  job's samples divides that out.

So this is not a wider budget, which is the remedy this document refuses everywhere else. It is a
different measurement. The base is built on the same runner in the same job, so the machine
cancels out, and the verdict is the question a reader of CI can act on: did this commit make a
clean build slower? On identical work, five probe legs on four CPU models measured head/base
ratios between 0.971 and 1.029.

**What it does not catch, stated rather than implied.** A regression under the tolerance passes:
1 500 planted functions measured 1.053 and would be green, while 6 000 measured 1.118. Because
each commit is compared with its own base, small regressions can also add up across commits
without any single one failing. The 90 000 ms target has not been dropped. Every run writes the
head's first build (cold daemons, the old measurement) to the step summary, so the absolute
trend stays readable. Gating it again needs a runner whose hardware is fixed, not a different
number.

### Why the fastest sample

Everything that disturbs a wall-clock build time adds time: a neighbour on the host, a GC pause,
a cold page cache. Nothing makes a build faster than the work it does, so the fastest sample is
the closest to the build's real cost. `udeaBenchCharacterMover` moved to the fastest of its
samples for the same reason in issue #175.

### Known drag

None outstanding. `ksp.incremental=false` used to sit in `gradle.properties` and make
`udea-codegen`'s KSP pass do full work on every build; it is now `true`, and the section
below is the gate that keeps it honest.

## KSP incremental processing — enforced

| | |
|---|---|
| **Owner** | codegen epic (issue #35). Owns `ksp.incremental`, the isolating/aggregating split and this threshold — not the clean-build gate above. |
| **Gate** | `ksp-incremental-budget` job in `.github/workflows/ci.yml` |
| **Hard gate** | a second identical `./gradlew :udea-codegen:testClasses` must execute **no** `:udea-codegen:` task |
| **Threshold** | `UDEA_KSP_COLD_BUDGET_MS` = 90 000, `UDEA_KSP_EDIT_BUDGET_MS` = 40 000 — holding values, ~2.6x and ~3.7x the workstation baselines below, **not** baseline + 20% (see *Why the thresholds are not baseline + 20%*) |
| **Measured on** | developer workstation, Windows 11, 32 logical cores, Corretto 17.0.8, Gradle 8.13, other builds running concurrently |

| Scenario | Command | Measured |
|---|---|---|
| Cold codegen pass | `:udea-codegen:clean` then `:udea-codegen:testClasses --no-build-cache` | 34 285 ms |
| No-op | `:udea-codegen:testClasses` again | 5 124 ms, every task `UP-TO-DATE` |
| One-component edit | touch a fixture component, then `:udea-codegen:testClasses` | 10 767 ms |
| One `src/main` edit to runnable | touch `FieldIo.kt`, then `:udea-codegen:testClasses` | 8 317 ms |

The committed CI thresholds are **provisional**: they were measured on a workstation, and the
job runs on `ubuntu-latest`, so the first green run's numbers — which the job writes to the
step summary every time, pass or fail — should replace them. They are deliberately loose
rather than tight-and-wrong; the gate that carries the real weight is the hard one.

### Why the thresholds are not baseline + 20%

Because 20% of *these* numbers is 20% of the wrong machine. The measurements above come from a
32-core workstation with a stored configuration-cache entry; the job runs on a two-core
`ubuntu-latest` runner with none. Baseline + 20% would be 41 142 ms cold and 12 920 ms for the
edit, and a green run would fail on machine class rather than on a regression — which is how a
gate gets switched off.

So the two numbers are stated for what they are: **holding values**, 90 000 ms and 40 000 ms,
about 2.6x and 3.7x the workstation figures, wide enough that a pass means "nothing
catastrophic" and nothing more. A regression that takes the cold pass from 34s to 89s does get
through, and that is the accepted cost of not having a CI baseline yet.

The +20% rule is not abandoned, it is **blocked on a measurement**: once the `ksp-incremental-budget`
job has a handful of green runs, take the cold and edit numbers from its step summary, record
them here as the CI baseline, and set `UDEA_KSP_COLD_BUDGET_MS` and `UDEA_KSP_EDIT_BUDGET_MS`
to that baseline + 20%. Until then the hard gate below is the one doing the work, and this
paragraph exists so nobody reads "within budget" in a step summary as "within 20% of
baseline".

### Why the hard gate is the one that matters

A timing threshold cannot see the regression this is guarding against. An output whose *name*
varies between runs — `UdeaSerializerRegistry_${System.currentTimeMillis()}`, which is what the
generator being retired emitted — is never recognised as up to date, so every build is a full
rebuild. The first build is exactly as fast either way; only the second one tells you, and
only by re-executing. So the gate asserts task states, not milliseconds.

The other half is the isolating/aggregating split. `udea-codegen` writes one **isolating**
file per `@Replicated` component (invalidated only by an edit to that component's own source)
and exactly one **aggregating** group per module — the `…NetProtocol` constant, the
generated registries and `net-protocol.lock`, which genuinely do depend on every component,
because adding one renumbers the ids of all its successors. One aggregating output per module
is fine; one per component would make every keystroke a full module reprocess.
`IncrementalProcessingTest` audits both halves, because nothing else in the repository would
notice either being got wrong again.

## Wall-clock latency budgets — enforced, in a job with nothing beside them

| | |
|---|---|
| **Gate** | `latency-budgets` job in `.github/workflows/ci.yml`, on `ubuntu-latest` **and** `windows-latest` |
| **Command** | `./gradlew udeaLatencyBudgets --no-parallel --max-workers=1` |
| **Aggregate** | `udeaLatencyBudgets` in the root `build.gradle.kts`; its members are listed there |
| **Wiring gate** | `:udea-gradle:LatencyBudgetJobTest`, which reads the workflow and the root script |
| **Census gate** | `:udea-gradle:WallClockBudgetCensusTest`, which reads every test source in the repository |

Every gate in this repository that asserts a number of milliseconds is on this list, and since
issue #182 that is a checked claim rather than a stated one: `WallClockBudgetCensusTest` reads
every Kotlin test source in the tree and requires each one that touches a wall clock to be either a
member of the aggregate or a row in its own census saying what the reading is instead — a timeout,
a seed, a printed figure or a ratio. A new timing test is red until somebody decides which.

That check exists because the list has been declared complete three times. Until issue #175 these
gates hung off `check`, so each was timed while nineteen other modules compiled on the same cores,
and **a wall-clock measurement taken during a parallel build measures the build**. Some of them
failed on both runner images for that reason alone, on a branch that had touched none of them. #175
enumerated a set, found `udeaDigestBudget` and `udeaQueryBudget` while wiring them, and left two
behind. `review-175-r1` found those two and filed #182. #182's own work found three more that no
issue had named — `AssetCompilerTest`'s one-second warm compile, `PhysicsRebuildTest`'s 2 ms rebuild
and `NetHarnessTest`'s two-second session bound. Every one of those enumerations was honest, and
every one was a snapshot.

They are not on `check` any more, and that is not the same as switching them off: they run on
every push, on both operating systems, as hard gates, in a job that has the runner to itself.
Issue #175's option 3 — running them only where the machine is known — was ranked last by the
issue and is not what this is.

### The measured numbers

Solo, serialised, on the development box: 24 processors, load average 4.8–13.3, Temurin 21.0.11
launcher, JDK 21 toolchain, Gradle 8.13, another project's GL suite running alongside.

| Gate | Task | Budget | Measured (median) | Headroom |
|---|---|---|---|---|
| Snapshot capture, 1 000 entities | `:udea-core:udeaSnapshotBudget` | 1 000 000 ns | 84 272 ns | 11.9x |
| Assembled tick loop, 600 ticks at 200 entities | `:udea-core:udeaBenchTickLoop` | 50 ms | 6.159 ms | 8.1x |
| 200 movers x 60 replays (12 000 `move` calls) | `:udea-core:udeaBenchCharacterMover` | 4.0 ms | 1.85 ms (best of 25) | 2.2x |
| Physics rebuild, 500 bodies | `:udea-core:udeaPhysicsRebuildBudget` | 2 000 us | 549 us | 3.6x |
| Warm validate of one edited script | `:udea-assets-compiler:udeaDaemonBudget` | 300 ms | 128 ms | 2.3x |
| Warm reload decision | `:udea-assets-compiler:udeaDaemonBudget` | 500 ms | 228 ms | 2.2x |
| Graph deserialisation, 2 000 assets | `:udea-assets-compiler:udeaGraphBudget` | 15 ms | 4.79 ms | 3.1x |
| Warm pass-1 scan of the example tree | `:udea-assets-compiler:udeaScanBudget` | 200 ms | 58.08 ms | 3.4x |
| Warm edit of moba's real corpus, edit to observe | `:udea-assets-compiler:udeaWarmEditBudget` | 1 500 ms (deadline 3 000 ms, see below) | 167 ms (max of 5) | 9.0x |
| Tier-0 digest build, 500 entities | `:udea-agent:udeaDigestBudget` | 300 000 ns | 7 810 ns | 38x |
| Entity query, 500 entities returning 20 | `:udea-agent:udeaQueryBudget` | 1 000 000 ns | 21 060 ns | 47x |
| Agent patch to running world, over HTTP | `:udea-agent-host:udeaPhase2Exit` | 1 000 ms | 445 ms | 2.2x |
| Typo'd reference rejected | `:udea-agent-host:udeaPhase2Exit` | 300 ms | 16 ms | 18.8x |

### The warm edit has a deadline and a threshold (issue #184)

`udeaWarmEditBudget` used to fail at **3 000 ms**. It now fails at **1 500 ms**. The 3 000 ms
number did not move and is not this repository's to move: it is spec §6's Phase 2 exit criterion,
*asset edit-to-observe <3s*, a promise to the person editing. What changed is that the gate stopped
using the promise as its tripwire.

**Why.** One constant was doing two jobs. As a product deadline 3 000 ms is right. As a regression
detector it could not fail: the slowest warm edit on this box is a few hundred milliseconds, so the
code had to get more than ten times slower before the gate noticed. That was checked with a real
regression rather than argued. Two changes, together the shape of a daemon that has lost what makes
its warm path warm: `AssetDaemon.reload` recompiles every script in the corpus rather than the one
that changed, and `AssetCompiler` has no compiled-script jar cache. Against the 3 000 ms line that
regression measured a slowest sample of 2 649, 2 948 and 2 976 ms in three solo runs, at one-minute
load averages of 6.55–7.13 on 24 processors. **All three were green.** Against 1 500 ms the same
regression was red in every run made against it, with slowest samples of 3 080, 2 980, 2 926 and
5 389 ms at load 4.40–5.41.

**Why 1 500 ms.** It has to sit above the slowest honest edit on every machine the gate runs on,
and below the fastest sample of that regression. The first was read from the `latency-budgets`
job's own logs for every completed CI run on `example` since #182 put this gate there: 8 runs on
`ubuntu-latest` and 6 on `windows-latest`. (Two more Windows runs stopped at an earlier failing
budget before reaching this one.)

| Runner | Slowest sample per run, oldest run first (ms) | Worst |
|---|---|---|
| `ubuntu-latest` | 258, 263, 265, 236, 219, 273, 270, 330 | 330 |
| `windows-latest` | 485, 455, 428, 326, 674, 277 | 674 |

The fastest sample of the regression, across every run of it, was 2 035 ms. 1 500 ms is 2.2x the
worst CI run and 1.36x below the regression on this box. The regression has not been measured on a
CI runner.

**Under load, unmutated, on this box**, six solo runs of the gate: slowest samples of 172, 233 and
199 ms at load 6.05–6.64, and 247, 248 and 186 ms at load 9.99–10.32 with other builds running. All
green, and the worst is about a sixth of the threshold. Four more runs beside a full parallel
`build` of this repository, which is not where the gate is meant to run, at load 12.73–25.06: 239,
274, 627 and 230 ms. Still green, with the worst 2.4x inside the threshold.

**It stays `samples.max()`.** Issue #182's reasons still hold: the counted samples spread by tens of
percent rather than multiples, and a deadline is a claim about every edit, not a typical one.

**There is no second assertion at 3 000 ms.** 1 500 ms is below it, so a run that passes the
threshold has met the deadline, and an assertion that can only fail after the one before it has
already failed is an assertion that cannot fail. The failure message says whether a red run also
missed the deadline.

**What a stopwatch here cannot see.** The reload recompiling the whole corpus *with* the jar cache
still working measured a slowest sample of 266 ms in one run, against 322 ms for the unmutated gate
a few minutes earlier: no slower. A warm jar cache answers every unchanged script, so at this corpus
size losing incrementality on its own does not show on a stopwatch, and no threshold on this gate
would catch it. That property needs a count rather than a time.

**If the owner disagrees.** Both numbers are constants in `MobaWarmEditBudgetTest`: `CONTRACT_MS`
is the deadline, `REGRESSION_MS` the threshold. Moving the threshold is a one-line diff, and it must
stay below the deadline for the reasoning above to hold. To make 3 000 ms the only line again, set
`REGRESSION_MS` to `CONTRACT_MS`, and accept the tenfold regression above going green.

### Two wall-clock assertions issue #182 dropped rather than moved

Not everything that reads a clock is worth a task. Two of the five #182 found were assertions
whose subject was already covered by something machine-independent in the same file, so they were
deleted and the better assertion left in place. Both are recorded here because a deleted gate is
exactly the kind of thing a later reader assumes was an oversight.

| Was | Where | What asserts it now |
|---|---|---|
| Warm asset compile under 1 s | `AssetCompilerTest` | `assertEquals(scripts.size, warm.cacheHits)` in the same test. The only way the warm path becomes slow is by missing the cache, and the hit count says so exactly, on any machine. |
| A 600-tick 4-client session under 2 s | `NetHarnessTest` | `Thread.sleep` added to `NoWallClockInTransportTest`'s banned list. The bound was a 40x-headroom proxy for "the harness does not sleep"; the source scan asserts that property directly, by file and line. |

The headroom column is the useful one and it is why none of these numbers was widened: every
gate was already inside its budget by a factor, and the reds were contention rather than cost.
It also says what a regression has to be worth before a gate notices — the two `udea-core`
benches would absorb an eight-fold slowdown, so they are floors and not tripwires.

### Warm-up is part of the measurement, and one gate did not have enough

`udeaGraphBudget` measured a median of 9.124 ms on `ubuntu-latest` in one Actions run and 16.140 ms
in the next, on identical bytes, with even its *best* sample at 15.561 ms. Five warm-up `open`s were
five invocations of the measured method, so the samples that followed were partly of a JVM that had
not finished compiling the decoder. On the development box, five runs each, back to back:

| warm-up | medians | best |
|---|---|---|
| 5 | 5.61, 6.30, 8.47, 6.36, 7.70 ms | 5.20 – 7.04 ms |
| 40 | 4.84, 4.94, 5.03, 4.73, 4.79 ms | 4.58 – 4.71 ms |

A 1.51x run-to-run spread becomes 1.06x, and the number falls. **The budget did not move**; the
measurement got honest, and the gate got stricter in the sense that matters — the smallest
regression it can reliably distinguish from noise is now much smaller than the noise band it used
to have.

The same experiment was run against `udeaBenchCharacterMover` and it needs no such change: 5 warm-up
frames and 40 give 2.02–2.18 ms and 2.20–2.30 ms. Its five frames are sixty thousand calls to
`move`, which is why. **Warm-up here is counted in calls to the measured method, not in units of
work** — that is the distinction to check before adding a budget to this list.

### When one goes red

Every failure message ends with `LatencyBudget.contentionNote`: this machine's processor count,
its one-minute load average at the moment the budget was missed, and the command that re-runs
that one task alone. Run it. If it passes alone the machine was busy; if it fails alone the code
is slower, and the remedy is the one the failing test's own KDoc names — never a wider number.

### How to re-measure

```bash
./gradlew udeaLatencyBudgets --no-parallel --max-workers=1
```

Both flags matter: `--no-parallel` stops Gradle running two projects' tasks at once and
`--max-workers=1` stops it starting the next task's worker while one is being timed. To prove a
gate can still fail, make the production code it times genuinely slower and run the one task —
issue #175's branch records one such slowdown per gate with the diff and the resulting number.

## Budgets owned by later epics

These are placeholders. The epic that lands the capability lands the gate, records the
measured number here, and adds the job to `.github/workflows/ci.yml`.

| Budget | Target | Spec | Gate | Measured |
|---|---|---|---|---|
| Asset edit-to-observe | < 3 s | §6 (Phase 2 exit) | `:udea-assets-compiler:udeaWarmEditBudget`, at 1 500 ms | see the warm edit section above |
| Agent edit-to-observe | < 12 s | §6 (Phase 5 exit) | *(agent epic)* | *(not yet measured)* |
| Snapshot capture, 1 000 entities | < 1 ms | §6 (Phase 7 exit) | `:udea-core:udeaSnapshotBudget` | 84 272 ns — see the latency section above |
| Snapshot digest, 500 entities | < 0.3 ms | §6 (Phase 7 exit) | *(determinism epic)* | *(not yet measured)* |

## How to re-measure the clean build

```bash
./gradlew help                                   # warm the daemon; the budget is about compiling, not about JVM startup
./gradlew clean udeaAssemble --no-build-cache    # once untimed, to warm the Kotlin daemon and the JIT
rm -rf .gradle/configuration-cache && ./gradlew clean udeaAssemble --no-build-cache   # time this, several times
```

A number from one machine is only comparable with another number from the same machine. Take
base and head from the same box, interleaved, and compare the fastest of each.

On CI the `clean-build-budget` job does exactly that and writes both fastest samples, their ratio
and the head's cold first build to the job summary on every run, pass or fail. To prove the gate
can still fail, dispatch the workflow manually with `clean_build_plant_functions` set: it plants
that many extra functions in the head's `udea-core`, so the head really does compile more than its
base. 6 000 measured a ratio of 1.118 in probe round 3, just over the tolerance. Use more than
that to see a clear red.
