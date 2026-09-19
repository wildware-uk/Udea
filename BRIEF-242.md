050b1ea

(The code under review is `050b1ea` on `issue-242-skinned-playback`: the work, merged with
`origin/master` at `5cbf6d0`. This brief is committed on top of it and changes no code.)

# BRIEF-242: GPU-skinned playback driven by the Animator, with crossfades

Issue #242, A3 of epic #239. Branch `issue-242-skinned-playback`, from `origin/master` at `25e2865`.

`scratchpad/` below is
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/`.

## 1. Evidence command

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew :udea-render:jvmTest --tests '*.ClipPoseTest' --tests '*.SkinnedPoseTest' --rerun :udea-render:udeaGlTest --tests '*.GlSkinnedModelRenderTest' --rerun :udea-render:runModelShot -Pudea.render.requireGl=true --continue
```

(`ANDROID_HOME` and `JAVA_HOME` as the contract says.) It runs the pure clip-clock tests, the
Kool-without-GL skin tests, the GL test, and writes the pictures to
`udea-render/build/reports/udea/model/model-fox-anim-*.png` and
`udea-render/build/reports/udea/gl/skinned-fox-*.png`.

**It goes red when the feature is reverted.** Six mutations, each applied alone to the committed
code by `scratchpad/issue242/mutate.py`, which ran the command above minus the shot and restored
the file. Each diff block is the diff file it wrote (`git diff` taken while the mutation was
applied), `index` lines dropped; each result block is the whole `.result` file it wrote, its own
summary of Gradle's exit code and the JUnit XML, with failure messages cut at 220 characters. All
of it is under `scratchpad/issue242/mutations/`, with each run's full Gradle log beside it.

**M1, the pose is never applied** (the feature switched off at its one call site):

```
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
@@ -275,7 +275,6 @@ internal class ModelStage(
             val placed = if (shown < nodes.size) nodes[shown] else make()
             shown++
             placed.node.transform.setMatrix(transform)
-            placed.node.applyPose(pose)
             placed.node.isVisible = true
         }
 
```
```
mutation M1-pose-not-applied: gradle exit 1
  BUILD FAILED in 54s
  dev.wildware.udea.render.model.ClipPoseTest: tests=9 failures=0 errors=0 skipped=0
  dev.wildware.udea.render.model.SkinnedPoseTest: tests=6 failures=0 errors=0 skipped=0
  dev.wildware.udea.render.gl.GlSkinnedModelRenderTest: tests=1 failures=1 errors=0 skipped=0
    FAILED the fox's legs move with its clip, and only with its clip(): org.opentest4j.AssertionFailedError: Walk ticks t11 and 22: only 0 pixels of the fox moved
```

