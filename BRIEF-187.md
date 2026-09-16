a363c36

# Issue #187 — `UiLayer` and `UiScreen` on ComposeGL

Branch `issue-187-composegl-ui-layer`, off `origin/example` at `38612a4`.
Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-ad50cbdd4b0807eec`.

> **On the filename.** The contract I was given says `BRIEF.md` in the worktree root. `4f075c4`
> is on `origin/example` and removed the committed root copy on purpose, and every brief on
> `example` from #154 onwards is `BRIEF-<N>.md`. So this is `BRIEF-187.md`, the same decision
> `BRIEF-186.md` made and for the same reason. If the lead wants the other name, `git mv`.

**This is the second handover.** The first (tip `3c51bb4`) was built against a ComposeGL snapshot.
ComposeGL 0.6.0 was then released on Central, the lead stopped the review before it produced a
verdict, and `a363c36` swaps the pin to the release. Everything below is re-run on `a363c36`
unless it says otherwise; section 2 has the version story.

**On the SHA.** The runs were made on `dd5a131`. I then amended that commit's *message* only,
because it said 0.6.0 was released "while this branch was in review", and the release (11:44 UTC)
predates the first handover. `git rev-parse dd5a131^{tree}` and `git rev-parse a363c36^{tree}`
both print `6e2c80c4b4887db17031b09a9ffe410dc43a23d8` in this worktree (`dd5a131` is reachable
only through its reflog now), so every run below is a run of `a363c36`'s tree, and that is what
this brief calls it.

The SHA above is the last commit of the change. The commit that updates this file sits on top of
it and contains nothing but this file, the convention `BRIEF-186.md` and `BRIEF-166.md` followed:

```
a363c36 ComposeGL 0.6.0 from Maven Central, and the snapshot repository goes
3c51bb4 BRIEF-187: the ComposeGL seam, and the four wrong comments my own pass found
0d04db0 Four comments that were true-sounding and wrong, found by my own review
c2369c0 The GL test compared two unsettled frames, so its main assertion passed on nothing
865e6b7 Two tests that could not fail, and the reason both could not
13ed255 UiLayer and UiScreen drive a ComposeGL composition, not a scene2d Stage
```

The only other thing in `git status` is ` M gradlew`, the executable bit this box needs on the
wrapper, deliberately not committed.

Evidence files referenced below live in `/srv/ssd1/workspace/Udea/build/issue187-evidence/` — the
main checkout, not this worktree's `build/`, so they survive a `clean`. Files numbered 30 and up
are runs on `a363c36` against 0.6.0. Files 19, 24, 25, 26 and 26b are fetches and listings of the
published ComposeGL artifacts, not runs of this tree. Every other lower-numbered file is the first
handover's, against the snapshot, and is cited only where the text says so.

---

## 1. The evidence command

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew :udea-render:test :udea-render:udeaGlTest \
    -Pudea.render.requireGl=true --rerun-tasks --console=plain
```

`--rerun-tasks` because both tasks are up-to-date-checked and a cached pass proves nothing.
`-Pudea.render.requireGl=true` because without it the GL half **skips** and stays green, which is
the trap this whole ticket lives in.

### It is green on `a363c36`, against 0.6.0

Run after everything else in this handover, including the `moba` re-shoot, so nothing ran after it
to overwrite its XMLs or PNGs. `48-evidence-green-0.6.0-last.txt`, last four lines:

```
BUILD SUCCESSFUL in 14s
37 actionable tasks: 37 executed
Configuration cache entry reused.
EXIT=0
```

Its XMLs were copied out the moment it finished, to `evidence-xml-0.6.0-last/`, and counted from there
(`48-evidence-xml-totals-0.6.0.txt`):

```
test: tests=198 failures+errors=0 skipped=0
udeaGlTest: tests=21 failures+errors=0 skipped=0
```

`skipped=0` on `udeaGlTest` is the load-bearing half: it says the GL tests *ran*.

### It goes red when the feature is reverted — proven against 0.6.0, not carried over

The one line that makes `UiLayer` draw, replaced by the call that only settles and lays out: it
still compiles, still composes, still advances the clock, and draws nothing. Literal diff, from
that run (`45-evidence-red-0.6.0.diff`):

```diff
diff --git a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
index 82fdb9d..9598a79 100644
--- a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
+++ b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
@@ -15,6 +15,7 @@ import dev.wildware.composegl.ui.focus.FocusManager
 import dev.wildware.composegl.ui.geometry.Size
 import dev.wildware.composegl.ui.host.UiHost
 import dev.wildware.composegl.ui.host.UiRenderer
+import dev.wildware.composegl.ui.host.settle
 import dev.wildware.composegl.ui.input.KeyRouter
 import dev.wildware.composegl.ui.input.PointerRouter
 import dev.wildware.composegl.ui.layout.Viewport
@@ -229,7 +230,7 @@ public class UiLayer internal constructor(
         // the canvas's frame, draw and close it. The canvas draws into whatever framebuffer is
         // bound, which inside a `RenderPipeline` frame is the offscreen target every capture is
         // read from, and it clears nothing -- the world is already there.
-        renderer.render(viewport, clockNanos)
+        host.settle(viewport, focus, clockNanos)
     }
 
     /**
```

The exact command above, run on `a363c36` with that diff applied. `45-evidence-red-0.6.0.txt`,
lines 101–118, one contiguous run:

```
> Task :udea-render:udeaGlTest

ComposeUiGlTest > a composed ComposeGL screen is drawn over the world into the captured frame() FAILED
    org.opentest4j.AssertionFailedError at ComposeUiGlTest.kt:124

ComposeUiGlTest > a click on the composed button recomposes the frame a capture reads() FAILED
    org.opentest4j.AssertionFailedError at ComposeUiGlTest.kt:177

> Task :udea-render:test FAILED

UiLayerTest > the composed tree is drawn into the backend's canvas every frame() FAILED
    org.opentest4j.AssertionFailedError at UiLayerTest.kt:187

198 tests completed, 1 failed

> Task :udea-render:udeaGlTest FAILED

21 tests completed, 2 failed
```

and lines 144–147, the end of the file:

```
BUILD FAILED in 15s
37 actionable tasks: 37 executed
Configuration cache entry reused.
EXIT=1
```

(The first handover proved the same command red against the snapshot, with the GL failures at
lines 122/175 of the test. They are at 124/177 now because `0d04db0` added two KDoc lines to that
file. Nothing else about the failure moved.)

Reverted with `git checkout --`; `git status --short` was ` M gradlew` only afterwards, and the
green run above was taken after that, last.

---

## 2. What I did

### The shape

`UiLayer` was a scene2d `Stage` driven once per frame with a clamped delta. It is now a ComposeGL
`UiHost` + `UiRenderer` driven once per frame with a clamped delta. The four behaviours the old
tests pinned are all still pinned, by tests that I watched fail (section 6 has the table):

- **one act per frame** → one `UiRenderer.render` per `RenderSystem.render`, asserted by
  composition count over sixty frames and by canvas frame count;
- **a clamped delta** → `frameSeconds.coerceAtMost(MAX_UI_SECONDS)` converted to nanos and
  accumulated into the toolkit's `BroadcastFrameClock`;
- **the screen's lifecycle** → `show` mounts, `hide` unmounts *and* disposes, a second `show`
  replaces rather than stacks, `dispose` is reached through `RenderResources.own`;
- **UI consumes input first** → `layer.input` is an `InputMultiplexer` of ComposeGL's pointer and
  key adapters, installed ahead of `GdxKeyboard`.

`UiScreen` went from `build(stage: Stage): Actor` to `@Composable fun content()`. It takes no size
argument on purpose: a composition lays itself out against the viewport, and a parameter would be
a number a screen could cache and then be wrong about after a resize.

### The scene2d layer is still there, renamed

`Scene2dUiLayer` / `Scene2dUiScreen` in `udea-render/.../ui/scene2d/` are the old classes, moved
verbatim, with `MobaHud` switched to the new names and nothing else changed about it. Their tests
moved with them (`Scene2dUiLayerTest`, 6 tests). That is decision 4 of the four I was handed: #188
takes the last caller away and #189 deletes them. Their KDoc says "nothing new goes here" and
names both issues.

