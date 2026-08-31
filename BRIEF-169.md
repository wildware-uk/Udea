3ea4865

# Issue #169 — the cross-OS `replay-equality` gate now compares something

**`3ea4865`** is the commit under review: every code, build-script and workflow change is at
or below it. `BRIEF-169.md` lands in the one commit on top, so `git rev-parse --short HEAD`
will show that instead, and `git diff 3ea4865 HEAD --stat` names this file and nothing else.

Branch `issue-169-replay-digest-path`, off `origin/example` at `5dc9024`.
Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a30fa1e7426fea7a5`.

`origin/example` has since moved to `1f6cddd`; the three commits I am behind are `664220c`
"adding logo", `0240d72` a merge, and `1f6cddd` "Use the SVG logo in the README". None of them
touches `.github/workflows/ci.yml` or `udea-replay`.

Working files referenced below live in
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/20843ffd-9b18-44a2-bc3a-b290e74d1509/scratchpad/dev-169/`,
written as `scratchpad/dev-169/…` for brevity.

---

## 1. The evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew \
  :udea-replay:test --tests 'dev.wildware.udea.replay.equality.ReplayEqualityPathsTest' \
                    --tests 'dev.wildware.udea.replay.equality.ReplayEqualityProofTest' \
  :udea-replay:udeaReplayDigest -Pudea.replay.label=local/leg-a \
  -Pudea.replay.out=digests/leg-a.udeaeq --console=plain \
  && ls -l digests/leg-a.udeaeq
```

It does both halves of the ticket in one paste. The tests resolve the workflow's own
`-Pudea.replay.out` string through the entry point CI runs and compare the answer with the
directory `actions/upload-artifact` globs; the task then physically reproduces a CI leg with the
workflow's own relative path, and the `ls` asserts the bytes are where the upload step looks.

It leaves `digests/leg-a.udeaeq` at the repository root. That is the point of it; `rm -rf digests`
cleans up.

### It goes red when the feature is reverted

Reverting `ReplayEqualityPaths.resolve` to the pre-#169 semantics — the literal diff is mutation
**M1** in §8 — and running the identical command. Spliced from
`scratchpad/dev-169/evidence-red.txt`, lines 78-96, one contiguous run:

```
  1626950 bytes at /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a30fa1e7426fea7a5/udea-replay/digests/leg-a.udeaeq

> Task :udea-replay:test FAILED

ReplayEqualityPathsTest > a real leg writes a readable stream at the resolved path and says where it went() FAILED
    org.opentest4j.AssertionFailedError at ReplayEqualityPathsTest.kt:179

ReplayEqualityPathsTest > a relative timing path is resolved the same way as the digest() FAILED
    org.opentest4j.AssertionFailedError at ReplayEqualityPathsTest.kt:64

ReplayEqualityPathsTest > a relative out is resolved against the workspace, not against the process directory() FAILED
    org.opentest4j.AssertionFailedError at ReplayEqualityPathsTest.kt:44

ReplayEqualityProofTest > the join compares the directory the workflow downloads into() FAILED
    org.opentest4j.AssertionFailedError at ReplayEqualityProofTest.kt:240

ReplayEqualityProofTest > a leg's digest lands in the directory its upload step globs() FAILED
    org.opentest4j.AssertionFailedError at ReplayEqualityProofTest.kt:212

20 tests completed, 5 failed
```

The same command on the branch as it stands, from `scratchpad/dev-169/evidence-green.txt`
(`[…]` marks the four elided lines of Gradle's deprecation notice):

```
> Task :udea-replay:udeaReplayDigest
local/leg-a: replayed 3600 tick(s) in 440ms into leg-a.udeaeq
  1626950 bytes at /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a30fa1e7426fea7a5/digests/leg-a.udeaeq