**M2, no clips and no skin loaded** (the stage's glTF config as #240 left it):

```
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ModelStage.kt
@@ -370,8 +370,8 @@ internal fun gltfLoadConfig(model: ImportedModel, shadowMaps: List<ShadowMap>):
     applyMaterials = true,
     materialConfig = GltfMaterialConfig(shadowMaps = shadowMaps),
     // Posed every frame from the entity's `Animator` by `applyPose`.
-    loadAnimations = true,
-    applySkins = true,
+    loadAnimations = false,
+    applySkins = false,
     applyMorphTargets = false,
     assetLoader = model.loader,
     // The file's material, with the same uniform ambient light the built-in shapes get; its
```
```
mutation M2-no-clips-no-skin: gradle exit 1
  BUILD FAILED in 59s
  dev.wildware.udea.render.model.ClipPoseTest: tests=9 failures=0 errors=0 skipped=0
  dev.wildware.udea.render.model.SkinnedPoseTest: tests=6 failures=5 errors=0 skipped=0
    FAILED halfway through a crossfade every joint that moves lies between the two clips' poses()[jvm]: java.util.NoSuchElementException: List is empty.
    FAILED a clip moves the head, and the same clip time puts it back()[jvm]: java.util.NoSuchElementException: List is empty.
    FAILED a clip the file does not have draws the bind pose, with no error()[jvm]: java.util.NoSuchElementException: List is empty.
    FAILED no animator draws the bind pose, on a node that was posed before()[jvm]: java.util.NoSuchElementException: List is empty.
    FAILED the stage's node carries the file's three clips and its skin()[jvm]: org.opentest4j.AssertionFailedError: expected: <[Survey, Walk, Run]> but was: <[]>
  dev.wildware.udea.render.gl.GlSkinnedModelRenderTest: tests=1 failures=1 errors=0 skipped=0
    FAILED the fox's legs move with its clip, and only with its clip(): org.opentest4j.AssertionFailedError: Walk ticks t11 and 22: only 0 pixels of the fox moved
```

**M3, no reset to the bind pose** (a pooled node keeps the last entity's pose):

```
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/SkinnedPose.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/SkinnedPose.kt
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/SkinnedPose.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/SkinnedPose.kt
@@ -28,6 +28,7 @@ import dev.wildware.udea.core.spatial.ClipPlayback
 internal fun KoolModel.applyPose(pose: ClipPose) {
     val clips = animations
     if (clips.isEmpty() && skins.isEmpty()) return
+    if (pose.current == ClipPlayback.NO_CLIP) return
     for (index in clips.indices) clips[index].weight = 0f
     if (pose.previous == ClipPlayback.NO_CLIP || pose.previous == pose.current) {
         weigh(clips, pose.current, pose.currentSeconds, 1f)
```
```
mutation M3-no-reset-to-bind-pose: gradle exit 1
  BUILD FAILED in 1m 2s
  dev.wildware.udea.render.model.ClipPoseTest: tests=9 failures=0 errors=0 skipped=0
  dev.wildware.udea.render.model.SkinnedPoseTest: tests=6 failures=1 errors=0 skipped=0
    FAILED no animator draws the bind pose, on a node that was posed before()[jvm]: org.opentest4j.AssertionFailedError: no animator: the head is at (-0.107747786, 56.550793, 39.497757), not its bind pose (5.2443072E-5, 60.72549, 36.15446)
  dev.wildware.udea.render.gl.GlSkinnedModelRenderTest: tests=1 failures=0 errors=0 skipped=0
```

**M4, the crossfade ignored** (only the new clip is ever shown):

```
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/SkinnedPose.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/SkinnedPose.kt
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/SkinnedPose.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/SkinnedPose.kt
@@ -29,7 +29,7 @@ internal fun KoolModel.applyPose(pose: ClipPose) {
     val clips = animations
     if (clips.isEmpty() && skins.isEmpty()) return
     for (index in clips.indices) clips[index].weight = 0f
-    if (pose.previous == ClipPlayback.NO_CLIP || pose.previous == pose.current) {
+    if (true) {
         weigh(clips, pose.current, pose.currentSeconds, 1f)
     } else {
         weigh(clips, pose.current, pose.currentSeconds, pose.weight)
```
```
mutation M4-crossfade-ignored: gradle exit 1
  BUILD FAILED in 2m
  dev.wildware.udea.render.model.ClipPoseTest: tests=9 failures=0 errors=0 skipped=0
  dev.wildware.udea.render.model.SkinnedPoseTest: tests=6 failures=1 errors=0 skipped=0
    FAILED halfway through a crossfade every joint that moves lies between the two clips' poses()[jvm]: org.opentest4j.AssertionFailedError: joint 2: halfway at (1.4208518E-6, 33.701706, -22.689198) is not between Walk (-0.64123845, 41.028515, -24.55178) and Run (1.4208518E-6, 33.701706, -22.689198) (7.5869975 from Walk, 0
  dev.wildware.udea.render.gl.GlSkinnedModelRenderTest: tests=1 failures=0 errors=0 skipped=0
```

**M5, the issue's formula taken literally at every speed** (`floor(elapsed * speed) + alpha`).
**This one survived the first time.** The test then asserted only that the clip time never runs
backwards, and the literal formula never does: it skips forwards. The test was changed to assert
continuity across a tick boundary (commit `0c24a29`), and the mutation re-run against it. The
`M5` diff file also holds that test change, because it was still uncommitted when the diff was
taken; the production half is:

```
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ClipPose.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ClipPose.kt
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ClipPose.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ClipPose.kt
@@ -95,7 +95,7 @@ internal class ClipPose {
             if (length == 0L) return 0.0
             val elapsed = now.ticksSince(playback.start)
             if (elapsed < 0L) return 0.0
-            val scaled = (elapsed.toDouble() + alpha.toDouble()) * playback.speed.toDouble()
+            val scaled = kotlin.math.floor(elapsed.toDouble() * playback.speed.toDouble()) + alpha.toDouble()
             return when (playback.loop) {
                 Loop.Repeat -> scaled % length.toDouble()
                 Loop.Once -> minOf(scaled, length.toDouble())
[the diff file continues with its two test-file sections: omitted here]
```
```
mutation M5-floored-clip-time-plus-alpha: gradle exit 1
  BUILD FAILED in 1m 8s
  dev.wildware.udea.render.model.ClipPoseTest: tests=9 failures=1 errors=0 skipped=0
    FAILED between ticks the clip time is continuous, at a speed that is not whole()[jvm]: org.opentest4j.AssertionFailedError: tick 1: the clip jumped from 1.9990000128746033 at alpha 0.999 to 3.0 at the next tick
  dev.wildware.udea.render.model.SkinnedPoseTest: tests=6 failures=0 errors=0 skipped=0
  dev.wildware.udea.render.gl.GlSkinnedModelRenderTest: tests=1 failures=0 errors=0 skipped=0
```

**M6, the fade weight ignores alpha:**

```
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ClipPose.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ClipPose.kt
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ClipPose.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/ClipPose.kt
@@ -111,7 +111,7 @@ internal class ClipPose {
             if (length <= 0L || animator.previous.isEmpty()) return 1f
             val elapsed = now.ticksSince(animator.fadeStart)
             if (elapsed < 0L) return 0f
-            return ((elapsed.toFloat() + alpha) / length.toFloat()).coerceIn(0f, 1f)
+            return (elapsed.toFloat() / length.toFloat()).coerceIn(0f, 1f)
         }
 
         /** Clip ticks to seconds, at the rate the asset build measured every clip's length in. */
```
```
mutation M6-fade-ignores-alpha: gradle exit 1
  BUILD FAILED in 1m 11s
  dev.wildware.udea.render.model.ClipPoseTest: tests=9 failures=1 errors=0 skipped=0
    FAILED halfway through a crossfade both clips are weighed, each at its own time()[jvm]: org.opentest4j.AssertionFailedError: alpha moves the fade on between ticks. Expected <0.55> with absolute tolerance <1.0E-6>, actual <0.5>.
  dev.wildware.udea.render.model.SkinnedPoseTest: tests=6 failures=0 errors=0 skipped=0
  dev.wildware.udea.render.gl.GlSkinnedModelRenderTest: tests=1 failures=0 errors=0 skipped=0
```

The mutation runs had six `SkinnedPoseTest` tests. In self-review afterwards I deleted one, "a
model with no clips and no skin ignores its animator": it could fail only by throwing, and
`applyPose` on such a model cannot throw, so it was a test that cannot fail. The other five are
unchanged.

The GL test's failure message printed the tick as `t11` (a `Tick`'s `toString`) next to a bare
`22`; `0c24a29` makes it print `11`.

## 2. Summary

The Fox moves. `ModelRenderSystem` reads each entity's `Animator` at the simulation's current tick
plus the interpolation alpha and hands the numbers to the stage, which poses that entity's Kool
glTF node through Kool's own clips, blending and armature skinning (GPU skinning, no hand-rolled
shader).

