d8f2da9a

# BRIEF — Udea #222, composegl-kool on Android (and why not the browser)

Repository `wildware-uk/composegl`, branch `issue-222-composegl-kool-wasm-android`, worktree
`/srv/ssd1/workspace/composegl-wt/kool-222`, one commit on `origin/master` 1086a586. Draft PR
**composegl#233** exists only so CI runs; land squashed and rebased, not through the PR.

**Headline:** Android is done and tested on a device. The browser half is out of this ticket: Kool
0.19.0 publishes no WebAssembly build, and the lead moved the Wasm frontend, the Kool Wasm demo and the
WebGL 1 task to a new issue. Transcript proving the blocker in section 2.

---

## 1. Evidence command

Needs the emulator this ticket booted (`composegl_kool_222`, `emulator-5584`; boot it with
`$ANDROID_HOME/emulator/emulator -avd composegl_kool_222 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -port 5584`):

```
cd /srv/ssd1/workspace/composegl-wt/kool-222 && xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem ANDROID_SERIAL=emulator-5584 sh gradlew :composegl-kool:jvmTest :composegl-demo-kool:test :composegl-kool:connectedAndroidDeviceTest :composegl-kool:checkRendererConfinement :composegl-kool:checkNoRendererOfItsOwn --rerun-tasks --max-workers=2
```

No wasm browser tests are in it because there is no wasm target to test (section 2). Check
`skipped="0"` in `composegl-kool/build/test-results/jvmTest/*.xml`,
`composegl-demo-kool/build/test-results/test/*.xml` and
`composegl-kool/build/outputs/androidTest-results/connected/androidMain/*.xml`.
Android runs **instrumented tests on an emulator** (API 35, x86_64, google_apis, SwiftShader GLES) — not
host tests and not a phone.

Last run on d8f2da9a (log `/srv/ssd1/workspace/composegl-wt/logs/issue222-evidence-final.log`, `EXIT=0`, `BUILD SUCCESSFUL in 51s`;
summary `/srv/ssd1/workspace/composegl-wt/logs/issue222-evidence-final-summary.txt`, spliced from the XML):

```
<testsuite name="KoolScreenshotTest[jvm]" tests="20" skipped="0" failures="0" errors="0"
<testsuite name="dev.wildware.composegl.kool.demo.DemoPointerTest" tests="1" skipped="0" failures="0" errors="0"
<testsuite name="KoolPointerInputTest[jvm]" tests="8" skipped="0" failures="0" errors="0"
<testsuite name="KoolSceneViewTest[jvm]" tests="6" skipped="0" failures="0" errors="0"
<testsuite name="dev.wildware.composegl.kool.KoolAndroidScreenshotTest" tests="1" failures="0" errors="0" skipped="0"
<testsuite name="dev.wildware.composegl.kool.KoolAndroidTest" tests="5" failures="0" errors="0" skipped="0"
```
and `composegl-kool: renderer confined to composegl-render (11 files checked)`.

### It goes red when the feature is reverted

Nine mutations. m1–m8 by `/srv/ssd1/workspace/composegl-wt/logs/issue222-mutations/run.py` on the pre-squash commit 6887b1bf
(whose code differs from d8f2da9a only in KDoc text, in `ComposeGlScene` and `KoolDevice`, and two
`KoolDevice` members made private; the rest of the difference is wiki and README —
`git diff 6887b1bf d8f2da9a`): it applies one, saves `git diff`, runs the evidence tasks **with `--continue`**
(so every task reports), reads the XML, and `git checkout`s the file. m9 was run by hand the same way,
Android tests only. Every log ends `EXIT=1`. Diffs and failure lists below are spliced from
`m*.diff` / `m*.failures` in that folder (XML entities unescaped, failure text cut at 200 characters by
the script; suites with no failures dropped).