> Task :udea-replay:test
[…]
BUILD SUCCESSFUL in 3s
44 actionable tasks: 2 executed, 2 from cache, 40 up-to-date
Configuration cache entry stored.
-rw-rw-r-- 1 shaun users 1626950 Aug 31 17:14 digests/leg-a.udeaeq
== command exit status: 0 ==
```

Same command, same 1626950 bytes, two different directories. That is the whole defect.

The failing message of the criterion-3 test names the two directories outright. Captured from
that run's JUnit XML into `scratchpad/dev-169/M1-failure-messages.txt`, one line, wrapped here
only by your reader:

```
org.opentest4j.AssertionFailedError: the leg writes its digest to a directory the upload step does not glob. That is issue #169: the upload trips `if-no-files-found: error`, and because `replay-equality-join` declares `needs: replay-equality` the join never runs and nothing is ever compared. ==> expected: </srv/ssd1/workspace/Udea/.claude/worktrees/agent-a30fa1e7426fea7a5/digests> but was: </srv/ssd1/workspace/Udea/.claude/worktrees/agent-a30fa1e7426fea7a5/udea-replay/digests>
```

---

## 2. What I did, and why

### The defect

`udeaReplayDigest` is a `JavaExec` with no `workingDir`, so it inherits the **project** directory.
`-Pudea.replay.out=digests/<leg>.udeaeq` therefore resolved under `udea-replay/`, while
`actions/upload-artifact` globs `digests/*.udeaeq` under `$GITHUB_WORKSPACE`. Both sides spelled
the same relative path and neither was wrong on its own terms. The upload tripped
`if-no-files-found: error`; `replay-equality-join` declares `needs: replay-equality`; the join has
never executed on any run of this repository.

**The join carried the identical defect and the issue does not mention it.** The join passes
`-Pudea.replay.streams=digests`, and `udeaReplayEquals` is also a `JavaExec` with no `workingDir`,
so even with the leg fixed the join's first-ever execution would have looked in
`udea-replay/digests`, found nothing, and exited `EXIT_UNUSABLE`. I looked for the class rather
than fixing the instance: those two are every place in this job where a relative path is handed to
a JVM. The third pair — the join's `summary=udea-replay/build/reports/…` and the report directory
the build script declares — already agreed, and is now pinned by a test that derives one from the
other. That is the whole sweep; there is nothing else of this kind in `ci.yml` or in
`udea-replay/build.gradle.kts`.

### The fix

`ReplayEqualityPaths` (new, `udea-replay/src/main`) is the one place that turns a caller's path
into a real one, and it takes the base as an argument rather than inheriting it.
`udeaReplayDigest` and `udeaReplayEquals` pass `--workspace <rootProject dir>`, which is what
`actions/checkout` makes `$GITHUB_WORKSPACE` and is also where `./gradlew` is normally typed.

Taking the base as an argument rather than setting `workingDir` is what makes the rule reachable
from a test: `ReplayEqualityProofTest` hands the real entry point the `-Pudea.replay.out` value
parsed out of `ci.yml` and compares the answer with the upload glob. A `workingDir` line would fix
the same bug and could only have been asserted by a string match. The full reasoning, the two
alternatives I rejected, and what to change if the owner disagrees are on the issue:
<https://github.com/wildware-uk/Udea/issues/169#issuecomment-5482016565>

### Loud at the source

`ReplayEqualityPaths.requireStreamWritten` is a post-condition on every digest run: the stream must
exist and be non-empty, or the run fails naming the absolute path, the raw `--out` it was given,
and the workspace it resolved against. The success line now prints the absolute path rather than
echoing the argument — the CI log line is
`1626969 bytes at /home/runner/work/Udea/Udea/digests/ubuntu-latest-temurin.udeaeq`, which is the
one fact that would have explained the original failure and which no leg printed.

`ReplayEqualsMain.run` now names paths that are not readable digest streams before it counts them.
Previously a directory that did not exist arrived as one unreadable path and was reported as
"needs at least two digest streams; got 1" — a true sentence about the wrong subject.

**What that guard does not cover, stated plainly.** Nothing a caller can hand today's
`ReplayDigestRecorder` makes it return having written nothing; the paths that could go wrong throw
an `IOException` from inside the write. So the guard's *condition* is unit-tested in both
directions and its *placement* is asserted by reading `DriftDigestMain.kt` for the call — mutation
**M4**. It guards against a future change, and no behaviour available today reaches it. This is a
"merely untested" claim rather than an "untestable" one only in the sense that the fixture would
have to be a fake recorder; I judged injecting one into a fixture entry point worse than saying so.

### Proving the gate can still fail

`replay_plant_ulp_at` is a `workflow_dispatch` input that plants a one-ulp divergence on the single
matrix leg marked `plant: true`. It follows this workflow's existing idiom — `clean_build_budget_ms`
is documented in the same `on:` block as "Set it to 1 to prove the gate still fails". One leg and
not three is deliberate and is asserted by a test: a plant is deterministic, so three legs all
carrying it agree with each other, the join reports EQUAL, and the run meant to go red comes back
green.

### A near-miss I caused and then fenced

Writing the phrase `digests` followed by a slash and a star into a KDoc in
`udea-replay/build.gradle.kts` opened a **nested** block comment. Kotlin's nest, so that KDoc's own
terminator closed only the inner one and the rest of the file — all eight `udeaReplay*` task
registrations — became comment. `sh gradlew build` stayed green (none of those tasks is in
`check`). `:udea-replay:test` stayed green, because `ReplayEqualityProofTest` was matching text
that was all still present and merely switched off. Only `gradlew :udea-replay:tasks` saw it.

Measured rather than asserted: with the pre-hardening test file (checked out from `10e10f3`),
commenting out the whole `replay-equality` section produced **0** failing tests —
`scratchpad/dev-169/M7-before-hardening.failing` is a zero-byte file. With the hardened one the
same mutation produces **4** (row **M8**). The fence now reads the build script with its comments
removed — nesting and string literals handled — and the workflow's step assertions read `ci.yml`
with its comment lines dropped. The one test that is *about* a comment's content still reads the
raw file. Both directions of the stripper have their own test, including the nested-opener case
that caused this.

This is a weakness that test class has carried since #152; my change produced the input that
exposed it. Also on the issue:
<https://github.com/wildware-uk/Udea/issues/169#issuecomment-5482018569>

### One test I had to fix after watching a mutation

The first M1 run turned the leg's test red and left the join's test green: both sides of the join's
comparison went through `ReplayEqualityPaths.resolve`, so they agreed with each other however wrong
that function was. The download side now uses `Path.resolve` directly (commit `7db3260`), and M1
turns both red.

---

## 3. `sh gradlew build`

Clean build, no exclusions. `sh gradlew clean` and then `sh gradlew build`, from
`scratchpad/dev-169/build-clean.txt`:

```
BUILD SUCCESSFUL in 24s
204 actionable tasks: 118 executed, 80 from cache, 6 up-to-date
Configuration cache entry reused.
=== gradlew exit status: 0 ===
```

Counted from the test-result XMLs in the tree afterwards: **2486 tests, 0 failures, 0 errors,
34 skipped** across 370 result files. (The recorded baseline of 2447 at `8035374` predates several
merged tickets as well as the 15 tests this branch adds; I have not re-run that SHA.)

### The wall-clock budget tasks, honestly

That clean build served the four budget tasks `FROM-CACHE`, so it did not re-execute them. An
earlier full `build`, run while my own mutation pass and melon-merge were both on the box, failed
three of them — `udeaBenchCharacterMover`, `udeaPhase2Exit`, `udeaDaemonBudget`, all on the
developer contract's list of four. Forcing all four with `--rerun` while another Udea worktree was
building (load rose to 33.74) gave, from `scratchpad/dev-169/budgets-rerun.txt`:

```
> Task :udea-core:udeaBenchCharacterMover

CharacterMoverBudgetTest > 200 movers replayed 60 times fit in the per-frame budget() STANDARD_OUT
    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) median 3.459ms, budget 4.0ms

> Task :udea-assets-compiler:udeaPackGate

GraphBudgetTest > deserialising a graph larger than the example tree stays inside the budget() STANDARD_OUT
    graph deserialisation: best=5.547341ms median=7.680527ms over 2000 assets (budget 15ms)

> Task :udea-assets-compiler:udeaDaemonBudget FAILED

DaemonLatencyBudgetTest > a warm reload of one script decides inside the edit-to-observe budget() STANDARD_OUT
    warm reload decision: median 538ms over 4 samples [626, 538, 254, 378]

DaemonLatencyBudgetTest > a warm reload of one script decides inside the edit-to-observe budget() FAILED
    org.opentest4j.AssertionFailedError at DaemonLatencyBudgetTest.kt:97

DaemonLatencyBudgetTest > a warm validate of one edited script is under 300ms() STANDARD_OUT
    warm validate of one script: median 258ms over 4 samples [29, 258, 205, 312]

2 tests completed, 1 failed
```

`udeaPhase2Exit` passed too; only `udeaDaemonBudget` failed, on its warm-reload test.
`udeaDaemonBudget` alone, after `scratchpad/dev-169/quiet-then.sh` waited 190 seconds for six
consecutive five-second samples with no `gradlew` client in any workspace on this box (that
wait line went to the script's stdout and is in no file, so it is prose here rather than a
transcript; the load average at the moment it ran was 13.31). From
`scratchpad/dev-169/daemonbudget-solo.txt`:

```
    warm reload decision: median 207ms over 4 samples [233, 207, 146, 144]
```
```
    warm validate of one script: median 133ms over 4 samples [11, 137, 126, 133]
```
```
BUILD SUCCESSFUL in 11s
```

538ms under load, 207ms alone. That is the box, not this branch, and it is the task the developer
contract names first. Nothing in this branch is on any path those four tasks exercise.

### The five-process local proof still holds, both ways

`sh gradlew :udea-replay:udeaReplayEqualityProof` drives both entry points I changed, across five
JVMs. Last line, from `scratchpad/dev-169/replay-proof.txt`:

```
replay-equality proof PASSED: two honest legs agree (exit 0); the planted leg fails (exit 1) naming Drifter.x at t1200, with five ticks of history.
```

### GL

**This ticket touches no GL.** The diff is `udea-replay` (headless replay and digest IO), its build
script, and `ci.yml`; nothing in `udea-render`, nothing in the render half of `udea-agent-host`,
nothing that opens a context. I ran it anyway rather than argue the point, and forced both tasks
with `--rerun` because the first attempt served `udeaGlTest` `FROM-CACHE` and a cached skip would
have looked exactly like a pass:

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew :udea-render:udeaGlTest --rerun :udea-agent-host:udeaAgentGlTest --rerun \
  -Pudea.render.requireGl=true --console=plain
```

```
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest

BUILD SUCCESSFUL in 18s
41 actionable tasks: 2 executed, 39 up-to-date
```

Counted from the result XMLs of that run: `udeaGlTest` 4 classes / **18 tests** / 0 failed /
**0 skipped**; `udeaAgentGlTest` 2 classes / **8 tests** / 0 failed / **0 skipped**. Zero skipped is
the number that matters — with an empty `$DISPLAY` and `-Pudea.render.requireGl` at its default
these skip silently and the build stays green.

---

## 4. The Actions runs

Nobody can run Actions locally, so these are the second half of the evidence.
**Judge them by the four `replay-equality` jobs only.**

| Run | Trigger | The four jobs | Everything else |
|---|---|---|---|
| [33418566690](https://github.com/wildware-uk/Udea/actions/runs/33418566690) | push, `429f091` | 3 legs **success**, join **success** | red on #170 |
| [33419266780](https://github.com/wildware-uk/Udea/actions/runs/33419266780) | `workflow_dispatch`, `replay_plant_ulp_at=1200` | 3 legs **success**, join **failure**, as intended | red on #170 |

**Both runs are red overall and that is not this branch.** Every job that builds `moba` fails with
25 x `UDEA0032` on unstaged licensed art — issue #170, filed, nobody working it this wave.

The baseline is checkable rather than asserted. Run
[33415872451](https://github.com/wildware-uk/Udea/actions/runs/33415872451) is `origin/example` at
`5dc9024`, my exact branch point. Its job outcomes, saved as
`scratchpad/dev-169/baseline-5dc9024-jobs.txt`:

```
failure	build (ubuntu-latest)
failure	build (windows-latest)
failure	build with the K2 plugin disabled
failure	clean build under budget
failure	determinism (ubuntu-latest, corretto)
failure	determinism (ubuntu-latest, temurin)
failure	determinism (windows-latest, corretto)
failure	determinism (windows-latest, temurin)
failure	game-bridge-mcp conformance
failure	gl tests (xvfb)
failure	replay-equality (ubuntu-latest, corretto)
failure	replay-equality (ubuntu-latest, temurin)
failure	replay-equality (windows-latest, temurin)
failure	the FIR checkers fail a real build
skipped	kotlin upgrade probe (non-blocking)
skipped	replay-equality (join)
success	agent brief matches the tree
success	KSP stays incremental
success	migration ledger
```

Three `replay-equality` legs **failure**, join **skipped** — the issue's report, at my branch point.
On my branch those four rows are the only ones that changed; every other row has the same outcome
as the baseline.

---

## 5. The images

In `/srv/ssd1/workspace/Udea/build/debug-screenshots/`.

This ticket has no game-visual surface — nothing renders and no entity moves — so rather than take
a screenshot of something unrelated, these are typeset renderings of captured program output. Each
image states on its face which file it was spliced from, and those files are on disk in
`scratchpad/dev-169/`. `scratchpad/dev-169/render.py` is the renderer; it invents no text.

- **`issue169-where-the-digest-lands.png`** — the same one-line evidence command with the fix
  reverted and with it in place: the identical 1626950 bytes written to
  `.../udea-replay/digests/leg-a.udeaeq` and to `.../digests/leg-a.udeaeq`. The defect and the fix
  in one frame. From `evidence-red.txt` and `evidence-green.txt`.
- **`issue169-join-verdict-green.png`** — the join's verdict on run 33418566690: three legs of
  `drift-3600.udearep`, Windows Server 2025 and Linux on Corretto and Temurin, cell-for-cell
  identical over 3600 ticks. The first verdict this gate has ever produced. From
  `join-verdict-green.txt`.
- **`issue169-join-verdict-planted.png`** — the same join on run 33419266780 with the plant: FAILED
  at t1200, `NetId(#0@0) dev.wildware.udea.replay.equality.fixture.Drifter.x`, `0x40d21533` against
  `0x40d21532`, and five preceding ticks that agreed. From `join-verdict-planted.txt`.

---

## 6. The issue, criterion by criterion

### [x] A real Actions run on the branch shows both `replay-equality` legs green **and `replay-equality-join` executed**, with its verdict in the job summary. Link the run.

Run **33418566690**, push of `429f091`:
<https://github.com/wildware-uk/Udea/actions/runs/33418566690>

```
replay-equality (ubuntu-latest, temurin)	success
replay-equality (ubuntu-latest, corretto)	success
replay-equality (windows-latest, temurin)	success
replay-equality (join)	success
```

The matrix has three legs, not two — the issue says "both legs", but `ci.yml` has carried a third,
`ubuntu-latest / corretto`, since #152. All three are green.

The join's "List what arrived" step, spliced from `scratchpad/dev-169/run-33418566690.log` lines
11906-11912, with the ANSI codes, the job/step prefix and the timestamps removed:

```
total 4784
drwxr-xr-x  2 runner runner    4096 Aug 31 17:20 .
drwxr-xr-x 32 runner runner    4096 Aug 31 17:20 ..
-rw-r--r--  1 runner runner 1626969 Aug 31 17:20 ubuntu-latest-corretto.udeaeq
-rw-r--r--  1 runner runner 1626969 Aug 31 17:20 ubuntu-latest-temurin.udeaeq
-rw-r--r--  1 runner runner 1626979 Aug 31 17:20 windows-latest-temurin.udeaeq
3 digest stream(s)
```

Its "Compare every leg" step, same log, lines 12014-12028 contiguous, same treatment:

```
> Task :udea-replay:udeaReplayEquals
replay-equality over 3 leg(s) of 'drift-3600.udearep', 3600 tick(s) from t0
  ubuntu-latest/corretto-17  [Linux amd64; Amazon.com Inc. OpenJDK 64-Bit Server VM 17.0.20.1]
  ubuntu-latest/temurin-17  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20.1]
  windows-latest/temurin-17  [Windows Server 2025 amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20.1]

replay equality holds: 3600 tick(s) of 'drift-3600.udearep' are cell-for-cell identical
  fixture drift-3600.udearep
  A = 'ubuntu-latest/corretto-17'  [Linux amd64; Amazon.com Inc. OpenJDK 64-Bit Server VM 17.0.20.1]
  B = 'ubuntu-latest/temurin-17'  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20.1]

replay equality holds: 3600 tick(s) of 'drift-3600.udearep' are cell-for-cell identical
  fixture drift-3600.udearep
  A = 'ubuntu-latest/corretto-17'  [Linux amd64; Amazon.com Inc. OpenJDK 64-Bit Server VM 17.0.20.1]
  B = 'windows-latest/temurin-17'  [Windows Server 2025 amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20.1]
```

and, from line 12039 of the same log, `BUILD SUCCESSFUL in 1m 10s`.

**On the job summary.** The "Publish the verdict" step is unchanged by this branch: it `cat`s
`udea-replay/build/reports/udea/replay-equality/summary.md` into `$GITHUB_STEP_SUMMARY`, and
`ReplayEqualsMain` writes that file with the identical rendered text quoted above. The step ran and
the job is green. What I can splice is the log; I cannot splice the rendered summary page itself
from an artefact on this box, and I am not going to retype it — the summary page is on the run.

And the leg step that used to fail. Its command, log line 1000:

```
##[group]Run ./gradlew :udea-replay:udeaReplayDigest -Pudea.replay.label=ubuntu-latest/temurin-17 -Pudea.replay.jvmVendor=Adoptium -Pudea.replay.out=digests/ubuntu-latest-temurin.udeaeq  --stacktrace
```

and its output, lines 1011-1013, a separate contiguous run 11 lines later:

```
> Task :udea-replay:udeaReplayDigest
ubuntu-latest/temurin-17: replayed 3600 tick(s) in 451ms into ubuntu-latest-temurin.udeaeq
  1626969 bytes at /home/runner/work/Udea/Udea/digests/ubuntu-latest-temurin.udeaeq
```

The double space before `--stacktrace` is the `replay_plant_ulp_at` expression rendering empty,
which is what it must do on every ordinary run.

Windows, where a `--workspace` argument could most easily have gone wrong:

```
windows-latest/temurin-17: replayed 3600 tick(s) in 653ms into windows-latest-temurin.udeaeq
  1626979 bytes at D:\a\Udea\Udea\digests\windows-latest-temurin.udeaeq
```
```
Artifact replay-digest-windows-latest-temurin has been successfully uploaded! Final size is 1564591 bytes. Artifact ID is 9768109187
```

### [x] A test asserts a digest run that produces no stream fails at the point it was produced, naming the path it expected.

`ReplayEqualityPathsTest`:

- `a digest run that wrote no stream fails naming the path it expected` — asserts the message
  carries the resolved absolute path, the raw `--out`, and the workspace.
- `a digest run that left an empty stream fails naming the path it expected`
- `a stream with bytes in it passes and reports its size` — the control, so the guard is not one
  that fires on a healthy run.
- `the digest entry point still asks whether it wrote anything` — that `DriftDigestMain` calls it.

Mutations **M2** (guard reports but never refuses → the first two red) and **M4** (`main` stops
calling it → the fourth red). The limitation is stated in §2 rather than papered over: no input
available to today's recorder reaches the guard, so its condition is what is behaviourally tested
and its placement is a source assertion.

### [x] `ReplayEqualityProofTest` (or a sibling) asserts the workflow's `--out` path and the upload glob resolve to the same directory, so the two cannot drift apart again.

`ReplayEqualityProofTest.a leg's digest lands in the directory its upload step globs`. It parses
`-Pudea.replay.out=` out of `ci.yml`, stands in for the `matrix.*` expressions (and refuses to
proceed if any other Actions expression is left unexpanded, so it cannot compare a string no runner
ever sees), drives `DriftDigestMain.parse` with `--workspace` set to the checkout root, parses the
`path:` of the step named "Upload this leg's digest stream", and compares the directories and
matches the filename against the glob.

Its sibling `the join compares the directory the workflow downloads into` does the same for
`-Pudea.replay.streams` against the download step's `path:` — the instance the issue does not
mention. `the workflow reads the verdict out of the file the join writes` derives the third pair
from the build script rather than asserting both halves separately. `both entry points the workflow
runs are told which directory the workspace is` covers the one line in the build script no test can
execute.

Mutations **M1** (both directory tests red), **M6** (upload globs elsewhere → the leg's red), **M7**
(join pointed elsewhere → the join's red), **M3** (`--workspace` dropped from the build script),
**C2** (a second real step passing the property → red). Control **C1** confirms prose naming
`-Pudea.replay.out=` and a comment line reading `plant: true` leave all of them green.

### [x] The join's divergence rendering is shown to still work — plant a divergence (`-Pudea.replay.plantUlpAt`) and show the run going red with the field named.

Run **33419266780**, `workflow_dispatch` with `replay_plant_ulp_at=1200`:
<https://github.com/wildware-uk/Udea/actions/runs/33419266780>

The plant reached exactly one leg — from `scratchpad/dev-169/run-33419266780.log`, the corretto
leg's step:

```
##[group]Run ./gradlew :udea-replay:udeaReplayDigest -Pudea.replay.label=ubuntu-latest/corretto-17 -Pudea.replay.jvmVendor=Amazon -Pudea.replay.out=digests/ubuntu-latest-corretto.udeaeq -Pudea.replay.plantUlpAt=1200 --stacktrace
```
```
  PLANTED: one ulp on Drifter.x of the lead drifter, which is the magnitude determinism-audit.md section 3.1 measured Math.sin differing from StrictMath.sin by, at t1200
```

A grep of that log for `plantUlpAt` and `PLANTED` across all three legs returns those two lines and
nothing else: the other two legs carried no plant.

The join went red naming the field. Spliced from `scratchpad/dev-169/run-33419266780-failed.log`
lines 4129-4151, one contiguous run, prefixes and timestamps stripped:

```
> Task :udea-replay:udeaReplayEquals FAILED
replay-equality over 3 leg(s) of 'drift-3600.udearep', 3600 tick(s) from t0
  ubuntu-latest/corretto-17  [Linux amd64; Amazon.com Inc. OpenJDK 64-Bit Server VM 17.0.20.1]
  ubuntu-latest/temurin-17  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20.1]
  windows-latest/temurin-17  [Windows Server 2025 amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20.1]

replay equality FAILED at t1200 (1200 tick(s) matched first)
  fixture drift-3600.udearep
  A = 'ubuntu-latest/corretto-17'  [Linux amd64; Amazon.com Inc. OpenJDK 64-Bit Server VM 17.0.20.1]
  B = 'ubuntu-latest/temurin-17'  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20.1]
  world hash: 8365181117672703832 against -7000854319554458987
  1 differing cell(s):
    NetId(#0@0) dev.wildware.udea.replay.equality.fixture.Drifter.x
      A = 6.565088 (0x40d21533)
      B = 6.5650873 (0x40d21532)
      the preceding 5 tick(s) of this cell:
        t1195  agreed  A = 7.066144 (0x40e21dda), B = 7.066144 (0x40e21dda)
        t1196  agreed  A = 6.9657426 (0x40dee75d), B = 6.9657426 (0x40dee75d)
        t1197  agreed  A = 6.8644977 (0x40dba9f7), B = 6.8644977 (0x40dba9f7)
        t1198  agreed  A = 6.763905 (0x40d871e9), B = 6.763905 (0x40d871e9)
        t1199  agreed  A = 6.6647997 (0x40d5460a), B = 6.6647997 (0x40d5460a)
```

The planted leg diverges from **both** honest legs; the same log carries the second comparison,
against `windows-latest/temurin-17`, with the identical differing cell. The tick, the entity, the
component and field, and five ticks of history — spec 7's four.

---

## 7. Regenerated files

**None.** `udea-codegen/net-protocol.lock` and
`udea-codegen/src/test/resources/expected-generated-hashes.txt` are untouched: this branch adds no
replicated component and does not go near `udea-codegen`, `udea-net` or `udea-annotations`, which
`dev-167` owns this wave.

`git diff --stat origin/example...HEAD`, saved as `scratchpad/dev-169/diffstat.txt`:

```
 .github/workflows/ci.yml                           |  25 ++
 udea-replay/build.gradle.kts                       |  39 ++-
 .../udea/replay/equality/ReplayEqualityPaths.kt    |  85 +++++++
 .../udea/replay/equality/ReplayEqualsMain.kt       |  67 ++++-
 .../replay/equality/ReplayEqualityPathsTest.kt     | 184 ++++++++++++++
 .../replay/equality/ReplayEqualityProofTest.kt     | 277 ++++++++++++++++++++-
 .../replay/equality/fixture/DriftDigestMain.kt     |  96 +++++--
 7 files changed, 742 insertions(+), 31 deletions(-)
```

No `docs/contracts/` file is touched. No module moved, so `AGENTS.md`'s module table is unchanged
and `udeaVerifyAgentsMd` passes inside `build`.

---

## 8. Mutation table

Every row carries the literal `git diff` of the mutation as applied, from the run that produced the
failing-test names beside it. All ten were run in one pass at `3ea4865` by
`scratchpad/dev-169/mutations.py`, and byte-for-byte identically at `429f091` before it
(`scratchpad/dev-169/mutation-table-429f091.md` and `mutation-table.md` `diff` clean — the
commit between the two is a comment in a test file); the raw Gradle logs are `scratchpad/dev-169/M*.log` and `C*.log`.
Failing test names come from parsing the JUnit result XML, not from grepping for `FAILED` — an
unanchored `grep -c FAILED` also matches `BUILD FAILED`.


### M1 — ReplayEqualityPaths.resolve ignores the workspace (the pre-#169 semantics)

```diff
diff --git a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEqualityPaths.kt b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEqualityPaths.kt
index 14ab3d8..a5dba38 100644
--- a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEqualityPaths.kt
+++ b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEqualityPaths.kt
@@ -40,9 +40,7 @@ public object ReplayEqualityPaths {
      * per process.
      */
     public fun resolve(workspace: Path, path: String): Path {
-        val requested = Path.of(path)
-        val resolved = if (requested.isAbsolute) requested else workspace.resolve(requested)
-        return resolved.toAbsolutePath().normalize()
+        return Path.of(path).toAbsolutePath().normalize()
     }
 
     /**
```

Gradle exit `1`; 5 failing test(s):

- `ReplayEqualityPathsTest.a real leg writes a readable stream at the resolved path and says where it went()`
- `ReplayEqualityPathsTest.a relative timing path is resolved the same way as the digest()`
- `ReplayEqualityPathsTest.a relative out is resolved against the workspace, not against the process directory()`
- `ReplayEqualityProofTest.the join compares the directory the workflow downloads into()`
- `ReplayEqualityProofTest.a leg's digest lands in the directory its upload step globs()`

### M2 — the write post-condition reports but never refuses

```diff
diff --git a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEqualityPaths.kt b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEqualityPaths.kt
index 14ab3d8..a7cf6ea 100644
--- a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEqualityPaths.kt
+++ b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEqualityPaths.kt
@@ -69,17 +69,6 @@ public object ReplayEqualityPaths {
      */
     public fun requireStreamWritten(requested: String, workspace: Path, output: Path): Long {
         val absolute = output.toAbsolutePath().normalize()
-        check(Files.isRegularFile(absolute)) {
-            "the digest run wrote no stream: nothing is at '$absolute'. --out was '$requested', " +
-                "resolved against the workspace '$workspace'. Nothing downstream can upload or " +
-                "compare a file that is not there."
-        }
-        val size = Files.size(absolute)
-        check(size > 0L) {
-            "the digest run left an empty stream at '$absolute'. --out was '$requested', " +
-                "resolved against the workspace '$workspace'. A zero-byte digest is not a leg " +
-                "of the gate; it is a leg that failed quietly."
-        }
-        return size
+        return if (Files.isRegularFile(absolute)) Files.size(absolute) else 0L
     }
 }
