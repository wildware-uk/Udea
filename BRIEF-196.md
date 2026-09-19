ebdad11

# BRIEF-196: Play, Stop, Step and Play standalone in the editor

Branch `issue-196-play-stop-step`, cut from `origin/kmp` at `5821d25` (fully green, 928 tasks, per the lead; I did not re-run that baseline). `ebdad11` is the code every result below was produced on unless a line says otherwise; the commit that adds this file sits on top of it and changes nothing else.

## Evidence command

```
sh gradlew :moba:desktop:editorTest --tests '*MobaEditorPlayTest*' --rerun
```

`MobaEditorPlayTest` presses the toolbar's buttons in the real editor window (ComposeGL `uiTest`, headless) over a real `moba` world wired exactly as `runEditor` wires it. Its first test is the issue's first criterion: Play, 1200 ticks of the lane fighting (it asserts something took damage or died), Stop, and `WorldHasher.hash` over a whole-world capture (fields, tick, random streams, id allocator) must equal the hash from before Play.

Green at `ebdad11` (`--rerun`, so not from the build cache):

```
Calculating task graph as no cached configuration is available for tasks: :moba:desktop:editorTest --tests *MobaEditorPlayTest* --rerun
> Task :moba:desktop:editorTestClasses UP-TO-DATE
> Task :moba:desktop:editorTest
BUILD SUCCESSFUL in 8s
```