**m1 — shared canvas hands Kool's state back with `HostState.Leave` instead of `Restore`**
```diff
diff --git a/composegl-kool/src/jvmAndAndroidMain/kotlin/dev/wildware/composegl/kool/KoolCanvas.kt b/composegl-kool/src/jvmAndAndroidMain/kotlin/dev/wildware/composegl/kool/KoolCanvas.kt
index f2294c53..65d30068 100644
--- a/composegl-kool/src/jvmAndAndroidMain/kotlin/dev/wildware/composegl/kool/KoolCanvas.kt
+++ b/composegl-kool/src/jvmAndAndroidMain/kotlin/dev/wildware/composegl/kool/KoolCanvas.kt
@@ -40,7 +40,7 @@ class KoolFrame(val ctx: KoolContext, val projection: FloatArray, val viewport:
  *
  * @param fonts where text is measured, and where solid colour is sampled from.
  */
-class KoolCanvas(fonts: AtlasFonts? = null) : RenderCanvas(GlDevice(KoolGl, HostState.Restore), fonts, KoolTexture.Resolver) {
+class KoolCanvas(fonts: AtlasFonts? = null) : RenderCanvas(GlDevice(KoolGl, HostState.Leave), fonts, KoolTexture.Resolver) {
 
     /**
      * Yes: Kool's OpenGL backend is OpenGL 3.3 core or later on the desktop and OpenGL ES 3 on Android,
```
```
EXIT=1
TASK FAILED :composegl-kool:connectedAndroidDeviceTest
TASK FAILED :composegl-kool:jvmTest
SUITE KoolSceneViewTest[jvm] tests 6 skipped 0 failures 1
  FAILED Kool's own drawing and a widget after a careless scene come out as they do with no scene()[jvm] :: org.opentest4j.AssertionFailedError: Kool's square, with no scene ==> expected: <16711935> but was: <0>
SUITE dev.wildware.composegl.kool.KoolAndroidTest tests 5 skipped 0 failures 2
  FAILED a_ComposeGL_screen_is_drawn_inside_Kools_frame_over_what_Kool_drew_before_it :: java.lang.AssertionError: Kool's square, drawn before the screen and not covered by it expected:<16711935> but was:<0>
  FAILED Kools_own_drawing_and_a_widget_after_a_careless_scene_come_out_as_they_do_with_no_scene :: java.lang.AssertionError: Kool's square, with no scene expected:<16711935> but was:<0>
```

**m2 — no Kool depth func / cull face / line width put back after `raw`**
```diff
diff --git a/composegl-kool/src/jvmAndAndroidMain/kotlin/dev/wildware/composegl/kool/KoolState.kt b/composegl-kool/src/jvmAndAndroidMain/kotlin/dev/wildware/composegl/kool/KoolState.kt
index 536ab0bb..0b653650 100644
--- a/composegl-kool/src/jvmAndAndroidMain/kotlin/dev/wildware/composegl/kool/KoolState.kt
+++ b/composegl-kool/src/jvmAndAndroidMain/kotlin/dev/wildware/composegl/kool/KoolState.kt
@@ -12,9 +12,6 @@ package dev.wildware.composegl.kool
 internal class KoolState private constructor(private val depthFunc: Int, private val cullFace: Int, private val lineWidth: Float) {
 
     fun restore() {
-        ContextGl.depthFunc(depthFunc)
-        ContextGl.cullFace(cullFace)
-        ContextGl.lineWidth(lineWidth)
     }
 
     companion object {
```
```
EXIT=1
TASK FAILED :composegl-kool:connectedAndroidDeviceTest
TASK FAILED :composegl-kool:jvmTest
SUITE KoolSceneViewTest[jvm] tests 6 skipped 0 failures 2
  FAILED Kool's own drawing and a widget after a careless scene come out as they do with no scene()[jvm] :: org.opentest4j.AssertionFailedError: Kool's square, with no scene ==> expected: <16711935> but was: <0>
  FAILED the nearer triangle wins inside a scene view whichever order they are drawn in()[jvm] :: org.opentest4j.AssertionFailedError: the far triangle was drawn first ==> expected: <16711680> but was: <65280>
SUITE dev.wildware.composegl.kool.KoolAndroidTest tests 5 skipped 0 failures 1
  FAILED Kools_own_drawing_and_a_widget_after_a_careless_scene_come_out_as_they_do_with_no_scene :: java.lang.AssertionError: Kool's square, with no scene expected:<16711935> but was:<0>
```

