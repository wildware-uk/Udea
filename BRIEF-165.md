0a09576

*(The commit that adds this line is `0a09576`'s child and touches only this file — a file cannot
state the hash of the commit containing it. `0a09576` is the SHA to review, and
`git diff 7942823..0a09576` is the whole change: this branch's merge base is `7942823`, not
today's `origin/example`, which has moved under it — see §8b.)*

# Issue #165 — replay-equality nightly fixture, regeneration flag and bisect job summary

Branch `issue-165-replay-nightly-and-regen`, off `origin/example` (`7942823`).

---

## 1. The evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew :udea-replay:test :udea-replay:udeaReplayEqualityProof --console=plain
```

One invocation, and it covers all three scope items:

- **the regeneration flag** — `ReplayFixtureUpdateTest` (the mechanism, against a `protoHash` that
  has moved, a fixture that does not exist, a wrong length and bytes that are not a recording) and
  `ReplayFixturesCurrentTest` (the same mechanism against the real checked-in bytes);
- **the nightly job** — `ReplayEqualityProofTest`'s new fences, which read `ci.yml` and resolve its
  own argument strings through the entry points CI runs;
- **the job summary** — `ReplayBisectGuideTest` for the renderer, and `udeaReplayEqualityProof`,
  which runs the five-process gate for real and now requires the rendered reproduction block to be
  in the file the job summary prints.

### Proof it goes red when the feature is reverted

The three production halves reverted to their `origin/example` state with the tests left in place
— `git checkout HEAD~1 -- .github/workflows/ci.yml udea-replay/build.gradle.kts
udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEqualsMain.kt` and
`rm udea-replay/src/testFixtures/resources/fixtures/drift-36000.udearep` — then the command above,
spliced from `scratchpad/evidence-reverted.txt`:

```
replay-equality proof PASSED: two honest legs agree (exit 0); the planted leg fails (exit 1) naming Drifter.x at t1200, with five ticks of history.
ReplayEqualityProofTest > the two pairs of jobs do not upload into each other's artifact names() FAILED
ReplayEqualityProofTest > the job slicer cuts one job and not its neighbour() FAILED
ReplayEqualityProofTest > the test task forwards the regeneration flag to the JVM that reads it() FAILED
ReplayEqualityProofTest > the nightly join compares the directory the workflow downloads into() FAILED
ReplayEqualityProofTest > the nightly never runs on a pull request and the gate always does() FAILED
ReplayEqualityProofTest > the nightly replays the long fixture and the PR job replays the short one() FAILED
ReplayEqualityProofTest > a nightly leg's digest lands in the directory its upload step globs() FAILED
ReplayFixturesCurrentTest > the nightly fixture is the length the nightly job asks for() FAILED
ReplayFixturesCurrentTest > every checked-in replay fixture can be replayed by this build() FAILED
> Task :udea-replay:test FAILED
BUILD FAILED in 10s
```

**Note the proof task stayed green in that run, and say why rather than let it read as a gap.**
That revert takes `build.gradle.kts` back too, so it removes the two needles *and* the thing they
assert, together. The guide half is therefore proved separately, by reverting only the one line
that puts the block into the summary — mutation **M10** in the table below — which turns the proof
task red on its own:

```
> Task :udea-replay:udeaReplayEqualityProof FAILED
> the planted divergence report does not contain '--- reproducing this locally ---', so it does not name what issue #152 requires it to name.
```

Restored, and green again — the last two lines of `scratchpad/proof.log`:

```
replay-equality proof PASSED: two honest legs agree (exit 0); the planted leg fails (exit 1) naming Drifter.x at t1200, with five ticks of history.

BUILD SUCCESSFUL in 1m 17s
44 actionable tasks: 44 executed
```

---

## 2. Summary: what I did, and what I decided

### `--update-replay-fixtures` — the item with a queued consumer

`udea-replay/src/main/kotlin/dev/wildware/udea/replay/fixture/ReplayFixtures.kt`. A `ReplayFixture`
declares a name, where its bytes are checked in, how many ticks it holds, this build's
`BuildIdentity` for it and how to rebuild it. `ReplayFixtures.reconcile` looks at every one and
rebuilds only the stale ones; `requireCurrent` turns the answer into a failure that names the field
that moved and the one command that fixes it.

**The convention is `--update-goldens`, spelled the way that one is actually typed.**
`docs/engineering-standards.md` §5 names `--update-goldens`; what is typed for it is
`./gradlew :udea-net:test -Dupdate.goldens=true`, because Gradle has no `--update-goldens` option
for a plain `Test` task. So `UPDATE_FLAG` is `--update-replay-fixtures` and `UPDATE_PROPERTY` is
`update.replay.fixtures`, and the failure message prints the typed form:

Reproduced for this brief by moving `drift-36000.udearep` aside and running
`ReplayFixturesCurrentTest`, out of the run's own JUnit XML
(`scratchpad/missing-fixture-failure.txt`):

```
java.lang.IllegalStateException: 1 replay fixture(s) cannot be replayed by this build:
  drift-36000.udearep: MISSING - no bytes at /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a5d07682a843e49fe/udea-replay/src/testFixtures/resources/fixtures/drift-36000.udearep

If that is expected - an id moved, a component was added, the fixture world changed - rebuild them and review the diff:
  ./gradlew :udea-replay:test -Dupdate.replay.fixtures=true
	at dev.wildware.udea.replay.fixture.ReplayFixtures.requireCurrent(ReplayFixtures.kt:167)
	at dev.wildware.udea.replay.fixture.ReplayFixturesCurrentTest.every checked-in replay fixture can be replayed by this build(ReplayFixturesCurrentTest.kt:48)
```

The `readCheckedIn` path prints the same command, so a reader who hits it from either end is sent
to the same place:

```
java.lang.IllegalStateException: /fixtures/drift-36000.udearep is not on the classpath. It is checked in under udea-replay/src/testFixtures/resources; rebuild it with ./gradlew :udea-replay:test -Dupdate.replay.fixtures=true
	at dev.wildware.udea.replay.equality.fixture.DriftFixtureRecorder.readCheckedIn(DriftFixtureRecorder.kt:121)
```

**Two decisions inside it.**

*Regeneration is not unconditional.* A fixture that this build can replay is left alone, even with
the flag set. A rebuild that ran every time would churn a checked-in binary on every invocation,
and a binary that changes on every run is a diff nobody can read — the same argument
`udeaWriteProtocolLock` makes about the wire contract. Rejected the simpler "always rewrite";
`a fixture this build can replay is reported current and its bytes are not touched` is the pin.

*"Current" means this build can replay these bytes — not that they equal a fresh recording's.* A
`.udearep` carries one world hash per tick and those hashes are whatever the recording machine
produced, which is the very question the cross-OS gate exists to ask. A byte comparison would make
the recording machine the authority and go red on the second platform, in the wrong job, for the
gate's own reason. `DriftFixtureRecorder`'s KDoc already said this about the gate; the reconcile now
obeys it. The half that *is* machine-independent — the recorded input stream against a rebuild,
sample for sample — stays where it was, in `ReplayEqualityProofTest`, on the 3600-tick fixture only.

*One mechanism, two front doors.* `:udea-replay:test -Dupdate.replay.fixtures=true` is the
`--update-goldens` mirror; `:udea-replay:udeaWriteReplayFixture` is the same
`ReplayFixtures.reconcile` call without the rest of the suite. `udeaWriteReplayFixture` previously
drove a one-file `DriftFixtureMain`, which is now `DriftFixturesMain` over the registry, so the two
cannot disagree about what "stale" means or about what they write. Run on a current tree it writes
nothing and says so:

```
drift-3600.udearep: CURRENT - 3600 tick(s), 66413 bytes, replayable by this build
drift-36000.udearep: CURRENT - 36000 tick(s), 665794 bytes, replayable by this build
```
(`scratchpad/write-current.log`, from `sh gradlew :udea-replay:udeaWriteReplayFixture --rerun-tasks`.)

I removed a `--dry-run` / `-Pudea.replay.dryRun` mode I had added to that entry point. It was a
second way to ask the question `ReplayFixturesCurrentTest` already answers on every push, and it
was a branch no test entered — a knob a reviewer would have had to take on trust.

### The 36000-tick nightly

`drift-36000.udearep`, checked in beside the PR fixture, replayed by two new jobs on the same two
operating systems and two JVM vendors. **`-Pudea.replay.fixture` is the only thing that differs**
from the gate's own legs.

I could not make it "5v5": the gate replays `udea-replay`'s fixture world, not `moba`'s, and
pointing it at `moba` is issue #172 — which is blocked on this ticket's flag. Commented on the
issue. #172 has to add a `ReplayFixture` to `moba` and a `-Pudea.replay.fixture` to the job; the
length, the trigger, the artefact naming and the join are done.

**No PR leg was touched** — no wall time, no fixture, no `join` behaviour, and **no edit to the
shared `on:` block at the top of `ci.yml`**. The existing `schedule` cron and `workflow_dispatch`
are reused; the condition is on the *job*, because a matrix leg cannot be skipped by event without
the runner still starting and installing a JDK. Two of the new tests exist to keep it that way:
`the nightly never runs on a pull request and the gate always does` asserts the gate job has no
`if:` at all, and `the nightly replays the long fixture and the PR job replays the short one`
asserts the gate job names no fixture.

### The job summary

`ReplayBisectGuide` renders the reproduction block into the summary `ReplayEqualsMain` writes —
on a green run as well as a red one, because a green summary is read by somebody about to push.

**The issue names a `replay.bisect` MCP tool that does not exist.** Issue #149 built a bisect
*surface*, not a bisect command. `ReplayBisectGuideTest` checks every tool name the guide prints
against `ReplayToolModules.Replay` — the generated module, not a second hand-written list — so the
document cannot come to name a tool nobody can call. Planting `replay.bisect` in that list turns it
red (mutation M6). Commented on the issue with the alternative I rejected.

Real output, spliced from `scratchpad/proof.log` — the console of a
`sh gradlew :udea-replay:udeaReplayEqualityProof --rerun-tasks` run:

```
--- reproducing this locally ---
Both halves of the gate, in five processes on one machine:
  ./gradlew :udea-replay:udeaReplayEqualityProof
This leg on its own, against the same recording:
  ./gradlew :udea-replay:udeaReplayDigest -Pudea.replay.fixture=drift-3600.udearep -Pudea.replay.label=mine
The divergence is at t1200, so walk into it. There is no single bisect tool: the surface is the 5 calls below, and issue #149 is where the loop is described.
  replay.load    {"name": "drift-3600.udearep"}
  replay.verify  {}
  replay.seek    {"tick": 1199}
  replay.step    {"ticks": 1}
  replay.rewind  {"ticks": 1}
Read the world between the last two with `world.*`; they are a loop, and the recording is bit-exact in both directions.
```

It seeks to 1199 and not 1200: `replay.seek` lands *on* the tick it is given, so seeking to the
divergence has already run the step a reader wants to watch.

### The surprise: no fixture of this world could exceed 255 button presses

Recording the 36000-tick fixture failed outright, about a sixth of the way in:

```
java.lang.IllegalArgumentException: a press count must be in 0..255, was 256 for action 'drift/pulse'
	at dev.wildware.udea.replay.InputSample.setPressCount(InputSample.kt:65)
	at dev.wildware.udea.replay.equality.fixture.DriftFixtureRecorder.record(DriftFixtureRecorder.kt:93)
	at dev.wildware.udea.replay.equality.DriftPilotTest.the pilot's press counter rolls over, so a fixture is not capped at 255 presses(DriftPilotTest.kt:33)
```

(That is `scratchpad/M11-failure.txt`, taken from a fresh run of mutation M11 rather than from the
original discovery, whose log I did not keep — the line number is `:93` here and was `:92` when I
first hit it, because the fix added a KDoc above it.)

`InputSample.setPressCount` takes `0..255` because a press count is one byte on the wire, and the
fixture pilot kept a *lifetime* total. That fitted only by accident — the 3600-tick fixture presses
roughly `3600 / PULSE_ODDS` = 150 times — so the maximum length of any fixture of this world was
capped at about 6100 ticks by a rule in another class, and nothing named it.

Fixed by letting the counter roll over, which is what a one-byte press counter means.
`ChargeSystem` compares `pulseCount > lastPulseCount`, so on the tick a wrap lands it declines to
fire: one missed press in 256, integer arithmetic, identical on both legs of a cross-OS comparison.

**`drift-3600.udearep` regenerates byte-identical**, so the PR gate replays the bytes it did
before. Deleted, rebuilt from scratch, and:

```
57cc9c2fa3ca5348a6d04be00117fe89c3d26c6ff2a3bc2820cf29bed39d2a60  /tmp/claude-1000/-srv-ssd1-workspace-Udea/a3ee2737-1b26-4f77-96b3-6805f45c796f/scratchpad/drift-3600.before.udearep
57cc9c2fa3ca5348a6d04be00117fe89c3d26c6ff2a3bc2820cf29bed39d2a60  udea-replay/src/testFixtures/resources/fixtures/drift-3600.udearep
```

(`drift-3600.before.udearep` is the copy taken before the recorder changed; the second path is the
file rebuilt from scratch afterwards. `scratchpad/fixture-sha256.txt`.)

and `git status` does not list it as modified in the commit that changed the recorder.

`DriftPilotTest` is the pin, and it records past the wrap rather than reading the checked-in bytes:
those bytes are the *output* of the thing under test, and a fixture recorded before the fix would
sit there in range saying nothing.

---

## 3. `sh gradlew build`

### The final tree: green

```
BUILD SUCCESSFUL in 11s
204 actionable tasks: 8 executed, 2 from cache, 194 up-to-date
```

**Say what that does not say.** Most of that run was `UP-TO-DATE`. `:udea-replay:test` was one of
the three that executed — this module's build script declares `ci.yml` as a test input, and the
last edit before it was a comment in `ci.yml`, so every test this branch adds ran again:

```
> Task :udea-replay:testClasses UP-TO-DATE
> Task :udea-replay:test
```

The four wall-clock budget tasks were `UP-TO-DATE` in it, so the run says nothing about them. They
were therefore run by name at this same tree, all four together with `--rerun-tasks` — and by then
the box had finally gone quiet, load `4.20` at the start and `4.81` at the end
(`scratchpad/budgets-quiet.log`):

```
    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) median 2.081ms, budget 4.0ms
    graph deserialisation: best=5.737ms median=5.926901ms over 2000 assets (budget 15ms)
    warm reload decision: median 140ms over 4 samples [200, 140, 135, 117]
    warm validate of one script: median 117ms over 4 samples [12, 129, 113, 117]
    phase 2 exit: typo'd reference rejected in 14ms (median of [289, 10, 14])
    phase 2 exit: agent request -> running world observed changed in 466ms
