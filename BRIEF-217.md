d7b6e2e

# BRIEF - issue #217: `udeaVerifyDeterminism` sees the clocks Kotlin common code can reach

Branch `issue-217-determinism-timesource`, off `origin/kmp` at `7073adc`. Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-ab6b17f2ef1d868c5`.

## 1. Evidence command

```
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew -p build-logic test --tests 'dev.wildware.udea.build.determinism.KotlinClockTest'
```

Green on this branch (5 tests). **Red with the feature reverted**: `DeterminismRules.kt` put back to `origin/kmp` (`git checkout origin/kmp -- build-logic/src/main/kotlin/dev/wildware/udea/build/determinism/DeterminismRules.kt`, diffstat `2 insertions(+), 30 deletions(-)` against HEAD), test file unchanged, then restored. From `M0-revert-rules.log`:

```
> Task :test FAILED
KotlinClockTest > asking a monotonic mark how much time has passed is a wall-clock read() FAILED
KotlinClockTest > kotlinx datetime Clock System now fails the scan as DET003() FAILED
KotlinClockTest > TimeSource Monotonic markNow planted in udea-core commonMain fails the scan as DET001() FAILED
KotlinClockTest > kotlin time Clock System now planted in udea-core commonMain fails the scan as DET003() FAILED
5 tests completed, 4 failed
BUILD FAILED in 2s
```

The fifth test is the control (interface-typed clocks and presentation code are *not* findings); it passes on both sides by design, and M6 below is what makes it go red.

It is a build-logic test rather than a gameplay scenario because the ticket is a rule in a bytecode scanner. The end-to-end half - real `kotlinc` output, the real task - is the planted transcript in section 2.

## 2. Summary

**The gap.** `DET001`/`DET003` matched `java.lang.System`, `java.time`, `Date` and `Calendar` owners only. In `commonMain` none of those resolve; `kotlin.time.TimeSource.Monotonic` and `Clock.System` do, and the scan let them through.

Reproduced before any change: `TimeSource.Monotonic.markNow()` and `Clock.System.now()` planted in `udea-core/src/commonMain/kotlin/dev/wildware/udea/core/planted217/PlantedClocks217.kt`, then `sh gradlew udeaVerifyDeterminism` (`plant-before-fix-217.log`):

```
> Task :udeaVerifyDeterminism
udeaVerifyDeterminism
  scanned :udea-core: 562 class files
  scanned :udea-gas: 272 class files
  scanned :udea-net: 421 class files
  scanned :moba: 370 class files
  allowlist entries used: 0
  findings: 0
