# BRIEF: the pointer points at the world, and at what is standing on it (#262)

    f336184

SHA `f336184` — the branch tip that carries every source change described here. It is the merge of
`origin/master` (`21c2383`) into `issue-262-pointer-picking`; the implementation is the one commit
under it, `0ddfe00`. The branch head is one commit later than `f336184`, because a file cannot name
its own commit, and that commit adds only this brief: `git diff f336184 HEAD --stat` names `BRIEF.md`
and nothing else. **Review the branch tip**; `f336184` is what to read as the change, and it is what
every run below was measured on.

Branch `issue-262-pointer-picking`, from `origin/master` at `d1bf245`, merged up to `origin/master`
at `21c2383` (fetched 2026-09-20 20:19). `git rev-list --left-right --count origin/master...HEAD`
is `0	2`: nothing on master is missing here.

Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-ab6e6404d52fc3f46`.
Scratchpad, where the saved artefacts quoted below live:
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad` (written
`<scratchpad>` from here on).

Issue: <https://github.com/wildware-uk/Udea/issues/262>.

---

## 1. The evidence command

One command, complete, from the worktree root:

```sh
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew :udea-render:udeaGlTest --tests dev.wildware.udea.render.gl.GlGroundPickTest \
  -Pudea.render.requireGl=true --console=plain
```

It runs `GlGroundPickTest`, which holds **both** of the issue's acceptance criteria and measures
them in the pixels a real Kool context drew. Nothing in its loop is arithmetic the test wrote:

```
a chosen pixel -> WorldPointer.aim -> a world point -> a marker entity moved there
              -> Kool draws the frame -> a capture -> the marker's centroid -> back to the pixel?
```

`-Pudea.render.requireGl=true` is not optional: it defaults to false, `$DISPLAY` is empty on this
box, and without it the test **skips** and the build stays green.

### It is green on this branch