Three tests did **not** get duplicated into the scene2d copy — the pipeline resize tests (`a
window resize reaches the screen target and every Resizable`, `a resize leaves the offscreen
target alone so two captures stay comparable`, `a minimised window reports zero and is ignored
rather than dividing by it`). They are about `RenderPipeline`, not about either UI layer, so they
live once, in `UiLayerTest`. Keeping a copy in both would have been the reject list's
"copy-pasted logic that differs only in a constant".

### Getting a frame onto a real GL surface — the part #186 could not

Two things were missing and both are one line each:

1. **`gdx-freetype-platform:natives-desktop`, `runtimeOnly` on `udea-render`.** `composegl-gdx`'s
   POM brings `gdx-freetype` — the Java binding — and no natives, so `GdxFonts.registerTrueType`
   compiles and then dies the first time anything asks for a glyph. Mutation **M7** removes this
   line and the GL test fails with `Couldn't load shared library 'libgdx-freetype64.so' for
   target: Linux, x86, 64-bit`.
2. **A font to rasterise.** `udea-render/src/test/resources/fonts/DejaVuSans.ttf`, 759720 bytes,
   md5 `18756952572508cb0948b3a884128a0f` — byte-identical to this box's
   `/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf` and to ComposeGL's own test copy at
   `/srv/ssd1/workspace/composegl-wt/render-gdx/composegl-gdx/src/test/resources/fonts/DejaVuSans.ttf`,
   which is how I know the toolkit's own harness rasterises the same bytes. Bitstream
   Vera / DejaVu licence, both permissive for redistribution; the Debian `fonts-dejavu-core`
   copyright file is checked in beside it as `DejaVuSans-LICENSE.txt`.

`GdxCanvas`'s other requirement — a live `Batch` — is satisfied by `RenderResources.batch`, the
one the pipeline already owns, so there is no second batch and no second glyph atlas.

### The ComposeGL version: `0.6.0` from Maven Central

**Resolved: `dev.wildware.composegl:*:0.6.0`, a release, from Central proper.** No snapshot and no
snapshot repository. The sha1s are `sha1sum` over the jars in the Gradle cache the build resolved
into, shown here by filename rather than by their full cache paths, and they match what the lead
checked independently on repo1.maven.org:

```
6139735e44bb17f76b2a8e8178af537f67557213  composegl-gdx-0.6.0.jar
cb63a8df4e46f11f28c4a972e0aee46e467122e8  composegl-render-jvm-0.6.0.jar
278489f5fa09f6e9f2d9fa0ca2dfa16bd4b51a94  composegl-ui-jvm-0.6.0.jar
```

Release date from Central's own metadata (`19-composegl-central-metadata.xml`):
`<lastUpdated>20260916115441</lastUpdated>`, and the jar's `last-modified: Wed, 16 Sep 2026
11:44:49 GMT`. That is today, while the first handover was being written; its later runs (files
22 and 23) postdate the release, but they resolved the snapshot because that is what the pin
named.

The pin is one line. The whole diff of the two build files that carried the snapshot, against
`origin/example`, saved as `49-pin-diff-0.6.0.txt` and pasted here entire:

```
$ git diff origin/example..HEAD -- gradle/libs.versions.toml build.gradle.kts
```
```diff
diff --git a/gradle/libs.versions.toml b/gradle/libs.versions.toml
index 44d87f0..653184e 100644
--- a/gradle/libs.versions.toml
+++ b/gradle/libs.versions.toml
@@ -53,7 +53,7 @@ junitPlatform = "1.13.4"
 # published release declares `kotlin-stdlib:2.4.20` and its jars carry `@Metadata(mv = [2, 4, 0])`,
 # which is why `kotlin` above had to move to 2.4.20 before any of this could be on the classpath
 # at all. `composegl-gdx` is a GL backend, so it is `udea-render`-only by UDEA-MG-002.
-composegl = "0.5.0"
+composegl = "0.6.0"
 
 [libraries]
 junit = { group = "junit", name = "junit", version.ref = "junit" }