**It goes red when Stop restores from the live map instead of bytes.** Mutation M1 keeps Fleks' `world.snapshot()` map at Play and loads it at Stop, after the bytes, so clock, random streams and ids are still right and only the component objects are the live ones. Applied to `ebdad11` with `git apply`, run with the command above, reverted with `git apply -R`:

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 8119fc0..f9e4f20 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -158,6 +158,7 @@ public class EditorToolset(
 
     /** The play under way, or `null` while editing. */
     private var playing: PlaySession? = null
+    private var liveMap: Map<Entity, com.github.quillraven.fleks.Snapshot> = emptyMap()
 
     /** Every call that changed the world or an edit session, in order. */
     public val journal: EditorJournal = EditorJournal()
@@ -700,6 +701,7 @@ public class EditorToolset(
         forEachOpenEdit(::commit)
         val session = PlaySession(level, clock.tick, history.mark())
         playing = session
+        liveMap = world.snapshot()
         play.time.resume()
         bridge.event("editor_play:${session.tick.value}", clock.tick.value)
         AgentResult.ok {
@@ -733,6 +735,7 @@ public class EditorToolset(
 
         // 1. The world as it was at Play, from the bytes, through the level load path.
         play.levels.loadNow(level)
+        world.loadSnapshot(liveMap)
         playing = null
         // 2. What becomes of the edits made while playing.
         val discarded = afterRestore(running)
```

```
MobaEditorPlayTest > play, a fight, then stop puts back the world hash from before play() FAILED
MobaEditorPlayTest > an edit made during play is thrown away at stop, with its undo entry, and one made before stays() FAILED
5 tests completed, 2 failed
BUILD FAILED in 7s
message="org.opentest4j.AssertionFailedError: the world after Stop is not the world from before Play ==> expected: <-2223924700062390788> but was: <3996789981255548409>"
message="org.opentest4j.AssertionFailedError: Stop did not put back the world the pre-play spawn left ==> expected: <5601703085113449964> but was: <3173718925151124592>"
```

The clock assertion just before the hash (`Stop did not put the clock back`) passed under M1; the hash is what caught it.

## Summary

**What Play, Stop and Step are.**

- `editor.play` (new tool, `EditorToolset`): encodes the world with `LevelService.saveNow()` (the #191 encoder) into a `ByteArray` held in memory, copies every author's undo history, commits every open edit session, and resumes the loop. Pressed again while a play is under way it only resumes, keeping the first bytes.
- `editor.stop` (new tool): decodes the bytes with `LevelService.read`, pauses, cancels every open edit session, then (1) applies the level with the new `LevelService.loadNow(level)`, then (2) `afterRestore(...)` puts every author's undo history back as it was at Play and answers the play-time edits it dropped. Step (2) is the one place #238's Keep would re-apply kept edits.
- Step is the existing `time.step` with `ticks=1`. Nothing new.
- The toolbar (`udea-editor`, new files `PlaybackToolbar.kt` and `PlayControls.kt`) is Play, Stop, Step, Play standalone and a status line. Each button is a tool call through `EditorTools`, so it goes through the bridge, the barrier and the editor author's history exactly like an agent's call. Hooks in existing files: one constructor parameter and one property on `EditorSession`, one line in `EditorWindow`.

**Why `loadNow` and not `LevelScene`.** The issue says Stop decodes "through the same load path as a level file (#192's `LevelScene` + `LevelService.load`)". `LevelScene` deliberately does not restore the clock or the random streams (its KDoc says why), so a Stop through it could not match the pre-Play hash. `LevelService.load` queues its `apply` on the barrier; a tool already runs inside a barrier drain, so queued it would land after another tick. `loadNow` calls that same `apply` directly, the same split as `saveNow`/`save`. `read` + `apply` is the whole level load path.

**Decisions** (each also commented on #196):

- Play and Stop are agent tools, not window-only actions, so an agent can do what the window does. Rejected: the window holding a snapshot and calling `time.resume`.
- Stop restores each author's whole history as it was at Play. So an entry made during play is dropped (the issue), and an entry *undone* during play comes back (the world Stop restores still holds that edit).
- Edit sessions (#232): Play commits every open one (after the save succeeds, so a refused Play leaves them open); Stop cancels every open one before loading. Both walk authors in id order.
- Play standalone saves through `editor.save` as `play-standalone.udealevel` in the editor's level directory (`moba`: `moba/desktop/build/editor-levels/`), overwritten each time, instead of a fresh temp file.
- Play standalone starts `:moba:desktop:run`'s main class (`MobaAgent`) in a new JVM with `-Dmoba.level=<file>` (what `-Plevel` forwards) and `-Dudea.render.mode=Windowed`, on the editor's own classpath - **not** `sh gradlew :moba:desktop:run -Plevel=...`. The editor itself runs inside a Gradle `JavaExec`; a nested Gradle call means a second client and daemon and a configuration pass before a window appears. `MobaLaunchLevel.PROPERTY` went from `internal` to `public` so the editor source set can name it rather than repeat the string.
- The toolbar keeps no copy of whether a play is under way: every button is always pressable, and the tool's answer (or refusal, e.g. `not_playing`) is what the status line shows. An agent can start or stop a play without the window knowing.

**Things found, not fixed here:**

- After Stop, `moba`'s score bar (`MobaHudModel` reads the `MatchService` mirror, which `MatchSystem` refreshes once per tick) and the follow camera still show where play left them until the next tick. The world itself is back (the hash test, and `issue196-03-stopped-back-at-tick1.png` shows the units and the 500/500 bar at tick 1). One Step puts both right (`issue196-04-step-to-tick2.png`). A paused world has no tick to refresh a mirror; fixing it belongs to `moba`'s HUD, not this ticket.
- Known edge, commented on #196: undoing a pre-play `editor.delete` during play puts the same component objects back into the world, play then changes them, and after Stop that history entry holds the changed objects.
- The snapshot ring is not cleared at Stop, so after Stop it holds keyframes from play with ticks later than the clock. This is the same as for any runtime `LevelService.load`; a rewind after Stop can only land on ticks at or before the Stop tick, where only pre-play keyframes exist. Not exercised by a test.

## `sh gradlew build`

`ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue` at `ebdad11`, run alone after three quiet samples ten seconds apart, tail of the log:

```
BUILD SUCCESSFUL in 3m 36s
928 actionable tasks: 657 executed, 130 from cache, 141 up-to-date
Configuration cache entry stored.
```

`grep -c FAILED` over that log: 0. Same task count as the lead's baseline (928); no baseline task turned red, and this ticket names no task to turn green (kmp was already green). `:moba:desktop:editorTest` ran in it (5 `MobaEditorPlayTest` + 2 `MobaEditorTest`, 0 failures).

GL: the ticket adds a toolbar to a ComposeGL window and touches no `udea-render` code, but I ran the GL suites for real anyway at `ebdad11`:

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true
```

```
Calculating task graph as no cached configuration is available for tasks: udeaGlTest udeaAgentGlTest
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest
BUILD SUCCESSFUL in 1m 18s
```

Suites in the XML reports afterwards (none skipped):

```
testsuite name="dev.wildware.udea.render.gl.GlCaptureDeterminismTest" tests="1" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.render.gl.GlCaptureTest" tests="1" skipped="0" failures="0" errors="0"
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

`sh gradlew udeaVerifyModuleGraph udeaVerifyAgentsMd`: BUILD SUCCESSFUL.

## Images

All in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`. One live session at `ebdad11`: `sh gradlew :moba:desktop:runEditor -PdebugPort=7851` on a private Xvfb display (`:1961`, 1600x900, llvmpipe), buttons pressed with real X mouse clicks (XTEST), the whole display grabbed with `ffmpeg -f x11grab` after each press, and `/health` read each time. The right-hand 320px and the bottom are empty Xvfb screen outside the 1280x720 window.

- `issue196-01-editor-paused-tick1.png` - the editor as it opens: the new toolbar under the menu bar, status "Editing", paused at tick 1, score 5/12/10.
- `issue196-02-playing-tick719.png` - after Play and 12 s: "Playing - Stop returns to tick 1", running at tick 719, the lane mid-fight, the orc elite's bar down.
- `issue196-03-stopped-back-at-tick1.png` - after Stop: "Editing - back at tick 1", paused at tick 1, the units back in their starting ranks and the elite at 500/500. The score bar still reads the play-time counts (see "Things found").
- `issue196-04-step-to-tick2.png` - after Step: "Stepped to tick 2", paused at tick 2; score bar back to 5/12/10.
- `issue196-05-skeleton-spawned-before-standalone.png` - Create's spawn button: an extra skeleton beside the player, `#1 editor.spawn #31` in History.
- `issue196-06-standalone-game-undead11.png` - Play standalone: a separate game window (no editor panels), running, with **UNDEAD 11** - the bundled level has 10, so it loaded the saved level with the skeleton from shot 05.
- `issue196-sequence.png` - the six above, tiled in order.

The transcript of that session (`/health` after each press):

```
== 01-editor-open
{"ok":true,"frame":627,"tick":1,"paused":true,"renderMode":"Windowed","role":"standalone","sessionId":"s-2e03","editor":true}
clicked 36,55 on :1961
== 02-playing
{"ok":true,"frame":1189,"tick":716,"paused":false,"renderMode":"Windowed","role":"standalone","sessionId":"s-2e03","editor":true}
clicked 101,55 on :1961
== 03-stopped
{"ok":true,"frame":1282,"tick":1,"paused":true,"renderMode":"Windowed","role":"standalone","sessionId":"s-2e03","editor":true}
clicked 169,55 on :1961
== 04-stepped
{"ok":true,"frame":1413,"tick":2,"paused":true,"renderMode":"Windowed","role":"standalone","sessionId":"s-2e03","editor":true}
clicked 118,140 on :1961
== 05-spawned
{"ok":true,"frame":1556,"tick":2,"paused":true,"renderMode":"Windowed","role":"standalone","sessionId":"s-2e03","editor":true}
clicked 275,55 on :1961
== 06-standalone
{"ok":true,"frame":2031,"tick":2,"paused":true,"renderMode":"Windowed","role":"standalone","sessionId":"s-2e03","editor":true}
moba.editor: Play standalone started pid 3488244 on /srv/ssd1/workspace/Udea/.claude/worktrees/agent-adaebd966d17e4337/moba/desktop/build/editor-levels/play-standalone.udealevel, output in /srv/ssd1/workspace/Udea/.claude/worktrees/agent-adaebd966d17e4337/moba/desktop/build/editor-levels/play-standalone.log
```

What `/proc` said about the standalone game while it ran (script `proc.sh`, the `-cp` value removed for length):

```
# /proc/3488244/cmdline, the -cp value (one long line) removed
/home/shaun/.sdkman/candidates/java/21.0.11-tem/bin/java
-cp
-Dmoba.level=/srv/ssd1/workspace/Udea/.claude/worktrees/agent-adaebd966d17e4337/moba/desktop/build/editor-levels/play-standalone.udealevel
-Dudea.render.mode=Windowed
dev.wildware.moba.agent.MobaAgent
# /proc/3488244/stat fields 1-4 (pid comm state ppid)
3488244 (java) S 3485238
# /proc/3485238/cmdline, last argument (the parent's main class)
dev.wildware.moba.editor.MobaEditor
```

Both processes were stopped afterwards (their `/proc/<pid>/cmdline` read first).

## The issue, criterion by criterion

1. **Play, run N ticks of fighting, Stop: `WorldHasher.hash` equals the hash from before Play. Fails if Stop restores from the live map instead of bytes.** `MobaEditorPlayTest."play, a fight, then stop puts back the world hash from before play"` (N = 1200, asserts damage or a death happened and that the hash moved during play). Red under M1 above. Also `issue196-02`/`03`. And, one layer down, `LevelServiceTest."loadNow puts the same world back at once - after it moved on and was changed in place"` (M0 below).
2. **Step advances the tick counter by exactly one.** `MobaEditorPlayTest."step advances the tick counter by exactly one"`: +1 after a press and further frames, +2 after a second press, still paused. Red under M4 (Step as `time.resume`: t13 instead of t2). Live: `/health` tick 1 -> 2 in the transcript, `issue196-04`.
3. **Play standalone starts a separate game process loading the saved level. Screenshot of it.** `issue196-06-standalone-game-undead11.png` (UNDEAD 11 against the bundled level's 10), the `/proc` block above (a `java` whose parent is `MobaEditor`, running `MobaAgent` with `-Dmoba.level=.../play-standalone.udealevel`). And `MobaEditorPlayTest."play standalone saves the level and hands the launcher a file that loads as this world"`: the file the launcher is handed decodes, and is byte-identical to `saveNow()` of the editor's world.

The issue's design points, also tested in `MobaEditorPlayTest`: edits during play are thrown away with their undo entries and a pre-play edit stays undoable (M2); an edit session open at Stop is cancelled (M3). Refusals, through `SimHarness`: `EditorPlayRefusalTest` - no play wired answers `no_play`; Stop with nothing playing answers `not_playing`; a world a level cannot carry refuses Play with `level_not_saveable` and leaves the world paused and the drag open (M5); Play, 40 ticks, Stop on an empty world puts the clock back.

Not exercised: Play on a world while a replay is loaded; `time.rewind` after Stop; a second Play/Stop cycle in one test (the live session did Play-Stop once, then Step, then spawn).

### Mutation table

Each diff is `git diff` of the mutation, taken from the run; each was reverted before the next.

**M1** - Stop loads the live `snapshot()` map (shown above under the evidence command). Red: 2 of 5.

**M2** - Stop does not put the histories back. Run at `b60b189` (`EditorToolset` is unchanged from there to `ebdad11`).

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 8119fc0..6541c54 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -750,7 +750,7 @@ public class EditorToolset(
      * and the edits made while playing are answered. Thrown away today; the one place a "keep these"
      * step (epic #231's Keep) would re-apply them to the world just put back.
      */
-    private fun afterRestore(ended: PlaySession): List<EditorEdit> = history.restore(ended.history)
+    private fun afterRestore(ended: PlaySession): List<EditorEdit> = emptyList()
 
     private fun requirePlay(tool: String): EditorPlay = play ?: throw AgentToolException(
         NO_PLAY,
```

```
MobaEditorPlayTest > an edit made during play is thrown away at stop, with its undo entry, and one made before stays() FAILED
5 tests completed, 1 failed
BUILD FAILED in 8s
message="org.opentest4j.AssertionFailedError: the undo entry made during play survived Stop ==> expected: <[(editor.spawn, 31)]> but was: <[(editor.spawn, 65568), (editor.spawn, 31)]>"
```

**M3** - Stop leaves edit sessions open. Run at `b60b189`.

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 8119fc0..35f1a30 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -729,7 +729,7 @@ public class EditorToolset(
             return@journaled AgentResult.failed(LEVEL_NOT_LOADABLE, unreadable.message ?: "the world saved at Play cannot be loaded")
         }
         play.time.pause()
-        forEachOpenEdit(::cancel)
+        // forEachOpenEdit(::cancel)
 
         // 1. The world as it was at Play, from the bytes, through the level load path.
         play.levels.loadNow(level)
```

```
MobaEditorPlayTest > an edit session left open at stop is cancelled() FAILED
5 tests completed, 1 failed
BUILD FAILED in 9s
message="org.opentest4j.AssertionFailedError: the edit session was still open after Stop. Expected value to be of type <dev.wildware.udea.agent.AgentResult.Failed>, actual <class dev.wildware.udea.agent.AgentResult$Ok>."
```

**M4** - Step implemented as resume. Run at `ebdad11`. (A first try that kept the `ticks` argument on `time.resume` failed for the wrong reason - the tool refused the unknown argument and the tick did not move - and was replaced by this one.)

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/PlayControls.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/PlayControls.kt
index 8b66100..e169a4d 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/PlayControls.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/PlayControls.kt
@@ -76,7 +76,7 @@ internal class PlayControls(
     }
 
     fun step() {
-        tools.call(STEP, mapOf("ticks" to "1")) { answer ->
+        tools.call("time.resume") { answer ->
             status = when (answer) {
                 is AgentResult.Ok -> "Stepped to tick ${fields(answer)["tickAfter"]?.jsonPrimitive?.content}"
                 is AgentResult.Failed -> "$STEP refused: ${answer.error}"
```

```
MobaEditorPlayTest > step advances the tick counter by exactly one() FAILED
5 tests completed, 1 failed
BUILD FAILED in 7s
message="org.opentest4j.AssertionFailedError: one press of Step ==> expected: <t2> but was: <t13>"
```

**M0** - `loadNow` does nothing; `sh gradlew :udea-core:jvmTest --tests '*LevelServiceTest*'`, run at `b60b189`.

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/level/LevelService.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/level/LevelService.kt
index c93c7a7..ef4a7d3 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/level/LevelService.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/level/LevelService.kt
@@ -169,7 +169,7 @@ public class LevelService internal constructor(
      * load would land on the *next* drain, after a tick of the world it is meant to replace had run.
      */
     public fun loadNow(level: Level) {
-        apply(level)
+        // apply(level)
     }
 
     internal fun apply(level: Level) {
```

```
LevelServiceTest[jvm] > loadNow puts the same world back at once - after it moved on and was changed in place()[jvm] FAILED
    org.opentest4j.AssertionFailedError at LevelServiceTest.kt:248
15 tests completed, 1 failed
BUILD FAILED in 4s
```

(Line 248 is the clock assertion. Its message, which I read from the XML report at the time but did not keep, was that the clock was not put back, t37 expected and t57 found.)

**M5** - Play commits open sessions before the save, so a refused Play commits the drag. `sh gradlew :udea-agent:jvmTest --tests '*EditorPlayRefusalTest*'`, run on the tree that was then committed as `0d0519c` (the test was not yet committed).

```diff
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
index 8119fc0..d29a7d4 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/tools/EditorToolset.kt
@@ -691,13 +691,13 @@ public class EditorToolset(
                 put("stopReturnsTo", running.tick.value)
             }
         }
+        forEachOpenEdit(::commit)
         val level = try {
             play.levels.saveNow()
         } catch (refused: LevelSaveException) {
             return@journaled AgentResult.failed(LEVEL_NOT_SAVEABLE, refused.message ?: "the world cannot be saved as a level")
         }
         // After the save, so a refused Play leaves every session open exactly as it was.
-        forEachOpenEdit(::commit)
         val session = PlaySession(level, clock.tick, history.mark())
         playing = session
         play.time.resume()
```

```
Calculating task graph as no cached configuration is available for tasks: :udea-agent:jvmTest --tests *EditorPlayRefusalTest*
EditorPlayRefusalTest[jvm] > a play whose world cannot be saved is refused, and the world stays paused and the drag open()[jvm] FAILED
    org.opentest4j.AssertionFailedError at EditorPlayRefusalTest.kt:52
4 tests completed, 1 failed
BUILD FAILED in 10s
```

(Line 52 is the `update_edit` that must still succeed; under M5 it answered `Failed`.)

## Regenerated files

None. No replicated component was added or removed; `net-protocol.lock` and `expected-generated-hashes.txt` are untouched, and `udeaCheckProtocolLock` ran green in the build. Two new `@AgentTool`s (`editor.play`, `editor.stop`) arrive through the generated tool manifest, and `EngineToolSurfaceTest` (which compares `EngineToolModules` against that manifest) is green.
