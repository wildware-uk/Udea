# BRIEF: issue #227, mouse and touch clicks become intents, and a click on the interface never does

**SHA:** `aed73eb` (the code under review. The commit on top of it adds only this file.)

Branch `issue-227-kool-pointer-intents`, cut from `origin/kmp` at `1cc5f60` (`git fetch` at start;
`origin/kmp` was still `1cc5f60` when this was written). Worktree
`/srv/ssd1/workspace/Udea/.claude/worktrees/agent-ab22c07454e2b1eaa`. Every artefact named below is
under `/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue227/`,
called `$A` from here on. Only `udea-render` code changed, plus one census row in `udea-gradle`'s tests
and one comment in `gradle/libs.versions.toml` (both explained in section 2). `udea-core` and `moba`
are untouched.

## 1. The evidence command

```
cd /srv/ssd1/workspace/Udea/.claude/worktrees/agent-ab22c07454e2b1eaa && \
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe ANDROID_HOME=$HOME/Android/Sdk \
      JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew :udea-render:jvmTest --tests 'dev.wildware.udea.render.kool.*' --rerun \
             :udea-render:udeaGlTest --tests 'dev.wildware.udea.render.gl.GlKool*' --rerun \
             --continue -Pudea.render.requireGl=true --console=plain
```

It runs the pointer tests with no GL (`KoolPointerOrderTest`) and with GL (`GlKoolPointerTest`), and
the #224/#230 key tests beside them unchanged (`KoolKeyboardOrderTest`, `GlKoolInputTest`).
`--continue` so a red unit test does not stop the GL test from running too.

**Green on `aed73eb`** (`$A/evidence-green.log`: `BUILD SUCCESSFUL in 20s`, `exit=0`; per-suite counts
read from the JUnit XML by `$A/failures.py` into `$A/evidence-green.failures`):

```
SUITE dev.wildware.udea.render.kool.KoolKeyboardOrderTest tests=13 skipped=0 failures=0 errors=0
SUITE dev.wildware.udea.render.kool.KoolPointerOrderTest tests=14 skipped=0 failures=0 errors=0
SUITE dev.wildware.udea.render.gl.GlKoolInputTest tests=1 skipped=0 failures=0 errors=0
SUITE dev.wildware.udea.render.gl.GlKoolPointerTest tests=1 skipped=0 failures=0 errors=0
```

**Red when the feature is reverted.** Each row below is a mutation of the production code, applied by
`$A/mutate.py`, run through exactly the command above by `$A/mutations.sh`, then restored with
`git checkout`. The diff is `git diff` captured by that script (`$A/mutations/<name>.diff`); the failures
are read from the fresh XML (`$A/mutations/<name>.failures`; the script deletes the old XML first, so a
suite that did not run cannot report stale green). Every one of the eight exited 1 (`exit=1` at the end
of each `$A/mutations/<name>.log`). M1 is the feature itself reverted: the interface's verdict ignored.