**m3 — Android binding uploads a vertex buffer with the element count as the byte count**
```diff
diff --git a/composegl-kool/src/androidMain/kotlin/dev/wildware/composegl/kool/ContextGl.kt b/composegl-kool/src/androidMain/kotlin/dev/wildware/composegl/kool/ContextGl.kt
index 40b5c177..00a8aa83 100644
--- a/composegl-kool/src/androidMain/kotlin/dev/wildware/composegl/kool/ContextGl.kt
+++ b/composegl-kool/src/androidMain/kotlin/dev/wildware/composegl/kool/ContextGl.kt
@@ -91,7 +91,7 @@ internal actual object ContextGl : Gl {
     override fun bindBuffer(target: Int, buffer: Int) = GLES20.glBindBuffer(target, buffer)
     override fun deleteBuffer(buffer: Int) = GLES20.glDeleteBuffers(1, intArrayOf(buffer), 0)
     override fun bufferData(target: Int, data: GlFloats, count: Int, usage: Int) =
-        GLES20.glBufferData(target, count * Float.SIZE_BYTES, rewound((data as Floats).buffer), usage)
+        GLES20.glBufferData(target, count, rewound((data as Floats).buffer), usage)
     override fun bufferData(target: Int, data: GlShorts, count: Int, usage: Int) =
         GLES20.glBufferData(target, count * Short.SIZE_BYTES, rewound((data as Shorts).buffer), usage)
     override fun enableVertexAttribArray(index: Int) = GLES20.glEnableVertexAttribArray(index)
```
```
EXIT=1
TASK FAILED :composegl-kool:connectedAndroidDeviceTest
SUITE dev.wildware.composegl.kool.KoolAndroidScreenshotTest tests 1 skipped 0 failures 1
SUITE dev.wildware.composegl.kool.KoolAndroidTest tests 5 skipped 0 failures 3
  FAILED the_scenes_without_text_match_the_raw_OpenGL_frontends_goldens :: java.lang.AssertionError: particles: 1.424% of pixels differ by more than 20, mean difference 1.11
  FAILED a_finger_on_Kools_view_clicks_what_it_lands_on :: java.lang.AssertionError: expected:<16711680> but was:<0>
  FAILED a_ComposeGL_screen_is_drawn_inside_Kools_frame_over_what_Kool_drew_before_it :: java.lang.AssertionError: the label was drawn with Android's glyphs: 0 white pixels
  FAILED Kools_own_drawing_and_a_widget_after_a_careless_scene_come_out_as_they_do_with_no_scene :: java.lang.AssertionError: the careless scene did clear its panel expected:<16711680> but was:<0>
```

**m4 — Android glyphs rasterised with no ink**
```diff
diff --git a/composegl-kool/src/androidMain/kotlin/dev/wildware/composegl/kool/AndroidFonts.kt b/composegl-kool/src/androidMain/kotlin/dev/wildware/composegl/kool/AndroidFonts.kt
index bd762249..ec68be1a 100644
--- a/composegl-kool/src/androidMain/kotlin/dev/wildware/composegl/kool/AndroidFonts.kt
+++ b/composegl-kool/src/androidMain/kotlin/dev/wildware/composegl/kool/AndroidFonts.kt
@@ -84,7 +84,6 @@ internal class PaintRasteriser : GlyphRasteriser {
             val height = bounds.height() + Padding * 2
             val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
             try {
-                Canvas(bitmap).drawText(text, (Padding - bounds.left).toFloat(), (Padding - bounds.top).toFloat(), paint)
                 into.resize(width, height, GlyphKind.Coverage)
                 val coverage = ByteBuffer.allocate(bitmap.rowBytes * height)
                 bitmap.copyPixelsToBuffer(coverage)
```
```
EXIT=1
TASK FAILED :composegl-kool:connectedAndroidDeviceTest
SUITE dev.wildware.composegl.kool.KoolAndroidTest tests 5 skipped 0 failures 2
  FAILED a_ComposeGL_screen_is_drawn_inside_Kools_frame_over_what_Kool_drew_before_it :: java.lang.AssertionError: the label was drawn with Android's glyphs: 0 white pixels
  FAILED Kools_own_drawing_and_a_widget_after_a_careless_scene_come_out_as_they_do_with_no_scene :: java.lang.AssertionError: the label was drawn at all: 0 white pixels
```

