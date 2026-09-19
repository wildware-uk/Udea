# BRIEF: issue #228, games name keys and udea-render owns the numbers

**SHA:** `eb05c73` (the code under review; the commit on top of it adds only this file)

Branch `issue-228-symbolic-keys`, cut from `origin/kmp` at `407123a`, with `origin/kmp` at `322dde9`
(#213) merged in as `eb05c73`. Worktree
`/srv/ssd1/workspace/Udea/.claude/worktrees/agent-ae967e2598f737a7b`. Every artefact named below is
under `/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue228/`,
called `$A` from here on.

## 1. The evidence command

```
cd /srv/ssd1/workspace/Udea/.claude/worktrees/agent-ae967e2598f737a7b && \
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
      ANDROID_HOME=/home/shaun/Android/Sdk \
      JAVA_HOME=/home/shaun/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew :udea-render:udeaGlTest --tests '*GlKoolKeyTableTest' \
             :udea-render:testAndroidHostTest --tests '*AndroidKeyTableTest' \
             -Pudea.render.requireGl=true --continue
```

One command, both backends:

- `GlKoolKeyTableTest` binds one action per `InputKey`, presses every physical key as its GLFW
  constant **through Kool's installed GLFW key callback** (so Kool, not the test, picks the code),
  and requires each press to fire exactly the action named after it. It then presses four things no
  table should name - raw `'w'.code` (119, what `UniversalKeyCode('W')` gives on Kool `main`), F13,
  keypad 1, `WORLD_1` - and requires each to fire nothing.
- `AndroidKeyTableTest` (an Android host test) reads `KEY_CODE_MAP` out of Kool 0.19.0's own
  `PlatformInputAndroid` and runs each physical `KeyEvent.KEYCODE_*` through it, per key.

Green on `eb05c73` (`$A/evidence-green.log`, test XML copied to `$A/evidence-green-gl.xml`):

```
> Task :udea-render:testAndroidHostTest
> Task :udea-render:udeaGlTest
...
BUILD SUCCESSFUL in 34s
```

The suites, from the XML in the build dirs after that run:

```
<testsuite name="dev.wildware.udea.render.kool.AndroidKeyTableTest" tests="2" skipped="0" failures="0" errors="0"
<testsuite name="dev.wildware.udea.render.gl.GlKoolKeyTableTest" tests="1" skipped="0" failures="0" errors="0"
```

On `origin/kmp` the command cannot pass: neither test class, `InputKey` nor `KoolKeyTable` exists
there, so it fails at compile.

### It goes red when the table is wrong - the mutations, with their diffs

Each diff is `git diff` saved from the run (`$A/mut-*.diff`); each failure line is from the saved
JUnit XML (`$A/mut-*.xml`).

**M1, the lowercase trap on desktop** (`$A/mut-desktop-w-lowercase.diff`):

```diff
diff --git a/udea-render/src/jvmMain/kotlin/dev/wildware/udea/render/kool/KoolKeyTable.jvm.kt b/udea-render/src/jvmMain/kotlin/dev/wildware/udea/render/kool/KoolKeyTable.jvm.kt
index 9b21261..002bdb7 100644
--- a/udea-render/src/jvmMain/kotlin/dev/wildware/udea/render/kool/KoolKeyTable.jvm.kt
+++ b/udea-render/src/jvmMain/kotlin/dev/wildware/udea/render/kool/KoolKeyTable.jvm.kt
@@ -36,7 +36,7 @@ internal actual val platformKeyTable: KoolKeyTable = KoolKeyTable(
         InputKey.T to GLFW.GLFW_KEY_T,
         InputKey.U to GLFW.GLFW_KEY_U,
         InputKey.V to GLFW.GLFW_KEY_V,
-        InputKey.W to GLFW.GLFW_KEY_W,
+        InputKey.W to 'w'.code,
         InputKey.X to GLFW.GLFW_KEY_X,
         InputKey.Y to GLFW.GLFW_KEY_Y,
         InputKey.Z to GLFW.GLFW_KEY_Z,
```

`$A/mut-desktop-w-lowercase.xml`:

```
W: GLFW key 87 fired []
'w'.code, what UniversalKeyCode('W') gives on Kool main (GLFW 119) is no named key, and fired [W]
```

**M2, two keys swapped on desktop** (`$A/mut-desktop-swap-ad.diff`):

```diff
diff --git a/udea-render/src/jvmMain/kotlin/dev/wildware/udea/render/kool/KoolKeyTable.jvm.kt b/udea-render/src/jvmMain/kotlin/dev/wildware/udea/render/kool/KoolKeyTable.jvm.kt
index 9b21261..f47282a 100644
--- a/udea-render/src/jvmMain/kotlin/dev/wildware/udea/render/kool/KoolKeyTable.jvm.kt
+++ b/udea-render/src/jvmMain/kotlin/dev/wildware/udea/render/kool/KoolKeyTable.jvm.kt
@@ -14,10 +14,10 @@ import org.lwjgl.glfw.GLFW
  */
 internal actual val platformKeyTable: KoolKeyTable = KoolKeyTable(
     mapOf(
-        InputKey.A to GLFW.GLFW_KEY_A,
+        InputKey.A to GLFW.GLFW_KEY_D,
         InputKey.B to GLFW.GLFW_KEY_B,
         InputKey.C to GLFW.GLFW_KEY_C,
-        InputKey.D to GLFW.GLFW_KEY_D,
+        InputKey.D to GLFW.GLFW_KEY_A,
         InputKey.E to GLFW.GLFW_KEY_E,
         InputKey.F to GLFW.GLFW_KEY_F,
         InputKey.G to GLFW.GLFW_KEY_G,
```

`$A/mut-desktop-swap-ad.xml`:

```
A: GLFW key 65 fired [D]
D: GLFW key 68 fired [A]
```

**M3, a special key given GLFW's number instead of Kool's** - in the shared table, so both backends
(`$A/mut-escape-glfw256.diff`):

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolKeyTable.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolKeyTable.kt
index 5d65d76..c22daba 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolKeyTable.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolKeyTable.kt
@@ -97,7 +97,7 @@ internal class KoolKeyTable(printable: Map<InputKey, Int>) {
             InputKey.PageDown to KeyboardInput.KEY_PAGE_DOWN.code,
             InputKey.Enter to KeyboardInput.KEY_ENTER.code,
             InputKey.NumpadEnter to KeyboardInput.KEY_NP_ENTER.code,
-            InputKey.Escape to KeyboardInput.KEY_ESC.code,
+            InputKey.Escape to 256,
             InputKey.Tab to KeyboardInput.KEY_TAB.code,
             InputKey.Backspace to KeyboardInput.KEY_BACKSPACE.code,
             InputKey.Delete to KeyboardInput.KEY_DEL.code,
```

`$A/mut-escape-glfw256-gl.xml` and `$A/mut-escape-glfw256-android.xml`:

```
Escape: GLFW key 256 fired []
```
```
Escape: Android key 111 reaches the game as Kool code -9, which the table reads as null
```

**M4, the desktop digit on Android** (`$A/mut-android-digit0.diff`):

```diff
diff --git a/udea-render/src/androidMain/kotlin/dev/wildware/udea/render/kool/KoolKeyTable.android.kt b/udea-render/src/androidMain/kotlin/dev/wildware/udea/render/kool/KoolKeyTable.android.kt
index 307b7eb..c5cb1a5 100644
--- a/udea-render/src/androidMain/kotlin/dev/wildware/udea/render/kool/KoolKeyTable.android.kt
+++ b/udea-render/src/androidMain/kotlin/dev/wildware/udea/render/kool/KoolKeyTable.android.kt
@@ -63,7 +63,7 @@ internal actual val platformKeyTable: KoolKeyTable = KoolKeyTable(
         InputKey.X to 'X'.code,
         InputKey.Y to 'Y'.code,
         InputKey.Z to 'Z'.code,
-        InputKey.Digit0 to KeyEvent.KEYCODE_0,
+        InputKey.Digit0 to '0'.code,
         InputKey.Digit1 to KeyEvent.KEYCODE_1,
         InputKey.Digit2 to KeyEvent.KEYCODE_2,
         InputKey.Digit3 to KeyEvent.KEYCODE_3,
```

`$A/mut-android-digit0.xml`:

```
Digit0: Android key 7 reaches the game as Kool code 7, which the table reads as null
```

**M5, mapping a key that shares a letter's code on Android** (`$A/mut-android-minus-clash.diff`):

```diff
diff --git a/udea-render/src/androidMain/kotlin/dev/wildware/udea/render/kool/KoolKeyTable.android.kt b/udea-render/src/androidMain/kotlin/dev/wildware/udea/render/kool/KoolKeyTable.android.kt
index 307b7eb..f03017d 100644
--- a/udea-render/src/androidMain/kotlin/dev/wildware/udea/render/kool/KoolKeyTable.android.kt
+++ b/udea-render/src/androidMain/kotlin/dev/wildware/udea/render/kool/KoolKeyTable.android.kt
@@ -76,5 +76,6 @@ internal actual val platformKeyTable: KoolKeyTable = KoolKeyTable(
         InputKey.Space to KeyEvent.KEYCODE_SPACE,
         InputKey.Comma to KeyEvent.KEYCODE_COMMA,
         InputKey.Period to KeyEvent.KEYCODE_PERIOD,
+        InputKey.Minus to KeyEvent.KEYCODE_MINUS,
     ),
 )
```

Both Android tests fail; `$A/mut-android-minus-clash.xml`:

```
two keys share one Kool code, so one of each pair could never be pressed: {69=[E, Minus]}
```

The same five runs as a picture: `issue228-every-key-pressed-green-and-three-broken-tables.png`
(section 5) draws M1-M3 and the green run key by key, read from the XML above by `$A/keygrid.py`.

What cannot be driven red from this branch: `AndroidKeyTableTest`'s check that the nine left-out
keys *still* collide with a letter. It exists to go red when Kool changes, and no edit on this side
of Kool makes it fail. M5 is the nearest production mutation, and it fails that test for a different
reason (the table refuses to build).

## 2. Summary

**What changed.** A controls asset writes `key(InputKey.W)`; there is no integer form any more.
`InputKey` is an enum of names in `udea-assets`. `udea-render`'s `KoolKeyTable` is the only place a
Kool key code becomes an `InputKey`, with a `jvm` table (GLFW) and an `android` table, and a shared
list of the special keys both Kool backends rename to the same negative codes. `KoolKeyboard`
translates every event through it, so the runtime speaks names too: `KeyboardState.isKeyDown(InputKey)`,
`ActionBinding.keys: List<InputKey>`, `Axis2DBinding.negativeX: InputKey?`, `KeyStroke.key: InputKey?`.
The #224/#230 `ui/KeyTable.kt` is deleted; the interface maps `InputKey` to the toolkit's `Key` with
an exhaustive `when` (`ui/ToolkitKey.kt`) that holds no number. `moba`'s asset, loader, HUD labels and
tests use names; `MobaControls.Keys` is gone.

**Decisions** (each commented on #228):

- *Where the type lives:* `udea-assets`, because a `.udea.kts` compiles against the asset model and
  `udea-assets` is below `udea-render`. It is a default import of the asset script. `udea-render`'s
  dependency on `udea-assets` went from `implementation` to `api`, because its public input types now
  name `InputKey`. Rejected: `udea-core` (the asset model does not depend on it), and keeping `Int` at
  run time (the game would still hold backend numbers).
- *Universal, not local:* the table maps Kool's universal `keyCode` - the physical key, which is what
  a movement binding means and what both backends fill the same way. The local code is the layout's
  label. Stated in `KoolKeyTable`'s KDoc.
- *The bundle stores the key by name* (`inputKey = "W"`), not by ordinal, so a new key cannot re-point
  bindings in a bundle already built. An unknown name is refused by name (`NamedKeyBindingTest`).
- *Android* - read from the kool-core-android 0.19.0 AAR with `javap -c`, not from a Kool `main`
  clone. Kool's Android map renames `KEYCODE_A..Z` (29..54) to 65..90 and passes every other key
  through as Android's own number (digit 0 is 7, space is 62). So nine punctuation keys share a
  letter's code (`KEYCODE_MINUS` 69 is `E`, and so on). The Android table keeps the letters and leaves
  those nine out; `KoolKeyTable`'s constructor refuses any shared code (M5). Android letters are
  written `'A'.code`, never through `UniversalKeyCode(Char)`.
- *Web:* no table and no stub. `udea-render` has no wasmJs target (Kool 0.19.0 publishes none; the
  owner shelved web), so there is nothing to map.

**Things I had to change beyond the obvious, and why:**

- `udea-render/build.gradle.kts` sets `isReturnDefaultValues = true` on the Android host-test
  compilation. `PlatformInputAndroid`'s static initialiser builds a `MotionEvent.PointerCoords`, and
  the default mockable `android.jar` throws from it. Only `AndroidKeyTableTest` runs there.
- `NoRenderSystemIsAFleksSystemTest` loaded every compiled class by name on the JVM test classpath.
  `KoolKeyTable.android.kt` is the first `androidMain` code in `udea-render`, and no JVM classpath holds
  it, so the gate threw `ClassNotFoundException`. It now reads an Android-only class's header with ASM
  and checks its supertypes the same way. Control run: a temporary `androidMain` class that is both an
  `IntervalSystem` and a `RenderSystem` turned it red (`$A/mut-norender-androidprobe.xml`:
  `expected: <[]> but was: <[dev.wildware.udea.render.kool.AndroidOnlyProbe]>`), and was deleted.
- The GL key helpers (`press`, `release`, the callback swap, `FrameProbe`, `awaitFrames`) moved out of
  `GlKoolInputTest` into `gl/GlKeys.kt`, shared with `GlKoolKeyTableTest`, rather than copied. The
  wall-clock census row moved with the frame deadline (`WallClockBudgetCensusTest`).

**Not exercised here:** a real Android device (none on this box; the Android evidence is Kool's own
map run key by key, not a key press); iOS (cannot build on Linux, and `udea-render` has no iOS target).

## 3. `sh gradlew build`

On the merged tree `eb05c73`, box at load 9 when it started and no other Gradle build running
(`$A/build-merged.log`):

```
BUILD SUCCESSFUL in 2m 10s
917 actionable tasks: 625 executed, 212 from cache, 80 up-to-date
Configuration cache entry stored.
```

The command was `ANDROID_HOME=/home/shaun/Android/Sdk JAVA_HOME=/home/shaun/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue`.
No task failed. The lead's baseline for `322dde9` is 915 tasks green; this branch adds the Android
host-test tasks `udea-render` now has sources for.

The GL tests, for real (`$A/gl-merged.log`):

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true --continue
```
```
BUILD SUCCESSFUL in 1m 8s
121 actionable tasks: 11 executed, 1 from cache, 109 up-to-date
Configuration cache entry stored.
```

Every suite, none skipped (`$A/gl-merged-suites.txt`):

```
render.gl.GlCaptureTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.GlKoolInputTest" tests="1" skipped="0" failures="0" errors="0"
agent.host.gl.OverlayCaptureIsolationTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.GlKoolKeyTableTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.GlCaptureDeterminismTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.GlKoolPointerTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.GlModelRenderTest" tests="1" skipped="0" failures="0" errors="0"
agent.host.gl.OffscreenRenderToolsTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.OffscreenBackendTest" tests="2" skipped="0" failures="0" errors="0"
render.gl.KoolThreadShutdownTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.GlUiLayerTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.GlOverlayIsolationTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.OffscreenBackendSecondCreateTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.OffscreenBackendShutdownTest" tests="1" skipped="0" failures="0" errors="0"
render.gl.OffscreenBackendExplodingCaptureTest" tests="1" skipped="0" failures="0" errors="0"
```

Also run: `sh gradlew udeaVerifyModuleGraph udeaVerifyAgentsMd` - `BUILD SUCCESSFUL in 5s`
(`$A/gates.log`).

## 4. Images

In `/srv/ssd1/workspace/Udea/build/debug-screenshots/`:

- `issue228-every-key-pressed-green-and-three-broken-tables.png` - every `InputKey` as a tile, green or
  red, for the green run and for M1-M3, drawn from the saved test XML. Proves the guard is per key:
  only the keys each mutation broke turn red.
- `issue228-hud-key-labels-from-named-asset.png` - `runMatchShot`'s `item_bar.png` on the merged tree.
  The ability boxes read SPACE, Q, E, R, which `MobaControls.keyName` now takes from the names the
  asset wrote. It looks the same as before this ticket, and that is the point: the labels no longer
  come from a number-to-letter table.

## 5. The issue, criterion by criterion

| Criterion | Proof |
|---|---|
| A game names keys symbolically; no backend key integer appears in a controls asset | `moba/game/assets/control/controls.udea.kts` writes `key(InputKey.…)` only. `NamedKeyBindingTest`: `key(87)` in a script is a `SCRIPT_COMPILATION_FAILED` error, and `key(InputKey.Space)` reaches the runtime `Binding` as `BindingInput.Key(InputKey.Space)` |
| Mapping lives in `udea-render`, per backend | `KoolKeyTable.kt` (shared special keys), `KoolKeyTable.jvm.kt` (GLFW), `KoolKeyTable.android.kt` (Android). Evidence command, both halves, and M1-M5 |
| Both halves of a Kool key event accounted for | `KoolKeyTable` KDoc, "The universal code, not the local one": which half, and why. Comment on #228 |
| `InputBindings.keys`' KDoc no longer claims LibGDX codes | `ActionBinding.keys` KDoc now says keys are named, with no backend numbers; `DeviceState`, `UiInput` and `UiPointers` KDocs fixed to match |
| A test fails if a symbolic key maps to the wrong physical key, per key | `GlKoolKeyTableTest` (every key through Kool's real GLFW callback, plus the negatives) and `AndroidKeyTableTest` (every key through Kool's real Android map). M1-M5 and the grid image |
| `moba`'s controls asset migrated | The asset above; `MobaKeyBindingTest` drives all eight keys through `DeviceIntent` and checks every other key does nothing; mutation below |
| (comment) the symbolic set covers special keys too, and a game never sees a raw constant vs a private code | `InputKey` holds the function row, cursor, editing keys and modifiers; `KoolKeyTable.SPECIAL` maps them from Kool's own constants. Escape in the evidence and M3 |

`moba` mutation (`$A/mut-moba-attack-digit3.diff`, result in `$A/mut-moba-attack-digit3.xml`):

```diff
diff --git a/moba/game/assets/control/controls.udea.kts b/moba/game/assets/control/controls.udea.kts
index 986db4c..9de1086 100644
--- a/moba/game/assets/control/controls.udea.kts
+++ b/moba/game/assets/control/controls.udea.kts
@@ -65,7 +65,7 @@ axis2D(name = "move")
 binding(
     name = "attack_binding",
     control = reference("control/attack"),
-    input = key(InputKey.Space),
+    input = key(InputKey.Digit3),
 )
 
 binding(
```
```
failure message="org.opentest4j.AssertionFailedError: Space did not press the action it is bound to
failure message="org.opentest4j.AssertionFailedError: keys the game does not bind still did something ==&gt; expected: &lt;[]&gt; but was: &lt;[Digit3]&gt;
```

## 6. Regenerated files

- `udea-codegen/net-protocol.lock`, `expected-generated-hashes.txt`: **not touched.** No replicated
  component was added or removed.
- `moba/desktop/src/test/resources/fixtures/moba-3600.udearep` and `moba-36000.udearep`: **regenerated**
  with `sh gradlew :moba:desktop:udeaWriteReplayFixture`, because the controls asset now packs key
  names and moba's asset-graph hash moved. From `$A/replay-fixture-write.log`:
  `assetGraphHash: recorded 064c663e186e52c8... (32 bytes), this build 7bf327c4dfc0c6fd... (32 bytes)`.
  The failing build showed nothing else moved: `proto=0x1bef` and `inputSchema=407227863552470576`
  were equal on both sides. **A merge conflict in these files is a regeneration**: dev-192's level
  work also changes moba's assets, so whichever branch lands second reruns that task.
