9c4ebc2

(The code under review. This brief is committed on top of it; nothing else changed after it.)

## Round 2 delta: following #246's interpolated pose

The lead asked for this after #246 merged. The first review round was on `c70488a`. After it:

- `bdcd12d` merges `origin/master` at `6186ef6`, which brings in #246.
- `9c4ebc2` is the one-function change. `ThirdPersonRig.targetPosition` now reads the target through
  `ModelPlacer` at the frame's alpha, instead of reading `Transform3D` directly. `ModelPlacer` is
  #246's internal placer, and `ModelRenderSystem` draws with it too, so the camera centres on the
  exact pose the model is drawn at. The rig binds its own `ModelPlacer(lift = null)` in `onBind`;
  `follow` passes `alpha` through. Nothing else in the rig changed.
- New test, `ThirdPersonRigTest`'s `the camera follows the interpolated pose the model is drawn at`.
  It steps a walking target twice with `Interp3DSnapshotSystem` in the world, renders at alpha 0.5,
  and expects the focus midway between the two ticks. It was run on the raw-`Transform3D` rig
  first, and failed there (`scratchpad/issue248/interp-red.log`:
  `ThirdPersonRigTest[jvm] > the camera follows the interpolated pose the model is drawn at()[jvm] FAILED`,
  `57 tests completed, 1 failed`). Then it passed on `9c4ebc2` (`interp-green.log`, 14 of 14
  `ThirdPersonRigTest` tests).

The lead asked for the change first and the merge second. I merged first, because the change needs
#246's code to compile. The change is still its own commit, and
`git diff bdcd12d 9c4ebc2 -- udea-render/src/commonMain` shows the whole production delta. It is
saved as `scratchpad/issue248/interp-commit.diff`.

On `9c4ebc2` (the tails of `scratchpad/issue248/build-3.log` and `glall-3.log`):

```
BUILD SUCCESSFUL in 1m 49s
984 actionable tasks: 159 executed, 96 from cache, 729 up-to-date
Configuration cache entry stored.
EXIT 0
DONE
```
```
BUILD SUCCESSFUL in 2m 14s
126 actionable tasks: 12 executed, 114 up-to-date
Configuration cache entry stored.
EXIT 0
DONE
```

The GL command was `udeaGlTest --rerun udeaAgentGlTest --rerun udeaEditorGlTest --rerun`. Counts
from `glcount.py` after it: `udeaGlTest` 24 classes, 25 tests, which now include #246's
`GlModelInterpolationTest`. `udeaAgentGlTest` ran 2 tests and `udeaEditorGlTest` 6. All three had
0 skipped, 0 failures and 0 errors. The evidence command's measurements match those quoted below
line for line, and the gallery images were regenerated from this run.

The build and GL sections below describe round 1, on `c7baf93` and `31b4fc3`.

# #248 Hollow E3: a third-person 3D camera rig in `udea-render`

## Evidence command

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew :udea-render:udeaGlTest --tests 'dev.wildware.udea.render.gl.GlThirdPersonRigTest' -Pudea.render.requireGl=true
```

It boots a real Kool context, draws through the real `ModelRenderSystem` with the rig's camera,
drives the rig with a real mouse (raw GLFW cursor events into Kool's own callback, then Kool's
`PointerInput`, `InputStack`, `KoolPointer`, the rig) and reads the pixels back. It leaves the PNGs
in `udea-render/build/reports/udea/gl/issue248-*.png` and its measurements in the test report's
stdout. The green run's report is kept at
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue248/green-gl.xml`.

**It goes red when the feature is reverted.** Three mutations, each run with exactly this command
on `3f207f4`, the first commit. The later commits change KDoc, one named constant in
`ThirdPersonRig` and names in the GL test, and none of the mutated lines. M1 was run again on
`c7baf93`, the code as it stands (the later merge touched nothing in `udea-render`): the same failure, from `scratchpad/issue248/m1-final.log`, and then the evidence
command was run green again so the report directory holds the green run's pictures. Each diff below
is the literal `git diff` of its run, saved in the scratchpad as `m<N>.diff` beside its log.

| # | The mutation | Result of the evidence command |
|---|---|---|
| M1 | The rig stops following (the pre-ticket shape: a fixed camera) | `BUILD FAILED`: `the ball at (4.0, -3.0, 0.5) is not in the picture at all` |
| M2 | The rig reads the mouse and ignores it | `BUILD FAILED`: `moving the mouse 100 pixels right should turn the view 20.0 degrees right; the yaw is 90.0` |
| M3 | `KoolPointer` records no motion (the input layer as it was before) | `BUILD FAILED`: same message as M2 |

