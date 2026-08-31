063c792

# BRIEF-172 — the determinism gate replays the game, not a world written not to drift

`063c792` is the change: every file in this ticket's diff except this one. This brief is the commit
on top of it and touches nothing else — `git log --oneline -2` and `git show --stat HEAD` show
that, so the branch tip and `063c792` are the same code. (A SHA cannot name its own commit, which
is why the top line is the code's rather than `HEAD`'s; `BRIEF-170.md` and `BRIEF-171.md` do the
same.)

Branch `issue-172-replay-gate-at-moba`, off `origin/example` at `db477f4`.

### Which Actions run is on which commit

| run | commit | event | what it is evidence of |
|---|---|---|---|
| [33441678513](https://github.com/wildware-uk/Udea/actions/runs/33441678513) | `8bf85ad` | push | the **cold-cache** leg times of section 5, and nothing else |
| [33443701286](https://github.com/wildware-uk/Udea/actions/runs/33443701286) | `933dfff` | push | criterion 1, and the warm leg times |
| [33444043104](https://github.com/wildware-uk/Udea/actions/runs/33444043104) | `933dfff` | dispatch | criteria 1 and the nightly |
| [33444524021](https://github.com/wildware-uk/Udea/actions/runs/33444524021) | `933dfff` | dispatch, `replay_plant_ulp_at: 1200` | criterion 2 |

**Every run above is on `933dfff`, and the head of this branch is `063c792` plus this file.** The
difference is two commits and neither can change what a leg does:

- `4c86fba` is **test-only** — it adds `MobaReplayEqualityTest > the digest task tells its entry
  point which directory the workspace is` and the comment stripper its build-script fences read
  through.
- `063c792` is **comment-only** — it replaces five counts ("twenty-seven AI units", "six green
  legs", a KDoc counting a job's own lines) with the property behind them, in KDoc and YAML
  comments.

So no `src/main`, no Gradle task, no `ci.yml` step and no checked-in fixture moved after `933dfff`,
and the legs run the identical command over the identical bytes. `git diff 933dfff 063c792 --stat`
and `git log -p 933dfff..063c792` are the check.

Run [33445413997](https://github.com/wildware-uk/Udea/actions/runs/33445413997) is on `9afecf5`,
which is `063c792` plus an earlier draft of this file, and its three `replay-equality` legs and
`join` are green over `moba-3600.udearep`. So criterion 1 holds on a commit whose *code* is
identical to HEAD's, and the only thing that has moved since is the paragraph you are reading.

---

## 1. The evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew :moba:udeaReplayEqualityProof
```

Five processes. Two honest legs replay `moba-3600.udearep` in separate JVMs and their digests are
compared cell for cell; a third leg replays the same recording with one ulp added to the champion's
`Position.x` at t1200, and the join has to catch it and name it. The task fails unless **both**
halves hold.

### It does not exist on `origin/example`

```
$ git show origin/example:moba/build.gradle.kts | grep -c "udeaReplayEqualityProof"
0
(0 matches means the task does not exist there)
```

So it asserts nothing about the branch point; it is entirely new surface.

### It goes red when the feature is reverted

Neutralising the plant — `position.x = Math.nextUp(position.x)` becomes `position.x = position.x`,
the literal diff is M7 in section 7 below — and running the command:

```
* What went wrong:
Execution failed for task ':moba:udeaReplayEqualityProof'.
> a leg with a deliberately planted one-ulp divergence was NOT caught (exit 0). A gate that cannot fail proves nothing.
  replay-equality over 2 leg(s) of 'moba-3600.udearep', 3600 tick(s) from t1
    proof/leg-a  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20]
    proof/leg-planted  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20]

  replay equality holds: 3600 tick(s) of 'moba-3600.udearep' are cell-for-cell identical
```

*(spliced from `mutations/M7-evidence-command-plant-neutralised.log`, lines 206-217, one
consecutive run)*

### What it prints when it passes

```
=== a third leg carrying a planted one-ulp divergence ===
replay-equality over 2 leg(s) of 'moba-3600.udearep', 3600 tick(s) from t1
  proof/leg-a  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20]
  proof/leg-planted  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20]

replay equality FAILED at t1200 (1199 tick(s) matched first)
  fixture moba-3600.udearep
  A = 'proof/leg-a'  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20]
  B = 'proof/leg-planted'  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20]
  world hash: -7461869609382314053 against 367776917239456302
  1 differing cell(s):
    NetId(#0@0) dev.wildware.moba.Position.x
      A = 303.3437 (0x4397abfe)
      B = 303.34372 (0x4397abff)
      the preceding 5 tick(s) of this cell:
        t1195  agreed  A = 300.34366 (0x43962bfd), B = 300.34366 (0x43962bfd)
        t1196  agreed  A = 300.94366 (0x439678ca), B = 300.94366 (0x439678ca)
        t1197  agreed  A = 301.54367 (0x4396c597), B = 301.54367 (0x4396c597)
        t1198  agreed  A = 302.14368 (0x43971264), B = 302.14368 (0x43971264)
        t1199  agreed  A = 302.74368 (0x43975f31), B = 302.74368 (0x43975f31)
...
moba replay-equality proof PASSED: two honest legs agree (exit 0); the planted leg fails (exit 1) naming Position.x at t1200, with five ticks of history.
```

*(elided at the marked `...` — the omitted block is the `--- reproducing this locally ---` guide,
reproduced under criterion 2 in section 6)*

`dev.wildware.moba.Position.x` is the champion's world x. That is the whole point of the ticket:
the gate now names something in the game.

---

## 2. Summary

### What was wrong

#152 and #169 built a working cross-OS `replay-equality` gate — three legs, two operating systems,
two JVM vendors, a field-level divergence report, and a `join` that produces a verdict. It replayed
`DriftWorld`: a few hundred lines of purpose-built drifters that route their trigonometry through
`StrictMath` because their author knew exactly which call was the trap. That world is **written to
be deterministic**. Six green legs (three on the gate, three on the nightly) reported the health of
their own fixture.

### What I did

Both jobs now replay `moba`. `replay-equality` replays `moba-3600.udearep` on every push;
`replay-equality-nightly` replays `moba-36000.udearep`. Each is a real match with the champion
piloted by a fixed-seed `java.util.Random`, and everything else — the AI units, the lane, the
creeps, the towers, the projectiles, the abilities, the shop, the match loop — reproduced from the
seed. The recording carries one peer's input, which is the design `MobaReplay` already had.

`DriftWorld` stays checked in as the gate's **self-test**. It is the only place a divergence of
exactly one ulp on exactly one field at exactly one tick can be arranged over a world whose
`BuildIdentity` is a function of one source file, and it is what
`:udea-replay:udeaReplayEqualityProof` and `CrossPlatformDivergenceTest` use.

### The shape of the change

- **`ReplayDigestCli`** (new, `udea-replay/src/main`) is the whole of a leg's command line: the
  options, the `--workspace` resolution issue #169 is about, the identity refusal, and the
  post-condition that a stream really got written. `MobaDigestMain` and `DriftDigestMain` are each
  about six lines naming their own fixtures, world, registry and plant. Copying the parser into
  `moba` would have been "copy-pasted logic that differs only in a constant" (§8) and, worse, two
  parsers that could disagree about what `--out` means — #169 with a second place to go wrong.
- **`moba/src/test/kotlin/dev/wildware/moba/replay/`** gains `MobaFixture` (the constants and the
  `MobaFixtureKind` set), `MobaFixtureRecorder` (the LCG pilot, the identity, the reconcile set),
  `MobaDigestMain` (the leg entry point and `PlantedMobaWorld`), and two test classes.
- **`moba/build.gradle.kts`** gains `udeaReplayDigest`, `udeaWriteReplayFixture` and the
  five-process `udeaReplayEqualityProof`, modelled on `udea-replay`'s and sharing its entry points.
- **`ci.yml`**: the three `replay-equality` legs and the three `replay-equality-nightly` legs run
  `:moba:udeaReplayDigest`. The two `join` jobs still run `:udea-replay:udeaReplayEquals`, which
  reads nothing but the files.

### Decisions, and what I rejected

Each is also a comment on issue #172 so it is reviewable there.

1. **3600 and 36000 ticks, unchanged from the drift fixtures.**
   ([comment](https://github.com/wildware-uk/Udea/issues/172#issuecomment-5485133081)) The issue
   asks for a shorter tick count if `moba` blows the 240s budget. It does not: 27s, 38s and 54s
   against `DriftWorld`'s 32s, 38s and 49s. Section 5 has the numbers, including the 302s I nearly
   reported as a finding and why it was a cold cache rather than a cost. Rejected: halving the
   fixture, which would have bought back a second in a leg where a second is not the problem.
2. **I changed `ReplayBisectGuide`, which the issue's "Out of scope" bullet 2 forbids.**
   ([comment](https://github.com/wildware-uk/Udea/issues/172#issuecomment-5485134323)) It printed
   `./gradlew :udea-replay:udeaReplayDigest -Pudea.replay.fixture=moba-3600.udearep`, which exits
   non-zero with `no fixture is called 'moba-3600.udearep'`. My change is what broke it, so fixing
   it is in scope by any reading; the change is the smallest one that makes the sentence true
   rather than a better sentence. `.udeaeq` format version 1 → 2; no such file is checked in.
3. **The plant moved to `moba`; `DriftWorld` keeps everything else.**
   ([comment](https://github.com/wildware-uk/Udea/issues/172#issuecomment-5485134568)) Scope says
   `drift-3600` "is what `replay_plant_ulp_at` plants into"; acceptance criterion 2 says the plant
   must name a **`moba`** component. Both cannot be true, and I took the criterion as authoritative.
4. **The plant is a decorator, not a constructor parameter.** `PlantedMobaWorld` wraps
   `MobaReplayWorld` in the test source set, so `moba/src/main` has no branch whose only purpose is
   to corrupt a simulation. It refuses loudly when there is no champion at the plant tick, because
   a match restart has a window with no `Player` in it and a plant that silently wrote nothing
   would make the planted leg agree with every honest one.
5. **The fixture machinery lives in `moba/src/test`, not `src/main` or a new `testFixtures`
   variant.** `MatchShot`, `LaneShot` and `VfxShot` are already `JavaExec` entry points in
   `src/test`; `ReleaseRules.CLASSPATH_RULE` is about `runtimeClasspath`, so this keeps CI
   machinery out of the shipped jar the way this project already does it. Rejected: a
   `java-test-fixtures` variant on `moba` for four files.
6. **`HANDOFF.md` edited.** Its item 3 said "the gate covers the engine's world, not `moba`'s" and
   its next-step item 2 asked for exactly this ticket. Both are false once this merges. It is the
   lead's file, so flagging it here: two paragraphs, no other agent's subject.

### One thing worth knowing that is not a defect

The **first** CI run on any new branch will emit the leg's `::warning::`, because a cold Gradle
cache costs a leg ~250s where a warm one costs ~40s. That is true of every job in this workflow;
this ticket only makes it visible, because `replay-equality` is the job that prints its own wall
time. Section 5 has both samples. I considered raising the budget or slicing `moba`'s test source
set out of the leg's classpath, and did neither, because with a warm cache there is nothing to fix.

---

## 3. `sh gradlew build`

Two runs. **The first failed**, on two tasks, and the box was carrying `melon-merge`'s suite at the
time (`pgrep -f "[m]elon-merge"` returned 18 processes; load average 10-13).

```
2 tests completed, 2 failed

> Task :udea-assets-compiler:udeaDaemonBudget FAILED

DaemonLatencyBudgetTest > a warm validate of one edited script is under 300ms() STANDARD_OUT
    warm validate of one script: median 405ms over 4 samples [19, 554, 405, 317]

DaemonLatencyBudgetTest > a warm validate of one edited script is under 300ms() FAILED
    org.opentest4j.AssertionFailedError at DaemonLatencyBudgetTest.kt:60
...
BUILD FAILED in 1m 11s
215 actionable tasks: 81 executed, 34 from cache, 100 up-to-date
```

*(spliced from `full-build.log`; the `...` elides the second failure's block,
`:udea-core:udeaBenchCharacterMover`, and Gradle's deprecation notice)*

Both are wall-clock budgets. **Re-run alone, both pass**, which is what the brief asks for:

```
$ JAVA_HOME=... sh gradlew :udea-assets-compiler:udeaDaemonBudget :udea-core:udeaBenchCharacterMover
CharacterMoverBudgetTest > 200 movers replayed 60 times fit in the per-frame budget() STANDARD_OUT
    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) median 2.049ms, budget 4.0ms