...
BUILD SUCCESSFUL in 25s
```

**The change** (`DeterminismRules.kt` only):

- **DET001** (wall clock) also matches any reference to `kotlin.time.TimeSource$Monotonic` (`INSTANCE`, `markNow-z9LOYto`) and the clock-reading members of `TimeSource$Monotonic$ValueTimeMark`, by name prefix because the JVM names are mangled: `elapsedNow`, `hasPassedNow`, `hasNotPassedNow`.
- **DET003** (calendar time) also matches any reference to `kotlin.time.Clock$System` or `kotlinx.datetime.Clock$System`.

Same plant, extended to marks, `measureTime`, arithmetic on a mark and interface-typed clocks, after the change (`plant-after-fix-217.log`; the plant was then deleted, its copy is `PlantedClocks217.kt` in the scratchpad):

```
> Task :udeaVerifyDeterminism FAILED
...
    scanned :udea-core: 562 class files
    scanned :udea-gas: 272 class files
    scanned :udea-net: 421 class files
    scanned :moba: 370 class files
    allowlist entries used: 0
    findings: 10
  udea-core/src/commonMain/kotlin/dev/wildware/udea/core/planted217/PlantedClocks217.kt:12:1: error: [DET001] dev.wildware.udea.core.planted217.PlantedClocks217Kt.plantedElapsed-6eNON_k is declared simulation (:udea-core) and reads the wall clock: it references kotlin.time.TimeSource$Monotonic$ValueTimeMark.elapsedNow-UwyO8pc.
      did you mean: SimClock.tick - a tick is the simulation's only clock; seconds are a presentation unit (spec 5)
  udea-core/src/commonMain/kotlin/dev/wildware/udea/core/planted217/PlantedClocks217.kt:14:1: error: [DET001] dev.wildware.udea.core.planted217.PlantedClocks217Kt.plantedPassed-6eNON_k is declared simulation (:udea-core) and reads the wall clock: it references kotlin.time.TimeSource$Monotonic$ValueTimeMark.hasPassedNow-impl.
      did you mean: SimClock.tick - a tick is the simulation's only clock; seconds are a presentation unit (spec 5)
  udea-core/src/commonMain/kotlin/dev/wildware/udea/core/planted217/PlantedClocks217.kt:16:1: error: [DET001] dev.wildware.udea.core.planted217.PlantedClocks217Kt.plantedNotPassed-6eNON_k is declared simulation (:udea-core) and reads the wall clock: it references kotlin.time.TimeSource$Monotonic$ValueTimeMark.hasNotPassedNow-impl.
      did you mean: SimClock.tick - a tick is the simulation's only clock; seconds are a presentation unit (spec 5)
  udea-core/src/commonMain/kotlin/dev/wildware/udea/core/planted217/PlantedClocks217.kt:26:1: error: [DET001] dev.wildware.udea.core.planted217.PlantedClocks217Kt.plantedMeasure is declared simulation (:udea-core) and reads the wall clock: it references kotlin.time.TimeSource$Monotonic.INSTANCE.
      did you mean: SimClock.tick - a tick is the simulation's only clock; seconds are a presentation unit (spec 5)
  udea-core/src/commonMain/kotlin/dev/wildware/udea/core/planted217/PlantedClocks217.kt:27:1: error: [DET001] dev.wildware.udea.core.planted217.PlantedClocks217Kt.plantedMeasure is declared simulation (:udea-core) and reads the wall clock: it references kotlin.time.TimeSource$Monotonic.markNow-z9LOYto.
      did you mean: SimClock.tick - a tick is the simulation's only clock; seconds are a presentation unit (spec 5)
  udea-core/src/commonMain/kotlin/dev/wildware/udea/core/planted217/PlantedClocks217.kt:29:1: error: [DET001] dev.wildware.udea.core.planted217.PlantedClocks217Kt.plantedMeasure is declared simulation (:udea-core) and reads the wall clock: it references kotlin.time.TimeSource$Monotonic$ValueTimeMark.elapsedNow-UwyO8pc.
      did you mean: SimClock.tick - a tick is the simulation's only clock; seconds are a presentation unit (spec 5)
  udea-core/src/commonMain/kotlin/dev/wildware/udea/core/planted217/PlantedClocks217.kt:8:1: error: [DET001] dev.wildware.udea.core.planted217.PlantedClocks217Kt.plantedMonotonic is declared simulation (:udea-core) and reads the wall clock: it references kotlin.time.TimeSource$Monotonic.INSTANCE.
      did you mean: SimClock.tick - a tick is the simulation's only clock; seconds are a presentation unit (spec 5)
  udea-core/src/commonMain/kotlin/dev/wildware/udea/core/planted217/PlantedClocks217.kt:8:1: error: [DET001] dev.wildware.udea.core.planted217.PlantedClocks217Kt.plantedMonotonic is declared simulation (:udea-core) and reads the wall clock: it references kotlin.time.TimeSource$Monotonic.markNow-z9LOYto.
      did you mean: SimClock.tick - a tick is the simulation's only clock; seconds are a presentation unit (spec 5)
  udea-core/src/commonMain/kotlin/dev/wildware/udea/core/planted217/PlantedClocks217.kt:10:1: error: [DET003] dev.wildware.udea.core.planted217.PlantedClocks217Kt.plantedSystemClock is declared simulation (:udea-core) and reads calendar time: it references kotlin.time.Clock$System.INSTANCE.
      did you mean: SimClock.tick, or record the value at build time if it is metadata
  udea-core/src/commonMain/kotlin/dev/wildware/udea/core/planted217/PlantedClocks217.kt:10:1: error: [DET003] dev.wildware.udea.core.planted217.PlantedClocks217Kt.plantedSystemClock is declared simulation (:udea-core) and reads calendar time: it references kotlin.time.Clock$System.now.
      did you mean: SimClock.tick, or record the value at build time if it is metadata