**m5 — the screen never joins Kool's input stack**
```diff
diff --git a/composegl-kool/src/jvmAndAndroidMain/kotlin/dev/wildware/composegl/kool/ComposeGlScene.kt b/composegl-kool/src/jvmAndAndroidMain/kotlin/dev/wildware/composegl/kool/ComposeGlScene.kt
index 245a5d57..65bc14a8 100644
--- a/composegl-kool/src/jvmAndAndroidMain/kotlin/dev/wildware/composegl/kool/ComposeGlScene.kt
+++ b/composegl-kool/src/jvmAndAndroidMain/kotlin/dev/wildware/composegl/kool/ComposeGlScene.kt
@@ -138,7 +138,6 @@ class ComposeGlScene(
 
     init {
         scene.mainRenderPass.defaultView.onSetupView { render() }
-        InputStack.pushTop(input)
     }
 
     fun setContent(content: @Composable () -> Unit) {
```
```
EXIT=1
TASK FAILED :composegl-demo-kool:test
TASK FAILED :composegl-kool:connectedAndroidDeviceTest
SUITE dev.wildware.composegl.kool.demo.DemoPointerTest tests 1 skipped 0 failures 1
  FAILED the mouse hovers and clicks a ComposeGL button over a Kool world() :: org.opentest4j.AssertionFailedError: the button is drawn hovered once Kool's mouse is over it ==> expected: not equal but was: <-14011582>
SUITE dev.wildware.composegl.kool.KoolAndroidTest tests 5 skipped 0 failures 1
  FAILED a_finger_on_Kools_view_clicks_what_it_lands_on :: java.lang.AssertionError: one tap, one click expected:<1> but was:<0>
```

**m6 — Android binding says no context is ever current**
```diff
diff --git a/composegl-kool/src/androidMain/kotlin/dev/wildware/composegl/kool/ContextGl.kt b/composegl-kool/src/androidMain/kotlin/dev/wildware/composegl/kool/ContextGl.kt
index 40b5c177..262bf885 100644
--- a/composegl-kool/src/androidMain/kotlin/dev/wildware/composegl/kool/ContextGl.kt
+++ b/composegl-kool/src/androidMain/kotlin/dev/wildware/composegl/kool/ContextGl.kt
@@ -22,7 +22,7 @@ import java.nio.ShortBuffer
  */
 internal actual object ContextGl : Gl {
 
-    actual val current: Boolean get() = EGL14.eglGetCurrentContext() != EGL14.EGL_NO_CONTEXT
+    actual val current: Boolean get() = false
 
     actual fun depthFunc(func: Int) = GLES20.glDepthFunc(func)
 
```
```
EXIT=1
TASK FAILED :composegl-kool:connectedAndroidDeviceTest
SUITE dev.wildware.composegl.kool.KoolAndroidTest tests 5 skipped 0 failures 1
  FAILED it_renders_scenes_and_asks_the_driver_for_their_size_only_where_Kools_context_is :: java.lang.AssertionError: on Kool's render thread it is the driver's own limit expected:<8192> but was:<2147483647>
```

