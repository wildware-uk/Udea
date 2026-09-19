b2760b7

# BRIEF - issue #235, editor gizmos G4: picking, selection and a multi-entity Inspector

The SHA above is the code this brief describes, and every run below is from it. The brief itself is committed
in the next commit, on top of it.

Branch `issue-235-picking-selection`, from `origin/master` at `ba7516b`. `git fetch` at the time of writing
shows no new commits on `origin/master`, so there was nothing to merge.

## 1. Evidence command

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew :udea-editor:test :udea-editor:udeaEditorGlTest :moba:desktop:editorTest -Pudea.render.requireGl=true --continue
```

It runs these tests:
- the headless picking and Inspector tests;
- the GL test that clicks, Shift-clicks and box-drags with the real mouse and keyboard in a real Kool window (`GlScenePickingTest`);
- `MobaInspectorTest` over a real `moba` world.

**Red when the feature is reverted.** Two things were switched off together: picking finds nothing under the
pointer, and the Inspector sends one `editor.set_field` per keystroke instead of its edit session. The command
then exits 1 with 12 failing tests. The literal diff, taken with `git diff` during that run:

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorInspector.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorInspector.kt
index 44f6cba..0cc9f68 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorInspector.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorInspector.kt
@@ -154,7 +154,7 @@ internal class EditorInspector(
         if (text != null && text != open.sent) {
             open.inFlight = true
             open.sent = text
-            tools.call(UPDATE_EDIT, mapOf("sessionId" to session.toString(), "values" to "${open.key}=$text")) { answer ->
+            tools.call("editor.set_field", mapOf("id" to readFor.joinToString(",") { it.raw.toString() }, "component" to open.key.substringBefore('.'), "field" to open.key.substringAfter('.'), "value" to text)) { answer ->
                 open.inFlight = false
                 open.valid = answer is AgentResult.Ok
                 message = when (answer) {
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ScenePicker.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ScenePicker.kt
index 22594c4..61b796a 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ScenePicker.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ScenePicker.kt
@@ -33,7 +33,7 @@ internal class ScenePicker(private val view: WorldViewport) {
     private val camera: EditorCamera = checkNotNull(view.camera) { "$view is the Game tab, which the editor does not pick in" }
 
     /** Every entity drawn under view pixel ([x], [y]), front-most first, each once. */
-    fun under(x: Float, y: Float): List<NetId> = frontFirst { it.contains(x, y) }
+    fun under(x: Float, y: Float): List<NetId> = frontFirst { false }
 
     /**
      * Every entity whose drawn rectangle touches the view rectangle from ([left], [bottom]) to
```

The failing tests, as the JUnit XML named them (`scratchpad/issue235/mut/EV.result`):

```
exit=1
FAILED InspectorTest > leaving the field commits the typing as one undo entry()
FAILED InspectorTest > Enter commits the typing as one undo entry()
FAILED InspectorTest > a value the field cannot hold is never kept, so the edit is cancelled rather than committed()
FAILED InspectorTest > typing into a field writes every selected entity live through one edit session, with no Set button()
FAILED ScenePickerTest > of two model boxes under the pointer the one nearer the eye is in front, whatever order they were reported in()
FAILED ScenePickingTest > an entity reported by a later render system is in front of one reported by an earlier()
FAILED ScenePickingTest > Shift-click adds and takes away, and a Shift-click on nothing changes nothing()
FAILED ScenePickingTest > where entities overlap the front one is picked, and clicking again picks the one behind()
FAILED ScenePickingTest > a click selects the entity under it, and a click on nothing selects nothing()
FAILED ScenePickingTest > a gizmo handle is hit before the entity under it, and the entity is picked beside it()
FAILED GlScenePickingTest > click, Shift-click and box select with the real mouse give the expected selection, and misses select nothing()
FAILED MobaInspectorTest > two units on different teams show Mixed for their team, and typing one team gives it to both as one undoable edit()
```

**Green on the branch.** The same command with nothing changed exits 0. These are the suites it ran, with the
GL ones really run (`skipped="0"`), as the XML reports them (`scratchpad/issue235/ev0-suites.txt`):

