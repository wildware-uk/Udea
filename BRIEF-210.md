1086a586

# BRIEF — Udea #210, composegl-kool (JVM desktop)

Repository `wildware-uk/composegl`, branch `issue-210-composegl-kool`, worktree
`/srv/ssd1/workspace/composegl-wt/kool-210`, one commit on `origin/master` 6af1c116.
Draft PR #232 exists only so CI runs on the branch (CI triggers on master pushes and pull
requests, not branch pushes). Land it squashed and rebased, not through the PR.

Snapshot version Udea should use: **`dev.wildware.composegl:composegl-kool:0.7.0-SNAPSHOT`**
(last tag `v0.6.0`, next minor; `generatePomFileForJvmPublication` wrote
`composegl-kool-jvm` / `0.7.0-SNAPSHOT`, with `lwjgl-bom:3.4.3` imported). Repository:
`https://central.sonatype.com/repository/maven-snapshots/`.

---

## 1. Evidence command

```
cd /srv/ssd1/workspace/composegl-wt/kool-210 && xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem ./gradlew :composegl-kool:jvmTest :composegl-demo-kool:test --rerun-tasks --max-workers=2
```

Every GL test calls `assumeTrue(DISPLAY)`, so **check `skipped="0"`** in
`composegl-kool/build/test-results/jvmTest/*.xml` and `composegl-demo-kool/build/test-results/test/*.xml`.
Last run on 1086a586 (log `/srv/ssd1/workspace/composegl-wt/logs/issue210-evidence-final.log`,
summary `…/issue210-evidence-final-summary.txt`, spliced from the XML):

```
<testsuite name="KoolPointerInputTest[jvm]" tests="8" skipped="0" failures="0" errors="0" timestamp="2026-09-17T04:35:31.886Z"
<testsuite name="KoolSceneViewTest[jvm]" tests="6" skipped="0" failures="0" errors="0" timestamp="2026-09-17T04:35:31.963Z"
<testsuite name="KoolScreenshotTest[jvm]" tests="20" skipped="0" failures="0" errors="0" timestamp="2026-09-17T04:35:33.127Z"
<testsuite name="dev.wildware.composegl.kool.demo.DemoPointerTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-17T04:35:31.579Z"
```

### It goes red when the feature is reverted

Six mutations on 1086a586, run by `/srv/ssd1/workspace/composegl-wt/logs/mutations/run.sh`
(applies one, saves `git diff`, runs the evidence tasks, reads the XML, `git checkout`s the
file). Diffs, failure lists and logs are in that folder (`m*.diff`, `m*.failures`, `m*.log`);
all six logs end `EXIT=1`. Diffs and failures below are spliced from those files (failure
messages cut at 200 characters by the script).

**m1 — ordinary hand-back instead of `HostState.Restore`**
```
-class KoolCanvas(fonts: StbFonts? = null) : RenderCanvas(GlDevice(KoolGl, HostState.Restore), fonts, KoolTexture.Resolver) {
+class KoolCanvas(fonts: StbFonts? = null) : RenderCanvas(GlDevice(KoolGl, HostState.Leave), fonts, KoolTexture.Resolver) {
```
```
SUITE KoolSceneViewTest[jvm] tests 6 skipped 0 failures 1
  FAILED Kool's own drawing and a widget after a careless scene come out as they do with no scene()[jvm] :: org.opentest4j.AssertionFailedError: Kool's square, with no scene ==> expected: <16711935> but was: <0>
```
Kool's own magenta square disappears even in the frame with no scene. Likely cause, not proven:
`Leave` ends a frame with program 0 bound, while Kool's `ShaderManager` still believes its own
program is.