M1:
```diff
@@ -204,7 +204,6 @@ public class ThirdPersonRig(
         // capturable frame; doing them again would ease twice as fast while an editor is open.
         if (resources.viewing.current != null) return
         turn()
-        follow()
         place()
     }
 
```
M2:
```diff
@@ -217,8 +217,6 @@ public class ThirdPersonRig(
             if (dx != 0f || dy != 0f) {
                 // Right turns the view right, which is clockwise seen from above: the yaw falls.
                 // Down tips the view down: the pitch rises.
-                yawDegrees -= dx * degreesPerPixel
-                pitchDegrees += dy * degreesPerPixel
             }
         }
         // Spent even when unused, so motion made with the button up does not land when it goes down.
```
M3:
```diff
@@ -104,7 +104,6 @@ public class KoolPointer internal constructor(
         for (pointer in state.pointers) {
             if (!pointer.isValid) continue
             onPointer(PointerId(pointer.id), pointer.buttonMask)
-            onMotion(pointer.delta.x, pointer.delta.y)
         }
         endFrame()
     }
```

M3 is the one only the GL test can catch: the unit tests drive `onMotion` directly.

The unit tests (`ThirdPersonRigTest`, run by `:udea-render:jvmTest`) were also run against M1, M2 and
three more mutations, each on its own:

| # | The mutation | Failing tests in `ThirdPersonRigTest` (56 tests in the run, which includes `CameraRigTest`, `EditorCameraTest` and `KoolPointerOrderTest`) |
|---|---|---|
| M1 | as above | `the camera looks at the target from the configured distance, from behind and above`; `smoothing eases towards a moved target, frame-rate independently`; `the camera keeps the target centred and at its distance as it moves`; `a world ticked with the rig present hashes the same as one ticked without it` (its "the rig never followed anything" guard); `a new target is framed at once rather than eased to from the old one` |
| M2 | as above | `the view the mouse turned is the one the camera draws`; `pitch stays inside its limits however far the mouse goes`; `with a turn button the mouse turns the view only while the button is held`; `mouse motion turns the view, and is spent by the frame that used it` |
| M4 | The rig turns its target to face the camera: a write into the world | `a world ticked with the rig present hashes the same as one ticked without it` |
| M5 | No Scene-view guard: an editor's second run advances the rig again | `an editor Scene view's second run does not move the camera again` |
| M6 | A fixed per-frame fraction instead of a half-life in seconds | `smoothing eases towards a moved target, frame-rate independently`; `the camera keeps the target centred and at its distance as it moves` |

M4:
```diff
@@ -261,6 +261,7 @@ public class ThirdPersonRig(
         atX = transform.x
         atY = transform.y
         atZ = transform.z
+        transform.rotationZ = radians(yawDegrees)
         return true
     }
 
