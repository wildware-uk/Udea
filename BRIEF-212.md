32c8be3

# #212: `moba` split into `:moba:game`, `:moba:desktop` and `:moba:android`, on Kool

The SHA above is the last commit of the change. The commit that adds this file sits on top of it and touches `BRIEF-212.md` only.
- Branch: `issue-212-moba-split`.
- Base: `origin/kmp` `21232ff`, which it has merged, including #221.
- Worktree: `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3cbf6ce8ad2f0116`.
- Every file this brief names without a path is in `/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue212/`.

## 1. The evidence command

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew :moba:desktop:runShot :moba:desktop:runLaneShot :moba:desktop:runMatchShot \
  :moba:android:assembleDebug udeaVerifyModuleGraph
```

One command covers all four criteria:
- It renders the ten parity scenes on Kool.
- It assembles the APK, which also compiles `:moba:game` for Android.
- It runs the module-graph gate. That includes `UDEA-MG-009`: no `moba` project may resolve LibGDX.

**Green at 32c8be3** (run 6). From `parity/branch-run6.log`:

```
[moba.shot] /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3cbf6ce8ad2f0116/moba/desktop/build/reports/udea/roster.png 1280x720 at tick 19, 6 characters
[... 149 lines of parity/branch-run6.log elided ...]
[lane.shot] wrote /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3cbf6ce8ad2f0116/moba/desktop/build/reports/udea/lane/wave.png 1280x720 at tick 272 - wave 1 walking the lane | wave=1 creeps=6 bodies=0 towers=0 gold=0 cs=0 level=1
[lane.shot] wrote /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3cbf6ce8ad2f0116/moba/desktop/build/reports/udea/lane/farm.png 1280x720 at tick 399 - the champion in the lane | wave=1 creeps=6 bodies=0 towers=3 gold=0 cs=0 level=1
[lane.shot] wrote /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3cbf6ce8ad2f0116/moba/desktop/build/reports/udea/lane/clash.png 1280x720 at tick 643 - the lines met: 6 creeps, 1 down | wave=1 creeps=6 bodies=1 towers=8 gold=0 cs=0 level=1
[... 148 lines of parity/branch-run6.log elided ...]
[match.shot] wrote /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3cbf6ce8ad2f0116/moba/desktop/build/reports/udea/match/item_bar.png 1280x720 at tick 24 - two item actives on the bar, granted at tick 21 | alive=27 score orc=5 soldier=12 undead=10
[match.shot] wrote /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3cbf6ce8ad2f0116/moba/desktop/build/reports/udea/match/item_fired.png 1280x720 at tick 72 - the item bar cooling down, fired from moba/item_1 at tick 69 | alive=27 score orc=5 soldier=12 undead=10
[match.shot] wrote /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3cbf6ce8ad2f0116/moba/desktop/build/reports/udea/match/melee.png 1280x720 at tick 421 - the melee, camera on the player | alive=31 score orc=5 soldier=11 undead=9
[match.shot] wrote /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3cbf6ce8ad2f0116/moba/desktop/build/reports/udea/match/hud.png 1280x720 at tick 561 - the HUD with a cooldown running | alive=24 score orc=2 soldier=8 undead=8
[match.shot] wrote /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3cbf6ce8ad2f0116/moba/desktop/build/reports/udea/match/spin.png 1280x720 at tick 1344 - the spin, fired from the bound key, activated at tick 1341 | alive=15 score orc=1 soldier=5 undead=4
[match.shot] wrote /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3cbf6ce8ad2f0116/moba/desktop/build/reports/udea/match/result.png 1280x720 at tick 5432 - match 1 won by team 1 decided on tick 5400 | alive=24 score orc=1 soldier=5 undead=1
[... 9 lines of parity/branch-run6.log elided ...]
BUILD SUCCESSFUL in 1m 38s
[... 2 lines of parity/branch-run6.log elided ...]
exit 0
```

**Red at 32c8be3 with LibGDX put back on the game.** This mutation restores the real old shape: `origin/kmp`'s `moba/build.gradle.kts:88` was `implementation(libs.gdx)`. The literal diff, from `mutation-gdx-2.diff`:

```diff
diff --git a/moba/game/build.gradle.kts b/moba/game/build.gradle.kts
index 0c800af..5d29db0 100644
--- a/moba/game/build.gradle.kts
+++ b/moba/game/build.gradle.kts
@@ -163,6 +163,7 @@ kotlin {
                 // they are on this module's own bytecode and cannot be `compileOnly`.
                 implementation(project(":udea-annotations"))
 
+                implementation(libs.gdx)
                 implementation(project(":udea-gas"))
                 implementation(project(":udea-net"))
                 implementation(project(":udea-assets"))
```

From `evidence-red-gdx-2.log`:

```
> Task :moba:desktop:udeaVerifyModuleGraph FAILED
[... 14 lines of evidence-red-gdx-2.log elided ...]
> Task :moba:android:udeaVerifyModuleGraph FAILED
[... 12 lines of evidence-red-gdx-2.log elided ...]
  UDEA-MG-009 :moba:desktop runtimeClasspath -> com.badlogicgames.gdx:gdx
[... 2 lines of evidence-red-gdx-2.log elided ...]
  UDEA-MG-009 :moba:desktop runtimeClasspath -> com.badlogicgames.gdx:gdx-jnigen-loader
[... 15 lines of evidence-red-gdx-2.log elided ...]
  UDEA-MG-009 :moba:android debugRuntimeClasspath -> com.badlogicgames.gdx:gdx
[... 2 lines of evidence-red-gdx-2.log elided ...]
  UDEA-MG-009 :moba:android debugRuntimeClasspath -> com.badlogicgames.gdx:gdx-jnigen-loader
[... 2 lines of evidence-red-gdx-2.log elided ...]
  UDEA-MG-009 :moba:android releaseRuntimeClasspath -> com.badlogicgames.gdx:gdx
[... 2 lines of evidence-red-gdx-2.log elided ...]
  UDEA-MG-009 :moba:android releaseRuntimeClasspath -> com.badlogicgames.gdx:gdx-jnigen-loader
[... 16 lines of evidence-red-gdx-2.log elided ...]
BUILD FAILED in 5s
[... 2 lines of evidence-red-gdx-2.log elided ...]
exit 1
```

The game's own gate, run alone under the same mutation on an earlier commit (`mutation-gdx-game.log`), reports every target classpath of `:moba:game` as well:

```
> Task :moba:game:udeaVerifyModuleGraph FAILED
[... 8 lines of mutation-gdx-game.log elided ...]
  UDEA-MG-009 :moba:game androidCompileClasspath -> com.badlogicgames.gdx:gdx
[... 2 lines of mutation-gdx-game.log elided ...]
  UDEA-MG-009 :moba:game androidCompileClasspath -> com.badlogicgames.gdx:gdx-jnigen-loader
[... 2 lines of mutation-gdx-game.log elided ...]
  UDEA-MG-009 :moba:game androidRuntimeClasspath -> com.badlogicgames.gdx:gdx
[... 2 lines of mutation-gdx-game.log elided ...]
  UDEA-MG-009 :moba:game androidRuntimeClasspath -> com.badlogicgames.gdx:gdx-jnigen-loader
[... 2 lines of mutation-gdx-game.log elided ...]
  UDEA-MG-009 :moba:game jvmCompileClasspath -> com.badlogicgames.gdx:gdx
[... 2 lines of mutation-gdx-game.log elided ...]
  UDEA-MG-009 :moba:game jvmCompileClasspath -> com.badlogicgames.gdx:gdx-jnigen-loader
[... 2 lines of mutation-gdx-game.log elided ...]
  UDEA-MG-009 :moba:game jvmMainCompileClasspath -> com.badlogicgames.gdx:gdx
[... 2 lines of mutation-gdx-game.log elided ...]
  UDEA-MG-009 :moba:game jvmMainCompileClasspath -> com.badlogicgames.gdx:gdx-jnigen-loader
[... 2 lines of mutation-gdx-game.log elided ...]
  UDEA-MG-009 :moba:game jvmMainRuntimeClasspath -> com.badlogicgames.gdx:gdx
[... 2 lines of mutation-gdx-game.log elided ...]
  UDEA-MG-009 :moba:game jvmMainRuntimeClasspath -> com.badlogicgames.gdx:gdx-jnigen-loader
[... 2 lines of mutation-gdx-game.log elided ...]
  UDEA-MG-009 :moba:game jvmRuntimeClasspath -> com.badlogicgames.gdx:gdx
[... 2 lines of mutation-gdx-game.log elided ...]
  UDEA-MG-009 :moba:game jvmRuntimeClasspath -> com.badlogicgames.gdx:gdx-jnigen-loader
[... 2 lines of mutation-gdx-game.log elided ...]
  UDEA-MG-009 :moba:game metadataCompileClasspath -> com.badlogicgames.gdx:gdx
[... 2 lines of mutation-gdx-game.log elided ...]
  UDEA-MG-009 :moba:game metadataCompileClasspath -> com.badlogicgames.gdx:gdx-jnigen-loader
[... 15 lines of mutation-gdx-game.log elided ...]
BUILD FAILED in 10s
```

**Red on `origin/kmp` itself.** I ran the same command on a `git archive` of `origin/kmp` `0520f27`, the base this branch started from. From `evidence-red-kmp.log`:

```
* What went wrong:
Cannot locate tasks that match ':moba:desktop:runShot' as project 'desktop' not found in project ':moba'.
[... 15 lines of evidence-red-kmp.log elided ...]
BUILD FAILED in 30s
```

That red is cheap, because the projects do not exist there. Before this branch, `moba`'s own compile was the baseline red. From `baseline.log`, taken before my first change:

```
> Task :moba:compileKotlin FAILED
[... 46 lines of baseline.log elided ...]
BUILD FAILED in 1m 43s
```

Each mutation was reverted with `git checkout`. `git status` is clean except for `gradlew`'s mode bit, which is never staged.

## 2. Summary

`moba` was one JVM module with a LibGDX renderer and three `main`s. It had been red since #211 took LibGDX out of `udea-render`. Following spec D12, it is now three projects:

- **`:moba:game`** is on `udea.kotlin-multiplatform-render`, so it builds for jvm and android. It holds the components, systems, assets, HUD and scene, and it has no entry point.
  - KSP runs as `kspJvm`, and its output goes on `commonMain`. The Kotlin plugin creates no `commonMain` metadata compilation for an all-JVM-family target list, so `kspCommonMainKotlinMetadata` does not exist here. The build script explains this.
  - Its `net-protocol.lock` is byte-identical to `origin/kmp:moba/net-protocol.lock`. It moved and did not change.
- **`:moba:desktop`** is the JVM launcher. It holds:
  - `run`, `runServer`, `runClient`;
  - the shot mains and the proofs;
  - the replay tooling, because `udea-replay` generates its registry on its JVM target only;
  - the agent source set;
  - the release gate.
- **`:moba:android`** is a debug APK with one activity. It boots the simulation headless, because `udea-render` has no Android Kool backend yet.

What had to change to get there, beyond moving files:

- **Sound goes through #221's `koolAudioDevice`**, as the coordinator asked. See the audio part below.
- **The HUD** is ported from scene2d onto `udea-render`'s `BitmapFont2D`, as approved on the issue. **This is an interim rendering, and #188 owns the final version.** `HudState` is untouched.
- **Key codes.** `moba` now writes Kool's key codes rather than LibGDX's. W is `'W'.code` (87), where LibGDX's W was 51, which is GLFW's `3`. This is backend-specific, and **#228** is the engine-owned table that fixes it. Measured, not assumed:
  - dev-224 measured 87 under lwjgl 3.4.3.
  - `KoolKeyboard` reads the universal code.
  - Special keys become Kool's own negative codes: Escape is -9, not GLFW's 256.
  - **What breaks if the backend changes:** every binding in `moba/game/assets/control/controls.udea.kts` and `MobaControls.Keys` silently answers to a different key. Nothing fails to compile or validate.
  - `MobaKeyBindingTest` covers each bound key. It also has two negatives holding the old LibGDX codes, so the test measures the codes rather than restating them.
- **The keyboard is wired.** #224 landed during this ticket. `MobaLaunch.wireInput` now takes the `KeyboardState`, and every GL entry point passes it the `KoolKeyboard` built on the render thread. `moba` binds no pointer button, so #227's `KoolPointer` is not wired: nothing would read it.
- **The agent overlay** is null for now (`MobaAgent.overlayFor`). `AgentOverlaySystem` and `GdxOverlayKey` went with #211. This is recorded in the KDoc.
- **`androidMinSdk` is 26**, raised from 24 in the catalog. `composegl-kool-android`, which arrived with #224, declares 26, and `:moba:android` is the first application to resolve it.

### Audio (#221), as the coordinator asked

- **Who picks the device.** `MobaDesktopAudio.forHost` in `:moba:desktop`. `:moba:game` compiles for Android and cannot name `koolAudioDevice`, which is `jvmMain`. `MobaAudio` keeps `of(host, device)` and `silent(host)`, and `MobaAudio.forHost` is gone.
- **What it picks:**
  - **Headless:** `AudioDevice.Silent`, and no device is built.
  - **Windowed or Offscreen, with `-Dudea.assets.root`:** `koolAudioDevice(root)`. `runClient` now sets that property to `:moba:game`'s asset tree, because the `.udeapak` carries no audio bytes.
  - **No asset root** (a packaged game): silent, with one line saying why.
- **The fallback.** When a load throws `AudioLoadException` (no output, a missing file or a bad decode), the device is closed, one `[moba.audio] silent: ...` line carrying the device's own message goes to stderr, and the process continues on `AudioDevice.Silent`. The cue queue is drained either way. This is the "catch at the game root, say why" ruling from #221, and the KDoc says so.
- **Why `koolAudioDevice` stays public:** `moba` calls it. `MobaDesktopAudio`'s default `device` parameter is `::koolAudioDevice`, and all three `MobaClient` entry points go through it.
- **Not called:** Kool's own audio loader. `git grep -n "de.fabmax.kool" -- moba` finds nothing at all. The same grep over `udea-render` finds matches, so the search itself works.
- **`MobaAgent` stays silent.** `:moba:desktop:run` is an agent session, and it wants no sound.
- **Pitch and pan:** Kool ignores both, so on the desktop every cue plays at unit pitch and centred. No test in `moba` asserts pitch or pan: `git grep -n -E "\bpitch\b|\bpan\b" -- moba` finds only `play` signatures, comments and the authored data. So no test was weakened to fit, and none could have been. `MobaAudioTest` asserts which file each cue played, over a recording device. Its only change is a KDoc that named `GdxAudioDevice`.
- **The `MobaAudioProbe` `Gdx.audio` comment** went with the probe, which the split had already deleted.

**Failing test first.** `MobaDesktopAudioTest` has three tests: no output falls back to silence and says so once; a working device is used and nothing is logged; Headless never builds a device. It was red as a compile error before the class existed. From `audio-red.log`:

```
> Task :moba:desktop:compileTestKotlin FAILED
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3cbf6ce8ad2f0116/moba/desktop/src/test/kotlin/dev/wildware/moba/MobaDesktopAudioTest.kt:3:32 Unresolved reference 'MobaDesktopAudio'.
[... 27 lines of audio-red.log elided ...]
BUILD FAILED in 46s
```

It is green at 32c8be3. The JUnit XML that `build-9.log` wrote (`audio-test-at-head.txt`):

```
<testsuite name="dev.wildware.moba.MobaDesktopAudioTest" tests="3" skipped="0" failures="0" errors="0" timestamp="2026-09-19T00:45:27.142Z" hostname="wild-home-server" time="0.8">
```

The mutations are in §7.

**On this box, for real.** This machine has no audio output. `runClient --args="host 27115" -Dudea.net.frames=240` under xvfb, from `audio-live-host.log`:

```
[moba.client] moba 0.1.0 Windowed host
[moba.client] serving on /[0:0:0:0:0:0:0:0]:27115; tell the other player to run:
[moba.client]   ./gradlew :moba:desktop:runClient --args="join <this machine>:27115"
[moba.client] connecting to /127.0.0.1:27115
[... 145 lines of audio-live-host.log elided ...]
[moba.audio] silent: 'sounds/orc/orc_hurt_1.ogg' would not load (no audio output: Java Sound gave Kool no line to play 'sounds/orc/orc_hurt_1.ogg' on (No line matching interface Clip supporting format PCM_SIGNED unknown sample rate, 16 bit, stereo, 4 bytes/frame, big-endian is supported.). A process that should be silent uses AudioDevice.Silent.; IllegalArgumentException: No line matching interface Clip supporting format PCM_SIGNED unknown sample rate, 16 bit, stereo, 4 bytes/frame, big-endian is supported.), so this process plays through AudioDevice.Silent
[... 1 lines of audio-live-host.log elided ...]
[moba.client] connected as client1 at tick 3
[moba.client] you are net id 0; WASD to walk, Space to swing
[moba.client] server=120 units=27 applied=117 champions * peer1@0=(-74.06, 12.01)
[moba.client] server=240 units=27 applied=237 champions * peer1@0=(-74.06, 12.01)
[moba.client] server=240 units=27 applied=237 champions * peer1@0=(-74.06, 12.01)
[moba.client] done after 240 frame(s)
[... 10 lines of audio-live-host.log elided ...]
BUILD SUCCESSFUL in 9s
```

Port 27015, the default, was held by a process that is not mine, which is why 27115 was used. An unbounded `local` run printed the same fallback line and then kept playing, as `local` has no frame limit (`audio-live-client.log`). That limit exists only on the UDP path, on `origin/kmp` as well.

### Broken by the split and fixed here

- **`UDEA-MG-005` was scanning nothing.** It bans a scripting host and `kotlin-reflect` from the shipped game, and it still named the flat `:moba`, which no longer has a classpath.
  - I added `kotlin-reflect` to `:moba:desktop`. Under the old scope the gate stayed green (`mg005-negative-oldscope.log`: `BUILD SUCCESSFUL`). Under the new scope it goes red (`mg005-negative.log`: `UDEA-MG-005 :moba:desktop runtimeClasspath -> org.jetbrains.kotlin:kotlin-reflect`).
  - It now governs `MOBA_PROJECTS`, the same set as MG-009.
  - A new test, `ModuleGraphRulesTest > every rule governs at least one project settings_gradle_kts includes`, was written first. It failed with `UDEA-MG-005 governs only [:moba]` (`mg005-red.log`).
- **`sh gradlew -p build-logic check`**, which CI runs outside the root build, was red in `AgentsMdTest`, `CharacterArtStagingTest` and `KotlinClockTest`, each of which read the flat layout (`buildlogic-check-1.log`). All three now read the nested projects.
- **`LICENSE`** excluded `moba/assets/sprites/`. It now names `moba/game/assets/sprites/`. The art scripts moved with it, and so did the staging KDoc's past-tense record of `stage-moba-art.py`, which `scripts/test_verify_art_staging.py` requires (before: `FAILED (failures=1)`, `art-staging-unittest.log`; after: `OK`, `art-staging-unittest-3.log`).
- **Every gate and path that named `:moba` or `moba/...`:**
  - determinism scope, field-mask scan globs, build-logic inputs, the migration check;
  - the stdlib-pin classification of AGP's configurations, and `jvmRole` for Android variants;
  - the root `rewriteProjects` filter;
  - the replay fixtures and task names, `ci.yml`, and the conformance test;
  - `AGENTS.md`, `docs/module-graph.md`, `HANDOFF.md`, `README.md` and `docs/art-assets.md`.
- **The staging task** is now `:moba:game:udeaStageCharacterArt`, and `udea-assets-compiler` depends on it by that path.

### Decisions, each commented on #212

- The HUD port is interim.
- The parity threshold and its masks (§5). A later comment withdrew the MOVING tier's numeric line and demoted a SETTLED fail to "look".
- Web is omitted: `:moba:web` is #226, and no wasmJs target was added.
- The keyboard is wired.
- `minSdk` is 26.
- Audio is chosen in the desktop launcher, with a logged fallback to silence.

### Known and left alone

- **`udea-render`** is not edited by this branch: `git diff --stat origin/kmp...HEAD -- udea-render docs/contracts` is empty. My path sweep once touched `InputBindings.kt:7`, and `4cbf0cc` put it back. So its KDocs at `PresentationControl.kt:37` and `NoDeviceInSimTest.kt:31` still say `:moba:run` and `:moba:classes`, and `InputBindings.kt:7` still says `moba/assets/control/controls.udea.kts`.
- **`HANDOFF.md`** still says `runUdpProof` is red. #219 fixed that, and the note is not mine to rewrite.
- **`udea-assets-compiler`**'s default `udea.pack.sprites`, `moba/src/main/resources/assets/sprites`, did not exist before this branch either.
- **Historical mentions** of `:moba:run` and `:moba:udeaValidateAssets` in build-logic KDocs describe the past, and I left them as history.

## 3. `sh gradlew build`

`ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue` at 32c8be3, with nothing else building on the box. From `build-9.log`:

```
BUILD SUCCESSFUL in 2m 45s
952 actionable tasks: 751 executed, 12 from cache, 189 up-to-date
[... 1 lines of build-9.log elided ...]
exit 0
```

**Against the baseline:**
- **Red on the baseline and gone now:** `:moba:compileKotlin`. The task no longer exists: `sh gradlew :moba:compileKotlin` answers `Cannot locate tasks that match ':moba:compileKotlin' as task 'compileKotlin' not found in project ':moba'.` (`moba-compileKotlin-gone.log`).
- **Green on the baseline and red now:** none. The build above has no failed task.

**Outside `check`, at 32c8be3:**
- `udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd udeaVerifyMigration udeaLegacyReport`, from `gates-2.log`:

```
BUILD SUCCESSFUL in 5s
194 actionable tasks: 12 executed, 182 up-to-date
```

- `sh gradlew -p build-logic check`, from `buildlogic-check-4.log`:

```
BUILD SUCCESSFUL in 58s
13 actionable tasks: 3 executed, 10 up-to-date
```

- `:moba:desktop:runUdpProof` was not run. It is outside the scope of this ticket.

**GL, run for real** under xvfb with llvmpipe at 32c8be3:

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true
```

From `gltest-3.log`, followed by the per-suite counts read out of the JUnit XML into `gltest-3-suites.txt` by `glsuites.py`:

```
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest
[... 9 lines of gltest-3.log elided ...]
BUILD SUCCESSFUL in 50s
# udea-render/build/test-results/udeaGlTest: 11 suites
dev.wildware.udea.render.gl.GlCaptureDeterminismTest tests=1 skipped=0 failures=0 errors=0 at 2026-09-19T00:46:49.916Z
dev.wildware.udea.render.gl.GlCaptureTest tests=1 skipped=0 failures=0 errors=0 at 2026-09-19T00:46:52.692Z
dev.wildware.udea.render.gl.GlKoolInputTest tests=1 skipped=0 failures=0 errors=0 at 2026-09-19T00:46:57.677Z
dev.wildware.udea.render.gl.GlKoolPointerTest tests=1 skipped=0 failures=0 errors=0 at 2026-09-19T00:47:08.232Z
dev.wildware.udea.render.gl.GlOverlayIsolationTest tests=1 skipped=0 failures=0 errors=0 at 2026-09-19T00:47:13.992Z
dev.wildware.udea.render.gl.GlUiLayerTest tests=1 skipped=0 failures=0 errors=0 at 2026-09-19T00:47:17.541Z
dev.wildware.udea.render.gl.KoolThreadShutdownTest tests=1 skipped=0 failures=0 errors=0 at 2026-09-19T00:47:19.798Z
dev.wildware.udea.render.gl.OffscreenBackendExplodingCaptureTest tests=1 skipped=0 failures=0 errors=0 at 2026-09-19T00:47:21.799Z
dev.wildware.udea.render.gl.OffscreenBackendSecondCreateTest tests=1 skipped=0 failures=0 errors=0 at 2026-09-19T00:47:23.934Z
dev.wildware.udea.render.gl.OffscreenBackendShutdownTest tests=1 skipped=0 failures=0 errors=0 at 2026-09-19T00:47:25.120Z
dev.wildware.udea.render.gl.OffscreenBackendTest tests=2 skipped=0 failures=0 errors=0 at 2026-09-19T00:47:27.129Z
# udea-agent-host/build/test-results/udeaAgentGlTest: 2 suites
dev.wildware.udea.agent.host.gl.OffscreenRenderToolsTest tests=1 skipped=0 failures=0 errors=0 at 2026-09-19T00:46:49.587Z
dev.wildware.udea.agent.host.gl.OverlayCaptureIsolationTest tests=1 skipped=0 failures=0 errors=0 at 2026-09-19T00:46:54.426Z
TOTAL tests=14 skipped=0 failures=0 errors=0
```

iOS was not built or tested. It cannot be on this Linux box.

## 4. Images

Every image is in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`.

- **`issue212-parity-all.png`:** all ten scenes, **unmasked**, with master `409c044` on the left and this branch's run 6 on Kool on the right. This is the primary parity evidence.
- **`issue212-parity-<scene>.png`**, for each of `roster`, `wave`, `farm`, `clash`, `item_bar`, `item_fired`, `melee`, `hud`, `spin` and `result`, all from run 6. Each shows master, then the branch, then the world diff after camera alignment: differing pixels in red, masked UI hatched grey.
- **`issue212-noise-two-branch-runs-spin.png`:** two runs of this branch, same scene, same tick, integer offset (0,0). Every sprite outline differs and no object does. This is why a SETTLED fail means "look" (§5).
- **`issue212-ref-*.png` and `issue212-references.png`:** the master reference captures (run 1), posted before any port work.
- **`issue212-live-agent-session.png`:** a live `:moba:desktop:run -PdebugPort=7841` agent instance in Offscreen, driven over the bridge's tool surface.
  - It shows three frames:
    - tick 896;
    - 60 ticks of `input.set_axis moba/move (1,0)` with `time.step 60`, ending at tick 956;
    - `input.tap moba/attack_2` and `time.step 4`, ending at tick 960.
  - `/health` answered `"renderMode":"Offscreen"`, with 51 tools.
  - The bridge's `launch_instance` could not start it: its child Gradle picked up JDK 25. So I started the instance myself with `JAVA_HOME` set to 21 and drove it through the bridge. That run was at an earlier commit, `e2c0135`. Since then the agent source set has changed only in `MobaAssetTools.kt`, whose asset-root constant now points at `MobaLaunch`'s.

## 5. World parity: the threshold, the masks, and what they do not say

The owner ruled that the threshold applies to world content only. UI regions are masked and named. A UI difference is not a parity failure; a world difference is.

**Masked in both images**, in PNG coordinates `(x0,y0,x1,y1)` on 1280x720:

- `score-strip` `(0,0,1280,56)`: the MATCH and team-score bar.
- `hud-panel` `(0,536,560,720)`: the name, health and mana rails, the slot boxes with their key and cooldown text, and the ability-name column. It is the union of where master's scene2d HUD and this branch's interim `BitmapFont2D` HUD draw.
- `result-banner` `(0,224,1280,320)`: the "X WINS" banner, masked on `result` only.

A pixel differs when any channel moves by more than 24/255. The camera is aligned by the best integer offset within ±8 px.

**Why the scenes need tiers.** The shot harness polls and taps once per rendered frame. So the tick it captures, and the tick its scripted inputs land on, both move with frame pacing, on master as well as here. A second master run (run 2, at a load of about 13; **its timings are noisy, but its images are valid**) caught different ticks from master run 1 in seven of ten scenes. Its `melee` shows a different battle outright (`views/master-self-melee.png`). The camera also follows on wall time (`CameraRig.smoothingFactor(frameSeconds)`). So pixels are unaffected by load only for a fixed tick **and** a fixed camera.

**Tiers**, from `parity/verdict.py`, chosen per scene from the ticks in each shot log:

- **SETTLED** (same tick, integer offset (0,0)): **passes** when at most 1.0% of world pixels differ **and** no connected patch of differing pixels is over 1000 px. The fraction catches diffuse change. The patch catches a missing or moved object: erasing one creep moves the fraction by only 0.392%, but makes a 2870 px patch (`controls.log`).
- **MOVING** (ticks one apart, or a camera offset): printed as **`LOOK`** and judged by eye. **There is no numeric line.** I first set 2.5% here, and withdrew it:
  - Run 5 against master scored `item_fired` at 2.556% (`parity/verdict-run5-v1.log`).
  - Run 5 against run 6 scored it at 2.550% (`parity/verdict-v1-branch5-vs-branch6.log`). Between those two commits nothing that draws changed, only audio, docs and build logic. So the branch fails the old line against itself.
  - An earlier branch run against run 5 gives 2.667% (`parity/verdict-v1-branch1-vs-branch5.log`), though that pair is two different commits.
- **NOT JUDGED**: captures more than one tick apart, or a scripted harness action that landed on a different tick. These are shown side by side and never counted.

**A SETTLED fail is a reason to look, not a verdict.** An integer offset of (0,0) is not the same sub-pixel camera position. Two runs of this branch at identical ticks (`parity/verdict-v1-branch2-vs-branch5.log`) put:
- `item_fired` at 1.242% (patch 462);
- `spin` at 2.253% (patch 1377).

In the difference views (`parity/views/b2-vs-b5-*.png`, and `issue212-noise-two-branch-runs-spin.png`), every object is present and in place. Only grass speckle and one-pixel sprite outlines differ, and in a packed crowd those outlines join into one patch. So a SETTLED **pass** is evidence, and a SETTLED **fail** sends me to the difference view.

**The controls** were run before I trusted the tool (`controls.log`):
- identical frames: 0 differing pixels;
- a different scene: 26.7%;
- magenta painted over the masked UI: 0;
- a 100x100 magenta world patch: exactly 10000 px, as one 10000 px patch;
- one creep erased from `wave`: 0.392%, and a 2870 px patch.

The verdict's own known negative is `branch-negative`: run 1 with that creep erased (`parity/verdict-negative.log`):

```
PASS roster            tick    19/19    SETTLED world  0.084% patch     50 offset (+0,+0)  rule <= 1.0%, patch <= 1000
FAIL lane/wave         tick   272/272   SETTLED world  0.603% patch   2825 offset (+0,+0)  rule <= 1.0%, patch <= 1000
LOOK lane/farm         tick   398/399   MOVING  world  1.609% patch   1520 offset (-1,+0)  camera or tick moved: judged by eye, largest patch at (596, 329, 659, 385)
LOOK lane/clash        tick   642/643   MOVING  world  1.536% patch   5725 offset (-4,+5)  camera or tick moved: judged by eye, largest patch at (592, 271, 702, 407)
PASS match/item_bar    tick    24/24    SETTLED world  0.159% patch     56 offset (+0,+0)  rule <= 1.0%, patch <= 1000
LOOK match/item_fired  tick    72/72    MOVING  world  0.349% patch    105 offset (-1,-1)  camera or tick moved: judged by eye, largest patch at (1160, 364, 1161, 469)
PASS match/melee       tick   421/421   SETTLED world  0.184% patch     66 offset (+0,+0)  rule <= 1.0%, patch <= 1000
PASS match/hud         tick   561/561   SETTLED world  0.650% patch    194 offset (+0,+0)  rule <= 1.0%, patch <= 1000
SKIP match/spin        tick  1344/426   different moment of the match (ticks 918 apart): eye only
PASS match/result      tick  5432/5432  SETTLED world  0.098% patch    292 offset (+0,+0)  rule <= 1.0%, patch <= 1000
judged 6 of 10; FAILED: lane/wave
exit 1
```

**At 32c8be3** (run 6 against master run 1, `parity/verdict-run6.log`):

```
PASS roster            tick    19/19    SETTLED world  0.084% patch     50 offset (+0,+0)  rule <= 1.0%, patch <= 1000
PASS lane/wave         tick   272/272   SETTLED world  0.224% patch      9 offset (+0,+0)  rule <= 1.0%, patch <= 1000
LOOK lane/farm         tick   398/399   MOVING  world  1.609% patch   1519 offset (-1,+0)  camera or tick moved: judged by eye, largest patch at (596, 329, 659, 385)
LOOK lane/clash        tick   642/643   MOVING  world  1.720% patch   5800 offset (-4,+6)  camera or tick moved: judged by eye, largest patch at (592, 271, 702, 407)
PASS match/item_bar    tick    24/24    SETTLED world  0.159% patch     56 offset (+0,+0)  rule <= 1.0%, patch <= 1000
LOOK match/item_fired  tick    72/72    MOVING  world  0.520% patch    105 offset (-1,-1)  camera or tick moved: judged by eye, largest patch at (1035, 546, 1140, 547)
PASS match/melee       tick   421/421   SETTLED world  0.187% patch     66 offset (+0,+0)  rule <= 1.0%, patch <= 1000
PASS match/hud         tick   561/561   SETTLED world  0.254% patch    267 offset (+0,+0)  rule <= 1.0%, patch <= 1000
LOOK match/spin        tick  1344/1344  MOVING  world  0.093% patch     88 offset (-1,+0)  camera or tick moved: judged by eye, largest patch at (673, 403, 710, 422)
PASS match/result      tick  5432/5432  SETTLED world  0.098% patch    292 offset (+0,+0)  rule <= 1.0%, patch <= 1000
judged 6 of 10; every judged scene passes
```

Six scenes are judged numerically, and all six pass. I judged the four `LOOK` scenes by eye from `issue212-parity-<scene>.png`:

- **`spin`** (tick 1344 on both). This is the first run whose `spin` landed on master's tick. It shows the same unit, the same 10/750 health and the same white spin crescents. Only a one-pixel outline differs (0.093%).
- **`item_fired`** (tick 72 on both, camera one pixel apart). Same crowd, same 430/750, and every unit is in place. Only outlines differ.
- **`farm`** (398 on master, 399 here). Same towers, same creeps and the same champion at 356/500, one tick of walking apart.
- **`clash`** (642 on master, 643 here): **open, and I cannot settle it from here.** The world matches: same towers, same units, champion on 138/500 in both. But master's frame has a white crescent at the fight, and the branch's frame does not.
  - The branch has captured `clash` at 643 in every run, and master at 642 in both of its runs. So the lag is systematic, not noise, and the large patch at the fight (about 5800 px) is the same in every run.
  - Crescents do draw on Kool: `spin` shows them at master's tick, and `melee` passes SETTLED.
  - I tried to take the branch frame at 642 by capturing one tick earlier. The literal diff is `probe-clash642.diff`, the log is `probe-clash642.log`, and it was reverted. That frame is not the same world state: the champion is on 126/500, and the camera sits elsewhere, because the harness starts the champion's walk on a frame-paced edge. The difference view is `parity/views/probe-clash642.png`. So the crescent's absence at 643 is either the effect ending one tick earlier or a missing draw, and nothing I have separates the two.
  - If the owner wants it settled, the fix is the same one the MOVING tier needs: a harness that steps fixed ticks. The one-tick lag may also come from how the Kool capture counts `afterTick`, and that code is in `udea-render`, which this ticket may not edit.

The numeric verdict for every earlier run is kept beside this one in `parity/verdict-run1.log` to `parity/verdict-run5.log`.

## 6. The issue, criterion by criterion

1. **`:moba:desktop:runMatchShot`, `runLaneShot` and `runShot` produce PNGs, compared side by side with the master references, with world content inside a threshold I picked and justified.**
   - The evidence command's green run is in §1.
   - The side-by-sides are `issue212-parity-all.png` and `issue212-parity-*.png`.
   - The threshold, the masks, the verdict and what each tier can and cannot say are in §5.
2. **`:moba:android` assembles a debug APK.**
   - `:moba:android:assembleDebug` is part of the evidence command.
   - The APK is `moba/android/build/outputs/apk/debug/android-debug.apk`, and it was rebuilt by `build-9.log`. Its size is in `apk-size-2.txt`: 11189558 bytes, modified 2026-09-19 00:45:39.411654804 +0000.
   - `aapt2 dump badging` (`apk-badging-2.txt`):

     ```
     package: name='dev.wildware.udea.moba.android' versionCode='1' versionName='1.0' platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36' compileSdkVersionCodename='16'
minSdkVersion:'26'
[... 4 lines of apk-badging-2.txt elided ...]
launchable-activity: name='dev.wildware.moba.android.MobaActivity'  label='moba' icon=''
     ```

   - `unzip -l` lists `udea/assets.udeapak` (`apk-listing-2.txt`).
   - It was not installed on a device or an emulator. None is attached to this box.
3. **`:moba:game` builds for jvm and android.** From `build-9.log`:

   ```
   > Task :moba:game:compileKotlinJvm UP-TO-DATE
[... 971 lines of build-9.log elided ...]
> Task :moba:game:compileAndroidMain
[... 394 lines of build-9.log elided ...]
> Task :moba:game:jvmJar UP-TO-DATE
[... 26 lines of build-9.log elided ...]
> Task :moba:game:bundleAndroidMainAar
[... 274 lines of build-9.log elided ...]
> Task :moba:android:assembleDebug
   ```

   The JVM half is `UP-TO-DATE` there because the `MobaDesktopAudioTest` runs had just compiled the same main sources.
4. **`:moba:compileKotlin` is gone, and nothing in moba compiles against LibGDX.**
   - The task is gone (§3).
   - `UDEA-MG-009` bans `com.badlogicgames.gdx:*` and `composegl-gdx*` on every classpath of every `moba` project. The LibGDX mutation turns it red (§1).
   - `git grep 'com.badlogic' -- moba` finds three lines, and all three are comments explaining the key-code change.
   - A dexdump of all 18 dex files in the APK (`apk-dexdump-2.txt`, from `apkfacts.sh`) has 0 `Lcom/badlogic` references, against 9418 `Ldev/wildware/moba/` ones. That covers the APK only. MG-009 covers the desktop runtime classpath.

Standing rules:
- `AGENTS.md`'s module table and `docs/module-graph.md` are updated, and `udeaVerifyAgentsMd` is green.
- `docs/contracts/` and `udea-render` are untouched (§2).
- Nothing was built with `-x`.

## 7. Mutations

Each row is the literal diff from the run, and each was reverted with `git checkout`.

**Audio, case (a): the unguarded call**, meaning the plain replacement of `GdxAudioDevice()` with `koolAudioDevice(...)` and no catch (`mutation-audio-unguarded.diff`):

```diff
diff --git a/moba/desktop/src/main/kotlin/dev/wildware/moba/entry/MobaDesktopAudio.kt b/moba/desktop/src/main/kotlin/dev/wildware/moba/entry/MobaDesktopAudio.kt
index d1dde70..129419c 100644
--- a/moba/desktop/src/main/kotlin/dev/wildware/moba/entry/MobaDesktopAudio.kt
+++ b/moba/desktop/src/main/kotlin/dev/wildware/moba/entry/MobaDesktopAudio.kt
@@ -51,17 +51,6 @@ public object MobaDesktopAudio {
             log("[moba.audio] silent: -D${MobaLaunch.ASSET_ROOT_PROPERTY} is not set, so there is no directory to read sounds from")
             return MobaAudio.silent(host)
         }
-        val real = device(assetRoot)
-        return try {
-            MobaAudio.of(host, real)
-        } catch (failed: AudioLoadException) {
-            real.close()
-            log(
-                "[moba.audio] silent: '${failed.path}' would not load (${failed.message}" +
-                    (failed.cause?.let { "; ${it::class.simpleName}: ${it.message}" } ?: "") +
-                    "), so this process plays through AudioDevice.Silent",
-            )
-            MobaAudio.silent(host)
-        }
+        return MobaAudio.of(host, device(assetRoot))
     }
 }