**m2 — no Kool state put back after a `raw` block**
```
             if (kool != null) {
-                device.suspend()
-                kool.restore()
             }
```
```
SUITE KoolSceneViewTest[jvm] tests 6 skipped 0 failures 3
  FAILED Kool's own drawing and a widget after a careless scene come out as they do with no scene()[jvm] :: org.opentest4j.AssertionFailedError: Kool's square, with no scene ==> expected: <16711935> but was: <0>
  FAILED raw is handed a Kool frame with the picture bound the right way up()[jvm] :: org.opentest4j.AssertionFailedError: the bottom of the panel ==> expected: <255> but was: <16711680>
  FAILED the nearer triangle wins inside a scene view whichever order they are drawn in()[jvm] :: org.opentest4j.AssertionFailedError: the far triangle was drawn first ==> expected: <16711680> but was: <65280>
SUITE KoolScreenshotTest[jvm] tests 20 skipped 0 failures 1
  FAILED nine-patch[jvm] :: java.lang.AssertionError: "nine-patch" does not match its golden: 1.809% of pixels differ by more than 20, mean difference 0.64. See /srv/ssd1/workspace/composegl-wt/kool-210/composegl-kool/build/scre
```
In the careless-scene test the scene frame is drawn first and the "no scene" frame after it, so
that first failure is the careless block's closed colour mask and `GL_NEVER` depth test surviving
into a later frame as Kool's own. The other three most likely fail for the same reason in the same
JVM (the test order was not recorded, so that is an inference): each passes on 1086a586 and has no
other change under it.

**m3 — texture bound without Kool's sampler settings**
```
             loaded.bind()
-            loaded.applySamplerSettings(texture.samplerSettings)
```
```
SUITE KoolScreenshotTest[jvm] tests 20 skipped 0 failures 3
  FAILED nine-patch[jvm] :: java.lang.AssertionError: "nine-patch" does not match its golden: 61.458% of pixels differ by more than 20, mean difference 48.74. See …
  FAILED rotation-and-glow[jvm] :: … 6.571% of pixels differ by more than 20, mean difference 5.89. …
  FAILED tint[jvm] :: … 11.667% of pixels differ by more than 20, mean difference 6.95. …
```
(elided with `…`: the same report path). The three scenes that draw the nine-patch art.

**m4 — `maxSceneSize` asks the driver on any thread**
```
-    override val maxSceneSize: Int get() = if (KoolGl.current) super.maxSceneSize else Int.MAX_VALUE
+    override val maxSceneSize: Int get() = super.maxSceneSize
```
```
SUITE KoolSceneViewTest[jvm] tests 6 skipped 0 failures 1
  FAILED it renders scenes and asks the driver for their size only where Kool's context is()[jvm] :: java.lang.IllegalStateException: No GLCapabilities instance set for the current thread. Possible solutions:
```

**m5 — the scene host drops Kool's pointer frames**
```
-        while (true) pointerInput.onFrame(pointerFrames.poll() ?: break)
+        pointerFrames.clear()
```
```
SUITE dev.wildware.composegl.kool.demo.DemoPointerTest tests 1 skipped 0 failures 1
  FAILED the mouse hovers and clicks a ComposeGL button over a Kool world() :: org.opentest4j.AssertionFailedError: the button is drawn hovered once Kool's mouse is over it ==> expected: not equal but was: <-14011582>
```

**m6 — a press and release inside one Kool frame is dropped**
```
-                    changed && !isDown -> press() or release()
+                    changed && !isDown -> false
```
```
SUITE KoolPointerInputTest[jvm] tests 8 skipped 0 failures 1
  FAILED a press and a release inside one frame is still a click()[jvm] :: org.opentest4j.AssertionFailedError: expected: <[press Primary Offset(x=100.0, y=50.0), release Primary Offset(x=100.0, y=50.0)]> but was: <[]>
```

The same six were first run on a work-in-progress commit (4711f74b, before public API was
trimmed) with the same failures: `/srv/ssd1/workspace/composegl-wt/logs/mutations-wip-4711f74b/`.

---

## 2. Summary

**Scope** (as the lead split it): JVM desktop Kool frontend only. Wasm, Android and a Wasm demo
are Udea #222. The module is KMP with only `jvm()` declared.

**What it is.** `composegl-kool` (published) and `composegl-demo-kool` (not published).