@@ -83,6 +83,11 @@ gdx-backend-lwjgl3 = { module = "com.badlogicgames.gdx:gdx-backend-lwjgl3", vers
 # UDEA-MG-002 bans `com.badlogicgames.gdx:*-platform` from every headless module for exactly
 # that reason.
 gdx-platform = { module = "com.badlogicgames.gdx:gdx-platform", version.ref = "gdx" }
+# FreeType's desktop natives. `composegl-gdx` brings the Java binding (`gdx-freetype`)
+# transitively and not this, so a game that registers a `.ttf` through `GdxFonts` needs it
+# selected here; `udea-render/build.gradle.kts` says why at the call site. A `*-platform`
+# artifact, so UDEA-MG-002 keeps it out of every headless module.
+gdx-freetype-platform = { module = "com.badlogicgames.gdx:gdx-freetype-platform", version.ref = "gdx" }
 # Box2D. `gdx-box2d` is the Java binding and is headless in the sense UDEA-MG-002 cares about -
 # it names no GL type - but it is useless without `gdx-box2d-platform`, which is a
 # `*-platform` native loader and therefore banned from every designated headless module. Both are
```

The first hunk is the version. The second is the `gdx-freetype-platform` catalogue entry, unchanged
from the first handover. `build.gradle.kts` does not appear at all: the root build script is
byte-identical to `origin/example`, so the two `oss.sonatype.org` snapshot repositories that have
been there since `19403f3` are exactly as they were.

**`UiLayer` needed no change.** `:udea-render:compileKotlin` and `:udea-render:compileTestKotlin`
executed against 0.6.0 and passed (`30-compile-against-0.6.0.txt`; `:moba:compileKotlin` was
`UP-TO-DATE` in that run, since `moba` cannot see ComposeGL through `udea-render`'s
`implementation` edge, and it executed and passed in the cold build, `41-clean-build-0.6.0.txt`).
Worth saying how I know, because the obvious shortcut failed: I first checked each ComposeGL import this branch uses
against the release jar listings, and it reported nine "missing" — `Box`, `Column`, `Button`,
`Panel`, `Text` and the four `Provide*`. Run against the snapshot this branch already compiled
against, the same probe reported the same nine (`26-imports-vs-0.6.0-release.txt`, and the
control `26b-imports-probe-CONTROL-vs-snapshot.txt`). Those are Compose composables — capitalised *functions* living in
`*Kt.class` files — and my probe classified them as classes. A listing cannot answer the question;
only compiling did. None of 0.6.0's new widgets is used here; they are #188's.

#### What 0.6.0 newly brings: `gdx-controllers-core:2.2.3`

From `:udea-render:dependencies --configuration testRuntimeClasspath`
(`31-render-testRuntime-deps-0.6.0.txt`), filtered with
`grep -E "composegl|gdx-freetype|gdx-controllers|com.badlogicgames.gdx:gdx:" | sort -u`:

```
|    +--- com.badlogicgames.gdx-controllers:gdx-controllers-core:2.2.3
+--- com.badlogicgames.gdx:gdx:1.14.2
|    +--- com.badlogicgames.gdx:gdx:1.14.2 (*)
|    |    \--- com.badlogicgames.gdx:gdx:1.14.2 (*)
|    |    \--- com.badlogicgames.gdx:gdx:1.9.11 -> 1.14.2 (*)
|    +--- com.badlogicgames.gdx:gdx-freetype:1.14.2
+--- com.badlogicgames.gdx:gdx-freetype-platform:1.14.2
+--- dev.wildware.composegl:composegl-gdx:0.6.0
|    +--- dev.wildware.composegl:composegl-render:0.6.0
|    |    \--- dev.wildware.composegl:composegl-render-jvm:0.6.0
+--- dev.wildware.composegl:composegl-ui:0.6.0
|    +--- dev.wildware.composegl:composegl-ui:0.6.0 (*)
|    |         +--- dev.wildware.composegl:composegl-ui:0.6.0 (*)
|    \--- dev.wildware.composegl:composegl-ui-jvm:0.6.0
```

Two things in that. `gdx-controllers-core` asks for `gdx:1.9.11` and Gradle takes it to `1.14.2`,
so there is still one gdx core on the classpath and no skew against the natives. And `gdx` itself
is still `1.14.2`, so the catalogue version `determinism-allowlist.txt` pins is unchanged.

**Against the module graph.** `UDEA-MG-002`'s banned patterns are `com.badlogicgames.gdx:gdx-backend-lwjgl3`,
`org.lwjgl:*` and `com.badlogicgames.gdx:*-platform`. `gdx-controllers-core`'s group is
`com.badlogicgames.gdx-controllers`, which **none of them matches** — so `udeaVerifyModuleGraph`
being green is not evidence about this dependency at all, and I did not use it as such. Instead I
resolved the dependency tree of every designated headless project plus `udea-render` and `moba`
(`32-all-deps-0.6.0.txt`) and parsed it per project and configuration (`33-gdx-controllers-census.txt`):

```
projects parsed: :moba, :udea-agent, :udea-annotations, :udea-assets, :udea-assets-compiler, :udea-audio, :udea-codegen, :udea-compiler-plugin, :udea-core, :udea-diagnostics, :udea-gas, :udea-gradle, :udea-net, :udea-render, :udea-replay
projects whose dependency trees name gdx-controllers:
  :moba  in: agentRuntimeClasspath, runtimeClasspath, testRuntimeClasspath
  :udea-render  in: compileClasspath, runtimeClasspath, testCompileClasspath, testRuntimeClasspath
```

The thirteen `HEADLESS_PROJECTS` are all parsed and none of them names it. The census is not
passing by construction: it finds the two projects that do have it, and a raw
`grep -c gdx-controllers-core` over the same file gives 7, which is those 4 + 3 configurations.
`udea-render`'s `compileClasspath` carries it because `composegl-gdx` exposes it at compile scope;
`moba` gets it at runtime only, through `udea-render`. Neither is a headless module.

**Against the determinism allowlist.** `udeaVerifyDeterminism`'s `ALLOW005` stamps the versions of
the catalogue aliases listed in `PINNED_ALIASES` against `determinism-allowlist.txt`, whose pins
are `@version fleks 2.14` and `@version gdx 1.14.2`. Neither moved, so `ALLOW005` has nothing to
say, and the cold build ran the gate rather than taking it from cache (`> Task
:udeaVerifyDeterminism`, no `UP-TO-DATE`, in `41-clean-build-0.6.0.txt`). `gdx-controllers-core` is
not a catalogue alias and not pinned, so that gate does not examine it — and correctly: the gate
scans the declared simulation scopes' own bytecode, `:udea-render` is deliberately not one of them,
and `git grep -ln controllers` over `udea-core`, `udea-gas`, `udea-net`, `moba`, `udea-assets` and
`udea-agent` sources returns nothing. No game code reads a controller.

**The `gdx-freetype` natives line is still needed on 0.6.0.** The lead's guess was yes; I ran M7
against 0.6.0 rather than take the guess. The release POM brings `gdx-freetype:1.14.2`, the Java
binding (visible in the tree above), and still not the `natives-desktop` classifier. With the line
removed, `ComposeUiGlTest` fails both tests with
`SharedLibraryLoadRuntimeException: Couldn't load shared library 'libgdx-freetype64.so' for target:
Linux, x86, 64-bit` — section 7, M7.

#### How this ticket got here: the snapshot, briefly

The first handover of this branch (tip `3c51bb4`, SHA of the change `0d04db0`) was built against
`0.6.0-SNAPSHOT`, resolved as `0.6.0-20260915.202706-1` from
`https://central.sonatype.com/repository/maven-snapshots`, because 0.6.0 was not yet on Central.
That snapshot turned out byte-identical to 0.5.0 (`94444e31…`, `7b0d81a6…`, `6fd0d8a9…` for
`ui-jvm`, `gdx`, `render-jvm`), so it had none of the features the owner asked for 0.6.0 for.
0.6.0 itself was released at 11:44 UTC, a little before that handover went out; I noticed it only
afterwards, when the dashboard showed the owner pushing the ComposeGL release. The lead stopped the
review, which had produced no verdict, and had me swap to the release: `a363c36`. Against the snapshot the
`ui-jvm` listing gains 115 entries and loses 96 (`25-composegl-0.6.0-listing-diff.txt`); among the
additions are `DebugOverlay`, `KeyShortcut`, `BarMenu`, `ContextMenu`, `CellWindow`,
`CollapsingHeader`, `ColourPicker` and `DividerDrag`, which is #188's business. The correction is on
the issue: <https://github.com/wildware-uk/Udea/issues/187#issuecomment-5697156157>.

No stale-artifact problem was ever hit, so no `changing = true` or cache setting was ever added,
and with a release pin there is nothing left that could need one.

### Decisions I had to make, and what to change if the owner disagrees

**No `KeyNavigator` and no `GamepadNavigator` wired into `UiLayer`.** ComposeGL ships both, and
wiring them is two lines. I did not. A navigator makes the arrow keys move focus and Escape mean
"back" *for as long as a layer is mounted*, which is screen policy, not an engine default — in a
MOBA, arrows are a camera and Escape is a menu. A game that wants keyboard navigation constructs
one over `layer.focus`. **If the owner disagrees**, add them in `UiLayer`'s `init` and put the two
navigators at the head of the `InputMultiplexer`; nothing else has to move. Commented on the
issue.

**`MAX_UI_SECONDS` is 1/30, tighter than the pipeline's `MAX_FRAME_SECONDS` of 0.25.** That is
what the scene2d layer already used (`MAX_ACT_SECONDS`), so this is a port and not a new number.
Kept because a quarter-second step through a UI animation is visible as a jump, and the pipeline's
clamp exists to protect the *simulation*, not the interface.

**The primary constructor is `internal`.** `UiBackend` is a ComposeGL type, and an `implementation`
dependency must not surface in `udea-render`'s public API. Games use the `public constructor(
resources, frameTime, font: FileHandle, fontSizes)` that builds a `GdxBackend`; the tests use the
internal one with a `HeadlessBackend`. That is what lets `UiLayerTest` run with no GL context at
all.

**The clock is accumulated in `UiLayer`.** `AGENTS.md` bans an accumulated `SimClock.time` because
it drifts. This is not that clock: it is presentation-only, never snapshotted, never read by a
system, and it exists because a `BroadcastFrameClock` wants a monotonically increasing nanosecond
value rather than a delta. The class KDoc says so at the field.

---

## 3. `sh gradlew build`, and the xvfb GL run

### The cold full build on `a363c36`, against 0.6.0, no exclusions

`clean build --no-build-cache --no-daemon --console=plain`. `41-clean-build-0.6.0.txt`, its last
five lines:

```
BUILD SUCCESSFUL in 2m 8s
234 actionable tasks: 217 executed, 17 up-to-date
Configuration cache entry stored.
EXIT=0
loadavg at end: 15.43 10.07 6.61 2/1610 2009747
```

I ran it cold on purpose. The warm `sh gradlew build` just before it (`40-full-build-0.6.0.txt`,
`BUILD SUCCESSFUL in 1m 5s`, `213 actionable tasks: 11 executed, 202 up-to-date`, `EXIT=0`)
executed 11 tasks. After a dependency version moves, that is too little to be evidence about the
suite.

Summed over every `TEST-*.xml` in the tree straight after the cold build
(`42-clean-build-0.6.0-xml-totals.txt`):

```
files=390 tests=2597 failures+errors=0 skipped=37
  skipped: dev.wildware.udea.agent.host.gl.OffscreenRenderToolsTest 7
  skipped: dev.wildware.udea.agent.host.gl.OverlayCaptureIsolationTest 1
  skipped: dev.wildware.udea.assets.compiler.atlas.RealArtAtlasPackerTest 7
  skipped: dev.wildware.udea.assets.compiler.pack.RealArtReproducibilityTest 2
  skipped: dev.wildware.udea.render.gl.ComposeUiGlTest 2
  skipped: dev.wildware.udea.render.gl.GlCaptureDeterminismTest 4
  skipped: dev.wildware.udea.render.gl.GlCaptureTest 5
  skipped: dev.wildware.udea.render.gl.GlOverlayIsolationTest 1
  skipped: dev.wildware.udea.render.gl.GlThreadShutdownTest 1
  skipped: dev.wildware.udea.render.gl.OffscreenBackendTest 7