```

Gradle exit `1`; 2 failing test(s):

- `ReplayEqualityPathsTest.a digest run that wrote no stream fails naming the path it expected()`
- `ReplayEqualityPathsTest.a digest run that left an empty stream fails naming the path it expected()`

### M3 — udeaReplayDigest stops passing --workspace

```diff
diff --git a/udea-replay/build.gradle.kts b/udea-replay/build.gradle.kts
index e0ad4ae..690117b 100644
--- a/udea-replay/build.gradle.kts
+++ b/udea-replay/build.gradle.kts
@@ -190,8 +190,6 @@ tasks.register<JavaExec>("udeaReplayDigest") {
     val workspace = workspaceRoot
     argumentProviders.add {
         buildList {
-            add("--workspace")
-            add(workspace)
             add("--label")
             add(label.get())
             add("--out")
```

Gradle exit `1`; 1 failing test(s):

- `ReplayEqualityProofTest.both entry points the workflow runs are told which directory the workspace is()`

### M4 — DriftDigestMain stops calling the post-condition

```diff
diff --git a/udea-replay/src/testFixtures/kotlin/dev/wildware/udea/replay/equality/fixture/DriftDigestMain.kt b/udea-replay/src/testFixtures/kotlin/dev/wildware/udea/replay/equality/fixture/DriftDigestMain.kt
index fffea93..734bb2b 100644
--- a/udea-replay/src/testFixtures/kotlin/dev/wildware/udea/replay/equality/fixture/DriftDigestMain.kt
+++ b/udea-replay/src/testFixtures/kotlin/dev/wildware/udea/replay/equality/fixture/DriftDigestMain.kt
@@ -134,7 +134,7 @@ public object DriftDigestMain {
         // The post-condition, before anything downstream is allowed to assume it. A leg that
         // wrote nothing has to say so here, where it knows the path, rather than leave the
         // upload step two lines later to report a glob that matched nothing.
-        val size = ReplayEqualityPaths.requireStreamWritten(options.requestedOut, options.workspace, output)
+        val size = java.nio.file.Files.size(output)
 
         val summary = buildString {
             append(options.label).append(": ").append(run.describe())
```

Gradle exit `1`; 1 failing test(s):

- `ReplayEqualityPathsTest.the digest entry point still asks whether it wrote anything()`

### M5 — a second matrix leg carries the plant

```diff
diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml
index 7297d0b..268bec6 100644
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@ -1075,6 +1075,7 @@ jobs:
           - os: windows-latest
             distribution: temurin
             vendor: Adoptium
+            plant: true
           # The second JVM, on the OS whose runners are cheapest. `determinism-audit.md` §3.1
           # measured `Math.sin` disagreeing with `StrictMath.sin` on 3.4% of sampled inputs on a
           # single JVM; two independent builds are under no obligation to disagree in the same
```

Gradle exit `1`; 1 failing test(s):

- `ReplayEqualityProofTest.exactly one leg carries the planted divergence()`

### M6 — the upload step globs a different directory

```diff
diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml
index 7297d0b..4cee796 100644
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@ -1132,7 +1132,7 @@ jobs:
         uses: actions/upload-artifact@v4
         with:
           name: replay-digest-${{ matrix.os }}-${{ matrix.distribution }}
-          path: digests/*.udeaeq
+          path: replay-digests/*.udeaeq
           if-no-files-found: error
           retention-days: 7
 
```

Gradle exit `1`; 1 failing test(s):

- `ReplayEqualityProofTest.a leg's digest lands in the directory its upload step globs()`

### M7 — the join is pointed at a different directory

```diff
diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml
index 7297d0b..dd54f54 100644
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@ -1209,7 +1209,7 @@ jobs:
         shell: bash
         run: >-
           ./gradlew :udea-replay:udeaReplayEquals
-          -Pudea.replay.streams=digests
+          -Pudea.replay.streams=other-digests
           --stacktrace
 
       # The rendered verdict, on a green run as well as a red one. A gate you can only read once
```

Gradle exit `1`; 1 failing test(s):

- `ReplayEqualityProofTest.the join compares the directory the workflow downloads into()`

### M8 — the whole replay-equality section of the build script is commented out

```diff
diff --git a/udea-replay/build.gradle.kts b/udea-replay/build.gradle.kts
index e0ad4ae..15974af 100644
--- a/udea-replay/build.gradle.kts
+++ b/udea-replay/build.gradle.kts
@@ -114,6 +114,7 @@ tasks.withType<Test>().configureEach {
 // What `check` does carry is `:udea-replay:test`, which holds `CrossPlatformDivergenceTest`,
 // `DivergenceReportFormatTest`, `ReplayDigestTest` and `ReplayEqualityProofTest`.
 
+/* MUTATION: the whole replay-equality section switched off, as the nested KDoc did.
 val replayEqualityDir: Provider<Directory> =
     layout.buildDirectory.dir("reports/udea/replay-equality")
 
@@ -386,3 +387,5 @@ tasks.register("udeaReplayEqualityProof") {
         )
     }
 }
+
+*/
```

Gradle exit `1`; 4 failing test(s):

- `ReplayEqualityProofTest.both entry points the workflow runs are told which directory the workspace is()`
- `ReplayEqualityProofTest.the proof task asserts on the four things a cross-OS failure has to name()`
- `ReplayEqualityProofTest.the proof task plants at the tick the fixture declares()`
- `ReplayEqualityProofTest.the workflow reads the verdict out of the file the join writes()`

### C1 — CONTROL: prose only - a comment naming the property and the plant flag

```diff
diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml
index 7297d0b..8645b72 100644
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@ -1117,6 +1117,9 @@ jobs:
       #
       # The trailing expression is empty on every ordinary run. It is how `replay_plant_ulp_at`
       # reaches the one leg the matrix marks `plant: true`.
+      # CONTROL: prose only.
+          # plant: true
+      # -Pudea.replay.out=somewhere-else/x.udeaeq
       - name: Replay the fixture and write this leg's digest
         shell: bash
         run: >-
```

Gradle exit `0`; 0 failing test(s):

- *(none — this row is a control and must stay green)*

### C2 — CONTROL: a second real step passing -Pudea.replay.out

```diff
diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml
index 7297d0b..2bd789c 100644
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@ -1139,6 +1139,12 @@ jobs:
       # Criterion 6: the added PR wall time, measured rather than estimated, on a green run as
       # well as a red one. Whole-job seconds, not the replay's own - checkout, JDK setup and the
       # Gradle build are wall time a pull request pays for too.
+      - name: CONTROL a second real step passing the property
+        shell: bash
+        run: >-
+          ./gradlew :udea-replay:udeaReplayDigest
+          -Pudea.replay.out=somewhere-else/x.udeaeq
+
       - name: Report this leg's wall time
         if: always()
         shell: bash
```

Gradle exit `1`; 1 failing test(s):

- `ReplayEqualityProofTest.a leg's digest lands in the directory its upload step globs()`

---

## 9. On the transcripts in this document

Every fenced block above that is program output was checked mechanically against the file it was
captured from: `scratchpad/dev-169/verify-splices.py` requires each segment between elision markers
to appear as a **consecutive, in-order run** in a captured source, not merely for each line to
appear somewhere. Run against this document as it stands, the only segments it cannot place are the
two `JAVA_HOME=... sh gradlew ...` command blocks — which are inputs I typed rather than program
output, and correctly appear in no output file. Its output is saved as
`scratchpad/dev-169/verify-splices-output.txt`.

The check has been run against its own known negative:
`scratchpad/dev-169/verifier-control.py` takes three real, adjacent lines of the CI log out of
order in a copy of this document, runs the verifier, and restores the document. The verifier flags
the transposed segment; a membership check would not. Its output is saved as
`scratchpad/dev-169/verifier-control-output.txt`, and it ends `CONTROL PASSED`.

The evidence command in §1 was compared token-for-token against `scratchpad/dev-169/evidence.sh`,
the script that produced `evidence-green.txt` and `evidence-red.txt`: 16 tokens each, identical.

One thing this document does **not** claim: the rendered `$GITHUB_STEP_SUMMARY` page of the join
job. I have the log text `ReplayEqualsMain` wrote into `summary.md` and I have quoted that; the
rendered page itself I could not fetch onto this box, so it is linked rather than transcribed.

---

## 10. A note on the SHAs

The two Actions runs cited in §4 and §6 are of **`429f091`**. `3ea4865`, the commit under review,
sits one above it and changes a comment in `ReplayEqualityProofTest.kt` — nothing CI executes
differently, which the identical mutation table across the two demonstrates rather than asserts.
`BRIEF-169.md` lands in the commit above that.

The `replay-equality` result of the run of the final SHA goes to the lead with this brief rather
than into this document, because a run of a commit cannot be linked from inside that commit.