- **`ClipPose`** (new, `udea-render` model package, internal): the renderer's reading of an
  `Animator` as plain numbers - current clip and its time, previous clip and its time, fade
  weight. The only place a clip's time becomes seconds: `clipTicks / SimClock.DEFAULT_TICK_RATE`,
  the rate the asset build measured clip lengths in. A pure function of `(Animator, tick, alpha)`;
  one instance reused for every entity, no allocation.
- **`applyPose`** (new, `SkinnedPose.kt`, internal): the one place those numbers cross into Kool,
  once per entity per frame. Zeroes every clip's weight, weighs the current and fading-out clips,
  puts each at its time, calls `applyAnimation(0f)`. Kool resets every animated joint to rest before
  applying weighted clips, so the pose depends on nothing from an earlier frame or an earlier
  entity.
- **`ModelStage`**: the glTF config (now `gltfLoadConfig`, a top-level internal function so the
  test builds its node the same way) loads clips and skins; `add` takes the pose and `show` applies
  it to every node shown, every frame, because nodes are pooled per model.
- **`ModelRenderSystem`**: takes `ctx.clock` in `onBind`, reads the tick once per frame.
- **The editor's Scene view** (#234's second pass) draws the same nodes with
  `isUpdateDrawNode = false`, so it sees the pose without posing again. The GL test captures the
  view and checks it moved too. I touched no view code.