```

**Read that skip list, because it is the trap stated as a measurement.** `$DISPLAY` is empty on
this box, so every class in `dev.wildware.udea.render.gl` and `dev.wildware.udea.agent.host.gl`
skipped — `ComposeUiGlTest` among them, both of its tests — and the build was green anyway. A green
`sh gradlew build` says nothing at all about the GL half of this ticket, which is why section 1's
evidence command is the xvfb one. The two real-art classes are unrelated to this ticket and skip on
`origin/example` too.

The gates that matter here executed in that build rather than coming from cache — none of these
lines carries `UP-TO-DATE`. `grep -nE` over `41-clean-build-0.6.0.txt` for each task name:

```
77:> Task :udeaVerifyContracts
78:> Task :udeaVerifyAgentsMd
137:> Task :udeaLegacyReport
186:> Task :udeaVerifyModuleGraph
189:> Task :udeaVerifyNoLegacyDependencies
281:> Task :udeaVerifyMigration
575:> Task :udea-codegen:udeaCheckProtocolLock
595:> Task :udea-render:udeaVerifyHeadless
625:> Task :udea-assets-compiler:udeaPackGate
645:> Task :udeaVerifyDeterminism
```

`udeaVerifyContracts` executing and the build passing is the mechanical statement that no
`docs/contracts/` file moved; `udeaCheckProtocolLock` the same for `net-protocol.lock`. Section 2
explains why `udeaVerifyModuleGraph` passing is *not* evidence about `gdx-controllers-core`, and
what is.

### The xvfb GL run on `a363c36`, `requireGl=true`

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true --rerun-tasks --console=plain
```

`43-gl-xvfb-0.6.0.txt`, last four lines:

```
BUILD SUCCESSFUL in 24s
45 actionable tasks: 45 executed
Configuration cache entry stored.
EXIT=0
```

Its XMLs were copied to `gl-xml-0.6.0/` in the same shell command that ran it, before anything
else could overwrite them. Summary (`43-gl-xml-summary-0.6.0.txt`):

```
dev.wildware.udea.agent.host.gl.OffscreenRenderToolsTest: tests=7 failures=0 errors=0 skipped=0 time=1.822
dev.wildware.udea.agent.host.gl.OverlayCaptureIsolationTest: tests=1 failures=0 errors=0 skipped=0 time=0.51
dev.wildware.udea.render.gl.ComposeUiGlTest: tests=2 failures=0 errors=0 skipped=0 time=1.386
dev.wildware.udea.render.gl.GlCaptureDeterminismTest: tests=4 failures=0 errors=0 skipped=0 time=0.929
dev.wildware.udea.render.gl.GlCaptureTest: tests=5 failures=0 errors=0 skipped=0 time=1.205
dev.wildware.udea.render.gl.GlOverlayIsolationTest: tests=1 failures=0 errors=0 skipped=0 time=0.292
dev.wildware.udea.render.gl.GlThreadShutdownTest: tests=1 failures=0 errors=0 skipped=0 time=0.11
dev.wildware.udea.render.gl.OffscreenBackendTest: tests=8 failures=0 errors=0 skipped=0 time=1.62
TOTAL tests=29 failures+errors=0 skipped=0
```

### `moba`, re-shot on 0.6.0

`moba`'s runtime classpath changed — it gains `gdx-controllers-core` through `udea-render` — so I
did not carry the first handover's shots over. `:moba:runMatchShot :moba:runShot` under xvfb,
`47-moba-shots-0.6.0.txt`: `BUILD SUCCESSFUL in 1m 35s`, `EXIT=0`, no `UDEA0032`. The match shots
land on the same ticks with the same scores as the snapshot-era run, for example
`hud.png 1280x720 at tick 561 - the HUD with a cooldown running | alive=24 score orc=2 soldier=8
undead=8`. I looked at `match/hud.png`: score bar, health bar, ability bar with live cooldowns,
event log, selection ring and sprites all present. That is the scene2d HUD, so it proves the
rename and the dependency move did not break the game, not anything about ComposeGL.

`:moba:runUdpProof` I did not run. It is red on `origin/example` and nothing here touches
replication.

### `udeaDaemonBudget`

**It did not run, because it is not on `check` in this tree.**
`udea-assets-compiler/build.gradle.kts` registers it as a standalone `Test` task and wires only
`udeaPackGate` to `check`:

```kotlin
// `udeaPackGate` only. `udeaGraphBudget` is reached through the root's `udeaLatencyBudgets`,
// which the `latency-budgets` CI job runs serially on both runner images (issue #175).
tasks.named("check") {
    dependsOn(udeaPackGate)
}
```

`udeaPackGate` executed and passed (line 625 above). So the latency-budget flakiness I was warned
about could not have affected these builds, and there is nothing to re-run solo.

### Incidents worth recording

In the first handover, the first attempt at the cold build was **killed, not failed** —
`Gradle build daemon has been stopped: stop command received`, at loadavg 24, while `melon-merge`
ran its scenario suite on this shared box. Re-run once the box was quiet, it passed. Nothing about
it was specific to this branch, and nothing like it happened on `a363c36`.

---

## 4. The images

All in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`. The three GL frames are written by
`ComposeUiGlTest` itself, so they are regenerated by section 1's evidence command; these copies
came out of the final green run, and here are the md5s so that can be checked rather than
believed:

```
181818131b12c8d9422f2391c0031c9b  issue187-composegl-ui-gl-frame.png
1727d716fee46841e8321d0dd8888cda  issue187-composegl-ui-gl-clicked.png
88d407da05d00b701a4794a571af00a1  issue187-composegl-ui-gl-frame-unmounted.png
```

Those are the md5s of the copies in the gallery, and the same three come out of
`udea-render/build/reports/udea/compose-ui/` after the final evidence run on `a363c36` against
0.6.0. They are also the md5s the first handover's runs against the snapshot produced: ComposeGL
0.6.0 draws this screen to the identical pixel. I looked at the 0.6.0 frames anyway, and the
collage was rebuilt from them.

| File | What it shows | What it proves |
|---|---|---|
| `issue187-composegl-ui-gl-frame-unmounted.png` | The world alone: one blue quad, no interface | The baseline every other assertion diffs against. Also the control for "the layer draws nothing when nothing is mounted" |
| `issue187-composegl-ui-gl-frame.png` | The same frame with a ComposeGL panel over it: a title, a label reading "clicked 0 times", and a focused button | AC-3. A real LWJGL3 context, a real FreeType-rasterised glyph, drawn into the offscreen target a `FrameCapture` reads. The world shows through around the panel, so the layer composited rather than cleared |
| `issue187-composegl-ui-gl-clicked.png` | The label now reads "clicked 1 times" | A pointer event went through `layer.input`, reached the button's `onClick`, recomposed, and reached a pixel |
| `issue187-composegl-ui-sequence.png` | The three above tiled and labelled | The sequence read as one picture: no interface, interface, interface responding |
| `issue187-moba-hud-live-agent-screenshot.png` | `moba`'s scene2d HUD, captured over the bridge from a live game at tick 2203 | `MobaHud` is unregressed by the `Scene2dUiLayer` rename — decision 4 honoured. This is the *old* layer still working, not the new one. **Taken in the first handover, against the snapshot, and not re-taken.** The re-check on 0.6.0 is `47-moba-shots-0.6.0.txt` and `match/hud.png` in section 3 |

I looked at every one of them rather than only measuring them, and that caught a defect no
assertion would have. An earlier version put the panel's `.padding(20f)` on `Panel` itself, and
`Panel(modifier)` is `Box(modifier.styled(style))` — so the padding landed *outside* the painted
background and the middle label touched both drawn edges. I measured the drawn panel at 322px
wide against a requested 360 — that is prose about a tree that no longer exists, not a transcript,
because the fix changed the frame it was measured from. Fixed by moving the padding to the inner
`Column` and giving `Panel` a `contentAlignment`. **Every pixel-count assertion in the test was
green throughout**, which is the point: no number I had was going to find this, and looking did.

---

## 5. The diff, and the two generated files

`git diff origin/example..HEAD --stat -- . ':!BRIEF-187.md'`:

```
 gradle/libs.versions.toml                          |   7 +-
 moba/src/main/kotlin/dev/wildware/moba/MobaHud.kt  |  14 +-
 udea-render/build.gradle.kts                       |   8 +
 .../kotlin/dev/wildware/udea/render/ui/UiLayer.kt  | 357 ++++++++++++++-----
 .../kotlin/dev/wildware/udea/render/ui/UiScreen.kt |  46 ++-
 .../udea/render/ui/scene2d/Scene2dUiLayer.kt       | 196 ++++++++++
 .../udea/render/ui/scene2d/Scene2dUiScreen.kt      |  44 +++
 .../dev/wildware/udea/render/gl/ComposeUiGlTest.kt | 394 +++++++++++++++++++++
 .../dev/wildware/udea/render/support/HeadlessGl.kt |  35 +-
 .../wildware/udea/render/ui/UiInputOrderTest.kt    | 218 ++++++++++++
 .../dev/wildware/udea/render/ui/UiLayerTest.kt     | 253 +++++++++----
 .../udea/render/ui/scene2d/Scene2dUiLayerTest.kt   | 216 +++++++++++
 .../test/resources/fonts/DejaVuSans-LICENSE.txt    |  78 ++++
 .../src/test/resources/fonts/DejaVuSans.ttf        | Bin 0 -> 759720 bytes
 14 files changed, 1680 insertions(+), 186 deletions(-)
