# BRIEF — issue #224: Kool input to intents, and the `composegl-kool` UI host

**SHA:** `168df2b`

Branch `issue-224-kool-input-ui-host`, cut from `origin/kmp` at `aaacad4`.
Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a01eb6c762a84065b`.

The acceptance criteria proved below are the **revised** ones the lead put on the issue on
2026-09-18 after splitting the pointer half out to Udea #227, not the originals. Section 7 states
what is absent and what a player experiences because of it.

---

## 1. The evidence command

```
cd /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a01eb6c762a84065b && \
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
      ANDROID_HOME=$HOME/Android/Sdk \
      JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew :udea-render:jvmTest --rerun :udea-render:udeaGlTest --rerun \
    -Pudea.render.requireGl=true --console=plain
```

`--rerun` is on each task deliberately: both are cacheable, and a repeat run of the plain command
reports them `UP-TO-DATE` and asserts nothing. `-Pudea.render.requireGl=true` is what turns
`GlAvailability.require()` from "skip quietly when there is no display" into "fail", which is the
whole point of running under xvfb.

No `:moba:` task is in it. `moba` is baseline-red on `kmp` and #212 is rewriting it in parallel, so
an evidence command that touched it would be measuring somebody else's branch.

The suites that matter, from that run's JUnit XML:

```
<testsuite name="dev.wildware.udea.render.gl.GlKoolInputTest" tests="1" skipped="0" failures="0" errors="0"
<testsuite name="dev.wildware.udea.render.gl.GlUiLayerTest" tests="1" skipped="0" failures="0" errors="0"
<testsuite name="dev.wildware.udea.render.kool.KoolKeyboardOrderTest" tests="8" skipped="0" failures="0" errors="0"
<testsuite name="dev.wildware.udea.render.RenderModuleGraphTest" tests="5" skipped="0" failures="0" errors="0"
```

The first three are new; `RenderModuleGraphTest` is not, and one of its five is. Frames land in
`udea-render/build/reports/udea/gl/`: `ui-window-*.png` is what a person sees, `ui-capture-*.png` is
what an agent's `render.screenshot` returns.

**It goes red when the feature is reverted.** Eight mutations in section 6, each with the literal
`git diff` taken from the run that produced its failures. Two of the eight are controls.

---

## 2. What this branch does

### `KoolKeyboard` — the one class that reads a real key on this renderer

`udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolKeyboard.kt`.

`GdxKeyboard` used to be that class and went with LibGDX in #211. `DeviceIntent` has always talked
to a `KeyboardState` and has never named a backend, so a game builds a `KoolKeyboard`, hands it to a
`DeviceIntent`, and every existing binding works again.

Every event is offered to `UiInput` **before** anything is recorded:

- the interface takes it: Kool's event is marked `isConsumed` for whatever sits below, and nothing is
  recorded, so no binding under an open menu can fire from it;
- the interface declines: it is recorded exactly as a key nobody was listening for;
- something *above* already consumed it: skipped entirely.

**The ordering is decided here rather than on Kool's `InputStack`, and it had to be.** A handler
there sets `blockAllKeyboardInput`, which blocks every key for every handler below it — an interface
using that would eat W as readily as Escape. The stack still decides whether this handler is asked
at all, and `KoolKeyboard` pushes itself to the **bottom** of it, so a console or an editor pushed on
top works without knowing it exists.

### `UiInput` / `KeyStroke` — a backend-neutral seam

`udea-render/src/commonMain/kotlin/dev/wildware/udea/render/input/UiInput.kt`. A `fun interface`
returning "did the interface use that?", with `UiInput.NONE` for a game with no interface. It names
neither Kool nor ComposeGL, which is what lets `KoolKeyboardOrderTest` drive the whole ordering with
a fake interface and no GL context at all.

### `UiLayer` — the `composegl-kool` host

`udea-render/src/commonMain/kotlin/dev/wildware/udea/render/ui/UiLayer.kt`. Owns one
`ComposeGlScene`, shows one `UiScreen` at a time, and implements `UiInput` by translating a
`KeyStroke` into the toolkit's own `KeyEvent` / `TextEvent` and handing it to `ComposeGlScene.player`,
which answers whether the toolkit used it. `KoolBackend.show(ui)` attaches it on the render thread
and owns it from then on, closing it before the pipeline — a game never names a `KoolContext`, which
under `UDEA-MG-002` it could not do anyway.

`KeyTable.kt` maps Kool's universal codes to the toolkit's `Key`; anything unrecognised becomes
`Key.Unknown` rather than throwing, because an unmapped key must still be a key the game can bind.

### `UiFonts` / `DesktopFonts` — why there is an extra type here

`UiLayer` first took ComposeGL's `AtlasFonts` directly. That type is in `composegl-render`, which
reaches this module only through `composegl-kool`, an `implementation` dependency — so a game could
not have named it even to pass one in, and promoting it to `api` would have put a renderer on every
game's compile classpath, which is exactly the leak `UDEA-MG-002` exists to stop. `UiFonts` is an
abstract class with an `internal` constructor and an `internal` `atlas`, so "only `udea-render` can
build one" is a compiler guarantee rather than a convention. `DesktopFonts` is the desktop's, over
`composegl-lwjgl3`'s stb rasteriser, and lives in `jvmMain` because Android rasterises with its own
`Typeface` inside `composegl-kool`.

Confirmed by the ComposeGL maintainers on the issue: *"Keeping the renderer off every game's compile
classpath is the right call. Don't expose AtlasFonts."*

### Where a ComposeGL screen draws, and why a capture cannot contain it

A `ComposeGlScene` is a Kool `Scene`. `UiLayer.attach` adds it **after** `KoolSurface`'s
`udea-screen` scene, so it lands in the **window's** framebuffer, over the presented frame. It is
never inside the `OffscreenPass2d` that every `RenderSystem` draws into and that `KoolPixelSource`
downloads.

So a menu is absent from `render.screenshot` because those are two different render targets, not
because of an ordering rule somebody maintains — the same argument the agent overlay's exclusion
already rests on (`KoolSurface`'s KDoc). Section 5 measures it anyway, including while the interface
is mid-composition, because a structural argument nobody checked is still an argument.

---

## 3. The test that was deleted, and which half of it came back

The ticket named `UiInputOrderTest` as "the existing assertion of UI gets first refusal", so I looked
for it. It is **not in the tree**: it was deleted on `origin/kmp` by `5d7e6a7`
(*"#211: WIP from dev-211 (Kool rewrite in progress)"*), along with `GdxKeyboard`, the
`HeadlessBackend`-based `UiLayer` and the gdx `InputProcessor` chain it drove. It had four tests:

| Its test | Status on this branch |
|---|---|
| `a key the composition consumes never reaches the keyboard` | restored — `KoolKeyboardOrderTest.a key the interface takes never reaches the keyboard`, and end to end in `GlKoolInputTest` |
| `a key the composition declines still reaches the keyboard` | restored — `KoolKeyboardOrderTest.a key the interface declines still reaches the keyboard`, and end to end in `GlKoolInputTest` |
| `a click on a button is consumed before the keyboard sees the frame` | **not restored.** Udea #227 — see section 7 |
| `a click on empty screen is declined so the world still gets it` | **not restored.** Udea #227 — see section 7 |

Two things were kept from it deliberately rather than reinvented. Its fixture put a **focused**
`Button` inside the panel that handles Escape, because the toolkit's `KeyRouter` starts at the
focused node and walks outwards, so a handler on a panel with nothing focused inside it is never
asked; `GlKoolInputTest.EscapeScreen` does the same and its KDoc says why. And its insistence that
"the keyboard saw nothing" is a green result for a chain wired backwards **and** for a chain not
wired at all: every consumed case here has a declined case beside it.

---

## 4. Three build-file changes, none of them cosmetic

**`build.gradle.kts` — the `central.sonatype.com` snapshot repository.**
`composegl-kool:0.7.0-SNAPSHOT` does not exist on either Sonatype host this repository declared:
`oss.sonatype.org/content/repositories/snapshots/` and
`s01.oss.sonatype.org/content/repositories/snapshots/` both answer **404** for
`dev/wildware/composegl/composegl-kool/0.7.0-SNAPSHOT/maven-metadata.xml`. It is served by
`https://central.sonatype.com/repository/maven-snapshots/`, which is the host ComposeGL's own
`docs/wiki/Kool.md:22` names — so this is **Udea's `build.gradle.kts` being stale**, not ComposeGL
documenting the wrong host. (I said the opposite to the lead early on and withdrew it; the record is
on the issue.) Added **above** the two stale entries rather than replacing them: removing them is a
separate question about every other snapshot this build might resolve, and adding is the reversible
half.

