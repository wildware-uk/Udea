aa7c969

# BRIEF-243: the Animation panel, the scrub preview and the bone overlay

`aa7c969` is the code under review: the merge of `origin/master` at `0e2b871` (#235) into this
branch. The only commit after it adds this file and changes nothing else.

## 1. Evidence command

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew :udea-editor:udeaEditorGlTest --tests dev.wildware.udea.editor.gl.GlAnimationPreviewTest :moba:desktop:editorTest --tests dev.wildware.moba.editor.MobaAnimationPanelTest -Pudea.render.requireGl=true
```

It runs two tests (with `ANDROID_HOME` and a JDK 21 `JAVA_HOME` set, as for every build here):

- The GL test drives a real Kool context. It checks that scrubbing moves the fox in the Scene tab.
  It also checks that the capturable frame and the `WorldHasher` hash stay the same, and that the
  bones sit on the joints. For the model preview, it checks the model is on its own, turning, and
  inside the view.
- The moba test chooses a clip through the tools and runs `editor.undo`.

**It goes red when the feature is cut.** Mutation m1 (below) makes the Scene view ignore its
preview setting. That is the seam the whole scrub preview goes through. `scratchpad/issue243/mut/ev.sh m1`
applied m1, ran the command above exactly, then restored the file. The diff it applied:

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
index 589b012..71e67a1 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
@@ -216,7 +216,7 @@ internal class ModelStage(
         seen.fit(view.width, view.height)
         editor.writeOrbit(orbit)
         aim(seen.camera, orbit)
-        when (val preview = view.modelPreview) {
+        when (val preview: ModelPreview? = null) {
             null -> seen.clearPreview()
             is ModelPreview.Pose -> {
                 val drawn = drawnFor(previewEntity)
```

The lines of `ev-m1.log` that match `udeaEditorGlTest FAILED|GlAnimationPreviewTest >|AssertionFailedError|tests? completed|BUILD|EXIT`, in order. This is a filter, not a contiguous run; the lines in between are cut:

```
> Task :udea-editor:udeaEditorGlTest FAILED
GlAnimationPreviewTest > scrubbing moves the fox in the Scene tab alone, and its bones are drawn on its joints() FAILED
    org.opentest4j.AssertionFailedError at GlAnimationPreviewTest.kt:179
1 test completed, 1 failed
BUILD FAILED in 9s
EXIT 1
```

What the failure says, from the test's XML report: `scrubbing did not move the fox in the Scene tab: 0 pixels`.

Then the same script ran with nothing applied (`ev.sh none`, `ev-none.log`). These are its matching lines, filtered the same way:

```
> Task :moba:desktop:editorTest FROM-CACHE
> Task :udea-editor:udeaEditorGlTest
BUILD SUCCESSFUL in 8s
EXIT 0
```

In both of these runs `:moba:desktop:editorTest` came `FROM-CACHE`, because m1 does not touch
its inputs. That test runs for real, and goes red, under mutation m4 in the table in section 7.

## 2. Summary

**What a person sees in the editor now.** An **Animation** panel is docked on the left.

- **Choosing a clip.** Select an entity drawn with an animated model; since #235 that is a
  left-click on it in the Scene tab. The panel lists the model's clips. Pressing a clip writes
  `Animator.current.clip` and `Animator.current.length` as **one** edit, which `editor.undo`
  takes back.
- **The scrub preview.** Ticking "preview" poses the entity in the **Scene tab only**. It has
  play, pause and a scrubber over the clip's ticks.
  - The Game tab and `render.screenshot` keep the simulated pose, and the world is not touched.
  - The preview clears when you leave it or select something else.
- **The bone overlay.** It draws a dot on every joint and a line from each joint to its parent.
  - It sits at the animated positions, and follows the preview as it scrubs.
  - It is Scene-tab only, so it is never in `render.screenshot`.
- **The model preview.** Pressing a model in the panel's model list shows that model **alone**
  in the Scene tab, turning, with its clips.

**Decisions.** Each is on the issue as its own comment, with the alternative and how to undo it.

- **Choosing a clip is an edit session**, `editor.begin_edit` / `update_edit` / `commit_edit` over
  the clip and its length, rather than a single `editor.set_field`. A clip is an index plus a
  length, and writing only one of the two leaves an Animator playing Run for Survey's 205 ticks.
  Mutation m4 shows exactly that.
  https://github.com/wildware-uk/Udea/issues/243#issuecomment-5741930491
- **The public gizmo API gained what the bone overlay needed**, and nothing else:
  - `GizmoScope.mark(at, shape)`, `Mark` and `Gizmo.marks(target)`: a read-only drawn shape that
    is not a handle, so it never takes a press;
  - `GizmoCanvas.line(...)` in `udea-render`, over a new internal `SpriteBatch2D.line`.

  The overlay (`BoneOverlayGizmo`) is written against that public API alone. dev-236 (#236) adds
  the same `GizmoCanvas.line` signature, and whichever branch merges second folds their `guide()`
  and my `mark()` together.
  https://github.com/wildware-uk/Udea/issues/243#issuecomment-5741930565
- **The scrub preview is a Scene-view setting.** `WorldViewport.modelPreview`
  (`ModelPreview.Pose` or `ModelPreview.Asset`) goes through `ModelStage`'s per-view pass.
  - That pass's Kool `drawFilter` hides the simulated node and draws a preview node posed by
    `ClipPose`.
  - The capturable pass filters every preview node out, so `render.screenshot` and the Game tab
    cannot see one.
  - `ModelRenderSystem.skeletonOf` hands the overlay the joints of whatever the view draws.

  https://github.com/wildware-uk/Udea/issues/243#issuecomment-5741930696
- **The bone overlay is Scene-tab only**, not on the Game tab: the Game tab is the game's own
  picture. https://github.com/wildware-uk/Udea/issues/243#issuecomment-5741980544
- **No bones in the Scene tab's 2D mode.** In 2D the Scene still draws models through the 3D
  orbit, but projects gizmos through the 2D camera, so bones would land somewhere else. Found
  live. https://github.com/wildware-uk/Udea/issues/243#issuecomment-5742079138
- **The Fox comes from a dev hook**, as the lead decided. `runEditor -PeditorFox=true` puts the
  Khronos Fox beside the player (`MobaEditorModels`, in moba's editor source set). moba's level
  has no animated entity. `MobaLaunch.launch` gained a `renderers` hook so the editor can
  register the model renderer before the backend builds its pipeline.
- **The clip list comes from the generated accessor.** Each model's `Clips` object gains
  `all: List<AnimationClip>`, in file order, generated with KotlinPoet (`%M(%L)` over `%N`
  members), with the golden `Fox.kt.txt` updated.

**Two defects found by driving the real editor.** Neither ever shipped; both are fixed and pinned
by tests.

- **Every joint was a root, so no bone lines were drawn.** Kool's glTF joint nodes have no
  `Node.parent` for the Fox (24 roots). Parents now come from the glTF node tree
  (`skinJointsOf`). Pinned by `SkinnedPoseTest`, which expects one root, and by the GL test's
  "hang from another" check (m8).
- **The model preview drew the model huge, over the entity's own fox and its bones.** Both of
  Kool's bounds were empty for it. It is now measured from its vertices
  (`restBoundsOf`, `updateGeometryBounds`) and fitted to three quarters of the view's height or
  width, whichever is smaller. The other models and the hidden fox's bones are left out while it
  shows (m11 to m14).

**The merge with #235** (`0e2b871`: mouse picking, `EditorInspector`, `HandleLayer`).

- I took master's `EditorSelection` and `EditorTools` whole, and dropped my own selection
  polling. The panel now follows `session.selection`.
- `GizmoMarkLayer` wraps the layer beneath it (`under`). #235's handles still draw and still
  take presses: `GizmoMarkLayerTest`, m15.
- My pre-merge redraw-on-selection change and its mutations (m7, m10) are gone. Master's session
  already redraws the Scene when the selection changes.

**What I did not cover.**

- The `under?.draw(canvas)` line in `GizmoMarkLayer` has no test of its own. The press path
  (`under?.press`) is tested (m15).
- #235's selection outline stays drawn on the hidden fox while a *model* preview shows.
- iOS was not built. It cannot be built on this box.
- A slip to own up to: while cleaning up Xvfb displays I once ran `kill 1328110`, an Xvfb pid I
  had started earlier, without first reading `/proc/1328110/cmdline`. Afterwards the pid did not
  exist, so I cannot say what, if anything, it hit.

**A trap worth knowing.** A plain `sh gradlew build` after an xvfb GL run restores the
*skipped* GL results from the build cache over the real ones in `build/test-results/`. So read the
GL XML right after the xvfb run, as below.

## 3. `sh gradlew build`

`sh gradlew build --continue --max-workers=6`, no exclusions, on the merged tree (`build-merged.log`; its `BUILD` and `actionable tasks` lines):

```
BUILD SUCCESSFUL in 1m 59s
984 actionable tasks: 603 executed, 214 from cache, 167 up-to-date
```

It was run again on `aa7c969` with a clean `git status` just before this brief was written
(`final.log`; almost everything was already up to date):

```
BUILD SUCCESSFUL in 11s
975 actionable tasks: 18 executed, 6 from cache, 951 up-to-date
```

Neither log has `FAILED` in it.

## 4. The GL run, for real

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew udeaGlTest --rerun udeaAgentGlTest --rerun :udea-editor:udeaEditorGlTest --rerun -Pudea.render.requireGl=true --continue --max-workers=6
```

The lines of `glfinal.log` that match `> Task :.*Gl|BUILD|actionable` (filtered):

```
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-editor:udeaEditorGlTest
> Task :udea-render:udeaGlTest
BUILD SUCCESSFUL in 1m 27s
117 actionable tasks: 12 executed, 105 up-to-date
```

Every suite's JUnit XML, read straight after that run (`glfinal-suites.txt`). Each one reads
`skipped="0" failures="0" errors="0"`.

```
udea-render:udeaGlTest GlCaptureDeterminismTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:04:23.503Z"
udea-render:udeaGlTest GlFrameResizeTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:04:34.191Z"
udea-render:udeaGlTest GlModelRenderTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:05:02.960Z"
udea-render:udeaGlTest GlImportedModelRenderTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:04:36.561Z"
udea-render:udeaGlTest GlCapturedUiTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:04:30.578Z"
udea-render:udeaGlTest GlKoolInputTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:04:42.084Z"
udea-render:udeaGlTest GlKoolPointerTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:04:58.467Z"
udea-render:udeaGlTest GlKoolKeyTableTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:04:50.568Z"
udea-render:udeaGlTest GlViewportOrbitTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:05:21.873Z"
udea-render:udeaGlTest KoolThreadShutdownTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:05:31.121Z"
udea-render:udeaGlTest OffscreenBackendShutdownTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:05:37.958Z"
udea-render:udeaGlTest GlViewPresentTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:05:16.237Z"
udea-render:udeaGlTest GlWorldViewTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:05:25.195Z"
udea-render:udeaGlTest OffscreenBackendSecondCreateTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:05:36.406Z"
udea-render:udeaGlTest GlOverlayIsolationTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:05:06.782Z"
udea-render:udeaGlTest GlSkinnedModelRenderTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:05:08.978Z"
udea-editor:udeaEditorGlTest GlAnimationPreviewTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:04:20.886Z"
udea-render:udeaGlTest GlWorldViewportTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:05:28.956Z"
udea-render:udeaGlTest GlViewResizeTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:05:19.062Z"
udea-render:udeaGlTest OffscreenBackendExplodingCaptureTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:05:33.507Z"
udea-render:udeaGlTest OffscreenBackendTest tests="2" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:05:40.378Z"
udea-render:udeaGlTest GlCaptureTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:04:26.731Z"
udea-editor:udeaEditorGlTest GlEditorTabsTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:04:33.414Z"
udea-render:udeaGlTest GlUiLayerTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:05:13.534Z"
udea-editor:udeaEditorGlTest GlScenePickingTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:04:37.354Z"
udea-editor:udeaEditorGlTest GlEditorLayoutTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:04:29.093Z"
udea-agent-host:udeaAgentGlTest OffscreenRenderToolsTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:04:21.965Z"
udea-agent-host:udeaAgentGlTest OverlayCaptureIsolationTest tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T14:04:27.375Z"
```

## 5. Images

Each is in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`. The live ones come from
`runEditor -PeditorFox=true` on Xvfb, driven by real mouse clicks.

- `issue243-panel-run-chosen.png`: the whole editor after pressing Run in the Animation panel.
  It proves the panel exists and the choice lands in the Inspector.
- `issue243-choose-and-undo.png`: after Run, the Inspector reads clip 2, length 70, with one
  history entry. After History > Undo it reads 0 and 205, with the history empty. This is the
  one undoable edit.
- `issue243-bone-overlay.png`: the fox selected by a left-click (#235's outline), with dots on
  its joints and lines between them. The bones sit on the model.
- `issue243-scrub-sequence.png`: the Walk clip scrubbed from tick 0 to 43, with the panel's time
  and slider beside the Scene tab. Legs and bones move together.
- `issue243-screenshot-keeps-simulated-pose.png`: the Scene tab at Walk 43/43 beside a
  `render.screenshot` taken at the same moment. The capture keeps the simulated pose and holds
  no bones.
- `issue243-model-preview.png`: the Fox model on its own, turning, then playing Run.
- `issue243-gl-scrub-bones.png`: the GL test's own Scene frames at Walk ticks 0, 11 and 21, bones
  included.
- `issue243-gl-capture-2d-model.png`: from the GL test, three frames. The capture during the
  scrub, which is unchanged. The Scene in 2D, with no bones. The model preview fitted to a
  200-pixel-wide tab.
- `issue243-bones-before-after.png`: the joint-parent defect found live. Dots with no lines
  before, a connected skeleton after.

## 6. The issue, criterion by criterion

1. **Choosing a clip changes the entity's `Animator` as one undoable edit, and `editor.undo`
   restores it (tested through the tools).**
   - `MobaAnimationPanelTest` > `choosing a clip sets the Animator as one undoable edit, and
     editor undo puts it back`. This is the real moba registry and real tools, and it is in the
     evidence command.
   - `AnimationPanelStateTest` > `choosing a clip is an edit session on the Animator's clip and
     length, closed as one edit`.
   - Live, over the bridge, on NetId 31 (`live/*.json`): after pressing Run,
     `world.get_component` read `current.clip` 2, `current.length` 70, and `editor.history` read
     size 1. After History > Undo: 0, 205, size 0.
   - Image: `issue243-choose-and-undo.png`. Mutation: m4.
2. **Scrubbing changes the Scene tab's picture but not the world hash (GL test plus a `WorldHasher`
   assertion).**
   - `GlAnimationPreviewTest`: the Scene frame differs at scrub ticks 0, 11 and 21.
   - `WorldHasher.hash` of a `SnapshotService` capture is equal before, during and after
     (`scrubbing changed the world`, `the preview changed the world`).
   - The capturable frame is byte-equal throughout.
   - Mutations m1, m2, m3 and m5. Images: `issue243-scrub-sequence.png`,
     `issue243-gl-scrub-bones.png`.
3. **The bone overlay draws the joints at their animated positions, and is absent from
   `render.screenshot` (GL test).**
   - `GlAnimationPreviewTest`: every joint's dot is found where the Scene camera projects
     `ModelRenderSystem`'s animated joint, at each scrub tick. Bone lines are drawn, and at least
     half the joints hang from another.
   - The capture holds no mark pixels. In 2D there are 0 marks.
   - Headless: `BoneOverlayGizmoTest` and `SkinnedPoseTest`.
   - Mutations m6, m8, m9 and m14. Images: `issue243-bone-overlay.png`,
     `issue243-screenshot-keeps-simulated-pose.png`.
4. **Screenshots of the panel, a scrub sequence and the bone overlay are posted.** They are in
   section 5. They were posted to the dashboard (the Udea project) and copied to the gallery.
5. **The model asset preview (small: the model turning, with its clip list).**
   - `AnimationPanelStateTest` > `a model can be previewed on its own, turning, with its clips`.
   - `GlAnimationPreviewTest`'s model section checks that the model:
     - is inside the view and fills 25-90% of it, off-centre by no more than 15%;
     - turns;
     - stands alone;
     - leaves the capture and the hash unchanged;
     - has no bones;
     - fits a narrow tab.
   - Mutations m11 to m14. Image: `issue243-model-preview.png`.

## 7. Mutations

Each mutation was applied alone to `aa7c969` by `mut/run.sh` (whole, below), which saved its literal
`git diff`. Each was run under xvfb, and the file was restored afterwards:

```
#!/bin/sh
# usage: run.sh m1 m2 ...   each mutation alone: apply, save the literal diff, run the suite under xvfb, restore.
M=/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue243/mut
W=/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3a211acbf1ad80a6
cd "$W" || exit 1
rm -f "$M/ALL.done"
for m in "$@"; do
  f=$(python3 "$M/apply.py" "$W" "$m" --file)
  if ! python3 "$M/apply.py" "$W" "$m" > "$M/$m.apply" 2>&1; then git checkout -- "$f"; continue; fi
  git diff > "$M/$m.diff"
  rm -rf udea-render/build/test-results/jvmTest udea-editor/build/test-results moba/desktop/build/test-results/editorTest
  ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
    xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
    sh gradlew :udea-render:jvmTest --tests 'dev.wildware.udea.render.model.*' :udea-editor:test :udea-editor:udeaEditorGlTest \
      :moba:desktop:editorTest --tests dev.wildware.moba.editor.MobaAnimationPanelTest \
      -Pudea.render.requireGl=true --continue --max-workers=6 > "$M/$m.log" 2>&1
  echo "EXIT $?" >> "$M/$m.log"
  python3 "$M/failed.py" "$W" > "$M/$m.failed"
  git checkout -- "$f"
done
git status --short > "$M/status.after"
echo DONE > "$M/ALL.done"
```

The failure lines come from the JUnit XML (`mut/failed.py`). `git status --short` after the whole
run printed nothing (`mut/status.after` is empty).

**m0, the control** (no change): `EXIT 0`

```
udea-render/build/test-results/jvmTest: 16 tests, 0 failed, 0 skipped
udea-editor/build/test-results/test: 69 tests, 0 failed, 0 skipped
udea-editor/build/test-results/udeaEditorGlTest: 4 tests, 0 failed, 0 skipped
moba/desktop/build/test-results/editorTest: 2 tests, 0 failed, 0 skipped
```

**m1**: The Scene view ignores its preview setting: the seam cut. `EXIT 1`

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
index 589b012..71e67a1 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
@@ -216,7 +216,7 @@ internal class ModelStage(
         seen.fit(view.width, view.height)
         editor.writeOrbit(orbit)
         aim(seen.camera, orbit)
-        when (val preview = view.modelPreview) {
+        when (val preview: ModelPreview? = null) {
             null -> seen.clearPreview()
             is ModelPreview.Pose -> {
                 val drawn = drawnFor(previewEntity)
```

```
  FAILED GlAnimationPreviewTest > scrubbing moves the fox in the Scene tab alone, and its bones are drawn on its joints()
    org.opentest4j.AssertionFailedError: scrubbing did not move the fox in the Scene tab: 0 pixels
```

**m2**: The capturable pass draws the preview nodes too (master had no filter there). `EXIT 1`

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
index 589b012..881688e 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
@@ -104,7 +104,6 @@ internal class ModelStage(
         pass.dependsOn(shadow)
         passes.addBeforeCapture(shadow)
         passes.addBeforeCapture(pass)
-        pass.defaultView.drawFilter = { node -> node !in previewNodes }
         val castsShadow = shadow.defaultView.drawFilter
         shadow.defaultView.drawFilter = { node -> node !in previewNodes && castsShadow(node) }
     }
```

```
  FAILED GlAnimationPreviewTest > scrubbing moves the fox in the Scene tab alone, and its bones are drawn on its joints()
    org.opentest4j.AssertionFailedError: scrubbing to 11 changed the capturable frame. Array elements differ at index 58395. Expected element <-4937570>, actual element <-5074588>.
```

**m3**: The Scene view draws the preview without hiding the simulated node beneath it. `EXIT 1`

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
index 81b6b32..0f3a075 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
@@ -451,7 +451,7 @@ internal class ModelStage(
 
         init {
             // Everything but the node this view hides, and no preview node but its own.
-            pass.defaultView.drawFilter = { node -> node !== hidden && (node === preview?.node || node !in previewNodes) }
+            pass.defaultView.drawFilter = { node -> node === preview?.node || node !in previewNodes }
             pass.dependsOn(shadow)
             passes.addBeside(pass)
             view.dependsOn(pass)
```

```
  FAILED GlAnimationPreviewTest > scrubbing moves the fox in the Scene tab alone, and its bones are drawn on its joints()
    org.opentest4j.AssertionFailedError: the simulated fox is still drawn under the preview: 0 pixels uncovered
```

**m4**: Choosing a clip writes the clip index alone, not its length. `EXIT 1`

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/AnimationPanelState.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/AnimationPanelState.kt
index 53d5270..1196d18 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/AnimationPanelState.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/AnimationPanelState.kt
@@ -126,10 +126,10 @@ internal class AnimationPanelState(
     fun choose(clip: AnimationClip) {
         val entity = target ?: return
         if (previewing) previewClip = clip
-        tools.call(BEGIN_EDIT, mapOf("entities" to entity.raw.toString(), "fields" to "$CLIP_FIELD,$LENGTH_FIELD")) { begun ->
+        tools.call(BEGIN_EDIT, mapOf("entities" to entity.raw.toString(), "fields" to CLIP_FIELD)) { begun ->
             val session = answered(BEGIN_EDIT, begun) ?: return@call
             val id = Json.parseToJsonElement(session).jsonObject.getValue("sessionId").jsonPrimitive.int.toString()
-            val values = "$CLIP_FIELD=${clip.index},$LENGTH_FIELD=${clip.length.count}"
+            val values = "$CLIP_FIELD=${clip.index}"
             tools.call(UPDATE_EDIT, mapOf("sessionId" to id, "values" to values)) { updated ->
                 answered(UPDATE_EDIT, updated) ?: return@call
                 tools.call(COMMIT_EDIT, mapOf("sessionId" to id)) { committed -> answered(COMMIT_EDIT, committed) }
```

```
  FAILED AnimationPanelStateTest > choosing a clip is an edit session on the Animator's clip and length, closed as one edit()
    org.opentest4j.AssertionFailedError: expected: <{entities=0, fields=Animator.current.clip,Animator.current.length}> but was: <{entities=0, fields=Animator.current.clip}>
  FAILED MobaAnimationPanelTest > choosing a clip sets the Animator as one undoable edit, and editor undo puts it back()
    org.opentest4j.AssertionFailedError: Run's length was not written with it ==> expected: <70> but was: <205>
```

**m5**: A held pose is not clamped to its clip. `EXIT 1`

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ClipPose.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ClipPose.kt
index 2b5a4f4..04c4ce3 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ClipPose.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ClipPose.kt
@@ -87,7 +87,7 @@ internal class ClipPose {
      */
     fun hold(clip: AnimationClip, at: Ticks): ClipPose {
         current = clip.index
-        currentTicks = at.count.coerceIn(0L, clip.length.count).toDouble()
+        currentTicks = at.count.toDouble()
         clearPrevious()
         weight = 1f
         return this
```

```
  FAILED ClipPoseTest > a held pose is its clip at its time alone, clamped to the clip, whatever was set before()[jvm]
    org.opentest4j.AssertionFailedError: a time past the clip's end is its end ==> expected: <70.0> but was: <500.0>
```

**m6**: The bone overlay declares no marks. `EXIT 1`

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/gizmo/BoneOverlayGizmo.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/gizmo/BoneOverlayGizmo.kt
index 60976bc..49adfb8 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/gizmo/BoneOverlayGizmo.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/gizmo/BoneOverlayGizmo.kt
@@ -24,10 +24,6 @@ internal class BoneOverlayGizmo(private val skeletons: SkeletonSource) : Gizmo<A
 
     override fun GizmoScope<Animator>.build(target: GizmoTarget<Animator>) {
         val joints = skeletons.skeletonOf(target.entity)
-        for (joint in joints) {
-            mark(joint.at, HandleShape.Point)
-            if (joint.parent != SkeletonJoint.ROOT) mark(joint.at, HandleShape.Line(joints[joint.parent].at))
-        }
     }
 
     override fun toString(): String = "BoneOverlayGizmo($skeletons)"
```

```
  FAILED BoneOverlayGizmoTest > every joint gets a dot and every joint below another a line up to it()
    org.opentest4j.AssertionFailedError: a dot on each joint, where the renderer says it is ==> expected: <[WorldPoint(x=0.0, y=0.0, z=10.0), WorldPoint(x=0.0, y=1.0, z=5.0), WorldPoint(x=0.0, y=2.0, z=0.0), WorldPoint(x=-4.0, y=0.0, z=9.0)]> but was: <[]>
  FAILED BoneOverlayGizmoTest > it is read-only - it declares no handle()
    org.opentest4j.AssertionFailedError: Expected value to be true.
  FAILED GlAnimationPreviewTest > scrubbing moves the fox in the Scene tab alone, and its bones are drawn on its joints()
    org.opentest4j.AssertionFailedError: at clip tick 0 not every joint has its dot where the Scene camera puts it ==> expected: <24> but was: <0>
```

**m8**: Joint parents not read from the glTF tree: every joint a root, as the Node.parent walk gave. `EXIT 1`

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelSkeleton.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelSkeleton.kt
index 272645e..4960bfb 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelSkeleton.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelSkeleton.kt
@@ -130,7 +130,7 @@ internal fun skinJointsOf(model: KoolModel, gltf: GltfFile): List<SkinJoint> {
         // nodes are a tree, so the walk is bounded by its size even in one that is not.
         for (index in first until joints.size) {
             val joint = joints[index]
-            var above = parentNode[joint.node]
+            var above = NO_NODE
             var steps = 0
             while (above != NO_NODE && above !in indexOfNode && steps++ < parentNode.size) above = parentNode[above]
             val parent = indexOfNode[above] ?: continue
```

```
  FAILED SkinnedPoseTest > the skeleton the bone overlay reads is the skin's one tree, each joint hanging from the one above it()[jvm]
    org.opentest4j.AssertionFailedError: the Fox's skin has one root joint, found 24 ==> expected: <1> but was: <24>
  FAILED GlAnimationPreviewTest > scrubbing moves the fox in the Scene tab alone, and its bones are drawn on its joints()
    org.opentest4j.AssertionFailedError: at clip tick 0 only 0 of 24 joints hang from another
```

**m9**: Bones drawn in the Scene tab's 2D mode, through its 2D camera. `EXIT 1`

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/gizmo/BoneOverlayGizmo.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/gizmo/BoneOverlayGizmo.kt
index 60976bc..41258e5 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/gizmo/BoneOverlayGizmo.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/gizmo/BoneOverlayGizmo.kt
@@ -71,7 +71,6 @@ private class ModelSkeletons(private val models: ModelRenderSystem, private val
     private val read = ModelSkeleton()
 
     override fun skeletonOf(entity: NetId): List<SkeletonJoint> {
-        if (view?.camera?.dimension == ViewDimension.TwoD) return emptyList()
         if (!models.skeletonOf(entity, view, read)) return emptyList()
         return List(read.size) { joint ->
             SkeletonJoint(WorldPoint(read.x(joint), read.y(joint), read.z(joint)), read.parent(joint))
```

```
  FAILED GlAnimationPreviewTest > scrubbing moves the fox in the Scene tab alone, and its bones are drawn on its joints()
    org.opentest4j.AssertionFailedError: the Scene tab in 2D drew bones through its 2D camera ==> expected: <0> but was: <299>
```

**m11**: The model preview's rest extent never measured: drawn at the file's own size. `EXIT 1`

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
index 589b012..20fd560 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
@@ -542,7 +542,6 @@ internal class ModelStage(
             dropPreview()
             val made = Placed(model, model.gltf.makeModel(importsFor(model).config).withOwnShadowSkins())
             made.setAmbient()
-            restBoundsOf(made.node, previewRest)
             previewNodes += made.node
             drawNode.addNode(made.node)
             preview = made
```

```
  FAILED GlAnimationPreviewTest > scrubbing moves the fox in the Scene tab alone, and its bones are drawn on its joints()
    org.opentest4j.AssertionFailedError: the model preview is cut off by the view's edge: Box(left=115, top=0, right=314, bottom=113)
```

**m12**: The model preview drawn with every other model still showing. `EXIT 1`

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
index 589b012..7161dd8 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
@@ -459,7 +459,7 @@ internal class ModelStage(
             // A model shown on its own is shown alone: every other model under the draw node is left out.
             pass.defaultView.drawFilter = { node ->
                 node === preview?.node ||
-                    (node !== hidden && node !in previewNodes && !(showingAsset && node.parent === drawNode))
+                    (node !== hidden && node !in previewNodes)
             }
             pass.dependsOn(shadow)
             passes.addBeside(pass)
```

```
  FAILED GlAnimationPreviewTest > scrubbing moves the fox in the Scene tab alone, and its bones are drawn on its joints()
    org.opentest4j.AssertionFailedError: the entity's fox is drawn under the model preview: 0 pixels uncovered
```

**m13**: The model preview sized by the view's height alone. `EXIT 1`

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
index 589b012..c931162 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
@@ -513,7 +513,7 @@ internal class ModelStage(
             // The view is 2 d tan(fov / 2) high at the orbit's distance d, and its aspect times that
             // wide: the model fits the narrower of the two.
             val viewHeight = 2f * distance * tan(orbit.fovYDegrees.deg.rad / 2f)
-            val wanted = ASSET_FILL * viewHeight * minOf(1f, width.toFloat() / height)
+            val wanted = ASSET_FILL * viewHeight
             val scale = if (largest > 0f) wanted / largest else 1f
             placing.setIdentity()
                 .translate(orbit.targetX, orbit.targetY, orbit.targetZ)
```

```
  FAILED GlAnimationPreviewTest > scrubbing moves the fox in the Scene tab alone, and its bones are drawn on its joints()
    org.opentest4j.AssertionFailedError: the model preview is cut off by the view's edge: Box(left=0, top=98, right=199, bottom=223)
```

**m14**: The hidden fox's skeleton still given while the model preview shows. `EXIT 1`

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
index 589b012..d10ad88 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
@@ -247,7 +247,6 @@ internal class ModelStage(
         out.clear()
         val seen = view?.let { views[it] }
         // A model shown on its own hides every entity's from that view, so none has a skeleton there.
-        if (seen != null && seen.showingAsset) return false
         val placed = seen?.previewFor(entity) ?: drawnFor(entity) ?: return false
         return placed.writeSkeleton(out)
     }
```

```
  FAILED GlAnimationPreviewTest > scrubbing moves the fox in the Scene tab alone, and its bones are drawn on its joints()
    org.opentest4j.AssertionFailedError: the hidden fox's bones are drawn over the model preview ==> expected: <0> but was: <1653>
```

**m15**: The mark layer keeps a press from the handle layer it was put over. `EXIT 1`

```diff
diff --git a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/gizmo/GizmoMarkLayer.kt b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/gizmo/GizmoMarkLayer.kt
index bbae9fa..54f629f 100644
--- a/udea-editor/src/main/kotlin/dev/wildware/udea/editor/gizmo/GizmoMarkLayer.kt
+++ b/udea-editor/src/main/kotlin/dev/wildware/udea/editor/gizmo/GizmoMarkLayer.kt
@@ -99,7 +99,7 @@ internal class GizmoMarkLayer(
         canvas.fill(x - DOT_SIZE / 2f, y - DOT_SIZE / 2f, DOT_SIZE, DOT_SIZE, MARK_COLOUR)
     }
 
-    override fun press(canvas: GizmoCanvas, viewX: Float, viewY: Float): Boolean = under?.press(canvas, viewX, viewY) ?: false
+    override fun press(canvas: GizmoCanvas, viewX: Float, viewY: Float): Boolean = false
 
     override fun toString(): String = "GizmoMarkLayer(${gizmos.size} gizmos, under=$under)"
 
```

```
  FAILED GizmoMarkLayerTest > a press goes on to the layer the marks were put over()
    org.opentest4j.AssertionFailedError: the handles under the marks did not take the press
```

**m16**: (After the #235 merge) the editor's own reads count as changes in master's EditorTools, so the panel's selection read and the history read wake each other. `EXIT 1`

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
  FAILED AnimationPanelStateTest > an idle window sends nothing, with the panel's selection read beside the history's()
    org.opentest4j.AssertionFailedError: an idle editor kept calling tools ==> expected: <[]> but was: <[editor.history, editor.selection, editor.common_fields]>
  FAILED EditorSessionTest > its own reads do not trigger more, so an idle window sends nothing()
    org.opentest4j.AssertionFailedError: an idle editor kept calling tools ==> expected: <[]> but was: <[editor.history, editor.selection]>
```


## 8. Regenerated files

None. No replicated component was added or removed, so neither `net-protocol.lock` nor
`expected-generated-hashes.txt` moved. The one golden that changed is the asset compiler's
`Fox.kt.txt`, updated by hand for the new `Clips.all` and checked by `ModelClipAccessorsTest`.