BUILD SUCCESSFUL in 20s
```

**All four pass, comfortably**: 2.081ms against 4.0ms, 5.927ms against 15ms, 140ms against
`WARM_RELOAD_BUDGET_MS` = 500, 117ms against `WARM_VALIDATE_BUDGET_MS` = 300, and 466ms for the
agent edit-to-observe. The 140ms is the figure the developer contract records for `udeaDaemonBudget`
run alone on this box.

So the repository is green with no exclusions, in two commands rather than one, and I would rather
say that plainly than present a mostly-cached `BUILD SUCCESSFUL` as though it had exercised the
budgets. An earlier by-name run of the same four at load `18.96` gave 2.198 / 5.904 / 178 / 113 /
502 (`scratchpad/budgets-final.log`) — the same picture, which is the point.

### An earlier run mid-ticket was red on exactly those four, and it is worth reading why

At `bdb7ec3`, load `8.23` at the start, `20.40` at the end, sampled at `28.73` during it, with the
other project on this box running a fifteen-minute GL scenario suite:

```
BUILD FAILED in 1m 21s
202 actionable tasks: 115 executed, 44 from cache, 43 up-to-date
```

**Four tasks failed and all four are the wall-clock budgets the developer contract names as failing
under load on this box** — no other task failed:

```
1: Task failed with an exception.
* What went wrong:
Execution failed for task ':udea-core:udeaBenchCharacterMover'.
2: Task failed with an exception.
* What went wrong:
Execution failed for task ':udea-agent-host:udeaPhase2Exit'.
3: Task failed with an exception.
* What went wrong:
Execution failed for task ':udea-assets-compiler:udeaDaemonBudget'.
4: Task failed with an exception.
* What went wrong:
Execution failed for task ':udea-assets-compiler:udeaPackGate'.
```

with the numbers each printed:

```
    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) median 11.991ms, budget 4.0ms
    graph deserialisation: best=7.477455ms median=15.824656ms over 2000 assets (budget 15ms)
    warm reload decision: median 871ms over 4 samples [1165, 695, 741, 871]
    phase 2 exit: agent request -> running world observed changed in 1375ms
    warm validate of one script: median 532ms over 4 samples [79, 578, 532, 512]