DaemonLatencyBudgetTest > a warm reload of one script decides inside the edit-to-observe budget() STANDARD_OUT
    warm reload decision: median 162ms over 4 samples [162, 178, 160, 137]
    warm validate of one script: median 109ms over 4 samples [9, 106, 116, 109]
BUILD SUCCESSFUL in 7s
```

162ms and 109ms against the same budgets that measured 832ms and 405ms under load; 2.049ms against
a 4.0ms budget. That is the box, not the branch.

**Second full run, on the quieter box:**

```
> Task :udeaVerifyMigration
> Task :check
> Task :build

BUILD SUCCESSFUL in 8s
206 actionable tasks: 4 executed, 202 up-to-date
Configuration cache entry reused.
```

Across every module's JUnit XML in the tree: **2549 tests, 0 failures, 0 errors, 34 skipped**
(the 34 are the GL tests, which skip without a `DISPLAY` — see below, where they are run for real).

### GL, run for real under xvfb

This ticket does not touch `udea-render`, but the reviewer is told to treat an omission as a
finding, so:

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew udeaGlTest --rerun udeaAgentGlTest --rerun -Pudea.render.requireGl=true
```

`--rerun` on both is load-bearing. Without it the first attempt reported
`> Task :udea-render:udeaGlTest FROM-CACHE`, and the cached entry was from a run with **no**
`DISPLAY`, i.e. a skip. A cached green is the same trap as a skipped green.