```
udea.editor.EditorAssetsTest" tests="7" skipped="0" failures="0" errors="0"
udea.editor.EditorLayoutTest" tests="6" skipped="0" failures="0" errors="0"
udea.editor.EditorSessionTest" tests="7" skipped="0" failures="0" errors="0"
udea.editor.EditorTabsTest" tests="5" skipped="0" failures="0" errors="0"
udea.editor.FreeAreaTest" tests="6" skipped="0" failures="0" errors="0"
udea.editor.gizmo.GizmoApiTest" tests="10" skipped="0" failures="0" errors="0"
udea.editor.InspectorTest" tests="5" skipped="0" failures="0" errors="0"
udea.editor.ScenePickerTest" tests="1" skipped="0" failures="0" errors="0"
udea.editor.ScenePickingTest" tests="6" skipped="0" failures="0" errors="0"
udea.editor.ViewportRedrawTest" tests="2" skipped="0" failures="0" errors="0"
udea.editor.gl.GlEditorLayoutTest" tests="1" skipped="0" failures="0" errors="0"
udea.editor.gl.GlEditorTabsTest" tests="1" skipped="0" failures="0" errors="0"
udea.editor.gl.GlScenePickingTest" tests="1" skipped="0" failures="0" errors="0"
moba.editor.MobaEditorPlayTest" tests="8" skipped="0" failures="0" errors="0"
moba.editor.MobaEditorSaveTest" tests="3" skipped="0" failures="0" errors="0"
moba.editor.MobaEditorTest" tests="2" skipped="0" failures="0" errors="0"
moba.editor.MobaGizmoRegistryTest" tests="2" skipped="0" failures="0" errors="0"
moba.editor.MobaInspectorTest" tests="1" skipped="0" failures="0" errors="0"
```

## 2. Summary

**Picking.** A render system says what it drew by implementing `PickBounds`. It reports a rectangle or a 3D
box per `NetId` to a `PickSink`, back to front.
- The editor reads those systems from `WorldViewport.pickBounds`. The pipeline fills it for Scene views from
  its view systems.
- `ScenePicker` projects each shape into the view:
  - rectangles through the 2D projection;
  - boxes through the orbit camera, all 8 corners (`EditorCamera.projectOrbit`).
- It sorts front first: a later system is in front of an earlier one; within one system, a nearer box is in
  front (`EditorCamera.depthOf`); otherwise, the later report is in front.
- The shapes come from:
  - `SpriteRenderSystem`: the drawn rectangle, as the upright box around it when the sprite is rotated;
  - `ModelRenderSystem`: the mesh's local box, placed with the same matrix the draw uses. `ModelStage` and
    `ModelBounds` now share one `place` function;
  - `moba`'s `CharacterRenderSystem`: the body rectangle it recorded while drawing (`DrawnUnits`).

**Selecting.** `ScenePicking` is the gesture.
- A click replaces the selection with the front entity.
- A second click on the same spot picks the next entity behind.
- A click on nothing clears the selection.
- A Shift-click toggles an entity in or out. A Shift-click on nothing changes nothing.
- A drag from empty space draws a box. On release it replaces the selection, or adds to it with Shift.

Each change is one `editor.select` call under the `editor` author, so the selection lives in the tool surface
and an agent sees it.

Who gets a press, in order: gizmo handles first (`HandleLayer`, on the G2 `Handle` values), then entities,
then empty space. `SceneOverlay` draws orange outlines round the selection and the box while it is dragged.
Shift comes from key events (`EditorKeys`), because ComposeGL's pointer events carry no modifiers.

**The Inspector** is a new docked panel. It reads `editor.common_fields` for the selection and shows **Mixed**
where the entities disagree.
- Following the owner's ruling during this ticket, there is **no Set button**. Each box is a G1 edit session:
  - the first keystroke sends `editor.begin_edit`;
  - each keystroke sends `editor.update_edit`, a live write with nothing filed in History;
  - Enter, or leaving the box, sends `editor.commit_edit`: **one** undo entry;
  - a box left holding a value the field cannot take sends `editor.cancel_edit` instead, so every entity goes
    back.
- It reads again when the selection changes, when any non-read tool call completes, and every 15 ticks while
  the game runs.
- To stop reads waking each other up, `EditorTools` now knows which calls are its own reads and does not
  count them as changes.