**m7 — control: a draw call in an Android file outside the binding**
```diff
diff --git a/composegl-kool/src/androidMain/kotlin/dev/wildware/composegl/kool/AndroidFonts.kt b/composegl-kool/src/androidMain/kotlin/dev/wildware/composegl/kool/AndroidFonts.kt
index bd762249..dd53b011 100644
--- a/composegl-kool/src/androidMain/kotlin/dev/wildware/composegl/kool/AndroidFonts.kt
+++ b/composegl-kool/src/androidMain/kotlin/dev/wildware/composegl/kool/AndroidFonts.kt
@@ -99,6 +99,8 @@ internal class PaintRasteriser : GlyphRasteriser {
         }
     }
 
+    private fun drawBehindTheRenderersBack() = android.opengl.GLES20.glDrawArrays(0, 0, 3)
+
     private companion object {
         const val Padding = 1
     }
```
```
EXIT=1
TASK FAILED :composegl-kool:checkRendererConfinement
```

**m8 — control: a Kool mesh referenced from Android classes**
```diff
diff --git a/composegl-kool/src/androidMain/kotlin/dev/wildware/composegl/kool/AndroidFonts.kt b/composegl-kool/src/androidMain/kotlin/dev/wildware/composegl/kool/AndroidFonts.kt
index bd762249..6d94c469 100644
--- a/composegl-kool/src/androidMain/kotlin/dev/wildware/composegl/kool/AndroidFonts.kt
+++ b/composegl-kool/src/androidMain/kotlin/dev/wildware/composegl/kool/AndroidFonts.kt
@@ -99,6 +99,8 @@ internal class PaintRasteriser : GlyphRasteriser {
         }
     }
 
+    private val meshOfItsOwn = de.fabmax.kool.scene.Mesh::class
+
     private companion object {
         const val Padding = 1
     }
```
```
EXIT=1
TASK FAILED :composegl-kool:checkNoRendererOfItsOwn
```

m7's log: `1 renderer offence(s) in composegl-kool:` / `AndroidFonts.kt:102 calls glDrawArrays outside ContextGl.kt`. m8's log: `dev.wildware.composegl.kool.PaintRasteriser references de.fabmax.kool.scene.Mesh` — i.e. `checkNoRendererOfItsOwn` now reads the Android classes too (it read only the JVM ones before).

**m9 — every pointer lands on the box (Android tests only, `connectedAndroidDeviceTest`)**
```diff
diff --git a/composegl-kool/src/jvmAndAndroidMain/kotlin/dev/wildware/composegl/kool/KoolPointerInput.kt b/composegl-kool/src/jvmAndAndroidMain/kotlin/dev/wildware/composegl/kool/KoolPointerInput.kt
index c3ff5fdc..2c599199 100644
--- a/composegl-kool/src/jvmAndAndroidMain/kotlin/dev/wildware/composegl/kool/KoolPointerInput.kt
+++ b/composegl-kool/src/jvmAndAndroidMain/kotlin/dev/wildware/composegl/kool/KoolPointerInput.kt
@@ -58,7 +58,7 @@ internal class KoolPointerInput(
             val id = if (pointer.id == PointerInput.MOUSE_POINTER_ID) PointerId.Mouse else PointerId(1L + pointer.id)
             val type = if (id == PointerId.Mouse) PointerType.Mouse else PointerType.Touch
             present += id
-            val at = viewport().toDesign(Offset(pointer.x, pointer.y))
+            val at = viewport().toDesign(Offset(100f, 100f))
             val last = seen[id]
             val seenNow = last ?: Seen(at).also { seen[id] = it }
             if (last == null || last.at != at) {
```
```
<testsuite name="dev.wildware.composegl.kool.KoolAndroidScreenshotTest" tests="1" failures="0"
<testsuite name="dev.wildware.composegl.kool.KoolAndroidTest" tests="5" failures="1"
dev.wildware.composegl.kool.KoolAndroidTest > a_finger_that_misses_clicks_nothing[composegl_kool_222(AVD) - 15] [31mFAILED [0m
	java.lang.AssertionError: expected:<0> but was:<1>
```

---

## 2. The browser: stopped, with the transcript

