a39d2c2

(The code under review is `a39d2c2` on `issue-241-animator`. This brief is committed on top of it and changes no code.)

# BRIEF-241: the Animator component and typed clip API

Issue #241, A2 of epic #239. Branch `issue-241-animator`, from `origin/kmp` at `2046c79`.

## 1. Evidence command

```
sh gradlew :udea-core:jvmTest --tests 'dev.wildware.udea.core.spatial.*' --rerun :udea-assets-compiler:test --tests 'dev.wildware.udea.assets.compiler.gen.*' --rerun :udea-compiler-plugin:test --tests 'dev.wildware.udea.compiler.fir.UdeaAnimationClipCheckerTest' --rerun :udea-replay:jvmTest --tests 'dev.wildware.udea.replay.AnimatorReplayTest' --rerun :moba:desktop:runNetProof
```

One command covering the four halves of the ticket: the clip API (`udea-core`), clips generated from the glTF (`udea-assets-compiler`), the misspelling did-you-mean (`udea-compiler-plugin`), replay (`udea-replay`), and replication in the real game (`runNetProof`, which since this branch **exits 1** when any run disagrees).

**Green at `a39d2c2`**, from `scratchpad/issue241/evidence-green.log`:

```
[moba.netproof] perfect        units and animation AGREED
[moba.netproof] 150ms+5% loss  units and animation AGREED
[moba.netproof] TRELLO_8       units and animation AGREED
```
```
BUILD SUCCESSFUL in 29s
```

Each client's line in that log reads, for example (log line 333):

```
           animHash  client=0xa53e4399515f948c server@t240=0xa53e4399515f948c animated=1/1 MATCH
```

and the server's fox, read through the clip API (line 341):

```
  animated fox NetId(#32@0) on the server: Run at 50ticks of 70ticks, blend 1.00, finished=false
```

Test counts from the XML reports that run wrote: `AnimatorTest` 18, `ClipTimeExactnessTest` 1, `AnimatorSnapshotTypeTest` 3, `GltfClipsTest` 8, `ModelClipAccessorsTest` 9 (plus the three pre-existing `gen` classes), `UdeaAnimationClipCheckerTest` 8, `AnimatorReplayTest` 2. No failures.

**Red when the feature is reverted.** Two reverts of replication, each applied to `a39d2c2` and run with `sh gradlew :moba:desktop:runNetProof`:

1. The Animator is taken out of moba's component registry (`scratchpad/issue241/evidence-red.diff`):

```diff
-        ) + LaneModule.snapshotTypes() + ItemModule.snapshotTypes() +
-            // An animated model's clip, start, speed, loop and fade (issue #241): udea-core's own
-            // registration, so its field kinds are written once, beside the component.
-            Animator.snapshotType(),
+        ) + LaneModule.snapshotTypes() + ItemModule.snapshotTypes(),
```

   `evidence-red.log`, lines 259 and 272:

```
Exception in thread "main" java.lang.IllegalStateException: this registry has no Animator; the entities carrying it cannot be identified
```
```
> Process 'command '/home/shaun/.sdkman/candidates/java/21.0.11-tem/bin/java'' finished with non-zero exit value 1
```

2. The Animator stays registered but its fields stop being sent (`@Net` to `@Sim`, `evidence-red2.diff`). `evidence-red2.log`, lines 265 and 317-319:

```
           animHash  client=0x5fd4b748b2f5442c server@t240=0xabacb0a28ec5be49 animated=0/1 DIFFER
```
```
[moba.netproof] perfect        units and animation DISAGREED
[moba.netproof] 150ms+5% loss  units and animation DISAGREED
[moba.netproof] TRELLO_8       units and animation DISAGREED
```

   and the process exits non-zero (`BUILD FAILED in 16s`). On `origin/kmp` the command does not compile at all: none of the test classes, `Animator` or `Fox.Clips` exist there.

The test halves of the command each have mutation rows in section 6 showing them go red: `udea-core` M01-M10 and M15-M21, `udea-assets-compiler` M11, `udea-compiler-plugin` M12-M13, `udea-replay` M14.

## 2. Summary

**What exists now:**