- **`ClipPlayback`** (lead's decision): the renderer uses `isEmpty()` and the five fields.
  `holds`, `clipTime`, `isFinished` are now `internal`; only `Animator` and udea-core's tests call
  them.
- **`AGENTS.md`**: one sentence under "What the engine does today".
- **`runModelShot`** gains the animated sequence: the host is paused before the first frame so
  alpha is zero and every picture is a known tick moved on by `host.run`, and the camera moves in
  side-on for it. None of the earlier shots in that main reads the clock.

**Decisions** (each commented on #242):

1. At speed 1 the clip time is exactly the issue's `(clipTime + alpha) / 60`. At other speeds it is
   `(elapsed + alpha) * speed`, wrapped or held by the loop, then `/ 60`, because the literal
   formula skips a clip tick at every other boundary at speed 1.5 (mutation M5). A test pins the
   whole part at alpha 0 to the simulation's `clipTime` at speeds 0 to 3.3.
2. A clip index the file does not have: bind pose, no error (throwing would throw every frame on
   the render thread for a content mistake).
3. A crossfade from a clip to itself shows only the new one: Kool has one set of joints per clip.
4. A finished `Loop.Once` clip is held 0.1 ms short of its last keyframe, because Kool wraps a
   clip's time at its last keyframe and would otherwise draw frame 0.

**What is not done, and why**

- Kool's keyframe lookup (`AnimationChannel.apply`) boxes the time it searches for
  (`Float.valueOf`, read from the 0.19.0 bytecode). My code allocates nothing per entity per
  frame; Kool's sampler does, and it is out of reach without forking Kool.
- Kool frustum-culls a skinned mesh by its bind-pose bounds. A limb swung past those bounds at the
  very edge of the picture could be culled early. Not seen in any picture; not tested.
- Criterion 4 ("two runs of the same recorded session") is proved as a chain rather than by
  replaying a `.udearep` through a renderer: see section 5.

## 3. `sh gradlew build`

Run on `e5fe42b` (after merging `origin/master`), with `free -g` showing 13G available. From
`scratchpad/issue242/build.log`:

```
BUILD SUCCESSFUL in 3m 7s
959 actionable tasks: 695 executed, 129 from cache, 135 up-to-date
Configuration cache entry stored.
```

No failing task. `udeaDaemonBudget` passed inside it.

Then `050b1ea` deleted one test (section 1) and the build was run again, from
`scratchpad/issue242/build2.log`:

```
BUILD SUCCESSFUL in 54s
950 actionable tasks: 24 executed, 2 from cache, 924 up-to-date
Configuration cache entry reused.
```

**The GL run**, on `e5fe42b`, from `scratchpad/issue242/gl.log`:

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true --continue
```
```
BUILD SUCCESSFUL in 1m 55s
126 actionable tasks: 12 executed, 114 up-to-date
```

All three tasks executed (not up-to-date). Their XML reports: `udea-render` udeaGlTest 20 tests,
`udea-agent-host` udeaAgentGlTest 2, `udea-editor` udeaEditorGlTest 1; 0 failures, 0 errors,
0 skipped.

The evidence command was run again on `050b1ea` (`scratchpad/issue242/evidence.log`,
`BUILD SUCCESSFUL in 1m 3s`): `ClipPoseTest` 9 tests, `SkinnedPoseTest` 5,
`GlSkinnedModelRenderTest` 1, no failures, none skipped.

Not run: `runUdpProof`, `runLaneShot` (this ticket touches neither the network nor the lane).

## 4. Images

In `/srv/ssd1/workspace/Udea/build/debug-screenshots/`:

- `issue242-fox-survey-walk-run-sequence.png` - the collage the issue asks for: twelve frames from
  `runModelShot`, each labelled with its tick. Survey (the head turns), a crossfade to Walk, Walk,
  a crossfade to Run at 0, 33 and 67 percent, then Run. Shadows move with the legs.
- `issue242-fox-crossfade-walk-to-run-67pct.png` - one frame two thirds of the way from Walk to Run.
- `issue242-fox-run.png` - Run, 30 ticks after the fade began.
- `issue242-gl-no-animator-bind-pose.png` - the GL test's frame with no `Animator`: the bind pose.
- `issue242-gl-walk-tick-a.png`, `issue242-gl-walk-tick-a-plus-11.png` - the two Walk frames the GL
  test compares, a quarter stride apart: the legs have swung.
- `issue242-gl-walk-scene-view.png` - the same instant through an editor Scene view's own pass.

## 5. The issue, criterion by criterion

| Criterion | Proof |
|---|---|
| The Fox walks and runs: a screenshot sequence at known ticks (Survey, then Walk, then a crossfade to Run), posted as a collage | `issue242-fox-survey-walk-run-sequence.png`, from `runModelShot`; posted to the dashboard |
| A GL test fails when the pose is not applied: a bone-driven part of the mesh at different screen positions at two clip times, and the same when skinning is off | `GlSkinnedModelRenderTest`. The fox's transform and the camera never move, so any silhouette pixel that changes was moved by a bone. Walk a quarter stride apart: 3412 pixels changed (capture and Scene view). No `Animator`, same two ticks: byte-identical frames. Goes red under M1 and M2. "Skinning switched off" is taken as "nothing poses the skin", the no-`Animator` case, rather than a test-only switch in production code |
| The crossfade is continuous: halfway through a fade the pose lies between the two clips' poses (test) | `SkinnedPoseTest` "halfway through a crossfade every joint that moves lies between the two clips' poses": at weight 0.5, each of the 22 joints that differ between Walk and Run is closer to each clip's position than the two are to each other, and the fade's first and last ticks are Walk and Run exactly. `ClipPoseTest` covers the weight being continuous between ticks. Red under M4 and M6 |
| Two runs of the same recorded session give the same frames at the same ticks, because the pose depends only on ticks and alpha | A chain, each link tested: #241's `AnimatorReplayTest` shows a replay reproduces the `Animator`; `ClipPoseTest` "the same animator, tick and alpha give the same pose whatever was asked before"; `SkinnedPoseTest` puts the head back exactly at the same clip time; `GlSkinnedModelRenderTest` step 3 draws a byte-identical frame at the same clip time reached from a later tick. And two separate `runModelShot` processes wrote twelve byte-identical animation frames (below) |

The two-run check, spliced from `scratchpad/issue242/run1.sha` and `run2.sha` (12 lines each,
12 distinct hashes, `diff` printed `IDENTICAL`); the first three lines of the second:

```
e2f01359e68fce0383f8f9dd5ff3b6d3b00053b1c9efb9fc1d686794881ecb9e  model-fox-anim-01-survey-t000.png
1c808b4ec891bbad3684af0956aa098bd5d09a36e485e8fc5b7290341edfe7e0  model-fox-anim-02-survey-t060.png
b88eebfb66470c885e51951054e413448654b02a52b001feb9e10f8e14e88390  model-fox-anim-03-survey-t120.png
```

The issue's design bullets:

- Kool's own skin and GPU skinning: `applySkins = true`, Kool's armature shader; no shader here.
- Pose computed in the renderer only: `ClipPose` and `applyPose` are `udea-render`, internal.
  The simulation sees no bone; `udea-core` changed only in visibility.
- Crossfade with Kool's blending: `Animation.weight` and `Model.applyAnimation`.
- No per-entity-per-frame allocation in the pose path: see section 2, Kool's boxing excepted.
- No skin ignores the `Animator`: the GL test's crate, a built-in shape with an `Animator`, is
  drawn (`drawnCount` 2) with no error. A glTF file with no skin is not tested: the Fox is the only
  model file in the tree. A skinned model with no `Animator` draws its bind pose (`SkinnedPoseTest`,
  red under M3).

## 6. Regenerated files

None. No replicated component was added or removed; `net-protocol.lock` and
`expected-generated-hashes.txt` are untouched.
