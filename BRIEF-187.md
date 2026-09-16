0d04db0

# Issue #187 — `UiLayer` and `UiScreen` on ComposeGL

Branch `issue-187-composegl-ui-layer`, off `origin/example` at `38612a4`.
Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-ad50cbdd4b0807eec`.

> **On the filename.** The contract I was given says `BRIEF.md` in the worktree root. `4f075c4`
> is on `origin/example` and removed the committed root copy on purpose, and every brief on
> `example` from #154 onwards is `BRIEF-<N>.md`. So this is `BRIEF-187.md`, the same decision
> `BRIEF-186.md` made and for the same reason. If the lead wants the other name, `git mv`.

Four commits, and the SHA above is the last of them. The commit that adds this file sits on top
of it and contains nothing but this file, which is the convention `BRIEF-186.md` and `BRIEF-166.md`
both followed:

```
0d04db0 Four comments that were true-sounding and wrong, found by my own review
c2369c0 The GL test compared two unsettled frames, so its main assertion passed on nothing
865e6b7 Two tests that could not fail, and the reason both could not
13ed255 UiLayer and UiScreen drive a ComposeGL composition, not a scene2d Stage
```

Every run quoted below was made on `0d04db0`'s content unless the text says otherwise. `0d04db0`
changes no behaviour -- it is comments only, and section 8 is where it came from -- but the full
build and the evidence command were both re-run on it rather than assumed, and those are the runs
quoted. The only other thing in `git status` is ` M gradlew`, the executable bit this box needs on
the wrapper, deliberately not committed.

Evidence files referenced below live in `/srv/ssd1/workspace/Udea/build/issue187-evidence/` — the
main checkout, not this worktree's `build/`, so they survive a `clean`.

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

### It is green on this branch

On `0d04db0`. Spliced from `23-evidence-green-final.txt`, last four lines:

```
BUILD SUCCESSFUL in 48s
46 actionable tasks: 46 executed
Configuration cache entry stored.
EXIT=0
```

Counted off that run's XMLs, before any later run touched them, and saved as
`23-evidence-green-xml-totals.txt`:

```
test: tests=198 failures+errors=0 skipped=0
udeaGlTest: tests=21 failures+errors=0 skipped=0
```

`skipped=0` on `udeaGlTest` is the load-bearing half: it says the GL tests *ran*.

The same command on `c2369c0`, before the comment commit, is `16-evidence-green-final.txt`:
`BUILD SUCCESSFUL in 34s`, `EXIT=0`, and the same two counts. The three PNGs it wrote are
byte-identical to `0d04db0`'s, which is a small bonus fact: the composed GL frame is reproducible
run to run on this box, so a pixel diff against the gallery copies is a real check rather than a
coin toss.

### It goes red when the feature is reverted

The one line that makes `UiLayer` draw, replaced by the same call that only settles and lays out
— the mutation a reviewer would reach for, because it still compiles, still composes, still
advances the clock, and draws nothing. Literal diff, taken from that run
(`15-evidence-red.diff`):

```diff
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
```

Spliced from `15-evidence-red.txt`. Two segments, each a consecutive in-order run of that file's
lines, with the one elision marked. The first segment is lines 95–101:

```
> Task :udea-render:udeaGlTest

ComposeUiGlTest > a composed ComposeGL screen is drawn over the world into the captured frame() FAILED
    org.opentest4j.AssertionFailedError at ComposeUiGlTest.kt:122

ComposeUiGlTest > a click on the composed button recomposes the frame a capture reads() FAILED
    org.opentest4j.AssertionFailedError at ComposeUiGlTest.kt:175
```

**[... lines 102–112 elided: two blank lines and nine `> Task` lines for `:udea-agent` and
`:udea-replay`, which Gradle interleaved because it was still building them while the test task
ran ...]**

Then lines 113–126, unbroken:

```
> Task :udea-render:udeaGlTest

21 tests completed, 2 failed

> Task :udea-render:udeaGlTest FAILED

> Task :udea-render:test