**`gradle.properties` — `android.useAndroidX=true`.** Not a preference. `composegl-ui` brings
`org.jetbrains.compose.runtime`, which is published under the `androidx.compose.runtime`
coordinates; AGP refuses an `androidx.*` dependency on any configuration of a project it manages
without this flag, and `udea-render` has an Android target, so without it the module stops resolving
entirely. The error names AndroidX and never mentions ComposeGL, which is why the comment in the
file says where it comes from. The support library it opts out of has never been on this
repository's classpath.

**`gradle/libs.versions.toml` — `composegl` 0.6.0 to 0.7.0-SNAPSHOT**, plus `composegl-kool` and
`composegl-lwjgl3` aliases. Every composegl artifact moves together: `composegl-kool:0.7.0-SNAPSHOT`
declares `composegl-ui` and `composegl-render` at the same version, and a mixed graph would not
link. The snapshot most of this branch was built against is **`0.7.0-20260917.070212-2`** — a
`-SNAPSHOT` string does not identify a build, so `--refresh-dependencies` is what picks up a later
one.

**And one landed while I was writing this, so it is measured rather than assumed.**
`0.7.0-20260918.215548-3` was published at 21:55 today, and a reviewer's `--refresh-dependencies`
build will resolve it rather than `-2`. I ran section 1's command with `--refresh-dependencies`,
confirmed the new jar was fetched (57005 bytes, cached 21:59, against `-2`'s 53527), and it is
**green**:

```
BUILD SUCCESSFUL in 1m 20s
142 actionable tasks: 15 executed, 127 up-to-date
```

`-3` is the snapshot #227 has been waiting for: `javap` on its jar shows a new
`dev.wildware.composegl.kool.PointerUse(int pointer, int frame, boolean used)` and
`ComposeGlScene.setOnPointerUsed(Function1<PointerUse, Unit>)` — the per-pointer report the lead
specified. Its `ComposeGlScene` constructor also gained a fourth parameter with a default, which is
source-compatible and is why this branch still compiles against it unchanged. **Nothing on this
branch consumes any of that**: the lead's ruling split pointers to #227, and a half-wired pointer
path landing here to look finished is exactly what that ruling said not to do.

**And one test-resource addition that needs saying out loud:**
`udea-render/src/jvmTest/resources/fonts/DejaVuSans.ttf`, 759720 bytes. ComposeGL's `StbFonts`
throws `no fonts were registered` when a `Button` is composed with no family registered, which
killed the render loop in a way that surfaced several frames later as `CaptureStalledException`. The
font is test-only, never packaged, and its licence (Bitstream Vera / public-domain Arev derivative,
permissive and redistributable) sits beside it in `DejaVuSans-LICENSE.txt` with a header saying what
it is doing here.

---

## 5. The images

All in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`, all written by `GlUiLayerTest` in the run
of section 1, under xvfb with `-Pudea.render.requireGl=true`.

| File | What it shows | What it proves |
|---|---|---|
| `issue224-kool-scene-no-ui.png` | The window with no screen shown: the Kool scene, presented and letterboxed | The before state, and that the window read-back is reading something real rather than black |
| `issue224-composegl-screen-over-kool-scene.png` | The same window with a ComposeGL panel, border and label drawn over it | A ComposeGL screen draws over the Kool scene in Offscreen mode — AC2's first half |
| `issue224-agent-capture-has-no-ui.png` | The agent's capture of that same moment, 160x90 | The interface is absent from the frame `render.screenshot` returns — AC2's second half |
| `issue224-ui-three-frames.png` | The three above, tiled | The whole of AC2 in one picture |
| `issue224-ui-redrawing-frame-a.png` | A window frame while the panel is moving | With b and c: the toolkit really was recomposing and drawing different pixels |
| `issue224-ui-redrawing-frame-b.png` | The next one | " |
| `issue224-ui-redrawing-frame-c.png` | The next one | " |
| `issue224-capture-during-redraw.png` | A capture taken during that churn | Byte-identical to the no-interface capture: the exclusion is not a property of a settled frame |
| `issue224-moving-ui-vs-capture.png` | The three moving frames and that capture, tiled | The mid-composition question answered by measurement rather than by argument |

The measurement behind the last four. Every capture from that run is one file by content:

```
cdb6bdc8f872127404a49ba0dbf1595b  ui-capture-hidden.png
cdb6bdc8f872127404a49ba0dbf1595b  ui-capture-moving-0.png
cdb6bdc8f872127404a49ba0dbf1595b  ui-capture-moving-1.png
cdb6bdc8f872127404a49ba0dbf1595b  ui-capture-moving-2.png
cdb6bdc8f872127404a49ba0dbf1595b  ui-capture-shown.png
```

while the window read-backs interleaved with them are all different:

```
49736fa4bc66471da9f934adff94364d  ui-window-hidden.png
7649c80ed9c9ea153a012d2e7a29052f  ui-window-moving-0.png
c416649d88c1cf8a44dd46ee10aa933d  ui-window-moving-1.png
874c00a17b44f177ed34f63f2d5da6ec  ui-window-moving-2.png
9b4608554f33938c908d42238bbc366d  ui-window-shown.png
```

**Two honest notes on that.** First, the churn frames come from a thread rewriting Compose state as
fast as it can, so how many *distinct* window frames land in three samples is not deterministic: an
earlier run of the same test gave two identical ones out of three. That is why what the test
*asserts* is the recomposition counter (`recompositions >= CHURNED_CAPTURES`) rather than the pixels
— the hashes above are a measurement of one run, not a guarantee. Mutation **m4** is the control
that shows the counter is real: with the scene never added to the context it reads *"the moving
screen composed 0 times while 3 captures were taken"*.

Second, all five captures being one file says the interface is absent; it does **not** say the
capture path is alive. What says that is `GlCaptureDeterminismTest`, which fails under **m6** with
*"the capture did not change when the drawn scene did, so it is not reading the frame"*.

---

## 6. The mutation table

Produced by a script that applies one mutation, saves `git diff`, runs the named tasks under xvfb
with `-Pudea.render.requireGl=true --continue`, parses the JUnit XML, and reverts with
`git checkout --`. Every diff below is the file that run wrote, not a retyped description; every
failure line is from that run's XML. Tasks: `U` = `:udea-render:jvmTest`, `G` =
`:udea-render:udeaGlTest`.

### m1 — `KoolKeyboard` records the key before asking the interface

```diff
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolKeyboard.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolKeyboard.kt
@@ -93,11 +93,10 @@ public class KoolKeyboard(
     internal fun onKeyEvents(events: List<KeyEvent>) {
         for (event in events) {
             if (event.isConsumed) continue
+            record(event)
             if (ui.onKey(strokeOf(event))) {
                 event.isConsumed = true
-                continue
             }
-            record(event)
         }
     }
```

`U G` — 3 failures:

```
SUITE dev.wildware.udea.render.gl.GlKoolInputTest failures 1
  FAILED a Kool key becomes an intent, and one the interface takes does not() :: org.opentest4j.AssertionFailedError: Escape closed the panel AND fired the binding under it
SUITE dev.wildware.udea.render.kool.KoolKeyboardOrderTest failures 2
  FAILED a press the interface took becomes no intent()[jvm] :: org.opentest4j.AssertionFailedError: closing the panel also fired the binding behind it
  FAILED a key the interface takes never reaches the keyboard()[jvm] :: org.opentest4j.AssertionFailedError: Escape closed a panel AND was held down for the gameplay binding under it
```

This is the ticket's central claim, and it is the mutation that restores the *real* wrong shape: the
interface is still asked, still answers, and Kool's event is still marked — only the order moved.

### m2 — `KoolKeyboard` never asks the interface at all

```diff
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolKeyboard.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolKeyboard.kt
@@ -93,10 +93,6 @@ public class KoolKeyboard(
     internal fun onKeyEvents(events: List<KeyEvent>) {
         for (event in events) {
             if (event.isConsumed) continue
-            if (ui.onKey(strokeOf(event))) {
-                event.isConsumed = true
-                continue
-            }
             record(event)
         }
     }
```

`U G` — 4 failures:

```
SUITE dev.wildware.udea.render.gl.GlKoolInputTest failures 1
  FAILED a Kool key becomes an intent, and one the interface takes does not() :: org.opentest4j.AssertionFailedError: the panel never saw Escape, so nothing was refused
SUITE dev.wildware.udea.render.kool.KoolKeyboardOrderTest failures 3
  FAILED a press the interface took becomes no intent()[jvm] :: org.opentest4j.AssertionFailedError: closing the panel also fired the binding behind it
  FAILED a key the interface takes is marked consumed for whatever is under it()[jvm] :: org.opentest4j.AssertionFailedError: Kool's event was not marked, so a lower handler would see it
  FAILED a key the interface takes never reaches the keyboard()[jvm] :: org.opentest4j.AssertionFailedError: Escape closed a panel AND was held down for the gameplay binding under it
```

### m3 — `KoolKeyboard` ignores an event something above already consumed

```diff
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolKeyboard.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolKeyboard.kt
@@ -92,7 +92,6 @@ public class KoolKeyboard(
      */
     internal fun onKeyEvents(events: List<KeyEvent>) {
         for (event in events) {
-            if (event.isConsumed) continue
             if (ui.onKey(strokeOf(event))) {
                 event.isConsumed = true
                 continue
```

`U` — 1 failure:

```
SUITE dev.wildware.udea.render.kool.KoolKeyboardOrderTest failures 1
  FAILED a key already consumed above is not recorded()[jvm] :: org.opentest4j.AssertionFailedError: something above had already taken W and the game saw it anyway
```

### m4 — `UiLayer` never puts its scene on the context

```diff
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
@@ -113,7 +113,6 @@ public class UiLayer(
         val backend = ComposeGlBackend(fonts.atlas)
         val scene = ComposeGlScene(backend, design, policy)
         scene.setContent { screen?.content() }
-        ctx.addScene(scene.scene)
         mounted = Mounted(ctx, backend, scene)
     }
```

`G` — 2 failures:

```
SUITE dev.wildware.udea.render.gl.GlKoolInputTest failures 1
  FAILED a Kool key becomes an intent, and one the interface takes does not() :: org.opentest4j.AssertionFailedError: the panel never saw Escape, so nothing was refused
SUITE dev.wildware.udea.render.gl.GlUiLayerTest failures 1
  FAILED a ComposeGL screen draws over the Kool scene and never into a capture() :: org.opentest4j.AssertionFailedError: the moving screen composed 0 times while 3 captures were taken, so nothing was changing and this proves nothing about a capture taken mid-composition
```

This is the vacuity guard's own control. Without the second failure, three identical captures of a
screen that never redrew would look exactly like three identical captures of a screen the capture
path cannot see.

### m5 — `UiLayer` never offers a key-down to the toolkit

```diff
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
@@ -96,7 +96,7 @@ public class UiLayer(
             KeyPhase.Character ->
                 event.character.isPrintable() && scene.player.onText(TextEvent(event.character.toString()))
 
-            KeyPhase.Down -> scene.player.onKey(toolkitEvent(event, KeyEventType.Down, repeat = false))
+            KeyPhase.Down -> false
             KeyPhase.Repeat -> scene.player.onKey(toolkitEvent(event, KeyEventType.Down, repeat = true))
             KeyPhase.Up -> scene.player.onKey(toolkitEvent(event, KeyEventType.Up, repeat = false))
         }
```

`G` — 1 failure:

```
SUITE dev.wildware.udea.render.gl.GlKoolInputTest failures 1
  FAILED a Kool key becomes an intent, and one the interface takes does not() :: org.opentest4j.AssertionFailedError: the panel never saw Escape, so nothing was refused
```

Only the GL test can catch this: it is the half of the path that is a real ComposeGL `KeyRouter`
walking a real focused node, which `KoolKeyboardOrderTest`'s fake interface stands in for.

### m6 — `RenderSystem`s draw into the window batch instead of the captured pass

```diff
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolSurface.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolSurface.kt
@@ -100,7 +100,7 @@ internal class KoolSurface(
     fun targets(): RenderTargets = RenderTargets(
         offscreen = OffscreenTarget(width, height),
         screen = ScreenTarget(windowWidth, windowHeight),
-        batch = offscreenBatch,
+        batch = screenBatch,
         screenBatch = screenBatch,
         surface = this,
         pixels = KoolPixelSource(pass),
```

`G` — 4 failures across four suites:

```
SUITE dev.wildware.udea.render.gl.GlCaptureDeterminismTest failures 1
  FAILED twenty-repeat determinism, change tracking, tick readback and region stability() :: org.opentest4j.AssertionFailedError: the capture did not change when the drawn scene did, so it is not reading the frame
SUITE dev.wildware.udea.render.gl.GlCaptureTest failures 1
  FAILED the pixel path - alpha stomp, draw order, afterTick, no-perturb, region() :: org.opentest4j.AssertionFailedError: the sentinel red quad is missing ==> expected: <255> but was: <0>
SUITE dev.wildware.udea.render.gl.GlOverlayIsolationTest failures 1
  FAILED an overlay reaches the window and never the capture() :: org.opentest4j.AssertionFailedError: without the overlay the window should show the blitted scene, was #000000 ==> expected: <255> but was: <0>
SUITE dev.wildware.udea.render.gl.GlUiLayerTest failures 1
  FAILED a ComposeGL screen draws over the Kool scene and never into a capture() :: org.opentest4j.AssertionFailedError: with no screen shown the window should show the presented Kool scene, was #000000 ==> expected: <255> but was: <0>
```

This one is not about code I wrote — it is the control for section 5's "all five captures are one
file". If the capture were reading nothing at all, every hash would still agree; this shows it is
not.

### m7 — control: the Kool frontend declared `api`, so it reaches every game

```diff
--- a/udea-render/build.gradle.kts
+++ b/udea-render/build.gradle.kts
@@ -40,7 +40,7 @@ kotlin {
 
                 // `composegl-kool` is a frontend: it draws that tree through Kool's GL context.
                 // `implementation`, so it stops here - the same reason `kool-core` does.
-                implementation(libs.composegl.kool)
+                api(libs.composegl.kool)
```

`U` — 1 failure:

```
SUITE dev.wildware.udea.render.RenderModuleGraphTest failures 1
  FAILED no ComposeGL frontend is on a consumer's compile classpath()[jvm] :: org.opentest4j.AssertionFailedError: libs.composegl.kool must be `implementation`: as `api` it would reach every game that depends on udea-render, which is what UDEA-MG-002 exists to prevent
```

### m8 — control: a comment that merely mentions the banned line must stay green

```diff
--- a/udea-render/build.gradle.kts
+++ b/udea-render/build.gradle.kts
@@ -40,6 +40,7 @@ kotlin {
 
                 // `composegl-kool` is a frontend: it draws that tree through Kool's GL context.
                 // `implementation`, so it stops here - the same reason `kool-core` does.
+                // api(libs.composegl.kool) would put a renderer on every game's classpath
                 implementation(libs.composegl.kool)
```

`U` — **exit 0**, and the suite really ran:

```
<testsuite name="dev.wildware.udea.render.RenderModuleGraphTest" tests="5" skipped="0" failures="0" errors="0"
```

A fence that fails on prose is as wrong as one that passes on a real violation. The first version of
this fence did a raw-line search and would have gone red on the *comment that explains why the line
must not be written* — a sentence that lives two lines above it in the shipped file. It strips `//`,
`/*` and `*` lines before searching, and this is the row that says so.

---

## 7. What is absent: pointers, and gamepads

### Pointers are absent, not wrong

**There is no pointer-to-intent path in this branch at all.** `ActionBinding` has no mouse-button
field, `DeviceIntent` reads a `KeyboardState` and a `GamepadState` and nothing else, and no
`PointerState` type exists.

**What a user experiences**: a click produces **no intent** on this renderer. It cannot drive the
simulation — and for the same reason it cannot leak past an open menu, because there is nothing for
it to leak into. A click on a ComposeGL control still works: `composegl-kool` handles Kool's pointers
itself, inside the toolkit. So this ships an **absent** behaviour, not a known-wrong one.

**Why**, and this is upstream rather than a Udea limitation. `ComposeGlScene` installs its own
private `InputStack` pointer listener in `init`, samples Kool's pointers on the update thread, calls
`KoolPointerInput.onFrame(...)` a frame later on the render thread, and **discards the `used`
boolean** that returns. It never calls Kool's `Pointer.consume()`. So nothing downstream can learn
whether the interface took a click, and an ordering built on `Pointer.isConsumed()` would be told
"the interface wanted nothing" for every click. Both workarounds were rejected: driving
`scene.player` ourselves double-delivers (the scene's listener is private and cannot be switched off)
*and* touches the toolkit off the render thread, which is a data race; removing that listener by its
public handler name couples us to the string `"composegl"`.

**Udea #227** carries the pointer criterion against the agreed upstream fix, with the one-frame
ordering consequence recorded as settled.

### Keys and pads: the thread question, and why it is asserted rather than assumed

ComposeGL's rule is that `player` — and therefore `KeyRouter`, `KeyNavigator`, `GamepadNavigator`,
`FocusManager` and the node tree — is touched **only on the render thread**. Udea satisfies it, and
by configuration rather than by design: `KoolThread.config()` passes `asyncSceneUpdate = false`, and
in Kool 0.19.0 `Lwjgl3Context.renderFrame()` only sets `nextFrameData` when that flag is on. With it
off, `render()` — where `Input.poll(this)` and the `onRender` callbacks run — is called inline on the
same thread that then calls `backend.renderFrame(...)`, where `ComposeGlScene.render()` touches the
toolkit. One thread, `udea-kool`, for all three.

That is one line from being untrue, and the KDoc explaining why the flag is off lives in a different
file from the line that would turn it on. So `GlKoolInputTest` records `Thread.currentThread()` in
three places that are actually on the path — inside the Kool input callback, inside the composition,
and inside a frame callback — and `assertSame`s them, with a failure message naming
`KoolThread.config()` and `asyncSceneUpdate`. That test is what goes red the day somebody flips it
back on.

**One thing being single-threaded does not buy**, stated because "one thread" invites the wrong
conclusion: it does not buy a same-frame verdict. Within a frame the order is poll, then Udea's tick,
then ComposeGL's `onFrame`, so a pointer verdict on frame N's click exists only after Udea has
already ticked N. #227's pointer intents still land one frame later, for an **ordering** reason
rather than a threading one.

### No Kool gamepad, and that is deliberate

`GamepadState.NONE` is still the only implementation, which is what it was before this ticket and
what its own KDoc already said. Kool does have `ControllerInput`, so a `KoolGamepad` is a real
possibility and no design change — I did not write one because I **cannot drive it here**:
`ControllerInput.addController` is `internal`, and `Controller.setButtonState` / `setAxisState` are
`protected`, so nothing outside Kool can register a fake pad. A device reader that nothing can
exercise is a test that cannot fail, which is the third item on the reviewer's reject list.

Everything above the device interface — the radial deadzone, the rescale, the clamp, the combination
with the keyboard vector — is already tested in `InputModelTest` and `IntentSamplingTest` and does
not change when a real pad arrives.

---

## 8. The key table, and a number I got wrong and corrected

`KeyboardState.isKeyDown` and `ActionBinding.keys` are ints in whatever table the build's renderer
speaks. That was `com.badlogic.gdx.Input.Keys` before #211 and is **Kool's universal key codes** now.
Four KDocs still said gdx (`DeviceState`, `InputBindings`, `DeviceIntent`, `InputModule`); all four
now name the backend's own table and point at `KoolKeyboard`.

**I first told the lead W was `119`. It is `87`.** My 119 came from Kool's
`UniversalKeyCode(codeChar: Char)` convenience constructor, which lowercases — a helper for callers
that the GLFW path never goes through. `GlfwInput.kt:146` is
`KEY_CODE_MAP[key] ?: UniversalKeyCode(key)` with `key` the raw GLFW key int, and `KEY_CODE_MAP`
holds only special keys — modifiers, escape, enter, tab, backspace, delete, insert, home, end, page
up/down, cursors, F1-F12, numpad. No letter, no digit, no space. So a printable key's code is the
GLFW constant, and GLFW numbers letters by ASCII **uppercase**: `javap -constants` on
`lwjgl-glfw-3.4.3.jar` gives `GLFW_KEY_SPACE = 32`, `GLFW_KEY_A = 65`, `GLFW_KEY_W = 87`.

**Escape is the exception and is not GLFW's 256**: it *is* in that map, mapped to Kool's own
`KEY_ESC`, whose code is `-9`. Anything from that map must be written `KeyboardInput.KEY_*.code`.

**The defect in my own evidence that this exposed, which is the part worth recording.** The first
`GlKoolInputTest` *synthesised* the Kool `KeyEvent` and bound the same number it had just written
into it, so it passed for any number at all and could not have caught me. It now takes Kool's own
GLFW key callback off the window on the render thread, invokes it with the raw `GLFW_KEY_W`, and puts
it straight back (GLFW has no getter, only a setter that returns the previous one), so `GlfwInput`
decides the number. `assertFalse(keyboard.isKeyDown('w'.code))` is the negative control in the test
body. Binding the action to 119 instead produced, from that run's
`TEST-dev.wildware.udea.render.gl.GlKoolInputTest.xml`:

```
org.opentest4j.AssertionFailedError: W never became an intent. The binding is on 119, which is `GLFW_KEY_W`; if Kool derived some other code from the physical key, this is where a game's controls asset stops matching the keyboard. The interface saw [87]
```

`The interface saw [87]`. That message's "which is `GLFW_KEY_W`" clause was itself wrong under the
mutation and is reworded in the shipped test; the block above is the unedited output of the run that
measured the number, quoted as it was produced.

**Consequence for `moba`, which is #212's and not mine.** `moba/assets/control/controls.udea.kts`
writes gdx literals (`val KeyW = 51`); under Kool, 51 is `'3'`. Relayed to the lead and to dev-212
with the measurement, and corrected on the issue. I did not touch `moba`.

---

## 9. `sh gradlew build --continue`

```
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew build --continue --console=plain
```

```
> Task :moba:compileKotlin FAILED
BUILD FAILED in 1m 22s
752 actionable tasks: 26 executed, 1 from cache, 725 up-to-date
```

`> Task ... FAILED` appears exactly once in that transcript, and this is it.

**Tasks this ticket turns green:** `:udea-render:jvmTest`, `:udea-render:udeaGlTest`,
`:udea-render:udeaVerifyHeadless`, `:udea-render:check`, `:udea-render:build`, and
`:udea-gradle:test` (the two `WallClockBudgetCensus` rows below).

**Baseline failures, unchanged:** exactly one, `:moba:compileKotlin`, which was red on `aaacad4`
before I touched anything. Its errors are in `moba/src/main/kotlin/dev/wildware/moba/lane/LaneRender.kt`
against the Kool `SpriteBatch2D` that #211 landed — `Unresolved reference 'color' on receiver of type
'SpriteBatch2D'`, `actual type is 'TextureRegion', but 'SpriteRegion' was expected` — which is
precisely the port #212 is doing. Nothing in that file is touched by this branch.

**No task green on the baseline went red.**

### The GL run, for real

Section 1's command, `-Pudea.render.requireGl=true` under xvfb:

```
> Task :udea-render:jvmTest
> Task :udea-render:udeaGlTest
BUILD SUCCESSFUL in 37s
133 actionable tasks: 15 executed, 118 up-to-date
```

That run is on `168df2b`, the SHA at the top, and resolves `composegl-kool` at
`0.7.0-20260918.215548-3` (section 4). The mutation table in section 6 predates that snapshot and
was run against `-2`; every mutation there is a change to Udea source, and the per-mutation failure
messages are Udea's own assertion text, so the snapshot is not what any of them measure.

`$DISPLAY` is empty on this box, so without xvfb `udeaGlTest` would skip and a green `build` would be
saying nothing at all about GL.

### The module-graph gates, run fresh

```
sh gradlew udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd --rerun-tasks
```

```
> Task :udeaVerifyAgentsMd
> Task :udea-render:udeaVerifyModuleGraph
BUILD SUCCESSFUL in 1m 15s
175 actionable tasks: 175 executed
```

`--rerun-tasks` because all three are cacheable and were up to date. `:moba:runUdpProof` is red today
for reasons predating this branch and is not touched by it; `:moba:runLaneShot` needs the `moba`
module that is baseline-red.

### Two `WallClockBudgetCensus` rows

`:udea-gradle:test` went red when the two GL tests landed: `WallClockBudgetCensusTest` demands that
every wall-clock read in a test source is either a budget or a censused non-budget. Both new reads
are `awaitFrames` deadlines — *how long to wait for the render thread to reach a frame* — so both got
a `NOT_A_BUDGET` row naming that reason. This is the gate working, not an obstacle.

---

## 10. The issue, criterion by criterion

The revised criteria, from the lead's ruling of 2026-09-18.

### AC1 — a Kool key/pad event reaches the simulation as an intent through `IntentSource`, and a key a focused ComposeGL control takes is consumed by the UI and never becomes an intent

| Half | Proof |
|---|---|
| A real key becomes an intent | `GlKoolInputTest`: Kool's own GLFW key callback is invoked with `GLFW_KEY_W` on the render thread; `DeviceIntent` (an `IntentSource`) is sampled and `walking.isPressed(walk)` and `pressCount(walk) == 1` hold. Goes red under **m2**, **m5** |
| ...and the number is the backend's, not the test's | Same test: `assertEquals('W'.code, W)`, `assertTrue(keyboard.isKeyDown(87))`, `assertFalse(keyboard.isKeyDown('w'.code))`. Section 8 has the run where the wrong number was measured |
| A key a **focused** control takes is consumed and never becomes an intent | Same test: `EscapeScreen` is a panel with `onKeyEvent { it.key == Key.Escape }` and an `initialFocus = true` `Button` inside, because `KeyRouter` starts at the focused node. `screen.escapes.get() > 0` **and** `assertFalse(closing.isPressed(menu))` **and** `pressCount(menu) == 0`. Goes red under **m1**, **m2**, **m4**, **m5** |
| ...and the unit-level ordering, including cases a GL test cannot reach cheaply | `KoolKeyboardOrderTest`, 8 tests: taken, declined, `isConsumed` honoured from above, release, press counts spent by `endSample`. Goes red under **m1**, **m2**, **m3** |
| The pad half | No Kool gamepad; `GamepadState.NONE` is unchanged and section 7 says why writing one would be a test that cannot fail |
| xvfb GL run with `-Pudea.render.requireGl=true` | Sections 1 and 9 |

### AC2 — a ComposeGL screen draws over the Kool scene in Offscreen mode, and never appears in a capture it should not

| Half | Proof |
|---|---|
| It draws over the scene, in Offscreen | `issue224-composegl-screen-over-kool-scene.png`, and in `GlUiLayerTest` the window centre pixel is the panel colour when a screen is shown and the scene's blue when it is not. Goes red under **m4** |
| It never appears in a capture | `issue224-agent-capture-has-no-ui.png`, and `assertContentEquals(without.png, with.png)` — the capture with a screen shown is byte-identical to the one with none |
| ...including while it is composing | `issue224-capture-during-redraw.png` and `issue224-moving-ui-vs-capture.png`: three captures taken while a churn thread rewrites Compose state, each byte-identical to the no-interface capture, with `recompositions >= 3` asserted so the churn is real. **m4** is the control for that counter |
| ...and the capture is genuinely reading the frame | `GlCaptureDeterminismTest` under **m6** |

### AC3 — no Kool or ComposeGL backend outside `udea-render` (module-graph gate green)

| Half | Proof |
|---|---|
| The configuration-level rule | `udeaVerifyModuleGraph`, run fresh in section 9. `UDEA-MG-002` already bans `de.fabmax.kool:*` and every ComposeGL frontend from headless modules |
| The bytecode-level rule | `:udea-render:udeaVerifyHeadless` green in the full build — it fails when a headless module's *compiled class* names a renderer type, which is the case a configuration check cannot see |
| The hole neither of those closes | `RenderModuleGraphTest.no ComposeGL frontend is on a consumer's compile classpath` — new. The gate checks which modules *have* a dependency, not what **scope** it is at, so a frontend promoted to `api` inside `udea-render` would arrive on every game's compile classpath with both gates still green. It asserts `api` for `libs.composegl.ui` and `implementation` for `libs.composegl.kool` and `libs.composegl.lwjgl3`. Goes red under **m7**; **m8** is its prose control |

---

## 11. Regenerated files

**None.** This branch adds no replicated component, so `udea-codegen/net-protocol.lock` and
`udea-codegen/src/test/resources/expected-generated-hashes.txt` are untouched and no id moved.
`:udea-codegen:udeaCheckProtocolLock` and `:moba:udeaCheckProtocolLock` both ran in the full build
and passed.

No file in `docs/contracts/` was changed, and nothing in this ticket needed one to change.
`udeaVerifyContracts` is green.

---

## 12. Things a reviewer should know that are not in the diff

- **`gradlew` shows as modified in `git status`.** That is the local `chmod +x` this box needs, and
  it is deliberately **not staged** on any commit of this branch.
- **`UiInput.kt` was first committed with a literal NUL byte in `KeyStroke.NO_CHARACTER`'s char
  literal**; commit `c1eda30` fixes it. Kotlin compiles it either way, so nothing was red — but `git diff`
  called the whole file `Bin 0 -> 3998 bytes`, which means a reviewer would never have seen a line of
  it. Grepping every `.kt`, `.kts`, `.md`, `.toml` and `.properties` for that byte finds two others,
  `PngDeterminismTest` and `BundleReaderTest`, both pre-existing and both about bytes on purpose.
  That is the whole class; nothing else.
- **`:udea-assets-compiler:udeaDaemonBudget` was not run.** It is not wired into `check`, so it is
  absent from the build transcript above (`grep -c udeaDaemonBudget` on it returns `0`), and this
  ticket touches nothing the asset daemon reads. Said because that task is the usual casualty of a
  loaded box and its absence should not be read as a pass.
- **Merge state against `origin/kmp`**, fetched at the time of writing (`f998f9c`): 11 behind, 6
  ahead, and all 11 of those commits touch **`.claude/WAVE.md` and nothing else**
  (`git diff --name-only aaacad4..origin/kmp` returns that one path). No overlap with anything on
  this branch.
- **dev-212 was compiling `moba` concurrently** for most of this ticket. Nothing failed that was
  attributable to contention; the one full-build failure is the baseline one, and it is a compile
  error with a source location rather than a resource symptom.
