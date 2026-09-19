1b4d0da

(The code under review is `1b4d0da`. The commit that adds this brief sits on top of it and changes nothing else.)

# BRIEF-238: Editor gizmos G7: edit during Play, and Keep an edit past Stop

Branch `issue-238-play-edits-keep`, off `origin/master` at `46c9206`. `git fetch` just before the final build found `origin/master` still at `46c9206`, so there was nothing to merge.

Every artefact named here is under
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue238/` (called `issue238/` below).

## 1. Evidence command

    sh gradlew :moba:desktop:editorTest --tests dev.wildware.moba.editor.MobaPlayKeepTest --tests dev.wildware.moba.editor.MobaPlayKeepPanelTest

These two classes run a real `moba` world, wired the way `runEditor` wires it: `MobaAgent.attach(editor = true)` and `MobaEditor.session`.
- **`MobaPlayKeepTest`** drives the tools as an agent does: `editor.play`, `editor.set_field`, `editor.play_edits`, `editor.keep`, `editor.unkeep`, `editor.stop`, `editor.undo`.
- **`MobaPlayKeepPanelTest`** presses the editor window's own controls: Play, the range ring dragged in the Scene tab, the panel's Keep box, the Inspector's Keep pin, Stop and Undo.

It runs 9 tests. On this tree it is green: `issue238/evidence-green.log` ends `BUILD SUCCESSFUL`. The JUnit XML is copied beside it: `TEST-dev.wildware.moba.editor.MobaPlayKeepTest.xml` has 5 tests and 0 failures, and `...MobaPlayKeepPanelTest.xml` has 4 tests and 0 failures.

**It goes red when the feature is reverted.** Mutation `stop-drops-kept` (section 7) takes Keep out of Stop, so Stop makes no kept edit again. The diff is from `issue238/mut/stop-drops-kept.diff`:

    @@ -775,7 +775,7 @@ public class EditorToolset(
             for (edit in made) {
                 if (PlayEditId(edit.sequence) !in running.kept || edit !is EditorEdit.Fields) continue
                 if (whyNotKeepable(edit, running) != null) continue
    -            kept.add(edit to reapply(edit))
    +            kept.add(edit to null)
             }

This is `issue238/mut/stop-drops-kept.log`, lines 285-297, with the blank lines kept:

    MobaPlayKeepPanelTest > the inspector pins a field changed during play, and its pin keeps and unkeeps that change() FAILED
        org.opentest4j.AssertionFailedError at MobaPlayKeepPanelTest.kt:145

    MobaPlayKeepPanelTest > a tower's range dragged during play reaches the game on the next tick, and kept, it is the stopped world's range() FAILED
        org.opentest4j.AssertionFailedError at MobaPlayKeepPanelTest.kt:110

    MobaPlayKeepTest > keep stores the value the edit left, not the change it made() FAILED
        org.opentest4j.AssertionFailedError at MobaPlayKeepTest.kt:96

    MobaPlayKeepTest > a kept play edit survives stop with its final value and is undoable, and an unkept one is gone() FAILED
        org.opentest4j.AssertionFailedError at MobaPlayKeepTest.kt:73

    > Task :moba:desktop:editorTest FAILED

Before any of the tools existed, the first run of `MobaPlayKeepTest` failed all 5 tests with `no_such_tool`. That failure is in `issue238/red1.log`, and the XML is at `issue238/TEST-*.xml` from that run, since overwritten by the green one.

## 2. Summary

**What a person gets.** In the editor, Play leaves the Scene tab and the Inspector live. A change made while the game runs reaches the game on the next tick. Every change made since Play is listed in a new **Changes during Play** panel, docked under History. Each row has a **Keep** box, or a line saying why it cannot be kept. Any Inspector field that a play edit wrote shows a **Keep after Stop** pin.

On Stop:
1. The world is put back exactly as #196 did it.
2. Every kept edit is made again, oldest first. Each one is a normal edit in its author's history, so Undo takes it back and Save writes it.
3. Everything else from the play is gone.

**The tools** (`udea-agent`, `EditorToolset`, next to the other `editor.*` tools):
- `editor.play_edits` lists every author's edits made since Play, oldest first. Each entry has its `editId`, author, tool, the values it left, `keepable`, `kept`, and a `reason` when it cannot be kept. With nothing playing it answers `playing: false` and no edits.
- `editor.keep(editId)` and `editor.unkeep(editId)` mark an edit. They are refused with `not_playing`, `no_such_play_edit` (an edit from before Play, or one undone since) or `not_keepable`.
- `editor.stop` also answers `kept`: for each kept edit, its old `editId` and the `sequence` it was recorded under again.

The three new tools are in `EngineToolModules.Editor`. Keep and unkeep are journaled like every other editor call. They run inside the barrier drain like every other tool, so a play edit lands between ticks.

**How Keep works.**
- `PlaySession` now also holds two things: the `NetId` of every entity live at Play (`existing`), and the set of kept `PlayEditId`s.
- `editor.play_edits` reads `EditorHistory.since(mark)`: every edit in any history made after Play's mark.
- Stop calls `history.restore` exactly as before. Then, for each edit it returns that is kept and still keepable, `reapply` writes each value the edit left (its `FieldChange.after`), not the difference it made. It records the result as an `EditorEdit.Fields` under the edit's own author and tool, with a new sequence number. Its `before` is what the restored world held, so Undo returns the restored value.

**The window** (`udea-editor`), in new files as the lead asked:
- `EditorPlayEdits.kt` holds the state. It reads `editor.play_edits` when anything but its own reads has completed, and calls `editor.keep` or `editor.unkeep` for a toggle or a pin.
- `PlayEditsPanel.kt` holds the panel, the `KeepPin`, and `PlayEditTags`.
- The shared window changed in three small places:
  - `EditorSession`: one field and one `frame` line.
  - `EditorWindow`: one dock line and the `DebugWindow`.
  - `InspectorPanel`: a `pin` slot under each field's name.

**Decisions**, each commented on #238:
1. Only field edits can be kept. A spawn or a delete made during Play is not keepable, and neither is an edit that wrote an entity spawned during Play (comment 1).
2. The Keep marks are shared by everyone, and any author can keep any play edit. A kept edit comes back in its own author's history under its original tool name (comment 2).
3. A kept value that the restored world already holds makes no undo entry (comment 3).

**Where the panel is docked, and why.** At 1280x720 there is no spare room, and I tried three places before this one:
- Under the Asset panel: `MobaEditorSaveTest` failed, because the asset fields no longer fit.
- A strip along the bottom: `MobaInspectorTest` and `EditorLayoutTest` failed, because the Inspector lost its height.
- Under Create: `EditorLayoutTest`'s float-the-Create-panel case failed, and the title did not fit.

It now sits under History. Each row puts its Keep box on its first line, so a short panel still shows every box, and the list scrolls.

**A defect found on the way, and a note for the merge.** The bridge replaces any answer longer than `AgentBridge.MAX_DELIVERABLE_RESULT_CHARS` with a `resultTooLarge` handle. `editor.play_edits` crosses that limit at about 7 edits. The window's parser then threw `Key playing is missing` on the render thread. The new test `a list of play edits too long for one answer ...` reproduced the throw (`issue238/red-long.log`). The panel now says "too long to show here" instead, and the window carries on (`issue238/green-long.log`).

The real fix is dev-237's: #237 changes `EditorTools.frame` to read whole answers (`wholeCommandResults`). **Once #237 is merged, the panel lists every edit however many there are.** Until then it shows the notice past about 7 edits. dev-237 told me that change is one line in `EditorTools.frame`, which this branch does not touch.

**Existing tests I changed.** The window now makes one more read of its own, `editor.play_edits`. The editor tests that list or answer the window's own reads now include it:
- `EditorSessionTest`: the idle case, and `drainHistoryAndEdits`.
- `EditorAssetsTest`: `sent()`.
- `AnimationPanelStateTest`: its answers and `READS`.
- `GlAnimationPreviewTest`: its answers. Only the xvfb GL run caught this one. A plain `build` skips it.

**Not covered:**
- Replay of an editing session that includes Play and Stop. `udea-replay` does not replay `editor.play` today (`grep` for `editor.play`, `editor.stop` and `EditorPlay` under `udea-replay/src` returns nothing). Keep and unkeep are journaled like every other call, so they will replay once Play does.
- In the pictures the Scene tab's grass area changes shape between frames while playing. That area is drawn from the game camera, which I did not touch.

## 3. `sh gradlew build`

The final run was `issue238/g.sh build-final build --continue` (JDK 21, `--max-workers=6`), on `1b4d0da`. The tail of `issue238/build-final.log`:

    BUILD SUCCESSFUL in 29s
    975 actionable tasks: 29 executed, 946 up-to-date
    Configuration cache entry reused.
    DONE exit=0

`DONE exit=0` is the line my script adds. The run is incremental, over `issue238/build3.log` (`975 actionable tasks: 58 executed, 917 up-to-date`, green) and the earlier builds. No `-x`. No task failed in either run, so `udeaDaemonBudget` needed no solo re-run.

### GL, under xvfb (the ticket touches `udea-editor`)

    xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
      sh gradlew udeaGlTest --rerun udeaAgentGlTest --rerun udeaEditorGlTest --rerun -Pudea.render.requireGl=true

This is `issue238/gl.sh`, run last by `issue238/final.sh`. From `issue238/gl.log`:

    > Task :udea-agent-host:udeaAgentGlTest
    > Task :udea-render:udeaGlTest
    > Task :udea-editor:udeaEditorGlTest
    ...
    BUILD SUCCESSFUL in 1m 34s
    117 actionable tasks: 12 executed, 105 up-to-date

The JUnit XML from that run is copied to `issue238/gl-xml/`:
- `udeaGlTest`: 22 classes, 23 tests, 0 skipped, 0 failed.
- `udeaAgentGlTest`: 2 tests, 0 skipped, 0 failed.
- `udeaEditorGlTest`: 5 tests (`GlAnimationPreviewTest`, `GlEditorLayoutTest`, `GlEditorTabsTest`, `GlGizmoDragTest`, `GlScenePickingTest`), 0 skipped, 0 failed.

An earlier GL run on `da2a8fc` failed `GlAnimationPreviewTest` with `Key playing is missing in the map`, because the fixture had no answer for the new read. That log was overwritten. The fixture fix is commit `2693aaf`.

## 4. Images

These are in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`. Each is the whole `runEditor` window under Xvfb display `:238`, captured with `ffmpeg -f x11grab` (`issue238/grab.sh`). The mouse was real X input through XTest (`issue238/xin.py`): the range drag, the Keep box, Play, Stop and Undo were all clicked. The calls made over HTTP as `session=editor` are in `issue238/transcript.log`: selecting the tower, spawning the skeleton, and the second pass's set_field and spawn. They were taken on `d8bfe96`. After that commit the panel only gained the "too long" branch, and the layout is the one under review.