```

`build.gradle.kts` is gone from that list: the root build script is now byte-identical to
`origin/example`. `gradle/libs.versions.toml` is the version line and the `gdx-freetype-platform`
entry, and nothing else.

**`net-protocol.lock`: not touched. `expected-generated-hashes.txt`: not touched. Ids moved by
zero.** This ticket adds no replicated component and no `@Net` field — it is presentation only,
and presentation has no wire representation. Both files are absent from the diffstat above, and
`udeaCheckProtocolLock` executed in the cold build on `a363c36` (line 575 of
`41-clean-build-0.6.0.txt`) and passed.

`docs/contracts/`: not touched, and no contract needed to change. `docs/contracts.lock`: not
touched. `udeaVerifyContracts` green.

`AGENTS.md`: not touched, and correctly so. No module moved, no contract moved, and its module
table still matches `settings.gradle.kts` — `udeaVerifyAgentsMd` green in section 3.

---

## 6. The issue, criterion by criterion

### AC-1 — "`UiLayer` drives a ComposeGL composition per frame instead of a scene2d `Stage`, and `UiLayerTest`'s existing behaviours still hold (or are replaced by equivalents that fail when the clamp or the lifecycle is broken)"

`UiLayerTest`, 11 tests, all in section 1's green run (`tests=11 failures=0 skipped=0`):

| Test | Old behaviour it carries forward | Mutation that reds it |
|---|---|---|
| `sixty frames compose the tree exactly sixty times` | `sixty frames act the stage exactly sixty times` | — (covered by M2's canvas-frame test; see the note below) |
| `a stalled frame advances the toolkit's clock by the clamp and no further` | the clamp | **M1** |
| `a stalled frame is clamped so an animation does not jump to its end state` | `a stalled frame is clamped so an action does not jump to its end state` | **M1** |
| `showing a screen composes its content and hiding removes and disposes it` | `showing a screen mounts its root and hiding removes and disposes it` | **M3** |
| `showing a second screen replaces the first rather than stacking on it` | same name, same behaviour | **M3** |
| `the composed tree is drawn into the backend's canvas every frame` | new — scene2d had no canvas to assert against | **M2** |
| `the layer lays the tree out against the surface it is drawing into` | new | **M6**, **M9** |
| `the layer is disposed by the pipeline rather than by whoever remembered` | same name, same behaviour | **M5** |
| the three `a … resize …` tests | `RenderPipeline` behaviour, kept once | — |

The one old behaviour with no direct equivalent: `the default stage shares the pipeline's batch
instead of constructing a second one`. It still exists, in `Scene2dUiLayerTest`, because it is
about the scene2d `Stage`. Its ComposeGL counterpart is structural rather than testable from
outside: `gdxBackend(...)` takes `resources.batch` and there is no second one to construct. The
GL test is what proves the shared batch actually works, because a `GdxCanvas` on a dead batch
draws nothing and M2/M3 both show what "draws nothing" looks like.

### AC-2 — "Input reaches the composition before `GdxKeyboard`, and a test proves a consumed UI event does not reach the keyboard"

`UiInputOrderTest`, 4 tests. It installs the real chain — `GdxKeyboard.install(layer.input,
keyboard)` — and then drives **what the device was actually handed**
(`checkNotNull(gl?.inputProcessor)`), not the multiplexer the call returned. That distinction is
the test: asserting against the return value would pass even if `install` never set
`Gdx.input.inputProcessor`.

| Test | Asserts |
|---|---|
| `a key the composition consumes never reaches the keyboard` | Escape, consumed by an `.onKeyEvent` on the mounted screen: `keyboard.pressesSince(ESCAPE) == 0` |
| `a key the composition declines still reaches the keyboard` | **the negative control.** W, which the screen declines: `pressesSince(W) == 1`. Without this, the test above would pass on a chain that swallows every key |
| `a click on a button is consumed before the keyboard sees the frame` | pointer path, same order |
| `a click on empty screen is declined so the world still gets it` | the other negative control: a click outside the panel is not consumed |

**M4** reverses the multiplexer order inside `GdxKeyboard.install` and the first test fails with

```
Escape closed a panel AND fired the gameplay binding under it ==> expected: <0> but was: <1>
```

which is the player-visible bug stated as an assertion.

One shape worth naming, because it took a round to find: the key screen is a `fillMaxSize` `Box`
with `.onKeyEvent { it.key == Key.Escape }` *wrapping* a `Button(..., initialFocus = true)`. A key
event starts at the focused node and walks outwards, so with no focused child inside it, the
handler is never reached and the test passes on nothing.

### AC-3 — "`sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true` under xvfb, with a screenshot of composed UI in a real GL frame"

The command, its `BUILD SUCCESSFUL` and its `skipped=0` per-class XML summary are in section 3.
The screenshot is `issue187-composegl-ui-gl-frame.png` in section 4.

`ComposeUiGlTest`, 2 tests, boots a real `Lwjgl3Backend.start(RenderMode.Offscreen,
WindowConfig(640x360), registry)` with a blue-quad `RenderSystem` at `RenderPhase.World` and a
real `UiLayer` at `RenderPhase.UI`, and reads the composed result out of `FrameCapture`.
Everything that touches the composition goes through `backend.onRenderThread { }`.

What the first test asserts, in order — the later ones are there because the pixel count alone
can be satisfied by accident:

1. **the panel was laid out at a non-zero size**, so a later count of zero means "drew nothing"
   rather than "was never asked to draw".
2. **> 1000 pixels differ inside the panel** between the mounted and unmounted frames — the
   interface drew something.
3. **exactly 0 pixels differ in a 64x64 corner the panel does not cover** — the control. It says
   the layer composited over the world instead of clearing or rescaling the surface. **M9**
   (viewport at half design size, so everything draws at 2x) fails it with `3641 pixels changed in
   a 64x64 corner the interface does not cover`.
4. **> 8 distinct colours inside the title's bounding box** — the glyphs are rasterised text, not
   a flat fill. **M8** replaces the `Text` with a coloured `Box` of the same size and it fails with
   `the title's box holds only 2 distinct colours, which is a flat rectangle rather than rasterised
   text: FreeType produced no glyphs`.

The second test clicks the button through `layer.input.touchDown`/`touchUp` and then asserts both
that `onClick` ran (`assertEquals(1, screen.clicks)`) and that more than 20 pixels of the count
label changed. Both, because either alone is weak: the first can pass while nothing redraws, and
the second is what M2 breaks.

### Also delivered against the standing instruction to drive the real game

*This live session was in the first handover, against the snapshot, and was not repeated on
0.6.0.* It exercised the agent tool surface and the scene2d HUD, neither of which this swap
touches; the 0.6.0 check on the running game is the `moba` re-shoot in section 3.

`mcp__game-bridge__launch_instance` fails on this box with the documented one-line `25.0.2` —
the bridge's generated `gamebridge.json` runs `./gradlew`, which picks up the box's default JDK
25, which Gradle 8.13 rejects. So I started the game myself with `JAVA_HOME` set and talked to
port 7841 with `call_tool`:

- `/health` → `{"ok":true,...,"renderMode":"Offscreen",...}`, 51 tools;
- `time.pause`, `render.screenshot`, `input.tap {action: ability_1}`, `time.step {ticks: 30}`
  (tick 2173 → 2203, with combat events in the log);