...
BUILD FAILED in 1m 5s
```

No finding for `plantedLater` (line 18, `mark + by`), `plantedInjectedSource` (line 20, `source.markNow()` on a `TimeSource` parameter) or `plantedInjectedClock` (line 22, `clock.now()` on a `Clock` parameter) - the intended negatives.

**Decisions** (each commented on #217):

- Extend DET001/DET003 rather than add DET007: the same defect on another platform, and no new id in the allowlist vocabulary. https://github.com/wildware-uk/Udea/issues/217#issuecomment-5707821182
- A clock received **through its interface** is not matched: it may be a tick-driven clock, and the scan cannot see the receiver. Recorded as a blind-spot row in `determinism-audit.md` section 1 (same comment).
- Mark arithmetic and value-class boxing are not matched, so one read is not reported several times over.
- Scope untouched: only `DeterminismRules.SIMULATION_SCOPES`. udea-audio's `Clock.System` seed and udea-agent's `TimeSource.Monotonic` are outside it. The real tree has no finding under the new rules (section 3).
- `udea-gas`'s `udeaVerifyGasTime` **kept**: it is a source-text scan of every `*Main` source set (so it covers `wasmJsMain`), and it also forbids `kotlin.time.Duration`, `com.badlogic.gdx` and `deltaTime`. Not equivalent. https://github.com/wildware-uk/Udea/issues/217#issuecomment-5707821649

**Why the test's fixtures are ASM, not `javac`.** Every other determinism test transliterates Kotlin to Java. That cannot express `markNow-z9LOYto` (a hyphen is not a Java identifier). So `KotlinClockTest` writes class files with ASM carrying the owners, names and descriptors `kotlinc` 2.4.20 emitted for the plant above, laid out as a multiplatform module (`src/commonMain/kotlin` + `build/classes/kotlin/jvm/main`) and scanned through `DeterminismLayout` against the real declared scopes - the task's own path. The `javap -c -p -l` it was copied from is `plant-javap-217.txt`; below is every line of that file containing `kotlin/time`, in file order (a filter, not a contiguous excerpt):

```
       0: getstatic     #13                 // Field kotlin/time/TimeSource$Monotonic.INSTANCE:Lkotlin/time/TimeSource$Monotonic;
       3: invokevirtual #17                 // Method kotlin/time/TimeSource$Monotonic."markNow-z9LOYto":()J
       6: invokestatic  #23                 // Method kotlin/time/TimeSource$Monotonic$ValueTimeMark."box-impl":(J)Lkotlin/time/TimeSource$Monotonic$ValueTimeMark;
       0: getstatic     #29                 // Field kotlin/time/Clock$System.INSTANCE:Lkotlin/time/Clock$System;
       3: invokevirtual #33                 // Method kotlin/time/Clock$System.now:()Lkotlin/time/Instant;
       1: invokestatic  #39                 // Method kotlin/time/TimeSource$Monotonic$ValueTimeMark."elapsedNow-UwyO8pc":(J)J
       4: invokestatic  #44                 // Method kotlin/time/Duration."box-impl":(J)Lkotlin/time/Duration;
       1: invokestatic  #51                 // Method kotlin/time/TimeSource$Monotonic$ValueTimeMark."hasPassedNow-impl":(J)Z
       1: invokestatic  #55                 // Method kotlin/time/TimeSource$Monotonic$ValueTimeMark."hasNotPassedNow-impl":(J)Z
       2: invokestatic  #61                 // Method kotlin/time/TimeSource$Monotonic$ValueTimeMark."plus-LRDsOJo":(JJ)J
       5: invokestatic  #23                 // Method kotlin/time/TimeSource$Monotonic$ValueTimeMark."box-impl":(J)Lkotlin/time/TimeSource$Monotonic$ValueTimeMark;
       7: invokeinterface #78,  1           // InterfaceMethod kotlin/time/TimeSource.markNow:()Lkotlin/time/TimeMark;
          0      13     0 source   Lkotlin/time/TimeSource;
       7: invokeinterface #86,  1           // InterfaceMethod kotlin/time/Clock.now:()Lkotlin/time/Instant;
          0      13     0 clock   Lkotlin/time/Clock;
       2: getstatic     #13                 // Field kotlin/time/TimeSource$Monotonic.INSTANCE:Lkotlin/time/TimeSource$Monotonic;
       9: invokevirtual #17                 // Method kotlin/time/TimeSource$Monotonic."markNow-z9LOYto":()J
      19: invokestatic  #39                 // Method kotlin/time/TimeSource$Monotonic$ValueTimeMark."elapsedNow-UwyO8pc":(J)J
      23: invokestatic  #44                 // Method kotlin/time/Duration."box-impl":(J)Lkotlin/time/Duration;
          6      16     1 $this$measureTime$iv$iv   Lkotlin/time/TimeSource$Monotonic;