**Decisions**, each commented on #235 (links in section 6):
- `PickBounds` lives in `udea-render`, not `udea-editor`, because the systems that implement it cannot depend
  on the editor.
- Left button selects. Right button orbits in 3D and pans in 2D. Middle button pans. A drag that starts on an
  entity does nothing; that gesture is kept for G5, moving things.
- Where the Inspector is docked, why its field list has no `ScrollArea`, and the re-read interval.
- What counts as each kind of entity's clickable shape. `AnimationRenderSystem` reports none yet. glTF boxes
  use the rest-pose vertex bounds.
- The owner's no-Set-button ruling, and when an edit commits.

**What changed in existing tests, and why.**
- `EditorLayoutTest` and `EditorTabsTest` now move the camera with the right button, because the left button
  selects.
- `EditorSessionTest`'s idle test now expects the first frame to read both `editor.history` and
  `editor.selection`, then nothing more.
- `EditorAssetsTest` ignores `editor.selection` reads.
- `GlEditorLayoutTest` checks the Inspector panel's placement too. Its window-reading overlay moved into
  `GlWindowProbe.kt`, where the new GL test can use it.

**A note on order of work.** Most of the picking code was written before its tests, not test-first. That is
why every test here has been proven by mutation (section 8), not trusted. The owner's Inspector change was
done test-first: the new `InspectorTest` cases failed against the Set-button code for the right reasons
(`scratchpad/issue235/red-inspector-old-code.xml`), for example:
`the panel still has a Set button: [GameUnit.team, Mixed, Set, Position.x, 4.5, Set]`.

**One surprise.** ComposeGL tells a node's *ancestors* when focus leaves it, never the node itself
(`Focus.kt`, `tellAncestors` starts from `node.parent`). So the focus-lost handler is on a `Box` around the
text field. With the handler on the field itself, the focus test failed with `leaving the field sent []`
(`scratchpad/issue235/green2.log`).

**Not done, and out of scope:**
- Toggles and choice lists in the Inspector. Every field is a text box today.
- G1 cancels an edit session after 30 seconds with no update. So a value typed and then left in a focused box
  for 30 seconds with no Enter goes back.
- Pickable shapes for `AnimationRenderSystem`.

## 3. `sh gradlew build`

```
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue --max-workers=6
```

Exit 0 (`scratchpad/issue235/build1.done` says `DONE exit=0`). The last lines of `scratchpad/issue235/build1.log`, as written:

```

BUILD SUCCESSFUL in 1m 54s
984 actionable tasks: 600 executed, 220 from cache, 164 up-to-date
Configuration cache entry stored.
```

`udeaVerifyAgentsMd`, `udeaVerifyModuleGraph` and `udeaCheckProtocolLock` are among the tasks the log shows running. melon-merge was also running on the box, at a load average of about 4. With no `DISPLAY`, the GL tasks inside `check` skip, so section 4 is the GL evidence.

## 4. GL, for real under xvfb

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true
```

Run with `--rerun-tasks`, so nothing came from the build cache. Exit 0 (`scratchpad/issue235/glall2.done` says `DONE exit=0`). The last lines of `scratchpad/issue235/glall2.log`:

```