- `close` → `{"closed":true}`, and I confirmed the process was gone.

That screenshot is `issue187-moba-hud-live-agent-screenshot.png`. Note what it is evidence *of*:
it is the **scene2d** HUD, so it proves decision 4 — the rename did not break the game — and it is
not evidence about the ComposeGL layer. `ComposeUiGlTest`'s three PNGs are that.

The bridge failure is not mine to fix and I did not try; it is the `gradlew`/JDK-25 interaction
that `AGENTS.md`'s operating notes already describe, and fixing `gamebridge.json`'s command line
is a `udea-gradle` change with no relationship to this ticket.

---

## 7. The mutation table

Each row is the literal `git diff` of the mutation, pasted whole from the saved file
`mutations/M<n>.diff` that the run wrote before it ran — `index` lines and context included.
(The first handover printed these with the context and `index` lines trimmed and no elision
marker; that is corrected here, not merely noted.) All ten were run against the snapshot in the
first handover. **M2 and M7 were re-run against 0.6.0 on `a363c36`**, because they are the two
that stand behind the evidence command and the AC-3 blocker, and a mutation proven red against
one library version is not proven red against another. Outputs, diffs and XMLs are all under `issue187-evidence/mutations/`. `unit` means
`:udea-render:test --tests 'dev.wildware.udea.render.ui.*'` (24 tests); `gl` means
`:udea-render:udeaGlTest --tests '...ComposeUiGlTest'` under xvfb with `requireGl=true`.

### M1 — the clamp

```diff
diff --git a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
index 82fdb9d..05169df 100644
--- a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
+++ b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
@@ -293,7 +293,7 @@ public class UiLayer internal constructor(
      * is the second, tighter one. See the class KDoc for why there are two.
      */
     private fun clampedFrameNanos(): Long =
-        (frameTime.frameSeconds.coerceAtMost(MAX_UI_SECONDS) * NANOS_PER_SECOND).toLong()
+        (frameTime.frameSeconds * NANOS_PER_SECOND).toLong()
 
     /**
      * Sizes the UI viewport to the surface being drawn into.
```

`unit`: `24 tests completed, 2 failed`

```
UiLayerTest > a stalled frame is clamped so an animation does not jump to its end state() FAILED
UiLayerTest > a stalled frame advances the toolkit's clock by the clamp and no further() FAILED
```
```
a 40-second frame must advance the toolkit by the clamp, not by 40 seconds ==> expected: <33333336> but was: <40000000000>
a 40-second stall ran a 1000ms linear tween to 1.0; the clamp puts about 33ms of it on the clock, and unclamped it reads 1.0
```

**M1 is why commit `865e6b7` exists, and it is the most useful thing in this ticket.** On the
first pass M1 failed only *one* of those two tests: the animation test could not fail. `UiHost.frame`
calls `Snapshot.sendApplyNotifications()` at the **top** of a frame and only then asks the clock
for one, so a value a tween writes while the clock is being advanced is not visible to a
composition until the frame after. The test read the value from *before* the stall, passed, and
would have passed with the clamp deleted. The fix is one ordinary frame after the stalled one, and
the test says so at the line:

```kotlin
frameTime.frameSeconds = 40f
pipeline.render(0f)
// One ordinary frame after the stall, and it is load-bearing. `UiHost.frame` publishes
// state writes at the *top* of a frame and only then asks the clock for one, so a value
// an animation writes while the clock is being advanced is not visible to a composition
// until the frame after. Assert without this and the test reads the value from before the
// stall, passes, and would pass with the clamp taken out -- which is what it did.
frameTime.frameSeconds = 1f / 60f
pipeline.render(0f)
```

### M2 — the draw

```diff
diff --git a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
index 82fdb9d..9598a79 100644
--- a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
+++ b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
@@ -15,6 +15,7 @@ import dev.wildware.composegl.ui.focus.FocusManager
 import dev.wildware.composegl.ui.geometry.Size
 import dev.wildware.composegl.ui.host.UiHost
 import dev.wildware.composegl.ui.host.UiRenderer
+import dev.wildware.composegl.ui.host.settle
 import dev.wildware.composegl.ui.input.KeyRouter
 import dev.wildware.composegl.ui.input.PointerRouter
 import dev.wildware.composegl.ui.layout.Viewport
@@ -229,7 +230,7 @@ public class UiLayer internal constructor(
         // the canvas's frame, draw and close it. The canvas draws into whatever framebuffer is
         // bound, which inside a `RenderPipeline` frame is the offscreen target every capture is
         // read from, and it clears nothing -- the world is already there.
-        renderer.render(viewport, clockNanos)
+        host.settle(viewport, focus, clockNanos)
     }
 
     /**
```

`unit`: `24 tests completed, 1 failed` — `the composed tree is drawn into the backend's canvas
every frame`, `three pipeline frames must be three canvas frames opened and closed cleanly ==>
expected: <3> but was: <0>`.
`gl`: `2 tests completed, 2 failed` — `only 0 pixels inside the panel differ between the mounted
and the unmounted frame; the interface did not draw`, and `the label counting the clicks reads the
same in both frames, so the recomposition never reached a pixel`.

**Re-run against 0.6.0 on `a363c36`.** The mutation, saved scoped to the one file as
`mutations/M2-060-scoped.diff`, is byte-identical to the diff above. `unit` (`M2-060.out`): `24 tests completed, 1 failed`, the
same test. `gl` (`M2gl-060.out`): `2 tests completed, 2 failed`, the same two, with the same two
messages. Section 1 then runs the *named* evidence command red with it, against 0.6.0.

### M3 — the unmount

```diff
diff --git a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
index 82fdb9d..0b052b6 100644
--- a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
+++ b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
@@ -268,7 +268,6 @@ public class UiLayer internal constructor(
     public fun hide() {
         val current = mounted ?: return
         mounted = null
-        host.setContent {}
         current.dispose()
     }
 
```

`unit`: 1 failed — `showing a screen composes its content and hiding removes and disposes it`,
`the screen's nodes are still in the tree ==> expected: <null> but was: <UiNode(text)>`.
`gl`: 1 failed — `only 0 pixels inside the panel differ between the mounted and the unmounted
frame; the interface did not draw`.

**M3 is why commit `c2369c0` exists.** On the first pass M3 left the GL test's headline assertion
**green**, because the test was comparing the first captured frame after a mount against the
second — and those two differ from each other anyway, by a recompose, a layout pass and a glyph
atlas upload. So the assertion was measuring frame-to-frame churn rather than the presence of an
interface. The fix is three discarded frames before every compared capture:

```kotlin
    private fun settle(slot: dev.wildware.udea.render.capture.FrameCaptureSlot) {
        repeat(FRAMES) { slot.capture(CaptureRequest()) }
    }
```

`FRAMES` is `3`, declared at `ComposeUiGlTest.kt:380`.

`M3gl-fixedtest.out` is the check that the *fixed* test still passes on unmutated code
(`BUILD SUCCESSFUL in 15s`), so the fix is not simply a test that now always fails.

### M4 — the input order

```diff
diff --git a/udea-render/src/main/kotlin/dev/wildware/udea/render/input/GdxKeyboard.kt b/udea-render/src/main/kotlin/dev/wildware/udea/render/input/GdxKeyboard.kt
index ae8f167..0d7732a 100644
--- a/udea-render/src/main/kotlin/dev/wildware/udea/render/input/GdxKeyboard.kt
+++ b/udea-render/src/main/kotlin/dev/wildware/udea/render/input/GdxKeyboard.kt
@@ -120,7 +120,7 @@ public class GdxKeyboard : KeyboardState, InputProcessor {
                 "there is no Gdx.input to install an input chain on; call this on the render " +
                     "thread once the backend has started"
             }
-            val multiplexer = InputMultiplexer(*processors)
+            val multiplexer = InputMultiplexer(*processors.reversedArray())
             input.inputProcessor = multiplexer
             return multiplexer
         }
```

`unit`: 1 failed — `a key the composition consumes never reaches the keyboard`,
`Escape closed a panel AND fired the gameplay binding under it ==> expected: <0> but was: <1>`.

### M5 — the disposal