```

(Those five lines are a `grep` over the run's saved log for the tasks' own median lines, so they
are in the log's order rather than in the order the failures were reported.)

Compare them against the clean run above: 11.991ms against 2.198ms, 1375ms against 502ms, 871ms
against 178ms, 532ms against 113ms. Same tasks, same branch, one competing with a full build and
the other not.

### Are those four mine? No, and the answer does not depend on the box

**The arithmetic answer, first.** This branch changes `udea-replay/**` and `.github/workflows/ci.yml`
and nothing else. `udea-replay` is on the test runtime classpath of **none** of the three modules
that own those four tasks, so no input to any of them moved. Each module's `testRuntimeClasspath`
was saved to a file and grepped from there, with the **known positive run first** — a `grep -c` that returns 0 for everything proves nothing until you have seen
it return non-zero for something that is there (`scratchpad/deps-check.sh`, output in
`scratchpad/deps-check.txt`):

```
control (a project that IS on the classpath, so the grep is known to work):
  udea-assets-compiler / udea-diagnostics: 2
  udea-agent-host / udea-agent:            1
  udea-core / udea-annotations:            1

udea-replay on each module's testRuntimeClasspath:
  udea-assets-compiler: 0
  udea-agent-host:      0
  udea-core:            0
```

Say what that does *not* say: it is not "these tasks are unaffected by anything", and it is not a
statement about `udea-replay`'s own tests. It is that the three modules whose budgets failed cannot
observe the module this branch edits.

**The matched control on CI, which is a machine nobody else on this box is loading.** The same ten
jobs fail on this branch as on `origin/example` — the same names, none added and none fixed, from
run `33429395807` (this branch) and run `33425479983` (`origin/example`'s most recent). Not
compared by eye. `scratchpad/ci-parity.sh` writes each run's failing job names to a file and
`diff`s them, and it runs **two known negatives first** — because a `diff` that prints nothing
proves only that `diff` was not looking, and an empty `jq` result on both sides would diff clean
and say nothing at all. Output, `scratchpad/ci-parity.txt`:

```
control 1 - neither side is empty:
  this branch:    10 failing job(s)
  origin/example: 10 failing job(s)

control 2 - diff reports a difference when there is one (one job removed):
1d0
< build (ubuntu-latest)
  control diff exit=1

the real comparison, origin/example 33425479983 vs this branch 33429395807:
  diff exit=0

the failing set, identical on both:
build (ubuntu-latest)
build (windows-latest)
build with the K2 plugin disabled
clean build under budget
determinism (ubuntu-latest, corretto)
determinism (ubuntu-latest, temurin)
determinism (windows-latest, corretto)
determinism (windows-latest, temurin)
game-bridge-mcp conformance
the FIR checkers fail a real build
```

The first control I wrote was worse than useless and the run caught it: I diffed `origin/example`
against the **dispatch** run on the assumption that a run with six more jobs would differ, and it
came back `exit=0` as well. That is a true and useful fact — the nightly jobs add no failure — but
as a control it was silent, and I replaced it with one that cannot be.

Three of those ten are what #170, #171 and #173 are fixing this wave. **The gate this ticket
extends is green on all three legs of that run:**

```
replay-equality (ubuntu-latest, corretto): success
replay-equality (ubuntu-latest, temurin): success
replay-equality (windows-latest, temurin): success
replay-equality-nightly (${{ matrix.os }}, ${{ matrix.distribution }}): skipped
replay-equality-nightly (join): skipped
replay-equality (join): cancelled
```

That is the whole `replay*` job list for run `33429395807`, unedited
(`scratchpad/ci-replay-push.txt`) — the nightly's skip and the gate's three successes are two
lines apart in the same output. The join shows `cancelled` because a later push to the same ref
tripped the workflow's `concurrency: cancel-in-progress`.

**A third data point, mid-ticket, showing the shape of the interference.** The same four with
`--rerun-tasks` starting at load `5.74`
(`scratchpad/budgets-solo.log`):

```
    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) median 3.566ms, budget 4.0ms
    graph deserialisation: best=10.925681ms median=18.091072ms over 2000 assets (budget 15ms)
    warm reload decision: median 1036ms over 4 samples [1036, 1143, 856, 899]
    warm validate of one script: median 610ms over 4 samples [121, 610, 428, 641]
```

Two of the four passed there and two did not — and the load at the *end* of that run was `57.31`,
because the other project's second GL suite started while it was going. It is here because it is
the shape the developer contract describes: **a different thing fails each time**, which is the
box rather than the branch. The two that failed there were then run on their own at load `21.03`
and both passed (`scratchpad/budgets-two-solo.log`):

```
    graph deserialisation: best=5.608749ms median=5.765472ms over 2000 assets (budget 15ms)
    warm reload decision: median 170ms over 4 samples [205, 159, 170, 157]
    warm validate of one script: median 122ms over 4 samples [12, 122, 131, 119]
BUILD SUCCESSFUL in 23s
```

Four separate by-name runs across this ticket, at starting loads 5.7, 21.0, 19.0 and 4.2. Every
one of the four tasks is inside its budget in the quiet run at the top of this section, and the
only runs in which any of them missed were runs competing with a full build or with another
project's GL suite.

### `sh gradlew :udea-replay:tasks`

The build script is edited by this ticket, and the last wave lost every `udeaReplay*` registration
to a nested block comment that `build` did not notice. Every task is still registered:

Two consecutive runs from `scratchpad/replay-tasks.log`, with the groups between them elided.
`udeaWriteReplayFixture` is in `Build tasks`; the rest are in `Verification tasks`:

```
udeaWriteReplayFixture - Rebuilds udea-replay's checked-in .udearep replay-equality fixtures.
```

[... other task groups ...]

```
Verification tasks
------------------
check - Runs all checks.
checkKotlinGradlePluginConfigurationErrors - Checks that Kotlin Gradle Plugin hasn't reported project configuration errors, failing otherwise. This task always runs before compileKotlin* or similar tasks.
test - Runs the test suite.
udeaReplayDigest - Replays a checked-in .udearep fixture and writes this machine's .udeaeq digest.
udeaReplayEqualityProof - Proves the replay-equality gate both ways: two honest legs agree, and a one-ulp leg fails with the tick, the entity, the component and the field named.
udeaReplayEquals - Compares two or more .udeaeq digest streams and fails naming the differing field.
udeaReplayProofDigestA - replay-equality proof: writes leg-a.udeaeq
udeaReplayProofDigestB - replay-equality proof: writes leg-b.udeaeq
udeaReplayProofDigestPlanted - replay-equality proof: writes planted.udeaeq
udeaReplayProofJoinEqual - replay-equality proof: joins leg-a.udeaeq and leg-b.udeaeq
udeaReplayProofJoinPlanted - replay-equality proof: joins leg-a.udeaeq and planted.udeaeq
```

### GL

This ticket touches neither `udea-render` nor the render half of `udea-agent-host`. Nothing here
opens a GL context: `udea-replay` is headless by construction and the two new jobs run
`JavaExec` mains. No `xvfb-run` transcript, and that is a statement about scope rather than an
omission.

---

## 4. The measured numbers

### On CI, which is where they matter — run `33430551297`

The three nightly legs and, for comparison, a gate leg from the **same run**:

`scratchpad/nightly-numbers.txt`, produced by `scratchpad/nightly-numbers.sh`, which greps four
`gh run view --job N --log` outputs for their own lines and strips the job/step columns. The four
runs of lines are in job order — ubuntu/temurin, windows/temurin, ubuntu/corretto, then the gate
leg — and each is consecutive within its own job's log:

```
2026-08-31T19:28:21.1191638Z nightly/ubuntu-latest/temurin-17: replayed 36000 tick(s) in 3630ms into ubuntu-latest-temurin.udeaeq
2026-08-31T19:28:21.1193326Z   16466747 bytes at /home/runner/work/Udea/Udea/nightly-digests/ubuntu-latest-temurin.udeaeq
2026-08-31T19:28:23.8414899Z replay-equality-nightly leg wall time: 85s
2026-08-31T19:28:28.0574400Z nightly/windows-latest/temurin-17: replayed 36000 tick(s) in 3799ms into windows-latest-temurin.udeaeq
2026-08-31T19:28:30.9317644Z replay-equality-nightly leg wall time: 92s
2026-08-31T19:28:23.9328911Z nightly/ubuntu-latest/corretto-17: replayed 36000 tick(s) in 2553ms into ubuntu-latest-corretto.udeaeq
2026-08-31T19:28:23.9329999Z   16466747 bytes at /home/runner/work/Udea/Udea/nightly-digests/ubuntu-latest-corretto.udeaeq
2026-08-31T19:28:26.0154947Z replay-equality-nightly leg wall time: 88s
2026-08-31T19:28:32.9443008Z ubuntu-latest/temurin-17: replayed 3600 tick(s) in 640ms into ubuntu-latest-temurin.udeaeq
2026-08-31T19:28:32.9444017Z   1626969 bytes at /home/runner/work/Udea/Udea/digests/ubuntu-latest-temurin.udeaeq
2026-08-31T19:28:35.0918040Z replay-equality leg wall time: 97s
```

The windows leg has no `bytes at` line here because its runner writes a Windows path, which the
grep's `/home/runner` term does not match — not because it wrote nothing. Its size is in the
artefact listing below.

**What the numbers support, and not more than that.** The replay itself grew by 3.0s (3630ms
against 640ms on the same OS and JVM). The whole-leg times are 85s, 88s and 92s for the nightly and
97s for that gate leg — one sample each, and the spread *within* the three nightly legs is 7s, so
the honest reading is that a leg's wall time is dominated by checkout, JDK install and the Gradle
build and the extra replay disappears into the noise of that. It is certainly not the case that
ten times the ticks costs ten times the leg. Every nightly leg is far inside the 240s budget issue
#152 states for a gate leg, and I have given the nightly no budget of its own — see below.

The artefact cost is the real one. The three digest streams that run uploaded:

```
total 48260
drwxr-xr-x 2 shaun shaun     4096 Aug 31 19:31 .
drwxr-xr-x 4 shaun shaun     4096 Aug 31 19:31 ..
-rw-r--r-- 1 shaun shaun 16466747 Aug 31 19:31 ubuntu-latest-corretto.udeaeq
-rw-r--r-- 1 shaun shaun 16466747 Aug 31 19:31 ubuntu-latest-temurin.udeaeq
-rw-r--r-- 1 shaun shaun 16466767 Aug 31 19:31 windows-latest-temurin.udeaeq
```
(`ls -la` over the downloaded `replay-equality-nightly-verdict` artefact;
`scratchpad/nightly-digest-sizes.txt`.)

47.1 MiB across the three, at the seven-day retention the gate legs already use. The Windows stream
is 20 bytes larger than the Linux ones and both Linux ones are equal: a digest header carries the
leg's label, OS name and JVM name as strings, and those differ in length per leg. **The cells are
identical** — that is what the verdict in §6 says, and it is a stronger statement than the byte
count.

### On this box

**The fixture sizes**, which do not depend on the box, and the transcript of both being built from
scratch in one invocation (`scratchpad/write-fixtures.log`, run at load 24.23 → 26.42):

```
drift-3600.udearep: REGENERATED - rebuilt because it did not exist; now 3600 tick(s), 66413 bytes at /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a5d07682a843e49fe/udea-replay/src/testFixtures/resources/fixtures/drift-3600.udearep
drift-36000.udearep: REGENERATED - rebuilt because it did not exist; now 36000 tick(s), 665794 bytes at /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a5d07682a843e49fe/udea-replay/src/testFixtures/resources/fixtures/drift-36000.udearep
```

**66 413 bytes against 665 794** — ten times the ticks, ten times the file.

**The local replay timings are not worth quoting** and I am not going to. Two runs of the same
3600-tick digest on this box gave 411ms and 865ms, at loads 21.24 and 31.46, because another
project's GL suite was running through both. The CI numbers above were taken on a machine doing
nothing else, and they are the ones the decision rests on. What the local runs do give, because it
does not depend on load, is the digest size — 1 626 953 bytes for 3600 ticks and 16 466 736 for
36000, against CI's 1 626 969 and 16 466 747. A digest header carries the leg's label, OS name and
JVM name as strings, and those are different lengths on each leg; the cells are what the verdict
compares, and it says they are identical.

**No row was added to `docs/budgets.md`.** That document says in its own words that "a budget here
is a CI gate, not an aspiration", and the nightly enforces no threshold: a wall-time gate on an
overnight job would fail at three in the morning for something nobody can act on, and the measured
85–92s is nowhere near the 240s the gate legs are held to anyway. The numbers are in the `ci.yml`
job comment instead, where the person changing the job will read them. `replay-equality`'s own
240s budget is likewise stated in `ci.yml` and not in `budgets.md`, so this follows what #152 did
rather than inventing a second place.

---

## 5. Images

**None, and that is not an omission I am hiding.** This ticket produces no frame, no world state
and no visual change: it is a checked-in binary, a Gradle flag, two CI jobs and a block of text in
a report. The artefacts a reader can check are the transcripts above and the Actions runs below,
and a screenshot of a GitHub page would be a picture of a record rather than the record.

---

## 6. The issue, scope item by scope item

### "The ten-minute 36000-tick 5v5 fixture, run nightly and on the integration branch. It must not block PRs."

| | |
|---|---|
| the fixture exists and is the declared length | `ReplayFixturesCurrentTest > the nightly fixture is the length the nightly job asks for`, and `drift-36000.udearep`, 665 794 bytes, 36000 ticks |
| the job replays *that* fixture | `ReplayEqualityProofTest > the nightly replays the long fixture and the PR job replays the short one`, which resolves the workflow's string through `DriftFixtureKind.byName`. Mutation M2 |
| it does not block a PR-shaped push | **Run `33428965257`**, event `push` on this branch: `replay-equality-nightly` and its join both `skipped`, while the three `replay-equality` legs ran. The matrix never expanded — the job name is still the raw `${{ matrix.os }}` expression, so no runner was allocated |
| it runs nightly and on the integration branch | the `if:` names `schedule` and `refs/heads/example`, fenced by `the nightly never runs on a pull request and the gate always does`. Mutation M3 |
| it actually runs, and passes | **Run `33430551297`**, event `workflow_dispatch` on this branch — all three legs and the join green, verdict below |
| its digests land where its join looks | `a nightly leg's digest lands in the directory its upload step globs` and `the nightly join compares the directory the workflow downloads into` — issue #169's defect, checked for the second pair of jobs. Mutations M4 and M5 |
| the two pairs do not collide | `the two pairs of jobs do not upload into each other's artifact names`. Mutation M5 |

**The nightly's own verdict**, spliced out of the `replay-equality-nightly-verdict` artefact of
run `33430551297` (`udea-replay/build/reports/udea/replay-equality/summary.md`), which is the file
the job summary prints:

```
replay-equality over 3 leg(s) of 'drift-36000.udearep', 36000 tick(s) from t0
  nightly/ubuntu-latest/corretto-17  [Linux amd64; Amazon.com Inc. OpenJDK 64-Bit Server VM 17.0.20.1]
  nightly/ubuntu-latest/temurin-17  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20.1]
  nightly/windows-latest/temurin-17  [Windows Server 2025 amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20.1]

replay equality holds: 36000 tick(s) of 'drift-36000.udearep' are cell-for-cell identical
  fixture drift-36000.udearep
  A = 'nightly/ubuntu-latest/corretto-17'  [Linux amd64; Amazon.com Inc. OpenJDK 64-Bit Server VM 17.0.20.1]
  B = 'nightly/ubuntu-latest/temurin-17'  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20.1]

replay equality holds: 36000 tick(s) of 'drift-36000.udearep' are cell-for-cell identical
  fixture drift-36000.udearep
  A = 'nightly/ubuntu-latest/corretto-17'  [Linux amd64; Amazon.com Inc. OpenJDK 64-Bit Server VM 17.0.20.1]
  B = 'nightly/windows-latest/temurin-17'  [Windows Server 2025 amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20.1]

--- reproducing this locally ---
Both halves of the gate, in five processes on one machine:
  ./gradlew :udea-replay:udeaReplayEqualityProof
This leg on its own, against the same recording:
  ./gradlew :udea-replay:udeaReplayDigest -Pudea.replay.fixture=drift-36000.udearep -Pudea.replay.label=mine
There is no divergence to bisect: every leg folded the same cells on every tick, so the loop below has no tick to land on.
```

Both axes are real on that run and the header says so rather than this file asserting it:
`Amazon.com Inc.` beside `Eclipse Adoptium`, and `Windows Server 2025` beside `Linux`. And the
last line is the case I had listed as unexercised — the guide with nothing to bisect — reached on a
real run rather than only in a test.

**"5v5" is not delivered**, because the gate replays `udea-replay`'s fixture world and pointing it
at `moba` is issue #172, which is blocked on this ticket. Commented on the issue.

### "A `--update-replay-fixtures` regeneration flag, mirroring the Phase 3 `--update-goldens` convention."

| | |
|---|---|
| it mirrors the convention | `ReplayFixtureUpdateTest > the flag is read from the property that spells the documented flag` |
| it rebuilds a fixture whose `protoHash` has moved, and the replay stops refusing it | `the update flag rebuilds a fixture whose protoHash has moved, and the replay stops refusing it` — it asserts `ReplayVerifier.refuseIfMismatched` throws *before* and does not throw after |
| it writes a fixture that does not exist yet | `a fixture that does not exist yet is reported missing, and the flag writes it` |
| it does not touch a fixture that is current | `a fixture this build can replay is reported current and its bytes are not touched`. Mutation M8 |
| the refusal names the field and both sides | `a fixture whose protoHash has moved is refused, naming the field and both sides`, using #167's real `0xea9f` and `0xc67b` |
| the refusal names the command | `a refused fixture names the one command that rebuilds it`. Mutation M9 |
| the flag reaches the forked test JVM | `the test task forwards the regeneration flag to the JVM that reads it`. Mutations M7 and C2 |
| each of the four identity fields is named when it moves | `each of the four identity fields is named when it is the one that moved`. Mutation M14 |
| two stale fixtures are both reported and both rebuilt | `two stale fixtures are both reported, not just the first`. Mutation M15 |
| it works on the real checked-in bytes | `ReplayFixturesCurrentTest`, and the regeneration transcript in §4 |

### "A job summary that links the `replay.bisect` MCP tool with the exact invocation for local reproduction."

| | |
|---|---|
| every tool it names exists | `ReplayBisectGuideTest > every tool the guide tells a reader to call is one the replay module declares`, against `ReplayToolModules.Replay`. Mutation M6 |
| the tick is the last one the legs agreed on | `a divergence sends the reader to the last tick the two legs agreed on` |
| a divergence at tick zero does not ask for tick −1 | `a divergence at the very first tick does not send the reader to a tick before the recording` |
| a green run gets one too, with nothing invented | `with nothing to bisect the guide says so and still says how to run it` |
| the command names the fixture that diverged | `the reproduction command names the fixture that actually diverged` |
| it reaches the file the job summary prints | `udeaReplayEqualityProof`, two needles on its list, themselves fenced by `the proof task also checks that the report says how to reproduce it`. Mutations M10 and M12, the transcript in §2, and the real CI verdict above |
| it lands on the earliest divergence, not the first pair's | `the guide lands on the earliest tick any pair diverged at, not the first pair's` and `one pair agreeing does not hide another pair's divergence`. Mutation M13 |

---

## 7. Mutation table

Every diff below is `diff -u` output from the run that produced the failures beside it, not a
description. Each was applied alone and reverted before the next, by
`scratchpad/mutate.py <id> <file> <old> <new>`, which asserts the pattern occurs **exactly once**
before it edits, writes the diff and the failing-test list to `<id>.diff` and `<id>.result`, and
restores the file in a `finally` — so a mutation cannot be left behind and a diff cannot be a
retyping of one.

Every fenced block in this brief was checked with `scratchpad/verify-splices.py`, which requires
each block's lines to be present in a saved artefact on disk. It reports one block unmatched: the
evidence command in §1, which is a command to run rather than output. Those artefacts live in this
session's scratchpad and will not outlive it — but every block names the command that produced it,
and the mutation rows carry their own diffs, so all of it can be rebuilt.

Command in every row: `sh gradlew :udea-replay:test --console=plain`, except **M10**, which is
`sh gradlew :udea-replay:udeaReplayEqualityProof`.

### M2 — the nightly replays the gate's short fixture

```diff
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@ -1377,5 +1377,5 @@
         run: >-
           ./gradlew :udea-replay:udeaReplayDigest
-          -Pudea.replay.fixture=drift-36000.udearep
+          -Pudea.replay.fixture=drift-3600.udearep
           -Pudea.replay.label=nightly/${{ matrix.os }}/${{ matrix.distribution }}-17
           -Pudea.replay.jvmVendor=${{ matrix.vendor }}
```
`ReplayEqualityProofTest > the nightly replays the long fixture and the PR job replays the short one() FAILED`

### M3 — the nightly loses its condition and would run on a pull request

```diff
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@ -1336,8 +1336,4 @@
     # Never on a pull request. `workflow_dispatch` is here so the job can be proven to run without
     # waiting a day for the cron; it is how issue #165's own evidence was produced.
-    if: >-
-      github.event_name == 'schedule' ||
-      github.event_name == 'workflow_dispatch' ||
-      (github.event_name == 'push' && github.ref == 'refs/heads/example')
     runs-on: ${{ matrix.os }}
     strategy:
```
`ReplayEqualityProofTest > the nightly never runs on a pull request and the gate always does() FAILED`

Note the comment saying "Never on a pull request" survived the mutation and the fence still went
red. C1 below is the control in the other direction.

### M4 — the nightly leg writes where its upload step does not look (issue #169's defect)

```diff
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@ -1387,5 +1387,5 @@
         with:
           name: replay-nightly-digest-${{ matrix.os }}-${{ matrix.distribution }}
-          path: nightly-digests/*.udeaeq
+          path: digests/*.udeaeq
           if-no-files-found: error
           retention-days: 7
```
`ReplayEqualityProofTest > a nightly leg's digest lands in the directory its upload step globs() FAILED`

### M5 — the nightly join downloads both jobs' digests

```diff
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@ -1431,5 +1431,5 @@
         uses: actions/download-artifact@v4
         with:
-          pattern: replay-nightly-digest-*
+          pattern: replay-*digest-*
           path: nightly-digests
           merge-multiple: true
```
`ReplayEqualityProofTest > the two pairs of jobs do not upload into each other's artifact names() FAILED`

### M6 — the guide names the tool the issue asks for

```diff
--- a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayBisectGuide.kt
+++ b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayBisectGuide.kt
@@ -41,5 +41,5 @@
         "replay.load",
         "replay.verify",
-        "replay.seek",
+        "replay.bisect",
         "replay.step",
         "replay.rewind",
```
`ReplayBisectGuideTest > every tool the guide tells a reader to call is one the replay module declares() FAILED`

### M7 — the regeneration flag is not forwarded to the forked test JVM

```diff
--- a/udea-replay/build.gradle.kts
+++ b/udea-replay/build.gradle.kts
@@ -80,8 +80,5 @@
     // properties, so a flag that is not passed here is a flag `ReplayFixturesCurrentTest` never
     // sees - it would report every fixture current and rebuild nothing, silently.
-    systemProperty(
-        "update.replay.fixtures",
-        providers.systemProperty("update.replay.fixtures").orElse("false").get(),
-    )
+    // mutation: the flag is no longer forwarded to the forked test JVM
     // The build script and the workflow are read by `ReplayEqualityProofTest`, so an edit to
     // either has to make the task rerun. Found the same way `udea-core`'s FieldMask scan was: a
```
`ReplayEqualityProofTest > the test task forwards the regeneration flag to the JVM that reads it() FAILED`

### M8 — every fixture is always stale, so the flag rewrites current bytes

```diff
--- a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/fixture/ReplayFixtures.kt
+++ b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/fixture/ReplayFixtures.kt
@@ -217,5 +217,5 @@
             return "this build cannot replay it - " + mismatches.joinToString("; ")
         }
-        if (recording.tickCount != fixture.ticks) {
+        if (true) {
             return "it holds ${recording.tickCount} tick(s) and the fixture declares " +
                 "${fixture.ticks}; a recording of the right build and the wrong length replays " +
```
```
ReplayFixtureUpdateTest > the update flag rebuilds a fixture whose protoHash has moved, and the replay stops refusing it() FAILED
ReplayFixtureUpdateTest > a fixture this build can replay is reported current and its bytes are not touched() FAILED
ReplayFixturesCurrentTest > every checked-in replay fixture can be replayed by this build() FAILED
```

### M9 — `requireCurrent` never fails

```diff
--- a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/fixture/ReplayFixtures.kt
+++ b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/fixture/ReplayFixtures.kt
@@ -164,5 +164,5 @@
      */
     public fun requireCurrent(statuses: List<ReplayFixtureStatus>, gradleTask: String) {
-        val failures = statuses.filter { it.outcome.isFailure }
+        val failures = statuses.filter { false }
         check(failures.isEmpty()) {
             buildString {
```
`ReplayFixtureUpdateTest > a refused fixture names the one command that rebuilds it() FAILED`

### M10 — the summary no longer carries the reproduction block

```diff
--- a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEqualsMain.kt
+++ b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEqualsMain.kt
@@ -180,5 +180,5 @@
         // green run as well as a red one. Rendered by a class with tests rather than assembled in
         // a workflow step - see `ReplayBisectGuide`.
-        report.append(ReplayBisectGuide.render(reference.header.fixture, firstDivergence))
+        // mutation: the summary no longer carries the reproduction block
         return worst
     }
```
`:udea-replay:test` goes green — the renderer is untouched, only its use — and
`:udea-replay:udeaReplayEqualityProof` fails:
```
> the planted divergence report does not contain '--- reproducing this locally ---', so it does not name what issue #152 requires it to name.
```
That is the reason the evidence command names both tasks.

### M11 — the fixture pilot keeps a lifetime press count again

```diff
--- DriftFixtureRecorder.orig.kt
+++ DriftFixtureRecorder.mutated.kt
@@ -87,7 +87,7 @@
                     pilot.nextFloat() * 2f - 1f,
                     pilot.nextFloat() * 2f - 1f,
                 )
-                if (pilot.nextInt(PULSE_ODDS) == 0) pulses = (pulses + 1) and PULSE_COUNT_MASK
+                if (pilot.nextInt(PULSE_ODDS) == 0) pulses++
                 sample.setPressed(DriftFixture.ACTION_PULSE, pulses % 2 == 1)
                 sample.setPressCount(DriftFixture.ACTION_PULSE, pulses)
```
`DriftPilotTest > the pilot's press counter rolls over, so a fixture is not capped at 255 presses() FAILED`,
with (`scratchpad/M11-failure.txt`, out of the run's JUnit XML):
```
java.lang.IllegalArgumentException: a press count must be in 0..255, was 256 for action 'drift/pulse'
	at dev.wildware.udea.replay.InputSample.setPressCount(InputSample.kt:65)
	at dev.wildware.udea.replay.equality.fixture.DriftFixtureRecorder.record(DriftFixtureRecorder.kt:93)
	at dev.wildware.udea.replay.equality.DriftPilotTest.the pilot's press counter rolls over, so a fixture is not capped at 255 presses(DriftPilotTest.kt:33)
	at java.base/java.lang.reflect.Method.invoke(Method.java:569)
```

### M12 — the proof stops requiring the reproduction block

```diff
--- a/udea-replay/build.gradle.kts
+++ b/udea-replay/build.gradle.kts
@@ -403,6 +403,5 @@
             "NetId(",
             "the preceding 5 tick(s)",
-            "--- reproducing this locally ---",
-            "replay.seek    {\"tick\": ${expectedTick.toInt() - 1}}",
+            // mutation: the proof no longer requires the reproduction block
         )
         for (needle in required) {
```
`ReplayEqualityProofTest > the proof task also checks that the report says how to reproduce it() FAILED`

M10 proves the block reaching the summary; this proves the proof task still *asks* for it. Without
both, deleting the needles would leave `udeaReplayEqualityProof` green with nothing checking the
guide ever gets published.

### M13 — the guide takes the first pair's tick rather than the earliest

```diff
--- a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayBisectGuide.kt
+++ b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayBisectGuide.kt
@@ -56,5 +56,5 @@
      */
     public fun render(fixture: String, divergentTicks: List<Tick?>): String = buildString {
-        val divergentTick = divergentTicks.filterNotNull().minOrNull()
+        val divergentTick = divergentTicks.firstOrNull()
         append("\n\n--- reproducing this locally ---\n")
         append("Both halves of the gate, in five processes on one machine:\n")
```
```
ReplayBisectGuideTest > one pair agreeing does not hide another pair's divergence() FAILED
ReplayBisectGuideTest > the guide lands on the earliest tick any pair diverged at, not the first pair's() FAILED
```

### M14 — the refusal stops naming which identity field moved

```diff
--- a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/fixture/ReplayFixtures.kt
+++ b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/fixture/ReplayFixtures.kt
@@ -215,5 +215,5 @@
         val mismatches = recording.header.identity.mismatchesAgainst(fixture.identity())
         if (mismatches.isNotEmpty()) {
-            return "this build cannot replay it - " + mismatches.joinToString("; ")
+            return "this build cannot replay it"
         }
         if (recording.tickCount != fixture.ticks) {
```
```
ReplayFixtureUpdateTest > the update flag rebuilds a fixture whose protoHash has moved, and the replay stops refusing it() FAILED
ReplayFixtureUpdateTest > each of the four identity fields is named when it is the one that moved() FAILED
ReplayFixtureUpdateTest > a fixture whose protoHash has moved is refused, naming the field and both sides() FAILED
```

### M15 — `reconcile` looks at the first fixture only

```diff
--- a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/fixture/ReplayFixtures.kt
+++ b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/fixture/ReplayFixtures.kt
@@ -153,5 +153,5 @@
      */
     public fun reconcile(fixtures: List<ReplayFixture>, update: Boolean): List<ReplayFixtureStatus> =
-        fixtures.map { reconcileOne(it, update) }
+        fixtures.take(1).map { reconcileOne(it, update) }
 
     /**
```
```
ReplayFixtureUpdateTest > two stale fixtures are both reported, not just the first() FAILED
ReplayFixturesCurrentTest > every checked-in replay fixture can be replayed by this build() FAILED
```

### C1 (control) — prose naming every forbidden string leaves every fence green

```diff
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@ -1334,5 +1334,7 @@
     # fixture's 1,626,953, so one nightly run uploads about 50MB across its three legs.
     name: replay-equality-nightly (${{ matrix.os }}, ${{ matrix.distribution }})
-    # Never on a pull request. `workflow_dispatch` is here so the job can be proven to run without
+    # Never on a pull_request, and never with -Pudea.replay.fixture=drift-3600.udearep or a
+    # plant: true leg. This comment exists to prove the fences read code and not prose.
+    # `workflow_dispatch` is here so the job can be proven to run without
     # waiting a day for the cron; it is how issue #165's own evidence was produced.
```
**0 failing tests.** A comment mentioning `pull_request`, a second `-Pudea.replay.fixture=` and a
`plant: true` leg does not trip anything.

### C2 (control) — a commented-out copy does not satisfy the flag-forwarding fence

```diff
--- a/udea-replay/build.gradle.kts
+++ b/udea-replay/build.gradle.kts
@@ -80,8 +80,8 @@
     // properties, so a flag that is not passed here is a flag `ReplayFixturesCurrentTest` never
     // sees - it would report every fixture current and rebuild nothing, silently.
-    systemProperty(
-        "update.replay.fixtures",
-        providers.systemProperty("update.replay.fixtures").orElse("false").get(),
-    )
+    // systemProperty(
+    //     "update.replay.fixtures",
+    //     providers.systemProperty("update.replay.fixtures").orElse("false").get(),
+    // )
     // The build script and the workflow are read by `ReplayEqualityProofTest`, so an edit to
     // either has to make the task rerun. Found the same way `udea-core`'s FieldMask scan was: a
```
`ReplayEqualityProofTest > the test task forwards the regeneration flag to the JVM that reads it() FAILED` —
the fence reads what the compiler reads.

### The one I got wrong, and it is the one worth reading

The first version of `the job slicer cuts one job and not its neighbour` asserted that the nightly
block does not contain `digests/${{ matrix.os }}`. It failed on the honest tree, because the
nightly's own `nightly-digests/${{ matrix.os }}-...` **contains** that substring. The control
caught my discriminator rather than my slicer, which is what a control is for; it now uses two
things each block has and the other cannot — the nightly is the only job that names a fixture, and
`plant:` lives only on the gate.

---

## 8. Actions runs, and what each one shows

| Run | Event | Shows |
|---|---|---|
| [`33430551297`](https://github.com/wildware-uk/Udea/actions/runs/33430551297) | `workflow_dispatch` | the nightly **executing**: three legs and the join green over 36000 ticks, verdict in §6 |
| [`33429395807`](https://github.com/wildware-uk/Udea/actions/runs/33429395807) | `push` | the nightly **skipped** on a PR-shaped push while the three gate legs ran green; the same ten pre-existing failures as `origin/example` |
| [`33428965257`](https://github.com/wildware-uk/Udea/actions/runs/33428965257) | `push` | the same skip, with the job name still showing the raw `${{ matrix.os }}` expression — the matrix never expanded, so no runner was allocated |
| [`33425479983`](https://github.com/wildware-uk/Udea/actions/runs/33425479983) | `push` on `example` | the baseline the failure set above is compared against |

Two of those runs were cancelled part-way by the workflow's own
`concurrency: cancel-in-progress`, which cancels the older run on a ref when a newer one starts.
The jobs quoted above had all reached a terminal state before the cancellation; where one had not,
I have not quoted it.

**The Actions evidence was produced at `b06b2bc`.** Three commits land after it and none can move
what those runs showed:

- `ded18b4` moves the earliest-divergence selection into `ReplayBisectGuide.render` so it can be
  tested. For the single-pair case those runs exercised it renders the same text.
- `3334113` adds this brief and rewrites a comment block inside the nightly job, replacing the
  locally-estimated cost with the numbers that run actually produced. No step, no condition and no
  property changed.
- `0a09576` adds two tests and touches no production code at all.

Checkable rather than asserted: every one of the fifteen changed lines in `ci.yml` since `b06b2bc`
is a comment line, which is why the count below is zero.

`git diff b06b2bc..HEAD -- .github/workflows/ci.yml` was saved to a file; counting its changed
lines, and then its changed lines that are **not** comments, gives (`scratchpad/`
`ci-since-evidence.counts.txt`):

```
15
0
```

## 8b. Merging onto the current `origin/example`

`origin/example` moved while this branch was in flight — #171 and #173 merged into it
(`efab1d0`). Checked rather than assumed, and then undone so the branch is exactly what is
reported:

- `git merge --no-commit --no-ff origin/example` → `Auto-merging .github/workflows/ci.yml` /
  `Automatic merge went well`. Four of us edited that file in four disjoint blocks and it merged
  with no conflict.
- The merged `ci.yml` parses, with fifteen jobs: `build`, `gl-tests`, `legacy-ledger`,
  `agents-md`, `plugin-disabled`, `checkers-fire`, `kotlin-upgrade-probe`, `clean-build-budget`,
  `ksp-incremental-budget`, `bridge-conformance`, `determinism`, `replay-equality`,
  `replay-equality-join`, `replay-equality-nightly`, `replay-equality-nightly-join`.
- `sh gradlew :udea-replay:test --rerun-tasks` against the **merged** workflow: `BUILD SUCCESSFUL`.
  That matters because every fence in `ReplayEqualityProofTest` reads `ci.yml`, and the job slicer
  in particular has to keep cutting the right block when neighbouring jobs move.
- `git merge --abort`, and `git status` is clean again apart from the wrapper's mode bit.

`git diff 7942823..HEAD` — against the merge base, not against today's `origin/example` — is the
honest statement of what this branch changes: `.github/workflows/ci.yml`, `udea-replay/**` and
`BRIEF-165.md`, and nothing else.

---

## 9. Regenerated files

**`udea-codegen/net-protocol.lock`: not touched. `expected-generated-hashes.txt`: not touched.**
No replicated component was added or removed by this branch, so no id moved. `git status` at
`bdb7ec3` lists neither.

What *was* regenerated is `udea-replay/src/testFixtures/resources/fixtures/drift-3600.udearep`,
deliberately and from scratch, to prove the press-count change does not move it. It came back
byte-identical (`sha256 57cc9c2f…` on both sides) and is therefore **not** in the diff.

`drift-36000.udearep` is new: 665 794 bytes, 36000 ticks, generated by
`:udea-replay:udeaWriteReplayFixture` and reproducible on any conforming JVM from
`DriftFixtureRecorder`'s specified `java.util.Random` LCG.

---

## 10. My own pass over the diff, against the closed reject list

- **A §1 smell in new code.** `ReplayFixtures` has one job, `ReplayBisectGuide` one, and neither
  reaches for global state. The one place I was tempted to duplicate — a second bisect renderer for
  the nightly only — I did not: the guide is rendered once, by `ReplayEqualsMain`, and both joins
  publish it.
- **A `public` nobody outside the module uses.** `ReplayFixture`, `ReplayFixtureStatus` and
  `ReplayFixtures` live in `src/main` and are consumed from `testFixtures` — a separate, published
  compilation — and by both entry points. They are also the API issue #172 is waiting on, which is
  named in the KDoc rather than assumed. `ReplayBisectGuide.TOOLS` is public because its test reads
  it and because it is the list the rendering is derived from.
- **A test that cannot fail.** §7: every fence in this branch has a mutation beside it, each with the literal diff of the run that produced its failures, plus two controls in the other direction.
- **Generated code by string concatenation.** None; nothing here generates code.
- **A new `GameContext` field.** None.
- **Wall-clock or unseeded randomness in simulation.** None. The pilot's `java.util.Random` is
  pre-existing, authors a recording offline and never enters a world — `DriftFixtureRecorder`'s
  KDoc says so. `ReplayFixtures` reads no clock.
- **`TODO()`, a stubbed return, a swallowed exception on a reachable path.** The one `catch` I
  added, over `ReplayFormatException`, reports the reader's own message into `detail` rather than
  discarding it, and `bytes that are not a recording at all are refused with what the reader said`
  asserts the message survives.
- **Copy-pasted logic differing only in a constant.** The two nightly job blocks are the closest
  thing, and they are YAML rather than logic: GitHub has no way to share a job body inside one
  workflow, and this file's own opening comment forbids a second workflow file. Every decision they
  make — what to replay, what to compare, what to publish — is in `:udea-replay` and shared. The
  *code* paths are not duplicated: one `reconcile`, one `render`, one `DriftDigestMain`.
- **AGENTS.md do-not list.** No `by net(...)`, no second snapshot codec, no setter instrumentation,
  no wall clock or unseeded RNG in a simulation, no new dependency on `common`, no reflection on a
  per-tick path, no GL outside `udea-render`, no presentation system as a Fleks system, no module
  arrow moved. `DriftFixtureKind` is an enum rather than a bare `String` for "which fixture".
- **A frozen contract changed.** None. `docs/contracts/` is untouched — `git status` at `bdb7ec3`
  lists nothing under it.
- **The `fieldNames[i]` / `FieldMask` / `FieldStore` alignment.** Untouched. The regeneration path
  writes through the same `ReplayRecorder` and the same `BuildIdentity` the reading path refuses on,
  which is what stops a fixture being written under one field ordering and read under another.
- **A duration expressed as seconds or milliseconds instead of a `Tick`.** `NIGHTLY_TICKS` is
  36 000 ticks and the word "minutes" appears in no identifier. The KDoc says why: a number of
  seconds written down here stops being true the day the rate changes.
- **`AGENTS.md`'s module table.** No module moved; the table is unchanged and correct.

### What I did not exercise

- **The nightly on a genuinely red leg.** The join's `if: always()` and `EXIT_UNUSABLE` path are
  inherited from `replay-equality-join`, which #169 proved; I have not made a nightly leg fail on a
  runner. `ReplayEqualsMain`'s "a leg produced no stream" branch is covered by its own tests.
- ~~A fixture whose `assetGraphHash` or `inputSchemaHash` has moved.~~ **Closed.** I wrote this
  down as untested and then noticed it was a five-minute check.
  `each of the four identity fields is named when it is the one that moved` builds a recording per
  field, each differing from this build in that field alone, and asserts the refusal names it.
  `inputSchemaHash` needed a second `InputSchema` rather than a `copy()`, because `ReplayRecorder`
  substitutes the schema's own hash over whatever it is handed — which is the shape a real
  mismatch has too. Mutation M14.
- ~~Two fixtures stale at once.~~ **Closed**, same reason.
  `two stale fixtures are both reported, not just the first` checks the order, both outcomes, both
  names in the failure message, and that the flag rebuilds both. Mutation M15.
- **The nightly's Windows leg locally.** No Windows here. It is the same `JavaExec` the gate's
  Windows leg already runs.