- `issue238-01-before-play.png`: paused at tick 1. Tower #29 is selected with its range ring at 150, and a skeleton stands outside it. The Inspector shows `attackRange 150` and `shots 0`, and the panel says "Not playing".
- `issue238-02-playing.png`: tick 85, running. Nothing is in range, the ring is unchanged, and the panel says "No changes yet".
- `issue238-03-dragging.png`: tick 222, the grip held and dragged out. The live range is 360.9344, in the ring and in the Inspector. The panel is still empty, because an open drag is not an edit until it is let go.
- `issue238-04-released.png`: tick 347. The drag is one play edit, `#2 commit_edit #29 by editor`, with an unticked Keep box. The Inspector's `attackRange` has a "Keep after Stop" pin. `shots 1`: the tower has fired at the new range.
- `issue238-05-kept.png`: Keep is ticked in the panel, and the Inspector's pin shows ticked too. Tick 925, `shots 11`.
- `issue238-06-after-stop.png`: after Stop, back at tick 1. The skeleton is back and `shots` and `readyTick` are back to their start, but the ring and `attackRange` stay at 360.9344. History lists `#3 editor.commit_edit #29`, the kept edit made again.
- `issue238-07-undone.png`: Undo after Stop puts `attackRange` back to 150.
- `issue238-08-panel-kinds.png`: a second play with an edit that can be kept (`set_field` to 220) and a spawn. The panel is scrolled to the spawn, which reads "Can't keep: entity #39@0 was spawned during Play...".
- `issue238-09-unkept-gone.png`: after that Stop, the range is 150 and the history holds only the edit from before Play. Nothing was kept.
- `issue238-sequence.png`: images 01-07 tiled by `tools/collage.py`.

