872b8db

# BRIEF-188: the HUD's drawing half on ComposeGL, in the capture

Branch `issue-188-hud-kool` from `origin/kmp` `5821d25` (the name `issue-188-hud-composegl` is held
by the stopped wave-8 worktree `agent-a3e7f21c806774c0d`; commented on #188). The SHA above is the
code under review; this brief is committed on top of it.

## 1. Evidence command

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew :moba:desktop:runMatchShot
```

(On a box with a display, `sh gradlew :moba:desktop:runMatchShot`.) It writes seven PNGs to
`moba/desktop/build/reports/udea/match/` and now **reads each one back**: one pixel of every solid
HUD panel that should be in that picture is compared with the colour `MobaHudLook` names. Any missing
panel prints `[match.shot] HUD missing: ...` and exits 1 (the PNGs are still written).

Green on `872b8db`, from `scratchpad/issue188/matchshot-1.log` (lines filtered with
`grep -E "^\[match.shot\]|^BUILD"`, nothing else removed):

```
[match.shot] wrote /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a80b8fdec32c67256/moba/desktop/build/reports/udea/match/item_bar.png 1280x720 at tick 24 - two item actives on the bar, granted at tick 21 | alive=27 score orc=5 soldier=12 undead=10
[match.shot] wrote /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a80b8fdec32c67256/moba/desktop/build/reports/udea/match/item_fired.png 1280x720 at tick 72 - the item bar cooling down, fired from moba/item_1 at tick 69 | alive=27 score orc=5 soldier=12 undead=10
[match.shot] wrote /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a80b8fdec32c67256/moba/desktop/build/reports/udea/match/melee.png 1280x720 at tick 421 - the melee, camera on the player | alive=31 score orc=5 soldier=11 undead=9
[match.shot] wrote /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a80b8fdec32c67256/moba/desktop/build/reports/udea/match/spin.png 1280x720 at tick 427 - the spin, fired from the bound key, activated at tick 422 | alive=31 score orc=5 soldier=11 undead=9
[match.shot] wrote /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a80b8fdec32c67256/moba/desktop/build/reports/udea/match/hud.png 1280x720 at tick 561 - the HUD with a cooldown running | alive=24 score orc=2 soldier=8 undead=8
[match.shot] wrote /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a80b8fdec32c67256/moba/desktop/build/reports/udea/match/result.png 1280x720 at tick 5432 - match 1 won by team 1 decided on tick 5400 | alive=22 score orc=1 soldier=5 undead=1
[match.shot] wrote /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a80b8fdec32c67256/moba/desktop/build/reports/udea/match/dead.png 1280x720 at tick 5499 - the player killed at tick 5438, photographed dead | alive=21 score orc=1 soldier=5 undead=1
[match.shot] HUD present in all 7 captures
BUILD SUCCESSFUL in 1m 40s
```

**It goes red when the feature is reverted.** Two runs, each with the literal change from the run:

*Revert A - the HUD back on `origin/kmp`'s `BitmapFont2D` painter.* Made with
`git checkout origin/kmp -- moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaHud.kt moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaScene.kt moba/game/src/commonMain/kotlin/dev/wildware/moba/entry/MobaEntry.kt moba/desktop/src/main/kotlin/dev/wildware/moba/entry/MobaLaunch.kt`
(the harness and `MobaHudScreen.kt`, which holds `MobaHudLook`, stay). `git diff --cached --stat`:

```
 .../kotlin/dev/wildware/moba/entry/MobaLaunch.kt   |   2 +-
 .../commonMain/kotlin/dev/wildware/moba/MobaHud.kt | 429 +++++++++++++++++++--
 .../kotlin/dev/wildware/moba/MobaScene.kt          |  16 +-
 .../kotlin/dev/wildware/moba/entry/MobaEntry.kt    |   4 +-
 4 files changed, 398 insertions(+), 53 deletions(-)
```

`matchshot-mutA.log` (filtered with `grep -E "HUD missing|^BUILD|runMatchShot FAILED"`): all 14 panel probes fail, because the stand-in's
panels are translucent and placed differently.

```
[match.shot] HUD missing: item_bar.png has no score strip: (2, 20) is #1C1E16, the panel is #140F0F
[match.shot] HUD missing: item_bar.png has no player panel: (20, 700) is #2D4353, the panel is #140F0F
[match.shot] HUD missing: item_fired.png has no score strip: (2, 20) is #1D1F16, the panel is #140F0F
[match.shot] HUD missing: item_fired.png has no player panel: (20, 700) is #2E4454, the panel is #140F0F
[match.shot] HUD missing: melee.png has no score strip: (2, 20) is #1D1F16, the panel is #140F0F
[match.shot] HUD missing: melee.png has no player panel: (20, 700) is #2D4353, the panel is #140F0F
[match.shot] HUD missing: spin.png has no score strip: (2, 20) is #1E2017, the panel is #140F0F
[match.shot] HUD missing: spin.png has no player panel: (20, 700) is #2D4353, the panel is #140F0F
[match.shot] HUD missing: hud.png has no score strip: (2, 20) is #1D1F16, the panel is #140F0F
[match.shot] HUD missing: hud.png has no player panel: (20, 700) is #2D4353, the panel is #140F0F
[match.shot] HUD missing: result.png has no score strip: (2, 20) is #1C1E16, the panel is #140F0F
[match.shot] HUD missing: result.png has no result banner: (2, 272) is #192930, the panel is #0F1A2E
[match.shot] HUD missing: dead.png has no score strip: (2, 20) is #1C1E16, the panel is #140F0F
[match.shot] HUD missing: dead.png has no result banner: (2, 272) is #192930, the panel is #0F1A2E
[match.shot] HUD missing: dead.png has no death banner: (2, 360) is #4B2214, the panel is #4D0505
> Task :moba:desktop:runMatchShot FAILED
BUILD FAILED in 2m 13s
```

*Revert B - the engine hook removed, so the ComposeGL HUD still composes but never reaches the
capture* (the failure a HUD in a `UiLayer` would have):

```diff
diff --git a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolSurface.kt b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolSurface.kt
index a1ebf4d..16cc0ff 100644
--- a/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolSurface.kt
+++ b/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/kool/KoolSurface.kt
@@ -156,7 +156,7 @@ internal class KoolSurface(
     override fun addOnTop(name: String, draw: () -> Unit): RenderPass.View =
         pass.createView(name, pixelCamera()).apply {
             drawNode = Node(name)
-            onSetupView(draw)
+            // onSetupView(draw)
         }
 
     override fun removeOnTop(view: RenderPass.View) {
```

```
[match.shot] HUD missing: item_bar.png has no score strip: (2, 20) is #416536, the panel is #140F0F
[match.shot] HUD missing: item_bar.png has no player panel: (20, 700) is #4B683A, the panel is #140F0F
[match.shot] HUD missing: item_fired.png has no score strip: (2, 20) is #466A39, the panel is #140F0F
[match.shot] HUD missing: item_fired.png has no player panel: (20, 700) is #50743E, the panel is #140F0F
[match.shot] HUD missing: melee.png has no score strip: (2, 20) is #466A39, the panel is #140F0F
[match.shot] HUD missing: melee.png has no player panel: (20, 700) is #4B6F3B, the panel is #140F0F
[match.shot] HUD missing: hud.png has no score strip: (2, 20) is #466A39, the panel is #140F0F
[match.shot] HUD missing: hud.png has no player panel: (20, 700) is #4B6F3B, the panel is #140F0F
[match.shot] HUD missing: spin.png has no score strip: (2, 20) is #466A39, the panel is #140F0F
[match.shot] HUD missing: spin.png has no player panel: (20, 700) is #4B6F3B, the panel is #140F0F
[match.shot] HUD missing: result.png has no score strip: (2, 20) is #416536, the panel is #140F0F
[match.shot] HUD missing: result.png has no result banner: (2, 272) is #416536, the panel is #0F1A2E
[match.shot] HUD missing: dead.png has no score strip: (2, 20) is #416536, the panel is #140F0F
[match.shot] HUD missing: dead.png has no result banner: (2, 272) is #416536, the panel is #0F1A2E
[match.shot] HUD missing: dead.png has no death banner: (2, 360) is #466A39, the panel is #4D0505
> Task :moba:desktop:runMatchShot FAILED
BUILD FAILED in 1m 40s
```

Both restored with `git checkout`; the tree was clean before the build below.

## 2. Summary

**What changed.** The HUD's drawing half (`HudPainter`, `BitmapFont2D` glyphs through the sprite
batch, #212's stand-in) is replaced by `MobaHudScreen`, a ComposeGL `UiScreen` of plain composables:
vitals label, health and mana rails, four slots with key, shutter and seconds, the ability-name column,
score strip, result banner, death banner with respawn countdown. `HudState` and `MobaHudModel` are
byte-identical to `origin/kmp` (the 295 lines from `public class HudState` to the drawing half were
extracted from both and `diff`ed: identical). `MobaHudTest` is unchanged (0 diff lines).

**Which layer the HUD draws to, and why screenshots include it.** `UiLayer` (#224) draws into the
window after the presented frame, so nothing it draws can be captured - right for menus and the
editor, wrong for a HUD. The HUD goes through a new `udea-render` class, `CapturedUi`
(`RenderResources.capturedUi(fonts)`): it adds a second Kool view to the capturable `OffscreenPass2d`
(`ScenePasses.addOnTop`). Kool renders a pass's views in order into one framebuffer, so when that
view's setup hook runs, every `RenderSystem` has drawn and the pass is bound; ComposeGL's public
`UiHost` + `UiRenderer` + `KoolCanvas` draw there, into "whatever framebuffer the host has bound".
The HUD therefore sits on top of the whole frame in every capture and in the window (which presents
the pass). The agent overlay and `UiLayer` still draw into the window only. No ComposeGL change was
needed (#224's note had assumed one).

**Decisions** (each commented on #188):
- `CapturedUi` rather than `UiLayer` or a ComposeGL texture drawn back through `SpriteBatch2D` (that
  batch blends straight alpha; the toolkit writes premultiplied, so translucent panels would darken).
- Every HUD part is a plain composable; no `composegl-game` (`Bar`, `Hotbar`): its cooldown is a
  UI-owned timer (a second clock through `time.rewind`) and `Hotbar` takes input. No
  `libs.versions.toml` change.
- Panels are opaque (the stand-in's were ~80%): readable over any melee, and an exact colour a
  capture can be checked for.
- Fonts come from the launcher: `MobaScene.build(definition, hudFonts: () -> UiFonts)`; the factory
  runs once on the render thread and `MobaHudSystem` owns and closes the result. `moba:desktop`'s
  `mobaHudFonts()` loads DejaVu Sans (a third copy of the file `udea-editor` and `udea-render`'s tests
  ship; licence beside it) at the HUD's three sizes.
- `runMatchShot` gained `dead.png`: after `result.png` is written the player's health is zeroed (as
  `MobaHudTest.killPlayer` does) and the frame 60 ticks later is captured, while the result banner is
  still standing.

**Not decided here / worth knowing.** The capture ticks in `runMatchShot` are frame-paced, not fixed:
the spin shot landed at tick 427 on this branch and at 1359 on `origin/kmp`'s harness in my before-run
(item_bar 24 vs 25, melee 421 vs 422). The harness taps keys per rendered frame, so frame timing moves
the tick a tap lands on; I have not established a cause beyond that and make no claim that the ticks are
stable. `moba:android` still boots headless (no HUD there) - unchanged.

**Tests written for this** (verified red by mutation; diffs below):
- `udea-render` `GlCapturedUiTest` (GL, xvfb): a captured screen is in the capture, over the world,
  upright (not mirrored), blended source-over at 50% alpha, live on a Compose state change, gone on
  `show(null)`.
- `moba:game` `MobaHudScreenTest` (9 tests, `HeadlessBackend`, no GL): each number reaches the screen,
  bar lengths are the fractions, a changed number changes on screen, dead/empty/no-match states, solid
  panels in `MobaHudLook`'s colours. Ported from the wave-8 branch's test, adjusted for this layout.

| mutation | literal diff | result |
|---|---|---|
| engine hook off (GL test) | same diff as Revert B above | `GlCapturedUiTest` red: `the opaque panel is not in the capture ==> expected: <12986408> but was: <255>` (from `gl-red-nohook.log`'s XML report) |
| `refresh` no longer re-runs `content` | below | 2 of 9 red |
| rail fill not scaled by the fraction | below | 2 of 9 red |

`GlCapturedUiTest` with the hook off, `gl-red-nohook.log` (filtered `GlCapturedUiTest|FAILED|^BUILD`):

```
GlCapturedUiTest > a captured screen is in the capture, over the world, upright and blended() FAILED
    org.opentest4j.AssertionFailedError at GlCapturedUiTest.kt:107
> Task :udea-render:udeaGlTest FAILED
BUILD FAILED in 38s
```

```diff
diff --git a/moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaHudScreen.kt b/moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaHudScreen.kt
index de3759f..924b597 100644
--- a/moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaHudScreen.kt
+++ b/moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaHudScreen.kt
@@ -91,7 +91,7 @@ internal class MobaHudScreen(
     @Composable
     override fun content() {
         // Read for its effect: this read is what re-runs `content` after `refresh`.
-        frame.longValue
+        // frame.longValue
         Box(Modifier.fillMaxSize()) {
             // Three states, not two. `alive` and `died` are both false in a world that has never had
             // a player in it - `MobaShot` stands the roster up and seeds no level - so an `else` here
```

```
> Task :moba:game:jvmTest FAILED
MobaHudScreenTest[jvm] > a number that changes between frames changes on screen()[jvm] FAILED
MobaHudScreenTest[jvm] > the score is drawn while the fight is on, and the winner once it is decided()[jvm] FAILED
BUILD FAILED in 14s
```

```diff
diff --git a/moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaHudScreen.kt b/moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaHudScreen.kt
index de3759f..5c5636b 100644
--- a/moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaHudScreen.kt
+++ b/moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaHudScreen.kt
@@ -221,7 +221,7 @@ private fun Vitals(unitName: String, health: Int, maxHealth: Int) {
 private fun Rail(tag: String, filled: Float, colour: Colour) {
     Box(Modifier.testTag(tag).size(MobaHudScreen.BAR_WIDTH, BAR_HEIGHT).background(TRACK)) {
         if (filled > 0f) {
-            Box(Modifier.size(MobaHudScreen.BAR_WIDTH * filled, BAR_HEIGHT).background(colour))
+            Box(Modifier.size(MobaHudScreen.BAR_WIDTH, BAR_HEIGHT).background(colour))
         }
     }
 }
```

```
> Task :moba:game:jvmTest FAILED
MobaHudScreenTest[jvm] > a living player reads their unit and health, and the bar is as long as the health()[jvm] FAILED
MobaHudScreenTest[jvm] > a unit with mana gets a mana bar, and one without does not()[jvm] FAILED
BUILD FAILED in 11s
```

## 3. Build output

`sh gradlew build --continue` on `872b8db`'s code (`build-1.log`, filtered `FAILED|^BUILD|actionable`):

```
HeadlessHostTest[jvm] > time pause stops a free-running host, and its ticks are the loop's()[jvm] FAILED
> Task :udea-core:allTests FAILED
BUILD FAILED in 3m 12s
929 actionable tasks: 571 executed, 232 from cache, 126 up-to-date
```

The one failure is in `udea-core`, which this branch does not touch (`git diff --name-only origin/kmp -- udea-core` is empty):
`expected: <6864> but was: <6863>` on a free-running host's tick count, with the box at load average
~35 from other builds. Re-run alone (`sh gradlew :udea-core:jvmTest --tests '*HeadlessHostTest*' --rerun`,
`headlesshost-solo.log`):

```
BUILD SUCCESSFUL in 9s
```

Then the whole build again (`build-2.log`):

```
BUILD SUCCESSFUL in 4s
920 actionable tasks: 17 executed, 3 from cache, 900 up-to-date
```

The task totals differ between the two runs (929 and 920); I have not established why, and the lead's
baseline figure (928) is from a different run. Every task in both runs is green except the flake above.

GL, as required (`gl-full.log`, filtered `Task :.*GlTest|^BUILD|actionable`):

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true
```

```
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest
BUILD SUCCESSFUL in 1m 19s
121 actionable tasks: 11 executed, 110 up-to-date
```

Read from the JUnit XML after that run: `udea-render` udeaGlTest 16 classes, 17 tests, 0 skipped, 0
failures; `udea-agent-host` udeaAgentGlTest 2 classes, 2 tests, 0 skipped, 0 failures.
Also run: `sh gradlew udeaVerifyModuleGraph udeaVerifyAgentsMd` green, and `:moba:desktop:runLaneShot`
green (the HUD is now in the lane pictures too). `:moba:desktop:runUdpProof` not run: nothing here
touches the network path.

## 4. Images

All in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`:

- `issue188-before-after.png` - left `origin/kmp`'s stand-in HUD, right this branch, at hud / item_fired / result. Same numbers, real type.
- `issue188-after-hud.png` - health 330/750, Q cooling 12.7s, both item slots 6.8s, names, score.
- `issue188-after-dead.png` - the corpse state: YOU DIED, back in 2.0s, under the result banner.
- `issue188-after-melee.png`, `issue188-after-spin.png`, `issue188-after-item_bar.png`, `issue188-after-item_fired.png`, `issue188-after-result.png` - the rest of the run above.
- `issue188-before-hud.png` - the stand-in at the same tick (note its Q letter hidden under the shutter; the new HUD draws the key on top).
- `issue188-gl-captured-ui.png` - `GlCapturedUiTest`'s capture: opaque panel top-left, 50% yellow over blue bottom-right, glyphs.
- `issue188-agent-screenshot-alive.png`, `issue188-agent-screenshot-dead.png` - `render.screenshot` from a live `:moba:desktop:run -PdebugPort=7841` session: the agent's own picture carries the HUD (empty item slots dimmed; then a real death with the countdown).
- `issue188-mutation-reverted-hud.png`, `issue188-mutation-nohook-hud.png` - `hud.png` from Revert A and Revert B.

Earlier `issue188-*-scene2d-*` / `*-composegl-*` files in that folder are the stopped wave-8 run, not this branch.

## 5. Acceptance criteria

- **The HUD draws through ComposeGL; no scene2d import in `moba`; `BitmapFont2D` HUD replaced.**
  `grep -rn "scene2d\|BitmapFont2D" moba --include='*.kt'` finds four KDoc lines of history
  (`MobaHud.kt`, `MobaHudScreen.kt`, `MobaHudScreenTest.kt`) and no code;
  `grep -rn "^import.*\(scene2d\|BitmapFont2D\|badlogic\)" moba --include='*.kt'` finds 0 lines. Drawing is `MobaHudScreen` via `CapturedUi`. Proof: `MobaHudScreenTest`,
  the pictures, and Revert A turning the evidence red.
- **`MobaHudTest` unchanged; `HudState` / `MobaHudModel.sample` unchanged.** 0-line diff for the test;
  the 295-line model section identical to `origin/kmp`; `MobaHudTest` green in the build.
- **`runMatchShot` PNGs show the HUD (health, abilities, cooldowns, corpse state) and are posted.**
  The evidence run above, `issue188-after-hud.png` and `issue188-after-dead.png`, the harness's
  panel check, and the dashboard posts.
- **Lead: screenshots must include the HUD; say which layer.** Section 2; `GlCapturedUiTest`;
  the agent `render.screenshot` images.

## 6. Regenerated files

None. No replicated component changed; `net-protocol.lock` and `expected-generated-hashes.txt` untouched.