```diff
diff --git a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
index 82fdb9d..dcf9c4a 100644
--- a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
+++ b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
@@ -215,7 +215,6 @@ public class UiLayer internal constructor(
         // rather than left to the caller because a composition nobody disposes is a Recomposer
         // whose coroutine outlives the window, and a `GdxBackend` nobody disposes is a glyph
         // atlas and a set of GL programs leaked per pipeline.
-        resources.own(this)
         (backend as? Disposable)?.let(resources::own)
     }
 
```

`unit`: 1 failed — `the layer is disposed by the pipeline rather than by whoever remembered`,
`the pipeline did not dispose the UI layer`.

### M6 — the viewport is never sized

```diff
diff --git a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
index 82fdb9d..7585580 100644
--- a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
+++ b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
@@ -308,7 +308,6 @@ public class UiLayer internal constructor(
         val width = target.width.toFloat()
         val height = target.height.toFloat()
         if (viewport.physical.width == width && viewport.physical.height == height) return
-        viewport = Viewport.oneToOne(Size(width, height))
     }
 
     internal companion object {
```

`unit`: 1 failed — `the layer lays the tree out against the surface it is drawing into`,
`expected: <(800.0, 600.0)> but was: <(1.0, 1.0)>`.
`gl`: 2 failed — the panel assertion, and `the click did not reach the button's onClick ==>
expected: <1> but was: <0>` (a 1x1 layout puts the button nowhere the pointer lands).

### M7 — the FreeType natives, in the build file

```diff
diff --git a/udea-render/build.gradle.kts b/udea-render/build.gradle.kts
index 3243038..59800d8 100644
--- a/udea-render/build.gradle.kts
+++ b/udea-render/build.gradle.kts
@@ -41,7 +41,7 @@ dependencies {
     // `FreeType.initFreeType` with an UnsatisfiedLinkError the first time a game asks for a glyph.
     // #186 could not draw a ComposeGL frame at all for exactly this reason; this line is half of
     // what fixed it. Runtime-only because nothing compiles against the natives.
-    runtimeOnly(variantOf(libs.gdx.freetype.platform) { classifier("natives-desktop") })
+    // M7: the freetype natives removed.
 
     // Test-only, deliberately. `udeaVerifyHeadless` reports through the one UdeaDiagnostic
     // (spec 5) so its output has the same rule ids, spans and cap as every other producer;
```

`gl`: `2 tests completed, 2 failed`

```
SharedLibraryLoadRuntimeException: Couldn't load shared library 'libgdx-freetype64.so' for target: Linux, x86, 64-bit
SharedLibraryLoadRuntimeException: Unable to read file for extraction: libgdx-freetype64.so
```

This is the row that makes the AC-3 claim concrete: the blocker #186 hit is real, the one-line fix
is load-bearing, and removing it fails loudly rather than drawing blank text.

**Re-run against 0.6.0 on `a363c36`**, which is what settles whether the line is still needed now
that `composegl-gdx:0.6.0`'s POM brings `gdx-freetype` itself. The mutation (`mutations/M7-060-scoped.diff`)
is byte-identical to the diff above. `gl` (`M7-060.out`, XML `M7-060-ComposeUiGlTest.xml`): `2 tests completed, 2 failed`, with

```
SharedLibraryLoadRuntimeException: Couldn't load shared library 'libgdx-freetype64.so' for target: Linux, x86, 64-bit
SharedLibraryLoadRuntimeException: Unable to read file for extraction: libgdx-freetype64.so
```

So yes: the release brings the Java binding and not the `natives-desktop` classifier, and the line
stays.

### M8 — the glyphs

```diff
diff --git a/udea-render/src/test/kotlin/dev/wildware/udea/render/gl/ComposeUiGlTest.kt b/udea-render/src/test/kotlin/dev/wildware/udea/render/gl/ComposeUiGlTest.kt
index d0d70c0..817e3d5 100644
--- a/udea-render/src/test/kotlin/dev/wildware/udea/render/gl/ComposeUiGlTest.kt
+++ b/udea-render/src/test/kotlin/dev/wildware/udea/render/gl/ComposeUiGlTest.kt
@@ -21,6 +21,8 @@ import dev.wildware.composegl.ui.modifier.Modifier
 import dev.wildware.composegl.ui.modifier.fillMaxSize
 import dev.wildware.composegl.ui.modifier.padding
 import dev.wildware.composegl.ui.modifier.testTag
+import dev.wildware.composegl.ui.modifier.background
+import dev.wildware.composegl.ui.modifier.height
 import dev.wildware.composegl.ui.modifier.width
 import dev.wildware.composegl.ui.widget.Button
 import dev.wildware.composegl.ui.widget.Panel
@@ -318,7 +320,7 @@ class ComposeUiGlTest {
                         verticalArrangement = Arrangement.spacedBy(12f),
                         horizontalAlignment = HorizontalAlignment.Centre,
                     ) {
-                        Text("UDEA ON COMPOSEGL", Modifier.testTag(TITLE))
+                        Box(Modifier.width(220f).height(18f).background(dev.wildware.composegl.ui.graphics.Colour.rgb(0x8899AA)).testTag(TITLE))
                         Text("issue 187 - clicked $clicks times", Modifier.testTag(COUNT))
                         Button("CLICK ME", { clicks++ }, Modifier.testTag(BUTTON))
                     }
```

`gl`: 1 failed — `the title's box holds only 2 distinct colours, which is a flat rectangle rather
than rasterised text: FreeType produced no glyphs`.

A mutation of the *test's own subject* rather than of production code, deliberately: it is the only
way to show that the colour-count assertion distinguishes text from a coloured rectangle of the
same size, which is what a blank-glyph failure would look like.

### M9 — the layer rescales the whole surface

```diff
diff --git a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
index 82fdb9d..82ebefe 100644
--- a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
+++ b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
@@ -308,7 +308,7 @@ public class UiLayer internal constructor(
         val width = target.width.toFloat()
         val height = target.height.toFloat()
         if (viewport.physical.width == width && viewport.physical.height == height) return
-        viewport = Viewport.oneToOne(Size(width, height))
+        viewport = Viewport.oneToOne(Size(width / 2f, height / 2f)).copy(physical = Size(width, height))
     }
 
     internal companion object {
```

`gl`: 1 failed — `3641 pixels changed in a 64x64 corner the interface does not cover, so the layer
is clearing or rescaling the whole surface ==> expected: <0> but was: <3641>`.

### M10 — the control

```diff
diff --git a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
index 82fdb9d..e56c18b 100644
--- a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
+++ b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
@@ -292,6 +292,10 @@ public class UiLayer internal constructor(
      * The pipeline has already clamped [FrameTime.frameSeconds] to its own, looser figure; this
      * is the second, tighter one. See the class KDoc for why there are two.
      */
+    // M10, the control: prose that says the opposite of the code, and changes nothing.
+    // `(frameTime.frameSeconds * NANOS_PER_SECOND).toLong()` -- no coerceAtMost, no clamp, and
+    // `resources.own(this)` is not called either. Everything here must stay green: a check that
+    // failed on a comment would be a check about text rather than about behaviour.
     private fun clampedFrameNanos(): Long =
         (frameTime.frameSeconds.coerceAtMost(MAX_UI_SECONDS) * NANOS_PER_SECOND).toLong()
 
```

`unit`: `BUILD SUCCESSFUL in 19s`. `gl`: `BUILD SUCCESSFUL in 15s`.

A comment that states the opposite of the code, including the literal text M1 and M5 mutate,
changes nothing. So none of the rows above is a check about source text.

---

## 8. My own pass over the diff, as the reviewer will read it

Against `docs/engineering-standards.md` section 8:

- **A rule in section 1 reproduced in new code** — no. The clamp constant is a port of
  `MAX_ACT_SECONDS`, not a second copy: the scene2d one moved with its class and the ComposeGL one
  replaced it.
- **A `public` declaration nobody outside the module uses** — **this is the one real risk and I
  am not going to dress it up.** `UiLayer`, its public `FileHandle` constructor, `input`, `show`,
  `hide`, and `UiScreen.content()` have **no caller outside `udea-render` today**, because #188 is
  the ticket that ports `MobaHud` onto them. That is inherent to #187 being scoped as "the engine
  half" and to decision 4 forbidding me from touching `MobaHud`. What I did do is make the surface
  as small as that allows: the primary constructor is `internal` (so `UiBackend` never appears in
  public API at all), and `host`, `focus`, `viewport`, `frameCount`, `clockNanos` and
  `MAX_UI_SECONDS` are all `internal`. Everything public is either the engine's declared seam or
  an interface member. If the reviewer wants the surface smaller still, the only further move is to
  delete the `FileHandle` constructor and make games construct a `UiBackend` themselves, which
  would put a ComposeGL type in `udea-render`'s public API — worse.