## 5. The issue, criterion by criterion

1. **A kept play edit survives Stop with its final value and is undoable afterwards; an unkept one is gone (headless, through the tools).**
   - `MobaPlayKeepTest.a kept play edit survives stop with its final value and is undoable, and an unkept one is gone`. It keeps one of two edits. After Stop the kept range is 240, the other is back at 150, the history holds one `editor.set_field`, and undo returns 150.
   - `keep stores the value the edit left, not the change it made`: 200 unkept, then 230 kept, gives 230. A kept change would give 180, which the `keeps-the-change` mutation shows.
   - `unkeep takes a kept edit back, so stop drops it`.
   - `keep and unkeep are refused with nothing playing, and for an edit that is not a play edit`.
2. **An edit to an entity spawned during Play is marked not keepable, and Stop drops it without error.**
   - `MobaPlayKeepTest.an edit to an entity spawned during play is not keepable, and stop drops it with no error`. Both the spawn and the `set_field` on the spawned unit are `keepable: false` with "spawned during Play". `editor.keep` answers `not_keepable`, `editor.stop` answers Ok, and the unit and the history entries are gone.
   - In the window: `MobaPlayKeepPanelTest.the panel says why an edit to a unit spawned during play cannot be kept, and offers no toggle for it`, and image 08.