Spliced from
`udea-render/build/test-results/udeaGlTest/TEST-dev.wildware.udea.render.gl.GlGroundPickTest.xml`,
written by the forced GL run of 2026-09-20T20:31:28Z on `f336184` (section 4's GL suite):

```xml
<testsuite name="dev.wildware.udea.render.gl.GlGroundPickTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-20T20:31:28.739Z" hostname="wild-home-server" time="4.608">
  <properties/>
  <testcase name="the ground under the cursor is drawn under the cursor, at three zoom levels, and a model under it is named()" classname="dev.wildware.udea.render.gl.GlGroundPickTest" time="4.608"/>
```

Its own measurements, from the `system-out` of that same XML. The full 34 lines are saved at
`<scratchpad>/gl-green-stdout.txt`; the nine cursor rows per zoom are elided here to the first row
and the worst-of-zoom line, and every segment between `...` markers is a consecutive, in-order run
in that file:

```
GlGroundPickTest: view height 8.0: cursor (48.0, 32.0) -> world (1.1313629, 7.919586), drawn at (48.0, 31.5) over 82 pixels: 0.5 px
...
GlGroundPickTest: view height 8.0: worst 0.5 px
GlGroundPickTest: view height 20.0: cursor (48.0, 32.0) -> world (2.828415, 19.79898), drawn at (48.0, 32.0) over 80 pixels: 0.0 px
...
GlGroundPickTest: view height 20.0: worst 0.0 px
GlGroundPickTest: view height 60.0: cursor (48.0, 32.0) -> world (8.48526, 59.396957), drawn at (48.0, 32.0) over 80 pixels: 0.0 px
...
GlGroundPickTest: view height 60.0: worst 0.0 px
GlGroundPickTest: the red cube draws at (240.0, 194.16983) and picking there gave NetId(#0@0)
GlGroundPickTest: the green cube draws at (302.2254, 129.116) and picking there gave NetId(#1@0)
GlGroundPickTest: the blue cube draws at (172.11774, 126.287575) and picking there gave NetId(#2@0)
GlGroundPickTest: picking open ground at (40.0, 40.0) gave NetId.NONE
```

The 0.5 px at view height 8 is the marker's own rasterisation bias, not the un-projection's: the
marker lies `0.01` units above the ground so it is not z-fighting it, and at that zoom
`0.01 * cos(30) / 4 * 160 = 0.35` px of that lift shows. Nothing subtracts it; it is inside the
criterion and it shrinks as the view widens, which is what the 0.0 at 20 and 60 says. The
arithmetic's own error, measured without a rasteriser, is in `CameraPickTest`: under a thousandth of
a pixel.

### It goes red when the feature is reverted

Mutation **m10** below, one term in `CameraPick`'s orthographic ray origin. The failing run's own
XML, saved before a later run could overwrite it, is at `<scratchpad>/m10-red-GlGroundPickTest.xml`;
spliced from it:

```xml
<testsuite name="dev.wildware.udea.render.gl.GlGroundPickTest" tests="1" skipped="0" failures="1" errors="0" timestamp="2026-09-20T20:07:34.423Z" hostname="wild-home-server" time="3.94">
  <properties/>
  <testcase name="the ground under the cursor is drawn under the cursor, at three zoom levels, and a model under it is named()" classname="dev.wildware.udea.render.gl.GlGroundPickTest" time="3.94">
    <failure message="org.opentest4j.AssertionFailedError: the ground point under the cursor was drawn 23.323254 pixels away from the cursor at view height 8.0, cursor (432.0, 32.0), past the one-pixel criterion" type="org.opentest4j.AssertionFailedError">
```

**The magnitude is the check, not the redness.** m10 widens the orthographic half-height by 10%, so
a cursor `d` pixels from the frame centre lands about `0.1 * d` pixels out. The corner it reports
first is (432, 32), which is 192 px right and 128 px up from the centre of a 480x320 frame:
`0.1 * hypot(192, 128) = 23.07` px predicted, **23.32** measured. The 0.25 px difference is the same
marker bias the green run reports as 0.5 px at that zoom.

---

## 2. What this does, and what I decided

`PointerState` exposed buttons and nothing else. There was no cursor position outside the editor,
and the only screen-to-world picking in the tree was `udea-editor`'s `view/PickBounds.kt`. This puts
a pointer into the intent stream, and it puts it there **in world units**.

### The shape

- **`udea-render/src/commonMain/.../pick/CameraPick.kt`** — the un-projection. It takes a
  `ModelCamera`, a frame size and a view pixel, and gives back either a ray or the point where that
  ray meets a horizontal plane. `Orthographic` and `Perspective` both. It rebuilds the camera basis
  the way `writeViewMatrix` does rather than inverting a matrix, so there is one description of
  where the camera is and no 4x4 inverse to disagree with it; `CameraPickTest` checks it against the
  published matrices anyway.
- **`.../pick/EntityPicker.kt`** — "what is drawn under this pixel", over `PickBounds` sources. The
  ordering rule (later source first, then nearer the eye, then reported later) was **lifted out of
  `udea-editor`, not copied**: `ScenePicker` now implements the new `PickProjector` and delegates to
  `EntityPicker`, so the editor and the game share one comparator. The editor's own suites are
  unchanged and are the refactor's control.
- **`.../pick/WorldPointer.kt`** — the one place a pixel becomes a world point. A `RenderSystem`, so
  it runs on the render thread; it exposes an `IntentSource` and writes `setPointer`, `setScroll`,
  `setDragStart` and `setDragEnd` into the tick's `Intent`.
- **`.../input/PointerPosition.kt`** — `PointerPosition` and `PointerWheel`, the two backend seams,
  implemented by `KoolPointer` beside the `PointerState` and `PointerMotion` it already had.
- **`.../camera/IsoPanControl.kt`** — edge-of-screen and middle-drag panning and wheel zoom for
  `IsometricRig`, which is the issue's part (d). Edge scroll is a speed scaled by the zoom; a drag
  keeps the same ground under the hand at any zoom.
- **`udea-replay`** — `InputSample` carries the pointer behind a new presence bit and a sub-mask, so
  a tick with no pointer costs nothing, and `.udearep` gains **format 3**.

### Decision 1: world units only, and a fence that says so

The issue asks in (a) for "the pointer's screen position" in the intents and in (b) for presentation
to compute the ground hit "and send it as a world-space intent". Those are in tension, and the
tie-breaker is in the issue itself: *"the simulation never reads the camera, and that is structural
rather than stylistic… A screen coordinate must not survive into `Simulation.step()`."* A recorded
pixel replays differently on another window size, which is the same defect one layer down.

So **no pixel reaches `Intent`**, and `PointerIntentTest > the intent names no pixel` reflects over
`Intent`'s public fields and fails on a name that means a screen coordinate. Mutations m8 and m9 are
its two sides: a real `pointerPixelX` field goes red, a KDoc line that merely mentions a pixel stays
green.

Recorded on the issue. If the owner wants the raw pixel carried as well, the reversal is additive:
add the two fields to `Intent` and `InputSample`, widen the sub-mask by a bit, and delete that one
fence test — no wire id and no component moves.

### Decision 2: the editor's picking moved by reuse, not by relocation

`UDEA-MG-012` fails the build if a `udea-editor` class reaches a release classpath, so the editor's
picker could not simply be depended upon. `PickBounds` itself was already in `udea-render`; what sat
in `udea-editor` was the ordering and the projection. Those moved down into `udea-render`, and the
editor now calls them. `udeaVerifyEditorAbsent` is green on every module (section 4), and mutation
m3 shows the editor's own test still covers the moved rule.

### Decision 3: format 3 is the lowest version that can express a recording

`ReplayByteCompatibilityTest` holds golden bytes for a recording with no edits. Bumping the version
unconditionally would have changed those bytes for files carrying nothing new. So `ReplayRecording`
writes **the lowest version that can express what it holds**: 1 with no edits and no pointer, 2 with
edits, 3 with a pointer. This build reads all three, and `AGENTS.md` says so in the same change.

### A live defect this ticket exposed, and fixed

`MobaReplay.capture` reuses one `InputSample` for a whole match and wrote the pointer
*conditionally*. Every field it wrote before this change was written unconditionally, so the reuse
was safe; a conditional write made a stale drag-start persist for every later tick. The fix is one
line, `sample.clear()` at the top of `capture`; mutation m5 removes it and goes red. It was posted
to the dashboard as a surprise when it was found.

### What I did not do

- No new replicated component, so **no change to `udea-codegen/net-protocol.lock` and none to
  `expected-generated-hashes.txt`** (section 7).
- No `docs/contracts/` file touched.
- No new agent tool. The pointer is a presentation seam, and `input.*` already drives intents;
  `/tools` on a live instance of this branch reported 54 tools over 9 toolsets, which is what
  `origin/master` publishes.

---

## 3. The failing tests, first

Each was written before the code and watched fail for the reason the issue describes.

| File | Tests | What it holds |
|---|---|---|
| `udea-render/src/jvmTest/.../pick/CameraPickTest.kt` | 9 | the un-projection round trip at three zooms, and a control that a camera read at the wrong zoom misses by the zoom error |
| `udea-render/src/jvmTest/.../pick/EntityPickerTest.kt` | 7 | what is under the cursor, in both report orders |
| `udea-render/src/jvmTest/.../pick/WorldPointerTest.kt` | 8 | window pixels to world, the y flip, the entity reaching the intent |
| `udea-render/src/jvmTest/.../input/PointerIntentTest.kt` | 7 | the intent's pointer block, `clear`, `copyFrom`, `isIdle`, and the no-pixel fence |
| `udea-render/src/jvmTest/.../camera/IsoPanControlTest.kt` | 7 | edge scroll as a speed, middle-drag, wheel zoom |
| `udea-render/src/jvmTest/.../gl/GlGroundPickTest.kt` | 1 | both acceptance criteria, through a real GL context |
| `udea-replay/src/commonTest/.../PointerSampleTest.kt` | 7 | the pointer over the wire, the presence bits, the three format versions |
| `moba/desktop/src/test/.../replay/MobaPointerReplayTest.kt` | 2 | a recorded match replays the same orders |

### The mutation table

Every row below carries the **literal `git diff` of the run that produced its failure**, spliced
from `<scratchpad>/mut-<tag>.diff`, with the failing test names and counts read out of
`<scratchpad>/mut-<tag>.log`. Each mutation was reverted before the next was applied. Rows m4 to m10
were taken while the working tree also carried the `EntityPickerTest` correction described under
m3b (since committed in `0ddfe00`); their diffs below show the production hunk only, and that test
hunk is printed in full under m3b.

#### m1

**m1 — the orthographic ray starts 10% too wide.** `CameraPick` is the un-projection every other piece calls.

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/pick/CameraPick.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/pick/CameraPick.kt
index c2c369c..5e51339 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/pick/CameraPick.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/pick/CameraPick.kt
@@ -139,7 +139,7 @@ public class CameraPick(
                 out.directionZ = dz / length
             }
             ModelProjection.Orthographic -> {
-                val halfHeight = camera.viewHeight / 2f
+                val halfHeight = camera.viewHeight / 2f * 1.1f
                 val sideways = across * halfHeight * aspect
                 val upward = up * halfHeight
                 out.originX = camera.eyeX + rightX * sideways + upX * upward
```

Failing tests:

- `CameraPickTest > the ground point under a pixel comes back to that pixel, at three zoom levels` — `the point under the cursor draws 33.94092 pixels from the cursor`
- `CameraPickTest > a camera read at the wrong zoom misses by the zoom error` — `a 10% zoom error moved the pixel 154.20386 px when the arithmetic of the error says 73.43025 px: the round trip is not measuring the zoom`
- `WorldPointerTest > the world point under a window pixel is the point drawn at that pixel` — `at a view height of 8.0 world units the ground point under a pixel came back 73.4302 pixels away, past the one-pixel criterion; worst at (0.0, 720.0) -> (18.191391, 2.503685)`

`366 tests completed, 3 failed` in `:udea-render:jvmTest`. **The magnitude is the check.** The worst pixel is a corner of the 1280x720 frame, 640 px across and 360 px up from the centre, and a 10% error there predicts `0.1 * hypot(640, 360) = 73.43` px: measured **73.4302**. The third failure is the control test that exists to say the round trip is sensitive to the zoom at all, and it reports the *doubled* figure (154.20 against the 73.43 its own arithmetic predicts) because the mutation moves the reading and the expectation apart — which is what it is for.

#### m2

**m2 — the y flip is removed**, so window pixels (y down from the top) are read as view pixels (y up from the bottom).

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/pick/WorldPointer.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/pick/WorldPointer.kt
index 6ab6380..3a243c7 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/pick/WorldPointer.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/pick/WorldPointer.kt
@@ -162,7 +162,7 @@ public class WorldPointer(
         pick.fit(width, height)
         // Window pixels run down from the top; view pixels run up from the bottom. This is the one
         // line that turns them round, so nothing below `CameraPick` has to know there are two.
-        val viewY = height - pixelY
+        val viewY = pixelY
         isOnWorld = pick.groundUnder(pixelX, viewY, projector.groundZ, hit)
         if (isOnWorld) {
             worldX = hit.x
```

Failing tests:

- `WorldPointerTest > the top and the bottom of the window are different places, and the right way round` — `the top of the picture is nearer the camera than the bottom, so \`y\` is upside down`
- `WorldPointerTest > the world point under a window pixel is the point drawn at that pixel` — `the point under the cursor draws 480.00018 pixels from the cursor`
- `WorldPointerTest > the entity under the cursor reaches the intent` — `the model under the cursor was not picked ==> expected: <NetId(#5@0)> but was: <NetId.NONE>`

`366 tests completed, 3 failed`. The magnitude again: the aimed pixel is 120 px below the middle of a 720-tall window, and reading it the wrong way up puts it 120 px above, so the answer is drawn `2 * (360 - 120) = 480` px from the cursor. Measured **480.00018**.

#### m3

**m3 — `EntityPicker.FRONT_FIRST` loses its depth term**, so two models under the same pixel are ordered only by who reported last. **This is the near-miss, and I am disclosing it rather than being shown it.**

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/pick/EntityPicker.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/pick/EntityPicker.kt
index dfaa9c9..5d689ce 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/pick/EntityPicker.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/pick/EntityPicker.kt
@@ -198,7 +198,6 @@ public class EntityPicker(
 
         /** Later source first; then nearer the eye; then reported later. See the class KDoc. */
         val FRONT_FIRST: Comparator<PickedBounds> = compareByDescending<PickedBounds> { it.source }
-            .thenBy { it.depth }
             .thenByDescending { it.order }
     }
 }
```

Failing tests:

- `ScenePickerTest > of two model boxes under the pointer the one nearer the eye is in front, whatever order they were reported in` (`udea-editor`)

`120 tests completed, 1 failed` in `:udea-editor:test` — and **`:udea-render:jvmTest` stayed green**. My own `EntityPickerTest` reported the far box first only, and the surviving fallback rule ("reported later is in front") happened to give the same answer for that one order. It was a test that could not fail. The editor's own suite, which has always tried both orders, is what caught it.

#### m3b

**m3b — the same mutation, after the test was corrected** to report the two boxes in both orders. The test hunk below is the correction, and it is part of `0ddfe00`.

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/pick/EntityPicker.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/pick/EntityPicker.kt
index dfaa9c9..5d689ce 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/pick/EntityPicker.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/pick/EntityPicker.kt
@@ -198,7 +198,6 @@ public class EntityPicker(
 
         /** Later source first; then nearer the eye; then reported later. See the class KDoc. */
         val FRONT_FIRST: Comparator<PickedBounds> = compareByDescending<PickedBounds> { it.source }
-            .thenBy { it.depth }
             .thenByDescending { it.order }
     }
 }
diff --git a/udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/pick/EntityPickerTest.kt b/udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/pick/EntityPickerTest.kt
index aab3f54..d85af1f 100644
--- a/udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/pick/EntityPickerTest.kt
+++ b/udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/pick/EntityPickerTest.kt
@@ -67,20 +67,33 @@ class EntityPickerTest {
     }
 
     @Test
-    fun `of two models under the cursor the nearer one is first`() {
+    fun `of two models under the cursor the nearer one is first, whatever order they were reported in`() {
         // Along the camera's line of sight, so both draw at the same pixel and only depth separates
-        // them. The far one is reported *first*, so a picker that kept the report order would be
-        // wrong - which is the point of stating it this way round.
-        val pick = isoPick()
+        // them.
+        //
+        // **Both report orders**, and that clause is the whole test. The first version of this file
+        // reported the far one first only - and when the depth term was taken out of the comparator
+        // as a mutation, this test stayed green, because the fallback rule ("the one reported later
+        // is in front") happened to give the same answer. It was a test that could not fail, and it
+        // was `udea-editor`'s own `ScenePickerTest` - which has always tried both orders - that went
+        // red instead and said so.
         val far = worldAlongSight(FAR_ALONG_SIGHT)
         val near = worldAlongSight(NEAR_ALONG_SIGHT)
-        val both = Boxes(listOf(Box(TWO, far.x, far.y, far.z), Box(ONE, near.x, near.y, near.z)))
-        val picker = EntityPicker(CameraPickProjector(pick)) { listOf(both) }
-
-        val at = ViewPoint()
-        assertTrue(pick.project(near.x, near.y, near.z + BOX_HALF, at), "the near model projected nowhere")
-
-        assertEquals(listOf(ONE, TWO), picker.under(at.x, at.y), "the far model was picked before the near one")
+        for (nearFirst in listOf(false, true)) {
+            val pick = isoPick()
+            val reported = listOf(Box(TWO, far.x, far.y, far.z), Box(ONE, near.x, near.y, near.z))
+            val both = Boxes(if (nearFirst) reported.reversed() else reported)
+            val picker = EntityPicker(CameraPickProjector(pick)) { listOf(both) }
+
+            val at = ViewPoint()
+            assertTrue(pick.project(near.x, near.y, near.z + BOX_HALF, at), "the near model projected nowhere")
+
+            assertEquals(
+                listOf(ONE, TWO),
+                picker.under(at.x, at.y),
+                "reported ${if (nearFirst) "near first" else "far first"}: the far model came before the near one",
+            )
+        }
     }
 
     @Test
```

Failing tests:

- `EntityPickerTest > of two models under the cursor the nearer one is first, whatever order they were reported in` (`udea-render`)
- `ScenePickerTest > of two model boxes under the pointer the one nearer the eye is in front, whatever order they were reported in` (`udea-editor`)

`366 tests completed, 1 failed` and `120 tests completed, 1 failed`: both modules red, which is what the rule moving down a module should look like.

#### m4

**m4 — `InputSample` writes `NetId.NONE` in place of the entity the player was pointing at**, so the ground point survives the wire and the target does not.

```diff
diff --git a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/InputSample.kt b/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/InputSample.kt
index 98a4d94..a858567 100644
--- a/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/InputSample.kt
+++ b/udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/InputSample.kt
@@ -281,7 +281,7 @@ public class InputSample(
         if (parts and POINTER_AT != 0) {
             sink.f32(pointerX)
             sink.f32(pointerY)
-            sink.i32(pointerEntity.raw)
+            sink.i32(NetId.NONE.raw)
         }
         if (parts and POINTER_SCROLL != 0) sink.f32(scroll)
         if (parts and POINTER_DRAG_START != 0) {
```

Failing tests:

- `PointerSampleTest > every part of the pointer survives an encode and a decode` (`udea-replay`)
- `MobaPointerReplayTest > every order a player gave with the mouse comes back out of the recording` (`moba:desktop`)

`122 tests completed, 1 failed` and `106 tests completed, 1 failed`. Both halves of the second acceptance criterion: the codec's own test and the end-to-end replay.

#### m5

**m5 — `sample.clear()` is removed from `MobaReplay.capture`.** This is the live defect this ticket exposed: the recorder reuses one sample, and a conditionally-written pointer event left standing reads as input nobody gave.

```diff
diff --git a/moba/desktop/src/main/kotlin/dev/wildware/moba/replay/MobaReplay.kt b/moba/desktop/src/main/kotlin/dev/wildware/moba/replay/MobaReplay.kt
index 5a514dd..ba8de2e 100644
--- a/moba/desktop/src/main/kotlin/dev/wildware/moba/replay/MobaReplay.kt
+++ b/moba/desktop/src/main/kotlin/dev/wildware/moba/replay/MobaReplay.kt
@@ -188,7 +188,6 @@ public object MobaReplay {
         // beginning the same drag again, and so would every tick after it. The axes and the actions
         // are overwritten unconditionally below and do not care; the pointer is why this line is
         // here, and it is the `Q.Axis8` shape - a value left standing reads as input nobody gave.
-        sample.clear()
         for (axis in 0 until SCHEMA.axisCount) {
             val id = dev.wildware.udea.render.input.AxisId(axis)
             sample.setAxis(axis, intent.axisX(id), intent.axisY(id))
```

Failing tests:

- `MobaPointerReplayTest > every order a player gave with the mouse comes back out of the recording`

`106 tests completed, 1 failed`. The mutation restores the code exactly as it was before this branch, which is what makes it the real shape rather than a shape that fails.

#### m6

**m6 — `Intent.clear()` no longer resets `hasPointer`**, so a tick inherits the previous tick's aim.

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/input/Intent.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/input/Intent.kt
index 822a352..cb6a801 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/input/Intent.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/input/Intent.kt
@@ -199,7 +199,6 @@ public class Intent(
         presses.fill(0)
         axisX.fill(0f)
         axisY.fill(0f)
-        hasPointer = false
         pointerX = 0f
         pointerY = 0f
         pointerEntity = NetId.NONE
```

Failing tests:

- `PointerIntentTest > clearing an intent forgets the pointer`

`366 tests completed, 1 failed`.

#### m7

**m7 — the edge-scroll speed drops its `seconds` term**, turning a speed into a per-frame step, which is the frame-rate-dependent movement the intent system exists to prevent.

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/camera/IsoPanControl.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/camera/IsoPanControl.kt
index e10b57c..0a78680 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/camera/IsoPanControl.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/camera/IsoPanControl.kt
@@ -193,7 +193,7 @@ public class IsoPanControl(
         val forward = -push(y, height.toFloat())
         if (right == 0f && forward == 0f) return
         // Scaled by the zoom, so a scroll crosses the picture in the same time however far out it is.
-        val speed = edgeSpeed * seconds * rig.viewHeight / ZOOM_REFERENCE
+        val speed = edgeSpeed * rig.viewHeight / ZOOM_REFERENCE
         rig.panBy(right * speed, forward * speed)
     }
```

Failing tests:

- `IsoPanControlTest > an edge scroll is a speed, so twice the time moves twice as far`

`366 tests completed, 1 failed`.

#### m8

**m8 — a screen coordinate is added to `Intent`.** The fence's positive case.

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/input/Intent.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/input/Intent.kt
index 822a352..9e26f86 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/input/Intent.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/input/Intent.kt
@@ -67,6 +67,10 @@ public class Intent(
     public var hasPointer: Boolean = false
         private set
 
+    /** MUTATION m8: a screen coordinate, which is what the fence exists to refuse. */
+    public var pointerPixelX: Float = 0f
+        private set
+
     /** Where on the ground the pointer was, in world units. Meaningless unless [hasPointer]. */
     public var pointerX: Float = 0f
         private set
```

Failing tests:

- `PointerIntentTest > the intent names no pixel`

`366 tests completed, 1 failed`.

#### m9

**m9 — the control for m8: prose that merely mentions a pixel**, a screen and a view coordinate, in a KDoc line on a field whose type and name are unchanged.

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/input/Intent.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/input/Intent.kt
index 822a352..c5d3909 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/input/Intent.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/input/Intent.kt
@@ -63,7 +63,8 @@ public class Intent(
     // no camera: `WorldPointer` un-projects on the render thread and puts the answer here, so a
     // tick stays a function of its inputs and a recording replays the same orders on any window.
 
-    /** Whether the player was pointing at the world at all this tick. */
+    // MUTATION m9, the control: prose that merely mentions a pixel, a screen and a viewX.
+    /** Whether the player was pointing at the world at all this tick. Never a pixel on a screen. */
     public var hasPointer: Boolean = false
         private set
```

**Green**, exit code 0, `366 tests completed` with no failures. A fence that fails on prose would be as wrong as one that passes on a real field, so both sides were run.

#### m10

**m10 — m1's mutation, measured through a real GL context.** This is the proof for the evidence command in section 1.

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/pick/CameraPick.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/pick/CameraPick.kt
index c2c369c..5e51339 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/pick/CameraPick.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/pick/CameraPick.kt
@@ -139,7 +139,7 @@ public class CameraPick(
                 out.directionZ = dz / length
             }
             ModelProjection.Orthographic -> {
-                val halfHeight = camera.viewHeight / 2f
+                val halfHeight = camera.viewHeight / 2f * 1.1f
                 val sideways = across * halfHeight * aspect
                 val upward = up * halfHeight
                 out.originX = camera.eyeX + rightX * sideways + upX * upward
```

Failing tests:

- `GlGroundPickTest > the ground under the cursor is drawn under the cursor, at three zoom levels, and a model under it is named`

`1 test completed, 1 failed` in `:udea-render:udeaGlTest` under xvfb with `-Pudea.render.requireGl=true`; the message and its arithmetic are in section 1.

---

## 4. The runs

Every run below is on `f336184`, the tree this brief describes. Logs are kept, not just their
verdicts: `<scratchpad>/262-fullbuild.log`, `<scratchpad>/final-units.log`,
`<scratchpad>/final-gl.log`.

### `sh gradlew build`, no exclusions

```sh
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew build --continue --no-configuration-cache --max-workers=6
```

Tail of `<scratchpad>/262-fullbuild.log`, spliced:

```
> Task :moba:game:build
> Task :build-logic:validatePlugins FROM-CACHE
> Task :build-logic:check
> Task :build-logic:version-catalog:check UP-TO-DATE
> Task :build-logic:udeaBuildLogicCheck
> Task :check
> Task :build

[Incubating] Problems report is available at: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-ab6e6404d52fc3f46/build/reports/problems/problems-report.html

BUILD SUCCESSFUL in 2m
1122 actionable tasks: 34 executed, 10 from cache, 1078 up-to-date
```

Exit code read off the marker file the runner wrote, not off the process list:
`<scratchpad>/fullbuild2.done` contains `DONE 0`.

The gates that matter to this change all ran in that build rather than being skipped or cached:
`:udeaVerifyAgentsMd`, `:udeaVerifyWiki`, `:udea-codegen:udeaCheckProtocolLock`,
`:udea-core:udeaCheckProtocolLock`, `:moba:game:udeaCheckProtocolLock`,
`:hollow:game:udeaCheckProtocolLock`, `:udea-render:udeaVerifyHeadless`,
`:udea-render:udeaVerifyNoLibGdx` and `:build-logic:udeaBuildLogicCheck` are all in the log without
an `UP-TO-DATE`. `udeaVerifyContracts`, `udeaVerifyDeterminism` and every module's
`udeaVerifyModuleGraph` and `udeaVerifyEditorAbsent` are `UP-TO-DATE`, which is Gradle saying their
inputs did not move: no contract file and no module arrow changed here.

**`:udea-assets-compiler:udeaDaemonBudget` did not run**, and that is not an omission: it is not on
`check`, and nothing in this change touches the asset daemon. Saying so is more useful than a
figure I did not measure.

### The GL suites, for real, under xvfb

`-Pudea.render.requireGl` defaults to false and `$DISPLAY` is empty here, so the GL suites **skip**
inside an ordinary `build` and the build still goes green. This change is squarely inside
`udea-render`, so they were run for real, with `--rerun-tasks` so that no task could answer from
the cache:

```sh
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true \
  --rerun-tasks --console=plain
```

```
BUILD SUCCESSFUL in 2m 9s
117 actionable tasks: 117 executed
```

Counted out of the JUnit XML rather than trusting `BUILD SUCCESSFUL`, because a skipped GL suite is
also successful:

| Task | Classes | Tests | Failures | Skipped |
|---|---|---|---|---|
| `udeaGlTest` | 27 | 28 | 0 | **0** |
| `udeaAgentGlTest` | 2 | 2 | 0 | **0** |
| `udeaEditorGlTest` | 6 | 6 | 0 | **0** |

The zero in the skipped column is the load-bearing number: with the flag off, those columns read
28, 2 and 6 skipped and the same `BUILD SUCCESSFUL` comes out.

### The touched modules, forced

```sh
sh gradlew :udea-render:jvmTest :udea-replay:jvmTest :udea-editor:test :moba:desktop:test \
  --rerun-tasks --console=plain
```

```
BUILD SUCCESSFUL in 44s
199 actionable tasks: 199 executed
```

| Task | Classes | Tests | Failures | Skipped |
|---|---|---|---|---|
| `:udea-render:jvmTest` | 45 | 366 | 0 | 0 |
| `:udea-replay:jvmTest` | 18 | 122 | 0 | 0 |
| `:udea-editor:test` | 20 | 120 | 0 | 0 |
| `:moba:desktop:test` | 23 | 106 | 0 | 0 |

714 tests across the four, plus the 36 GL tests above.

**Why `--rerun-tasks` is in both commands, and this is a near-miss worth stating.** I re-ran
mutations m1 and m2 at the end to recover their failure messages, which had been overwritten. After
reverting them, `sh gradlew :udea-render:jvmTest` came back green **in 5 seconds** — Gradle
correctly called the task up to date, because the inputs had returned to a state it had already
seen. The XML on disk was still the *mutated* run's. A brief quoting those files would have been
quoting a red run while reporting green. The forced runs above are what the numbers come from.

### iOS

Not built and not tested. iOS cannot build on this Linux box, and nothing here claims otherwise.

### Driving the real game

`:moba:desktop:run -PdebugPort=7841` under xvfb, on this branch. `/health` answered
`{"ok":true,"frame":588,"tick":525,"paused":false,"renderMode":"Offscreen","role":"standalone","sessionId":"s-53ee","editor":false}`,
`/tools` listed 9 toolsets and 54 tools (`assets` 12, `diag` 4, `events` 3, `game` 1, `input` 6,
`render` 6, `replay` 6, `time` 8, `world` 8), and `render.screenshot` produced
`issue262-live-moba.png` — the tool answered
`{"artifactId": "cap_0008", "w": 1280, "h": 720, "tick": 12141}`. The instance was stopped
afterwards and its three processes confirmed gone by reading `/proc/<pid>/cmdline` before signalling
each one; nothing is left running.

This is a **regression check, not a demonstration of the feature**, and I would rather say so than
dress it up: `moba.agent` runs Offscreen with no window and therefore no mouse, so there is no
pointer for a bridge session to move. The feature's own evidence is the GL test, where a real
context, a real camera and a real frame are all present. What the live run proves is that the
`Intent`, `KoolPointer` and `RenderPipeline` changes did not break a game that boots and draws.

The `game-bridge` MCP tools could not launch it: the generated command is `./gradlew`, the wrapper
is checked in without its executable bit, and the bridge sets no `JAVA_HOME`, so it died first with
`Permission denied` and then with the bare `25.0.2`. I launched it myself instead. `chmod +x gradlew`
was reverted; `git status` on the final tree is clean.

---

## 5. The images

All in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`. Every one except the last was written
by the forced GL run of 2026-09-20T20:31Z described in section 4, and copied across afterwards.

| File | What it shows | What it proves |
|---|---|---|
| `issue262-cursor-grid.png` | a 3x3 collage of the nine cursor positions at view height 20, each tile labelled with its column | the magenta marker sits in the same corner, edge or centre of every tile as the cursor it was placed from — criterion one, seen rather than measured |
| `issue262-cursor-r1c1.png` … `issue262-cursor-r9c9.png` (9 files) | the nine tiles of that collage, unscaled | each tile on its own, at full size, for a reader who wants to measure one |
| `issue262-ground-zoom8.png` | the marker at view height 8, a unit filling a quarter of the screen | the closest zoom, where the marker's own 0.5 px bias is largest |
| `issue262-ground-zoom20.png` | the marker at the default view height of 20 | the middle zoom, 0.0 px |
| `issue262-ground-zoom60.png` | the marker at view height 60, a whole map | the widest zoom, 0.0 px — the three together are the "at 3 zoom levels" half of criterion one |
| `issue262-cubes.png` | three cubes, red, green and blue, on the isometric ground | the fixture for criterion two: each was projected to a pixel through the camera's published matrices, and picking that pixel named that cube |
| `issue262-live-moba.png` | `moba` running on this branch, seen through `render.screenshot` at frame 18198 | the regression check of section 4: the game still boots, draws its ground and draws its HUD |

I looked at them rather than only reading the numbers. Nothing is clipped, nothing collides, and the
marker is inside the frame in all nine tiles including the corners.

---

## 6. The issue, criterion by criterion

> **Clicking the ground sends the world point that lies under the cursor, within one render pixel,
> at 3 zoom levels.**

`GlGroundPickTest`, the evidence command. Three view heights — 8, 20 and 60 world units — nine
cursor positions each, corners included, measured by placing a marker at the answer and reading back
where Kool drew it. Worst error **0.5 px, 0.0 px and 0.0 px** (section 1's transcript), against a
criterion of one. Pictures: `issue262-ground-zoom8.png`, `issue262-ground-zoom20.png`,
`issue262-ground-zoom60.png` and the nine-tile collage. Mutation m10 is the proof it can fail, with
the size of the failure predicted before it was measured.

The arithmetic on its own, without a rasteriser in the loop, is `CameraPickTest > the ground point
under a pixel comes back to that pixel, at three zoom levels`, which holds the same three zooms to
under a thousandth of a pixel; `CameraPickTest > a camera read at the wrong zoom misses by the zoom
error` is its control, and it is what mutation m1 makes report the wrong figure.

> **Clicking a model returns that entity.**

Second half of `GlGroundPickTest`: three cubes with `NetId`s, each projected to a pixel through the
camera's published matrices and then picked at that pixel.

```
GlGroundPickTest: the red cube draws at (240.0, 194.16983) and picking there gave NetId(#0@0)
GlGroundPickTest: the green cube draws at (302.2254, 129.116) and picking there gave NetId(#1@0)
GlGroundPickTest: the blue cube draws at (172.11774, 126.287575) and picking there gave NetId(#2@0)
GlGroundPickTest: picking open ground at (40.0, 40.0) gave NetId.NONE
```

The fourth line is the half that matters most: a picker that answered whatever it looked at last
would pass the first three. Unit-level cover is `EntityPickerTest` (7) and
`WorldPointerTest > the entity under the cursor reaches the intent`. Picture: `issue262-cubes.png`.

> **A replay reproduces the same orders.**

`MobaPointerReplayTest > every order a player gave with the mouse comes back out of the recording`
records a match of pointer orders through the real `MobaReplay` recorder, writes a `.udearep`,
reads it back and compares every tick's pointer, entity, scroll and drag. `PointerSampleTest` (7)
holds the codec underneath it, and `a recording with a pointer is format 3 and one without is not`
is the version rule: a recording carrying a pointer is written as 3, one carrying neither a pointer
nor an edit stays at `EDITLESS_FORMAT_VERSION`. The middle case, a recording with edits and no
pointer, is held by `ReplayFormatTest` and `EditingSessionReplayTest`, which assert
`EDITS_FORMAT_VERSION` and were already there. Mutations m4 and m5 are the proof the replay path
goes red.

> **(a) intents carry the pointer's screen position plus scroll, drag start and drag end, recorded
> in replays like the other intents**

Carried, and recorded — **in world units, not screen position**. That is decision 1 in section 2,
taken from the issue's own structural rule, commented on the issue, and fenced by
`PointerIntentTest > the intent names no pixel` with both sides run (m8 red, m9 green).

> **(b) presentation computes the ground-plane hit (z = 0) from the current camera and sends it as a
> world-space intent — the sim never reads the camera**

`WorldPointer` is a `RenderSystem`. `CameraPick` lives in `udea-render`. `Intent` gained no camera
and no pixel. `udeaVerifyModuleGraph` and `udeaVerifyHeadless` are green, so `udea-core` still
compiles with no GL on its classpath. `groundZ` is configurable and defaults to 0.

> **(c) an entity pick under the cursor from the models' bounds, reusing `PickBounds` outside the
> editor**

`EntityPicker` over `PickBounds`, with `RenderPipeline.pickable` exposing the game's own pickable
systems. Reused rather than reimplemented: `ScenePicker` in `udea-editor` now delegates to it, and
mutation m3 shows the editor's own suite still covers the shared comparator.

> **(d) edge-of-screen and middle-drag camera panning helpers for the RTS camera**

`IsoPanControl`, with `IsoPanControlTest` (7): edge scroll is a speed (m7), a middle-drag keeps the
same ground under the hand at any zoom, and the wheel zooms by a notch factor.

### What I did not exercise

- **A perspective camera through GL.** `CameraPick` handles `Perspective` and `CameraPickTest`
  covers it arithmetically, but `GlGroundPickTest` draws through the orthographic isometric rig,
  because that is the camera the issue is about. The perspective path is tested, not pictured.
- **A window that is not 480x320 or 1280x720.** The frame size is an argument of every `fit` and
  `aim` call, but the unit suites pass one constant each (1280x720) and the GL test draws at
  480x320. Nothing resizes mid-run, and no test picks a non-16:9 aspect.
- **Two pointers, or a touch screen.** `PointerPosition` describes one cursor. Nothing here is
  hostile to a second, but nothing here has one.
- **The editor's Scene tab driven by hand through the new code.** Its automated suites (120 tests,
  including `ScenePickerTest`) are the cover; I did not sit in the editor window and drag.

---

## 7. Regenerated files

**None, and that is a claim I checked rather than assumed.** This change adds no `@Replicated`
component and no `@Net` field, so no id moved:

- `udea-codegen/net-protocol.lock` — unchanged. `git diff origin/master...HEAD -- udea-codegen/net-protocol.lock` is empty, and `udeaCheckProtocolLock` ran (not `UP-TO-DATE`) in the full build on four projects and passed.
- `udea-codegen/src/test/resources/expected-generated-hashes.txt` — unchanged, same diff check.
- `docs/contracts/` — untouched; `docs/contracts.lock` untouched.

The pointer travels in `InputSample`, which is `.udearep`'s own format and not the wire protocol, so
it is versioned by `ReplayFormat.FORMAT_VERSION` (now 3) rather than by `protoHash`.

---

## 8. My own pass over the diff

Against `docs/engineering-standards.md` section 8 and `AGENTS.md`'s "Do not", item by item, because
that is the list the reviewer works from:

- **No rule from section 1 reproduced.** The editor's picking ordering was the one piece of logic
  that would have been copied, and it was moved instead.
- **No `public` nobody outside the module uses.** `PickProjector`, `PickedBounds` and
  `CameraPickProjector` are public because `udea-editor` calls them from another module;
  `PickedBounds.source`, `.depth` and `.order` are `internal`.
- **No test that cannot fail.** Ten mutations, each with its diff and its failing test names — and
  the one test that could not fail was found by them and fixed (m3 and m3b).
- **No generated code by string concatenation**, none generated at all.
- **No new field on `GameContext`.**
- **No wall clock and no unseeded randomness in simulation.** Nothing this change adds runs inside
  `Simulation.step()`. `IsoPanControl` takes `dtSeconds` like every other `RenderSystem`, and
  `WallClockBudgetCensusTest` gained a `NOT_A_BUDGET` row for `MobaPointerReplayTest`, which seeds
  its fixture from `System.nanoTime` in **test** code.
- **No `TODO()`, no stubbed return, no swallowed exception** on a reachable path.
- **No copy-pasted logic differing by a constant** — see the first item.
- **No GL, Kool or ComposeGL outside `udea-render`**, and nothing off the render thread:
  `WorldPointer` and `IsoPanControl` are `RenderSystem`s.
- **No `by net(...)`, no separate snapshot codec, no setter instrumentation, no LibGDX, no
  reflection on a per-tick path.** The one reflection call in this change is in a test — the
  no-pixel fence — and runs once.
- **No bare `Int`/`Long`/`String` for a domain concept.** The entity under the cursor is a `NetId`.
  The pointer's position is a pair of world-unit floats, which is what a position is here
  (`Transform3D` is the same), and no duration, deadline or stamp was added in any unit at all.
- **No `docs/contracts/` file changed**, no `fieldNames`/`FieldMask`/`FieldStore` alignment touched,
  and `AGENTS.md`'s module table is untouched because no module moved. `AGENTS.md`'s replay bullet
  **was** updated in the same commit, from format 2 to format 3.

### The near-misses, stated rather than waited for

1. **A test of mine that could not fail.** `EntityPickerTest`'s two-model ordering test reported the
   boxes in one order only, and mutation m3 left it green while `udea-editor`'s own test went red.
   Fixed to try both orders (m3b), and both modules then go red. The fix is in `0ddfe00`.
2. **Stale test XML after a reverted mutation.** Described in section 4; the answer is the
   `--rerun-tasks` runs, and it is why the numbers in this brief are not the ones that were on disk
   an hour ago.
3. **A marker that measured itself.** The first version of `GlGroundPickTest` used a thin box as its
   marker, and the box's silhouette centroid sat a flat 1.265 px up the screen at the closest zoom —
   a measurement artefact that would have eaten the whole one-pixel criterion. It is a plane now,
   and the residual lift bias is stated in section 1 rather than subtracted.
4. **Cubes that shadowed the marker.** With all three cubes present from the start, the marker at
   view height 8 read 208 matching pixels instead of 238 and came out 2 px wrong. The cubes are now
   created after criterion one has finished, and the test says so where it does it.
