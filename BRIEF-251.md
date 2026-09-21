a139f001

# Issue #251 — Hollow H3: foxes that wander, chase and flee, in waves

Branch `issue-251-fox-waves`. Round 2 tree: `a139f001`, which merges `origin/master` `4a4a2df5`
(#271 and windows-green) into `1361afa7` (the round-1 fix). The commit that updates this file
changes nothing else.

Every artefact quoted below is a file under
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/dev251/`,
written `dev251/` from here on.

## 0. Round 2

### The finding

Review round 1 found `Fox.snapshotType()` public with its only caller, `HollowNet.kt`, in the same
module. It is now `internal` (`1361afa7`).

**The same kind of problem elsewhere.** I listed every `public` this branch adds in `hollow/`
(`git diff a99acb9f HEAD -- 'hollow/*.kt' | grep '^+.*\bpublic\b'`). One more had the problem:
`FoxWaves`' companion object was public, but every member in it was internal. It is now
`internal companion object`. These stay public because `hollow:desktop` uses them:
- `Fox` and its companion: `HollowFoxShot` uses `family { all(Fox, ...) }`, `entity[Fox]` and `fox.mode`.
- `FoxMode`: it is the type of the public `Fox.mode`.
- `FoxWaves`: `HollowFoxShot` builds its own schedule.
- `HollowFoxShot.main`: the `runFoxShot` entry point.

`Fox`'s `public var` fields follow the existing `Player` component, whose `@Sim` fields are
public too.

### The merge, and the generated files

This branch merged second, after #271, so it regenerated every generated-file family on the merged
tree. Only the two moba fixtures conflicted (they are binary). Neither side was picked:
`git checkout --ours` only put a file in place for `udeaWriteReplayFixture` to rewrite.

**What each side had moved.** I measured this before predicting, from the merge's own stages
(`git show :1:`, `:2:` and `:3:`, script `dev251/r2/stages.sh`). The regions follow
`ReplayFormat.kt`: little-endian, a 155-byte header, the asset hash at bytes 27..59
(`dev251/r2/regions.py`, output `dev251/r2/stages-regions.txt`).
- #271, base to master: asset hash 32 bytes (`f3556cc2...` to `c011c548...`), crc 4 bytes, and
  nothing else.
- #251, base to this branch: protoHash 2 bytes (`0x07b6` to `0xc979`), hashes 28713 bytes
  (3600) and 287018 bytes (36000), crc 4 bytes, and nothing else. Frames 0 on both sides.

**Prediction, frozen and sent to the lead before any regeneration** (`dev251/r2/prediction.txt`),
against what happened:

| | Predicted | Measured |
|---|---|---|
| P1 `net-components.lock` | unchanged from this branch | unchanged; `udeaWriteNetComponents` rewrote it (mtime 05:27:03) |
| P2 the four `net-protocol.lock` files | unchanged | unchanged; each task logged `wrote` (mtimes 05:27:40 to 05:28:02) |
| P3 `expected-generated-hashes.txt` | unchanged | unchanged; rewritten by the `-Pudea.updateGeneratedHashes=true` run (05:28:37) |
| P4 each fixture, regenerated vs this branch | asset hash 32 bytes, equal to master's; crc 4 bytes; every other region 0 | exactly that, both fixtures |
| P5 each fixture, regenerated vs master | protoHash 2 bytes; hashes 28713 / 287018 bytes; crc 4 bytes; asset hash 0 | exactly that, both fixtures |
| P6 `test_level.roster.txt` | unchanged, and the roster test green with no edit | unchanged; green in build5 |
| P7 `drift-*.udearep` | unchanged | unchanged |

How to check the table:
- "Unchanged" means `git diff HEAD --name-only` on the regenerated tree listed only the two moba
  fixtures (`dev251/r2/merged-vs-ours-names.txt`). The two fixtures are its positive control.
- The mtimes (`dev251/r2/rewritten-mtimes.txt`) show each file was rewritten during the run
  rather than left alone.
- The fixture regions are in `dev251/r2/measured-regions.txt`.
- The tool's own lines are in `dev251/r2/fixtures.log`: `REGENERATED - rebuilt because this
  build cannot replay it - assetGraphHash: recorded f3556cc2c7387f60... (32 bytes), this build
  c011c548ff5d5c87... (32 bytes)`, the same for each fixture.
- Every regeneration run has a marker under `dev251/r2/` recording EXIT, START, END, WORKTREE
  and ARGS.

`BRIEF.md` survived the merge as this branch's (its first line was `11c7680d` straight after it).

### The evidence command at `a139f001`

`dev251/evidence5.marker`: `EXIT=0`, `HEAD=a139f001`. The in-XML stamps are 05:31:11 to 05:31:12,
and the run ended at 05:31:17 (`dev251/evidence5-xml.txt`):

    testsuite name="dev.wildware.hollow.FoxWaveTest" tests="4" skipped="0" failures="0" errors="0" timestamp="2026-09-21T05:31:11.824Z"
    testsuite name="dev.wildware.hollow.net.FoxReplicationTest" tests="2" skipped="0" failures="0" errors="0" timestamp="2026-09-21T05:31:12.073Z"
    testsuite name="dev.wildware.hollow.FoxBehaviourTest" tests="7" skipped="0" failures="0" errors="0" timestamp="2026-09-21T05:31:11.613Z"
    testsuite name="dev.wildware.hollow.net.PlayerReplicationTest" tests="7" skipped="0" failures="0" errors="0" timestamp="2026-09-21T05:31:12.748Z"

### The full build at `a139f001`

Marker (`dev251/build5.marker`), verbatim:

    EXIT=0
    START=2026-09-21T05:31:19Z END=2026-09-21T07:02:28Z WORKTREE=/srv/ssd1/workspace/Udea/.claude/worktrees/agent-ab135fb44c31f8480 HEAD=a139f001 ARGS=build --continue --no-configuration-cache --max-workers=3 --rerun-tasks --no-build-cache --no-daemon
    DONE

The last two lines of `dev251/build5.log`:

    BUILD SUCCESSFUL in 1h 31m 7s
    1122 actionable tasks: 1122 executed

- The log has 0 `FROM-CACHE` lines.
- The test XML (`dev251/r2/build5-xml-count.txt`): 831 files, 5866 tests, 47 skipped, 0 failures
  or errors. None is stamped before build5's `START`. As a control, 14 are stamped before 06:30,
  so the check can return a non-zero.
- The 1h31m is the box, not the build. Three full builds ran at once, and memory and I/O pressure
  were high (reported to the lead at the time). Round 1's build4 took 10m32s.
- `AgentHostThreadsTest`, which was red in the reviewer's round-1 build, passed here: `tests="3"
  failures="0"`, stamped 06:55:54.
- As in round 1, there was no `DISPLAY`, so this build is not evidence about GL (section 3).

Sections 1 to 8 below are round 1's brief, unchanged apart from this section. Round 1's build
(build4 in section 3) was at `11c7680d`.

## 1. Evidence command

    ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
      sh gradlew :hollow:game:jvmTest \
        --tests 'dev.wildware.hollow.FoxBehaviourTest' \
        --tests 'dev.wildware.hollow.FoxWaveTest' \
        --tests 'dev.wildware.hollow.net.FoxReplicationTest' \
        --tests 'dev.wildware.hollow.net.PlayerReplicationTest' \
        --no-build-cache --rerun

That is 20 tests. They cover the chase threshold on both sides, the flee threshold on both sides,
waves for one seed and for two, wave growth, the clip following the state, and the server and two
clients agreeing tick for tick.

**Proof it goes red.** Run before the systems existed (`dev251/evidence/red-before-systems.log`):

    14 tests completed, 12 failed
    BUILD FAILED in 39s

Seven mutations of the production code each turn it red (section 7). Each exits 1 and fails
the tests named there.

## 2. Summary

- **`Fox`** is a new `@Replicated` component.
  - `@Net`: `mode` (`Wander | Chase | Flee`), `health` and `target`. These are what a watcher needs.
  - `@Sim`: `decideAt`, `goalX`, `goalY` and `heading`. These are the server's working memory.
  - The fox is the Khronos Fox, CC BY 4.0. The notice is in `hollow/game/assets/models/fox/NOTICE.md`.
  - It is spawned with a dynamic body, a circle, an `Animator` and `Drawn`, so a client draws it
    through #270's `ModelRenderSystem`. Hollow writes no bridge of its own.
- **`FoxBrainSystem`**, in `Movement`, is the state machine. It runs once per tick and reads no clock.
  - **Flee** when health is `<= FLEE_AT` (30) and a player is within `FLEE_RANGE`.
  - **Chase** when the nearest player is within `CHASE_RANGE` (8 m). It keeps chasing out to
    `GIVE_UP_RANGE` (11 m), so a player on the boundary does not make the fox flicker.
  - **Wander** otherwise. It walks to a goal drawn from the `AI` stream every `DECIDE_EVERY` ticks,
    plus a random spread.
- **`FoxPoseSystem`**, in `PostPhysics`, points the model along the heading and picks the clip:
  Survey when nearly still, Walk when wandering, Run when chasing or fleeing, with a cross-fade.
- **`FoxWaveSystem`**, in `PreSimulation`, spawns each wave at the clearing's edge.
  - The bearing comes from the `Wave` stream and each fox's scatter from the `Spawn` stream.
  - A wave is `size + wave * growth` foxes, capped by how many are already alive.
  - Arriving foxes are sent at the clearing's heart for `APPROACH` ticks, then left to the brain.
- **`HollowRelevancy`**, the surprise, needed for the "agree at the same tick" criterion. Without
  it a client's copy of a moving entity was 1-5 ticks older than the tick the client had been told.
  - The cause is in `udea-net`. Its priority accumulator counts ticks since an entity was last
    *sent*, and never-changing scenery is never sent, so the scenery's priority keeps growing.
  - When the scenery's baselines leave the ring, it is re-sent in full and crowds out the entities
    that move.
  - `HollowRelevancy` weights scenery and sunlight at 1/65536 and everything else at 1.
  - Measured with a walking player and the debug hook that was removed before the commit:
    - without it (`dev251/evidence/stall-without-relevancy.log`), 10 of 76 lines show a non-zero
      `playerLag`: told 13 lag 2, told 20 lag 5, told 139 lag 1, told 146 lag 5 and told 272 lag 1,
      each on both clients;
    - with it (`dev251/evidence/stall-with-relevancy.log`), 0 of 78.
  - **The engine-side fix is out of scope and left for the lead.** A cleaner fix would make the
    accumulator count from the last *change*, not the last send.
- The match seed (`HollowServer(matchSeed = ...)`) resets every random stream after the level
  loads, as a barrier action. Otherwise every match would start from the streams the level file
  saved.
- `runFoxShot` is new. It runs one server and two clients in one process and draws either client
  offscreen, one PNG per known tick.

### Decisions (each is a comment on #251)

1. **Waves hold no state.** A wave is a pure function of the tick (`FoxWaves.waveAt`,
   `sizeOf`) plus the two random streams. A rewind or snapshot therefore has nothing extra to
   carry, and the lead agreed this is right. The alternative, a replicated wave counter, is one
   more thing that can drift.
2. **No `sin`/`cos`.** I read `determinism-audit.md`; its section 3.1 found `Math.sin` differing
   from `StrictMath.sin` in the last bit. So a fox steers by the vector to its goal, made unit
   length with `sqrt`, which IEEE-754 specifies exactly. The only trig is one `atan2`, which
   writes `Fox.heading`. That only turns the model, through `Transform3D.rotationZ`, and nothing
   reads the heading back to choose a velocity; the collision shape is a circle. The wave bearing
   is a rejection-sampled unit vector. `dev251/evidence/greps.txt` has the check and its positive
   control (section 8).
3. **The match seed** is described above.
4. **The fox is CC BY 4.0, not CC0**, so Hollow carries its own copy and notice.
   `FoxAssetTest` pins the SHA-256.
5. **`HollowRelevancy`**, as above.

## 3. The build

**The run this brief rests on is build4: a single full execution at `11c7680d`**:

    build --continue --no-configuration-cache --max-workers=4 --rerun-tasks --no-build-cache --no-daemon

Marker (`dev251/build4.marker`), verbatim:

    EXIT=0
    START=2026-09-21T04:45:35Z END=2026-09-21T04:56:08Z WORKTREE=/srv/ssd1/workspace/Udea/.claude/worktrees/agent-ab135fb44c31f8480 HEAD=11c7680d ARGS=build --continue --no-configuration-cache --max-workers=4 --rerun-tasks --no-build-cache --no-daemon
    DONE

The last two lines of `dev251/build4.log`:

    BUILD SUCCESSFUL in 10m 32s
    1122 actionable tasks: 1122 executed

- The log has 0 `FROM-CACHE` lines. Its 97 `UP-TO-DATE` lines are lifecycle tasks with no actions
  (`classes`, `testClasses`, `androidPreBuild` and the like); the summary counts every
  actionable task as executed.
- Every JUnit XML under the worktree's `build/test-results` together holds 5815 tests, 47 skipped
  and 0 failures or errors, in 825 files. Not one in-XML `timestamp` is earlier than build4's
  `START`. As a control, the same check with a cutoff of 04:50:00 finds 158 suites, so it can
  return a non-zero (`dev251/evidence/build4-xml-count.txt`). I did not trace which tests the 47
  skips are.
- The run had no `DISPLAY`, so `udeaGlTest`, `udeaAgentGlTest` and `udeaEditorGlTest` ran as tasks
  under the default `-Pudea.render.requireGl=false`. **This build is not evidence about GL**; see
  the GL paragraph below.

### History, kept because the claims changed along the way

- **build1** (`HEAD` before the merge) did not finish. The Gradle daemon disappeared at 04:36:
  "Gradle build daemon disappeared unexpectedly".
  - Its JVM left `hs_err_pid542761.log` in the worktree root. The record says GradleDaemon 8.13,
    pid 542761, `-Xmx2g`, uptime 10m17s, time 04:36:25 UTC.
  - The JVM's own message: "Native memory allocation (mmap) failed to map 2899968 bytes. Error
    detail: committing reserved memory."
  - The log recorded `SwapFree: 23012 kB`. The lead found no OOM kill in the kernel log.
  - I name no cause.
  - That file was swept into my code commit by an amend. The branch had never been pushed, so I
    rewrote it: `git diff ad3b7631 3fb365a1` is exactly the deletion of that one file. The log is
    kept at `dev251/evidence/hs_err_pid542761.log`.
- **build2** failed only `TestLevelRosterTest`: a ninth generated file I had not predicted
  (section 6). Its summary line: `1122 actionable tasks: 79 executed, 8 from cache, 1035
  up-to-date`.
- **build3** (`dfea859a`) was green: `1122 actionable tasks: 25 executed, 1097 up-to-date`, with
  0 `FROM-CACHE`.
  - I first told the lead that build2 had executed the full suite. **It had not.** Most test tasks
    were executed by build1, which later died.
  - `:hollow:game:jvmTest` was `FROM-CACHE` in build1, restored from my own earlier `g3` run.
  - I closed that gap with a `--rerun --no-build-cache` of `:hollow:game:jvmTest` alone: 48 tests,
    0 failures, in-XML timestamps 04:44:38Z-04:44:40Z against a wall clock of 04:44:50Z
    (`dev251/evidence/hollow-jvmTest-rerun.txt`).
  - The lead then asked for build4, so that one run carries the claim.

**GL.** This ticket does not touch `udea-render`, `udea-agent-host` or `udea-editor`. The whole
diff against `a99acb9f` (`git diff --stat a99acb9f HEAD`) is under `hollow/`, the generated files, `AGENTS.md` and
`net-components.lock`. So the xvfb `udeaGlTest` suites were not run. The fox shots *were* taken in a
real GL context, under `xvfb-run` with llvmpipe (`dev251/shot.sh`), and they are the rendering
evidence.

## 4. Images

In `/srv/ssd1/workspace/Udea/build/debug-screenshots/`:

- `issue251-wave-closing-in-client0.png`: client 0's frames 00, 03, 06, 09, 12, 15 and 18, which
  are 60 ticks apart (server ticks 86 to 446). The wave comes out of the trees and closes on the
  two players. This proves "screenshots of a wave closing in".
- `issue251-wave-closing-in-client1.png`: the same frames from client 1, whose camera is turned 45
  degrees from client 0's.
- `issue251-two-clients-same-ticks.png`: frames 00, 10 and 20 (server ticks 86, 286 and 486), with
  client 0 on the left and client 1 on the right. The foxes are in the same places in each pair.
  This proves "the screenshots show two clients".
- `issue251-client0-foxes-running-in.png`: client 0's frame 10, byte-identical to
  `dev251/shots-shot0/foxes-client0-10.png`. At server tick 286, four foxes are in `Chase` and
  running and one is still in `Wander` (transcript line `F10`).
- `issue251-client1-foxes-running-in.png`: the same tick from client 1
  (`dev251/shots-shot1/foxes-client1-10.png`).

The two shot runs are separate OS processes. Each prints every fox's `NetId`, mode and position at
every frame. `dev251/t0.txt` and `dev251/t1.txt`, cut from `shot0.log` and `shot1.log`, compare
`IDENTICAL` under `cmp`: 21 frames each, server ticks 86 to 486, going from `Wander` through
`Chase` to standing at the players' heels.

## 5. The issue, criterion by criterion

| Criterion | Proof |
|---|---|
| Headless, a fox in range chases and one out of range wanders, seen red on both sides of the threshold | `FoxBehaviourTest` "a tenth of a metre inside chase range runs at the player" (7.9 m) and "a tenth of a metre outside ... wanders" (8.1 m, checked every tick). M1 turns only the outside test red and M2 only the inside test |
| Flee at low health | "a fox at the flee threshold runs away" (30) and "one point above the flee threshold chases instead" (31). M3 turns the first red |
| The Animator follows the state (Survey, Walk, Run) | "a walking fox plays the walk", plus Run asserted in the chase and flee tests. M6 turns the two Run assertions red |
| Waves grow | "each wave is bigger than the one before, and arrives at the clearing's edge". M7 turns it red |
| Waves deterministic for a seed, with two seeds that differ | "the same seed sends the same foxes to the same places" and "a different seed sends its foxes somewhere else" (seeds 251 and 252). M4 turns only the second red |
| Screenshots of a wave closing in | `issue251-wave-closing-in-client0.png` and `-client1.png` |
| A headless server and two clients in process agree on replicated state at the same tick | `FoxReplicationTest` "every fox is in the same place and the same state on the server and on both clients, tick for tick". It compares each client world with the server's history at `client.serverTick`, needs both chasing and wandering foxes, and needs at least 40 observations. M5 turns it red at tick 272 |
| The screenshots show two clients | `issue251-two-clients-same-ticks.png`, plus the `cmp`-identical transcripts |
| Trig: read `determinism-audit.md` and record the decision | Decision 2 above, the #251 comment, and `evidence/greps.txt` |

## 6. Regenerated files

**The prediction was frozen and sent to the lead before anything was generated:** Fox takes
id 0; `hollow.Player` moves 0 to 1; moba 1..16 moves to 2..17; codegen 17..22 to 18..23; core
23..31 to 24..32; nav +1. The measured `net-components.lock` diff (`dev251/evidence/lock-diff.txt`)
matches it line for line.

| File | What moved |
|---|---|
| `net-components.lock` | `dev.wildware.hollow.Fox` added. Every other name is one position later |
| `hollow/game/net-protocol.lock` | protoHash `0xc16a` to `0x6214`; Fox is component 0 with fields 0-6, Player moves 0 to 1 |
| `moba/game/net-protocol.lock` | protoHash `0xcb41` to `0x6874`; ids 1..16 to 2..17 |
| `udea-codegen/net-protocol.lock` | protoHash `0x358d` to `0x1277`; ids 17..22 to 18..23 |
| `udea-core/net-protocol.lock` | protoHash `0x0c1d` to `0x1e1e`; ids 23..31 to 24..32 |
| `udea-codegen/src/test/resources/expected-generated-hashes.txt` | 7 entries |
| `moba/desktop/.../fixtures/moba-3600.udearep` | 59572 bytes before and after. protoHash 2 bytes, other header 0, **frames 0**, per-tick hashes 28713, crc 4 |
| `moba/desktop/.../fixtures/moba-36000.udearep` | 590901 bytes before and after. protoHash 2, other header 0, **frames 0**, hashes 287018, crc 4 |
| `moba/desktop/.../levels/test_level.roster.txt` | line 1 only: `worldHash=fd265e081128cdd2` to `833620a7f4078d63` |

The fixture breakdown is in `dev251/evidence/fixture-regions.txt`. **No recorded input moved.**
The frames region is 0 bytes different in both fixtures. The hashes moved because `WorldHasher`
folds each component's type id.

**`test_level.roster.txt` was the file I did not predict.** It has no writer task. I took the
test's own reported actual from its JUnit XML (`dev251/roster.py`, output
`dev251/evidence/roster-diff.txt`):

    lines expected=30 actual=30
    line 1 differs:
      golden: worldHash=fd265e081128cdd2
      now:    worldHash=833620a7f4078d63

Every `netId`, position and component line is byte-identical. The id shift
moved the hash and nothing else.

**Checked by name and not moved:** `udea-replay`'s `drift-3600.udearep` and
`drift-36000.udearep`. Both are tracked (`git ls-files '*drift-*'` lists them). One command asks
about both fixture directories, so the moba pair is its positive control
(`dev251/evidence/unmoved.txt`):

    $ git diff --name-only a99acb9f HEAD -- udea-replay/src/jvmTestFixtures/resources/fixtures moba/desktop/src/test/resources/fixtures
    moba/desktop/src/test/resources/fixtures/moba-3600.udearep
    moba/desktop/src/test/resources/fixtures/moba-36000.udearep

The committed roster file and `dev251/evidence/roster-actual.txt` compare identical under `cmp`. `udea-nav` has no
`net-protocol.lock`, so its ids exist only as positions in `net-components.lock`.

**Merge rule (the lead's):** whichever of this branch and #271 merges second regenerates all nine
files on the merged tree. None of them is resolved as text.

## 7. Mutation table

The predictions were frozen in `dev251/mutations-predicted.txt` before any mutation ran, at
`bf471b7`. The production code there is identical to `3fb365a1`, which differs only in the roster
golden and the removed crash log. The runner is `dev251/mutate.py`. Each row ran the evidence
command's four classes with `--no-build-cache` after deleting the result XML. The results are in
`dev251/mutations/M*.result`.

| Row | Predicted red | Measured red (all exit=1) |
|---|---|---|
| M1 chase at 8.2 | outside-range wander only | outside-range wander only |
| M2 chase at 7.8 | inside-range chase only | inside-range chase only |
| M3 flee `<` 30 | flee-threshold only | flee-threshold only |
| M4 seed ignored | different-seed only | different-seed only |
| M5 no relevancy | FoxReplication tick-for-tick; Player walking | FoxReplication tick-for-tick (tick 272); Player walking (tick 142) |
| M6 clip ignores mode | the Run assertions in the chase and flee tests | those two |
| M7 waves do not grow | same-seed and bigger-wave | same-seed, bigger-wave **and** "no wave arrives on a tick the schedule does not name" |

Two misses against the frozen text:
- **M6:** the prediction says "the three clip assertions" and then names two. Two went red.
- **M7:** I did not predict that `waveAt`'s test also asserts `sizeOf`, so it went red with
  `expected: <5> but was: <3>`. That is a correct red for that mutation.

The diffs, as taken from each run:

`M1-chase-range-out` (`dev251/mutations/M1-chase-range-out.diff`):

```diff
diff --git a/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxBrain.kt b/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxBrain.kt
index 92bddb60..5cf4a05e 100644
--- a/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxBrain.kt
+++ b/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxBrain.kt
@@ -177,7 +177,7 @@ internal class FoxBrainSystem : SimSystem() {
         if (fox.health <= FoxBrain.FLEE_AT) {
             return if (squared <= FLEE_SQUARED) FoxMode.Flee else FoxMode.Wander
         }
-        if (squared <= CHASE_SQUARED) return FoxMode.Chase
+        if (squared <= (FoxBrain.CHASE_RANGE + 0.2f) * (FoxBrain.CHASE_RANGE + 0.2f)) return FoxMode.Chase
         if (fox.mode == FoxMode.Chase && squared <= GIVE_UP_SQUARED) return FoxMode.Chase
         return FoxMode.Wander
     }
```

`M2-chase-range-in` (`dev251/mutations/M2-chase-range-in.diff`):

```diff
diff --git a/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxBrain.kt b/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxBrain.kt
index 92bddb60..2a39ad57 100644
--- a/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxBrain.kt
+++ b/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxBrain.kt
@@ -177,7 +177,7 @@ internal class FoxBrainSystem : SimSystem() {
         if (fox.health <= FoxBrain.FLEE_AT) {
             return if (squared <= FLEE_SQUARED) FoxMode.Flee else FoxMode.Wander
         }
-        if (squared <= CHASE_SQUARED) return FoxMode.Chase
+        if (squared <= (FoxBrain.CHASE_RANGE - 0.2f) * (FoxBrain.CHASE_RANGE - 0.2f)) return FoxMode.Chase
         if (fox.mode == FoxMode.Chase && squared <= GIVE_UP_SQUARED) return FoxMode.Chase
         return FoxMode.Wander
     }
```

`M3-flee-strict` (`dev251/mutations/M3-flee-strict.diff`):

```diff
diff --git a/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxBrain.kt b/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxBrain.kt
index 92bddb60..0889315f 100644
--- a/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxBrain.kt
+++ b/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxBrain.kt
@@ -174,7 +174,7 @@ internal class FoxBrainSystem : SimSystem() {
     private fun modeFor(fox: Fox): FoxMode {
         if (nearest.id == NetId.NONE) return FoxMode.Wander
         val squared = nearest.distanceSquared
-        if (fox.health <= FoxBrain.FLEE_AT) {
+        if (fox.health < FoxBrain.FLEE_AT) {
             return if (squared <= FLEE_SQUARED) FoxMode.Flee else FoxMode.Wander
         }
         if (squared <= CHASE_SQUARED) return FoxMode.Chase
```

`M4-seed-ignored` (`dev251/mutations/M4-seed-ignored.diff`):

```diff
diff --git a/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/HollowGame.kt b/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/HollowGame.kt
index ac0b5acd..d11a4009 100644
--- a/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/HollowGame.kt
+++ b/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/HollowGame.kt
@@ -183,7 +183,7 @@ private class ReseedStreams(private val seed: Long) : BarrierAction {
         val streams = checkNotNull(ctx.rng as? CapturableRng) {
             "a new match reseeds the random streams, and ${ctx.rng::class.simpleName} does not implement CapturableRng"
         }
-        streams.restoreFrom(DefaultRngService(seed).saveState(), 0)
+        streams.restoreFrom(DefaultRngService(0L).saveState(), 0)
     }
 
     override fun toString(): String = "ReseedStreams($seed)"
```

`M5-no-relevancy` (`dev251/mutations/M5-no-relevancy.diff`):

```diff
diff --git a/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/net/HollowServer.kt b/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/net/HollowServer.kt
index 62d41582..b6ba648c 100644
--- a/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/net/HollowServer.kt
+++ b/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/net/HollowServer.kt
@@ -107,7 +107,6 @@ public class HollowServer(
         budget = budget,
         // The clearing's dressing behind everything that moves: see `HollowRelevancy` for the
         // stall it prevents (issue #251).
-        relevancy = HollowRelevancy(host.world, host.ctx[CoreModule.NET_IDS]),
         mtu = mtu,
     )
 
```

`M6-clip-ignores-mode` (`dev251/mutations/M6-clip-ignores-mode.diff`):

```diff
diff --git a/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxBrain.kt b/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxBrain.kt
index 92bddb60..36de9e88 100644
--- a/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxBrain.kt
+++ b/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxBrain.kt
@@ -311,7 +311,7 @@ internal class FoxPoseSystem : SimSystem() {
     /** The clip a fox in [mode] moving at [speed] plays. */
     private fun clipFor(mode: FoxMode, speed: Float): AnimationClip = when {
         speed < FoxBrain.IDLE_BELOW -> FoxModel.Clips.Survey
-        mode == FoxMode.Wander -> FoxModel.Clips.Walk
+        true -> FoxModel.Clips.Walk
         else -> FoxModel.Clips.Run
     }
 
```

`M7-waves-do-not-grow` (`dev251/mutations/M7-waves-do-not-grow.diff`):

```diff
diff --git a/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxWaves.kt b/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxWaves.kt
index 1fc55ff7..4a7957d1 100644
--- a/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxWaves.kt
+++ b/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxWaves.kt
@@ -49,7 +49,7 @@ public data class FoxWaves(
     }
 
     /** How many foxes wave [wave] brings, before [cap] is applied. */
-    internal fun sizeOf(wave: Long): Int = (size + wave * growth).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
+    internal fun sizeOf(wave: Long): Int = (size + wave * 0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
 
     public companion object {
 
```

## 8. Absence claims with their controls

From `dev251/evidence/greps.txt`:

    $ git grep -nE '\b(sin|cos|tan)\(' -- hollow/game/src/commonMain/kotlin/dev/wildware/hollow/Fox.kt hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxBrain.kt hollow/game/src/commonMain/kotlin/dev/wildware/hollow/FoxWaves.kt
    exit=1
    $ git grep -cE '\b(sin|cos|tan)\(' -- hollow/desktop/src/test/kotlin/dev/wildware/hollow/desktop/ClearingLayout.kt
    hollow/desktop/src/test/kotlin/dev/wildware/hollow/desktop/ClearingLayout.kt:3
    exit=0
    $ git grep -nE 'nanoTime|currentTimeMillis|Instant\.now|Math\.random|Random\.Default|kotlin\.random' -- hollow/game/src/commonMain
    exit=1
    $ git grep -cE 'nanoTime|currentTimeMillis|Instant\.now|Math\.random|Random\.Default|kotlin\.random' -- hollow/desktop/src
    hollow/desktop/src/main/kotlin/dev/wildware/hollow/desktop/HollowServerMain.kt:2
    hollow/desktop/src/test/kotlin/dev/wildware/hollow/desktop/ClearingLayout.kt:1
    exit=0

The first grep does not say there is no trig at all: `atan2` is in `FoxBrain.kt`, by design
(decision 2).
