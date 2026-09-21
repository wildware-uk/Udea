5dc87a37

# BRIEF - #267: a sky, so a 3D game's horizon is not black

The SHA above is the reviewed code, `issue-267-sky` rebased on `origin/master` `43b67c17`.
This brief is committed on top of it, as one commit that adds nothing but this file.
Every file named in backticks below without a path (`finalgl.log`, `mut/M0.diff`, `stable-transcript.txt`, ...) is in
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/dev267/`, and every transcript
here was spliced out of those files by `assemble.py` in the same folder, not typed.

## 1. The evidence command

    xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
      ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
      sh gradlew :moba:desktop:runSkyProof --no-configuration-cache

This is a game setting its sky from `moba:desktop`. That project may not name Kool (`UDEA-MG-002`), so it also proves a game needs no Kool type to do it. The proof opens a real Kool context, puts the game's fox on a wide ground plane and looks at the horizon. It works out which rows are open sky from the no-sky frame (rows that are exactly black) instead of assuming. Then it sets a flat sky, a day gradient and a night gradient while the game runs, and sets no sky again. It fails unless every open-sky pixel is the colour asked for, none of the world's pixels move, and the final no-sky frame is byte-identical to the first. The run at the SHA above (`finalgl.log`, spliced):

```
sky-proof: before: 172 of 360 rows are open sky, every pixel in them black
sky-proof: before: 120320 pixels are drawn by the world (not black)
sky-proof: solid: the furthest open-sky pixel is 0 levels from the sky colour
sky-proof: solid: 0 of the 120320 pixels the world draws changed
sky-proof: gradient: the top row is at most 0 levels from the top colour
sky-proof: gradient: every open-sky row is within 1 levels of the blend at its height
sky-proof: night: every open-sky row is within 1 levels of the night blend
sky-proof: none again: 0 pixels differ from the frame before any sky was set
sky-proof: all checks passed; pictures in /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3c62f4afea1f5f50/moba/desktop/build/reports/udea/sky
```

**It goes red with the feature reverted.** M0 below makes `SkyPainter.paint` record nothing, which is the tree before this change. The prediction was frozen before the run: *"FAIL at solid: worst open-sky pixel = max channel of DAY (0.45,0.68,0.95) = 242 levels"*. Measured (`mut/M0.log`):

```
> Task :udea-render:jvmTest FAILED
> Task :udea-render:udeaGlTest FAILED
> Task :moba:desktop:runSkyProof FAILED
sky-proof: before: 172 of 360 rows are open sky, every pixel in them black
sky-proof: before: 120320 pixels are drawn by the world (not black)
sky-proof: solid: the furthest open-sky pixel is 242 levels from the sky colour
Exception in thread "main" java.lang.IllegalArgumentException: open sky is 242 levels from [115, 173, 242] under a solid sky
```

## 2. Summary

- **What a game writes:** `registry.sky.background = SkyBackground.Gradient(top, bottom)`, or `Solid(colour)`. Colours are `Rgba`, the shader API's type, so no Kool type is involved. `SkyBackground` (`None` / `Solid` / `Gradient`) is a sealed value. `Sky` is a small holder on `RenderRegistry` whose `background` is `@Volatile`. Set it at any time from any thread, and the next frame shows it. That is how per-scene works: a level loader or a render system reading the world swaps it.
- **How it draws:** `SkyPainter` (internal) is the first draw of every frame, before any `RenderSystem`. It is one full-frame quad in the capturable batch: the batch's white texel tinted for `Solid`, or a 1x256 texture for `Gradient`, made once per gradient and released when the game sets a different one. The 3D model pass clears to transparent, so the sky shows wherever no model is. Screen shaders (#266) run after the frame is recorded, so they process the sky as part of the picture; `GlScreenShaderTest` stays green (section 4), and so do `runShaderProof` and `runShaderAssetProof`: both ran in the branch's shot run, whose marker reads `EXIT=0` (`shots-head.marker`). An editor's Scene tab paints the sky under the world as well. Its Game tab copies the frame, so it has the sky already.
- **`None` draws nothing.** It is not a black quad over the black clear colour, which would look identical and still be a change to every frame. Mutation M2 shows why the unit test is what guards this: the pixel checks are correctly silent about it.

Decisions, each commented on #267 with the alternative and what to change to overturn it:
1. **The sky lives on `RenderRegistry`, not `WindowConfig`.** A window is configured once; the issue asks for per-scene. I added no `WindowConfig` field even as an initial value, because two places to set one thing is one too many. Adding it later is additive: copy it into `registry.sky` before the first frame.
2. **Draw, don't re-clear.** A pass clear colour covers `Solid` only, and a gradient needs a draw anyway. One mechanism is simpler.
3. **The gradient is in screen space**, top edge to bottom edge. That is right for a camera with fixed pitch, which is every camera in the tree. A gradient tied to the view's horizon would be a new case beside this one.
4. **Texture skies are out of scope, as agreed.** Adding one takes a `SkyBackground.Image(region: SpriteRegion)` case, drawn like `Gradient` with one `batch.draw`. The painter must **not** release it, because the texture is the game's. The real work is the asset side: a sky image has to come out of the packed graph as a `SpriteTexture` on every target, the way `.frag` did in #269, rather than through a per-platform file read.

**The issue said moba has a `SkySystem`. It does not.** The one real game sky is Hollow's (`hollow/game/.../render/SkySystem.kt`). There are also two private copies in shot mains: `moba/desktop/.../GameModelShot.kt` and `udea-render/.../model/ModelShot.kt`. The census, with its control (`census-*.txt`):

```
$ git grep -n "class SkySystem" -- '*.kt'
hollow/game/src/commonMain/kotlin/dev/wildware/hollow/render/SkySystem.kt:21:internal class SkySystem(private val resources: RenderResources) : RenderSystem {
moba/desktop/src/test/kotlin/dev/wildware/moba/GameModelShot.kt:227:    private class SkySystem(private val resources: RenderResources) : RenderSystem {
udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/model/ModelShot.kt:228:    private class SkySystem(private val resources: RenderResources) : RenderSystem {
$ git grep -n "SkySystem" -- 'moba/game'      # prints nothing; the command's exit status was 1

$ git grep -ln "object MobaAssets" -- 'moba/game'   # control: a known positive under the same path
moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaAssets.kt
```

**Could Hollow adopt the engine's sky?** Mostly. Its sky is a zenith-to-haze blend eased by `t*t`, reaching full haze at 40% of the frame and flat below that. The engine's `Gradient` is linear from top to bottom, so adopting it as-is would change Hollow's shots. The swap is `registry.sky.background = Gradient(ZENITH, HORIZON)` and deleting `SkySystem`, and it needs the owner to accept a slightly different sky or a later `Gradient` with a stop position. As agreed, I did not touch any game's sky; `dev-251` owns `hollow:game` this wave.

**A surprise worth knowing, outside this ticket:** 40 of the 232 screenshots this repository produces differ between two runs of the *same* master commit. They are Hollow's 30 `player-*.png`, moba's `lane/clash`, `lane/farm` and `match/{hud,item_bar,item_fired,melee,spin}`, and `udea-render`'s `gl/ui-window-moving-{0,1,2}`. Hollow's `runPlayerShot` says "one PNG per known tick", and on this evidence its pictures are not reproducible. I have not looked for the cause.

**Re-measured after a scratchpad collision.** The session scratchpad turned out to be shared. At 04:28:30Z my mutation runner called a `gl.sh` that another developer had just overwritten, so the first M0 run executed *their* GL tests in *their* worktree. I deleted the false marker it left (the lead relayed this to them). No result from that window is quoted here. Everything below was re-run from `scratchpad/dev267/` with scripts of my own: `xgl.sh`, `mut.sh`, `shots.sh`, `build.sh`, `finalgl.sh`. Every log's gradle output names my worktree `agent-a3c62f4afea1f5f50` and never theirs; the earlier shot runs were checked the same way before being kept.

## 3. `sh gradlew build`

    ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue --no-configuration-cache --max-workers=4

Run at `140bb463` (`build.head`: `HEAD=140bb463b2b641df4f22960985920ea6b613c59f start=2026-09-21T04:43:51Z end=2026-09-21T04:52:28Z`), marker `EXIT=0 DONE`. Tail of `build.log`:

```
BUILD SUCCESSFUL in 8m 36s
1122 actionable tasks: 692 executed, 238 from cache, 192 up-to-date
```

`grep -c FAILED build.log` = 0. The build ran at `140bb463`, before the rebase onto `43b67c17`. Between that tree and the SHA above, the only changes are `.claude/` and `.gitignore`: `git diff --stat 140bb463 5dc87a37 -- . ':!.claude'` prints ` .gitignore | 5 +++++` and nothing else. The shot runs were at `a4278018`. Since then the only changes outside `.claude/` are `.gitignore`, `AGENTS.md`, the wiki page and five KDoc lines in `SkyBackground.kt` (`since-shots.txt`), and none of them is code.

## 4. The GL tests, for real

`finalgl.sh` at the SHA above deleted the three result directories first, then ran with `--no-build-cache`:

    xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
      ANDROID_HOME=... JAVA_HOME=.../21.0.11-tem \
      sh gradlew :udea-render:udeaGlTest :udea-agent-host:udeaAgentGlTest :udea-editor:udeaEditorGlTest \
        :moba:desktop:runSkyProof -Pudea.render.requireGl=true --no-configuration-cache --no-build-cache --max-workers=4 --continue

`finalgl.head`: `worktree=/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3c62f4afea1f5f50 HEAD=5dc87a37f1545c9f945b3db16b283ff08a607b82 start=2026-09-21T04:53:07Z end=2026-09-21T04:56:50Z`. Marker: `EXIT=0 DONE`. Counted out of the JUnit XML by `xmlcount.py`. Each in-XML `timestamp=` falls inside the run's start and end above, so these ran and were not restored from cache:

```
udeaAgentGlTest: files=2 tests=2 skipped=0 failures=0 errors=0 timestamps 2026-09-21T04:55:20.344Z .. 2026-09-21T04:55:25.455Z
udeaEditorGlTest: files=6 tests=6 skipped=0 failures=0 errors=0 timestamps 2026-09-21T04:55:29.210Z .. 2026-09-21T04:56:39.484Z
udeaGlTest: files=30 tests=31 skipped=0 failures=0 errors=0 timestamps 2026-09-21T04:53:14.955Z .. 2026-09-21T04:55:09.773Z
```

`udeaGlTest` has 30 classes and 31 tests: `OffscreenBackendTest` holds two, and `GlSkyTest` is new. Its PNGs are `udea-render/build/reports/udea/gl/sky-{1-none,2-solid,3-gradient,4-none-again}.png`.

## 5. Images

In `/srv/ssd1/workspace/Udea/build/debug-screenshots/`:

- `issue267-1-no-sky.png` - before: the game's fox on open ground, and the horizon is black. This is the defect.
- `issue267-2-solid-day.png` - a flat day sky. Every open-sky pixel is the colour to the level, and no ground pixel moved.
- `issue267-3-gradient-day.png` - a day gradient, zenith blue to haze at the frame's bottom edge.
- `issue267-4-gradient-night.png` - a night gradient, set while the game runs: the per-scene change.
- `issue267-5-no-sky-again.png` - no sky again, byte-identical to shot 1.
- `issue267-sequence.png` - the five above tiled, in order.
- `issue267-engine-sky-{1-none,2-solid,3-gradient,4-none-again}.png` - `GlSkyTest`'s frames: a box in an otherwise empty frame, so every non-box pixel is sky.
- `issue267-engine-test-none-solid-gradient-none.png` - those four tiled.

The five proof shots from the final run are byte-identical (`cmp`) to the ones first copied to the gallery.

## 6. The issue, criterion by criterion

**"A game sets a background colour and a screenshot shows it where nothing is drawn."**
- `runSkyProof` (section 1): open-sky pixels are 0 levels from the flat colour, 0 from the top of the gradient, and within 1 of the blend at every open row, for both day and night. The pictures are `issue267-2..4`.
- `GlSkyTest`, in `udeaGlTest`: all four corners are the solid colour, the box is still drawn over it, and the gradient's top and bottom rows are its two colours.
- `SkyTest`, 6 tests in `jvmTest`, with no GL: the sky is the first draw of the frame and covers all of it. A change between frames shows on the next frame. A gradient's texture runs top colour to bottom colour, is made once, and is released when replaced and on dispose. The Scene view paints it too, at its own size.

**"The default stays what it is today, so no existing shot changes."** Proved three ways:
1. **Every screenshot the repository produces, compared by pixel.** `shots.sh` ran every producer under xvfb: `udeaGlTest`, `udeaEditorGlTest`, `udeaAgentGlTest`, `udea-render:runModelShot`, moba's `runShot`, `runMatchShot`, `runModelShot`, `runLaneShot`, `runShaderProof`, `runShaderAssetProof`, `runLevelShot`, and hollow's `runShot` and `runPlayerShot`. It ran three times: on master `0e02b049` (base), on the branch `a4278018` (head), and on master again `56795a0f` (base2), which differs from base only in `.claude/WAVE.md`. That gave 232 PNGs a run. `shotdiff.py` compares every channel of every pixel, alpha included, at a tolerance of **0 levels**. Master against itself is the control: a shot master cannot reproduce cannot testify either way. `stable.py` splits the files by that control (`stable-transcript.txt`):
   ```
files master draws the same on two runs (stable): 192
  of those, identical on the branch: 192
  of those, different on the branch: 0
files master draws differently on two runs (noisy): 40
  of those, different between master and the branch too: 39
exit=0
   ```
   So all 192 shots master can reproduce are pixel-identical on the branch. All 192 are byte-identical as files too: `comm -12` of the stable list against `diff-base-head.txt`'s `SAME bytes=same` lines gives 192. The 40 master cannot reproduce are listed in section 2, and 39 of them differ on the branch as they differ on master. The four `NEW-IN-HEAD` files are `GlSkyTest`'s.

   **The threshold, failing on both sides.** At 0 levels an identical copy passes, and a single pixel moved by one level fails in a colour channel or in alpha alone (`thr-transcript.txt`):
   ```
== base vs same
SAME   bytes=same worst=0 over=0 x.png
SUMMARY base=1 head=1 compared=1 byte-identical=1 failed=0 tolerance=0
exit=0
== base vs one-level
OVER   bytes=diff worst=1 over=1 x.png
SUMMARY base=1 head=1 compared=1 byte-identical=0 failed=1 tolerance=0
exit=1
== base vs alpha-only
OVER   bytes=diff worst=1 over=1 x.png
SUMMARY base=1 head=1 compared=1 byte-identical=0 failed=1 tolerance=0
exit=1
   ```
   **And the comparison catches a real change.** With M1 applied (a default sky that is not `None`), the same pipeline over every producer (`stable-m1-transcript.txt`; its 86 `CHANGED` path lines are left out here and are in the file):
   ```
files master draws the same on two runs (stable): 192
  of those, identical on the branch: 106
  of those, different on the branch: 86
files master draws differently on two runs (noisy): 40
  of those, different between master and the branch too: 38
exit=1
   ```
    `udeaGlTest` and `udeaEditorGlTest` also went red, because tests that read a black background failed, and 19 of master's 232 shots were never written (`diff-base-m1.txt`: 19 `MISSING-IN-HEAD`; the one `NEW-IN-HEAD` is `GlSkyTest`'s `sky-1-none.png`, saved before its first assertion failed). That is as predicted before the run.
2. **At the record level:** `SkyTest` "with no sky set a frame records the game's draws and nothing else" asserts the batch holds the game's single draw and one run. This is the only test that sees M2, black drawn over black.
3. **In a real frame:** both `GlSkyTest` and `runSkyProof` assert that setting `None` again restores the first frame byte-for-byte.

## 7. Mutations: predicted, then measured

The predictions (`predictions.txt`) were written before any mutation ran. The M1-over-every-shot addendum is timestamped before that run finished. Each row applies the diff, runs `SkyTest`, `GlSkyTest` and `runSkyProof` in one `--continue` invocation, reads the verdicts out of the saved XML, then restores the file. Every row printed results: none is empty or UNKNOWN.

| | Mutation | Predicted | Measured |
|---|---|---|---|
| M0 | `paint` records nothing (the feature reverted) | SkyTest 5 of 6 FAIL, the no-sky test passes; GlSkyTest FAIL at step 2; proof FAIL at 242 levels | exactly that |
| M1 | the default sky is `Solid(...)` | SkyTest no-sky FAIL; GlSkyTest FAIL at step 1; proof FAIL, 0 open rows | exactly that; and over every shot, 86 stable shots CHANGED |
| M2 | `None` fills black over black | SkyTest no-sky FAIL, others pass; GlSkyTest PASS; proof PASS (correctly silent: the same pixels) | as predicted, **plus** SkyTest "changed between frames" FAILs, since its last step sets `None`. The prediction missed it |
| M3 | the sky painted after the systems | SkyTest solid, gradient, changed and Scene-view FAIL; GlSkyTest FAIL (sky over box); proof FAIL, 120320 of 120320 changed | GlSkyTest and proof exactly (120320 of 120320). SkyTest: solid, gradient, changed **and texture** FAIL, **Scene view passes**. The prediction was wrong on two tests: M3 moves only the capturable frame's paint, and the texture test reads run 0 |
| M4 | gradient rows reversed | SkyTest gradient FAIL; GlSkyTest FAIL at step 3; proof FAIL at 166 levels | proof 166 exactly, GlSkyTest exactly; SkyTest gradient **and texture** FAIL (texture checks the new gradient's first row). Missed one |
| M5 | no sky in the Scene view | SkyTest Scene-view FAIL only | exactly that; GlSkyTest and proof pass (correctly silent: neither opens a Scene view) |
| M6 | an old gradient never released | SkyTest texture FAIL only | exactly that; GlSkyTest and proof pass (correctly silent: a leak draws the same pixels) |

The predicted figures come from the constants, not from the runs: 242 = round(0.95 x 255), 166 = |round(0.85 x 255) - round(0.2 x 255)|, and 120320 = 188 rows x 640 from the first no-sky frame of the run. Each diff and its failing tests, spliced from `mut/<name>.diff` and `mut/<name>.summary.txt` (M0 from `mutsum.py M0`):

### M0

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/sky/SkyPainter.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/sky/SkyPainter.kt
index 4e9d287c..0f58ed75 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/sky/SkyPainter.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/sky/SkyPainter.kt
@@ -34,6 +34,7 @@ internal class SkyPainter(private val sky: Sky) : RenderResource {
     fun paint(batch: SpriteBatch2D, target: OffscreenTarget) {
         // Read once: a game may set the sky from another thread, and the frame draws one value.
         val background = sky.background
+        if (true) return
         if (background == SkyBackground.None) return
         val width = target.width.toFloat()
         val height = target.height.toFloat()
```

```
== M0
gradle EXIT=1
SkyTest: tests=6 failures=5 errors=0 skipped=0 timestamp=2026-09-21T04:32:15.223Z
  FAIL a sky changed between frames shows on the next frame, and None takes it away again()[jvm]  org.opentest4j.AssertionFailedError: expected: <[84418559, -16776961]> but was: <[-16776961]>
  FAIL a solid sky is the first draw of the frame, covers all of it, and is its colour()[jvm]  org.opentest4j.AssertionFailedError: the sky is not under the game's draws ==> expected: <[1722217215, -16776961]> but was: <[-16776961]>
  FAIL a gradient sky covers the frame with a texture running from its top colour to its bottom one()[jvm]  org.opentest4j.AssertionFailedError: expected: <[-1, -16776961]> but was: <[-16776961]>
  FAIL a gradient's texture is made once, and replaced and released only when the gradient changes()[jvm]  org.opentest4j.AssertionFailedError: expected: not same but was: <SpriteTexture('sky-test-white', 1x1)>
  FAIL an editor's Scene view draws the sky under the world, and its gizmos over both()[jvm]  org.opentest4j.AssertionFailedError: expected: <[-225686017, -16776961]> but was: <[-16776961]>
  pass with no sky set a frame records the game's draws and nothing else()[jvm]  
GlSkyTest: tests=1 failures=1 errors=0 skipped=0 timestamp=2026-09-21T04:32:15.842Z
  FAIL a sky shows wherever nothing is drawn, changes while running, and None is the black it always was()  org.opentest4j.AssertionFailedError: with a solid sky, the top-left corner is [0, 0, 0], and the sky is [102, 166, 242] (red, green, blue)
runSkyProof:
  > Task :udea-render:jvmTest FAILED
  > Task :udea-render:udeaGlTest FAILED
  > Task :moba:desktop:runSkyProof FAILED
  sky-proof: before: 172 of 360 rows are open sky, every pixel in them black
  sky-proof: before: 120320 pixels are drawn by the world (not black)
  sky-proof: solid: the furthest open-sky pixel is 242 levels from the sky colour
  Exception in thread "main" java.lang.IllegalArgumentException: open sky is 242 levels from [115, 173, 242] under a solid sky
```

### M1

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/sky/Sky.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/sky/Sky.kt
index a8a3596e..a759e684 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/sky/Sky.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/sky/Sky.kt
@@ -28,7 +28,7 @@ public class Sky internal constructor() {
      * the next frame, never half-way through one, and setting it touches nothing of the renderer's.
      */
     @Volatile
-    public var background: SkyBackground = SkyBackground.None
+    public var background: SkyBackground = SkyBackground.Solid(dev.wildware.udea.render.draw.Rgba.of(0.4f, 0.65f, 0.95f))
 
     override fun toString(): String = "Sky($background)"
 }
```

```
== M1
gradle EXIT=1
SkyTest: tests=6 failures=1 errors=0 skipped=0 timestamp=2026-09-21T04:32:38.208Z
  pass a sky changed between frames shows on the next frame, and None takes it away again()[jvm]  
  pass a solid sky is the first draw of the frame, covers all of it, and is its colour()[jvm]  
  pass a gradient sky covers the frame with a texture running from its top colour to its bottom one()[jvm]  
  pass a gradient's texture is made once, and replaced and released only when the gradient changes()[jvm]  
  pass an editor's Scene view draws the sky under the world, and its gizmos over both()[jvm]  
  FAIL with no sky set a frame records the game's draws and nothing else()[jvm]  org.opentest4j.AssertionFailedError: a game that sets no sky has none ==> expected: <None> but was: <Solid(colour=Rgba(0x66a6f2ff))>
GlSkyTest: tests=1 failures=1 errors=0 skipped=0 timestamp=2026-09-21T04:32:39.019Z
  FAIL a sky shows wherever nothing is drawn, changes while running, and None is the black it always was()  org.opentest4j.AssertionFailedError: with no sky, the top-left corner is [102, 166, 242], and the sky is [0, 0, 0] (red, green, blue)
runSkyProof:
  > Task :udea-render:jvmTest FAILED
  > Task :udea-render:udeaGlTest FAILED
  > Task :moba:desktop:runSkyProof FAILED
  sky-proof: before: 0 of 360 rows are open sky, every pixel in them black
  Exception in thread "main" java.lang.IllegalArgumentException: only 0 rows of the frame are open sky; the checks below would be measuring too little to mean anything
```

### M2

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/sky/SkyPainter.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/sky/SkyPainter.kt
index 4e9d287c..a6181022 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/sky/SkyPainter.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/sky/SkyPainter.kt
@@ -34,7 +34,9 @@ internal class SkyPainter(private val sky: Sky) : RenderResource {
     fun paint(batch: SpriteBatch2D, target: OffscreenTarget) {
         // Read once: a game may set the sky from another thread, and the frame draws one value.
         val background = sky.background
-        if (background == SkyBackground.None) return
+        if (background == SkyBackground.None) {
+            batch.beginPixels(); batch.fill(0f, 0f, target.width.toFloat(), target.height.toFloat(), Rgba.BLACK); batch.end(); return
+        }
         val width = target.width.toFloat()
         val height = target.height.toFloat()
         batch.beginPixels()
```

```
== M2
gradle EXIT=1
SkyTest: tests=6 failures=2 errors=0 skipped=0 timestamp=2026-09-21T04:32:54.452Z
  FAIL a sky changed between frames shows on the next frame, and None takes it away again()[jvm]  org.opentest4j.AssertionFailedError: expected: <[-16776961]> but was: <[255, -16776961]>
  pass a solid sky is the first draw of the frame, covers all of it, and is its colour()[jvm]  
  pass a gradient sky covers the frame with a texture running from its top colour to its bottom one()[jvm]  
  pass a gradient's texture is made once, and replaced and released only when the gradient changes()[jvm]  
  pass an editor's Scene view draws the sky under the world, and its gizmos over both()[jvm]  
  FAIL with no sky set a frame records the game's draws and nothing else()[jvm]  org.opentest4j.AssertionFailedError: expected: <[-16776961]> but was: <[255, -16776961]>
GlSkyTest: tests=1 failures=0 errors=0 skipped=0 timestamp=2026-09-21T04:32:55.168Z
  pass a sky shows wherever nothing is drawn, changes while running, and None is the black it always was()  
runSkyProof:
  > Task :udea-render:jvmTest FAILED
  sky-proof: before: 172 of 360 rows are open sky, every pixel in them black
  sky-proof: before: 120320 pixels are drawn by the world (not black)
  sky-proof: solid: the furthest open-sky pixel is 0 levels from the sky colour
  sky-proof: solid: 0 of the 120320 pixels the world draws changed
  sky-proof: gradient: the top row is at most 0 levels from the top colour
  sky-proof: gradient: every open-sky row is within 1 levels of the blend at its height
  sky-proof: night: every open-sky row is within 1 levels of the night blend
  sky-proof: none again: 0 pixels differ from the frame before any sky was set
  sky-proof: all checks passed; pictures in /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3c62f4afea1f5f50/moba/desktop/build/reports/udea/sky
```

### M3

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/RenderPipeline.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/RenderPipeline.kt
index c6031350..02569962 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/RenderPipeline.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/RenderPipeline.kt
@@ -163,7 +163,6 @@ public class RenderPipeline internal constructor(
         try {
             // The sky first, so every system draws over it. It records nothing at all while the
             // game has set none, which is how a frame with no sky stays the frame it always was.
-            sky?.paint(targets.batch, targets.offscreen)
 
             // Indexed loops: this is the per-frame path and an iterator per phase per frame is
             // garbage the collector has to deal with in the middle of drawing.
@@ -171,6 +170,8 @@ public class RenderPipeline internal constructor(
                 systems[index].render(targets.offscreen, alpha)
             }
 
+            sky?.paint(targets.batch, targets.offscreen)
+
             // ---- capture point (spec 3.7) ----
             // Claims the requests this frame satisfies. The read is `collect`'s, next frame.
             capture?.drain(targets.offscreen)
```

```
== M3
gradle EXIT=1
SkyTest: tests=6 failures=4 errors=0 skipped=0 timestamp=2026-09-21T04:33:09.396Z
  FAIL a sky changed between frames shows on the next frame, and None takes it away again()[jvm]  org.opentest4j.AssertionFailedError: expected: <[84418559, -16776961]> but was: <[-16776961, 84418559]>
  FAIL a solid sky is the first draw of the frame, covers all of it, and is its colour()[jvm]  org.opentest4j.AssertionFailedError: the sky is not under the game's draws ==> expected: <[1722217215, -16776961]> but was: <[-16776961, 1722217215]>
  FAIL a gradient sky covers the frame with a texture running from its top colour to its bottom one()[jvm]  org.opentest4j.AssertionFailedError: expected: <[-1, -16776961]> but was: <[-16776961, -1]>
  FAIL a gradient's texture is made once, and replaced and released only when the gradient changes()[jvm]  org.opentest4j.AssertionFailedError: expected: not same but was: <SpriteTexture('sky-test-white', 1x1)>
  pass an editor's Scene view draws the sky under the world, and its gizmos over both()[jvm]  
  pass with no sky set a frame records the game's draws and nothing else()[jvm]  
GlSkyTest: tests=1 failures=1 errors=0 skipped=0 timestamp=2026-09-21T04:33:09.974Z
  FAIL a sky shows wherever nothing is drawn, changes while running, and None is the black it always was()  org.opentest4j.AssertionFailedError: the centre of the frame is sky colour: the sky was drawn over the box
runSkyProof:
  > Task :udea-render:jvmTest FAILED
  > Task :udea-render:udeaGlTest FAILED
  > Task :moba:desktop:runSkyProof FAILED
  sky-proof: before: 172 of 360 rows are open sky, every pixel in them black
  sky-proof: before: 120320 pixels are drawn by the world (not black)
  sky-proof: solid: the furthest open-sky pixel is 0 levels from the sky colour
  sky-proof: solid: 120320 of the 120320 pixels the world draws changed
  Exception in thread "main" java.lang.IllegalArgumentException: 120320 of the world's 120320 pixels changed under the sky: the sky was drawn over the world, not behind it
```

### M4

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/sky/SkyPainter.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/sky/SkyPainter.kt
index 4e9d287c..d899aea2 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/sky/SkyPainter.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/sky/SkyPainter.kt
@@ -91,7 +91,7 @@ internal class SkyPainter(private val sky: Sky) : RenderResource {
             val top = gradient.top
             val bottom = gradient.bottom
             for (row in 0 until GRADIENT_ROWS) {
-                val t = row / (GRADIENT_ROWS - 1f)
+                val t = 1f - row / (GRADIENT_ROWS - 1f)
                 val colour = Rgba.of(
                     top.r + (bottom.r - top.r) * t,
                     top.g + (bottom.g - top.g) * t,
```

```
== M4
gradle EXIT=1
SkyTest: tests=6 failures=2 errors=0 skipped=0 timestamp=2026-09-21T04:33:26.852Z
  pass a sky changed between frames shows on the next frame, and None takes it away again()[jvm]  
  pass a solid sky is the first draw of the frame, covers all of it, and is its colour()[jvm]  
  FAIL a gradient sky covers the frame with a texture running from its top colour to its bottom one()[jvm]  org.opentest4j.AssertionFailedError: the top row is not the top colour ==> expected: <Rgba(0x05081fff)> but was: <Rgba(0xf28c4dff)>
  FAIL a gradient's texture is made once, and replaced and released only when the gradient changes()[jvm]  org.opentest4j.AssertionFailedError: expected: <Rgba(0x66a6f2ff)> but was: <Rgba(0xf28c4dff)>
  pass an editor's Scene view draws the sky under the world, and its gizmos over both()[jvm]  
  pass with no sky set a frame records the game's draws and nothing else()[jvm]  
GlSkyTest: tests=1 failures=1 errors=0 skipped=0 timestamp=2026-09-21T04:33:27.727Z
  FAIL a sky shows wherever nothing is drawn, changes while running, and None is the black it always was()  org.opentest4j.AssertionFailedError: the top row of a gradient sky at x=0 is [242, 140, 77], and the sky is [5, 8, 31] (red, green, blue)
runSkyProof:
  > Task :udea-render:jvmTest FAILED
  > Task :udea-render:udeaGlTest FAILED
  sky-proof: before: 172 of 360 rows are open sky, every pixel in them black
  sky-proof: before: 120320 pixels are drawn by the world (not black)
  sky-proof: solid: the furthest open-sky pixel is 0 levels from the sky colour
  sky-proof: solid: 0 of the 120320 pixels the world draws changed
  sky-proof: gradient: the top row is at most 166 levels from the top colour
  Exception in thread "main" java.lang.IllegalArgumentException: the top row of the gradient is 166 levels from [51, 107, 204]
  > Task :moba:desktop:runSkyProof FAILED
```

### M5

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/RenderPipeline.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/RenderPipeline.kt
index c6031350..9402a654 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/RenderPipeline.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/RenderPipeline.kt
@@ -287,7 +287,7 @@ public class RenderPipeline internal constructor(
         for (index in rigs.indices) rigs[index].enterView(camera.projection)
         try {
             // The Scene view is the world, and the world has a sky; the gizmos go over both.
-            sky?.paint(targets.batch, view.target)
+            // M5: no sky in the Scene view
             for (index in viewSystems.indices) viewSystems[index].render(view.target, alpha)
         } finally {
             for (index in rigs.indices) rigs[index].leaveView()
```

```
== M5
gradle EXIT=1
SkyTest: tests=6 failures=1 errors=0 skipped=0 timestamp=2026-09-21T04:33:45.329Z
  pass a sky changed between frames shows on the next frame, and None takes it away again()[jvm]  
  pass a solid sky is the first draw of the frame, covers all of it, and is its colour()[jvm]  
  pass a gradient sky covers the frame with a texture running from its top colour to its bottom one()[jvm]  
  pass a gradient's texture is made once, and replaced and released only when the gradient changes()[jvm]  
  FAIL an editor's Scene view draws the sky under the world, and its gizmos over both()[jvm]  org.opentest4j.AssertionFailedError: expected: <[-225686017, -16776961]> but was: <[-16776961]>
  pass with no sky set a frame records the game's draws and nothing else()[jvm]  
GlSkyTest: tests=1 failures=0 errors=0 skipped=0 timestamp=2026-09-21T04:33:46.298Z
  pass a sky shows wherever nothing is drawn, changes while running, and None is the black it always was()  
runSkyProof:
  > Task :udea-render:jvmTest FAILED
  sky-proof: before: 172 of 360 rows are open sky, every pixel in them black
  sky-proof: before: 120320 pixels are drawn by the world (not black)
  sky-proof: solid: the furthest open-sky pixel is 0 levels from the sky colour
  sky-proof: solid: 0 of the 120320 pixels the world draws changed
  sky-proof: gradient: the top row is at most 0 levels from the top colour
  sky-proof: gradient: every open-sky row is within 1 levels of the blend at its height
  sky-proof: night: every open-sky row is within 1 levels of the night blend
  sky-proof: none again: 0 pixels differ from the frame before any sky was set
  sky-proof: all checks passed; pictures in /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3c62f4afea1f5f50/moba/desktop/build/reports/udea/sky
```

### M6

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/sky/SkyPainter.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/sky/SkyPainter.kt
index 4e9d287c..2157f2ea 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/sky/SkyPainter.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/sky/SkyPainter.kt
@@ -57,7 +57,7 @@ internal class SkyPainter(private val sky: Sky) : RenderResource {
     private fun regionFor(gradient: SkyBackground.Gradient): SpriteRegion {
         val current = gradientRegion
         if (current != null && gradient == gradientFor) return current
-        current?.texture?.release()
+        // M6: the old gradient is kept
         val made = SpriteRegion(SpriteTexture.fromRgba(1, GRADIENT_ROWS, gradientRows(gradient), GRADIENT_NAME))
         gradientRegion = made
         gradientFor = gradient
```

```
== M6
gradle EXIT=1
SkyTest: tests=6 failures=1 errors=0 skipped=0 timestamp=2026-09-21T04:34:03.742Z
  pass a sky changed between frames shows on the next frame, and None takes it away again()[jvm]  
  pass a solid sky is the first draw of the frame, covers all of it, and is its colour()[jvm]  
  pass a gradient sky covers the frame with a texture running from its top colour to its bottom one()[jvm]  
  FAIL a gradient's texture is made once, and replaced and released only when the gradient changes()[jvm]  org.opentest4j.AssertionFailedError: the gradient no longer in use was not released ==> expected: <null> but was: <[5, 8, 31, -1, 6, 9, 31, -1, 7, 9, 
  pass an editor's Scene view draws the sky under the world, and its gizmos over both()[jvm]  
  pass with no sky set a frame records the game's draws and nothing else()[jvm]  
GlSkyTest: tests=1 failures=0 errors=0 skipped=0 timestamp=2026-09-21T04:34:04.868Z
  pass a sky shows wherever nothing is drawn, changes while running, and None is the black it always was()  
runSkyProof:
  > Task :udea-render:jvmTest FAILED
  sky-proof: before: 172 of 360 rows are open sky, every pixel in them black
  sky-proof: before: 120320 pixels are drawn by the world (not black)
  sky-proof: solid: the furthest open-sky pixel is 0 levels from the sky colour
  sky-proof: solid: 0 of the 120320 pixels the world draws changed
  sky-proof: gradient: the top row is at most 0 levels from the top colour
  sky-proof: gradient: every open-sky row is within 1 levels of the blend at its height
  sky-proof: night: every open-sky row is within 1 levels of the night blend
  sky-proof: none again: 0 pixels differ from the frame before any sky was set
  sky-proof: all checks passed; pictures in /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3c62f4afea1f5f50/moba/desktop/build/reports/udea/sky
```


## 8. Regenerated files

None. No component and no asset was added, so no lock, no `expected-generated-hashes.txt`, no `.udearep` and no `test_level.roster.txt` moved. `git diff --name-only 43b67c17..HEAD` is saved as `changed-files.txt` and lists 11 files, all in section 2's scope. `grep -E 'lock$|expected-generated-hashes|\.udearep$|roster\.txt$'` over it returns nothing (exit 1). The same grep over `git ls-files` (`tracked.txt`) counts 15, so the filter can see these files when they are there.

## 9. What I did not exercise

- A Windowed run by a person. Every picture here is `Offscreen`, which is the same capturable pass the window presents.
- An editor Scene tab on GL with a sky: that path is covered by `SkyTest` at the record level only.
- iOS and Android: `udea-render` has no iOS target, and the Android launcher runs headless.