Kool 0.19.0's Gradle module (`kool-core-0.19.0.module`, saved at
`/srv/ssd1/workspace/composegl-wt/logs/issue222-kool-core-0.19.0.module`) has variants for
`androidJvm` (debug, release), `jvm` (desktop), `js` (Kotlin/JS IR) and `common` metadata — no `wasm`.
Kool's `main` branch has a `wasmJs` target (version `0.20.0-SNAPSHOT`), but no snapshot of it is on
central.sonatype.com, oss.sonatype.org or s01.oss.sonatype.org (all three queried; none has
`de/fabmax/kool/kool-core/maven-metadata.xml`).

Probe: `wasmJs { browser() }` plus `wasmJsMain.dependencies { api(libs.kool.core) }` added to
composegl-kool (diff `/srv/ssd1/workspace/composegl-wt/logs/issue222-wasm-probe.diff`; the diff does not
show the one-line untracked `probe/Probe.kt` it also had), then
`sh gradlew :composegl-kool:compileKotlinWasmJs --max-workers=2`. Spliced from
`/srv/ssd1/workspace/composegl-wt/logs/issue222-wasm-probe.log`, lines 87–93, 124–125, and its last three lines:

```
* What went wrong:
Could not determine the dependencies of task ':composegl-kool:compileKotlinWasmJs'.
> Could not resolve all dependencies for configuration ':composegl-kool:wasmJsCompileClasspath'.
   > Could not resolve de.fabmax.kool:kool-core:0.19.0.
     Required by:
         project ':composegl-kool'
      > No matching variant of de.fabmax.kool:kool-core:0.19.0 was found. The consumer was configured to find a library for use during 'kotlin-api', preferably optimized for non-jvm, as well as attribute 'org.jetbrains.kotlin.klib.packaging' with value 'non-packed', attribute 'org.jetbrains.kotlin.platform.type' with value 'wasm', attribute 'org.jetbrains.kotlin.wasm.target' with value 'js' but:
[...]
          - Variant 'jsApiElements-published' declares a library for use during 'kotlin-api', preferably optimized for non-jvm:
              - Incompatible because this component declares a component, as well as attribute 'org.jetbrains.kotlin.platform.type' with value 'js' and the consumer needed a component, as well as attribute 'org.jetbrains.kotlin.platform.type' with value 'wasm'
[...]
BUILD FAILED in 5s
3 actionable tasks: 3 up-to-date
EXIT=1
```

The probe was reverted before any other change. So: no `wasmJs` target, no WebGL 1/2 task, no karma
config, no Kool Wasm demo module, no `ci-legs.json` line. The wiki says so (`Kool.md` limits,
`Backends.md`). This also bears on the Udea spec's D1 (udea-render on Kool for Wasm), flagged to the
lead; no Udea document was changed.

---

## 3. Summary

**What.** `composegl-kool` now targets `jvm` and `android` (AGP's
`com.android.kotlin.multiplatform.library`, compileSdk 36, minSdk 26).

- `jvmAndAndroidMain` (moved from `jvmMain`, unchanged in behaviour): `ComposeGlScene`, `KoolCanvas`,
  `KoolBackend`, `KoolTexture`, `KoolPointerInput`, `KoolGl` (the delete-later wrapper, now
  `Gl by ContextGl`), `KoolState` (now through `ContextGl` instead of `GL11`).
- `ContextGl` — `internal expect object ContextGl : Gl` plus `current`, `depthFunc`, `cullFace`,
  `lineWidth`, `currentLineWidth`. Desktop actual: `Gl by LwjglGl`, GLFW/GL11. Android actual: a
  one-line-a-call binding on `android.opengl.GLES20/GLES30`, `current` from `EGL14`. The module passes
  `-Xexpect-actual-classes` (warnings are errors here).
- `AndroidFonts` (public) — `AtlasFonts` with a `Paint` rasteriser, `register(family, Typeface, sizes)`;
  glyphs made on first use, like `WebFonts`.
- `KoolBackend(fonts: AtlasFonts)` and `KoolCanvas(fonts: AtlasFonts?)` — were `StbFonts`.
- Build: `checkRendererConfinement` binding file is now `ContextGl.kt`; `checkNoRendererOfItsOwn` reads
  the Android classes too. Catalog: `android-kotlin-multiplatform-library` plugin, androidx.test runner/core
  1.7.0, ext junit 1.3.0.