UiLayerTest > the composed tree is drawn into the backend's canvas every frame() FAILED
    org.opentest4j.AssertionFailedError at UiLayerTest.kt:187

198 tests completed, 1 failed

> Task :udea-render:test FAILED
```

and, after Gradle's two `* What went wrong` blocks, the tail:

```
BUILD FAILED in 38s
37 actionable tasks: 37 executed
Configuration cache entry reused.
EXIT=1
```

Reverted with `git checkout --`, and the green run in the section above was taken **after** the
revert, so the tree the reviewer gets is the green one.

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

### The snapshot, and where it is mentioned

Two places, and that is a grep rather than a promise. Run on `0d04db0`:

```
$ git grep -n "0\.6\.0-SNAPSHOT\|maven-snapshots" -- . ':!BRIEF-187.md'
build.gradle.kts:56:        maven("https://central.sonatype.com/repository/maven-snapshots")
gradle/libs.versions.toml:59:# it resolves from `https://central.sonatype.com/repository/maven-snapshots`, declared in the root
gradle/libs.versions.toml:69:composegl = "0.6.0-SNAPSHOT"
```

Three hits for two places: the middle one is the version comment naming the repository, which is
what makes the comment usable without the issue open.

**And here is what that grep does not say, because I got this wrong first time and the comments I
wrote said it wrongly too.** `build.gradle.kts` declares *three* snapshot repositories, not one:

```
$ git grep -n "snapshots" -- build.gradle.kts settings.gradle.kts
build.gradle.kts:56:        maven("https://central.sonatype.com/repository/maven-snapshots")
build.gradle.kts:57:        maven("https://oss.sonatype.org/content/repositories/snapshots/")
build.gradle.kts:58:        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
```

Lines 57 and 58 have been there since `19403f3`, the initial commit -- that is
`git log -1 -S "oss.sonatype.org/content/repositories/snapshots" -- build.gradle.kts` -- they
serve other dependencies, and they must stay. My original comments said "nothing else in the
build refers to a snapshot", which a reader doing the 0.6.0 swap from the comment alone (the exact
use the constraint asked me to support) would have acted on by deleting all three. `0d04db0` fixes
both comments to name the one line to delete and to say what to leave. Section 8 has the rest of
that audit.

`composegl = "0.6.0-SNAPSHOT"` is also the only `-SNAPSHOT` version in the catalogue, so
`grep -n SNAPSHOT gradle/libs.versions.toml` is the completeness check on the swap, and the
comment now says so.

The full diff of both places, as committed, so the swap can be read off the comments:

```diff
diff --git a/gradle/libs.versions.toml b/gradle/libs.versions.toml
--- a/gradle/libs.versions.toml
+++ b/gradle/libs.versions.toml
@@ -53,7 +53,20 @@ junitPlatform = "1.13.4"
 # published release declares `kotlin-stdlib:2.4.20` and its jars carry `@Metadata(mv = [2, 4, 0])`,
 # which is why `kotlin` above had to move to 2.4.20 before any of this could be on the classpath
 # at all. `composegl-gdx` is a GL backend, so it is `udea-render`-only by UDEA-MG-002.
-composegl = "0.5.0"
+#
+# TEMPORARY: A SNAPSHOT, AND ONE OF THE TWO PLACES THAT SAY SO.
+# 0.6.0 is what `UiLayer` is written against (issue #187) and it is not on Maven Central yet, so
+# it resolves from `https://central.sonatype.com/repository/maven-snapshots`, declared in the root
+# `build.gradle.kts` `allprojects { repositories { ... } }` block -- the other of the two places.
+#
+# When 0.6.0 releases on Central, the whole move is: change this line to `composegl = "0.6.0"`
+# and delete that one `maven(...)` line. This is the only `-SNAPSHOT` version in this catalogue,
+# so `grep -n SNAPSHOT gradle/libs.versions.toml` is the check that the swap is complete.
+#
+# Read the next sentence before deleting anything else. The `allprojects` block also declares two
+# `oss.sonatype.org/content/repositories/snapshots` repositories. They are **not** part of this,
+# they predate it by a long way (they are in `19403f3`, the initial commit), and they must stay.
+composegl = "0.6.0-SNAPSHOT"

 [libraries]
 junit = { group = "junit", name = "junit", version.ref = "junit" }