- **A test that cannot fail** — section 7 is the answer, and two of its rows exist because I
  *found* two of mine (M1's animation test, M3's GL headline). Both are fixed, both now fail for
  the right reason, and M10 is the control that says none of them is a source-text check.
- **Generated code produced by string concatenation** — none; this ticket generates nothing.
- **A new field on `GameContext`** — none.
- **Wall-clock or unseeded randomness inside simulation code** — none. Grepped the added lines of
  the whole diff for `System.currentTimeMillis`, `nanoTime`, `Instant.now`, `Math.random`,
  `Random.Default`: no matches. `UiLayer` reads `FrameTime.frameSeconds`, which is
  `udea-render`'s existing presentation seam, and `Gdx.graphics?.backBufferScale`, which is a
  scale factor rather than a clock.
- **A `TODO()`, a stubbed return, or a swallowed exception on a reachable path** — none. Grepped
  the added lines for `TODO()` and `catch (…) { }`: no matches. There is no gamepad stub either,
  because `UiLayer` passes ComposeGL's own `PointerRouter` and `KeyRouter` rather than
  implementing a sink.
- **Copy-pasted logic that differs only in a constant** — the nearest thing is
  `Scene2dUiLayer`, and it is a *move* rather than a duplicate: the original is deleted in the
  same commit, and the two layers are different implementations (a `Stage` and a `UiHost`), not
  one shape parameterised by a constant. The three pipeline resize tests deliberately live once.

Against `AGENTS.md`'s "Do not": no `by net(...)`; no snapshot codec; no setter instrumentation; no
wall clock or unseeded randomness in simulation; nothing new depends on `common`; no reflection on
a per-tick path; no bare `Int`/`Long`/`String` for a domain concept; **no GL outside
`udea-render`** (the ComposeGL and FreeType dependencies are `udea-render`-only, which
`udeaVerifyModuleGraph` and `udeaVerifyHeadless` both checked); `UiLayer` is a `RenderSystem`, not
a Fleks system; no module arrow moved.

Against the four this repository makes blocking: no `docs/contracts/` file changed
(`udeaVerifyContracts` green); the `fieldNames[i]` / FieldMask / FieldStore alignment is untouched
because nothing replicated moved; no `Tick` quantity expressed as seconds — the seconds in this
diff are all `udea-render` frame seconds, which `AGENTS.md` explicitly permits in this module;
`AGENTS.md`'s module table is unchanged and still correct.

### The pass found four things, and they were all the same species

I ran this pass before reporting, and it was not a formality: it produced `0d04db0`. All four
are a sentence a reader would act on, stating something the surrounding code does not support.
None of them would have failed a test, which is exactly why they were still there.

**1. The snapshot comments named the wrong thing to delete.** They said "nothing else in the
build refers to a snapshot"; `build.gradle.kts` has declared two `oss.sonatype.org` snapshot
repositories since the initial commit. The constraint I was given was that somebody who never
read this ticket must be able to do the 0.6.0 swap from the comment alone, and following my
comment they would have deleted three repositories instead of one. `0d04db0` fixed the comments;
`a363c36` then removed them, and the repository line they described, because 0.6.0 is a release
and there is no snapshot left to describe. The two `oss.sonatype.org` lines are untouched.

**2. A carried-forward count that was wrong.** `Scene2dUiLayer`'s disposal KDoc said "the old
tree disposed a stage in three different places and none of them in a `finally`". It came with
the file when I moved it, and it was already wrong: `git grep` over `common/` and `example/`
finds a `Stage` disposed in one place, `common/screen/UIScreen.kt`. I deleted the sentence
rather than correcting the number to one, because a corrected number goes stale the same way,
and put the property in its place — the test that pins the `RenderResources.own` registration.

My first attempt at that fix said "the old tree's scattered `stage.disposeSafely()` calls",
which is a *fresh* wrong claim about multiple sites where I had found one. Caught it before
committing. Worth recording, because it is the failure mode of fixing a count by rewriting it.

**3. Three line-number citations into `common/`, two of them stale.** `stage.act` is at
`UdeaGameManager.kt:237`, cited as `:228`. `UIScreen`'s own clamped act is at `:15`, cited as
`:18`. The third, a range, happened still to be right. All three came with the moved files.
`common` is old tree and still being edited, so a line number in a `udea-render` KDoc rots by
construction; all three now cite file and symbol.

Then I grepped for the class rather than stopping at the instances I had been looking at:

```
grep -rnE "\.kt:[0-9]+" udea-render/src/main/kotlin/dev/wildware/udea/render/ui/ \
    udea-render/src/test/kotlin/dev/wildware/udea/render/ui/ \
    udea-render/src/test/kotlin/dev/wildware/udea/render/gl/ComposeUiGlTest.kt
```

It prints no lines and exits with status 1, which is grep's "no match". (The first handover showed
this with an output line reading `(none left)`. grep prints no such line; that was my shell's
`|| echo`, typed into the block as though it were output. Corrected here.)

**4. Two exhaustiveness claims the text under them did not support.** "kept under a name that
says so for exactly one reason", sitting above two reasons; and a heading, "Why none of the
other tests can stand in for this one", over a body that argues only about `UiLayerTest`. Both
now say what they actually argue.

I re-ran both the full build and the evidence command on `0d04db0` rather than reasoning that
comments cannot break anything. Those runs are files 20–23; sections 1 and 3 now quote the
later runs on `a363c36`.

### The second pass, on `a363c36`, found more of the same

Re-reading for the swap turned up these. Every one is the same species as the four above, and
two of them had already shipped in the first handover.

- **The mutation diffs in section 7 were not literal.** The first handover printed them with the
  `index` lines and the diff context trimmed, and no elision marker, even though the whole point
  of the table is that a row can be reproduced from its diff. All ten are now pasted whole from
  `mutations/M<n>.diff`.
- **A block of grep "output" that grep never printed**: the `(none left)` line above.
- **A probe that answered wrong, caught by its control before I relied on it.** Checking this
  branch's ComposeGL imports against the 0.6.0 jar listings reported nine missing. Run against the
  snapshot this branch already compiled against, it reported the same nine: Compose composables
  are capitalised functions, and the probe treated them as classes. I used the compile as the
  evidence instead (section 2).
- **A saved artefact that mislabelled its own subject.** That probe's control output was first
  saved with the header "0.6.0 release" although it ran against the snapshot, because the label
  was hard-coded. Regenerated with the listing named, and each listing checked to be what its label
  says (`ContextMenuKt` present in the release listing only).
- **Two claims in my draft of this brief that the transcripts did not support**, both caught before
  publishing: that the compile run "executed both compile tasks" (`:moba:compileKotlin` was
  `UP-TO-DATE` in it), and a load average attributed to the cold build that was measured before
  the warm one.

Every quoted block in sections 1 and 3, and the two parsed blocks in section 2, were then checked
mechanically against their source files, as exact line sequences, by a script with an off-by-one
control that must fail and does.

### What I did not exercise

- **A resize while a screen is mounted, on a real GL surface.** `fitTo` is covered by a unit test
  and by M6/M9 on GL, but the GL test runs at one fixed size throughout. The unit path is the same
  code.
- **A second `show` on GL.** Covered by a unit test (M3), not by a picture.
- **`Headless` mode.** `UiLayer` needs a `Batch`, so a game in `Headless` does not construct one —
  the same arrangement scene2d had. Nothing asserts that, because there is nothing new to assert:
  it is `RenderMode`'s existing contract.
- **A `UiScreen` whose `content()` throws.** No behaviour is defined for it and I did not invent
  one; it propagates out of `render`, like any other render-thread exception.
- **The widget set.** Not this ticket (decision 2). 0.6.0's new widgets are listed in section 2
  and on the issue for #188; none of them is used here.
- **`gdx-controllers-core` at runtime.** It is on the classpath and nothing in this branch calls
  it. I checked where it resolves and that no game code references it; I did not exercise a
  controller.
