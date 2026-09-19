46b083d

# BRIEF-196: Play, Stop, Step and Play standalone in the editor (round 2)

The line above is the SHA of the code every result in this file was produced on, unless a line says otherwise. The commit that adds this file sits on top of it and changes nothing else. Branch `issue-196-play-stop-step`, cut from `origin/kmp` at `5821d25`, with `origin/kmp` merged in twice: at `48554d4` (#188, the HUD on ComposeGL) and at `1e6c08b` (udea-physics2d). Both merges applied with no conflicts.

The scripts named below (`muts.sh`, `fullbuild.sh`, `gl2.sh`, `extract.sh`) and every log and report quoted here are in `/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue196/`. Every output and diff block below was spliced out of those files by `splice.py`, with no typing. Only the command lines were typed.

## Round 2: what changed

Round 1 failed on two findings. Both are fixed, and each fix has a test that I saw fail first.

**Finding 1: an undone pre-Play delete put the delete's component objects into the running world.** The fix is a refusal (`undo_before_play`) plus a reclaim.

- `editor.undo` now refuses any edit made before Play for as long as a play is under way. The error is the typed `undo_before_play` (`EditorToolset.undo`, checked with `HistoryMark.predates`). It tells the caller to call `editor.stop` first.
- `editor.stop` step 1 now calls the new `NetIdIndex.reclaim` for every delete in the Play mark, straight after `loadNow`. The level Stop loads recorded that delete's id as a free index one generation on. Reclaiming takes it back out of the free queue as the same outstanding reservation, so the undo after Stop brings the unit back under its old id with its pre-delete values.
- **I rejected a deep copy of the Delete's `Snapshot` at `mark()`.** Fleks components are arbitrary objects, and nothing in the engine clones them. A clone would need a new per-entity encoding, which is the "separate snapshot codec" on the do-not list. Commented on #196: https://github.com/wildware-uk/Udea/issues/196#issuecomment-5739778868
- **Round 1 also had a second defect the reviewer did not name.** The reviewer's repro does not show play-time values on round-1 code. It fails earlier: `entity_gone` at the final undo, because Stop's reload dropped the delete's reservation (the red run below, `r2-red`). `reclaim` fixes that. With `reclaim` in place and the refusal removed (R1), the reviewer's exact symptom appears.

**Finding 2: Stop left rewind-ring frames newer than the clock.** It is fixed in `LevelService.apply`, which now calls the new `TimeTravel.forgetAfter(document.tick)` right after `ctx.clock.moveTo`. `SnapshotTimeTravel` implements it as `ring.dropAfter(tick)`.

- **Why the load and not Stop:** every level load moves the clock, and any frame newer than the loaded tick is from a timeline that is gone. A frame like that breaks `time.rewind` and makes the next capture throw from `Simulation.step`. `editor.load` meets that exactly as Stop does.
- **Kept:** frames at or before the loaded tick. For Stop they are the restored world's own history. For a load of an unrelated level they are still foreign; that was already so before this ticket and is not made worse here.
- Commented on #196: https://github.com/wildware-uk/Udea/issues/196#issuecomment-5739778909

**New tests, in `MobaEditorPlayTest`, the evidence class.**

- `a delete from before play comes back after stop exactly as it was deleted` is the reviewer's repro: delete, play, undo (asserted refused with `undo_before_play`), ticks, stop, undo. The whole-world hash must equal the hash from before the delete.
- `steps after stopping a long play run, and the ring holds nothing newer than the tick stop returned to` is play for 1200 frames, stop, step three times. The tick must be the start tick + 3, and the ring's newest frame must be no later than that.
- `a rewind during a second play lands in that play and not in the one before it` is play for 60 frames, stop, play, `editor.set_field`, step 30, mark the hash, step 15, `time.rewind` 15. The hash must equal the mark. The first play is 60 frames, inside the ring's reach, so that without the fix the test fails because the rewind lands in the discarded play (R3), rather than because of the capture crash.

**New tests, in `NetIdReservationTest`, for `reclaim` itself.**

- One test covers a detached id recorded as free by a save being reclaimed as the same reservation with the free queue in its saved order (R4).
- One test covers reclaiming a live id, or one whose index was handed out again, changing nothing (R5).

## Evidence command

```
sh gradlew :moba:desktop:editorTest --tests '*MobaEditorPlayTest*' --rerun
```

`MobaEditorPlayTest` presses the toolbar's buttons in the real editor window (ComposeGL `uiTest`, headless), over a real `moba` world wired exactly as `runEditor` wires it. Its first test is the issue's first criterion:

1. Play.
2. 1200 ticks of the lane fighting. The test asserts that something took damage or died.
3. Stop.
4. `WorldHasher.hash` over a whole-world capture (fields, tick, random streams, id allocator) must equal the hash from before Play.

Green at the SHA above:

```
> Task :moba:desktop:editorTest
BUILD SUCCESSFUL in 16s
```

The test cases in its XML report:

```
testcase name="play, a fight, then stop puts back the world hash from before play()"
testcase name="a delete from before play comes back after stop exactly as it was deleted()"
testcase name="an edit session left open at stop is cancelled()"
testcase name="step advances the tick counter by exactly one()"
testcase name="play standalone saves the level and hands the launcher a file that loads as this world()"
testcase name="an edit made during play is thrown away at stop, with its undo entry, and one made before stays()"
testcase name="a rewind during a second play lands in that play and not in the one before it()"
testcase name="steps after stopping a long play run, and the ring holds nothing newer than the tick stop returned to()"
tests="8" skipped="0" failures="0" errors="0"
```

**It goes red when Stop restores from the live map instead of bytes.** Mutation M1 keeps Fleks' `world.snapshot()` map at Play and loads it at Stop, after the bytes. The clock, random streams and ids are still right; only the component objects are the live ones. Round 1's M1 no longer applies to the round-2 Stop, so I made the same three insertions again and took this diff from the tree. It was then applied, run with the command above and reverted with `git apply -R` at the SHA above, as the first row of the mutation table below:

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 4d7a923..f80c5ec 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -160,6 +160,7 @@ public class EditorToolset(
 
     /** The play under way, or `null` while editing. */
     private var playing: PlaySession? = null
+    private var liveMap: Map<Entity, com.github.quillraven.fleks.Snapshot> = emptyMap()
 
     /** Every call that changed the world or an edit session, in order. */
     public val journal: EditorJournal = EditorJournal()
@@ -717,6 +718,7 @@ public class EditorToolset(
         forEachOpenEdit(::commit)
         val session = PlaySession(level, clock.tick, history.mark())
         playing = session
+        liveMap = world.snapshot()
         play.time.resume()
         bridge.event("editor_play:${session.tick.value}", clock.tick.value)
         AgentResult.ok {
@@ -752,6 +754,7 @@ public class EditorToolset(
         // records the id a pre-play delete is holding for its undo as free; take each back, so
         // that undo still brings the entity back under the same id.
         play.levels.loadNow(level)
+        world.loadSnapshot(liveMap)
         for (delete in running.history.deletes()) netIds.reclaim(delete.netId)
         playing = null
         // 2. What becomes of the edits made while playing.
```

```
MobaEditorPlayTest > play, a fight, then stop puts back the world hash from before play() FAILED
MobaEditorPlayTest > a delete from before play comes back after stop exactly as it was deleted() FAILED
MobaEditorPlayTest > an edit made during play is thrown away at stop, with its undo entry, and one made before stays() FAILED
MobaEditorPlayTest > a rewind during a second play lands in that play and not in the one before it() FAILED
MobaEditorPlayTest > steps after stopping a long play run, and the ring holds nothing newer than the tick stop returned to() FAILED
8 tests completed, 5 failed
BUILD FAILED in 6s
message="org.opentest4j.AssertionFailedError: the world after Stop is not the world from before Play ==&gt; expected: &lt;-2223924700062390788&gt; but was: &lt;3996789981255548409&gt;"
message="org.opentest4j.AssertionFailedError: the unit deleted before Play came back after Stop with values it did not have ==&gt; expected: &lt;-2223924700062390788&gt; but was: &lt;7492136214640677969&gt;"
message="org.opentest4j.AssertionFailedError: Stop did not put back the world the pre-play spawn left ==&gt; expected: &lt;5601703085113449964&gt; but was: &lt;3173718925151124592&gt;"
message="org.opentest4j.AssertionFailedError: time.step failed: Failed(tool_threw: time.step threw IllegalArgumentException after the tick: effect handles must be applied in ascending order to keep the list searchable; EffectHandle#23 is not above EffectHandle#45). Expected value to be of type &lt;dev.wildware.udea.agent.AgentResult.Ok&gt;, actual &lt;class dev.wildware.udea.agent.AgentResult$Fail
message="org.opentest4j.AssertionFailedError: three Steps after Stop ==&gt; expected: &lt;t4&gt; but was: &lt;t3&gt;"
```

## Summary

**What Play, Stop and Step are.** This is unchanged from round 1 apart from the two fixes above.

- **`editor.play`** encodes the world with `LevelService.saveNow()` into a `ByteArray` held in memory. It then takes a `HistoryMark` of every author's undo history, commits every open edit session (after the save, so a refused Play leaves them open) and resumes the loop. Pressed again during a play, it only resumes.
- **`editor.stop`** decodes the bytes with `LevelService.read`, pauses, and cancels every open edit session. Then it runs two steps:
  1. `LevelService.loadNow(level)`, and `reclaim` for each pre-Play delete.
  2. `afterRestore(...)`, which puts every author's history back to the mark and answers the play-time edits it dropped. This is the one place #238's Keep would re-apply kept edits.
- **Step** is the existing `time.step` with `ticks=1`.
- **The toolbar** (`udea-editor`, new files `PlaybackToolbar.kt` and `PlayControls.kt`) is Play, Stop, Step, Play standalone and a status line. Each button is a tool call through `EditorTools`. The hooks in existing files are one constructor parameter and one property on `EditorSession`, and one line in `EditorWindow`.

**Why `loadNow` and not `LevelScene`.** `LevelScene` deliberately does not restore the clock or the random streams, so a Stop through it could not match the pre-Play hash. `LevelService.load` queues its `apply` on the barrier, but a tool already runs inside a drain. `loadNow` calls that same `apply` directly, the same split as `saveNow`/`save`.

**Round-1 decisions, each commented on #196:**

- Play and Stop are agent tools.
- Stop puts back each author's whole history from Play.
- Play commits open edit sessions and Stop cancels them.
- Play standalone saves to `play-standalone.udealevel` in the editor's level directory.
- Play standalone starts `MobaAgent` in a new JVM on the editor's classpath with `-Dmoba.level=<file>` and `-Dudea.render.mode=Windowed`, rather than a nested `sh gradlew :moba:desktop:run -Plevel=...`. A nested call means a second Gradle client and daemon, plus a configuration pass, before a window appears.
- The toolbar keeps no copy of the play state.

**Things found, not fixed here:**

- **The score bar and camera lag after Stop.** `moba`'s score bar and the follow camera still show where play left them until the next tick. That was observed live at `ebdad11`, before #188. #188 moved the HUD onto ComposeGL, but `MobaHudModel` still copies the scoreboard out of the `MatchService` mirror (`moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaHud.kt`, "Copies the scoreboard out of [MatchService]"), which `MatchSystem` refreshes once per tick. So I expect the same lag, but I have not looked again live. The lead ruled it out of scope.
- **Pre-Play edits cannot be undone during a play.** That follows from the finding-1 decision; see its #196 comment for how to lift it.

## `sh gradlew build`

The command was `ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue` at the SHA above. At the lead's instruction it ran while dev-195's builds were also running on the box. Load when it started:

```
 06:08:07 up 4 days,  5:19,  1 user,  load average: 7.46, 14.81, 14.33
```

Tail of the log:

```
BUILD SUCCESSFUL in 27s
949 actionable tasks: 35 executed, 3 from cache, 911 up-to-date
Configuration cache entry reused.
```

**This is the second full build of round 2.**

- **The first one went red.** It ran at `fe2fe7e` (the merge before the last commit), and two tasks failed: `:udea-core:compileTestKotlinIosArm64` and `:udea-core:compileTestKotlinIosSimulatorArm64`. Both failed on `Name contains illegal characters: ","`, from two new `NetIdReservationTest` names that had commas in them; Kotlin/Native refuses commas in names. My own mistake. No other task failed in that run.
- **The fix is `46b083d`,** which renames both tests. This run is at `46b083d`, on the same Gradle state. Most tasks are up-to-date because the red run had already executed them against identical inputs. `:udea-core:jvmTest` and both iOS test compiles ran again here; `:moba:desktop:editorTest` came from the cache.
- **The red run's log is not kept.** `fullbuild.sh` overwrote it with this run's. What it said is written above from my reading of it at the time.
- **What the task count does not tell you.** This run reports 949 actionable tasks and the red run reported 957. Round 1's baseline at `5821d25` was 928, but both merges since then added projects and tasks, so that comparison does not hold. I have not explained the gap between 949 and 957.
- **A load-sensitive test would have shown up in the tail.** No latency budget, `UdpTwoProcessTest` or `HeadlessHostTest` failed in either run, so none needed a solo re-run.

GL: this ticket touches no `udea-render` code, but I ran the GL suites for real at the SHA above:

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true
```

```
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest
BUILD SUCCESSFUL in 1m 14s
```

Suites in the XML reports afterwards:

```
testsuite name="dev.wildware.udea.render.gl.GlCaptureDeterminismTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.GlCaptureTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.GlCapturedUiTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.GlImportedModelRenderTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.GlKoolInputTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.GlKoolKeyTableTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.GlKoolPointerTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.GlModelRenderTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.GlOverlayIsolationTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.GlUiLayerTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.GlWorldViewTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.KoolThreadShutdownTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.OffscreenBackendExplodingCaptureTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.OffscreenBackendSecondCreateTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.OffscreenBackendShutdownTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.OffscreenBackendTest" tests="2" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.agent.host.gl.OffscreenRenderToolsTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.agent.host.gl.OverlayCaptureIsolationTest" tests="1" skipped="0" failures="0" errors="0"
```

## Images

All the images are in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`. They are from round 1's live session at `ebdad11`, and round 2 does not change what they show:

- The session ran `sh gradlew :moba:desktop:runEditor -PdebugPort=7851` on a private Xvfb display (1600x900, llvmpipe).
- Buttons were pressed with real X mouse clicks.
- The display was grabbed after each press, and `/health` was read each time.

The shots:

- `issue196-01-editor-paused-tick1.png` - the editor as it opens: the toolbar under the menu bar, paused at tick 1.
- `issue196-02-playing-tick719.png` - after Play: running at tick 719, the lane mid-fight.
- `issue196-03-stopped-back-at-tick1.png` - after Stop: paused at tick 1, units back in their starting ranks, the elite at 500/500. The score bar is lagging, as noted above.
- `issue196-04-step-to-tick2.png` - after Step: paused at tick 2.
- `issue196-05-skeleton-spawned-before-standalone.png` - an extra skeleton spawned beside the player before Play standalone.
- `issue196-06-standalone-game-undead11.png` - Play standalone: a separate game window with **UNDEAD 11**. The bundled level has 10, so the window loaded the saved level.
- `issue196-sequence.png` - the six above, tiled in order.

## The issue, criterion by criterion

1. **Play, run N ticks of fighting, Stop: `WorldHasher.hash` equals the hash from before Play, and the test fails if Stop restores from the live map instead of bytes.** `play, a fight, then stop puts back the world hash from before play` (N = 1200) proves it. It is red under M1 above.
2. **Step advances the tick counter by exactly one.** `step advances the tick counter by exactly one` proves it. Round 1's M4 (Step as `time.resume`) turned it red, at `ebdad11`; the diff and its output are in round 1's brief, `BRIEF-196.md` at `ffd97b7`. Round 2 adds `steps after stopping a long play run...` (tick = start + 3 after a 1200-tick play), red under R3. Seen live in `issue196-04`.
3. **Play standalone starts a separate game process loading the saved level, with a screenshot.** `issue196-06-standalone-game-undead11.png` shows it, and so does `play standalone saves the level and hands the launcher a file that loads as this world`. Round 1's brief has the `/proc` evidence.

The review's required tests:

- "play >120 ticks, stop, step x3" is `steps after stopping a long play run...`.
- "play, stop, play, rewind" is `a rewind during a second play lands in that play...`.
- The finding-1 repro is `a delete from before play comes back after stop exactly as it was deleted`.

**Seen red before the fixes existed.** These are the three new tests, run with the round-2 production changes absent (`r2-red`, before `a9e67e0`):

```
MobaEditorPlayTest > a delete from before play comes back after stop exactly as it was deleted() FAILED
MobaEditorPlayTest > a rewind during a second play lands in that play and not in the one before it() FAILED
MobaEditorPlayTest > steps after stopping a long play run, and the ring holds nothing newer than the tick stop returned to() FAILED
8 tests completed, 3 failed
BUILD FAILED in 1m 1s
message="org.opentest4j.AssertionFailedError: editor.undo failed: Failed(entity_gone: cannot undo your editor.delete: entity #1@0 is no longer in the world as it was. Call editor.undo with overwrite=true to discard this edit from your history without changing anything.). Expected value to be of type &lt;dev.wildware.udea.agent.AgentResult.Ok&gt;, actual &lt;class dev.wildware.udea.agent.AgentResul
message="java.lang.IllegalArgumentException: snapshot t3 is not newer than the ring's newest, t1206"
message="org.opentest4j.AssertionFailedError: the ring still holds t1206, from the play Stop discarded"
```

In that run the rewind test failed on the capture crash, because its first play was then 1200 frames. I then shortened that play to 60 frames, so that the test fails on the landing itself. R3 below shows it red that way.

### Round-2 mutation table

Each diff is the `git diff` of the mutation, taken from the tree. Every row, M1 included, was run at the SHA above with `--rerun` by one script (`muts.sh`): apply the diff, run the tests, `git apply -R`. The script ended with `git status` clean and 8/8 green.

**R1** - no refusal of a pre-Play undo during play (reclaim kept):

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 4d7a923..f0bdcd6 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -598,18 +598,6 @@ public class EditorToolset(
         val author = context.command.session
         val edit = history.newest(author)
         val running = playing
-        if (edit != null && running != null && running.history.predates(edit)) {
-            // Refused rather than done: an undone delete hands the world the very component objects
-            // the history holds, the running game would change them, and after Stop the history
-            // would hold play-time values for an edit that belongs to the world Stop puts back.
-            // Stop restores the world as it was, this edit included, so nothing is lost by waiting.
-            return@journaled AgentResult.failed(
-                UNDO_BEFORE_PLAY,
-                "refused to undo your ${edit.tool}: it was made before editor.play, and a play " +
-                    "keeps the edits from before it. Call editor.stop first; this edit is still " +
-                    "yours to undo after it.",
-            )
-        }
         when (edit) {
             null -> AgentResult.failed(
                 NOTHING_TO_UNDO,
```

```
MobaEditorPlayTest > a delete from before play comes back after stop exactly as it was deleted() FAILED
8 tests completed, 1 failed
BUILD FAILED in 6s
message="org.opentest4j.AssertionFailedError: the unit deleted before Play came back after Stop with values it did not have ==&gt; expected: &lt;-2223924700062390788&gt; but was: &lt;-5293460981705832498&gt;"
```

This is the reviewer's symptom: the unit comes back with play-time values.

**R2** - Stop does not reclaim pre-Play deletes:

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 4d7a923..a7fa9f3 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -752,7 +752,6 @@ public class EditorToolset(
         // records the id a pre-play delete is holding for its undo as free; take each back, so
         // that undo still brings the entity back under the same id.
         play.levels.loadNow(level)
-        for (delete in running.history.deletes()) netIds.reclaim(delete.netId)
         playing = null
         // 2. What becomes of the edits made while playing.
         val discarded = afterRestore(running)
```

```
MobaEditorPlayTest > a delete from before play comes back after stop exactly as it was deleted() FAILED
8 tests completed, 1 failed
BUILD FAILED in 6s
message="org.opentest4j.AssertionFailedError: editor.undo failed: Failed(entity_gone: cannot undo your editor.delete: entity #1@0 is no longer in the world as it was. Call editor.undo with overwrite=true to discard this edit from your history without changing anything.). Expected value to be of type &lt;dev.wildware.udea.agent.AgentResult.Ok&gt;, actual &lt;class dev.wildware.udea.agent.AgentResul
```

**R3** - a level load does not forget ring frames after its tick:

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/level/LevelService.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/level/LevelService.kt
index 05efaa0..1fc3999 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/level/LevelService.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/level/LevelService.kt
@@ -189,7 +189,6 @@ public class LevelService internal constructor(
         // a rewind could land in one, and the first capture after the load, older than the
         // ring's newest, would be refused from inside `Simulation.step`. Frames at or before the
         // tick stay: for Stop they are the history of the very world just put back.
-        travel?.forgetAfter(document.tick)
         streams.restoreFrom(document.rng, 0)
         // Box2D bodies are never level content, exactly as they are never snapshot content: a
         // body's handle is `@Transient` and the bodies come back from `PhysicsBody` and the shape
```

```
MobaEditorPlayTest > a rewind during a second play lands in that play and not in the one before it() FAILED
MobaEditorPlayTest > steps after stopping a long play run, and the ring holds nothing newer than the tick stop returned to() FAILED
8 tests completed, 2 failed
BUILD FAILED in 6s
message="org.opentest4j.AssertionFailedError: the rewind landed in a world other than this play's ==&gt; expected: &lt;-6248557912160552136&gt; but was: &lt;1098188624191769084&gt;"
message="org.opentest4j.AssertionFailedError: the ring still holds t1206, from the play Stop discarded"
```

**R4** - `reclaim` leaves the index in the free queue. Run with `sh gradlew :udea-core:jvmTest --tests '*NetIdReservationTest*' --rerun`:

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/identity/NetIdIndex.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/identity/NetIdIndex.kt
index 0129f28..b3ce3b4 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/identity/NetIdIndex.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/identity/NetIdIndex.kt
@@ -223,7 +223,6 @@ public class NetIdIndex(
         val index = netId.index
         if (index >= capacity || liveFlags[index]) return false
         if (generations[index] != ((netId.generation + 1) and NetId.GENERATION_MASK)) return false
-        if (!removeFromFreeQueue(index)) return false
         generations[index] = netId.generation
         liveFlags[index] = true
         entities[index] = null
```

```
NetIdReservationTest[jvm] > a detached id recorded as free by a save is reclaimed as the same reservation with the free queue intact()[jvm] FAILED
13 tests completed, 1 failed
BUILD FAILED in 4s
message="org.opentest4j.AssertionFailedError: expected: &lt;HandleState(free=3, nextFresh=5, highWater=5)&gt; but was: &lt;HandleState(free=4, nextFresh=5, highWater=5)&gt;"
```

**R5** - `reclaim` with its guards removed: it trusts its caller, as a first version without checks would. Same command:

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/identity/NetIdIndex.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/identity/NetIdIndex.kt
index 0129f28..bdb9f4c 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/identity/NetIdIndex.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/identity/NetIdIndex.kt
@@ -221,9 +221,8 @@ public class NetIdIndex(
     public fun reclaim(netId: NetId): Boolean {
         if (netId.isNone) return false
         val index = netId.index
-        if (index >= capacity || liveFlags[index]) return false
-        if (generations[index] != ((netId.generation + 1) and NetId.GENERATION_MASK)) return false
-        if (!removeFromFreeQueue(index)) return false
+        if (index >= capacity) return false
+        removeFromFreeQueue(index)
         generations[index] = netId.generation
         liveFlags[index] = true
         entities[index] = null
```

```
NetIdReservationTest[jvm] > reclaiming a live id - or one whose index was handed out again - changes nothing()[jvm] FAILED
13 tests completed, 1 failed
BUILD FAILED in 4s
message="org.opentest4j.AssertionFailedError: a live id was reclaimed"
```

I removed the guards together and did not run each one on its own. The guards overlap (a live id is also absent from the free queue), so the test pins that `reclaim` refuses a live or reused index, not which line refuses it.

Round 1's mutations M0 and M2-M5 cover code round 2 did not change. Their diffs and outputs are in `BRIEF-196.md` at `ffd97b7`.

## Regenerated files

None. No replicated component was added or removed, and `net-protocol.lock` and `expected-generated-hashes.txt` are untouched by this branch. `udeaCheckProtocolLock` ran in the build.