```
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest

BUILD SUCCESSFUL in 6s
41 actionable tasks: 2 executed, 39 up-to-date
```

Read out of the results rather than out of the console:

```
udea-render/build/test-results/udeaGlTest: 18 tests, 0 failures, 0 skipped, 4 classes
    dev.wildware.udea.render.gl.GlCaptureDeterminismTest
    dev.wildware.udea.render.gl.GlCaptureTest
    dev.wildware.udea.render.gl.GlOverlayIsolationTest
    dev.wildware.udea.render.gl.OffscreenBackendTest
udea-agent-host/build/test-results/udeaAgentGlTest: 8 tests, 0 failures, 0 skipped, 2 classes
    dev.wildware.udea.agent.host.gl.OffscreenRenderToolsTest
    dev.wildware.udea.agent.host.gl.OverlayCaptureIsolationTest
```

---

## 4. The gate driven for real, over the agent tool surface

Compiling is not evidence, so the running game was asked to verify the checked-in fixture itself.
A `moba` instance under xvfb on port 7841, `/health` reporting `Offscreen`; the fixture copied into
`moba/build/udea/recordings/`; `replay.load` and `replay.verify` called over the debug surface and
the results read back out of `/state`:

```json
{
  "id": 4, "ok": true,
  "result": {
    "loaded": "moba-3600",
    "recording": {
      "gameId": "moba", "gameVersion": "0.1.0", "firstTick": 1, "endTick": 3601,
      "tickCount": 3600, "peerCount": 1, "tickRateHz": 60, "durationSeconds": 60,
      "rootSeed": 0, "protoHash": 7151, "inputSchemaHash": 2229103034793186487,
      "assetGraphHash": "76a6569c7001840665cf3414a01762af22e234f3ce832796993608031accef55",
      "axes": ["moba/move"], "actions": ["moba/attack", "moba/attack_2"]
    },
    "position": {"tick": 1, "rebuilds": 0, "ticksRun": 0, "firstDivergentTick": -1}
  }
}
{
  "id": 3, "ok": true,
  "result": {
    "bitExact": true, "ticksCompared": 3600, "matchingTicks": 3600, "firstDivergentTick": -1,
    "summary": "bit-exact: 3600 tick(s) from t1 replayed to the recorded hash stream, every tick"
  }
}
```

*(the two results are shown newest-last for reading; `/state` returns them in id order, and `id: 3`
is the `replay.verify` that ran before the `replay.info` at `id: 4`. Neither block is elided.)*

So the shipped agent surface can load and verify the exact bytes the CI legs replay. The `replay`
toolset this instance publishes is `replay.info`, `replay.load`, `replay.rewind`, `replay.seek`,
`replay.step`, `replay.verify` — read off the instance with `list_toolsets` rather than assumed,
because `/tools` is generated and has moved before.

**What the tool surface cannot do, stated rather than worked around:** it cannot *render* the
replay. `MobaReplayHost.worlds` builds a fresh headless world for the replay session, so
`render.screenshot` captures the live host and not the replayed one. The images in section 8 are
the live game stepped tick by tick, not a rendering of the fixture, and they are labelled that way.

---

## 5. The 240s budget, measured — and the finding I retracted

The issue asks for the leg wall time in the job summary against #152's 240s budget. The step that
prints it is unchanged, so it is there on every run, green or red.

**The first run on this branch said the windows leg took 302s and I nearly reported that as a
finding.** It was the first run on a *new* branch, so `gradle/actions/setup-gradle` had no cache
entry for the ref and every leg rebuilt the world from nothing. The next push says 54s.