3. **Dragging a tower's range during Play changes the running game on the next tick; keeping it leaves the new range in the stopped world; a before/during/after sequence is posted.**
   - `MobaPlayKeepPanelTest.a tower's range dragged during play reaches the game on the next tick, and kept, it is the stopped world's range`. The player stands 200 from an enemy tower. The ring's grip is dragged out in the Scene tab with the real pointer path while the game runs, one frame per pointer move. Each frame records `(tick, range, target)`.
   - The test asserts the tower turns on the player no later than the tick after the range first reaches 200, and never while it is short of 200. Then the panel's Keep is clicked, then Stop. The range stays, the history is `[editor.commit_edit, editor.move]`, and Undo returns 150.
   - The mutation `tower-reads-constant`, the tower targeting by the built constant as it did before #236, fails it with `the tower never turned on the player: [Seen(tick=17, range=150.0, target=-1), Seen(tick=18, range=160.0, target=-1), ...` (`issue238/mut/tower-reads-constant.log`).
   - Pictures: 01-07 and `issue238-sequence.png`, and they are on the dashboard.
   - Reaching the game on the next tick already worked after #196: play edits already went through the barrier. What this ticket adds is Keep.
4. **Tools `editor.play_edits`, `editor.keep`, `editor.unkeep`** are on the live surface. `describe_toolset editor` on the running `runEditor` (port 7848) listed all three with the descriptions above.
5. **The panel's Keep toggle and the Inspector's Keep pin** are covered by `MobaPlayKeepPanelTest`: the drag test clicks the panel's box, and `the inspector pins a field changed during play, and its pin keeps and unkeeps that change` covers the pin. It also checks that no other field has a pin. Images 04 and 05 show both.

## 6. Regenerated files

None. No replicated component was added or removed, so `net-protocol.lock` and `expected-generated-hashes.txt` are unchanged. No file in `docs/contracts/` changed.

## 7. Mutations

`issue238/mutate.py` does each mutation alone:
1. applies it, and saves `git diff` to `mut/<name>.diff`;
2. runs the evidence command, saving `mut/<name>.log`;
3. lists the failing tests from the JUnit XML in `mut/<name>.failed`;
4. restores the file with `git checkout`.

`mut/status-after.txt` is empty: the tree was clean afterwards. `mut/summary.txt` has one line per mutation. Its lines read "N failed of 9". Each diff below is copied from `mut/<name>.diff`, with only the lines that change.

| Mutation | Diff | Failed (of 9) |
|---|---|---|
| `stop-drops-kept` | `-kept.add(edit to reapply(edit))` / `+kept.add(edit to null)` | 4: drag, pin, kept-survives, value-not-change |
| `spawned-keepable` | `-edit.netIds.firstOrNull { it !in running.existing }` / `+edit.netIds.firstOrNull { false }` | 1: spawned-not-keepable |
| `reapply-unrecorded` | `-        record(again)` | 2: drag (history), kept-survives (history) |
| `since-includes-pre-play` | `-stack?.filterTo(since) { !mark.predates(it) }` / `+stack?.filterTo(since) { true }` | 2: drag, refusals |
| `panel-toggle-unkeeps` | `-val tool = if (keep) KEEP else UNKEEP` / `+val tool = UNKEEP` | 2: drag, pin |
| `keeps-the-change` | `-...write(world, entity, change.fieldIndex, change.after)` / `+...write(world, entity, change.fieldIndex, (before as Float) + ((change.after as Float) - (change.before as Float)))` | 1: value-not-change, `expected: <230.0> but was: <180.0>` |
| `tower-reads-constant` (moba `TowerSystem`) | `-if (distance > tower.attackRange) continue` / `+if (distance > LaneGeometry.TOWER_RANGE) continue` | 1: drag |
| `spill-unread` | `-val spilled = root["resultTooLarge"]?.jsonPrimitive?.booleanOrNull == true` / `+val spilled = false` | 1: too-long |
| `pin-never-shown` | `-if (rows.none { it.keepable }) return` / `+if (true) return` | 1: pin |

`keeps-the-change` is the design the issue rejected, keeping the change instead of the value. Before running it I predicted exactly one failure, 150 + 30 = 180, and that is what it gave. The other tests keep an edit whose starting value was the one restored, so keeping the value and keeping the change agree for them. That was the reason for writing that test.