BUILD SUCCESSFUL in 1m 39s
117 actionable tasks: 117 executed
Configuration cache entry reused.
```

Every suite the three tasks ran, read out of their JUnit XML afterwards (`scratchpad/issue235/gl-suites.txt`). None was skipped:

```
udea.render.gl.GlCaptureDeterminismTest" tests="1" skipped="0" failures="0" errors="0"
udea.render.gl.GlCapturedUiTest" tests="1" skipped="0" failures="0" errors="0"
udea.render.gl.GlCaptureTest" tests="1" skipped="0" failures="0" errors="0"
udea.render.gl.GlFrameResizeTest" tests="1" skipped="0" failures="0" errors="0"
udea.render.gl.GlImportedModelRenderTest" tests="1" skipped="0" failures="0" errors="0"
udea.render.gl.GlKoolInputTest" tests="1" skipped="0" failures="0" errors="0"
udea.render.gl.GlKoolKeyTableTest" tests="1" skipped="0" failures="0" errors="0"
udea.render.gl.GlKoolPointerTest" tests="1" skipped="0" failures="0" errors="0"
udea.render.gl.GlModelRenderTest" tests="1" skipped="0" failures="0" errors="0"
udea.render.gl.GlOverlayIsolationTest" tests="1" skipped="0" failures="0" errors="0"
udea.render.gl.GlSkinnedModelRenderTest" tests="1" skipped="0" failures="0" errors="0"
udea.render.gl.GlUiLayerTest" tests="1" skipped="0" failures="0" errors="0"
udea.render.gl.GlViewportOrbitTest" tests="1" skipped="0" failures="0" errors="0"
udea.render.gl.GlViewPresentTest" tests="1" skipped="0" failures="0" errors="0"
udea.render.gl.GlViewResizeTest" tests="1" skipped="0" failures="0" errors="0"
udea.render.gl.GlWorldViewportTest" tests="1" skipped="0" failures="0" errors="0"
udea.render.gl.GlWorldViewTest" tests="1" skipped="0" failures="0" errors="0"
udea.render.gl.KoolThreadShutdownTest" tests="1" skipped="0" failures="0" errors="0"
udea.render.gl.OffscreenBackendExplodingCaptureTest" tests="1" skipped="0" failures="0" errors="0"
udea.render.gl.OffscreenBackendSecondCreateTest" tests="1" skipped="0" failures="0" errors="0"
udea.render.gl.OffscreenBackendShutdownTest" tests="1" skipped="0" failures="0" errors="0"
udea.render.gl.OffscreenBackendTest" tests="2" skipped="0" failures="0" errors="0"
udea.agent.host.gl.OffscreenRenderToolsTest" tests="1" skipped="0" failures="0" errors="0"
udea.agent.host.gl.OverlayCaptureIsolationTest" tests="1" skipped="0" failures="0" errors="0"
udea.editor.gl.GlEditorLayoutTest" tests="1" skipped="0" failures="0" errors="0"
udea.editor.gl.GlEditorTabsTest" tests="1" skipped="0" failures="0" errors="0"
udea.editor.gl.GlScenePickingTest" tests="1" skipped="0" failures="0" errors="0"
```

## 5. Images

All in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`.

From `GlScenePickingTest`, a real Kool window driven through GLFW's own mouse and key callbacks:
- `issue235-gl-click-selects-red.png`: a click on the red square selects it, and it gets an orange outline.
- `issue235-gl-shift-click-adds-green.png`: Shift-click adds the green square. The Inspector says 2 entities.
- `issue235-gl-box-mid-drag.png`: the selection box during a drag.
- `issue235-gl-box-selected-two.png`: on release, the box has selected red and green but not blue.
- `issue235-gl-selection-sequence.png`: the four above, tiled.

From the real `moba` editor (`:moba:desktop:runEditor` on an Xvfb display), driven by real X mouse and key
events through the XTEST extension (`scratchpad/issue235/xt.py`):
- `issue235-moba-real-a0-click-empty.png`: a click on empty grass selects nothing.
- `issue235-moba-real-a1-click-orc.png`: a click on the lone orc selects it. The outline fits its body, not
  its whole sprite frame.
- `issue235-moba-real-a2-shift-click-soldier.png`: Shift-click adds a soldier. **The mixed selection**:
  kind, target and team all read Mixed.
- `issue235-moba-real-a3-typed-live.png`: "2" typed into team. Both units' rings are green, because the
  value is written live, and History still says "Nothing to undo".
- `issue235-moba-real-a4-enter-one-edit.png`: after Enter, History shows exactly one `editor.commit_edit`.
- `issue235-moba-real-a5-undo.png`: one Undo puts both teams back. The rings are orange and blue again, and
  the field reads Mixed again.
- `issue235-moba-real-input-sequence.png`: the six above, tiled.

In the same session, the tool surface confirmed what the pictures show:
- `editor.selection` answered ids `[6, 17]`.
- `editor.history` answered `size: 0` before Enter, and one `editor.commit_edit` over `ids [6, 17]` after.
- After Undo, `world.query_entities where=team=2` listed ids 7-16 only.

## 6. The issue, criterion by criterion