diff --git a/build.gradle.kts b/build.gradle.kts
--- a/build.gradle.kts
+++ b/build.gradle.kts
@@ -44,6 +44,16 @@ allprojects {
         mavenLocal()
         gradlePluginPortal()
         google()
+        // TEMPORARY: A SNAPSHOT REPOSITORY, AND ONE OF THE TWO PLACES THAT SAY SO.
+        // ComposeGL 0.6.0 is not on Maven Central yet (issue #187), and Central's snapshot
+        // service is a separate host from Central proper. The other place is the `composegl`
+        // version in `gradle/libs.versions.toml`. When 0.6.0 releases, that entry becomes
+        // `"0.6.0"` and this one line goes.
+        //
+        // Only this line. The two `oss.sonatype.org` snapshot repositories immediately below
+        // are older than this change -- they are in `19403f3`, the initial commit -- serve
+        // other dependencies, and are nothing to do with ComposeGL.
+        maven("https://central.sonatype.com/repository/maven-snapshots")
         maven("https://oss.sonatype.org/content/repositories/snapshots/")
         maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
         maven("https://s01.oss.sonatype.org")
```

Both hunks spliced from `git diff origin/example..HEAD -- gradle/libs.versions.toml
build.gradle.kts`, with only the `index` lines dropped.

**No `changing = true`, no `cacheChangingModulesFor`, no `--refresh-dependencies` anywhere in the
build.** I did not hit a stale-artifact problem, so I did not add a cache setting for one. The
snapshot resolved once, on 2026-09-16, and every run in this brief used that same cached jar.

**The exact build resolved: `0.6.0-20260915.202706-1`** for all three artifacts. Spliced from
`17-composegl-ui-snapshot-metadata.xml`, which I fetched from the snapshot repository:

```xml
    <lastUpdated>20260915202706</lastUpdated>
    <snapshot>
      <timestamp>20260915.202706</timestamp>
      <buildNumber>1</buildNumber>
    </snapshot>
```

### The surprise, and it matters for #188 rather than for this ticket

**`0.6.0-20260915.202706-1` is byte-identical to `0.5.0`** for all three artifacts this build
uses:

```
6fd0d8a9cebd7f00f895db53467f2ebd239a7c4b  composegl-render-jvm   (0.5.0 and 0.6.0-SNAPSHOT)
7b0d81a634b81cc4e1e83dd28106e21af1dedb77  composegl-gdx          (0.5.0 and 0.6.0-SNAPSHOT)
94444e319603c237f6bf1e64b48343b754d7fb6f  composegl-ui-jvm       (0.5.0 and 0.6.0-SNAPSHOT)
```

The hash is the whole argument, and it does not depend on what anything is called. For what it
is worth as a cross-check, the full `unzip -Z1` listing of
`composegl-ui-jvm-0.6.0-20260915.202706-1.jar` is saved as
`18-composegl-ui-jar-listing.txt` — 854 entries — and a case-insensitive grep over it for
`inventory|dialog|skilltree|chat|subtitle|hitmarker|dock|objective|damagedirection` returns **0**.
The control for that grep, so it is not a search that finds nothing by construction, is that the
same listing does yield the `ui/game/` composables, and they are 0.5.0's set:

```
dev/wildware/composegl/ui/game/BarKt.class
dev/wildware/composegl/ui/game/DamageNumbersKt.class
dev/wildware/composegl/ui/game/HotbarKt.class
dev/wildware/composegl/ui/game/MinimapKt.class
dev/wildware/composegl/ui/game/NotificationsKt.class
dev/wildware/composegl/ui/game/ParticlesKt.class
dev/wildware/composegl/ui/game/RadialCooldownKt.class
dev/wildware/composegl/ui/game/ReticleKt.class
```

Note what that grep does **not** say: it is a search for the names #188 would want, not proof
that no such widget exists under some other name. The byte-identical hash is what settles it.

So "0.6.0 has loads of awesome features" is true of some build that is not this one. **This
changes nothing about #187** — the seam this ticket ports needs `UiHost`, `UiRenderer`,
`GdxBackend`, `PointerRouter` and `KeyRouter`, all of which 0.5.0 already had, which is why
everything here works. **It changes #188**, which is the ticket that wants the widget set. Posted
on the issue: <https://github.com/wildware-uk/Udea/issues/187#issuecomment-5696259422>.

I kept the snapshot pin anyway, because the owner's instruction was explicit and because it is
the reversible choice: when a newer snapshot is published, the next `--refresh-dependencies`
picks it up with no code change, whereas pinning back to 0.5.0 would have to be undone by hand.

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

### The cold full build, no exclusions

`clean build --no-build-cache --console=plain --no-daemon`, transcript `10-full-build.txt`,
its last five lines:

```
BUILD SUCCESSFUL in 2m 3s
225 actionable tasks: 218 executed, 7 up-to-date
Configuration cache entry reused.
EXIT=0
loadavg at end: 14.56 12.74 8.69 2/1526 1860937
```

Confirmed warm on `c2369c0`, transcript `20-full-build-final.txt`:

```
BUILD SUCCESSFUL in 21s
213 actionable tasks: 7 executed, 206 up-to-date
Configuration cache entry stored.
```

And again on `0d04db0`, after the comment commit -- `22-full-build-after-kdoc-fixes.txt`, with
`EXIT=0` reported by the shell:

```
BUILD SUCCESSFUL in 1m 25s
213 actionable tasks: 17 executed, 196 up-to-date
Configuration cache entry stored.
```

Summed over every `TEST-*.xml` in the tree after that build — saved as
`21-full-build-xml-totals.txt` so the number can be grepped rather than taken on trust:

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
this box, so every class in `dev.wildware.udea.render.gl` and
`dev.wildware.udea.agent.host.gl` skipped — `ComposeUiGlTest` among them, both of its tests —
and the build was green anyway. A green `sh gradlew build` says nothing at all about the GL half
of this ticket. That is why section 1's evidence command is the xvfb one and not this. (The other
two skipped classes, `RealArtAtlasPackerTest` and `RealArtReproducibilityTest`, are unrelated to
this ticket and skip on `origin/example` too.)

### The xvfb GL run, `requireGl=true`

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true --rerun-tasks
```

Transcript `13-gl-xvfb.txt`, last four lines:

```
BUILD SUCCESSFUL in 1m 5s
45 actionable tasks: 45 executed
Configuration cache entry stored.
EXIT=0
```

The XMLs from that run were copied out **at capture time**, to
`issue187-evidence/gl-xml-final/`, precisely because a later plain `build` overwrites them with
skipped ones — which is exactly what the warm build above then did. Summary
(`13-gl-xml-summary.txt`):

```
dev.wildware.udea.agent.host.gl.OffscreenRenderToolsTest: tests=7 failures=0 errors=0 skipped=0 time=2.057
dev.wildware.udea.agent.host.gl.OverlayCaptureIsolationTest: tests=1 failures=0 errors=0 skipped=0 time=0.513
dev.wildware.udea.render.gl.ComposeUiGlTest: tests=2 failures=0 errors=0 skipped=0 time=1.609
dev.wildware.udea.render.gl.GlCaptureDeterminismTest: tests=4 failures=0 errors=0 skipped=0 time=0.963
dev.wildware.udea.render.gl.GlCaptureTest: tests=5 failures=0 errors=0 skipped=0 time=1.281
dev.wildware.udea.render.gl.GlOverlayIsolationTest: tests=1 failures=0 errors=0 skipped=0 time=0.322
dev.wildware.udea.render.gl.GlThreadShutdownTest: tests=1 failures=0 errors=0 skipped=0 time=0.123
dev.wildware.udea.render.gl.OffscreenBackendTest: tests=8 failures=0 errors=0 skipped=0 time=1.908
TOTAL tests=29 failures+errors=0 skipped=0
```

### The three gates outside `check`

`udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd udeaVerifyContracts
udeaVerifyMigration udeaLegacyReport udeaVerifyDeterminism` — transcript `11-gates.txt`.

Its last two lines, contiguous:

```
BUILD SUCCESSFUL in 12s
EXIT=0
```

The individual verdicts are **not** a contiguous block — Gradle interleaves a per-project task for
every module, so this is that file reduced by
`grep -E '^> Task :udea(Verify|Legacy)' 11-gates.txt` to the root-level aggregates:

```
> Task :udeaVerifyModuleGraph UP-TO-DATE
> Task :udeaVerifyNoLegacyDependencies UP-TO-DATE
> Task :udeaVerifyContracts UP-TO-DATE
> Task :udeaLegacyReport UP-TO-DATE
> Task :udeaVerifyMigration UP-TO-DATE
> Task :udeaVerifyDeterminism UP-TO-DATE
> Task :udeaVerifyAgentsMd UP-TO-DATE
```

`udeaVerifyContracts` green is the mechanical statement that **no `docs/contracts/` file moved**;
the diffstat in section 5 is the other half of it.

`:moba:runMatchShot :moba:runShot` under xvfb — `12-moba-shots.txt`, `BUILD SUCCESSFUL in 2m 36s`,
`EXIT=0`. I ran those to check `MobaHud` is unregressed by the rename, and I **looked at**
`match/hud.png`: score bar, health bar, ability bar with live cooldowns, event log, selection
ring, sprites all resolving, no `UDEA0032`.

`:moba:runUdpProof` I did not run. It is red on `origin/example` and nothing in this ticket
touches replication.

### `udeaDaemonBudget`

**It did not run, and the reason is that it is not on `check` in this tree.**
`udea-assets-compiler/build.gradle.kts` registers it as a standalone `Test` task and wires only
`udeaPackGate` to `check`:

```kotlin
// `udeaPackGate` only. `udeaGraphBudget` is reached through the root's `udeaLatencyBudgets`,
// which the `latency-budgets` CI job runs serially on both runner images (issue #175).
tasks.named("check") {
    dependsOn(udeaPackGate)
}
```

Consistent with the build log: the only `udea-assets-compiler` verification tasks in
`10-full-build.txt` are `test`, `udeaPackGate` and the verifiers. `udeaPackGate` passed. So the
latency-budget flakiness I was warned about could not have affected this build, and there is
nothing to re-run solo.

### One incident worth recording

The **first** attempt at the cold full build was **killed, not failed**:
`Gradle build daemon has been stopped: stop command received`, at loadavg 24, while `melon-merge`
was running its fifteen-minute scenario suite on this shared box. I did not report that as a test
failure. I sampled `pgrep` every few seconds until the box was quiet and re-ran, which gave the
`BUILD SUCCESSFUL in 2m 3s` above. Nothing about it was specific to this branch.

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

The same three md5s come out of `udea-render/build/reports/udea/compose-ui/` after the evidence
command, and out of the run before it on `c2369c0`.

| File | What it shows | What it proves |
|---|---|---|
| `issue187-composegl-ui-gl-frame-unmounted.png` | The world alone: one blue quad, no interface | The baseline every other assertion diffs against. Also the control for "the layer draws nothing when nothing is mounted" |
| `issue187-composegl-ui-gl-frame.png` | The same frame with a ComposeGL panel over it: a title, a label reading "clicked 0 times", and a focused button | AC-3. A real LWJGL3 context, a real FreeType-rasterised glyph, drawn into the offscreen target a `FrameCapture` reads. The world shows through around the panel, so the layer composited rather than cleared |
| `issue187-composegl-ui-gl-clicked.png` | The label now reads "clicked 1 times" | A pointer event went through `layer.input`, reached the button's `onClick`, recomposed, and reached a pixel |
| `issue187-composegl-ui-sequence.png` | The three above tiled and labelled | The sequence read as one picture: no interface, interface, interface responding |
| `issue187-moba-hud-live-agent-screenshot.png` | `moba`'s scene2d HUD, captured over the bridge from a live game at tick 2203 | `MobaHud` is unregressed by the `Scene2dUiLayer` rename — decision 4 honoured. This is the *old* layer still working, not the new one |

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
 build.gradle.kts                                   |  10 +
 gradle/libs.versions.toml                          |  20 +-
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
 15 files changed, 1703 insertions(+), 186 deletions(-)
```

`0d04db0` is the comment commit and changed no behaviour in any file. Its own stat, which is
where some of the added lines in the two build files and four Kotlin files above come from:

```
 build.gradle.kts                                   |  6 +++++-
 gradle/libs.versions.toml                          | 12 +++++++++---
 .../kotlin/dev/wildware/udea/render/ui/UiScreen.kt |  4 +++-
 .../udea/render/ui/scene2d/Scene2dUiLayer.kt       | 22 ++++++++++++----------
 .../udea/render/ui/scene2d/Scene2dUiScreen.kt      |  4 +++-
 .../dev/wildware/udea/render/gl/ComposeUiGlTest.kt | 10 ++++++----
 6 files changed, 38 insertions(+), 20 deletions(-)
```

**`net-protocol.lock`: not touched. `expected-generated-hashes.txt`: not touched. Ids moved by
zero.** This ticket adds no replicated component and no `@Net` field — it is presentation only,
and presentation has no wire representation. Both files are absent from the diffstat above, and
`udeaCheckProtocolLock` runs on `check`, which was green in the full build.

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

Each row is the literal `git diff` of the mutation, from the run that produced the failure beside
it. Outputs, diffs and XMLs are all under `issue187-evidence/mutations/`. `unit` means
`:udea-render:test --tests 'dev.wildware.udea.render.ui.*'` (24 tests); `gl` means
`:udea-render:udeaGlTest --tests '...ComposeUiGlTest'` under xvfb with `requireGl=true`.

### M1 — the clamp

```diff
--- a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
+++ b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
@@ -293,7 +293,7 @@ public class UiLayer internal constructor(
     private fun clampedFrameNanos(): Long =
-        (frameTime.frameSeconds.coerceAtMost(MAX_UI_SECONDS) * NANOS_PER_SECOND).toLong()
+        (frameTime.frameSeconds * NANOS_PER_SECOND).toLong()
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
--- a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
+++ b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
@@ -229,7 +230,7 @@ public class UiLayer internal constructor(
-        renderer.render(viewport, clockNanos)
+        host.settle(viewport, focus, clockNanos)
```

`unit`: `24 tests completed, 1 failed` — `the composed tree is drawn into the backend's canvas
every frame`, `three pipeline frames must be three canvas frames opened and closed cleanly ==>
expected: <3> but was: <0>`.
`gl`: `2 tests completed, 2 failed` — `only 0 pixels inside the panel differ between the mounted
and the unmounted frame; the interface did not draw`, and `the label counting the clicks reads the
same in both frames, so the recomposition never reached a pixel`.

This is the mutation section 1 uses as the evidence-command proof.

### M3 — the unmount

```diff
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
--- a/udea-render/src/main/kotlin/dev/wildware/udea/render/input/GdxKeyboard.kt
+++ b/udea-render/src/main/kotlin/dev/wildware/udea/render/input/GdxKeyboard.kt
@@ -120,7 +120,7 @@ public class GdxKeyboard : KeyboardState, InputProcessor {
-            val multiplexer = InputMultiplexer(*processors)
+            val multiplexer = InputMultiplexer(*processors.reversedArray())
```

`unit`: 1 failed — `a key the composition consumes never reaches the keyboard`,
`Escape closed a panel AND fired the gameplay binding under it ==> expected: <0> but was: <1>`.

### M5 — the disposal

```diff
--- a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
+++ b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
@@ -215,7 +215,6 @@ public class UiLayer internal constructor(
-        resources.own(this)
         (backend as? Disposable)?.let(resources::own)
```

`unit`: 1 failed — `the layer is disposed by the pipeline rather than by whoever remembered`,
`the pipeline did not dispose the UI layer`.

### M6 — the viewport is never sized

```diff
--- a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
+++ b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
@@ -308,7 +308,6 @@ public class UiLayer internal constructor(
         if (viewport.physical.width == width && viewport.physical.height == height) return
-        viewport = Viewport.oneToOne(Size(width, height))
     }
```

`unit`: 1 failed — `the layer lays the tree out against the surface it is drawing into`,
`expected: <(800.0, 600.0)> but was: <(1.0, 1.0)>`.
`gl`: 2 failed — the panel assertion, and `the click did not reach the button's onClick ==>
expected: <1> but was: <0>` (a 1x1 layout puts the button nowhere the pointer lands).

### M7 — the FreeType natives, in the build file

```diff
--- a/udea-render/build.gradle.kts
+++ b/udea-render/build.gradle.kts
@@ -41,7 +41,7 @@ dependencies {
-    runtimeOnly(variantOf(libs.gdx.freetype.platform) { classifier("natives-desktop") })
+    // M7: the freetype natives removed.
```

`gl`: `2 tests completed, 2 failed`

```
SharedLibraryLoadRuntimeException: Couldn't load shared library 'libgdx-freetype64.so' for target: Linux, x86, 64-bit
SharedLibraryLoadRuntimeException: Unable to read file for extraction: libgdx-freetype64.so
```

This is the row that makes the AC-3 claim concrete: the blocker #186 hit is real, the one-line fix
is load-bearing, and removing it fails loudly rather than drawing blank text.

### M8 — the glyphs

```diff
--- a/udea-render/src/test/kotlin/dev/wildware/udea/render/gl/ComposeUiGlTest.kt
+++ b/udea-render/src/test/kotlin/dev/wildware/udea/render/gl/ComposeUiGlTest.kt
@@ -318,7 +320,7 @@ class ComposeUiGlTest {
-                        Text("UDEA ON COMPOSEGL", Modifier.testTag(TITLE))
+                        Box(Modifier.width(220f).height(18f).background(dev.wildware.composegl.ui.graphics.Colour.rgb(0x8899AA)).testTag(TITLE))
```

`gl`: 1 failed — `the title's box holds only 2 distinct colours, which is a flat rectangle rather
than rasterised text: FreeType produced no glyphs`.

A mutation of the *test's own subject* rather than of production code, deliberately: it is the only
way to show that the colour-count assertion distinguishes text from a coloured rectangle of the
same size, which is what a blank-glyph failure would look like.

### M9 — the layer rescales the whole surface

```diff
--- a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
+++ b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
@@ -308,7 +308,7 @@ public class UiLayer internal constructor(
-        viewport = Viewport.oneToOne(Size(width, height))
+        viewport = Viewport.oneToOne(Size(width / 2f, height / 2f)).copy(physical = Size(width, height))
```

`gl`: 1 failed — `3641 pixels changed in a 64x64 corner the interface does not cover, so the layer
is clearing or rescaling the whole surface ==> expected: <0> but was: <3641>`.

### M10 — the control

```diff
--- a/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
+++ b/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/UiLayer.kt
@@ -292,6 +292,10 @@ public class UiLayer internal constructor(
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
comment they would have deleted three repositories instead of one. Section 2 has the greps.

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
$ grep -rnE "\.kt:[0-9]+" udea-render/src/main/kotlin/dev/wildware/udea/render/ui/ \
    udea-render/src/test/kotlin/dev/wildware/udea/render/ui/ \
    udea-render/src/test/kotlin/dev/wildware/udea/render/gl/ComposeUiGlTest.kt
(none left)
```

**4. Two exhaustiveness claims the text under them did not support.** "kept under a name that
says so for exactly one reason", sitting above two reasons; and a heading, "Why none of the
other tests can stand in for this one", over a body that argues only about `UiLayerTest`. Both
now say what they actually argue.

And I re-ran both the full build and the evidence command on `0d04db0` rather than reasoning
that comments cannot break anything. Sections 1 and 3 quote those runs.

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
- **The widget set.** Not this ticket (decision 2), and section 2 explains why #188 will want to
  read the snapshot finding before it starts.