```

**Found, not changed:**

- Spans for **inlined** code point past the end of the file: the `measureTime` findings above say lines 26, 27 and 29 of a 24-line file (Kotlin's SMAP virtual lines). Pre-existing; affects every rule. https://github.com/wildware-uk/Udea/issues/217#issuecomment-5707823543
- Source sets that compile only to a klib (`wasmJsMain`, `udea-core`'s `nonJvmMain`) are never scanned, since ASM reads JVM/Android bytecode. Pre-existing; now a row in `determinism-audit.md` section 1.

## 3. `sh gradlew build --continue`

Baseline, fresh branch at `7073adc` before any change (`baseline-217.log`, 0 lines containing `FAILED`):

```
BUILD SUCCESSFUL in 1m 37s
620 actionable tasks: 429 executed, 191 from cache
```

This branch at `d7b6e2e` (`build-217.log`, 0 lines containing `FAILED`):

```
BUILD SUCCESSFUL in 2m 6s
620 actionable tasks: 499 executed, 2 from cache, 119 up-to-date
```

The gate itself on the real tree, from the same run:

```
> Task :udeaVerifyDeterminism
udeaVerifyDeterminism
  scanned :udea-core: 560 class files
  scanned :udea-gas: 272 class files
  scanned :udea-net: 421 class files
  scanned :moba: 370 class files
  allowlist entries used: 0
  findings: 0
```

`:udea-core` is 560 class files here and on the baseline (`baseline-217.log`), 562 in the two planted runs.

**Tasks this ticket turned green:** none were red; the new tests are new. **Baseline failures, unchanged:** none.

`sh gradlew -p build-logic check` (`buildlogic-check-217.log`):

```
> Task :checkKotlinGradlePluginConfigurationErrors SKIPPED
> Task :testClasses UP-TO-DATE
> Task :test
> Task :check
BUILD SUCCESSFUL in 1m 34s
13 actionable tasks: 4 executed, 9 up-to-date
```

316 tests, 0 failures, 0 skipped, summed over `build-logic/build/test-results/test/*.xml` after that run.

No GL touched, so no xvfb run. Nobody else's module edited. No iOS claim.

## 4. Images

None. Nothing visual.

## 5. Acceptance criteria

| Criterion | Proof |
|---|---|
| `TimeSource.Monotonic.markNow()` planted in a scanned module's commonMain makes `udeaVerifyDeterminism` fail (test in build-logic) | `KotlinClockTest.TimeSource Monotonic markNow planted in udea-core commonMain fails the scan as DET001` (red in M0, M1); real-task transcript in section 2, `PlantedClocks217.kt:8:1` |
| Same for `Clock.System.now()` | `KotlinClockTest.kotlin time Clock System now planted in udea-core commonMain fails the scan as DET003` and `...kotlinx datetime Clock System now...` (red in M0, M4, M5); transcript `PlantedClocks217.kt:10:1` |
| `sh gradlew build --continue` shows no new failure | Section 3: baseline and branch both `BUILD SUCCESSFUL`, 0 `FAILED`; `-p build-logic check` green |

## 6. Mutations

Each is a literal `git diff` taken by the script before it ran `sh gradlew -p build-logic test --tests 'dev.wildware.udea.build.determinism.*'` (all 49 determinism tests), then restored. Failing tests and counts are from each run's log.

### M1-no-monotonic-source: `TimeSource.Monotonic` itself no longer matched

```diff
@@ -158,7 +158,6 @@ public object DeterminismRules {
                 // The monotonic clock multiplatform common code can reach, where `System.nanoTime`
                 // does not resolve (issue #217). Every reference to the object is a use of it:
                 // `INSTANCE` and the mangled `markNow-z9LOYto`.
-                ref.owner == MONOTONIC_TIME_SOURCE ||
                 // A mark taken elsewhere, read against now. `plus`/`minus`/`compareTo` and the
                 // value-class boxing are arithmetic on a reading already taken, so they do not
                 // match: flagging them would report one clock read several times over.
```

```
KotlinClockTest > TimeSource Monotonic markNow planted in udea-core commonMain fails the scan as DET001() FAILED
49 tests completed, 1 failed
```

### M2-no-mark-reads: reads on a mark no longer matched

```diff
@@ -158,14 +158,7 @@ public object DeterminismRules {
                 // The monotonic clock multiplatform common code can reach, where `System.nanoTime`
                 // does not resolve (issue #217). Every reference to the object is a use of it:
                 // `INSTANCE` and the mangled `markNow-z9LOYto`.
-                ref.owner == MONOTONIC_TIME_SOURCE ||
-                // A mark taken elsewhere, read against now. `plus`/`minus`/`compareTo` and the
-                // value-class boxing are arithmetic on a reading already taken, so they do not
-                // match: flagging them would report one clock read several times over.
-                (
-                    ref.owner == MONOTONIC_TIME_MARK &&
-                        MONOTONIC_MARK_READS.any { ref.member.startsWith(it) }
-                    )
+                ref.owner == MONOTONIC_TIME_SOURCE
         },
     )
 
```

```
KotlinClockTest > asking a monotonic mark how much time has passed is a wall-clock read() FAILED
49 tests completed, 1 failed
```

### M3-every-mark-member: every member of a mark matched, arithmetic and boxing included

```diff
@@ -164,7 +164,7 @@ public object DeterminismRules {
                 // match: flagging them would report one clock read several times over.
                 (
                     ref.owner == MONOTONIC_TIME_MARK &&
-                        MONOTONIC_MARK_READS.any { ref.member.startsWith(it) }
+                        true
                     )
         },
     )
```

```
KotlinClockTest > asking a monotonic mark how much time has passed is a wall-clock read() FAILED
KotlinClockTest > TimeSource Monotonic markNow planted in udea-core commonMain fails the scan as DET001() FAILED
49 tests completed, 2 failed
```

### M4-no-system-clocks: `Clock.System` no longer matched at all

```diff
@@ -205,12 +205,7 @@ public object DeterminismRules {
                         ref.member in setOf("systemUTC", "systemDefaultZone")
                     ) ||
                 (ref.owner == "java.util.Date" && ref.member == "<init>") ||
-                (ref.owner == "java.util.Calendar" && ref.member == "getInstance") ||
-                // `Clock.System`, from the stdlib or from kotlinx-datetime before 0.7, which is
-                // the calendar clock common code can reach (issue #217). A `Clock` received
-                // through its interface does not match: it may be one driven by the tick, and
-                // the place a system clock is chosen is this reference.
-                ref.owner in SYSTEM_CLOCKS
+                (ref.owner == "java.util.Calendar" && ref.member == "getInstance")
         },
     )
 
```

```
KotlinClockTest > kotlinx datetime Clock System now fails the scan as DET003() FAILED
KotlinClockTest > kotlin time Clock System now planted in udea-core commonMain fails the scan as DET003() FAILED
49 tests completed, 2 failed
```

### M5-stdlib-clock-only: kotlinx-datetime's `Clock.System` dropped

```diff
@@ -318,7 +318,7 @@ public object DeterminismRules {
      */
     private val MONOTONIC_MARK_READS = listOf("elapsedNow", "hasPassedNow", "hasNotPassedNow")
 
