6019569

# BRIEF-192: boot moba from a saved level file, and ban loops in asset scripts

Branch `issue-192-binary-test-level-kmp`, worktree
`/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a359f0d7330f3ae2d`. It branched from
`origin/kmp` at `407123a` and has `origin/kmp` `322dde9` (#213, the old tree deleted) merged in
at `6019569`. The SHA above is that merge, the last code commit. This brief is committed on top of
it and changes no code.

Scratch artefacts quoted below are in
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue192/`
(called `$S`).

## 1. The evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem ANDROID_HOME=$HOME/Android/Sdk sh gradlew \
  :udea-core:jvmTest --tests dev.wildware.udea.core.level.LevelSceneTest \
  :moba:desktop:test --tests dev.wildware.moba.level.TestLevelRosterTest \
                     --tests dev.wildware.moba.level.MobaLevelLaunchTest \
  :udea-assets-compiler:test --tests dev.wildware.udea.assets.compiler.scan.LoopInAssetTest \
                             --tests dev.wildware.udea.assets.compiler.daemon.AssetDaemonTest \
  --continue
```

What each part holds:

- `TestLevelRosterTest` boots the game from `moba/game/levels/test_level.udealevel` and compares
  it with `moba/desktop/src/test/resources/levels/test_level.roster.txt`. That file is the world
  the old `test_level.udea.kts` built, captured in commit `3a72b83`, before the script was
  deleted: a whole-world hash and then one line per `NetId` (team, kind, exact position bits,
  health, component list). This is AC1.
- `MobaLevelLaunchTest` checks four things. The bundled level is the checked-in file. A level
  named at launch boots, and a match restart comes back into it. The level's clock and random
  streams come back with it. A missing path fails and names the path.
- `LevelSceneTest` is the `udea-core` scene that puts a saved level back into the world.
- `LoopInAssetTest` and `AssetDaemonTest` cover the loop rule, in the build and in the live
  editor path. This is AC2's rule.

**Green on the merged state `6019569`**, from `$S/evidence-merged.log` and the JUnit XML it left
(the timestamps show the tests ran in this invocation):

```
BUILD SUCCESSFUL in 15s
```
```
<testsuite name="dev.wildware.udea.core.level.LevelSceneTest" tests="3" skipped="0" failures="0" errors="0" timestamp="2026-09-19T02:18:10.001Z"
<testsuite name="dev.wildware.moba.level.TestLevelRosterTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T02:18:10.673Z"
<testsuite name="dev.wildware.moba.level.MobaLevelLaunchTest" tests="4" skipped="0" failures="0" errors="0" timestamp="2026-09-19T02:18:10.175Z"
<testsuite name="dev.wildware.udea.assets.compiler.scan.LoopInAssetTest" tests="4" skipped="0" failures="0" errors="0" timestamp="2026-09-19T02:18:15.637Z"
<testsuite name="dev.wildware.udea.assets.compiler.daemon.AssetDaemonTest" tests="10" skipped="0" failures="0" errors="0" timestamp="2026-09-19T02:18:07.517Z"
```

**Red when the feature is reverted.** I ran each mutation below against this command before the
merge, and restored it afterwards. Each diff is the literal `git diff` I saved at the time, and
the failing tests are spliced from that run's log. The `index` line of each diff names the
mutated file's starting blob. Each of those blobs is the same at `a0a9c1b`, `adee707` and
`6019569`, so every mutation applies unchanged to the SHA above.

**mut1: boot no longer loads the level** (`$S/mut1.diff`, `$S/mut1.log`)
```diff
diff --git a/moba/game/src/commonMain/kotlin/dev/wildware/moba/entry/MobaEntry.kt b/moba/game/src/commonMain/kotlin/dev/wildware/moba/entry/MobaEntry.kt
index efb7559..3eefe38 100644
--- a/moba/game/src/commonMain/kotlin/dev/wildware/moba/entry/MobaEntry.kt
+++ b/moba/game/src/commonMain/kotlin/dev/wildware/moba/entry/MobaEntry.kt
@@ -62,7 +62,6 @@ public object MobaEntry {
         val levels = host.game.levels
         val level = levels.read(host.ctx[MobaLevel.KEY].bytes)
         host.ctx.scenes.requestScene(MobaLevel.SCENE_ID)
-        levels.load(level, host.ctx.barrier)
         host.run(1)
         // The player is the unit the level saved with a `Player` component on it: the elite orc in
         // the orc clearing, where the old game dropped it. Resolved from the world rather than
```
```
MobaLevelLaunchTest > a launch level brings back the clock and the random streams it was saved with() FAILED
...
TestLevelRosterTest > a boot places every unit where the authored test level placed it() FAILED
...
5 tests completed, 2 failed
```

**mut2: a different level file goes in as `test_level.udealevel`.** I copied
`match.udealevel`, the mid-fight level `runLevelShotSave` writes, over it. (`$S/mut2.diff`,
`$S/mut2.log`)
```diff
 moba/game/levels/test_level.udealevel | Bin 55955 -> 77327 bytes
 1 file changed, 0 insertions(+), 0 deletions(-)
diff --git a/moba/game/levels/test_level.udealevel b/moba/game/levels/test_level.udealevel
index 5681886..ce0dc97 100644
Binary files a/moba/game/levels/test_level.udealevel and b/moba/game/levels/test_level.udealevel differ
```
```
TestLevelRosterTest > a boot places every unit where the authored test level placed it() FAILED
...
5 tests completed, 1 failed
```

**mut3: the scanner no longer looks for loops** (`$S/mut3.diff`, `$S/mut3.log`)
```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt
index b90d27a..b22f40f 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt
@@ -268,7 +268,6 @@ public class UdeaDeclarationScanner @JvmOverloads constructor(
             collectFileConstants(statements)
             statements.forEach { visitStatement(it, emptyMap()) }
             collectReferences(ktFile)
-            collectLoops(ktFile)
         }
 
         /**
```
```
AssetDaemonTest > a loop in an edited script is rejected with the loop rule at the loop() FAILED
...
LoopInAssetTest > a repeat is an error that points at the repeat(Path) FAILED
...
LoopInAssetTest > for, while and do-while are errors too, each at its own line(Path) FAILED
...
LoopInAssetTest > a loop inside a declaration's lambda is found(Path) FAILED
...
14 tests completed, 4 failed
```

**mut4: the live daemon drops pass 1's diagnostics again** (`$S/mut4.diff`, `$S/mut4.log`)
```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt
index fdd5e89..133ab21 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt
@@ -280,7 +280,7 @@ public class AssetDaemon(
         // Pass 1's own diagnostics first, as `AssetPipeline.compileAndValidate` reports them: a
         // script the build refuses in its syntactic pass - a loop (issue #192) - must not be one
         // the live daemon applies.
-        return scan.diagnostics + result.diagnostics
+        return result.diagnostics
     }
 
     /**
```
```
AssetDaemonTest > a loop in an edited script is rejected with the loop rule at the loop() FAILED
...
14 tests completed, 1 failed
```

**mut5: a loaded level gets its `NetId`s in the wrong order** (`$S/mut5.diff`, `$S/mut5.log`)
```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/level/LevelService.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/level/LevelService.kt
index a1035bf..c285954 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/level/LevelService.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/level/LevelService.kt
@@ -184,7 +184,7 @@ public class LevelService internal constructor(
     internal fun populate(level: Level, scope: SceneScope) {
         val document = level.document
         scope.world.loadSnapshot(document.world)
-        for (binding in document.netIds.sortedBy { it.netId.index }) scope.netIds.allocate(binding.entity)
+        for (binding in document.netIds.sortedByDescending { it.netId.index }) scope.netIds.allocate(binding.entity)
         ctx.physics.rebuildFrom(scope.world, scope.netIds)
     }
 
```
```
LevelSceneTest[jvm] > a level scene puts the saved entities into the world and leaves the clock and the streams running()[jvm] FAILED
...
3 tests completed, 1 failed
```
Only `LevelSceneTest` catches mut5. The moba tests stay green under it: at boot, `NetId`s are
bound by `LevelService.load`, which restores the saved bindings, so the only path that uses
`populate`'s order alone is a match restart. No moba test compares ids across a restart.

## 2. Summary

**The level is a saved file now.** `moba/game/levels/test_level.udealevel` holds the world that
`test_level.udea.kts` used to build. It was written in `3a72b83`, the one commit where the script
and the file both exist, by a conversion task. That task boots the scripted level and saves it
through #191's `LevelService`. The file is byte-identical to the one the pre-port attempt saved
at `5e53e31`. The next commit (`c51b96e`) switched the game to the file and deleted the script,
`TestLevelScene` and the conversion task.

- `:moba:game`'s `udeaBundleResources` packages `levels/` next to the asset bundle, so the jar and
  both APKs carry `levels/test_level.udealevel` (the listings are under "Also checked" in section 5).
- **Boot** (`MobaEntry.seed`): in one barrier drain it swaps to a new `udea-core` `LevelScene` over
  the bytes, then runs #191's full `LevelService.load`. The scene gives the world an active
  scene, which snapshot restore and match restart need. The load brings back the saved clock,
  random streams and level sections.
- **Match restart** swaps back to the same `LevelScene`.
- `LevelScene` is in `udea-core` because it needs `LevelService`'s internal document. It reaches
  the service through an internal `SceneScope.levels`, which `UdeaGameDef` sets. Nothing was
  added to `GameContext`.
- **`-Plevel=<path>`** is on `:moba:desktop`. Every `JavaExec` there forwards it, resolved against
  the repo root, as `-Dmoba.level`. `MobaLaunchLevel.bytes()` reads it for `run` (headless and
  Kool), `runServer`, `runClient` and the shot mains. A path that is not a file throws and names
  the path; it does not fall back to the default.
- `:moba:game` still reads no system property. `MobaGame.definition/host` take the bytes as an
  argument, defaulting to the bundled level.
- `runLevelShotBoot` is new, beside the existing `runLevelShot*` phases. It photographs a boot
  from whatever level `-Plevel` names.

**The loop ban is `UDEA0015` (`UdeaRules.LOOP_IN_ASSET`).** Pass 1 (`UdeaDeclarationScanner`)
flags `for`, `while`, `do`-`while` and an unqualified `repeat(...)` anywhere in a `.udea.kts`
file, at the loop's own line and column. `"-".repeat(3)`, a comment that mentions a loop, and a
string that contains one are not flagged. `LoopInAssetTest` has that control.

The live `AssetDaemon` (the `assets.*` tools) used to drop every pass-1 diagnostic. It now keeps
them, as `AssetPipeline.compileAndValidate` already did, so the editor cannot save a loop that the
next build would refuse.

**Decisions**, each commented on #192:

- Rule id `UDEA0015`, in the shared `UdeaRules`. The alternative was the asset compiler's
  stopgap `UDEA002x` band, whose own KDoc says it is waiting to move into `UdeaRules`.
- `forEach` over a literal list is not banned. The issue names three loop kinds.
- A restart reloads the exact saved layout. Before, it rescattered units from the `Spawn` stream,
  and the file has no cluster centres left to scatter around. This is why the replay fixtures
  moved; section 6 has the numbers.
- `MatchState.seed` stays. Removing it would move a replicated component and the wire lock.

**Surprise.** The loop ban also caught a udea-agent test. `AssetsToolsetTest`'s "one unresolved
id with five referrers" wrote its five referrers with `repeat(5)`. Once the daemon kept pass-1
diagnostics, that script had two errors instead of one. The test now writes the five out
(`02f2d7f`).

I looked for any other asset script with a loop in it. The search covered the test sources of
`udea-agent`, `udea-assets-compiler`, `udea-gradle` and `moba/desktop`. It matched a line-start
`repeat(`, `for (` or `while (` within 30 lines after a `"""`. Every hit was either
`LoopInAssetTest`'s own negative scripts or ordinary test code that sits after a string.

The same file's blob (`5681886`) is at `moba/levels/` in `5e53e31` and at `moba/game/levels/`
here, which is the byte-identity claim above.

**Merge.** The merge had one textual conflict: a comment in the compiler fixture
`test_level.udea.kts` that named the corpus path #213 moved. I resolved it to the new
`example-assets/` path.

`example-assets/level/test_level.udea.kts` still has three `repeat` loops. That tree is the
retired game's corpus: `ExampleScanTest` reads it, and its golden records declarations, not
diagnostics. It is not a moba asset, so it is outside AC3, and I left it as history.

## 3. `sh gradlew build`

Run alone on the box, on the merged state `6019569`: `sh gradlew build --continue`, with no `-x`.
Spliced from `$S/build-merged.log`:

```
BUILD SUCCESSFUL in 3m 2s
915 actionable tasks: 739 executed, 90 from cache, 86 up-to-date
Configuration cache entry stored.
```

915 tasks is the lead's stated baseline for `origin/kmp` `322dde9`, and the build is green.

The build before this one (`$S/build-full.log`, at `adee707`, before the merge) failed only
`:udea-agent:udeaAssetTools`. That was the `AssetsToolsetTest` case in section 2.

**GL, run for real** (`$S/gl-merged.log`). The branch touches no `udea-render` code, but the level
shots open a Kool context, so I ran the GL suites under xvfb:

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true
```
```
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest
BUILD SUCCESSFUL in 1m 12s
```
The JUnit XML shows every GL suite ran, and none was skipped:
```
<testsuite name="dev.wildware.udea.render.gl.GlCaptureDeterminismTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T02:18:39.661Z"
<testsuite name="dev.wildware.udea.render.gl.GlCaptureTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T02:18:42.585Z"
<testsuite name="dev.wildware.udea.render.gl.GlKoolInputTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T02:18:47.860Z"
<testsuite name="dev.wildware.udea.render.gl.GlKoolPointerTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T02:19:01.508Z"
<testsuite name="dev.wildware.udea.render.gl.GlModelRenderTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T02:19:08.992Z"
<testsuite name="dev.wildware.udea.render.gl.GlOverlayIsolationTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T02:19:17.740Z"
<testsuite name="dev.wildware.udea.render.gl.GlUiLayerTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T02:19:25.808Z"
<testsuite name="dev.wildware.udea.render.gl.KoolThreadShutdownTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T02:19:30.718Z"
<testsuite name="dev.wildware.udea.render.gl.OffscreenBackendExplodingCaptureTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T02:19:33.855Z"
<testsuite name="dev.wildware.udea.render.gl.OffscreenBackendSecondCreateTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T02:19:36.563Z"
<testsuite name="dev.wildware.udea.render.gl.OffscreenBackendShutdownTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T02:19:38.027Z"
<testsuite name="dev.wildware.udea.render.gl.OffscreenBackendTest" tests="2" skipped="0" failures="0" errors="0" timestamp="2026-09-19T02:19:40.507Z"
<testsuite name="dev.wildware.udea.agent.host.gl.OffscreenRenderToolsTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T02:18:39.346Z"
<testsuite name="dev.wildware.udea.agent.host.gl.OverlayCaptureIsolationTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-19T02:18:44.339Z"
```

## 4. Images

All four are in `/srv/ssd1/workspace/Udea/build/debug-screenshots/` and on the dashboard.

- `issue192-before-script-level-boot.png`: the Kool boot shot of the old script level. It is the
  "before" picture. I took it on a detached checkout of `3a72b83`, where the script still boots
  the game. On top of that checkout I made two temporary changes, neither of them committed:
  - `LevelShot.kt` copied from `a0a9c1b`, with its launch-level read replaced by the fixed name
    `script-test_level`;
  - a `runLevelShotBoot` task appended to `moba/desktop/build.gradle.kts`.

  The log is `$S/shot-before.log`.
- `issue192-after-udealevel-boot.png`: the same shot, booted from `test_level.udealevel`. `cmp`
  says it is **byte-identical** to the "before" picture. I retook it on the merged state and it is
  still identical (`$S/shot-boot-merged.log`: "booted the bundled test level: 27 units at tick 1").
- `issue192-before-after-boot.png`: the two, side by side.
- `issue192-plevel-midfight-level-boot.png`: `runLevelShotBoot -Plevel=.../match.udealevel`, a
  level saved mid-fight at tick 420, booted through `-Plevel`. It shows the fight, not the
  starting layout.

## 5. The issue, criterion by criterion

**AC1: booting from `test_level.udealevel` gives the same units in the same places as the old
`.kts` level.**

- `TestLevelRosterTest` compares against the world captured from the script in `3a72b83`: the
  whole-world hash, then every `NetId`'s team, kind, exact float bits, health and components. It
  is green in section 1, and red under mut1 and mut2.
- The Kool screenshot of the file boot is byte-identical to the one of the script boot
  (section 4).
- `-Plevel`: `MobaLevelLaunchTest` "a level named on the launch line is the level the game boots
  and restarts into", plus the mid-fight shot.

**AC2: adding `repeat(2) { }` to any asset fails the build with the new rule id, pointing at that
line.** I appended `repeat(2) { }` as line 16 of `moba/game/assets/config.udea.kts` and ran
`sh gradlew :moba:game:assemble`. Spliced from `$S/ac2-red.log`:

```
> Task :moba:game:udeaScanAssets FAILED
[udeaScanAssets] error UDEA0015 moba/game/assets/config.udea.kts:16:1 `repeat` loop in an asset script. Assets may not contain loops: the editor saves an exact value back to the line it came from, and a value a loop produces has no single line. Write each declaration out, or keep a level's units in a `.udealevel` file.
...
> Task :moba:game:udeaPackBundle
[udeaPackBundle] error UDEA0015 moba/game/assets/config.udea.kts:16:1 `repeat` loop in an asset script. Assets may not contain loops: the editor saves an exact value back to the line it came from, and a value a loop produces has no single line. Write each declaration out, or keep a level's units in a `.udealevel` file.

> Task :moba:game:udeaPackBundle FAILED
...
BUILD FAILED in 48s
```
I restored the file, and the same command went green (`$S/ac2-green.log`):
```
BUILD SUCCESSFUL in 47s
```
The rule's unit tests are `LoopInAssetTest`: the repeat, for, while and do-while cases, a loop
inside a lambda, and the control. `AssetDaemonTest` covers the editor path. All are red under
mut3; the daemon test is also red under mut4.

**AC3: no `.udea.kts` under `moba/game/assets` contains a loop.** Enforced: the build scans every
moba asset with the rule above, and section 3 is green. Also checked directly (`$S/ac3-grep.txt`). The last command is the known negative: the same
probe does find loops in the retired corpus.

```
$ grep -rnE '\b(repeat *\(|for *\(|while *\(|do *\{)' moba/game/assets --include='*.udea.kts'
exit=1
$ find moba/game/assets -name '*.udea.kts' | wc -l
22
$ grep -rnE '\b(repeat *\(|for *\(|while *\(|do *\{)' example-assets --include='*.udea.kts'   # known negative: the probe does find loops
example-assets/level/test_level.udea.kts:43:        repeat(5) {
example-assets/level/test_level.udea.kts:53:        repeat(10) {
example-assets/level/test_level.udea.kts:63:        repeat(10) {
```

### Also checked

The level file is packed in both APKs. From `$S/apk-debug-listing.txt` and
`$S/apk-release-listing.txt`:
```
    55955  1981-01-01 01:01   levels/test_level.udealevel
    55955  1981-01-01 01:01   levels/test_level.udealevel
```
The Android launcher was built, not run. There is no device on this box. iOS was not built here
(Linux).

Not exercised:

- A live `:moba:desktop:run -PdebugPort` session over the bridge. The boot shots and
  `MobaLevelLaunchTest` go through the same launch path.
- `runUdpProof`, which was already red before this branch.

## 6. Regenerated files

- **`moba/desktop/src/test/resources/fixtures/moba-3600.udearep` and `moba-36000.udearep`**,
  regenerated with `:moba:desktop:udeaWriteReplayFixture` (`$S/regen-fixtures.log`). The starting
  point was `origin/kmp` `407123a`, whose fixture header has asset graph hash `064c663e...`. The
  regenerated header has `86976f01...`, because `level/test_level` and `gameConfig.defaultLevel`
  left the bundle. The two headers agree on `protoHash`, the input schema hash and the seed.
  `MobaReplayEqualityTest` refuses a fixture whose asset graph hash differs from the bundle's. It runs
  in `:moba:desktop:test`, which is in the green build on the merged state, so #213 did not move
  the hash again.
  This is not a re-baseline. The comparison of old and new, from `$S/fixture-compare.txt`:
  ```
fixture moba-3600
  asset graph hash: base 064c663e186e52c8..., branch 86976f010f259e07...
  firstTick base=1 branch=1, ticks base=3600 branch=3600, peers 1/1
  input frames identical: True (30599 bytes)
  per-tick world hashes equal for the first 2081 ticks; first differing index 2081 = tick t2082
  ticks whose hash differs: 1519 of 3600
fixture moba-36000
  asset graph hash: base 064c663e186e52c8..., branch 86976f010f259e07...
  firstTick base=1 branch=1, ticks base=36000 branch=36000, peers 1/1
  input frames identical: True (302728 bytes)
  per-tick world hashes equal for the first 2081 ticks; first differing index 2081 = tick t2082
  ticks whose hash differs: 33919 of 36000
  ```
  The recorded inputs are identical, and the first 2081 recorded hashes are equal, which covers
  all of match one. A replay probe (`$S/probe.log`, run against the regenerated fixture, so its
  `hashMatchesRecording` says nothing about the old one) shows where match one ends:
  ```
    ISSUE192 end of t2081 match=1 phase=Ended hashMatchesRecording=true
    ISSUE192 end of t2082 match=1 phase=Restarting hashMatchesRecording=true
    ISSUE192 end of t2083 match=2 phase=Fighting hashMatchesRecording=true
  ```
  So the first differing hash falls on the restart, within one tick of the swap. The tool
  labels it `t2082` as `firstTick + index`, and the probe prints the tick at the end of a step.
  I did not work out whether those two labels are off by one against each other. Either way,
  the conclusion stands: nothing before the restart moved. That is the restart decision in
  section 2. Match two onward starts from the saved layout rather than a fresh scatter, so every
  later tick differs.

  If #228 lands first, its fixtures conflict with these. The fix is to regenerate, not to merge
  the text.
- **`net-protocol.lock` did not move.** No replicated component changed. `udeaCheckProtocolLock`
  runs in the green build.
- **`expected-generated-hashes.txt` did not move.**