| leg | drift, [33438832167](https://github.com/wildware-uk/Udea/actions/runs/33438832167) | moba, [33441678513](https://github.com/wildware-uk/Udea/actions/runs/33441678513) (**cold**) | moba, [33443701286](https://github.com/wildware-uk/Udea/actions/runs/33443701286) (warm) |
|---|---|---|---|
| ubuntu-latest / temurin | 32s | 212s | **27s** |
| ubuntu-latest / corretto | 38s | 195s | **38s** |
| windows-latest / temurin | 49s | 302s | **54s** |
| *replay itself, ubuntu/temurin* | *394ms* | *1964ms* | *2038ms* |
| *replay itself, windows* | *449ms* | *2462ms* | *2644ms* |
| *digest bytes, ubuntu/temurin* | *1,626,969* | *2,326,517* | *2,326,517* |

Run 33438832167 is `example` at the branch point, replaying `drift-3600.udearep`; both others are
this branch replaying `moba-3600.udearep`. Nothing but the cache differs between the two `moba`
columns — same fixture, same tick count, same matrix.

**So replaying the game costs about what replaying the fixture world cost**: 27/38/54 against
32/38/49. The replay itself is genuinely ~1.5s slower per leg and that disappears into a leg whose
wall time is checkout, JDK setup and a Gradle build. **The 240s budget is not threatened.**

What *is* true, and narrower than what I first wrote: **the first CI run on any new branch will
emit the `::warning::`**, because a cold cache costs ~250s a leg. A reader who sees it should look
at the second run before acting on it. I have withdrawn the three mitigations I proposed on the
issue for a problem that turned out not to exist; the correction and both wrong versions are left
visible [there](https://github.com/wildware-uk/Udea/issues/172#issuecomment-5485133081).

### `--workspace` resolves to the repository root, executed rather than asserted

Issue #169's defect, for the third `udeaReplayDigest` in this tree. The workflow's exact `--out`
string, run by hand from the worktree root:

```
$ sh gradlew :moba:udeaReplayDigest -Pudea.replay.label=ubuntu-latest/temurin-17 \
    -Pudea.replay.out=digests/ubuntu-latest-temurin.udeaeq
ubuntu-latest/temurin-17: replayed 3600 tick(s) in 1717ms into ubuntu-latest-temurin.udeaeq
  2326516 bytes at /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a8b9947d3dbda0c54/digests/ubuntu-latest-temurin.udeaeq

$ ls -la digests/
-rw-rw-r--  1 shaun users 2326516 Aug 31 21:58 ubuntu-latest-temurin.udeaeq
$ git rev-parse --show-toplevel
/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a8b9947d3dbda0c54
$ ls moba/digests
ls: cannot access 'moba/digests': No such file or directory
```

The repository root, which is what `actions/checkout` makes `$GITHUB_WORKSPACE` and what
`actions/upload-artifact` globs — **not** `moba/digests/`, which is where a `JavaExec` with no
`--workspace` would have put it. `MobaReplayEqualityTest > the digest task tells its entry point
which directory the workspace is` is the fence, and mutations M9 and M10 are it going red.

---

## 6. The issue, criterion by criterion

### ☑ 1. A real Actions run shows all three `replay-equality` legs and the `join` green, replaying a `moba` fixture

**Run [33443701286](https://github.com/wildware-uk/Udea/actions/runs/33443701286)** (push,
`4c86fba`'s parent `933dfff`) and **run
[33444043104](https://github.com/wildware-uk/Udea/actions/runs/33444043104)** (dispatch, same code)
both show it. From the second's `replay-equality (join)` log:

```
replay equality holds: 3600 tick(s) of 'moba-3600.udearep' are cell-for-cell identical
  A = 'ubuntu-latest/corretto-17'  [Linux amd64; Amazon.com Inc. OpenJDK 64-Bit Server VM 17.0.20.1]
  B = 'ubuntu-latest/temurin-17'  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20.1]
  B = 'windows-latest/temurin-17'  [Windows Server 2025 amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20.1]
```

*(the three `A =`/`B =` lines are the distinct leg identities the join printed across its two
pairwise comparisons, collected with `sort -u`; they are not four consecutive lines of the log. The
`holds:` line is verbatim.)*

Three legs green, two operating systems, two JVM vendors, and the fixture named in the verdict is
`moba-3600.udearep`.

### ☑ 2. `replay_plant_ulp_at` on the `moba` fixture makes the join report a divergence naming a real `moba` component and field

**Run [33444524021](https://github.com/wildware-uk/Udea/actions/runs/33444524021)**, a
`workflow_dispatch` with `replay_plant_ulp_at: 1200`. `replay-equality (join)` is **red**, and the
three legs are green — which is the shape it has to have: a leg that fails to produce a digest is a
different failure from two legs that disagree. From that job's log, one consecutive block:

```
replay equality FAILED at t1200 (1199 tick(s) matched first)
  fixture moba-3600.udearep
  A = 'ubuntu-latest/corretto-17'  [Linux amd64; Amazon.com Inc. OpenJDK 64-Bit Server VM 17.0.20.1]
  B = 'windows-latest/temurin-17'  [Windows Server 2025 amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20.1]
  world hash: 367776917239456302 against -7461869609382314053
  1 differing cell(s):
    NetId(#0@0) dev.wildware.moba.Position.x
      A = 303.34372 (0x4397abff)
      B = 303.3437 (0x4397abfe)
      the preceding 5 tick(s) of this cell:
        t1195  agreed  A = 300.34366 (0x43962bfd), B = 300.34366 (0x43962bfd)
        t1196  agreed  A = 300.94366 (0x439678ca), B = 300.94366 (0x439678ca)
        t1197  agreed  A = 301.54367 (0x4396c597), B = 301.54367 (0x4396c597)
        t1198  agreed  A = 302.14368 (0x43971264), B = 302.14368 (0x43971264)
        t1199  agreed  A = 302.74368 (0x43975f31), B = 302.74368 (0x43975f31)
```

`dev.wildware.moba.Position.x` — a real `moba` component and a real field, at the tick the input
named, with the five preceding ticks agreeing.

Two things in that block are worth reading rather than skimming. `A` is `ubuntu-latest/corretto`,
which is the one leg the matrix marks `plant: true`; the join printed the *same* divergence against
the ubuntu-temurin leg as against the windows one, so the two honest legs agreed with each other on
Windows and Linux (`0x4397abfe` both) while the planted one is one ulp away (`0x4397abff`). And the
report says `1 differing cell(s)` — one ulp on one field of one entity showed up as exactly one
cell, on the tick it was planted.

Locally, the same claim through `:moba:udeaReplayEqualityProof` (section 1) and through
`MobaReplayEqualityTest > a planted one-ulp divergence is caught and names a real moba component
and field`, which asserts `componentName == "dev.wildware.moba.Position"`, `fieldName == "x"`,
`result.tick == MobaFixture.PLANT_TICK`, exactly one differing cell, and five agreeing history
entries. Mutation M2 in section 7 is it going red.

The reproduce block the join prints under a red gate, from the local proof's `planted.txt`:

```
--- reproducing this locally ---
Both halves of the gate, in five processes on one machine:
  ./gradlew :moba:udeaReplayEqualityProof
This leg on its own, against the same recording:
  ./gradlew :moba:udeaReplayDigest -Pudea.replay.fixture=moba-3600.udearep -Pudea.replay.label=mine
The divergence is at t1200, so walk into it. There is no single bisect tool: the surface is the 5 calls below, and issue #149 is where the loop is described.
  replay.load    {"name": "moba-3600.udearep"}
  replay.verify  {}
  replay.seek    {"tick": 1199}
  replay.step    {"ticks": 1}
  replay.rewind  {"ticks": 1}
Read the world between the last two with `world.*`; they are a loop, and the recording is bit-exact in both directions.
```

It says `:moba:` because of decision 2. Before that change it said `:udea-replay:`, and that command
exits non-zero.

### ☑ 3. The measured wall time of the `moba` leg is in the job summary, against the 240s budget

The `Report this leg's wall time` step is unchanged: it appends `| <os> / <dist>-17 | <n>s |` to
`$GITHUB_STEP_SUMMARY` on every run, green or red, and emits `::warning::` past 240s. Section 5 has
the numbers and the finding.

### ☑ 4. The regeneration command is documented where the next person will look

Four places, in the order somebody meets them:

1. **The failure message.** `ReplayFixtures.requireCurrent` prints
   `./gradlew :moba:test -Dupdate.replay.fixtures=true` when a fixture goes stale, and
   `MobaReplayFixturesCurrentTest` is what fires it. Mutation M4 in section 7 is that message
   arriving.
2. **`determinism-audit.md` §0**, new: what each job replays, where the bytes are, all three
   regeneration commands, and why review of the diff is possible at all (the LCG).
   `determinism-audit.md` is the document whose §1 already says the cross-OS job is the *only*
   thing that catches float differences across JVMs, so it is where a reader is already going.
3. **`HANDOFF.md` item 3**, rewritten, with the two commands.
4. **The KDoc** on `MobaFixtureRecorder`, `MobaFixturesMain` and `:moba:udeaWriteReplayFixture`.

### ☑ Scope: `drift-3600` stays beside the `moba` fixtures, not replaced

`udea-replay/src/testFixtures/resources/fixtures/drift-3600.udearep` and `drift-36000.udearep` are
untouched, and `:udea-replay:udeaReplayDigest`, `:udea-replay:udeaWriteReplayFixture` and
`:udea-replay:udeaReplayEqualityProof` all still exist and still replay them.
`CrossPlatformDivergenceTest` is untouched.

### ☑ Scope: the digest folds `moba`'s components

`ReplayDigestRecorder` takes the registry as an argument and `MobaDigestMain` hands it
`MobaReplay.REGISTRY` — the same registry *object* `MobaReplayWorld`'s `SnapshotService` was built
over, which matters because `WorldFieldStore.diffInto` compares registries by identity. No registry
work was needed: `MobaReplay.REGISTRY` already existed for `MobaReplayProofTest`. The divergence
report in section 1 naming `dev.wildware.moba.Position.x` is the folding working.

### ☑ Scope correction: the nightly is pointed at `moba` too

`replay-equality-nightly` runs `:moba:udeaReplayDigest -Pudea.replay.fixture=moba-36000.udearep`.
It did **not** turn out too large or too slow to check in: the recording is 586,987 bytes and the
digest 19,533,419, against 665,794 and 16,466,747 for `drift-36000`. Numbers and the artefact
arithmetic are in section 5 and in `ci.yml`'s own comment.

**Run [33444043104](https://github.com/wildware-uk/Udea/actions/runs/33444043104)** shows all
three nightly legs and `replay-equality-nightly (join)` green over the long fixture:

```
replay equality holds: 36000 tick(s) of 'moba-36000.udearep' are cell-for-cell identical
  A = 'nightly/ubuntu-latest/corretto-17'  [Linux amd64; Amazon.com Inc. OpenJDK 64-Bit Server VM 17.0.20.1]
  B = 'nightly/ubuntu-latest/temurin-17'  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20.1]
  B = 'nightly/windows-latest/temurin-17'  [Windows Server 2025 amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20.1]
```

*(same collection caveat as criterion 1: the `holds:` line is verbatim and the three leg identities
are `sort -u` over the join's two comparisons.)*

**That is the result this ticket exists to make possible**, and as far as I can tell it is the first
time this repository has established it: ten minutes of a real `moba` match — every replicated
field of every unit, plus the RNG state and the id allocator, on each of 36,000 ticks — is bit-exact
across Windows Server 2025 and Linux, and across Amazon Corretto and Eclipse Adoptium. Nightly leg
wall times were 90s (windows), 156s and 166s (ubuntu), and the replay itself 13.7-15.4s.

### ☑ Scope: the fixture is regenerable by a documented command, not by hand

`MobaReplayEqualityTest > the checked-in gate fixture is regenerable, input for input` rebuilds the
3600-tick recording from `MobaFixtureRecorder` and compares the input stream sample for sample
against the checked-in file, and asserts that more than a quarter of the ticks carry input at all
so that a recording of an idle champion could not pass. Mutation M3 is it going red.

---

## 7. Mutations: every fence, watched failing

Each row is the literal `git diff` of the mutation and the test names that went red, taken from the
run rather than retyped. Failing names are parsed out of the JUnit XML rather than by grepping for
`FAILED`, which also matches `BUILD FAILED`. Full diffs and logs are under
`mutations/` in the scratch directory named in section 9.

| # | mutation | what went red |
|---|---|---|
| M1 | `ci.yml`: `- ./gradlew :moba:udeaReplayDigest` / `+ ./gradlew :udea-replay:udeaReplayDigest` on the gate leg | `ReplayEqualityProofTest > the legs run the game's digest task and not this module's` — *the 'replay-equality' job no longer replays the game* |
| M2 | `MobaDigestMain.kt`: `- position.x = Math.nextUp(position.x)` / `+ position.x = position.x` | `MobaReplayEqualityTest > a planted one-ulp divergence is caught and names a real moba component and field` — *a planted one-ulp divergence was not caught at all* |
| M3 | `MobaFixture.kt`: `- PILOT_SEED: Long = 0x0BA_5EED_172L` / `+ 0x0BA_5EED_173L` | `MobaReplayEqualityTest > the checked-in gate fixture is regenerable, input for input` — *the pilot diverges at t1: the file holds InputSample(moba/move=(1.0, -1.0)) and a rebuild produces InputSample(moba/move=(-1.0, -1.0))* |
| M4 | `MobaFixture.kt`: `- NIGHTLY_TICKS: Int = 36_000` / `+ 30_000` | `MobaReplayEqualityTest > the gate replays the short recording and the nightly the long one` (*expected: 30000 but was: 36000*) **and** `MobaReplayFixturesCurrentTest > every checked-in moba replay fixture can be replayed by this build` (*1 replay fixture(s) cannot be replayed by this build*) |
| M5 | `ReplayBisectGuide.kt`: `- append(gradleProject).append(":udeaReplayDigest")` / `+ append(":udea-replay:udeaReplayDigest")`, both lines | `ReplayBisectGuideTest > the reproduction command names the project that owns the fixture` |
| M6 | `ci.yml`: `- -Pudea.replay.fixture=moba-36000.udearep` / `+ drift-36000.udearep` on the nightly | `MobaReplayEqualityTest > every fixture the workflow names is one this game has` — *no fixture is called 'drift-36000.udearep'; this world has moba-3600.udearep, moba-36000.udearep* |
| M7 | M2's diff, against the **evidence command** rather than the test suite | `:moba:udeaReplayEqualityProof` fails — transcript in section 1 |
| M8 | `moba/build.gradle.kts`: `- val replayPlantTick = "1200"` / `+ "1500"` | `MobaReplayEqualityTest > the proof task plants at the tick this game declares` |
| M9 | `moba/build.gradle.kts`: the two lines `- add("--workspace")` / `- add(workspace)` deleted from `udeaReplayDigest` | `MobaReplayEqualityTest > the digest task tells its entry point which directory the workspace is` — *it must pass --workspace exactly once; the build script has 0 ==> expected: <1> but was: <0>* |
| M10 | the same two lines **commented out** rather than deleted (`+ // add("--workspace")`) | the same test, the same message — which is the point: the fence reads a comment-stripped copy, so a switched-off line does not count as a live one |

M9 and M10 are a pair on purpose. M9 proves the assertion fires; M10 proves the *helper under it*
does, on the real file — Kotlin block comments nest, and one stray slash-star in a KDoc has already
switched off every task registered below it in `udea-replay/build.gradle.kts` once, silently.

M4 is worth reading twice: moving the *pilot seed* (M3) does **not** fail
`MobaReplayFixturesCurrentTest`, because a `BuildIdentity` does not cover the pilot — only the
length and the four identity fields. The two fences cover different things, and each has a mutation
that only it catches.

### Controls run, not assumed

- **`MobaReplayEqualityTest > a comment naming a fixture is not a job running one`** — the fence
  over `ci.yml` reads a comment-stripped copy, because the workflow's *prose* names
  `moba-36000.udearep` while explaining the nightly. The test runs the known negative (a
  commented-out `-Pudea.replay.fixture=not-a-fixture-at-all.udearep`) and checks it does not count,
  and asserts the real file really does carry that prose line — so the stripper is being exercised
  rather than agreed with by accident.
- **`MobaReplayEqualityTest > two honest legs of the gate fixture agree cell for cell`** — the
  control for the planted test. A comparison that reported a divergence between two identical runs
  would make the planted one prove nothing.
- **`ReplayBisectGuideTest > the reproduction command names the project that owns the fixture`**
  asserts both directions (`:moba` for a `moba` fixture, `:udea-replay` for a drift one), so it is
  not a fence that only knows one answer.
- **`MobaReplayEqualityTest > the build script fence reads what the compiler reads, not what is
  switched off`** — the control for the comment stripper the two build-script fences read through,
  in both directions and against the **real** file: it asserts that a KDoc line survives a raw read
  of `moba/build.gradle.kts` and does not survive the strip, so the fences cannot be passing
  because they are reading prose. M10 is the same claim from the failing side.
- **`ReplayEqualityPathsTest > a stream with bytes in it passes and reports its size`** (existing)
  is the positive case for the post-condition.
- **The evidence command's task graph** was checked against `origin/example` (section 1): it does
  not exist there, so it cannot be passing for an uninteresting reason.

---

## 8. Images

All in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`.

These are the **live** game driven over the agent tool surface — paused, rewound into the window
`moba-3600.udearep` covers, and stepped 120 ticks between captures. Each tile's tick is read out of
that capture's own result rather than computed, because `render.screenshot`'s description says a
time tool sent in the same batch runs after the capture. They are **not** a rendering of the replay:
see section 4 for why that is not possible with today's tool surface.

- **`issue172-fixture-window-t1281-t1881.png`** — six frames from t1281 to t1881, the window the
  gate's fixture covers. The champion (ORC_ELITE) wades into a stack of soldiers and its health
  falls 200 → 150 → 120 → 50 → 10, and the last tile is `SOLDIER WINS` / `YOU DIED`. It shows that
  the recording covers a whole match rather than a slice of idle time, which is what makes the
  fixture worth replaying.
- **`issue172-plant-tick-1281-champion-in-the-melee.png`** — t1281 on its own, the neighbourhood of
  the plant tick. `MATCH 1`, `ORC 1 SOLDIER 11 UNDEAD 0`, the champion at 200/500 mid-swing against
  five soldiers. This is the world state whose `Position.x` the planted leg moves by one ulp.
- **`issue172-match-ends-inside-the-fixture-t1881.png`** — t1881, the match resolving inside the
  3600-tick window. Proof the fixture is not a minute of standing still.

---

## 9. Regenerated files, and where the working artefacts are

**`net-protocol.lock` and `expected-generated-hashes.txt`: neither, and no id moved.** No
`@Replicated` component was added or removed. `MobaFixtureRecorder`'s identity reports
`protoHash: 7151` (section 4), and `git diff origin/example -- udea-codegen/net-protocol.lock
udea-codegen/src/test/resources/expected-generated-hashes.txt` is empty. `udeaCheckProtocolLock`
runs on `check` and the build is green.

**`.udeaeq` format version 1 → 2.** Not a checked-in file: a digest stream is written by a leg,
downloaded by the join and deleted after seven days. No migration, and a stale stream now refuses
itself by name.

**New checked-in binaries**, both regenerable with `./gradlew :moba:udeaWriteReplayFixture`:

```
moba/src/test/resources/fixtures/moba-3600.udearep    59,140 bytes
moba/src/test/resources/fixtures/moba-36000.udearep  586,987 bytes
```

**Working artefacts** (logs, mutation diffs, the CI logs I spliced from) are under
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/01ec1be7-305f-4987-ab53-69f61b72d43e/scratchpad/`:
`full-build.log`, `full-build-2.log`, `solo-budgets.log`, `gl-tests-rerun.log`, `mutations/`,
`legs/`, `failcauses/`.

---

## 10. The five CI checks that are red, and why four of them are not mine

`origin/example` is **not green in CI** at the branch point. Verified by comparing failing Gradle
tasks between run 33441678513 (mine) and run 33438832167 (`example` at `db477f4`):

| job | fails on `example` at `db477f4` | fails on this branch | same task? |
|---|---|---|---|
| `build (ubuntu-latest)` | `:udea-agent-host:udeaPhase2Exit`, `:udea-assets-compiler:udeaDaemonBudget` | `:udea-agent-host:udeaPhase2Exit` | yes |
| `build (windows-latest)` | `:udea-agent-host:udeaPhase2Exit`, `:udea-assets-compiler:test` | `:udea-assets-compiler:test`, `:udea-assets-compiler:udeaDaemonBudget` | yes |
| `build with the K2 plugin disabled` | `:udea-agent-host:udeaPhase2Exit`, `:udea-assets-compiler:udeaDaemonBudget` | `:udea-agent-host:udeaPhase2Exit` | yes |
| `determinism (windows-latest, temurin/corretto)` | `:test` — `AgentsMdTest > a row for a module that has been deleted fails`, `CompilerPluginSwitchTest > the checkers-fire probe is not written into a tree build-logic compiles a second time`, `CompilerPluginSwitchTest > the checkers-fire probe is in a module the K2 plugin is actually applied to` | `:test` — the same three | yes |
| `clean build under budget` | **passes** (81,418ms) | **fails** (97,716ms, budget 90,000ms) | — |

### One more, seen once in four runs, in code this branch does not touch

`gl tests (xvfb)` failed on run 33444043104 and passed on 33441678513, 33443701286 and 33444524021
— four runs of near-identical code, one red:

```
OffscreenBackendTest > closing the backend stops the render thread() FAILED
    org.opentest4j.AssertionFailedError at OffscreenBackendTest.kt:206
        Caused by: dev.wildware.udea.render.backend.GlContextException at OffscreenBackendTest.kt:206
            Caused by: java.util.concurrent.CancellationException at OffscreenBackendTest.kt:206
```

A shutdown race on the GL thread in `udea-render`, a module this branch does not modify. All 18
`udeaGlTest` tests pass locally under xvfb with `--rerun` (section 3). Flagging it because one
sample in four is exactly the rate at which a flake gets attributed to whoever is unlucky, and
because I would rather it be written down before somebody else meets it.

The windows `:udea-assets-compiler:test` and the `determinism` `AgentsMdTest` /
`CompilerPluginSwitchTest` failures are issue **#176**, which dev-176 is fixing on its own branch —
CRLF in checked-in golden files under a `core.autocrlf=true` checkout. `udeaPhase2Exit` and
`udeaDaemonBudget` are the wall-clock latency budgets, red under CI runner load exactly as they are
under `melon-merge` load here (section 3).

**`clean build under budget` failed on run 33441678513 and passed on run 33443701286**, the same
cold-cache-then-warm pair as the leg times in section 5:

The red one, from `mine-clean-budget.log` (the saved `--log-failed` output of job 99651148467):

```
##[error]clean build took 97716 ms, over the 90000 ms budget (spec 6, Phase 0 exit)
```

The other three, from `cleanbudget-history.txt` (the saved output of the script that fetched them).
Its own first line is truncated at `ove` by an Actions line break, which is why the red one is
quoted above from its own log rather than from here:

```
run 33441678513 job 99651148467: clean build took 97716 ms, ove
run 33443701286 job 99657686646: clean build took 66890 ms, within the 90000 ms budget
run 33438832167 job 99641769151: clean build took 81418 ms, within the 90000 ms budget
run 33437939749 job 99638839477: clean build took 66671 ms, within the 90000 ms budget
```

The last two are `example`: consecutive runs of a tree this branch had not touched, differing by
22%.

66,890ms against `example`'s 66,671ms is as close as two samples of this measurement get. That is
the whole answer: my change adds one new file to `udeaAssemble` (`ReplayDigestCli.kt`, ~230 lines
in `udea-replay/src/main`) and nothing in `moba/src/test` is in `udeaAssemble` at all.

---

## 11. My own pass over the diff, against the reviewer's closed list

**Engineering standards §8**

- *Any §1 smell reproduced in new code* — the one candidate was duplicating `DriftDigestMain`'s
  parser into `moba`; `ReplayDigestCli` exists so that it is not duplicated. The remaining
  near-duplicate is `moba/build.gradle.kts`'s `udeaReplayEqualityProof` block against
  `udea-replay`'s: two Gradle task graphs over two different projects' classpaths and main classes,
  which Kotlin DSL cannot share without a convention plugin. I judged a convention plugin for two
  callers worse than the repetition, and the shared half — the entry points both drive — is shared.
- *A `public` declaration nobody outside the module uses* — everything new in `moba/src/test` is
  scoped to that compilation, which exports nothing. In `udea-replay/src/main`, `ReplayDigestCli`
  and `ReplayFixtureRef` are consumed by `moba`; `ReplayDigestHeader.gradleProject` is read by
  `ReplayEqualsMain`.
- *A test that cannot fail* — eight mutations in section 7, plus five controls.
- *Generated code by string concatenation* — none; nothing here generates code.
- *A new field on `GameContext`* — none.
- *Wall clock or unseeded randomness inside simulation code* — the pilot is `java.util.Random` with
  a fixed seed and **never enters a world**: it writes into an `InputSample` before the tick, which
  is the `IntentSource` seam a keyboard sits behind. `ReplayDigestRecorder`'s `System.nanoTime` is
  a build measurement outside `Simulation.step()`, and it is pre-existing.
- *A `TODO()`, a stubbed return, or a swallowed exception on a reachable path* — none. The one new
  refusal (`PlantedMobaWorld.plant`) throws with the reason rather than returning.
- *Copy-pasted logic differing only in a constant* — covered above.

**`AGENTS.md` "Do not"** — no `by net(...)`; no second snapshot codec (the digest is written from
`WorldSnapshot`, and `ReplayDigestWriter` refolds its own cells into the world hash and refuses a
tick that does not reproduce it); no setter instrumentation; no wall clock or unseeded randomness in
`step()`; nothing new depends on `common`; no reflection on a per-tick path; no bare
`Int`/`Long`/`String` for a domain concept — `plantAt` is a `Tick`, `PLANT_TICK` is a `Tick`, and
the two tick *counts* are counts rather than durations, with the KDoc saying why they are not
seconds; no GL outside `udea-render`; no presentation system as a Fleks system;
`udeaVerifyModuleGraph` and `udeaVerifyNoLegacyDependencies` pass.

**And the four this repository's own documents make blocking**

- *A `docs/contracts/` file changed* — no. `git diff --stat origin/example -- docs/contracts/` is
  empty. The `.udeaeq` digest format is not in `docs/contracts/`; the three files there are
  `agent-tools.md`, `asset-index.md` and `replicator.md`.
- *The `fieldNames[i]` == FieldMask bit *i* == FieldStore index *i* alignment* — untouched. Nothing
  here writes a replicator or a field store; the digest reads `ReplayDigestIo.componentsOf(registry)`
  exactly as before.
- *A duration expressed in seconds or milliseconds rather than a `Tick`* — see above.
- *`AGENTS.md`'s module table left stale* — no module moved; `udeaVerifyAgentsMd` passes.

**What I did not exercise**

- **A red gate on a real cross-OS run.** The planted dispatch proves the join fails on a *planted*
  divergence. Nobody has yet seen `moba` diverge for a real reason across two operating systems,
  which is the thing this gate exists to find, and the honest position is that it may never — or
  may on the first nightly.
- **A `BuildIdentity` move in anger.** `MobaReplayFixturesCurrentTest` is proven by M4's tick-count
  mutation, not by adding a `@Replicated` component and watching `protoHash` shift. That path is
  `ReplayFixtureUpdateTest`'s in `udea-replay`, over a synthetic fixture.
- **The `--update-replay-fixtures` flag reaching the test JVM for `moba`.** `udea-replay` has a
  build-script fence for that forwarding (`ReplayEqualityProofTest > the test task forwards the
  regeneration flag to the JVM that reads it`); `moba` has the same `systemProperty` line but no
  fence over it. I ran the flag by hand and it regenerated (that is how the checked-in bytes were
  made), so it works today; nothing would catch it being deleted. The stripper and `buildScript`
  are now in `MobaReplayEqualityTest`, so adding that fence is three lines for whoever wants it.
- **A second peer.** The recording carries one peer, as `MobaReplay` was already designed. A
  two-peer recording is a different ticket.
- **The `36000`-tick fixture end to end locally.** I replayed it once to measure (section 5) but
  `:moba:udeaReplayEqualityProof` uses the 3600 one, and nothing local compares two 36000-tick
  digests. The nightly does.
