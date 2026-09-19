02e0a19

# BRIEF-234: Scene and Game tabs, editor camera, gizmos kept out of captures

Branch `issue-234-scene-game-tabs`. It was cut from `origin/kmp` at 2046c79 and then merged with `origin/kmp` at 117f4c8 (#241). The merge touched no file this branch touches.

## Evidence command

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew :udea-render:udeaGlTest --tests '*GlWorldViewportTest' :udea-editor:udeaEditorGlTest -Pudea.render.requireGl=true
```

This command runs one real Kool frame. That frame draws the capturable picture, the Game tab and the Scene tab, with a placeholder gizmo drawn in both tabs. The command then reads all three pictures back and asserts:

- the three pictures carry the same tick;
- the capture has no gizmo pixel;
- the Game tab is the capture plus the gizmo;
- the Scene tab has the world where `EditorCamera.project` puts it;
- through the real editor window, a click on the Game tab's gizmo reaches the game's `KoolPointer` and not the gizmo, while the same click in the Scene tab reaches the gizmo.

**Proof it goes red:** mutation M1 is the exclusion reverted. It makes a view's batch write into the capturable record. With M1 applied, both tests in this command fail (`render.screenshot holds gizmo pixels ==> expected: <0> but was: <288>`), as the table below shows.

## Summary

- **`udea-render`: views.** `KoolBackend.openSceneView(EditorCamera)` and `openGameView()` each return a `WorldViewport`. A `WorldViewport` has its own Kool pass, its own `SpriteRecord` and its own capture slot, and it sits next to the capturable pass rather than inside it.
  - **Game view.** It copies the capturable frame, then draws gizmos only when `showGizmos` is on.
  - **Scene view.** The pipeline runs every render system except the `UI` phase a second time, into the view's record, with each `CameraRig`'s projection swapped for the editor camera's. This covers `ModelRenderSystem` too: through `ModelStage.imageFor`, it adds a second pass over the same model nodes, aimed by the editor's orbit (decision D1, already commented).
  - **Why the exclusion is structural.** A gizmo can only be drawn through a view's own batch. Only that view's pass reads that batch's record, and `render.screenshot` reads only the capturable pass.
- **`EditorCamera`.** It is presentation state only: no `Tick`, and nothing the simulation reads.
  - In 2D it pans and zooms, keeping the point under the pointer fixed.
  - In 3D it orbits round a centre with Z up, pans the centre, and dollies.
  - It starts from the game's framing: `CameraRig` in 2D, `ModelCamera` in 3D.
  - `project()` is written out by hand, so hit-testing and drawing can be checked against each other.
- **The gizmo seam.** `GizmoLayer` (`draw`, `press`) and `GizmoCanvas` (`project`, `fill`). Only tests implement it; G2's API was not started.
- **`udea-editor`.**
  - A row of Scene and Game headings, a 2D/3D switch in the Scene tab, and a "Gizmos" checkbox in the Game tab.
  - The Scene `SceneView` takes every pointer event (`SceneNavigation`): a gizmo press first, then the camera.
  - The Game `SceneView` has no pointer handler, which is what hands its input to the game.
  - `EditorSession` takes an `EditorViews`. `EditorViews.detached()` (built on `WorldViewport.detached`) is the version with no GL behind it, for headless tests.
  - A new `udeaEditorGlTest` task, on `check`, with `forkEvery = 1`. It is also added to the CI GL job and its "nothing skipped" gate.
- **`editor.screenshot(view = scene|game)`.** It lives in `udea-agent-host` (`EditorViewToolset`) and captures a view's own pass, so gizmos are included.
  - It is bound late: the editor window opens its views after the tool index is built.
  - It answers `no_editor_window` until the views are bound, and `no_render_context` in Headless.
  - `render.screenshot` and `editor.screenshot` now share `CaptureFiling`, so their capture, poll and file steps are one piece of code, and the Deferred-to-Future adapter is shared too (`asCaptureFrame`).
- **`moba`.**
  - `MobaLaunch.Rendering.world` is replaced by `sceneView` and `gameView`.
  - `MobaEditor` opens both views and binds them to `editor.screenshot`.
  - `MobaAgent.attach` registers `editor.screenshot` when the instance is an editor.
- **Layout.** I did not lay the views out between the panels, and the reason is on the issue: a `DebugWindow` body scrolls. The headings got their own row, because inside the docked area the Create panel covered them.
- **Decisions commented on #234:** D1 (the Scene view re-runs the render systems), the gizmo seam, where `editor.screenshot` lives, the layout, and input routing (pointer only, the keyboard is unchanged, Game-tab gizmos project in 2D only).
- **Found by driving the live editor:** the scroll wheel zoomed the wrong way round (ComposeGL reports a wheel turned away from the user as a negative `delta.y`). I fixed it, pinned it in `EditorTabsTest` (M8), and checked it live again (image below).

## `sh gradlew build --continue`, after merging origin/kmp 117f4c8

The saved log is `scratchpad/issue234/build-merged.log`. These are its last three lines:

```
BUILD SUCCESSFUL in 2m
959 actionable tasks: 606 executed, 271 from cache, 82 up-to-date
Configuration cache entry stored.
```

That is 959 tasks, against the baseline's 958. The one extra task is `:udea-editor:udeaEditorGlTest`. No task failed.

## GL: every GL suite under xvfb, after the merge

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true --rerun-tasks
```

The last lines of `gl-merged.log`:

```
BUILD SUCCESSFUL in 1m 39s
126 actionable tasks: 126 executed
Configuration cache entry stored.
```

The `<testsuite>` lines from the result XML (`gl-suites-merged.txt`):

```
render.gl.GlKoolInputTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.GlImportedModelRenderTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.OffscreenBackendShutdownTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.GlKoolPointerTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.GlCaptureTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.GlKoolKeyTableTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.OffscreenBackendSecondCreateTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.GlOverlayIsolationTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.GlCaptureDeterminismTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.GlModelRenderTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.GlCapturedUiTest" tests="1" skipped="0" failures="0" errors="0"
agent.host.gl.OffscreenRenderToolsTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.OffscreenBackendTest" tests="2" skipped="0" failures="0" errors="0"
render.gl.OffscreenBackendExplodingCaptureTest" tests="1" skipped="0" failures="0" errors="0"
editor.gl.GlEditorTabsTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.GlViewportOrbitTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.GlWorldViewportTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.GlUiLayerTest" tests="1" skipped="0" failures="0" errors="0"
agent.host.gl.OverlayCaptureIsolationTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.KoolThreadShutdownTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.GlWorldViewTest" tests="1" skipped="0" failures="0" errors="0"
```

## Mutations

Each diff below is the literal `git diff` of a clean committed tree with only that mutation applied, taken by `scratchpad/issue234/mutate.py`. Each run was:

- `:udea-render:udeaGlTest` (GlWorldViewportTest, GlViewportOrbitTest);
- `:udea-render:jvmTest` (WorldViewportTest, EditorCameraTest);
- `:udea-editor:test`;
- `:udea-editor:udeaEditorGlTest`;
- `:udea-agent-host:test` (EditorViewToolsetTest, RenderToolsetTest).

The failures are read from the JUnit XML, which is deleted before each run. The control is the same run with no mutation:

```
exit 0, 59 test cases in the result XML, 0 failing
```

The runs were made at ec208dc, before the merge; the merge did not touch these files. The diffs below are shown with their `index` lines as `git diff` wrote them.

### M1-view-records-into-capturable

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolSurface.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolSurface.kt
index e504757..f30a01b 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolSurface.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolSurface.kt
@@ -172,7 +172,7 @@ internal class KoolSurface(
         val record = SpriteRecord()
         val kool = ViewportPass(record, width, height, name, scene)
         if (camera == null) kool.dependsOn(pass)
-        val batch = SpriteBatch2D(SpriteTexture.whitePixel("$name-white"), home = record)
+        val batch = SpriteBatch2D(SpriteTexture.whitePixel("$name-white"), home = offscreenBatch.home)
         val view = WorldViewport(
             camera = camera,
             target = OffscreenTarget(width, height),
```

`mutations/M1-view-records-into-capturable.failures.txt`:

```
exit 1, 59 test cases in the result XML, 2 failing
udea-render:udeaGlTest  GlWorldViewportTest > both tabs draw one tick through two cameras, and a gizmo drawn in both never reaches a capture()
    org.opentest4j.AssertionFailedError: render.screenshot holds gizmo pixels ==> expected: <0> but was: <288>
udea-editor:udeaEditorGlTest  GlEditorTabsTest > a click on a gizmo in the Game tab reaches the game, and the same click in the Scene tab is the gizmo's()
    org.opentest4j.AssertionFailedError: the Game tab's overlay drew no gizmo
```

### M2-rig-ignores-view-camera

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/camera/CameraRig.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/camera/CameraRig.kt
index 6cf7424..083849e 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/camera/CameraRig.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/camera/CameraRig.kt
@@ -178,7 +178,6 @@ public class CameraRig(
     internal fun enterView(view: Projection2D) {
         check(!inView) { "$this is already drawing a view" }
         gameProjection.set(projection)
-        projection.set(view)
         inView = true
     }
 
```

`mutations/M2-rig-ignores-view-camera.failures.txt`:

```
exit 1, 59 test cases in the result XML, 2 failing
udea-render:udeaGlTest  GlWorldViewportTest > both tabs draw one tick through two cameras, and a gizmo drawn in both never reaches a capture()
    org.opentest4j.AssertionFailedError: the Scene tab has no red square where its camera projects it ==> expected: <16711680> but was: <0>
udea-render:jvmTest  WorldViewportTest > the Scene view draws the same world through its own camera, and leaves the HUD out()[jvm]
    org.opentest4j.AssertionFailedError: the Scene view's marker did not follow its own camera: expected 420.0, was 320.0
```

### M3-model-view-aims-game-camera

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
index 28decc0..a6be01b 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
@@ -164,7 +164,7 @@ internal class ModelStage(
         editor.adopt(game)
         val seen = views.getOrPut(view) { ViewPass(view) }
         editor.writeOrbit(orbit)
-        aim(seen.camera, orbit)
+        aim(seen.camera, game)
         return seen.image
     }
 
```

`mutations/M3-model-view-aims-game-camera.failures.txt`:

```
exit 1, 59 test cases in the result XML, 1 failing
udea-render:udeaGlTest  GlViewportOrbitTest > the Scene view orbits round the game's centre, Z up, where its projection says it does()
    org.opentest4j.AssertionFailedError: after the orbit, no cube where the projection puts it: ViewPoint(219.01562, 61.47304)
```

### M4-model-y-up

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
index 28decc0..c163d43 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
@@ -172,7 +172,7 @@ internal class ModelStage(
     private fun aim(camera: PerspectiveCamera, view: ModelCamera) {
         eye.set(view.eyeX, view.eyeY, view.eyeZ)
         target.set(view.targetX, view.targetY, view.targetZ)
-        camera.setupCamera(position = eye, up = Vec3f.Z_AXIS, lookAt = target)
+        camera.setupCamera(position = eye, up = Vec3f.Y_AXIS, lookAt = target)
         camera.fovY = view.fovYDegrees.deg
         camera.setClipRange(view.near, view.far)
     }
```

`mutations/M4-model-y-up.failures.txt`:

```
exit 1, 59 test cases in the result XML, 1 failing
udea-render:udeaGlTest  GlViewportOrbitTest > the Scene view orbits round the game's centre, Z up, where its projection says it does()
    org.opentest4j.AssertionFailedError: after the orbit, no cube where the projection puts it: ViewPoint(219.01562, 61.47304)
```

### M5-game-tab-takes-its-pointer

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorWindow.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorWindow.kt
index bc418f1..bdd6498 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorWindow.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/EditorWindow.kt
@@ -157,7 +157,7 @@ private fun ViewPage(session: EditorSession) {
             session.drawView(EditorTab.Scene, this)
         }
 
-        EditorTab.Game -> SceneView(session.gameState, Modifier.fillMaxSize().testTag(EditorTags.GAME_VIEW)) {
+        EditorTab.Game -> SceneView(session.gameState, Modifier.fillMaxSize().testTag(EditorTags.GAME_VIEW), onPointer = { true }) {
             clear(Background)
             session.drawView(EditorTab.Game, this)
         }
```

`mutations/M5-game-tab-takes-its-pointer.failures.txt`:

```
exit 1, 59 test cases in the result XML, 3 failing
udea-editor:test  EditorTabsTest > the Scene tab takes its pointer, and the Game tab leaves its pointer to the game()
    org.opentest4j.AssertionFailedError: the Game tab took a click the game should have had
udea-editor:test  EditorTabsTest > a press on a gizmo is the gizmo's in the Scene tab, and the game's in the Game tab()
    org.opentest4j.AssertionFailedError: the Game tab took a click on a gizmo the game should have had
udea-editor:udeaEditorGlTest  GlEditorTabsTest > a click on a gizmo in the Game tab reaches the game, and the same click in the Scene tab is the gizmo's()
    org.opentest4j.AssertionFailedError: a click on a gizmo in the Game tab did not reach the game ==> expected: <1> but was: <0>
```

### M6-no-gizmo-first

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/SceneNavigation.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/SceneNavigation.kt
index 3c9daf5..d2bd7cc 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/SceneNavigation.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/SceneNavigation.kt
@@ -51,7 +51,6 @@ internal class SceneNavigation(private val view: WorldViewport) {
             is PointerEvent.Press -> {
                 drag = when {
                     !onView -> Drag.None
-                    event.button == PointerButton.Primary && view.pressGizmo(at.x, at.y) -> Drag.Gizmo
                     camera.dimension == ViewDimension.ThreeD && event.button == PointerButton.Primary -> Drag.Orbit
                     else -> Drag.Pan
                 }
```

`mutations/M6-no-gizmo-first.failures.txt`:

```
exit 1, 59 test cases in the result XML, 2 failing
udea-editor:test  EditorTabsTest > a press on a gizmo is the gizmo's in the Scene tab, and the game's in the Game tab()
    org.opentest4j.AssertionFailedError: a press on the Scene tab's gizmo did not reach it ==> expected: <1> but was: <0>
udea-editor:udeaEditorGlTest  GlEditorTabsTest > a click on a gizmo in the Game tab reaches the game, and the same click in the Scene tab is the gizmo's()
    org.opentest4j.AssertionFailedError: a click on the Scene tab's gizmo did not reach it, so the click below proves nothing ==> expected: <1> but was: <0>
```

### M7-screenshot-always-game

```diff
diff --git a/udea-agent-host/src/main/kotlin/dev/wildware/udea/agent/host/EditorViewTools.kt b/udea-agent-host/src/main/kotlin/dev/wildware/udea/agent/host/EditorViewTools.kt
index 239be9e..aa031a4 100644
--- a/udea-agent-host/src/main/kotlin/dev/wildware/udea/agent/host/EditorViewTools.kt
+++ b/udea-agent-host/src/main/kotlin/dev/wildware/udea/agent/host/EditorViewTools.kt
@@ -103,7 +103,7 @@ public class EditorViewToolset(
             "this instance has the editor tools but no editor window, so there is no Scene or Game tab to " +
                 "capture. Start it with :moba:desktop:runEditor; render.screenshot still captures the game.",
         )
-        return filing.answer(context, request = { bound.capture(chosen) }) {
+        return filing.answer(context, request = { bound.capture(EditorView.Game) }) {
             put("view", chosen.wireName)
         }
     }
```

`mutations/M7-screenshot-always-game.failures.txt`:

```
exit 1, 59 test cases in the result XML, 1 failing
udea-agent-host:test  EditorViewToolsetTest > each view is captured from its own pass and filed with the view named()
    org.opentest4j.AssertionFailedError: expected: <[Scene, Game]> but was: <[Game, Game]>
```

### M8-wheel-reversed

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/SceneNavigation.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/SceneNavigation.kt
index 3c9daf5..424e0e0 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/SceneNavigation.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/SceneNavigation.kt
@@ -72,7 +72,7 @@ internal class SceneNavigation(private val view: WorldViewport) {
             is PointerEvent.Release, is PointerEvent.Cancel -> drag = Drag.None
 
             is PointerEvent.Scroll -> if (onView && event.delta.y != 0f) {
-                camera.zoomAt(ZOOM_STEP.pow(event.delta.y), at.x, at.y)
+                camera.zoomAt(ZOOM_STEP.pow(-event.delta.y), at.x, at.y)
                 moved = true
             }
 
```

`mutations/M8-wheel-reversed.failures.txt`:

```
exit 1, 59 test cases in the result XML, 1 failing
udea-editor:test  EditorTabsTest > a drag in the Scene tab pans the world with the pointer, and the wheel zooms()
    org.opentest4j.AssertionFailedError: the wheel turned forward did not zoom in: 1.15
```

### M9-pan-y-not-flipped

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/SceneNavigation.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/SceneNavigation.kt
index 3c9daf5..c8088c8 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/SceneNavigation.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/SceneNavigation.kt
@@ -65,7 +65,7 @@ internal class SceneNavigation(private val view: WorldViewport) {
                 val dy = at.y - lastY
                 lastX = at.x
                 lastY = at.y
-                if (drag == Drag.Pan) camera.pan(dx, dy) else camera.orbit(-dx * ORBIT_DEGREES_PER_PIXEL, -dy * ORBIT_DEGREES_PER_PIXEL)
+                if (drag == Drag.Pan) camera.pan(dx, -dy) else camera.orbit(-dx * ORBIT_DEGREES_PER_PIXEL, -dy * ORBIT_DEGREES_PER_PIXEL)
                 moved = true
             }
 
```

`mutations/M9-pan-y-not-flipped.failures.txt`:

```
exit 1, 59 test cases in the result XML, 1 failing
udea-editor:test  EditorTabsTest > a drag in the Scene tab pans the world with the pointer, and the wheel zooms()
    org.opentest4j.AssertionFailedError: a drag down did not carry the world down with it: expected 142.10526, was 217.89474
```


## Images

All images are in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`.

**Live `runEditor` session** (under Xvfb, mouse driven through XTest):

- `issue234-live-scene-tab.png`: the editor opens on the Scene tab, framed as the game frames it, with no HUD.
- `issue234-live-scene-panned-zoomed.png`: the same tab after a mouse drag and four wheel notches. The editor camera moved and the game did not.
- `issue234-live-game-tab.png`: the Game tab is the game's own picture, HUD included, with the Gizmos checkbox. Same world, same tick, different camera (AC1).
- `issue234-live-wheel-zoom-in-then-out.png`: after the wheel fix, wheel up zooms in about the pointer, then wheel down zooms out.

**The same session, captured through the tools:**

- `issue234-editor-screenshot-scene.png`: `editor.screenshot view=scene`, the panned and zoomed Scene tab.
- `issue234-editor-screenshot-game.png`: `editor.screenshot view=game`.
- `issue234-render-screenshot.png`: `render.screenshot` in the same session. It matches the Game tab and shows the capture never moved with the editor camera. The answers are in `live-state-after-screenshots.json`.

**From `GlWorldViewportTest`:**

- `issue234-gl-capture-no-gizmo.png`: the capture, with the red world square and the blue HUD, and no yellow (AC3).
- `issue234-gl-game-tab-gizmo.png`: the Game tab, which is the capture's pixels plus a yellow gizmo (AC2 drawing, AC3).
- `issue234-gl-scene-tab-panned.png`: the Scene tab panned and zoomed, with its gizmo and no HUD (AC1, AC4 in 2D).

**From `GlViewportOrbitTest`:**

- `issue234-gl-orbit-opened.png`, `issue234-gl-orbit-turned.png`, `issue234-gl-orbit-dolly.png`: a cube where the game camera has it, then orbited 90 degrees and raised 15, then moved in. Each frame puts the cube at the pixel `EditorCamera.project` names (AC4 in 3D).

**From `GlEditorTabsTest`:**

- `issue234-gl-editor-capture-no-gizmo.png` and `issue234-gl-editor-game-tab-gizmo.png`: through the real editor window, the capture holds no gizmo and the Game tab holds one.

## The issue, criterion by criterion

1. **Both tabs render the same world at the same tick with different cameras, and screenshots of each tab are posted.**
   - `GlWorldViewportTest` asserts the capture, the Game tab and the Scene tab share one tick, and that the red square sits at a different pixel in each tab, each where its own camera puts it.
   - `WorldViewportTest` checks the same with no GL.
   - Images: the three live grabs, and the `editor.screenshot` pair from one session.
2. **With the overlay on, the Game tab draws gizmos, and a click on one still reaches the game, never the gizmo (GL test).**
   - `GlEditorTabsTest` turns the overlay on through `showGameGizmos`, drives a real GLFW click through Kool, and checks two things: `KoolPointer.pressesSince(LEFT) == 1` and the gizmo's `presses` count is unchanged.
   - The control is the same click in the Scene tab, which reaches the gizmo and not the game.
   - Red under M5 (the Game tab takes its pointer) and under M6 (the control fails).
   - `EditorTabsTest` holds the same routing headless.
3. **`render.screenshot` never contains gizmo pixels, even with gizmos drawn in both tabs (a GL test that fails when the exclusion is removed).**
   - `GlWorldViewportTest` and `GlEditorTabsTest` both count gizmo-coloured pixels in the capture: zero.
   - Red under M1, which is the exclusion removed (288 gizmo pixels).
4. **The editor camera pans and zooms in 2D and orbits in 3D (a GL test on the camera's projection).**
   - 2D: `GlWorldViewportTest` pans and zooms, then finds the red square at `EditorCamera.project`'s pixel. Red under M2.
   - 3D: `GlViewportOrbitTest` opens on the game camera, orbits, then dollies, checking the cube at `project`'s pixel each time. Red under M3 (the orbit is not applied) and M4 (Y up instead of Z up).
   - `EditorCameraTest` (8 tests) checks the arithmetic, and `EditorTabsTest` checks the mouse mapping (M8, M9).
5. **`editor.screenshot(view = scene|game)`.**
   - `EditorViewToolsetTest`: each view is captured from its own port and filed with its own bytes. It also covers `no_editor_window`, a bad view name, and Headless. Red under M7.
   - Driven live over HTTP: see the images.

## Regenerated files

None. No replicated component was added or removed, so neither `net-protocol.lock` nor `expected-generated-hashes.txt` moved. `docs/contracts/` is untouched.

## Not exercised

- The 3D Scene tab in `moba`. The game draws no models, so the 3D path is proven only by `GlViewportOrbitTest`.
- A Game-tab gizmo on a 3D model: out of scope, and it belongs to G6.
- A drag that starts on a gizmo moves nothing yet. The gizmo gets the press; what a drag does with it belongs to G5.
- iOS: not built on this box, as always.