```

```
> Task :moba:desktop:test FAILED
[... 1 lines of mutation-audio-unguarded.log elided ...]
MobaDesktopAudioTest > with no audio output a windowed process falls back to silence and says why() FAILED
[... 2 lines of mutation-audio-unguarded.log elided ...]
3 tests completed, 1 failed
[... 10 lines of mutation-audio-unguarded.log elided ...]
BUILD FAILED in 4s
```

**Audio, case (b): the old shape**, meaning silent everywhere (`mutation-audio-silent.diff`):

```diff
diff --git a/moba/desktop/src/main/kotlin/dev/wildware/moba/entry/MobaDesktopAudio.kt b/moba/desktop/src/main/kotlin/dev/wildware/moba/entry/MobaDesktopAudio.kt
index d1dde70..bff5e5f 100644
--- a/moba/desktop/src/main/kotlin/dev/wildware/moba/entry/MobaDesktopAudio.kt
+++ b/moba/desktop/src/main/kotlin/dev/wildware/moba/entry/MobaDesktopAudio.kt
@@ -51,17 +51,6 @@ public object MobaDesktopAudio {
             log("[moba.audio] silent: -D${MobaLaunch.ASSET_ROOT_PROPERTY} is not set, so there is no directory to read sounds from")
             return MobaAudio.silent(host)
         }