- `ComposeGlScene` / `KoolContext.composeGl(backend, design) { … }` — a ComposeGL screen as a
  Kool `Scene` that clears nothing. It draws in `defaultView.onSetupView`, i.e. while Kool
  renders that scene, on Kool's render thread, into the framebuffer Kool bound. Refuses a Kool
  context not on `RenderBackendGl` (Kool picks Vulkan by default — the demo hung until pinned).
- `KoolCanvas` — `RenderCanvas(GlDevice(KoolGl, HostState.Restore), …)`; `handOver` gives a
  `KoolFrame(ctx, projection, viewport)`; `drawsScenes = true`; `maxSceneSize` asks the driver
  only where Kool's context is current (Kool's GL context is current on its render thread for
  the whole render, so "borrowing the frame's context" here means answering off that thread
  without touching GL); `lend` puts Kool's state back after the block (decision below).
- `KoolGl` — `Gl by LwjglGl`, holding deletes made on a thread with no context until the next
  frame. `KoolState` — depth func, cull-face mode, line width: Kool caches these, the device's
  snapshot does not save them.
- `KoolTexture` — a Kool `Texture2d`, uploaded and bound through Kool.
- `KoolPointerInput` (internal) — Kool's once-a-frame pointer state to pointer events.
  `ComposeGlScene` samples pointers on Kool's update thread (Kool runs update and render in
  parallel with `asyncSceneUpdate`, the default) and hands them over on the render thread.
- `KoolBackend` — canvas, `StbFonts`, `Clipboard.None`, `SoftKeyboard.None`.

