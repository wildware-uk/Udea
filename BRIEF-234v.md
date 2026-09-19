4424c1d

# BRIEF-234v: the editor's view sits between the panels, at the panels' aspect

Issue #234, reopened. The owner asked for two things:

- "The viewport should be surrounded by the other panels, it looks like they're currently drawn over the top of it, keep it self contained."
- Added later by the lead: "The game panel doesn't have to force an aspect ratio in the editor."

Branch `issue-234-viewport-layout`, from `origin/master`. `origin/master` has been merged in three times, most recently at `6625ec9` (#233); the merges produced no conflicts.

## Evidence command

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew :udea-render:jvmTest :udea-render:udeaGlTest :udea-editor:test :udea-editor:udeaEditorGlTest \
  -Pudea.render.requireGl=true --continue --no-build-cache
```

**Green at `4424c1d`.** 315 testcases were read back from the four result directories. The log is `scratchpad/issue234v/mutations2/C0-control.log`, and all four test tasks executed in it rather than being restored from cache:

```
> Task :udea-editor:test
> Task :udea-render:jvmTest
> Task :udea-editor:udeaEditorGlTest
> Task :udea-render:udeaGlTest
BUILD SUCCESSFUL in 1m 31s
143 actionable tasks: 16 executed, 127 up-to-date
```

**Red when the layout is reverted (M1 below).** `EditorWindow.kt` was replaced by `origin/master`'s copy, which is unchanged since `1239eef`. The run was at `cc73683`, and the log is `mutations2/M1-layout-reverted.log`:

```
exit 1
BUILD FAILED in 1m 30s
FAIL test: EditorLayoutTest > dragging the divider beside the view moves the view's edge with it(): java.util.NoSuchElementException: Collection contains no element matching the predicate.
FAIL test: EditorLayoutTest > the Game tab fills the same gap(): org.opentest4j.AssertionFailedError: the view lies under Rect(left=0.0, top=122.0, right=176.53125, bottom=692.0): view Rect(left=0.0, top=122.0, right=1280.0, bottom=692.0), panels [Rect(left=0.0, to
FAIL test: EditorLayoutTest > a panel floated off its dock gives its room to the view, and docked along the bottom takes room from below(): org.opentest4j.AssertionFailedError: the view does not end at the divider above the Create panel: view Rect(left=0.0, top=122.0, right=1280.0, bottom=692.0), Create panel Rect(left=0.0, top=551.0, rig
FAIL test: EditorLayoutTest > a click just inside the Game tab goes to the game, and one on the panel beside it is the panel's(): org.opentest4j.AssertionFailedError: the window took a click just inside the Game tab
FAIL test: EditorLayoutTest > the Scene tab fills the gap between the docked panels and lies under none of them(): org.opentest4j.AssertionFailedError: the view lies under Rect(left=0.0, top=122.0, right=176.53125, bottom=692.0): view Rect(left=0.0, top=122.0, right=1280.0, bottom=692.0), panels [Rect(left=0.0, to
FAIL test: EditorLayoutTest > a press on a panel beside the Scene tab never moves its camera, and one just inside the view does(): org.opentest4j.AssertionFailedError: a drag just inside the Scene tab did not move its camera
FAIL udeaEditorGlTest: GlEditorLayoutTest > the world is drawn only between the docked panels, and input does not cross the view's edge(): org.opentest4j.AssertionFailedError: the Scene tab's world shows through the panel or divider at Rect(left=0.0, top=122.0, right=176.53125, bottom=692.0) ==> expected: <0> but was: <86538>
testcases read: 290
```

The same command also goes red for each of the ten other mutations in the table below. Every one of them now fails at least one test.

## Summary

**The view is laid out in the gap the panels leave.**
- ComposeGL's `DebugWindowHost` does not publish its dock hole. So `ViewArea` reads back where ComposeGL placed each docked panel (`onPlaced`), and places the Scene/Game page in the largest rectangle no docked panel covers.
- That rectangle is inset by the 6px divider on every side that is not the host's own edge.
- Floating panels take no room.
- Because the view is only ever composed there, a pointer over a panel or divider is the panel's, and a pointer inside the view is the view's.

**The game frame is no longer presented under the editor.** While an editor view is open, `KoolSurface` does not copy the game frame to the window behind the editor's own UI. Before this change, the world showed through the translucent panels (see the "before" shots).

**No fixed aspect.**
- A view takes the size of the `SceneView` it is drawn in: `WorldViewport.resizeTo`, applied by the pipeline at the top of the next frame.
- The Game view is the capturable frame. Resizing it resizes the frame, so the world, the HUD (`CapturedUi` re-lays out at the new size) and `render.screenshot` all follow the Game tab's rectangle.
- The camera adapts on its own: `CameraRig` is an extend viewport.
- `EditorSession` sizes both views to the tabs' shared rectangle, whichever tab is showing, and draws a tab again when its view changed size.

**`PassBlit` re-attaches by texture object, not GL name.** A resized pass gets a new texture, and GL hands out the old texture's name again. So comparing names skipped the re-attach, and the tab showed the old picture in one corner with black around it. This was first seen live. It is now caught by `GlViewResizeTest` (M11 and the image below).

**Decisions (each commented on #234):**
- D1: layout by reading back where ComposeGL placed the panels. Rejected alternatives: changing ComposeGL's API, docking the view as a window, clipping alone.
- D2: no present while a view is open.
- D3: keep the letterbox. D4 superseded this after the owner's aspect rule.
- D4: in an editor, the game frame is resized to the Game tab's rectangle.

**`render.screenshot`: its size is the one thing the aspect change forces.** In an editor, `render.screenshot` returns the Game tab's size (530x570, then 654x570 after a divider drag, live). It is still gizmo-free and panel-free. A game with no editor open never resizes its frame.

**Found on the way:**
- The census gap. `udea-gradle`'s `WallClockBudgetCensusTest` needed rows for the three new files that wait on a render-thread deadline.
- A dead helper. I had written a helper to push each view's viewport after `setSize`. A probe showed Kool 0.19.0 already does it (`before udea-view-scene-1 160x120 Viewport(... 160, 120)`, `after ... 200x300 Viewport(... 200, 300)`, in `scratchpad/issue234v/m7-probe.log`). The helper and its false KDoc are gone, and the passes call `setSize`.

**Master's `GlEditorTabsTest` has no census row.** On `origin/master` it reads `System.nanoTime` with no census row. By reading the census code, it should fail there. I have not run it on master. This branch moves that read into `GlEditorFixture`, which has a row.

**The live editor ran at `3057524`.** Everything since then is:
- tests;
- the dead helper's removal, which behaves the same, per the probe;
- the census rows;
- merges.

## `sh gradlew build`

`ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue`, at `4424c1d` (`scratchpad/issue234v/build-4424c1d.log`):

```
BUILD SUCCESSFUL in 5s
975 actionable tasks: 19 executed, 3 from cache, 953 up-to-date
Configuration cache entry reused.
exit 0
```

That run was almost all up to date. Earlier runs did the real executing, and their logs were overwritten by the next run of the same script, so what follows is prose, not a transcript:
- The run at `022c814` executed 676 tasks and restored 274 from cache. It failed only `:udea-gradle:test > WallClockBudgetCensusTest`, over the three missing census rows.
- The next run, with the rows added, was green with 20 tasks executed.
- The 984 and 975 task totals differ. I have not explained the difference.

## GL, for real

`xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true`, at `4424c1d`, with `--no-build-cache` (`scratchpad/issue234v/gl-4424c1d.log`):

```
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-editor:udeaEditorGlTest
> Task :udea-render:udeaGlTest
BUILD SUCCESSFUL in 1m 46s
117 actionable tasks: 12 executed, 105 up-to-date
```

The result XMLs, as `counts.py` read them straight after that run (`scratchpad/issue234v/gl-4424c1d.counts.txt`). They have to be read straight after: a later `sh gradlew build`, with no display, restores its own skipped results for the same tasks into the same directories.

```
udea-render/build/test-results/udeaGlTest tests 23 failed 0 skipped 0
    GlCaptureDeterminismTest@2026-09-19T11:47:57.710Z
    GlCaptureTest@2026-09-19T11:48:00.863Z
    GlCapturedUiTest@2026-09-19T11:48:04.893Z
    GlFrameResizeTest@2026-09-19T11:48:08.412Z
    GlImportedModelRenderTest@2026-09-19T11:48:10.590Z
    GlKoolInputTest@2026-09-19T11:48:15.912Z
    GlKoolKeyTableTest@2026-09-19T11:48:26.658Z
    GlKoolPointerTest@2026-09-19T11:48:38.410Z
    GlModelRenderTest@2026-09-19T11:48:44.266Z
    GlOverlayIsolationTest@2026-09-19T11:48:51.398Z
    GlSkinnedModelRenderTest@2026-09-19T11:48:54.060Z
    GlUiLayerTest@2026-09-19T11:49:01.342Z
    GlViewPresentTest@2026-09-19T11:49:05.921Z
    GlViewResizeTest@2026-09-19T11:49:09.225Z
    GlViewportOrbitTest@2026-09-19T11:49:11.988Z
    GlWorldViewTest@2026-09-19T11:49:15.222Z
    GlWorldViewportTest@2026-09-19T11:49:20.978Z
    KoolThreadShutdownTest@2026-09-19T11:49:24.364Z
    OffscreenBackendExplodingCaptureTest@2026-09-19T11:49:27.405Z
    OffscreenBackendSecondCreateTest@2026-09-19T11:49:29.832Z
    OffscreenBackendShutdownTest@2026-09-19T11:49:31.074Z
    OffscreenBackendTest@2026-09-19T11:49:33.330Z
udea-agent-host/build/test-results/udeaAgentGlTest tests 2 failed 0 skipped 0
    OffscreenRenderToolsTest@2026-09-19T11:47:56.344Z
    OverlayCaptureIsolationTest@2026-09-19T11:48:01.791Z
udea-editor/build/test-results/udeaEditorGlTest tests 2 failed 0 skipped 0
    GlEditorLayoutTest@2026-09-19T11:47:56.823Z
    GlEditorTabsTest@2026-09-19T11:48:01.448Z
```

## Images

All are in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`.

**Live editor** (`:moba:desktop:runEditor` at `3057524`, Xvfb, grabbed from the X screen):
- `issue234-live-game-tab.png`: the **before**, from round 1. The world shows through the Create, History and Asset panels, and the HUD is cut off at the view's left edge.
- `issue234v-live-after-scene-tab.png`: the Scene tab fills the gap between the panels, and nothing shows behind a panel.
- `issue234v-live-after-game-tab.png`: the Game tab fills the same gap edge to edge, with no bars, and the HUD is laid out inside it.
- `issue234v-live-after-divider-dragged-game.png`: the right divider was dragged about 165px. The Game tab widens and its HUD lays out again at the new width.
- `issue234v-live-after-divider-dragged-scene.png`: the same drag, Scene tab.
- `issue234v-live-render-screenshot-530x570.png` and `issue234v-live-render-screenshot-654x570.png`: `render.screenshot` before and after the drag, at the Game tab's size. They contain no panels and no gizmos.
- `issue234v-live-before-after.png`: the before, the Game tab after, and the two dragged shots, in one collage.

**GL tests** (`6d3df66` unless stated):
- `issue234v-gl-before-world-under-panels.png`: `GlEditorLayoutTest`'s window with the layout reverted. The magenta world shows through every panel.
- `issue234v-gl-after-scene-between-panels.png` and `issue234v-gl-after-game-between-panels.png`: the same test, green. The world is only in the gap and fills it.
- `issue234v-gl-before-after.png`: before and after side by side.
- `issue234v-gl-frame-640x360-before-resize.png` and `issue234v-gl-frame-300x360-after-resize-hud-in-corner.png`: `GlFrameResizeTest`'s capture before and after a Game view resizes the frame. The HUD square follows the new corner.
- `issue234v-gl-view-grown-to-panel.png` and `issue234v-gl-view-resized-again.png`: `GlViewResizeTest`. The view opened at 160x120 grows to its 200x300 panel, then to 260x280, red to the edges with the blue marker in the top-right corner.
- `issue234v-gl-mutant-blit-reads-old-texture.png`: `GlViewResizeTest` under M11, at `cc73683`. The old 160x120 picture sits in one corner with black around it, the live symptom.

## The issue, criterion by criterion

**1. The world view has its own rectangle between the docked panels.**
- `EditorLayoutTest`:
  - the Scene tab fills the gap between the docked panels and lies under none of them;
  - the Game tab fills the same gap;
  - dragging the divider beside the view moves the view's edge with it;
  - a panel floated off its dock gives its room to the view, and docked along the bottom takes room from below.
- `FreeAreaTest`.
- Live: the after shots and the divider-drag shots.

**2. No panel draws over it, and the world is not visible behind any panel.** This is checked in GL pixels.
- `GlEditorLayoutTest`, for both tabs:
  - no world pixel under any panel or divider;
  - the view is all world, edge to edge;
  - the world reaches the view's edges;
  - no world pixel outside the view.
- `GlViewPresentTest`: the frame is not presented to the window while a view is open, and is presented again once it closes.
- Images: the GL before/after, and the live before/after.

**3. Input on a panel never reaches the view, and input in the view never reaches a panel.**
- `EditorLayoutTest`:
  - a press on a panel beside the Scene tab never moves its camera, and one just inside the view does;
  - a click just inside the Game tab goes to the game, and one on the panel beside it is the panel's.
- `GlEditorLayoutTest`, the Game tab in GL: a click just inside reaches the game (1 press), and a click on the History panel beside it does not (0).

**4. Both tabs fill their whole rectangle at its aspect, with no letterbox or pillarbox.**
- `GlEditorLayoutTest`: each tab's view is all world, edge to edge, at the gap's aspect.
- `GlViewResizeTest`: a view grows to its panel and fills it, corner marker included, and again after a second resize.
- `GlFrameResizeTest`.
- `WorldViewportTest`:
  - a Game view resizes the capturable frame;
  - a Scene view resizes only itself;
  - sizes of zero or less are ignored.
- Live: `issue234v-live-after-game-tab.png`.

**5. The game camera's projection adapts.**
- `GlEditorLayoutTest` drives a real `CameraRig` over a fixed-size world, and asserts that the world reaches the view's left and right edges at the gap's aspect.
- `GlFrameResizeTest`: world at every corner of the resized frame.
- Live: the Game tab after the drag shows more world across, not a stretched picture.

**6. `render.screenshot` behaviour is kept unless the change forces it, and this brief says which.**
- It is forced, and only in its size. That is D4.
- `GlEditorLayoutTest` checks that `render.screenshot` is the tabs' rectangle while the Scene tab shows, and again while the Game tab shows (the M10 row).
- `GlFrameResizeTest`.
- Live: 530x570, then 654x570 after the drag. `scratchpad/issue234v/live-capture-sizes.txt` holds the sizes read from the files:
  - `cap_0002` and `cap_0003` are the two `render.screenshot` calls;
  - `cap_0004` and `cap_0005` are the two `editor.screenshot` calls, with `view=scene` and `view=game`;
  - `cap_0000` and `cap_0001` came from an earlier launch that day.
- It holds no gizmo: `GlEditorTabsTest`, and `udea-agent-host`'s `OverlayCaptureIsolationTest`, both green in the GL run above.

**7. Every behaviour from #234 stays green.** All of these are in `sh gradlew build` and the GL run above:
- `GlEditorTabsTest`: `render.screenshot` never contains gizmos, and `editor.screenshot(view=scene|game)` works.
- `EditorLayoutTest` and `GlEditorLayoutTest`: Game-tab clicks reach the game.
- `EditorTabsTest` and `GlViewportOrbitTest`: editor camera pan, zoom and orbit, and wheel direction.

## Mutation table

Every row is scripted by `scratchpad/issue234v/mutate2.sh`:
- it applies one mutation and saves `git diff`;
- it runs the evidence command's four tasks and lists every failing testcase from the result XMLs;
- it reverts with `git checkout`.

The runs were at `cc73683`, the last code change. The diffs, logs and failure lists are in `scratchpad/issue234v/mutations2/`. The control run at `cc73683` was green, with 290 testcases.

### M1: The layout reverted: `EditorWindow.kt` as on `origin/master` (`git show origin/master:<path> > <path>`).

`mutations2/M1-layout-reverted.diff`:

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorWindow.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorWindow.kt
index 4f1c574..bc418f1 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorWindow.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorWindow.kt
@@ -21,11 +21,8 @@ import dev.wildware.composegl.ui.modifier.background
 import dev.wildware.composegl.ui.modifier.fillMaxSize
 import dev.wildware.composegl.ui.modifier.fillMaxWidth
 import dev.wildware.composegl.ui.modifier.height
-import dev.wildware.composegl.ui.modifier.offset
-import dev.wildware.composegl.ui.modifier.onPlaced
 import dev.wildware.composegl.ui.modifier.onSizeChanged
 import dev.wildware.composegl.ui.modifier.padding
-import dev.wildware.composegl.ui.modifier.size
 import dev.wildware.composegl.ui.modifier.testTag
 import dev.wildware.composegl.ui.modifier.weight
 import dev.wildware.composegl.ui.widget.Button
@@ -37,8 +34,8 @@ import dev.wildware.composegl.ui.widget.Text
 import dev.wildware.udea.render.view.ViewDimension
 
 /**
- * The editor window's layout: a menu bar, the toolbar, the Scene and Game headings, the Create, Asset
- * and History panels docked round the showing tab, and a status line.
+ * The editor window's layout: a menu bar, the toolbar, the Scene and Game headings, the showing tab
+ * with the Create, Asset and History panels docked over its edges, and a status line.
  *
  * The panels are ComposeGL's own docked windows (`DebugWindowHost`, from `composegl-debug`): they can
  * be dragged off, tabbed together and re-docked, and the dividers between them resized. Their layout
@@ -67,33 +64,24 @@ internal fun EditorWindow(session: EditorSession) {
     }
 }
 
-/**
- * The docked panels, and the showing tab in the gap between them ([ViewArea]): the world is drawn only
- * there, so no panel lies over it and it shows behind none of them.
- */
+/** The Scene and Game tabs, and the panels docked over their edges. */
 @Composable
 private fun Panels(session: EditorSession) {
     val windows = rememberDebugWindowsState(remember { MemoryDebugWindowStore() })
     remember(windows) {
-        windows.dockToScreen(EditorTags.CREATE_PANEL, DockSide.Left)
-        windows.dockToScreen(EditorTags.HISTORY_PANEL, DockSide.Right)
-        windows.dockToScreen(EditorTags.ASSET_PANEL, DockSide.Right)
+        windows.dockToScreen(CREATE, DockSide.Left)
+        windows.dockToScreen(HISTORY, DockSide.Right)
+        windows.dockToScreen(ASSET, DockSide.Right)
     }
-    val area = remember { ViewArea() }
-    DebugWindowHost(Modifier.fillMaxSize().onPlaced(area.host), state = windows) {
-        // The view in the gap the docked panels leave, never under them: it draws only there, and a
-        // pointer over a panel or a divider is the panel's, never the view's.
-        val free = area.free(windows::isDocked)
-        Box(Modifier.offset(free.left, free.top).size(free.width, free.height)) {
-            ViewPage(session)
-        }
-        DebugWindow("Create", id = EditorTags.CREATE_PANEL, modifier = Modifier.onPlaced(area.pane(EditorTags.CREATE_PANEL))) {
+    DebugWindowHost(Modifier.fillMaxSize(), state = windows) {
+        ViewPage(session)
+        DebugWindow("Create", id = CREATE) {
             Button(session.spawnLabel, onClick = { session.spawn() }, modifier = Modifier.fillMaxWidth().testTag(EditorTags.SPAWN))
         }
-        DebugWindow("Asset", id = EditorTags.ASSET_PANEL, modifier = Modifier.onPlaced(area.pane(EditorTags.ASSET_PANEL))) {
+        DebugWindow("Asset", id = ASSET) {
             AssetPanel(session.assets)
         }
-        DebugWindow("History", id = EditorTags.HISTORY_PANEL, modifier = Modifier.onPlaced(area.pane(EditorTags.HISTORY_PANEL))) {
+        DebugWindow("History", id = HISTORY) {
             Column(Modifier.fillMaxWidth()) {
                 Button("Undo", onClick = { session.undo() }, modifier = Modifier.fillMaxWidth().testTag(EditorTags.UNDO))
                 Column(Modifier.fillMaxWidth().padding(top = GAP).testTag(EditorTags.HISTORY)) {
@@ -113,8 +101,8 @@ private fun Panels(session: EditorSession) {
  * The Scene and Game tabs' headings (issue #234), and the showing tab's own switch: the 2D / 3D camera
  * in the Scene tab, the gizmo overlay in the Game tab.
  *
- * A row of its own under the toolbar, above the docked panels, so a panel docked along the top can
- * never cover a heading. Not ComposeGL's `Tabs`, whose headings carry no
+ * A row of its own under the toolbar, rather than over the page: the docked panels lie over the page's
+ * edges, and a heading under one could not be clicked. Not ComposeGL's `Tabs`, whose headings carry no
  * test tag a test or an agent's UI driver could find.
  */
 @Composable
@@ -187,7 +175,7 @@ private fun TabHeading(title: String, tab: EditorTab, session: EditorSession, ta
     )
 }
 
-/** The window's backdrop, and what a tab shows around the world for the one frame before its view takes the tab's size. */
+/** The window's backdrop, and the letterbox bars around the world. */
 private val Background: Colour = Colour.rgb(0x1B1F27)
 
 /** The Scene and Game headings' row, in design units: a button and its padding. */
@@ -195,3 +183,8 @@ private const val TAB_ROW_HEIGHT: Float = 44f
 
 /** The space between a panel's parts, in design units. */
 private const val GAP: Float = 8f
+
+/** The docked windows' ids, which the dock layout is keyed by. */
+private const val CREATE: String = "editor-create"
+private const val HISTORY: String = "editor-history"
+private const val ASSET: String = "editor-asset"
```

`mutations2/M1-layout-reverted.failures.txt`:

```
exit 1
BUILD FAILED in 1m 30s
FAIL test: EditorLayoutTest > dragging the divider beside the view moves the view's edge with it(): java.util.NoSuchElementException: Collection contains no element matching the predicate.
FAIL test: EditorLayoutTest > the Game tab fills the same gap(): org.opentest4j.AssertionFailedError: the view lies under Rect(left=0.0, top=122.0, right=176.53125, bottom=692.0): view Rect(left=0.0, top=122.0, right=1280.0, bottom=692.0), panels [Rect(left=0.0, to
FAIL test: EditorLayoutTest > a panel floated off its dock gives its room to the view, and docked along the bottom takes room from below(): org.opentest4j.AssertionFailedError: the view does not end at the divider above the Create panel: view Rect(left=0.0, top=122.0, right=1280.0, bottom=692.0), Create panel Rect(left=0.0, top=551.0, rig
FAIL test: EditorLayoutTest > a click just inside the Game tab goes to the game, and one on the panel beside it is the panel's(): org.opentest4j.AssertionFailedError: the window took a click just inside the Game tab
FAIL test: EditorLayoutTest > the Scene tab fills the gap between the docked panels and lies under none of them(): org.opentest4j.AssertionFailedError: the view lies under Rect(left=0.0, top=122.0, right=176.53125, bottom=692.0): view Rect(left=0.0, top=122.0, right=1280.0, bottom=692.0), panels [Rect(left=0.0, to
FAIL test: EditorLayoutTest > a press on a panel beside the Scene tab never moves its camera, and one just inside the view does(): org.opentest4j.AssertionFailedError: a drag just inside the Scene tab did not move its camera
FAIL udeaEditorGlTest: GlEditorLayoutTest > the world is drawn only between the docked panels, and input does not cross the view's edge(): org.opentest4j.AssertionFailedError: the Scene tab's world shows through the panel or divider at Rect(left=0.0, top=122.0, right=176.53125, bottom=692.0) ==> expected: <0> but was: <86538>
testcases read: 290
```

### M2: The game frame presented under the editor again.

`mutations2/M2-frame-presented-under-editor.diff`:

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolSurface.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolSurface.kt
index 134b9e0..c859414 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolSurface.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolSurface.kt
@@ -252,7 +252,6 @@ internal class KoolSurface(
         presentBatch.clear()
         // An editor shows the world in its views and nowhere else (issue #234): a frame presented
         // under its window as well would show through wherever the window's panels are not opaque.
-        if (openViews > 0) return
 
         // Letterboxed rather than stretched: a window of another aspect ratio shows the frame the
         // agent captures, at the same shape, with bars.
```

`mutations2/M2-frame-presented-under-editor.failures.txt`:

```
exit 1
BUILD FAILED in 2m 28s
FAIL udeaEditorGlTest: GlEditorLayoutTest > the world is drawn only between the docked panels, and input does not cross the view's edge(): org.opentest4j.AssertionFailedError: the Scene tab's world does not reach its bottom edge: it ends at 719. Expected <692.0> with absolute tolerance <8.0>, actual <720.0>.
FAIL udeaGlTest: GlViewPresentTest > the window shows the frame until an editor view opens, and again once it closes(): org.opentest4j.AssertionFailedError: with an editor view open the frame was still presented to the window: #FF0000 ==> expected: <0> but was: <16711680>
testcases read: 290
```

### M3: No room left for the divider beside a panel.

`mutations2/M3-no-divider-inset.diff`:

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ViewArea.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ViewArea.kt
index 81ecb49..fbb4d0a 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ViewArea.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ViewArea.kt
@@ -48,7 +48,7 @@ internal class ViewArea {
         val occupied = panes.entries
             .filter { (id, bounds) -> docked(id) && !bounds.isEmpty }
             .map { (_, bounds) -> Rect(bounds.left - origin.left, bounds.top - origin.top, bounds.right - origin.left, bounds.bottom - origin.top) }
-        return freeArea(local, occupied, DIVIDER)
+        return freeArea(local, occupied, 0f)
     }
 
     override fun toString(): String = "ViewArea(host=$hostBounds, panes=${panes.toMap()})"
```

`mutations2/M3-no-divider-inset.failures.txt`:

```
exit 1
BUILD FAILED in 14s
FAIL test: EditorLayoutTest > dragging the divider beside the view moves the view's edge with it(): java.util.NoSuchElementException: Collection contains no element matching the predicate.
FAIL test: EditorLayoutTest > the Game tab fills the same gap(): org.opentest4j.AssertionFailedError: the view lies under Rect(left=712.125, top=122.0, right=718.125, bottom=692.0): view Rect(left=176.53125, top=122.0, right=718.125, bottom=692.0), panels [Rect(lef
FAIL test: EditorLayoutTest > a panel floated off its dock gives its room to the view, and docked along the bottom takes room from below(): org.opentest4j.AssertionFailedError: the view does not end at the divider above the Create panel: view Rect(left=0.0, top=122.0, right=718.125, bottom=551.0), Create panel Rect(left=0.0, top=551.0, ri
FAIL test: EditorLayoutTest > a click just inside the Game tab goes to the game, and one on the panel beside it is the panel's(): org.opentest4j.AssertionFailedError: the window took a click just inside the Game tab
FAIL test: EditorLayoutTest > the Scene tab fills the gap between the docked panels and lies under none of them(): org.opentest4j.AssertionFailedError: the view lies under Rect(left=712.125, top=122.0, right=718.125, bottom=692.0): view Rect(left=176.53125, top=122.0, right=718.125, bottom=692.0), panels [Rect(lef
FAIL test: EditorLayoutTest > a press on a panel beside the Scene tab never moves its camera, and one just inside the view does(): org.opentest4j.AssertionFailedError: a drag just inside the Scene tab did not move its camera
FAIL udeaEditorGlTest: GlEditorLayoutTest > the world is drawn only between the docked panels, and input does not cross the view's edge(): org.opentest4j.AssertionFailedError: the Scene tab's view at Rect(left=176.53125, top=122.0, right=718.125, bottom=692.0) is not all world: it has bars, or something over it ==> expected: <299160> but
testcases read: 290
```

### M4: Floating panels counted as docked.

`mutations2/M4-floating-panels-count-as-docked.diff`:

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ViewArea.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ViewArea.kt
index 81ecb49..f42a7ac 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ViewArea.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/ViewArea.kt
@@ -46,7 +46,7 @@ internal class ViewArea {
         val origin = hostBounds
         val local = Rect(0f, 0f, origin.width, origin.height)
         val occupied = panes.entries
-            .filter { (id, bounds) -> docked(id) && !bounds.isEmpty }
+            .filter { (id, bounds) -> !bounds.isEmpty }
             .map { (_, bounds) -> Rect(bounds.left - origin.left, bounds.top - origin.top, bounds.right - origin.left, bounds.bottom - origin.top) }
         return freeArea(local, occupied, DIVIDER)
     }
```

`mutations2/M4-floating-panels-count-as-docked.failures.txt`:

```
exit 1
BUILD FAILED in 16s
FAIL test: EditorLayoutTest > a panel floated off its dock gives its room to the view, and docked along the bottom takes room from below(): org.opentest4j.AssertionFailedError: the floating Create panel took room from the view's top. Expected <122.0> with absolute tolerance <1.0>, actual <223.5>.
testcases read: 290
```

### M5: A Game view no longer resizes the capturable frame.

`mutations2/M5-game-view-leaves-frame-size.diff`:

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/RenderPipeline.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/RenderPipeline.kt
index 7d7912a..7b66950 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/RenderPipeline.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/RenderPipeline.kt
@@ -213,7 +213,6 @@ public class RenderPipeline internal constructor(
         for (index in viewports.indices) {
             val view = viewports[index]
             if (!view.wantsResize) continue
-            if (view.camera == null) resizeFrame(view.wantedWidth, view.wantedHeight)
             view.applySize()
         }
     }
```

`mutations2/M5-game-view-leaves-frame-size.failures.txt`:

```
exit 1
BUILD FAILED in 1m 33s
FAIL udeaEditorGlTest: GlEditorLayoutTest > the world is drawn only between the docked panels, and input does not cross the view's edge(): org.opentest4j.AssertionFailedError: render.screenshot is not the Scene tab's width. Expected <529.59375> with absolute tolerance <1.0>, actual <1280.0>.
FAIL udeaGlTest: GlFrameResizeTest > a Game view's size becomes the frame's, with the world and the HUD fitted to it(): org.opentest4j.AssertionFailedError: render.screenshot is not the Game view's width ==> expected: <300> but was: <640>
FAIL jvmTest: WorldViewportTest > a Game view resizes the capturable frame to its own size before the next frame draws()[jvm]: org.opentest4j.AssertionFailedError: the surface was not resized to the Game view's size ==> expected: <[300x360]> but was: <[]>
testcases read: 290
```

### M6: The HUD keeps the layout of the size it was built at.

`mutations2/M6-hud-keeps-launch-layout.diff`:

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/ui/CapturedUi.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/ui/CapturedUi.kt
index 3045ea6..0d8f2e0 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/ui/CapturedUi.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/ui/CapturedUi.kt
@@ -102,7 +102,6 @@ public class CapturedUi internal constructor(
     private fun draw() {
         val width = passes.frameWidth.toFloat()
         val height = passes.frameHeight.toFloat()
-        if (viewport.design.width != width || viewport.design.height != height) viewport = viewportOf(passes.frameWidth, passes.frameHeight)
         renderer.render(viewport, clock.nanoTime())
     }
 
```

`mutations2/M6-hud-keeps-launch-layout.failures.txt`:

```
exit 1
BUILD FAILED in 1m 24s
FAIL udeaGlTest: GlFrameResizeTest > a Game view's size becomes the frame's, with the world and the HUD fitted to it(): org.opentest4j.AssertionFailedError: the HUD's corner square is not in the new frame's corner ==> expected: <51283> but was: <255>
testcases read: 290
```

### M7: A view's Kool pass never resized.

`mutations2/M7-view-pass-never-resized.diff`:

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/ViewportPass.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/ViewportPass.kt
index 3608a85..3c6b238 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/ViewportPass.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/ViewportPass.kt
@@ -55,7 +55,6 @@ internal class ViewportPass(
 
     /** Makes the picture [width] x [height] pixels from the next time Kool draws it. */
     fun resize(width: Int, height: Int) {
-        pass.setSize(width, height)
     }
 
     /** Draws this pass after [other]. */
```

`mutations2/M7-view-pass-never-resized.failures.txt`:

```
exit 1
BUILD FAILED in 1m 30s
FAIL udeaEditorGlTest: GlEditorLayoutTest > the world is drawn only between the docked panels, and input does not cross the view's edge(): org.opentest4j.AssertionFailedError: the Scene tab's view at Rect(left=182.53125, top=122.0, right=712.125, bottom=692.0) is not all world: it has bars, or something over it ==> expected: <292512> but
FAIL udeaGlTest: GlViewResizeTest > a view resized to its SceneView fills it, and is still shown after each resize(): org.opentest4j.AssertionFailedError: the 200x300 view is not shown at (103, 43): #000000 ==> expected: <16711680> but was: <0>
testcases read: 290
```

### M8: A tab not drawn again when its view changes size.

`mutations2/M8-no-redraw-on-resize.diff`:

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt
index 893893a..58630be 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt
@@ -145,7 +145,7 @@ public class EditorSession(
         if (historyStale && !historyPending) readHistory()
         // Both asked every frame: each keeps what it last saw.
         val resized = resized()
-        val due = redraw.due(tick(), completed) || resized
+        val due = redraw.due(tick(), completed)
         if (navigation.consumeMoved()) sceneMoved = SCENE_MOVED_FRAMES
         if (due || sceneMoved > 0) sceneState.invalidate()
         if (due) gameState.invalidate()
```

`mutations2/M8-no-redraw-on-resize.failures.txt`:

```
exit 1
BUILD FAILED in 15s
FAIL udeaEditorGlTest: GlEditorLayoutTest > the world is drawn only between the docked panels, and input does not cross the view's edge(): org.opentest4j.AssertionFailedError: the Scene tab's view at Rect(left=182.53125, top=122.0, right=712.125, bottom=692.0) is not all world: it has bars, or something over it ==> expected: <292512> but
testcases read: 290
```

### M9: `drawInto` no longer asks for its picture's size.

`mutations2/M9-drawInto-asks-no-size.diff`:

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/view/WorldViewport.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/view/WorldViewport.kt
index 3058b5d..0e53d15 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/view/WorldViewport.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/view/WorldViewport.kt
@@ -108,7 +108,6 @@ public class WorldViewport internal constructor(
     public fun drawInto(scene: SceneDrawScope) {
         val width = scene.width
         val height = scene.height
-        resizeTo(width, height)
         val pass = kool ?: return
         scene.raw { pass.blitInto(width, height) }
     }
```

`mutations2/M9-drawInto-asks-no-size.failures.txt`:

```
exit 1
BUILD FAILED in 1m 30s
FAIL udeaGlTest: GlViewResizeTest > a view resized to its SceneView fills it, and is still shown after each resize(): org.opentest4j.AssertionFailedError: the view did not take its SceneView's size ==> expected: <(200, 300)> but was: <(160, 120)>
testcases read: 290
```

### M10: The editor sizes neither view (only the showing tab's own `drawInto` does).

`mutations2/M10-editor-sizes-no-view.diff`:

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt
index 893893a..e8ef925 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorSession.kt
@@ -233,8 +233,6 @@ public class EditorSession(
      * `render.screenshot` reads whichever tab a person is looking at.
      */
     internal fun drawView(tab: EditorTab, scope: SceneDrawScope) {
-        views.scene.resizeTo(scope.width, scope.height)
-        views.game.resizeTo(scope.width, scope.height)
         when (tab) {
             EditorTab.Scene -> views.scene.drawInto(scope)
             EditorTab.Game -> views.game.drawInto(scope)
```

`mutations2/M10-editor-sizes-no-view.failures.txt`:

```
exit 1
BUILD FAILED in 57s
FAIL udeaEditorGlTest: GlEditorLayoutTest > the world is drawn only between the docked panels, and input does not cross the view's edge(): org.opentest4j.AssertionFailedError: render.screenshot is not the Scene tab's width. Expected <529.59375> with absolute tolerance <1.0>, actual <1280.0>.
testcases read: 290
```

### M11: `PassBlit` compares GL texture names, the shape before `3057524`.

`mutations2/M11-blit-compares-gl-names.diff`:

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/PassBlit.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/PassBlit.kt
index 926dde8..1e7ded8 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/PassBlit.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/PassBlit.kt
@@ -49,7 +49,7 @@ internal class PassBlit(
         val read = framebuffer ?: gl.createFramebuffer().also { framebuffer = it }
         val previousRead = gl.getInteger(READ_FRAMEBUFFER_BINDING)
         gl.bindFramebuffer(gl.READ_FRAMEBUFFER, read)
-        if (attached !== colour) {
+        if (attached?.glTexture != colour.glTexture) {
             gl.framebufferTexture2D(gl.READ_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, colour.glTexture, 0)
             attached = colour
         }
```

`mutations2/M11-blit-compares-gl-names.failures.txt`:

```
exit 1
BUILD FAILED in 1m 37s
FAIL udeaGlTest: GlViewResizeTest > a view resized to its SceneView fills it, and is still shown after each resize(): org.opentest4j.AssertionFailedError: the 200x300 view is not shown at (200, 190): #000000 ==> expected: <16711680> but was: <0>
testcases read: 290
```


## Regenerated files

None. No replicated component was added or removed. `git diff origin/master -- udea-codegen/` is empty, so `net-protocol.lock` and `expected-generated-hashes.txt` are as on master.