- `udea-core`: `Animator` (`dev.wildware.udea.core.spatial`), a `@Replicated` component with two `ClipPlayback` composites (`current`, `previous`) and a fade (`fadeStart: Tick`, `fadeLength` in ticks). `play`, `crossfade`, `isPlaying`, `clipTime`, `isFinished`, `blendWeight`. `AnimationClip(index, name, length: Ticks)` and `Loop { Once, Repeat }`. A new `Ticks` value class for durations (`6.ticks`), distinct from `Tick`, which is a moment.
- `udea-assets-compiler`: `udeaGenerateAccessors` reads each `model(...)`'s glTF/GLB and writes `object Fox { object Clips { val Survey; val Walk; val Run } }`, each an `AnimationClip` carrying its index and its length in ticks. A model whose clips cannot be read fails the task under new rule **UDEA0027**, with a did-you-mean for a missing file.
- `udea-compiler-plugin`: a FIR checker, new rule **UDEA0016**, adds `Fox.Clips has no clip 'Rnu'. Did you mean 'Run'?` to Kotlin's own unresolved-reference error.
- `udea-gradle`: the accessors task now takes the `.glb`/`.gltf` files and the asset root as inputs.
- `moba`: ships the Fox (`models/fox/Fox.glb`, same bytes and notice as #240's copy), registers `Animator.snapshotType()`, and the net proof carries an animated fox and an animation hash.

**Decisions, each commented on #241 with what, the alternative, why and how to change it:**

- `now` is an explicit argument (comment 5740101105). The sketch's `play(clip)` has no clock to read.
- Package `core.spatial`, so no existing id moves (5740101209).
- Clip identity is the glTF index; the length goes on the wire so no client needs the file; default loop `Repeat`; `Float` speed made exact by computing in `Double`; `clipTime` returns `Ticks`; seconds become ticks with `ceil(s * 60 - 0.001)` (5740101344).
- The typo path: UDEA0027 in the asset compiler, UDEA0016 in the K2 checker, clip objects recognised by member type rather than a marker (5740101516).
- The fox in `runNetProof`, and `runNetProof` exiting 1 on disagreement (5740101649). Its KDoc already said "exits non-zero if either disagrees"; on `origin/kmp` its `main` printed the verdicts and returned.

**Speed as a Float, and why two runs agree.** Clip time is `floor((now - start) as Double * speed as Double)`. Every `Float` is exactly a `Double`, every tick count below 2^29 is too, and the product of a 24-bit and a 29-bit significand fits in 53 bits, so the multiplication is exact and `floor` rounds once, identically on every platform. `ClipTimeExactnessTest` compares it with exact integer arithmetic over 70 speeds x 601 elapsed values; mutation M09 (a `Float` product) makes it fail. `AnimatorReplayTest` drives speeds taken raw off a recorded analogue axis and replays 3600 ticks bit-exactly for three pilots.

**Replay.** `AnimatorReplayTest` (in `udea-replay`) records a Fleks world of three foxes whose only system plays, crossfades and speeds up clips from recorded input, and hands a finished once-through clip back to Walk when `isFinished` says so. It replays through `ReplayVerifier` with the production `SnapshotService` and `WorldHasher`. Transcript from its XML report:

```
[animator-replay] pilot 1: 3600 ticks bit-exact, 61 plays, 30 crossfades, 31 finishes
[animator-replay] pilot 577: 3600 ticks bit-exact, 59 plays, 23 crossfades, 31 finishes
[animator-replay] pilot 1592651973: 3600 ticks bit-exact, 58 plays, 21 crossfades, 29 finishes
```

The second test changes one press at tick 1200 and requires the divergence to be reported at exactly that tick. A replay of a real moba session with the fox is not included: the fox lives only in the net proof, not in a match.

**Not done, deliberately:**

- Pose rendering is A3 (#242). Nothing on screen changes in this ticket, so there are no screenshots (section 4).
- Driving an Animator from the agent tool surface. The issue's design prose says mutations go "through the normal simulation path", and they do (systems, `BarrierAction`). The `@Net` fields keep the contract default `agentWritable = false`, so the agent's `world.*` field writes cannot set them. Opening that is a decision for A4 (the editor).
- `ClipPlayback`'s public `clipTime`, `isFinished` and `holds` are read only by `Animator` today. They are public because A3's renderer needs the *previous* clip's time to blend two poses.

**For the lead:** `AGENTS.md` does not list components, so nothing in it goes stale. If you want one line, it belongs under "The tick model": "Animation time is `Animator.clipTime(now)`, derived from a start `Tick`, never accumulated." I have not edited `AGENTS.md`.

## 3. `sh gradlew build`

The first full run (`build-full.log`) failed, and every failure was this branch's:

- `:udea-core:compileTestKotlinIosArm64` and `...IosSimulatorArm64`: Kotlin/Native refuses a comma in a test name (`AnimatorTest.kt:54:9 Name contains illegal characters: ","`, four names).
- `:moba:desktop:test`: `MobaReplayFixturesCurrentTest` and `MobaReplayEqualityTest` refused the checked-in `.udearep` files (`protoHash: recorded 0x1bef, this build 0x0d1e`, and a changed asset graph hash), and `TestLevelRosterTest` differed on its first line only, `worldHash=ace4dd33751828cf` against `worldHash=bf7638fec93721f1`. The other 29 lines (every unit's id, team, kind and position bits) matched.
- `:udea-assets-compiler:test`: `MigratedCorpusCompilesTest` and `MigratedCorpusGapTest` pin the game's script list and kind set, and the fox added `models/fox.udea.kts` and `model`.

Fixes, in `a39d2c2`: the names lost their commas; the two replay fixtures were rebuilt with the command the test prints, `sh gradlew :moba:desktop:test -Dupdate.replay.fixtures=true`; the roster's world hash was updated (`WorldHasher.hash` folds every registered type's id and slot count, including a type with no entities, so registering the Animator moves it and nothing else); the corpus lists gained the fox. No other file names the old values (`grep` for `1bef`, `d240e7a584c3492c` and `ace4dd33751828cf` finds only the old briefs 192, 212 and 228).

Second full run at `a39d2c2`, `ANDROID_HOME=/home/shaun/Android/Sdk JAVA_HOME=.../21.0.11-tem sh gradlew build --continue`, tail of `build-full-2.log`:

```
BUILD SUCCESSFUL in 52s
949 actionable tasks: 38 executed, 911 up-to-date
Configuration cache entry reused.
```

It is short because the first run had already executed everything the fixes did not touch; "up-to-date" is Gradle's record of those tasks' successful runs against unchanged inputs. The box had one other developer's `:udea-editor:test` running during the first run and nothing heavy during the second.

Also run (`verifiers.log`): `sh gradlew udeaVerifyDeterminism udeaVerifyModuleGraph udeaVerifyAgentsMd`, `BUILD SUCCESSFUL in 18s`. `udeaVerifyDeterminism` prints its own caveat that it is a first filter; the determinism evidence is the replay test above.

No GL: nothing in `udea-render` or the render half of `udea-agent-host` changed, so the xvfb run was not needed.

## 4. Images

None. This ticket has no visual output: `Animator` is simulation state, and drawing a pose from it is #242. The proof that it works is the transcripts above, and I would rather say that than post a picture of an unchanged screen.

## 5. The issue, criterion by criterion

| Criterion | Proof |
|---|---|
| `play`, `crossfade`, `clipTime`, `isFinished` across loop, once, speed and tick boundaries, headless, each seen red | `AnimatorTest` (18) and `ClipTimeExactnessTest` in `udea-core` `commonTest`, run on JVM here. 9 of the 18 went red against stub implementations first (`core-red-1.log`: `18 tests completed, 9 failed`), and every one of the 18 goes red under at least one mutation in section 6 (M01-M10, M15-M20). `scratchpad/issue241/redset.py` computes that set from the saved results and reports no test outside it. Boundaries covered: the tick a once-clip ends on, wrap to zero at the length, before the start, zero-length clips, half and double speed, a fade's first and last tick. |
| A misspelled clip name fails the build with a did-you-mean: golden test plus failing-compile test | Golden: `ModelClipAccessorsTest > the fox generates the golden Fox object` against `udea-assets-compiler/src/test/resources/golden/Fox.kt.txt`. Failing compile: `UdeaAnimationClipCheckerTest` (8), red before the checker existed (`clipchecker-red.log`: `8 tests completed, 4 failed`). In the real game, `Fox.Clips.Survey` misspelt `Survy` in `NetProofFox.kt` (`moba-typo.diff`) fails `:moba:game:compileKotlinJvm` with, from `moba-typo-compile.log` line 214: `e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a82e2f4966ae334d7/moba/game/src/commonMain/kotlin/dev/wildware/moba/net/NetProofFox.kt:53:66 UDEA0016: Fox.Clips has no clip 'Survy'. Did you mean 'Survey'?` |
| `Animator` state replicates: `runNetProof` hashes agree with an animated entity in the world | Section 1: all three runs `units and animation AGREED`, `animated=1/1 MATCH`; red twice when replication is reverted. `AnimatorSnapshotTypeTest` pins that every field is in the net mask and that name, mask bit and store column line up. |
| A replay of an animated session reproduces the same state | `AnimatorReplayTest`, 3600 ticks bit-exact for three pilots, and a changed press diverging on its own tick. M14 (an unseeded random start tick) turns both tests red. |
| `udeaVerifyDeterminism` green, lock files match the generator | Section 3 and section 7. |

## 6. Mutations

Each row is the literal `git diff` the runner applied (`scratchpad/issue241/mutate.py`) and the failures it read back from the test XML reports. Every diff was reverted before the next row. The runner prints `git status` when it ends: after the first batch (M01-M14) it showed one file I was editing on purpose at the same time, `NetStateProbe.kt`, now commit `b5725bc`; after M15-M20 and after M21 it was empty. M01-M14 ran against `655bb81`; M15-M21 against `a39d2c2`, where `Animator.kt` is byte-identical (both diffs start from blob `0ee43a8`).

#### M01-repeat-no-wrap

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
index 0ee43a8..4c10bb0 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
@@ -47,7 +47,7 @@ public class ClipPlayback(
         val scaled = scaledElapsed(now)
         return Ticks(
             when (loop) {
-                Loop.Repeat -> scaled % length
+                Loop.Repeat -> scaled
                 Loop.Once -> minOf(scaled, length)
             },
         )
```

```
M01-repeat-no-wrap: exit 1, 3 of 22 tests failed
  AnimatorTest > a looping clip counts up one tick per tick and wraps to zero at its length()[jvm]
  AnimatorTest > playing again restarts from the new tick and forgets the old loop mode()[jvm]
  AnimatorTest > speed scales elapsed ticks and rounds down to a whole tick of the clip()[jvm]
```

#### M02-round-not-floor

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
index 0ee43a8..1d28e2e 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
@@ -73,7 +73,7 @@ public class ClipPlayback(
     private fun scaledElapsed(now: Tick): Long {
         val elapsed = now.ticksSince(start)
         if (elapsed <= 0L) return 0L
-        return floor(elapsed.toDouble() * speed.toDouble()).toLong()
+        return kotlin.math.round(elapsed.toDouble() * speed.toDouble()).toLong()
     }
 
     internal fun set(clip: AnimationClip, now: Tick, loop: Loop, speed: Float) {
```

```
M02-round-not-floor: exit 1, 2 of 22 tests failed
  AnimatorTest > speed scales elapsed ticks and rounds down to a whole tick of the clip()[jvm]
  ClipTimeExactnessTest > clip time is the exact floor of elapsed times speed()[jvm]
```

#### M03-finished-strict

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
index 0ee43a8..395d04d 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
@@ -55,7 +55,7 @@ public class ClipPlayback(
 
     /** Whether a [Loop.Once] clip has reached its end by [now]. See [Animator.isFinished]. */
     public fun isFinished(now: Tick): Boolean =
-        !isEmpty() && loop == Loop.Once && (length == 0L || scaledElapsed(now) >= length)
+        !isEmpty() && loop == Loop.Once && (length == 0L || scaledElapsed(now) > length)
 
     /**
      * `(now - start) * speed`, rounded down, and never below zero.
```

```
M03-finished-strict: exit 1, 3 of 22 tests failed
  AnimatorTest > a slow clip takes twice as many ticks at half speed()[jvm]
  AnimatorTest > a clip played once holds its last tick and finishes exactly on it()[jvm]
  AnimatorTest > a fast clip played once finishes on the first tick its scaled time reaches the length()[jvm]
```

#### M04-no-before-start-clamp

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
index 0ee43a8..f9442f4 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
@@ -72,7 +72,6 @@ public class ClipPlayback(
      */
     private fun scaledElapsed(now: Tick): Long {
         val elapsed = now.ticksSince(start)
-        if (elapsed <= 0L) return 0L
         return floor(elapsed.toDouble() * speed.toDouble()).toLong()
     }
 
```

```
M04-no-before-start-clamp: exit 1, 1 of 22 tests failed
  AnimatorTest > before its start a clip reads zero, not a negative time()[jvm]
```

#### M05-crossfade-keeps-no-previous

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
index 0ee43a8..cf5474f 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
@@ -186,7 +186,6 @@ public class Animator(
         speed: Float = 1f,
     ) {
         require(over.count >= 0L) { "a crossfade to '${clip.name}' over $over; a fade cannot be negative" }
-        previous.copyFrom(current)
         current.set(clip, now, loop, speed)
         fadeStart = now
         fadeLength = over.count
```

```
M05-crossfade-keeps-no-previous: exit 1, 2 of 22 tests failed
  AnimatorTest > the clip faded out keeps its own clock, so it does not jump back to its first frame()[jvm]
  AnimatorTest > a crossfade starts the new clip now and blends it in over the fade()[jvm]
```

#### M06-play-keeps-previous

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
index 0ee43a8..324cfb2 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
@@ -165,7 +165,6 @@ public class Animator(
      */
     public fun play(clip: AnimationClip, now: Tick, loop: Loop = Loop.Repeat, speed: Float = 1f) {
         current.set(clip, now, loop, speed)
-        previous.clear()
         fadeStart = now
         fadeLength = 0L
     }
```

```
M06-play-keeps-previous: exit 1, 1 of 22 tests failed
  AnimatorTest > play after a crossfade ends the blend at once()[jvm]
```

#### M07-blend-no-ramp

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
index 0ee43a8..43eb1f4 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
@@ -220,7 +220,7 @@ public class Animator(
             elapsed >= fadeLength -> 1f
             // One IEEE division of two exactly-represented integers (a fade is far shorter than
             // 2^24 ticks), so every platform rounds it to the same float.
-            else -> elapsed.toFloat() / fadeLength.toFloat()
+            else -> 1f
         }
     }
 
```

```
M07-blend-no-ramp: exit 1, 1 of 22 tests failed
  AnimatorTest > a crossfade starts the new clip now and blends it in over the fade()[jvm]
```

#### M08-speed-unchecked

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
index 0ee43a8..7f75850 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
@@ -77,7 +77,7 @@ public class ClipPlayback(
     }
 
     internal fun set(clip: AnimationClip, now: Tick, loop: Loop, speed: Float) {
-        require(speed >= 0f && speed.isFinite()) {
+        require(true) {
             "clip '${clip.name}' asked for speed $speed; a speed is a finite multiplier, zero or more"
         }
         this.clip = clip.index
```

```
M08-speed-unchecked: exit 1, 1 of 22 tests failed
  AnimatorTest > a negative or non-finite speed is refused at the call()[jvm]
```

#### M09-float-product

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
index 0ee43a8..72a0019 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
@@ -73,7 +73,7 @@ public class ClipPlayback(
     private fun scaledElapsed(now: Tick): Long {
         val elapsed = now.ticksSince(start)
         if (elapsed <= 0L) return 0L
-        return floor(elapsed.toDouble() * speed.toDouble()).toLong()
+        return floor(elapsed.toFloat() * speed).toLong()
     }
 
     internal fun set(clip: AnimationClip, now: Tick, loop: Loop, speed: Float) {
```

```
M09-float-product: exit 1, 1 of 22 tests failed
  ClipTimeExactnessTest > clip time is the exact floor of elapsed times speed()[jvm]
```

#### M10-elapsed-ignores-start

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
index 0ee43a8..91a1387 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
@@ -71,7 +71,7 @@ public class ClipPlayback(
      * `ClipTimeExactnessTest` checks the result against integer arithmetic.
      */
     private fun scaledElapsed(now: Tick): Long {
-        val elapsed = now.ticksSince(start)
+        val elapsed = now.value
         if (elapsed <= 0L) return 0L
         return floor(elapsed.toDouble() * speed.toDouble()).toLong()
     }
```

```
M10-elapsed-ignores-start: exit 1, 10 of 22 tests failed
  AnimatorTest > a slow clip takes twice as many ticks at half speed()[jvm]
  AnimatorTest > a looping clip counts up one tick per tick and wraps to zero at its length()[jvm]
  AnimatorTest > playing again restarts from the new tick and forgets the old loop mode()[jvm]
  AnimatorTest > a clip played once holds its last tick and finishes exactly on it()[jvm]
  AnimatorTest > a fast clip played once finishes on the first tick its scaled time reaches the length()[jvm]
  AnimatorTest > before its start a clip reads zero, not a negative time()[jvm]
  AnimatorTest > the clip faded out keeps its own clock, so it does not jump back to its first frame()[jvm]
  AnimatorTest > a crossfade starts the new clip now and blends it in over the fade()[jvm]
  AnimatorTest > speed scales elapsed ticks and rounds down to a whole tick of the clip()[jvm]
  ClipTimeExactnessTest > clip time is the exact floor of elapsed times speed()[jvm]
```

#### M11-seconds-round-nearest

```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/gen/GltfClips.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/gen/GltfClips.kt
index 6333414..37c0872 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/gen/GltfClips.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/gen/GltfClips.kt
@@ -69,7 +69,7 @@ internal object GltfClips {
      * cost is at most one tick held on the final pose. Never below zero.
      */
     fun ticksOf(seconds: Float): Long =
-        maxOf(0L, ceil(seconds.toDouble() * TICKS_PER_SECOND - WHOLE_TICK_TOLERANCE).toLong())
+        maxOf(0L, kotlin.math.round(seconds.toDouble() * TICKS_PER_SECOND).toLong())
 
     /** Every clip in [file], in the file's order, or a failure saying what is wrong. */
     fun read(file: Path): Result<List<GltfClip>> {
```

```
M11-seconds-round-nearest: exit 1, 3 of 35 tests failed
  GltfClipsTest > seconds become ticks by rounding up, forgiving float32's error on a whole tick()
  GltfClipsTest > the fox's three clips come out in file order with their lengths in ticks()
  ModelClipAccessorsTest > the fox generates the golden Fox object()
```

#### M12-checker-unregistered

```diff
diff --git a/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaFirExtensionRegistrar.kt b/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaFirExtensionRegistrar.kt
index bccb8a0..7dc72ae 100644
--- a/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaFirExtensionRegistrar.kt
+++ b/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaFirExtensionRegistrar.kt
@@ -85,7 +85,7 @@ internal class UdeaFirAdditionalCheckers(
 
         /** Issue #241: `Fox.Clips.Rnu` gets a did-you-mean. Silent on every name that is not a clip. */
         override val propertyAccessExpressionCheckers: Set<FirExpressionChecker<FirPropertyAccessExpression>> =
-            setOf(UdeaAnimationClipChecker)
+            setOf()
 
         /** Issue #192: loops in a `.udea.kts`. [UdeaAssetLoopChecker] is silent in any other file. */
         override val loopExpressionCheckers: Set<FirExpressionChecker<FirLoop>> =
```

```
M12-checker-unregistered: exit 1, 4 of 8 tests failed
  UdeaAnimationClipCheckerTest > a name like no clip at all lists the clips the model has()
  UdeaAnimationClipCheckerTest > a misspelling through a value of the clips object is caught the same way()
  UdeaAnimationClipCheckerTest > a misspelled clip is an error at the name, with a did-you-mean()
  UdeaAnimationClipCheckerTest > a wrong-case clip name is suggested in its real case()
```

#### M13-checker-default-budget

```diff
diff --git a/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaAnimationClipChecker.kt b/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaAnimationClipChecker.kt
index 2bba6b9..8a6d34b 100644
--- a/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaAnimationClipChecker.kt
+++ b/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaAnimationClipChecker.kt
@@ -69,7 +69,7 @@ internal object UdeaAnimationClipChecker : FirExpressionChecker<FirPropertyAcces
         val asked = reference.name.asString()
         val owner = holder.classId.relativeClassName.asString()
         val head = "$owner has no clip '$asked'"
-        val suggestion = DidYouMean.suggest(asked, clips, maxOf(DidYouMean.defaultMaxDistance(asked), MIN_BUDGET))
+        val suggestion = DidYouMean.suggest(asked, clips)
         val detail = if (suggestion != null) {
             "$head. Did you mean '$suggestion'?"
         } else {
```

```
M13-checker-default-budget: exit 1, 1 of 8 tests failed
  UdeaAnimationClipCheckerTest > a misspelled clip is an error at the name, with a did-you-mean()
```

#### M14-unseeded-start

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
index 0ee43a8..53ca150 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
@@ -82,7 +82,7 @@ public class ClipPlayback(
         }
         this.clip = clip.index
         this.length = clip.length.count
-        this.start = now
+        this.start = Tick(now.value + kotlin.random.Random.Default.nextInt(2))
         this.speed = speed
         this.loop = loop
     }
```

```
M14-unseeded-start: exit 1, 2 of 2 tests failed
  AnimatorReplayTest > a replay fed one different press diverges at exactly that tick()[jvm]
  AnimatorReplayTest > an animated session replays bit-identically, for every pilot()[jvm]
```

#### M15-repeat-can-finish

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
index 0ee43a8..d051daf 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
@@ -55,7 +55,7 @@ public class ClipPlayback(
 
     /** Whether a [Loop.Once] clip has reached its end by [now]. See [Animator.isFinished]. */
     public fun isFinished(now: Tick): Boolean =
-        !isEmpty() && loop == Loop.Once && (length == 0L || scaledElapsed(now) >= length)
+        !isEmpty() && (length == 0L || scaledElapsed(now) >= length)
 
     /**
      * `(now - start) * speed`, rounded down, and never below zero.
```

```
M15-repeat-can-finish: exit 1, 1 of 22 tests failed
  AnimatorTest > a looping clip never finishes()[jvm]
```

#### M16-speed-zero-refused

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
index 0ee43a8..b12f47e 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
@@ -77,7 +77,7 @@ public class ClipPlayback(
     }
 
     internal fun set(clip: AnimationClip, now: Tick, loop: Loop, speed: Float) {
-        require(speed >= 0f && speed.isFinite()) {
+        require(speed > 0f && speed.isFinite()) {
             "clip '${clip.name}' asked for speed $speed; a speed is a finite multiplier, zero or more"
         }
         this.clip = clip.index
```

```
M16-speed-zero-refused: exit 1, 2 of 22 tests failed
  AnimatorTest > a clip at speed zero is frozen on its first tick and never finishes()[jvm]
  ClipTimeExactnessTest > clip time is the exact floor of elapsed times speed()[jvm]
```

#### M17-zero-fade-divides

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
index 0ee43a8..bf1dfcd 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
@@ -213,7 +213,7 @@ public class Animator(
      * the tick it ends; `1` with no fade, and with nothing to fade from.
      */
     public fun blendWeight(now: Tick): Float {
-        if (fadeLength <= 0L || previous.isEmpty()) return 1f
+        if (fadeLength < 0L || previous.isEmpty()) return 1f
         val elapsed = now.ticksSince(fadeStart)
         return when {
             elapsed <= 0L -> 0f
```

```
M17-zero-fade-divides: exit 1, 1 of 22 tests failed
  AnimatorTest > a crossfade over zero ticks is a cut()[jvm]
```

#### M18-blend-from-nothing

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
index 0ee43a8..429af32 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
@@ -213,7 +213,7 @@ public class Animator(
      * the tick it ends; `1` with no fade, and with nothing to fade from.
      */
     public fun blendWeight(now: Tick): Float {
-        if (fadeLength <= 0L || previous.isEmpty()) return 1f
+        if (fadeLength <= 0L) return 1f
         val elapsed = now.ticksSince(fadeStart)
         return when {
             elapsed <= 0L -> 0f
```

```
M18-blend-from-nothing: exit 1, 1 of 22 tests failed
  AnimatorTest > a crossfade from nothing is a play with nothing to blend from()[jvm]
```

#### M19-negative-fade-accepted

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
index 0ee43a8..412e10e 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
@@ -185,7 +185,7 @@ public class Animator(
         loop: Loop = Loop.Repeat,
         speed: Float = 1f,
     ) {
-        require(over.count >= 0L) { "a crossfade to '${clip.name}' over $over; a fade cannot be negative" }
+        require(true) { "a crossfade to '${clip.name}' over $over; a fade cannot be negative" }
         previous.copyFrom(current)
         current.set(clip, now, loop, speed)
         fadeStart = now
```

```
M19-negative-fade-accepted: exit 1, 1 of 22 tests failed
  AnimatorTest > a negative fade length is refused at the call()[jvm]
```

#### M20-empty-defaults-to-clip-zero

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
index 0ee43a8..6c435ec 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
@@ -25,7 +25,7 @@ import kotlin.math.floor
 @Serializable
 public class ClipPlayback(
     /** The clip's [AnimationClip.index], or [NO_CLIP]. */
-    public var clip: Int = NO_CLIP,
+    public var clip: Int = 0,
     /** The clip's [AnimationClip.length] in ticks, carried so a client needs no asset to loop it. */
     public var length: Long = 0L,
     /** The tick the clip's first frame was on. */
```

```
M20-empty-defaults-to-clip-zero: exit 1, 3 of 22 tests failed
  AnimatorTest > play after a crossfade ends the blend at once()[jvm]
  AnimatorTest > a crossfade from nothing is a play with nothing to blend from()[jvm]
  AnimatorTest > an animator that has played nothing reads zero and is not finished()[jvm]
```

#### M21-schema-columns-swapped

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
index 0ee43a8..9d084a6 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Animator.kt
@@ -250,7 +250,7 @@ public class Animator(
 
         /** One lowered [ClipPlayback]: `clip`, `length`, `loop`, `speed`, `start`. */
         private val PLAYBACK_KINDS: List<FieldKind> =
-            listOf(FieldKind.Int, FieldKind.Long, FieldKind.Int, FieldKind.Float, FieldKind.Tick)
+            listOf(FieldKind.Long, FieldKind.Int, FieldKind.Int, FieldKind.Float, FieldKind.Tick)
 
         /** `fadeLength`, `fadeStart`. */
         private val FADE_KINDS: List<FieldKind> = listOf(FieldKind.Long, FieldKind.Tick)
```

```
M21-schema-columns-swapped: exit 1, 1 of 22 tests failed
  AnimatorSnapshotTypeTest > every field of an animator comes back from a snapshot()[jvm]
```

## 7. Regenerated files

- `udea-core/net-protocol.lock`, by `sh gradlew :udea-core:udeaWriteProtocolLock`: protoHash `0x0826` to `0xa328`, and `component 27 dev.wildware.udea.core.spatial.Animator` appended with 12 fields (`current.clip`, `current.length`, `current.loop`, `current.speed`, `current.start`, `fadeLength`, `fadeStart`, then the same five for `previous`). **No existing id moved**: 22-26 are unchanged, because the name sorts after `core.physics.Teleport`.
- `net-components.lock` (the global list): one line appended, `dev.wildware.udea.core.spatial.Animator`, in sorted position.
- `udea-codegen/net-protocol.lock` and `udea-codegen/src/test/resources/expected-generated-hashes.txt`: both tasks re-run (`udeaWriteProtocolLock`, `:udea-codegen:test -Pudea.updateGeneratedHashes=true`); **no change**.
- `moba/game/net-protocol.lock`: re-run, **no change** (it lists moba's own components).
- `moba/desktop/src/test/resources/fixtures/moba-3600.udearep` and `moba-36000.udearep`: rebuilt with `-Dupdate.replay.fixtures=true`, because protoHash and the asset graph hash moved.
- `moba/desktop/src/test/resources/levels/test_level.roster.txt`: line 1 only, as section 3 explains.
- `udea-assets-compiler/src/test/resources/golden/Fox.kt.txt`: new, written from the generator's output; its three clips carry the lengths `GltfClipsTest` derives independently from `Fox.glb` (Survey 205, Walk 43, Run 70).