- **Click, Shift-click and box select give the expected `editor.selection`, as GL tests with real pointer
  input, each with a negative (a miss selects nothing).**
  - `GlScenePickingTest`: a real Kool window, with the mouse and Shift sent through GLFW's own callbacks. It
    checks the selection an agent reads with `editor.selection`, answered by the real `EditorToolset`.
  - Its steps: a click on nothing gives `[]`; a click on red gives `[red]`; Shift-click on green gives
    `[red, green]`; Shift-click on nothing leaves `[red, green]`; Shift-click on red gives `[green]`; a box
    over nothing gives `[]`; a box over red and green gives `[red, green]`.
  - Mutations M12 and M13 turn it red. Images `issue235-gl-*.png`.
  - The same gestures are covered headlessly by `ScenePickingTest`, and shown in the real moba editor with
    real X input: `issue235-moba-real-a0..a2`.
- **Where entities overlap, the front one is picked, and a second click picks the one behind.**
  - `ScenePickingTest > where entities overlap the front one is picked, and clicking again picks the one behind`.
  - `ScenePickingTest > an entity reported by a later render system is in front of one reported by an earlier`.
  - `ScenePickerTest` checks that the nearer model box wins whatever order the boxes were reported in.
  - Mutations M3 and M4 turn these red.
- **A custom render system that implements `PickBounds` makes its entities pickable (test).**
  - `GlScenePickingTest`'s `Tiles` is a render system written in the test itself. The only thing it does
    beyond drawing is implement `PickBounds`, and its squares are what the real mouse picks. Mutation M12,
    where the pipeline never hands it to the view, turns the test red.
  - The engine's and the game's own implementations: `PickBoundsTest` (sprites and models; M7, M8) and
    `DrawnUnitsTest` (moba units; M9).
- **The inspector shows Mixed for differing values, and one write changes every selected entity as one
  undoable edit. Post a screenshot of a mixed selection.**
  - `InspectorTest > a field the selection disagrees on shows Mixed, and one it agrees on shows the value`.
  - `MobaInspectorTest`, over a real moba world, checks each step in order:
    - an orc and a soldier show `Mixed` for their team;
    - typing a team changes both units, with History unchanged;
    - Enter adds exactly one `editor.commit_edit` to History;
    - one Undo puts both units back, and the field reads `Mixed` again.
  - Mutations M10 and M11 turn these red.
  - Screenshot of the mixed selection: `issue235-moba-real-a2-shift-click-soldier.png`. The write and the
    undo: `a3`, `a4` and `a5`.
- **Added by the owner's ruling: no Set button; editing a field writes the value. Typing must not file one
  undo entry per keystroke, and a half-typed invalid value must not be written.**
  - `InspectorTest > typing into a field writes every selected entity live through one edit session, with no Set button`:
    - the fields column holds no `Set`;
    - the first keystroke sends `editor.begin_edit` over `3,5`;
    - each keystroke sends the whole box as `editor.update_edit`;
    - nothing is committed while typing.
  - `InspectorTest > Enter commits the typing as one undo entry` and
    `> leaving the field commits the typing as one undo entry`.
  - `InspectorTest > a value the field cannot hold is never kept, so the edit is cancelled rather than committed`.
  - All four were seen red against the Set-button code before the change
    (`scratchpad/issue235/red-inspector-old-code.xml`).
  - `MobaInspectorTest` checks that the typing is live and that Enter adds exactly one History entry.
  - Mutations M10 and M11 turn these red. Images `issue235-moba-real-a3/a4/a5`.

Decision comments on #235: 5742073553 (where `PickBounds` lives), 5742073626 (mouse buttons),
5742073722 (the Inspector's dock and refresh), 5742073823 (clickable shapes), 5742141704 (no Set button,
and when an edit commits).

## 7. Regenerated files

None. No replicated component was added or removed, so neither `net-protocol.lock` nor
`expected-generated-hashes.txt` moved. No `docs/contracts/` file was touched.

## 8. Mutation table

Each row was made by `scratchpad/issue235/mutate.py`. It applies one edit, saves `git diff` of it, runs the
named tests, reads the failing test names out of the JUnit XML, and puts the file back. Every row went red.
The diffs and names below are spliced from `scratchpad/issue235/mut/<id>.diff` and `<id>.result`.