```
M5:
```diff
@@ -202,7 +202,6 @@ public class ThirdPersonRig(
         // An editor's Scene view is being drawn: the pipeline runs every system again for it, through
         // the editor's own camera. This frame's turn and easing already happened, in the run for the
         // capturable frame; doing them again would ease twice as fast while an editor is open.
-        if (resources.viewing.current != null) return
         turn()
         follow()
         place()
```
M6:
```diff
@@ -240,7 +240,7 @@ public class ThirdPersonRig(
             focusZ = desiredZ
             return
         }
-        val t = halfLifeStep(frameTime.frameSeconds, followHalfLife)
+        val t = 0.1f
         focusX += (atX - focusX) * t
         focusY += (atY - focusY) * t
         focusZ += (desiredZ - focusZ) * t
```

The hash test has its own control beside it (`the hash this rests on sees a one-ulp nudge to the
followed transform`), so an equality over a hash that cannot tell two worlds apart would fail there.

## Summary

**`ThirdPersonRig`** (`udea-render/.../camera/ThirdPersonRig.kt`) is a `RenderSystem`, registered in
`PreRender`, that moves a `ModelCamera`; a `ModelRenderSystem` handed the same camera draws through
it. It follows a target `NetId`'s `Transform3D` from `distance` away, `pitchDegrees` down, facing
`yawDegrees` across the ground (Z up, counter-clockwise from +X). It looks at the target raised by
`focusHeight`, so that point is the middle of the picture. The focus eases after the target with a
half-life in seconds. A new target is framed at once rather than eased to. The mouse turns yaw and
pitch at `degreesPerPixel`, with pitch clamped to `minPitchDegrees..maxPitchDegrees` (strictly
inside ±90). An optional `turnButton` turns the view only while that button is held. The rig
writes nothing into the world. It skips the editor Scene view's second run of the render systems,
so an open editor does not ease it twice per frame.

**Facing as plain floats:** `yawDegrees`, `forwardX`, `forwardY`, `rightX`, `rightY`. The KDoc says
to read them where the game samples input (its `IntentSource`), never in a simulation system. The
intent is what is replayed and sent to the server, and a server has no camera. The owner's
2026-09-19 spec change (multiplayer from H1, merged into this branch) is why that matters.

**`PointerMotion`** (`udea-render/.../input/PointerMotion.kt`): the input layer had pointer buttons
and no pointer motion. It is a separate interface from `PointerState`, because presses are spent
once per tick by the sampler and motion once per frame by the camera. `KoolPointer` implements it
from Kool's own `Pointer.delta`, summing every valid pointer.

**Shared, not copied:** the half-life step (`CameraRig` had it privately) and the angle wrap
(`EditorCamera` had it privately) moved to `camera/CameraMath.kt` as `internal` functions, and both
old callers use them. The GL mouse helper moved from `GlKoolPointerTest` (private) to `GlKeys.kt`
(shared by the GL tests), unchanged in behaviour.

**Decisions**, each commented on #248:
- Mouse motion is a new `PointerMotion` beside `PointerState`, not a member of it. It is raw, not
  held back for the UI's verdict. `turnButton` is the UI escape. It defaults to `null`: the mouse
  turns the view whenever it moves, which suits a captured cursor. To change that, set the default
  to `1` (right button), one line.
- The rig reads its target in one private function (`targetPosition`). In round 1 it read
  `Transform3D` as it stands, which is where `ModelRenderSystem` drew then. Since round 2 it reads
  the interpolated pose through #246's `ModelPlacer`: see "Round 2 delta" above. I rejected
  inventing a 3D pose interface here, because it would collide with #246.
- `ThirdPersonRig` takes `RenderResources` in its constructor. It needs `resources.viewing` to skip
  the editor's second run. `ModelRenderSystem` does the same. The KDoc shows the wiring.

**Public surface, stated because §8 lists it:** `ThirdPersonRig`, its properties and `PointerMotion`
are `public`, and nothing outside `udea-render` uses them yet. They are this ticket's deliverable.
The issue asks for a facing "a game can" read, and the consumer is Hollow H2 (#250), which the epic
says uses this rig. `KoolPointer`'s new `motionX`, `motionY` and `spendMotion` are public because
the interface is.

**Size:** the two new test files are 448 and 506 lines, over §6's ~400 trigger. Each is one
subject: the rig's arithmetic, and the rig through a real context. The GL one is one `@Test`
because Kool allows one context per JVM.

**Not exercised:** a touch screen (motion sums every pointer, but no finger was driven); the
`turnButton` path through the real mouse (unit-tested only); iOS/Android (no Kool backend for them
in `udea-render` here, and iOS cannot build on this box). No camera collision with the ground or
walls: the issue does not ask for it. The pitch limits are the only guard, and the default lowest
pitch is -5 degrees.

## `sh gradlew build`

Two full runs, both with `--continue --max-workers=6` and no exclusions.

1. On `c7baf93` (the branch merged with `origin/master` at `763b9c0`), at load average about 20
   (melon-merge's GPU suite and other developers' builds were running). The tail of
   `scratchpad/issue248/build1.log`:

   ```
   BUILD SUCCESSFUL in 3m 24s
   984 actionable tasks: 630 executed, 225 from cache, 129 up-to-date
   Configuration cache entry stored.
   EXIT 0
   DONE
   ```

2. On `31b4fc3`, after merging `origin/master` at `e9dc6a2` (#247). That merge touched
   `udea-physics2d`, `AGENTS.md`, `docs/module-graph.md`, `.claude/WAVE.md` and `BRIEF-247.md`,
   and nothing in `udea-render`. The tail of `scratchpad/issue248/build-2.log`:

   ```
   BUILD SUCCESSFUL in 29s
   975 actionable tasks: 34 executed, 7 from cache, 934 up-to-date
   Configuration cache entry reused.
   EXIT 0
   DONE
   ```

No task failed in either run, the latency budgets included, so none needed re-running alone.

### The GL run, for real

On `31b4fc3`. It uses `--rerun` on each task, because Gradle would otherwise restore them from the
build cache when the inputs are unchanged, and a restored result is not a run:

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew udeaGlTest --rerun udeaAgentGlTest --rerun udeaEditorGlTest --rerun -Pudea.render.requireGl=true
```

The tail of `scratchpad/issue248/glall-3.log`:

```
BUILD SUCCESSFUL in 1m 52s
126 actionable tasks: 12 executed, 114 up-to-date
Configuration cache entry stored.
EXIT 0
DONE
```

The three tasks ran rather than skipped. Summed from their `TEST-*.xml` after that run by
`scratchpad/issue248/glcount.py`:
`udeaGlTest` 23 classes, 24 tests, 0 skipped, 0 failures, 0 errors. `udeaAgentGlTest` 2 classes,
2 tests, 0 skipped, 0 failures, 0 errors. `udeaEditorGlTest` 6 classes, 6 tests, 0 skipped,
0 failures, 0 errors. Every file is timestamped inside that run. The same command without `--rerun`
on `c7baf93` (`glall1.log`) gave the same counts.

## Images

In `/srv/ssd1/workspace/Udea/build/debug-screenshots/`, all from the green GL run above:

- `issue248-fox-00-behind-yaw90.png`: behind the Khronos Fox as it faces +Y (yaw 90). The fox is
  the model on the `ModelRenderSystem` path. It shows the rig framing a model from behind, first angle.
- `issue248-fox-07-behind-yaw-150.png`: the fox turned to face yaw -150, and the real mouse turned
  the camera round behind it again. Second angle, from behind.
- `issue248-fox-01-turned-yaw60.png` to `issue248-fox-06-turned-yaw-90.png`: six steps of the real
  mouse turning the view round the fox, which stays in the middle.
- `issue248-fox-sequence.png`: the eight fox frames tiled, in order.
- `issue248-centred-0.png` to `-3.png`: the red ball, which is the followed entity, at four places in
  the world. The ground moves, the ball stays in the middle at the same size. It proves centring and
  distance; the test measured the numbers from these frames.
- `issue248-centred.png`: those four tiled.
- `issue248-mouse-1-before.png`, `-2-turned-right.png`, `-3-tipped-down.png`: the real mouse
  moving 100 px right (the blue marker swings towards the middle), then 50 px down (the view tips
  down). The ball stays centred throughout.
- `issue248-mouse.png`: those three tiled.

## The issue, criterion by criterion

1. **"GL test: the target stays centred and at the configured distance as it moves, and the mouse
   turns the view."** `GlThirdPersonRigTest`, the evidence command. From `green-gl.xml`:
   ```
   GlThirdPersonRigTest: ball at (0.0, 0.0, 0.0): centroid (239.6007, 159.26306), radius 31.805485, expected 32.301872
   GlThirdPersonRigTest: ball at (4.0, -3.0, 0.5): centroid (239.6007, 159.26306), radius 31.805485, expected 32.301872
   GlThirdPersonRigTest: ball at (-6.0, 5.0, 1.5): centroid (239.6007, 159.26306), radius 31.805485, expected 32.301872
   GlThirdPersonRigTest: ball at (12.0, 9.0, 0.0): centroid (239.6007, 159.26306), radius 31.805485, expected 32.301872
   GlThirdPersonRigTest: at twice the distance the radius is 15.917747, expected 16.108747
   GlThirdPersonRigTest: 100 pixels right turned the yaw from 90 to 70.0
   GlThirdPersonRigTest: the marker moved from x=323.11615 to x=259.95056
   GlThirdPersonRigTest: 50 pixels down tipped the pitch from 20 to 30.0, the eye from 2.0521207 to 3.0
   ```
   The middle of the 480x320 picture is (239.5, 159.5). The tolerance is 2 px on the centroid and
   8% on the radius. The expected radius is `asin(r/d)` through the 45-degree field of view. The
   same numbers are in `ThirdPersonRigTest` without a context: `the camera keeps the target
   centred and at its distance as it moves`, `mouse motion turns the view, and is spent by the frame
   that used it`, and `the view the mouse turned is the one the camera draws`.
2. **"The world hash is unchanged by the rig."** `ThirdPersonRigTest`: `a world ticked with the rig
   present hashes the same as one ticked without it`. That is `WorldHasher.hash` over a
   `SnapshotService` capture of `Transform3D` through its generated replicator, 120 ticks of a
   walking target, the rig following it and turned by mouse motion every frame. Its control is `the
   hash this rests on sees a one-ulp nudge to the followed transform`. Mutation M4 (the rig writes
   the target's heading) turns it red.
3. **"Screenshots from behind a model at two yaw angles."** `issue248-fox-00-behind-yaw90.png` and
   `issue248-fox-07-behind-yaw-150.png`. The fox is on the `ModelRenderSystem` path. The test also
   asserts the fox is in the picture and framed near the middle in every fox shot.

The spec's design points, beyond the checklist:
- *Smoothing in seconds, never ticks.* `followHalfLife` is seconds, fed by `FrameTime`. `smoothing
  eases towards a moved target, frame-rate independently` runs 60 Hz and 120 Hz for the same wall
  time and gets the same answer (M6 turns it red).
- *Pitch and yaw clamped.* Pitch is clamped (`pitch stays inside its limits however far the mouse
  goes`, `a pitch limit is refused when it would put the eye straight above or the limits cross`).
  Yaw wraps rather than clamps: a follow camera turns all the way round.
- *Facing without Kool types.* `the ground facing is the camera's own, as plain floats` checks
  forward against the drawn camera's own line of sight, at four yaws, and right as a quarter turn
  clockwise.

## Regenerated files

None. No replicated component was added or removed. `net-protocol.lock` and
`expected-generated-hashes.txt` are untouched.