**Decisions** (each commented on Udea #210):
1. GL binding and glyphs are `composegl-lwjgl3`'s (`LwjglGl`, `StbFonts`), not Kool's.
   Kool 0.19.0's `GlApi` lacks `isEnabled`, `blendFuncSeparate`, `blendEquationSeparate`,
   `colorMask`, `pixelStorei`, `bindAttribLocation`, `texSubImage2D`, `readPixels` and an
   integer-array `getInteger`, and `RenderBackendGl.gl` is `internal`; Kool has no per-glyph
   rasteriser (desktop text is AWT baking a whole atlas in an internal class). Rejected: a
   binding over Kool's `GlImpl` with LWJGL for the gaps (a second copy of `LwjglGl`).
   Measured first: the Udea #200 Kool spike, with every LWJGL module forced to 3.4.3, passes
   under xvfb/llvmpipe (`/srv/ssd1/workspace/composegl-wt/logs/issue210-lwjgl343-spike.log`,
   `spike: PASS - the textured quad's known pixels are in the read-back`). The module imports
   `lwjgl-bom` so Kool's other LWJGL modules move too. Comment 5708339917.
2. After a `raw` block Kool's own state goes back; what the block left is not kept. Kool's
   cache is private and cannot be told to forget (KorGE's frontend uses `ag.startFrame()`),
   and everything a block draws is behind Kool's back. Consequence for the Udea editor: a
   scene block starts in Kool's state (reversed depth where clip control exists) and sets the
   depth comparison it wants. Comment 5708616693.
3. Pointer only; no key/text/pad translation, do-nothing clipboard etc. Comment 5708616913.

**Other changes outside the module.**
- `buildSrc/RendererConfinementCheck.kt`: the binding file may implement `Gl` by delegation
  (regex `:\s*Gl\s*(\{|by\s)`), so `confineRenderer("KoolGl.kt")` applies. Run the control:
  `checkRendererConfinement` still passes on every other backend in the JVM leg log
  (`composegl-gdx`, `-korge`, `-lwjgl3`, `-webgl`: "renderer confined").
- Root `build.gradle.kts`: `composegl-kool` added to `published`. **Quota note**: that makes
  every future release bigger, per `CLAUDE.md`'s arithmetic, whose "15 modules / 322 files"
  line I did not touch (the skill updates it at release time).
- `gradle/libs.versions.toml`: `kool = "0.19.0"`, `kool-core`, `lwjgl-bom`.
- `.github/workflows/ci.yml`: `:composegl-kool:jvmTest :composegl-demo-kool:test` in the
  OpenGL job's Xvfb step. No `ci-legs.json` change needed: `ci-covers-build.sh` passes
  ("Every task of ./gradlew build is claimed by a leg.", log `issue210-covers.log`).
- Wiki: new `Kool.md` (+ `images/kool-demo-clicked.png`), `Backends.md`, `Scene-view.md`
  (frontends table + Kool paragraph), `Home.md`; `README.md` module table and run list.
  `bash docs/wiki/push.sh` is for after master.

**Test-only copies**: `KoolDepthScene` is the raw-OpenGL frontend's test `DepthScene` on
`KoolGl` (KorGE's test module carries the same copy); `KoolApp` borrows the Udea #200 spike's
GLFW-on-X11 start.

**Not exercised here**: Kool on Wayland, macOS or Windows; a HiDPI window (Kool scales pointer
positions on macOS/Wayland only; untested); Kool's `asyncSceneUpdate = false`; multi-touch on
a real device (unit-tested as values only); a window resize.

---

## 3. Builds

**Baseline** (fresh `origin/master` 6af1c116, before any change), the CI `jvm` leg's task list
with `--continue --max-workers=2`: `BUILD SUCCESSFUL in 2m 39s`, no failed task
(`/srv/ssd1/workspace/composegl-wt/logs/issue210-baseline-jvm.log`). The native and wasm legs
were not run on this box (iOS cannot build on Linux; the wasm leg is CI's).

**After** (same task list, `sh -c` so the list splits): `BUILD SUCCESSFUL in 1m 56s` then, on
the trimmed code, `BUILD SUCCESSFUL in 8s` (`issue210-after-jvm.log`, `issue210-after-jvm2.log`).
The new modules' tasks ran in it:
```
> Task :composegl-kool:jvmTest
> Task :composegl-kool:allTests
> Task :composegl-kool:checkNoRendererOfItsOwn
> Task :composegl-kool:checkRendererConfinement
composegl-kool: renderer confined to composegl-render (7 files checked)
```
(non-contiguous lines of `issue210-after-jvm.log`, 729–736). With no `DISPLAY` those GL tests
skip there; only `KoolPointerInputTest` runs for real in that leg.

**xvfb GL run**: the evidence command above, output in section 1.

**Tasks this ticket turns green** (new): `:composegl-kool:jvmTest`, `:composegl-kool:check`
(incl. `checkNoRendererOfItsOwn`, `checkRendererConfinement`), `:composegl-demo-kool:test`,
`:composegl-demo-kool:check`, and both modules' `build`.

**CI on the branch**: run **35182397923** (PR #232): success, section 3a.

### 3a. CI result

Run 35182397923 on 1086a586, conclusion **success**, every job (from
`gh run view 35182397923 --json conclusion,jobs`):

```
success
OpenGL (Xvfb + llvmpipe): success
Work out the legs: success
The legs still cover the whole build: success
WebAssembly and WebGL in a real browser: success
Kotlin/Native: Linux and both iOS targets: success
JVM, Android and the architecture checks: success
```

In the OpenGL job's log (saved at
`/srv/ssd1/workspace/composegl-wt/logs/issue210-ci-35182397923.log`)
the Xvfb step's command includes `:composegl-kool:jvmTest :composegl-demo-kool:test`, both tasks
execute, and Kool's own `Starting GLFW render loop` is printed under `KoolSceneViewTest` and under
`DemoPointerTest`: Kool only starts after each test's display assumption passed, so those ran
rather than skipped. The CI log does not print per-test counts (reports are kept only on failure),
so the 35-test count is from the local run in section 1.

---

## 4. Images

In `/srv/ssd1/workspace/Udea/build/debug-screenshots/`:

- `issue210-kool-shared-scenes.png` — all 20 shared scenes as drawn inside a Kool frame, text
  included; each matched the LWJGL3 golden (criterion 1).
- `issue210-kool-demo-1-before.png` — the demo at rest: Kool's cubes, ComposeGL panel, scene
  view green, "Clicked 0 times".
- `issue210-kool-demo-2-hover.png` — Kool's mouse over the button (via Kool's GLFW cursor
  callback): button drawn hovered.
- `issue210-kool-demo-3-pressed.png` — left button down: button drawn pressed, no click yet.
- `issue210-kool-demo-4-clicked.png` — released: "Clicked once", scene view red, cubes unchanged.
- `issue210-kool-demo-sequence.png` — the four above tiled, in order.

All from the evidence run on 1086a586 (`composegl-demo-kool/build/demo-shots/`, and
`COMPOSEGL_KOOL_SHOTS` for the scenes).

---

## 5. Acceptance criteria

1. **`composegl-kool` (jvm) passes the shared backend scenes; text-free scenes match the
   LWJGL3 goldens.** `KoolScreenshotTest`: all 20 scenes, text ones too, against
   `composegl-lwjgl3/src/test/resources/goldens` with `updatable = false`; 20/20, skipped 0.
   Image `issue210-kool-shared-scenes.png`. Red under m3 and m2.
2. **SceneView through the Kool frontend (`HostState.Restore`, `maxSceneSize` borrowing the
   frame's context), proved like c61c2a30.** `KoolSceneViewTest`, 6/6: GPU pixels read inside
   the panel, nothing red outside it; `raw` handed a `KoolFrame` with the picture (not Kool's
   framebuffer) bound, right way up; nearer triangle wins both orders; **a widget, a label and
   a Kool mesh drawn after a careless scene are pixel-identical to no scene** (and on the next frame
   the colour mask is open, the cull mode is not the careless `FRONT_AND_BACK` and the depth func
   is not the careless `NEVER`); a click invalidates the scene and a
   quiet frame does not redraw; `maxSceneSize` off-thread answers without GL, on the render
   thread equals `GL_MAX_TEXTURE_SIZE`. Red under m1, m2, m4.
3. **A Kool desktop demo draws a ComposeGL screen with working pointer input
   (screenshots).** `composegl-demo-kool` (`./gradlew :composegl-demo-kool:run`);
   `DemoPointerTest` drives Kool's own GLFW callbacks: hover changes the button, press+release
   counts exactly one click, the scene view turns red, Kool's world pixel unchanged. Images
   `issue210-kool-demo-*`. Red under m5. Translator unit tests `KoolPointerInputTest` 8/8, red
   under m6.
4. **Published as a snapshot Udea can depend on.** Not done by me: the lead publishes after
   merge (`gh workflow run release.yml -f kind=snapshot`). Ready: `composegl-kool` is in
   `published`, POM generates as `composegl-kool-jvm:0.7.0-SNAPSHOT` with description,
   licence and developer. Udea coordinates: `dev.wildware.composegl:composegl-kool:0.7.0-SNAPSHOT`.

---

## 6. For #222 (Wasm, Android)

- Binding and glyphs: same shape from the platform frontends — `composegl-webgl`'s `WebGl`
  and `WebFonts` for wasm. Android has no plain GLES `Gl` binding in the repository today (the one
  that runs there is `composegl-gdx`'s `GdxGl`, over `Gdx.gl`), so Android needs one.
  `KoolGl`/`KoolState`/`KoolTexture` use LWJGL or `LoadedTextureGl` on the JVM; they move to
  `jvmMain` vs per-platform actuals. On wasm, Kool's `GlState` also caches, so `KoolState` is
  needed there too (as WebGL calls).
- `KoolState.save()` uses `glGet*`, which is a synchronous round trip on WebGL — cost to check.
- Kool's browser pointer positions and HiDPI scale need their own check (the desktop callback
  scales only on macOS/Wayland).
- `ComposeGlScene`'s thread hand-off assumes Kool's async update; the browser is single
  threaded, so the queue is harmless but unnecessary.
- The `KoolApp` harness is JVM/GLFW; a browser harness is new work (see `composegl-webgl`'s
  browser tests).
- Keys, text, pads, clipboard, cursor shapes (decision 3) are still open on every target.