- Tests: `androidDeviceTest` — `KoolDevice` (a real `KoolContextAndroid` in a test activity, the Android
  twin of `KoolApp`), `KoolAndroidTest` (5), `KoolAndroidScreenshotTest` (1: the 8 text-free scenes against
  composegl-lwjgl3's goldens, same list the WebGL test uses).
- CI: new `android` job in `ci.yml` (reactivecircus/android-emulator-runner, API 35 x86_64).
- Wiki: `Kool.md` (Android section, browser limit), `Backends.md`, `Home.md`; `README.md` module row.

**Decisions** (each commented on Udea #222):
1. Wasm stopped — comment 5709461068.
2. Own `android.opengl` binding and `AndroidFonts` inside composegl-kool: composegl-android has no GL
   binding and no fonts; Kool's `GlImpl` lacks calls; StbFonts is LWJGL. Rejected: AndroidFonts in
   composegl-android (plain Android library, not on the renderer's classpath) — 5709750906.
3. Shared source set, `KoolBackend.fonts` widened to `AtlasFonts` — 5709751064.
4. Device tests + emulator CI job; the test activity overrides `finish()` because the runner finishes
   activities after every test and Kool has one context per process — 5709751204.
5. Finding: Kool 0.19.0 reported no press for a finger lifted after one Kool frame (probe log
   `/srv/ssd1/workspace/composegl-wt/logs/issue222-kool-quick-tap-probe.txt`: `mask=0 ev=0` in all three
   frames logged). With two frames held it pressed and ComposeGL clicked; the test holds two — 5709751334.

**Test-only copies**, because the two test source sets cannot share (JUnit 5 + LWJGL vs JUnit 4 +
`android.opengl`): `KoolDevice` follows `KoolApp`; `bevelTexture()` and the careless `raw` block follow
`KoolScreenshotTest`/`KoolSceneViewTest`.

**Not exercised**: a real phone; ES 3.1+/Mali/Adreno drivers; an activity pause/resume or rotation with
Kool (Kool's surface is recreated); multi-touch on Android; AndroidFonts fallback families and colour
emoji (glyphs are coverage only); API levels below 35.

---

## 4. Builds and tests

- **Baseline** (fresh `origin/master` 1086a586, before any change):
  `sh gradlew :composegl-kool:build :composegl-demo-kool:build --continue --max-workers=2`, no display
  (so the GL tests skipped): `BUILD SUCCESSFUL`, `EXIT=0`, no failed task
  (`/srv/ssd1/workspace/composegl-wt/logs/issue222-baseline.log`).
- **Same command on d8f2da9a**: `BUILD SUCCESSFUL in 6s`, `EXIT=0`
  (`/srv/ssd1/workspace/composegl-wt/logs/issue222-build-final.log`; incremental — the same modules with
  `--rerun-tasks` under xvfb passed on the pre-docs tree, `issue222-jvm-build.log`).
- **Real GL, desktop and Android**: the evidence run in section 1 — jvm 34 + demo 1 + Android 6 tests,
  every suite `skipped="0"`.
- **CI coverage guard** locally, `.github/scripts/ci-covers-build.sh`
  (`/srv/ssd1/workspace/composegl-wt/logs/issue222-covers.log`):
  `./gradlew build wants 1054 tasks` / `Every task of ./gradlew build is claimed by a leg.` / `EXIT=0`.
- `:composegl-kool:allMetadataJar` (native leg): `EXIT=0` (`issue222-metadata.log`).
- Native and wasm legs were not run on this box beyond that; iOS cannot build on Linux.

## 5. Publication (AC 3, local part)

`sh gradlew :composegl-kool:publishToMavenLocal -Dmaven.repo.local=/srv/ssd1/workspace/composegl-wt/logs/issue222-m2`
(a scratch repository, not `~/.m2`): `EXIT=0`, tasks `publishAndroidPublicationToMavenLocal`,
`publishJvmPublicationToMavenLocal`, `publishKotlinMultiplatformPublicationToMavenLocal`. Written:
`composegl-kool`, `composegl-kool-jvm`, `composegl-kool-android` (an `.aar`), all `0.7.0-SNAPSHOT`.
The root module's variants: `metadataApiElements`, `androidApiElements-published` → `composegl-kool-android`,
`jvmApiElements-published` → `composegl-kool-jvm` (plus runtime/sources of each). `composegl-kool` was
already in `published`. **Two targets, not three** — wasmJs is section 2.

**Quota note** (`CLAUDE.md`'s release arithmetic): a release now carries one more publication for this
module, `composegl-kool-android` (aar, sources, javadoc, module, pom, each with its signature and
checksums). I did not edit `CLAUDE.md`'s file count.

## 6. Images

In `/srv/ssd1/workspace/Udea/build/debug-screenshots/`, all from `connectedAndroidDeviceTest` on the
emulator (the run after the probe was removed, before the squash; pulled from the test app's files with
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`), 400×400 = Kool's whole framebuffer:

- `issue222-android-kool-screen.png` — ComposeGL red box and "Kool" label (Android glyphs) drawn inside
  Kool's frame, beside Kool's own magenta square. Proves drawing inside Kool on Android.
- `issue222-android-tap-before.png` / `issue222-android-tap-after.png` — the box red, then blue after a
  finger delivered through Kool's touch listener. Proves pointer input.
- `issue222-android-tap-sequence.png` — those two side by side.
- `issue222-android-careless-scene.png` — a SceneView whose `raw` block left GL state wrecked; the green
  box, "After" label and Kool's square still draw. Proves the state hand-back on GLES.
- `issue222-android-scene-nine-patch.png` — one shared scene as drawn on Android (matched the desktop golden).

No browser images: there is no Kool Wasm demo (section 2).

## 7. Acceptance criteria

Scope as ruled by the lead after the blocker: #222 ships jvm + android; the Wasm frontend, the Kool
Wasm demo and the WebGL 1 task move to a new issue the lead is filing.

- **AC 1 — builds and passes its tests, with CI leg coverage (jvm + android).** Section 1 evidence run
  (41 tests, `skipped="0"`), section 4 builds, section 8 CI: the jvm leg builds the Android target, the
  new `android` job runs the device tests (`Finished 6 tests`), the OpenGL job runs the desktop ones.
- **AC 2 — Kool Wasm demo: moved to the new issue.** Proof it cannot be built on Kool 0.19.0 is the probe
  transcript in section 2, spliced from `/srv/ssd1/workspace/composegl-wt/logs/issue222-wasm-probe.log`.
  The probe was reverted before any other change and is not in the branch
  (`git show --stat d8f2da9a` names no wasm or probe file).
- **AC 3 — published as a snapshot Udea can depend on (jvm + android).** Section 5: local publications
  `composegl-kool`, `composegl-kool-jvm`, `composegl-kool-android` at `0.7.0-SNAPSHOT`; the module is in
  `published`. The snapshot release itself is the lead's.

## 8. CI

Draft PR https://github.com/wildware-uk/composegl/pull/233 on d8f2da9a.
Run https://github.com/wildware-uk/composegl/actions/runs/35189228543 — every job `pass`, spliced from
`gh pr checks 233`:

```
JVM, Android and the architecture checks	pass	6m6s
Kool on an Android emulator	pass	5m53s
Kotlin/Native: Linux and both iOS targets	pass	4m32s
OpenGL (Xvfb + llvmpipe)	pass	5m10s
The legs still cover the whole build	pass	1m22s
WebAssembly and WebGL in a real browser	pass	6m18s
Work out the legs	pass	5s
```
(URL columns elided.) The emulator job ran the tests, not zero of them — from its log
(`/srv/ssd1/workspace/composegl-wt/logs/issue222-ci-android-job.log`): `Starting 6 tests on emulator-5554 - 15`,
`Finished 6 tests on emulator-5554 - 15`, `BUILD SUCCESSFUL in 4m`.

The local emulator was shut down after the run; section 1 says how to boot it again.