### M1: Picking finds nothing under the pointer.

Tests run: `:udea-editor:test`

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ScenePicker.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ScenePicker.kt
index 22594c4..61b796a 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ScenePicker.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ScenePicker.kt
@@ -33,7 +33,7 @@ internal class ScenePicker(private val view: WorldViewport) {
     private val camera: EditorCamera = checkNotNull(view.camera) { "$view is the Game tab, which the editor does not pick in" }
 
     /** Every entity drawn under view pixel ([x], [y]), front-most first, each once. */
-    fun under(x: Float, y: Float): List<NetId> = frontFirst { it.contains(x, y) }
+    fun under(x: Float, y: Float): List<NetId> = frontFirst { false }
 
     /**
      * Every entity whose drawn rectangle touches the view rectangle from ([left], [bottom]) to
```

```
exit=1
FAILED ScenePickerTest > of two model boxes under the pointer the one nearer the eye is in front, whatever order they were reported in()
FAILED ScenePickingTest > an entity reported by a later render system is in front of one reported by an earlier()
FAILED ScenePickingTest > Shift-click adds and takes away, and a Shift-click on nothing changes nothing()
FAILED ScenePickingTest > where entities overlap the front one is picked, and clicking again picks the one behind()
FAILED ScenePickingTest > a click selects the entity under it, and a click on nothing selects nothing()
FAILED ScenePickingTest > a gizmo handle is hit before the entity under it, and the entity is picked beside it()
```

### M2: Shift is never seen as held.

Tests run: `:udea-editor:test`

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorKeys.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorKeys.kt
index 1de83da..bdc893c 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorKeys.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorKeys.kt
@@ -22,8 +22,8 @@ internal class EditorKeys : KeyHandler {
 
     override fun onKey(event: KeyEvent): Boolean {
         shift = when {
-            event.key == Key.Shift -> event.type == KeyEventType.Down
-            else -> event.modifiers.shift
+            event.key == Key.Shift -> false
+            else -> false
         }
         return false
     }
```

```
exit=1
FAILED ScenePickingTest > a drag from empty space selects every entity the box touches, and a box over nothing selects nothing()
FAILED ScenePickingTest > Shift-click adds and takes away, and a Shift-click on nothing changes nothing()
```

### M3: A second click on the same spot never moves to the entity behind.

Tests run: `:udea-editor:test`

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ScenePicking.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ScenePicking.kt
index b01c153..885623d 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ScenePicking.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ScenePicking.kt
@@ -97,7 +97,7 @@ internal class ScenePicking(
             return
         }
         val again = abs(pressX - cycleX) <= SLOP && abs(pressY - cycleY) <= SLOP && stack == cycleStack
-        cycleIndex = if (again) (cycleIndex + 1) % stack.size else 0
+        cycleIndex = 0
         cycleX = pressX
         cycleY = pressY
         cycleStack = stack
```

```
exit=1
FAILED ScenePickingTest > where entities overlap the front one is picked, and clicking again picks the one behind()
```

### M4: Nearer models are no longer sorted in front of farther ones.

Tests run: `:udea-editor:test`

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ScenePicker.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ScenePicker.kt
index 22594c4..4ab984e 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ScenePicker.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ScenePicker.kt
@@ -134,7 +134,6 @@ internal class ScenePicker(private val view: WorldViewport) {
 
         /** Later system first; then nearer the eye; then reported later. See the class KDoc. */
         val FRONT_FIRST: Comparator<Bounds> = compareByDescending<Bounds> { it.source }
-            .thenBy { it.depth }
             .thenByDescending { it.order }
     }
 }
```

```
exit=1
FAILED ScenePickerTest > of two model boxes under the pointer the one nearer the eye is in front, whatever order they were reported in()
```

### M5: Gizmo handles no longer get a press before entities do.

Tests run: `:udea-editor:test`

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/SceneNavigation.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/SceneNavigation.kt
index 4655121..1cb1160 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/SceneNavigation.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/SceneNavigation.kt
@@ -62,7 +62,6 @@ internal class SceneNavigation(
             is PointerEvent.Press -> {
                 drag = when {
                     !onView -> Drag.None
-                    event.button == PointerButton.Primary && view.pressGizmo(at.x, at.y) -> Drag.Gizmo
                     event.button == PointerButton.Primary -> Drag.Select.also { picking.press(at.x, at.y, shift()) }
                     camera.dimension == ViewDimension.ThreeD && event.button == PointerButton.Secondary -> Drag.Orbit
                     else -> Drag.Pan
```