**M1-verdict-ignored**

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt
index c9d30d9..a941b8d 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt
@@ -209,7 +209,7 @@ public class KoolPointer internal constructor(
     private fun apply(change: Change, used: Boolean) {
         val before = held[change.pointer.value] ?: 0
         var after = before
-        if (!used) {
+        if (true) {
             after = after or change.press
             for (button in 0 until BUTTONS) {
                 if (change.press and (1 shl button) != 0) presses[button]++
```

Failed (suites with no failure omitted):

```
SUITE dev.wildware.udea.render.kool.KoolPointerOrderTest tests=14 skipped=0 failures=5 errors=0
  FAILED  a click the interface did not use becomes an intent, and one it used does not()[jvm]
          org.opentest4j.AssertionFailedError: a click on a button fired the binding under it ==> expected: <0> but was: <1>
  FAILED  a click the interface used never reaches the game()[jvm]
          org.opentest4j.AssertionFailedError: a press on a button was held down for the game as well
  FAILED  a press on the interface dragged onto the scene never becomes held()[jvm]
          org.opentest4j.AssertionFailedError: a drag that began on a slider became a held button once it left it
  FAILED  a report is matched to the frame the pointer was read in, not the one it arrives in()[jvm]
          org.opentest4j.AssertionFailedError: frame 1's press was judged by some other frame's verdict ==> expected: <0> but was: <1>
  FAILED  two pointers are judged separately()[jvm]
          org.opentest4j.AssertionFailedError: one finger on a button and one on the scene is one press ==> expected: <1> but was: <2>
SUITE dev.wildware.udea.render.gl.GlKoolPointerTest tests=1 skipped=0 failures=1 errors=0
  FAILED  a click becomes an intent, and a click on a ComposeGL button does not()
          org.opentest4j.AssertionFailedError: a click on a button also fired the binding underneath: presses=[0, 1], everHeld=[false, true], heldNow=[false, false] ==> expected: <0> but was: <1>
```

**M2-not-held-back**

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt
index c9d30d9..abf38a8 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt
@@ -198,7 +198,7 @@ public class KoolPointer internal constructor(
     }
 
     private fun take(change: Change) {
-        if (ui.isReporting) waiting += change else apply(change, used = false)
+        apply(change, used = false)
     }
 
     private fun settleAll() {
```

Failed (suites with no failure omitted):

```
SUITE dev.wildware.udea.render.kool.KoolPointerOrderTest tests=14 skipped=0 failures=6 errors=0
  FAILED  a click the interface did not use becomes an intent, and one it used does not()[jvm]
          org.opentest4j.AssertionFailedError: a click on a button fired the binding under it ==> expected: <0> but was: <1>
  FAILED  a click the interface used never reaches the game()[jvm]
          org.opentest4j.AssertionFailedError: a press on a button was held down for the game as well
  FAILED  nothing reaches the game before the interface has said whether it used the frame()[jvm]
          org.opentest4j.AssertionFailedError: a press reached the game before the interface judged it
  FAILED  a press on the interface dragged onto the scene never becomes held()[jvm]
          org.opentest4j.AssertionFailedError: a drag that began on a slider became a held button once it left it
  FAILED  a report is matched to the frame the pointer was read in, not the one it arrives in()[jvm]
          org.opentest4j.AssertionFailedError: frame 1's press was judged by some other frame's verdict ==> expected: <0> but was: <1>
  FAILED  two pointers are judged separately()[jvm]
          org.opentest4j.AssertionFailedError: one finger on a button and one on the scene is one press ==> expected: <1> but was: <2>
SUITE dev.wildware.udea.render.gl.GlKoolPointerTest tests=1 skipped=0 failures=1 errors=0
  FAILED  a click becomes an intent, and a click on a ComposeGL button does not()
          org.opentest4j.AssertionFailedError: a click on a button also fired the binding underneath: presses=[0, 1], everHeld=[false, true], heldNow=[false, false] ==> expected: <0> but was: <1>
```

**M3-release-filtered**

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt
index c9d30d9..dc3b776 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt
@@ -215,7 +215,7 @@ public class KoolPointer internal constructor(
                 if (change.press and (1 shl button) != 0) presses[button]++
             }
         }
-        after = after and change.release.inv()
+        if (!used) after = after and change.release.inv()
         if (after == 0) held -= change.pointer.value else held[change.pointer.value] = after
         for (button in 0 until BUTTONS) {
             val bit = 1 shl button
```

Failed (suites with no failure omitted):

```
SUITE dev.wildware.udea.render.kool.KoolPointerOrderTest tests=14 skipped=0 failures=2 errors=0
  FAILED  a release is never taken away from the game()[jvm]
          org.opentest4j.AssertionFailedError: a release over the interface left the button stuck down
  FAILED  a pointer that goes releases everything it held()[jvm]
          org.opentest4j.AssertionFailedError: the mouse left the window and the game still holds its left button
```

**M4-matched-on-arrival**

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt
index c9d30d9..de8b70f 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt
@@ -193,7 +193,7 @@ public class KoolPointer internal constructor(
                 continue
             }
             waiting.removeAt(index)
-            apply(change, used = age == 0 && used)
+            apply(change, used = used)
         }
     }
 
```

Failed (suites with no failure omitted):

```
SUITE dev.wildware.udea.render.kool.KoolPointerOrderTest tests=14 skipped=0 failures=1 errors=0
  FAILED  a frame the interface never reports on is the game's once a later report arrives()[jvm]
          org.opentest4j.AssertionFailedError: a frame the interface never saw was judged by a later frame's verdict ==> expected: <1> but was: <0>
```

**M5-event-mask-click**

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt
index c9d30d9..17d3602 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt
@@ -101,7 +101,7 @@ public class KoolPointer internal constructor(
     private val listener = InputStack.PointerListener { state, _ ->
         beginFrame(InputFrame(Time.frameCount))
         for (pointer in state.pointers) {
-            if (pointer.isValid) onPointer(PointerId(pointer.id), pointer.buttonMask)
+            if (pointer.isValid) onPointer(PointerId(pointer.id), pointer.buttonMask, pointer.buttonEventMask)
         }
         endFrame()
     }
@@ -160,13 +160,14 @@ public class KoolPointer internal constructor(
      * button marked as changed, left over from before it went. Read as a click shorter than a frame, that
      * was a press nobody made (`GlKoolPointerTest`, step 5, found it).
      */
-    internal fun onPointer(pointer: PointerId, buttons: Int) {
+    internal fun onPointer(pointer: PointerId, buttons: Int, changed: Int = 0) {
         val was = listed[pointer.value] ?: 0
         listed[pointer.value] = buttons
         listedNow += pointer.value
 
-        val press = buttons and was.inv()
-        val release = was and buttons.inv()
+        val click = changed and buttons.inv() and was.inv()
+        val press = (buttons and was.inv()) or click
+        val release = (was and buttons.inv()) or click
         if (press or release != 0) take(Change(pointer, frame, press, release))
     }
 
```

Failed (suites with no failure omitted):

```
SUITE dev.wildware.udea.render.gl.GlKoolPointerTest tests=1 skipped=0 failures=1 errors=0
  FAILED  a click becomes an intent, and a click on a ComposeGL button does not()
          org.opentest4j.AssertionFailedError: the mouse coming back into the window pressed a button nobody pressed: presses=[0, 2], everHeld=[false, true], heldNow=[false, false] ==> expected: <1> but was: <2>
```

**M6-verdict-not-forwarded**

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/ui/UiLayer.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
index 5370e6e..19264ab 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
@@ -139,9 +139,6 @@ public class UiLayer(
         val scene = ComposeGlScene(backend, design, policy)
         scene.setContent { screen?.content() }
         // Read at each report rather than captured here, so a listener set after attaching still hears.
-        scene.onPointerUsed = { use ->
-            pointerReports?.onReport(PointerId(use.pointer), InputFrame(use.frame), use.used)
-        }
         ctx.addScene(scene.scene)
         mounted = Mounted(ctx, backend, scene)
     }
```

Failed (suites with no failure omitted):

```
SUITE dev.wildware.udea.render.gl.GlKoolPointerTest tests=1 skipped=0 failures=1 errors=0
  FAILED  a click becomes an intent, and a click on a ComposeGL button does not()
          org.opentest4j.AssertionFailedError: a mouse button held down on the scene was never held in any tick's intent
```

**M7-pointer-not-sampled**

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/input/DeviceIntent.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/input/DeviceIntent.kt
index 39c20b6..c99c474 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/input/DeviceIntent.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/input/DeviceIntent.kt
@@ -53,10 +53,6 @@ public class DeviceIntent(
                     presses += gamepad.pressesSince(button)
                 }
             }
-            for (button in binding.pointerButtons) {
-                if (pointer.isButtonDown(button)) held = true
-                presses += pointer.pressesSince(button)
-            }
             val id = ActionId(index)
             into.setPressed(id, held)
             into.setPressCount(id, presses)
```

Failed (suites with no failure omitted):

```
SUITE dev.wildware.udea.render.kool.KoolPointerOrderTest tests=14 skipped=0 failures=1 errors=0
  FAILED  a click the interface did not use becomes an intent, and one it used does not()[jvm]
          org.opentest4j.AssertionFailedError: a click on the scene did not fire its binding ==> expected: <1> but was: <0>
SUITE dev.wildware.udea.render.gl.GlKoolPointerTest tests=1 skipped=0 failures=1 errors=0
  FAILED  a click becomes an intent, and a click on a ComposeGL button does not()
          org.opentest4j.AssertionFailedError: a mouse button held down on the scene was never held in any tick's intent
```

**M8-gone-not-released**

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt
index c9d30d9..0300115 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolPointer.kt
@@ -177,7 +177,6 @@ public class KoolPointer internal constructor(
             val entry = entries.next()
             if (entry.key in listedNow) continue
             entries.remove()
-            take(Change(PointerId(entry.key), frame, press = 0, release = ALL_BUTTONS))
         }
     }
 
```

Failed (suites with no failure omitted):

```
SUITE dev.wildware.udea.render.kool.KoolPointerOrderTest tests=14 skipped=0 failures=2 errors=0
  FAILED  a pointer that goes releases everything it held()[jvm]
          org.opentest4j.AssertionFailedError: the mouse left the window and the game still holds its left button
  FAILED  two pointers are judged separately()[jvm]
          org.opentest4j.AssertionFailedError: finger A's press on the button became held once finger B lifted
SUITE dev.wildware.udea.render.gl.GlKoolPointerTest tests=1 skipped=0 failures=1 errors=0
  FAILED  a click becomes an intent, and a click on a ComposeGL button does not()
          org.opentest4j.AssertionFailedError: the mouse left the window and the game still holds its button: presses=[0, 1], everHeld=[false, true], heldNow=[false, true]
```

What these show between them: M1/M2 are "no first refusal" and "no holding back", and both the unit
and the GL test catch them. M3 (a release filtered by the verdict) is caught by the unit test only: in
the GL scene a release over the button after a press on the scene is not *used* by the toolkit (the
button never took the press), so the GL test cannot produce that verdict. M5 is the first version of
this branch, and only the GL test catches it, because the stale bit it misreads is something only the
real Kool produces (section 2, "a surprise"). M6 proves the GL test depends on the real ComposeGL
verdict arriving at all.

## 2. Summary

**What was missing.** A click produced no intent at all: `ActionBinding` had no pointer field and
nothing read a pointer (#224's reviewer). So this builds pointer-to-intent from nothing, beside the
keyboard path and in its shape:

- `ActionBinding.pointerButtons` (Kool button indices: 0 left, 1 right, 2 middle, 3 back, 4 forward;
  a finger presses 0), read by `DeviceIntent` through a new `PointerState` interface beside
  `KeyboardState` (a level plus a counted edge that `endSample` spends).
- `KoolPointer`, beside `KoolKeyboard`: a Kool `InputStack` pointer listener at the bottom of the stack.
  It records each frame's button changes and **holds them back** until ComposeGL's verdict for that
  pointer in that Kool frame (`ComposeGlScene.onPointerUsed`, ComposeGL `007ea1cf`) arrives, matched on
  the frame the listener read the pointer in. Not used: applied exactly as with no interface. Used: a
  press is dropped (never counts, never holds). **A release is always applied**, so a button can never
  stick down. A pointer that goes (mouse left the window, finger lifted) releases everything it held.
- `UiPointers`, the second seam beside `UiInput` (re-worded AC3). `UiLayer` exposes one and forwards
  `onPointerUsed` through it. It is `internal`, with `PointerReportListener`, `PointerId` and
  `InputFrame`: its only implementation is `UiLayer`'s and its only reader is `KoolPointer`, whose public
  constructor takes the `UiLayer` (or `null` for no interface). `UiInput` stays as it was.

**What was not built on.** Kool's `isConsumed()` (the ticket says so). Kool's `buttonEventMask` (see the
surprise below). `InputFrame` (Kool's `Time.frameCount`) only pairs a verdict with a sample and never
reaches the simulation. The `Intent` carries no stamp, so no `Tick` was needed.

**Decisions** (each commented on #227 with the alternative and how to undo it):

1. Pointers reach the simulation as **button actions only**. No position in `Intent`. A screen point
   needs the camera and changes what a recorded input stream holds, and neither is first refusal.
   [comment 5737228755](https://github.com/wildware-uk/Udea/issues/227#issuecomment-5737228755)
2. A **second seam** rather than a wider `UiInput`, and **releases always apply**.
   [comment 5737228928](https://github.com/wildware-uk/Udea/issues/227#issuecomment-5737228928)
3. **Edges read from the button level only** (the surprise below).
   [comment 5737229116](https://github.com/wildware-uk/Udea/issues/227#issuecomment-5737229116)
4. **`GlKoolInputTest` untouched**. The pointer GL scenario is a new class in its own JVM
   (`udeaGlTest` forks one per class). The composegl version stays the floating `0.7.0-SNAPSHOT`.
   [comment 5737229236](https://github.com/wildware-uk/Udea/issues/227#issuecomment-5737229236)
5. After those comments, the seam was made `internal` rather than public (engineering standards
   section 2: "Public is a decision"). A game that brings its own non-`UiLayer` interface would need it
   public, and nothing does today.

**The threading precondition** is `GlKoolInputTest`'s, as the lead asked, and it is not duplicated.
`KoolPointer`'s listener runs in the same `InputStack.handleInput` pass as `KoolKeyboard`'s. The verdict
fires inside the same ComposeGL scene render as the composition. So that test's assertion (Kool's input
callback, the composition and the frame callback on one thread) is this path's guard too, and
`KoolPointer`'s KDoc names it.

**A surprise, found by the GL test.** Hold the mouse button, leave the window, release outside, come
back: Kool 0.19 lists the returning mouse with nothing down *and that button's bit set in
`buttonEventMask`*. The bit is left over on Kool's per-slot `Pointer`, whose event mask is old level XOR
new level. My first version read "changed with nothing down" as a click shorter than a frame, the way
ComposeGL's `KoolPointerInput` does. It produced a press nobody made: frame 183 in `$A/gl-dbg2.log`
reads `DBG change PointerId(-1000000) InputFrame(183) press=1 rf=0 rl=1 reporting=true` (line 1121),
logged by a temporary debug line since removed. `rf`/`rl` were that version's two release masks. `KoolPointer` now reads edges from the level alone. The cost: a click shorter than one
Kool poll is lost. Kool's own level already folds such a click away, so the event mask would not have
recovered it. GL step 5 pins the returning mouse, and mutation M5 above puts that reading of the event mask back. **ComposeGL
reads the same bit the same way**, so a mouse returning over a button may click it. That was reported to
the lead, not filed, because it is outside Udea.

**A second one, in the test harness.** Kool holds back the position of the move that *starts* a drag
(`BufferedPointerInput.movePointer`). So one big GLFW move with a button held left the pointer where it
was. "Dragged off the button and released on the scene" really released on the button. The first sign
was the step after it: the next press, on the scene, came back `used=true` (`$A/gl-dbg.log`, frame
121), because Kool still had the pointer over the button. The test's `moveTo` now reports each move twice, as a real mouse reports a drag in
many small moves. Its KDoc says why.

**Outside `udea-render`, and why:**
- `udea-gradle/.../WallClockBudgetCensusTest.kt`: one census row. `GlKoolPointerTest` reads
  `System.nanoTime` for a frame/tick deadline, the same as `GlKoolInputTest`. The census test failed the
  build until the row existed, and its own message says to add one.
- `gradle/libs.versions.toml`: a comment only, saying which composegl snapshot `udea-render` needs.
  **This has an operational edge worth knowing.** On this box the Gradle cache held a
  `composegl-kool-android` snapshot from 21:21, before `007ea1cf` was published at 21:55. So
  `:udea-render:compileAndroidMain` failed with `Unresolved reference 'onPointerUsed'` (in `$A/build-1.log`)
  until I ran `--refresh-dependencies` once (`$A/android-refresh.log`, 23:07). My worktree's
  *configuration cache* then still carried the old artifact from the baseline run, so the next plain
  `build` failed the same way. That run's log was overwritten by the final run and is not preserved;
  the failure it showed is the one in `$A/build-1.log`. The comment edit changes a
  configuration-cache input, so that entry is invalidated on any checkout that merges this. The JVM
  jar on this box was already current, fetched at 21:59. A machine whose Gradle cache fetched an older
  0.7.0 snapshot within the last 24 hours needs `--refresh-dependencies` once. Pinning the timestamp was
  rejected because the JVM jar is `-3` and the Android aar `-2` in one publish, so no single version
  names both.

**What is not exercised:** a real touch screen (GLFW has no touch, so fingers and two pointers are
unit-tested only); iOS (no target, and it cannot build here); a verdict arriving on another thread
(`asyncSceneUpdate = true` is not this engine's configuration, and `GlKoolInputTest` fails if it becomes
one); scroll (nothing binds it).

## 3. `sh gradlew build --continue`

`ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue --console=plain`
on `aed73eb`, log `$A/build-final.log`:

```
* What went wrong:
Execution failed for task ':moba:compileKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.btapi.BuildToolsApiCompilationWork
   > Compilation error. See log for more details
...

BUILD FAILED in 14s
752 actionable tasks: 22 executed, 3 from cache, 727 up-to-date
Configuration cache entry reused.
exit=1
```

The baseline, the same command on `1cc5f60` before any change (`$A/baseline-build.log`):

```
* What went wrong:
Execution failed for task ':moba:compileKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.btapi.BuildToolsApiCompilationWork
   > Compilation error. See log for more details
...
BUILD FAILED in 1m 31s
761 actionable tasks: 524 executed, 237 from cache
Configuration cache entry stored.
exit=1
```

The failing-task lists, `grep -E "^> Task .* FAILED$" | sort` of each log, are identical
(`diff $A/baseline.failed $A/final.failed` prints nothing):

- **Baseline failures, unchanged:** `:moba:compileKotlin` (the authorised D9 red; moba is on LibGDX
  until #212). Its dependants do not run.
- **Tasks this ticket turned green:** none were red. The ticket's own tasks are
  `:udea-render:jvmTest` and `:udea-render:udeaGlTest`, both green, and `:udea-render:compileAndroidMain`,
  which is green once the snapshot is current (section 2).
- **Baseline-green tasks turned red:** none.

The final run reused the configuration cache (`752 actionable tasks`). The baseline built it cold
(`761`). The difference is only `build-logic`'s own tasks, which a reused cache does not list (`diff` of
the `> Task` names in the two logs, `$A/b.tasks` against `$A/f.tasks`). No latency budget failed in
either run.

**The GL run**, under xvfb, on `aed73eb` (`$A/gl-final.log`):

```
cd /srv/ssd1/workspace/Udea/.claude/worktrees/agent-ab22c07454e2b1eaa && \
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe ANDROID_HOME=$HOME/Android/Sdk \
      JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew :udea-render:jvmTest --rerun :udea-render:udeaGlTest --rerun \
             :udea-agent-host:udeaAgentGlTest --rerun -Pudea.render.requireGl=true --console=plain
```

```
> Task :udea-render:jvmTest
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest
...
BUILD SUCCESSFUL in 45s
140 actionable tasks: 14 executed, 126 up-to-date
...
exit=0
```

Per-class results from the JUnit XML, every file written by that run (`$A/gl-final.summary`):

```
udea-render/build/test-results/jvmTest: 27 classes, tests 202, skipped 0, failures 0, errors 0, files written ['23:23']
udea-render/build/test-results/udeaGlTest: 11 classes, tests 12, skipped 0, failures 0, errors 0, files written ['23:24']
   TEST-dev.wildware.udea.render.gl.GlCaptureDeterminismTest.xml tests=1 skipped=0 failures=0 errors=0
   TEST-dev.wildware.udea.render.gl.GlCaptureTest.xml tests=1 skipped=0 failures=0 errors=0
   TEST-dev.wildware.udea.render.gl.GlKoolInputTest.xml tests=1 skipped=0 failures=0 errors=0
   TEST-dev.wildware.udea.render.gl.GlKoolPointerTest.xml tests=1 skipped=0 failures=0 errors=0
   TEST-dev.wildware.udea.render.gl.GlOverlayIsolationTest.xml tests=1 skipped=0 failures=0 errors=0
   TEST-dev.wildware.udea.render.gl.GlUiLayerTest.xml tests=1 skipped=0 failures=0 errors=0
   TEST-dev.wildware.udea.render.gl.KoolThreadShutdownTest.xml tests=1 skipped=0 failures=0 errors=0
   TEST-dev.wildware.udea.render.gl.OffscreenBackendExplodingCaptureTest.xml tests=1 skipped=0 failures=0 errors=0
   TEST-dev.wildware.udea.render.gl.OffscreenBackendSecondCreateTest.xml tests=1 skipped=0 failures=0 errors=0
   TEST-dev.wildware.udea.render.gl.OffscreenBackendShutdownTest.xml tests=1 skipped=0 failures=0 errors=0
   TEST-dev.wildware.udea.render.gl.OffscreenBackendTest.xml tests=2 skipped=0 failures=0 errors=0
udea-agent-host/build/test-results/udeaAgentGlTest: 2 classes, tests 2, skipped 0, failures 0, errors 0, files written ['23:24']
   TEST-dev.wildware.udea.agent.host.gl.OffscreenRenderToolsTest.xml tests=1 skipped=0 failures=0 errors=0
   TEST-dev.wildware.udea.agent.host.gl.OverlayCaptureIsolationTest.xml tests=1 skipped=0 failures=0 errors=0
```

No GL test skipped. With `requireGl=true`, `GlAvailability.require()` fails rather than skips.

## 4. Images

None. What changed is not visible. The interface draws into the window's framebuffer after the
presented frame, and no capture ever reads that (`UiLayer`'s KDoc, pinned by `GlUiLayerTest`). The
game side of a click is an `Intent` value, not a picture. A screenshot of the button would show a button
and prove nothing about where its click went. The GL test's assertions on the tick's `Intent` are the
evidence for that.

## 5. The issue, criterion by criterion

**AC1: a Kool pointer event reaches the simulation as an intent through `IntentSource`.**
`GlKoolPointerTest` step 1. A raw GLFW press on Kool's own mouse-button callback, over the scene, goes
through Kool's `PointerInput`, `InputStack`, `KoolPointer`, ComposeGL's verdict, `DeviceIntent`, and
`InputModule`'s `IntentSampleSystem` into a tick, where a `SimSystem` registered after it reads the
`Intent`. Assertions: held in a tick, exactly one press, released afterwards, and a right click exactly
one press of the right-button binding. Step 6 does the same with no screen shown. Unit twin:
`a click the interface did not use becomes an intent, and one it used does not`. Red under M6 (no
verdict ever arrives, so nothing is let through) and M7 (the sampler ignores pointer buttons).

**AC2: a click on a ComposeGL control is consumed by the UI and never becomes an intent.**
`GlKoolPointerTest` step 2. A real ComposeGL `Button` counts the click, which proves the interface took
it, and no tick's `Intent` ever holds or presses the binding. Steps 3 and 4 cover a drag from the
button onto the scene (still no intent) and from the scene onto the button (the game's press, and a
release that does not stick). The xvfb run with `-Pudea.render.requireGl=true` is in section 3. Unit
tests: `a click the interface used never reaches the game`, `a press on the interface dragged onto the
scene never becomes held`, `two pointers are judged separately`. Red under M1, M2.

**AC3 (re-worded): pointers go through a second seam beside `UiInput`, and #224's and #230's key tests
still pass unchanged.** The seam is `UiPointers` (section 2). `git diff --stat origin/kmp --
udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/gl/GlKoolInputTest.kt
udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/kool/KoolKeyboardOrderTest.kt` prints nothing,
and both are green in the evidence run (13 and 1 tests) and in the full GL run. `KoolKeyboard.kt` and
`UiInput`'s interface are unchanged. `UiInput.kt`'s KDoc gained two lines pointing at the pointer seam.

**The lead's "what did you not exercise" list:**

| Case | Where |
|---|---|
| press with no release | GL step 5 (press, then the mouse leaves) and unit `a click the interface did not use reaches the game` |
| release off the control it pressed on | GL step 3 |
| drag UI to scene, and scene to UI | GL steps 3 and 4; unit `a press on the interface dragged onto the scene never becomes held`, `a release is never taken away from the game` |
| pointer leaving the window, and coming back | GL step 5; unit `a pointer that goes releases everything it held` |
| the final report after a lift | unit `two pointers are judged separately` (finger B lifts in frame 3 and is reported in frame 3) |
| two pointers at once | unit only (`two pointers are judged separately`): GLFW has one mouse and no touch |
| a click when no UI is shown | GL step 6 (screen hidden, the scene still reports); unit `with no interface every click is the game's at once` (no `UiLayer` at all) |
| the frame-matching boundary | unit `nothing reaches the game before the interface has said whether it used the frame`, `a report is matched to the frame the pointer was read in, not the one it arrives in`, `a frame the interface never reports on is the game's once a later report arrives` (M4) |
| an interface closing mid-judgement | unit `an interface that stops reporting hands back what it was judging` |

**Failing test first.** `KoolPointerOrderTest` was written before any production code, and it failed to
compile against `origin/kmp` for want of the whole pointer path (`$A/red-1-compile.log`:
`Unresolved reference 'KoolPointer'`, `'UiPointers'`, `'InputFrame'`, `'PointerId'`). Behavioural red is
the mutation table above.

## 6. Regenerated files

None. No replicated component was added or removed. `udea-codegen/net-protocol.lock` and
`expected-generated-hashes.txt` are untouched, and `udeaCheckProtocolLock` is green in the build.
No `docs/contracts/` file changed.