-        val real = device(assetRoot)
-        return try {
-            MobaAudio.of(host, real)
-        } catch (failed: AudioLoadException) {
-            real.close()
-            log(
-                "[moba.audio] silent: '${failed.path}' would not load (${failed.message}" +
-                    (failed.cause?.let { "; ${it::class.simpleName}: ${it.message}" } ?: "") +
-                    "), so this process plays through AudioDevice.Silent",
-            )
-            MobaAudio.silent(host)
-        }
+        return MobaAudio.silent(host)
     }
 }
```

```
> Task :moba:desktop:test FAILED
[... 1 lines of mutation-audio-silent.log elided ...]
MobaDesktopAudioTest > a windowed process with an audio output plays through the device it was given() FAILED
[... 2 lines of mutation-audio-silent.log elided ...]
MobaDesktopAudioTest > with no audio output a windowed process falls back to silence and says why() FAILED
[... 2 lines of mutation-audio-silent.log elided ...]
3 tests completed, 2 failed
[... 10 lines of mutation-audio-silent.log elided ...]
BUILD FAILED in 4s
```

**Audio, case (c): no Headless guard** (`mutation-audio-headless.diff`):

```diff
diff --git a/moba/desktop/src/main/kotlin/dev/wildware/moba/entry/MobaDesktopAudio.kt b/moba/desktop/src/main/kotlin/dev/wildware/moba/entry/MobaDesktopAudio.kt
index d1dde70..ad2bf3a 100644
--- a/moba/desktop/src/main/kotlin/dev/wildware/moba/entry/MobaDesktopAudio.kt
+++ b/moba/desktop/src/main/kotlin/dev/wildware/moba/entry/MobaDesktopAudio.kt
@@ -46,7 +46,6 @@ public object MobaDesktopAudio {
         device: (Path) -> AudioDevice = ::koolAudioDevice,
         log: (String) -> Unit = { System.err.println(it) },
     ): MobaAudio {
-        if (host.mode == RenderMode.Headless) return MobaAudio.silent(host)
         if (assetRoot == null) {
             log("[moba.audio] silent: -D${MobaLaunch.ASSET_ROOT_PROPERTY} is not set, so there is no directory to read sounds from")
             return MobaAudio.silent(host)
```

```
> Task :moba:desktop:test FAILED
[... 1 lines of mutation-audio-headless.log elided ...]
MobaDesktopAudioTest > a headless process never opens a device() FAILED
[... 2 lines of mutation-audio-headless.log elided ...]
3 tests completed, 1 failed
[... 10 lines of mutation-audio-headless.log elided ...]
BUILD FAILED in 4s
```

**The launcher keyboard** (`mutation-keyboard.diff`), which restores the pre-#224 shape:

```diff
diff --git a/moba/desktop/src/main/kotlin/dev/wildware/moba/entry/MobaLaunch.kt b/moba/desktop/src/main/kotlin/dev/wildware/moba/entry/MobaLaunch.kt
index 82e2725..d1c56f5 100644
--- a/moba/desktop/src/main/kotlin/dev/wildware/moba/entry/MobaLaunch.kt
+++ b/moba/desktop/src/main/kotlin/dev/wildware/moba/entry/MobaLaunch.kt
@@ -108,7 +108,7 @@ public object MobaLaunch {
         extra: IntentSource? = null,
     ): IntentSource {
         val state = host.ctx[IntentState.KEY]
-        val device = DeviceIntent(state.bindings, keyboard)
+        val device = DeviceIntent(state.bindings)
         val source = if (extra == null) {
             device
         } else {
```

```
> Task :moba:desktop:test FAILED
[... 1 lines of mutation-keyboard.log elided ...]
MobaInputTest > the launcher wires the keyboard it is handed() FAILED
[... 2 lines of mutation-keyboard.log elided ...]
8 tests completed, 1 failed
[... 10 lines of mutation-keyboard.log elided ...]
BUILD FAILED in 4s
```

**Key W back on the LibGDX code**, in both the asset and the constants (`mutation-keyW.diff`). The positive per-key tests still pass under this mutation, because they ask the question using the answer. The two negatives are what catch it:

```diff
diff --git a/moba/game/assets/control/controls.udea.kts b/moba/game/assets/control/controls.udea.kts
index 17c450f..127a188 100644
--- a/moba/game/assets/control/controls.udea.kts
+++ b/moba/game/assets/control/controls.udea.kts
@@ -62,7 +62,7 @@
 // constants is a red test rather than a key that quietly stops firing.
 
 val KeyQ = 'Q'.code
-val KeyW = 'W'.code
+val KeyW = 51
 val KeyA = 'A'.code
 val KeyS = 'S'.code
 val KeyD = 'D'.code
diff --git a/moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaControls.kt b/moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaControls.kt
index 1de839d..bc42a19 100644
--- a/moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaControls.kt
+++ b/moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaControls.kt
@@ -140,7 +140,7 @@ public object MobaControls {
     public object Keys {
 
         public val Q: Int = 'Q'.code
-        public val W: Int = 'W'.code
+        public val W: Int = 51
         public val A: Int = 'A'.code
         public val S: Int = 'S'.code
         public val D: Int = 'D'.code
```

```
> Task :moba:game:jvmTest FAILED
[... 1 lines of mutation-keyW.log elided ...]
MobaKeyBindingTest[jvm] > no binding is still on the LibGDX code it replaced()[jvm] FAILED
[... 2 lines of mutation-keyW.log elided ...]
MobaKeyBindingTest[jvm] > the LibGDX code for each key no longer does that key's job()[jvm] FAILED
[... 2 lines of mutation-keyW.log elided ...]
5 tests completed, 2 failed
[... 18 lines of mutation-keyW.log elided ...]
BUILD FAILED in 11s
```

**MG-005's scope:** the new test failing on the old scope (`mg005-red.log`):

```
> Task :test FAILED
[... 1 lines of mg005-red.log elided ...]
ModuleGraphRulesTest > every rule governs at least one project settings_gradle_kts includes() FAILED
[... 2 lines of mg005-red.log elided ...]
36 tests completed, 1 failed
[... 10 lines of mg005-red.log elided ...]
BUILD FAILED in 20s
```

## 8. Regenerated files

- **`net-protocol.lock` and `expected-generated-hashes.txt`: not regenerated, and no id moved.** `moba/game/net-protocol.lock` is `moba/net-protocol.lock`, moved byte for byte.
- **The replay fixtures** `moba/desktop/src/test/resources/fixtures/moba-3600.udearep` and `moba-36000.udearep` were regenerated with `:moba:desktop:udeaWriteReplayFixture`. In each file only 36 bytes differ from `origin/kmp`'s (`fixdiff.log`, from `fixdiff.py`): zero-based offsets 27-58, which hold the 32-byte asset-graph hash, and the last 4 bytes.
  - The asset hash moved (`44e487ef…` to `064c663e…`) because `controls.udea.kts` changed its key codes.
  - The protocol hash `0x1bef` is unchanged.