-    private val SYSTEM_CLOCKS = setOf("kotlin.time.Clock\$System", "kotlinx.datetime.Clock\$System")
+    private val SYSTEM_CLOCKS = setOf("kotlin.time.Clock\$System")
 
     private val HASH_ORDERED_TYPES = setOf(
         "java.util.HashMap",
```

```
KotlinClockTest > kotlinx datetime Clock System now fails the scan as DET003() FAILED
49 tests completed, 1 failed
```

### M6-whole-kotlin-time-package: DET003 widened to the whole `kotlin.time` package

```diff
@@ -210,7 +210,7 @@ public object DeterminismRules {
                 // the calendar clock common code can reach (issue #217). A `Clock` received
                 // through its interface does not match: it may be one driven by the tick, and
                 // the place a system clock is chosen is this reference.
-                ref.owner in SYSTEM_CLOCKS
+                ref.owner in SYSTEM_CLOCKS || ref.owner.startsWith("kotlin.time.")
         },
     )
 
```

```
KotlinClockTest > asking a monotonic mark how much time has passed is a wall-clock read() FAILED
KotlinClockTest > TimeSource Monotonic markNow planted in udea-core commonMain fails the scan as DET001() FAILED
KotlinClockTest > an interface-typed clock and a clock outside the declared prefixes are not findings() FAILED
49 tests completed, 3 failed
```

## 7. Regenerated files

None. No replicated component changed; `net-protocol.lock` and `expected-generated-hashes.txt` untouched.

Scratch artefacts named above live in `/tmp/claude-1000/-srv-ssd1-workspace-Udea/63d0acb8-bd65-4c8c-8a79-47eb9be28599/scratchpad`.