```
exit=1
FAILED EditorTabsTest > a press on a gizmo is the gizmo's in the Scene tab, and the game's in the Game tab()
FAILED ScenePickingTest > a gizmo handle is hit before the entity under it, and the entity is picked beside it()
```

### M6: The editor's own reads count as changes, so each read wakes the next.

Tests run: `:udea-editor:test`

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorTools.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorTools.kt
index 44580ea..3e3e661 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorTools.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorTools.kt
@@ -99,7 +99,7 @@ public class EditorTools(
         var changed = false
         var id = maxOf(changesSeen, firstSent - 1) + 1
         while (id <= now) {
-            if (id !in reads) {
+            if (true) {
                 changed = true
                 break
             }
```

```
exit=1
FAILED EditorSessionTest > its own reads do not trigger more, so an idle window sends nothing()
```

### M7: A sprite's rotation is ignored in its clickable rectangle.

Tests run: `:udea-render:jvmTest --tests ...PickBoundsTest`

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/draw/SpriteRenderSystem.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/draw/SpriteRenderSystem.kt
index 4187766..2111055 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/draw/SpriteRenderSystem.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/draw/SpriteRenderSystem.kt
@@ -157,8 +157,8 @@ public class SpriteRenderSystem(
         val halfHeight = sprite.height / 2f
         val cosine = abs(cos(pose.angle))
         val sine = abs(sin(pose.angle))
-        val reachX = halfWidth * cosine + halfHeight * sine
-        val reachY = halfWidth * sine + halfHeight * cosine
+        val reachX = halfWidth
+        val reachY = halfHeight
         out.rect(id, centreX - reachX, centreY - reachY, centreX + reachX, centreY + reachY)
     }
 
```

```
exit=1
FAILED PickBoundsTest > the sprite system reports each sprite's drawn rectangle, turned with its body, by NetId()[jvm]
```

### M8: A model's rotation is ignored in its clickable box.

Tests run: `:udea-render:jvmTest --tests ...PickBoundsTest`

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelBounds.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelBounds.kt
index 19ee91f..9469a92 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelBounds.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelBounds.kt
@@ -46,7 +46,7 @@ internal class ModelBounds {
         out: PickSink,
     ) {
         val local = localBox(source) ?: return
-        matrix.place(x, y, z, rotationX, rotationY, rotationZ, axisScale.set(scaleX, scaleY, scaleZ))
+        matrix.place(x, y, z, 0f, 0f, 0f, axisScale.set(scaleX, scaleY, scaleZ))
         if (source is ImportedModel) matrix.rotate(Y_UP_TO_Z_UP, Vec3f.X_AXIS)
 
         var minX = Float.POSITIVE_INFINITY
```

```
exit=1
FAILED PickBoundsTest > a model's box is its mesh placed by the transform it is drawn with()[jvm]
```

### M9: moba reports units that have no `NetId`.

Tests run: `:moba:game:jvmTest --tests ...DrawnUnitsTest`

```diff
diff --git a/moba/game/src/commonMain/kotlin/dev/wildware/moba/DrawnUnits.kt b/moba/game/src/commonMain/kotlin/dev/wildware/moba/DrawnUnits.kt
index 88d06d0..0f6ff8a 100644
--- a/moba/game/src/commonMain/kotlin/dev/wildware/moba/DrawnUnits.kt
+++ b/moba/game/src/commonMain/kotlin/dev/wildware/moba/DrawnUnits.kt
@@ -33,7 +33,6 @@ internal class DrawnUnits {
      * `NetId` cannot be selected and is not kept.
      */
     fun add(id: NetId, x: Float, y: Float, height: Float) {
-        if (id.isNone) return
         if (size == ids.size) grow()
         val at = size * SIDES
         rects[at] = x - height * BODY_HALF_WIDTH_OF_HEIGHT
```

```
exit=1
FAILED DrawnUnitsTest > each unit is reported as its body around where it stands, in draw order, and one with no NetId is not()[jvm]
```

### M10: The Inspector writes one `editor.set_field` per keystroke instead of using an edit session.

Tests run: `:udea-editor:test :moba:desktop:editorTest`

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorInspector.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorInspector.kt
index 44f6cba..0cc9f68 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorInspector.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorInspector.kt
@@ -154,7 +154,7 @@ internal class EditorInspector(
         if (text != null && text != open.sent) {
             open.inFlight = true
             open.sent = text
-            tools.call(UPDATE_EDIT, mapOf("sessionId" to session.toString(), "values" to "${open.key}=$text")) { answer ->
+            tools.call("editor.set_field", mapOf("id" to readFor.joinToString(",") { it.raw.toString() }, "component" to open.key.substringBefore('.'), "field" to open.key.substringAfter('.'), "value" to text)) { answer ->
                 open.inFlight = false
                 open.valid = answer is AgentResult.Ok
                 message = when (answer) {
```

```
exit=1
FAILED InspectorTest > leaving the field commits the typing as one undo entry()
FAILED InspectorTest > Enter commits the typing as one undo entry()
FAILED InspectorTest > a value the field cannot hold is never kept, so the edit is cancelled rather than committed()
FAILED InspectorTest > typing into a field writes every selected entity live through one edit session, with no Set button()
FAILED MobaInspectorTest > two units on different teams show Mixed for their team, and typing one team gives it to both as one undoable edit()
```

### M11: A value the tool refused is committed anyway, not cancelled.

Tests run: `:udea-editor:test :moba:desktop:editorTest`

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorInspector.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorInspector.kt
index 44f6cba..56d8ba0 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorInspector.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorInspector.kt
@@ -176,7 +176,7 @@ internal class EditorInspector(
 
     /** `editor.commit_edit` when the last value sent was written, `editor.cancel_edit` when it was refused. */
     private fun close(open: Typing, session: Int) {
-        val keep = open.valid
+        val keep = true
         tools.call(if (keep) COMMIT_EDIT else CANCEL_EDIT, mapOf("sessionId" to session.toString())) { answer ->
             // Unless the same box is being typed in again already.
             if (typing?.key != open.key) edits.remove(open.key)
```

```
exit=1
FAILED InspectorTest > a value the field cannot hold is never kept, so the edit is cancelled rather than committed()
```

### M12: The pipeline never gives a Scene view its pickable systems. Only the GL test covers this, because the headless tests pass their systems in directly.

Tests run: `:udea-editor:udeaEditorGlTest --tests ...GlScenePickingTest` under xvfb, `-Pudea.render.requireGl=true`

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/RenderPipeline.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/RenderPipeline.kt
index c8039ef..64762d1 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/RenderPipeline.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/RenderPipeline.kt
@@ -195,7 +195,6 @@ public class RenderPipeline internal constructor(
      */
     internal fun open(view: WorldViewport) {
         check(!disposed) { "RenderPipeline has been disposed and cannot open $view" }
-        if (view.camera != null) view.pickBounds = pickBounds
         viewports += view
         view.onClose { viewports.remove(view) }
     }
```

```
exit=1
FAILED GlScenePickingTest > click, Shift-click and box select with the real mouse give the expected selection, and misses select nothing()
```

### M13: Shift is never seen as held, run through the real GLFW keyboard. It fails at the Shift-click assertion (`GlScenePickingTest.kt:135` in `mut/M13.log`). M12 fails at the plain click (`:130`).

Tests run: the same GL run

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorKeys.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorKeys.kt
index 1de83da..bdc893c 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorKeys.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorKeys.kt
@@ -22,8 +22,8 @@ internal class EditorKeys : KeyHandler {
 
     override fun onKey(event: KeyEvent): Boolean {
         shift = when {
-            event.key == Key.Shift -> event.type == KeyEventType.Down
-            else -> event.modifiers.shift
+            event.key == Key.Shift -> false
+            else -> false
         }
         return false
     }
```

```
exit=1
FAILED GlScenePickingTest > click, Shift-click and box select with the real mouse give the expected selection, and misses select nothing()
```

