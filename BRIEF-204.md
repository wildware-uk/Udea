e2fbcab

# BRIEF - #204: udea-gas on Kotlin Multiplatform

Branch `issue-204-gas-kmp`, three commits on `origin/kmp` at `07eddef`: `a47f6c8`, `ad6813a`, `e2fbcab`.
This file is not committed (four tickets writing `BRIEF.md` at the root would conflict on merge);
every artefact quoted below is on disk under
`/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a8b2f894959c4489d/build/issue204-evidence/`,
and each block names the file and line range it was spliced from.

## 1. Evidence command

```
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew :udea-gas:jvmTest :udea-gas:wasmJsNodeTest :udea-gas:testAndroidHostTest :udea-gas:udeaVerifyGasTime :udea-gas:udeaGasAllocationBudget udeaVerifyDeterminism
```

On this branch at `e2fbcab`, clean tree, `evidence-on-branch.log` (`jvmTest` and
`testAndroidHostTest` restored from the build cache, `wasmJsNodeTest` executed):

```
> Task :udea-gas:udeaGasAllocationBudget UP-TO-DATE
> Task :udeaVerifyDeterminism UP-TO-DATE
> Task :udea-gas:jvmTest FROM-CACHE
> Task :udea-gas:compileTestDevelopmentExecutableKotlinWasmJs FROM-CACHE
> Task :udea-gas:testAndroidHostTest FROM-CACHE
> Task :udea-gas:wasmJsTestTestDevelopmentExecutableCompileSync
> Task :udea-gas:wasmJsNodeTest

BUILD SUCCESSFUL in 30s
```

The counts, summed from the JUnit XML that run left in `udea-gas/build/test-results/` by a Python
one-liner, `test-counts.txt`:

```
jvmTest files 26 tests 115 skipped 0 failed 0
wasmJsNodeTest files 23 tests 97 skipped 0 failed 0
testAndroidHostTest files 23 tests 97 skipped 0 failed 0
udeaGasAllocationBudget files 1 tests 3 skipped 0 failed 0
```

**Red when the feature is reverted.** On `origin/kmp` itself (`git switch --detach origin/kmp`, same
worktree), `evidence-on-origin-kmp.log`:

```
* What went wrong:
Cannot locate tasks that match ':udea-gas:jvmTest' as task 'jvmTest' not found in project ':udea-gas'. Some candidates are: 'test'.
[... elided ...]
BUILD FAILED in 6s
```

That is the shallow red. The feature-level reds are the plant and the mutation table in section 5:
a `System.nanoTime` planted in `jvmMain` fails three gates, a Java call put back into common code
fails the Wasm compile, and mutations M3 to M7 of the ported arithmetic turn tests red.

## 2. Summary

udea-gas now builds for `jvm`, `android` and `wasmJs` and runs its tests on all three.

- **Build script.** `udea.kotlin-multiplatform-no-ios`, copying `udea-core`'s #203 wiring: KSP over
  `kspCommonMainMetadata` with the generated source dir added to `commonMain`, the same two
  `dependsOn` lines, `udeaModule("Gas")` kept, `testFixtures(project(":udea-core"))` on
  `jvmTestImplementation`. `udeaGasAllocationBudget` points at the `jvm` test compilation and sets
  `useJUnitPlatform()` (without it a registered `Test` task finds no JUnit 5 test). No build-logic
  or udea-codegen change.
- **Sources.** `src/main` to `src/commonMain`. The Java calls became their stdlib equivalents:
  `Arrays.fill` to `fill`, `System.arraycopy` to `copyInto`, `Long.bitCount` to `countOneBits`,
  `Long.numberOfTrailingZeros` to `countTrailingZeroBits`, `Math.floor` to `kotlin.math.floor`,
  `toSortedSet()` to `distinct().sorted()`, `@JvmInline` imported from `kotlin.jvm`.
- **Tests.** Every test file moved to `commonTest`, and runs on all three targets, except these,
  which stay in `jvmTest` because they need the JVM: `AllocationProbe` and `AttributeAllocationTest`
  (HotSpot's thread allocation counter), `AttributeReplicationTest` (udea-core's JVM-only test
  fixtures), `GasArchitectureTest` (`java.io.File`).

Decisions, each commented on the issue:

1. **Targets** ([comment](https://github.com/wildware-uk/Udea/issues/204#issuecomment-5705932327)).
   No iOS: udea-core has none (#215). Same one-line switch comment as udea-core; `ci.yml`'s
   `ios-tests` comment and `AGENTS.md` / `docs/module-graph.md` say so.
2. **Executor class names** ([comment](https://github.com/wildware-uk/Udea/issues/204#issuecomment-5705934906)).
   `AbilityExecRegistry` used `exec::class.java.name`. I made udea-core's `KClass<*>.runtimeName`
   `public` (it was `internal`, from #203) and use it: binary name on JVM/Android, so moba's
   `idOf(MeleeAttackExec::class.java.name)` and every JVM id are unchanged; qualified name on Wasm.
   Rejected: `qualifiedName` everywhere (changes nested names on the JVM), copying the expect/actual
   into udea-gas (duplicate logic). This is the one code change outside `udea-gas`: a visibility keyword
   on three lines and a KDoc paragraph in `udea-core/.../module/TypeNames*.kt`.
3. **`Math.round`** ([comment](https://github.com/wildware-uk/Udea/issues/204#issuecomment-5705935143)).
   `effectiveCooldownTicks` rounds a percentage with `Math.round(Float)`. Replaced by
   `internal fun roundHalfUp(value: Float) = floor(value.toDouble() + 0.5).toInt()`.
   `RoundHalfUpTest` (JVM) holds it to `Math.round` on every `n + 0.5` for |n| <= 2^22 with both
   neighbours, the Float/Int edges and 10M seeded bit patterns; `RoundHalfUpEdgesTest` (common)
   pins the ties, the float just under a half, saturation and NaN as literals on every target.
4. **The time gate** (same comment). `udeaVerifyGasTime` scanned `src/main/kotlin`. Pointed only at
   `commonMain` it passes a `System.nanoTime` planted in `jvmMain` (mutation M1 below), and `jvmMain`
   is the one place that call compiles. It now scans every `src/*Main/kotlin`, reports
   `<sourceSet>/kotlin/...` paths, and also forbids `TimeSource` and `Clock.System`, the clocks
   common code can reach. `GasArchitectureTest` gets the same scope and the same two needles.

Surprises:

- **Kotlin's `roundToInt()` disagrees between JVM and Wasm**
  ([comment](https://github.com/wildware-uk/Udea/issues/204#issuecomment-5706062034)). Mutating
  `roundHalfUp` to `value.roundToInt()`, `RoundHalfUpEdgesTest` fails on `wasmJs` only for
  `0.49999997f` (message in the XML: `Expected <0>, actual <1>`), and on all three for NaN
  (`Cannot round NaN value`). The obvious port could have put a JVM server and a Wasm client one
  basis point of reduction apart on such a value. My first issue comment claimed `roundToInt`
  rounds half away from zero; that was wrong and the comment is corrected in place.
- **`udeaVerifyDeterminism` does not see `kotlin.time`.** With `TimeSource.Monotonic.markNow()`
  planted in udea-gas `commonMain`, it reported `findings: 0` (`plant-commonMain-timesource.log`,
  line 187) while `udeaVerifyGasTime` failed on it. DET001 and DET003 name `java.lang.System`,
  `java.time`, `java.util.Date` and `java.util.Calendar` owners only. Build-logic is shared, so not
  changed here; it wants its own ticket.
- **No test pinned tag id order.** Mutation M6 (dropping the sort) first survived, at `ad6813a`
  (that run's log was overwritten by the rerun, so only the prose remains); `GameplayTagTableTest`
  (common) was added in `e2fbcab` and now fails it on every target.

Found, not changed: `GameplayEffect.kt`'s existing KDoc says `roundToInt` is half-away-from-zero;
Kotlin documents it as rounding ties towards positive infinity. It is a comment on
`ticksFromSeconds`, which does not call it.

## 3. `sh gradlew build`

Baseline, `origin/kmp` at `07eddef` before any change, `baseline-build.log` (0 lines contain `FAILED`):

```
BUILD SUCCESSFUL in 2m
364 actionable tasks: 243 executed, 121 from cache
Configuration cache entry stored.
```

Branch at `a47f6c8`, `branch-build-1.log`:

```
BUILD SUCCESSFUL in 1m 26s
406 actionable tasks: 76 executed, 1 from cache, 329 up-to-date
Configuration cache entry stored.
```

Branch at `e2fbcab`, `branch-build-2.log` (0 lines contain `FAILED`):

```
BUILD SUCCESSFUL in 24s
397 actionable tasks: 30 executed, 11 from cache, 356 up-to-date
Configuration cache entry reused.
```

**Tasks this ticket turned green:** `:udea-gas:jvmTest`, `:udea-gas:wasmJsNodeTest`,
`:udea-gas:testAndroidHostTest`, and the rest of the multiplatform task set (`compileKotlinWasmJs`,
`compileAndroidMain`, `allTests` ...), none of which existed on the baseline.

**Baseline failures, unchanged:** none; the baseline had no failing task.

**Baseline-green tasks that no longer exist** (renamed by the plugin, not red): `:udea-gas:test`,
`compileKotlin`, `compileTestKotlin`, `kspKotlin`, `kspTestKotlin`, `jar`, `classes`,
`testClasses`, `compileJava`, `compileTestJava`, `processResources`, `processTestResources`. Their
successors are `jvmTest`, `compileKotlinJvm`, `compileTestKotlinJvm`, `kspCommonMainKotlinMetadata`,
`jvmJar` and so on. Every other baseline task appears in `branch-build-2.log` (`comm` of the two
task lists, `base-tasks.txt` / `branch-tasks-2.txt`; the build-logic rows are absent from the
branch list only because that run reused the configuration cache).

`sh gradlew udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd` (this ticket
edits `AGENTS.md` and `docs/module-graph.md`), `gates.log`:

```
BUILD SUCCESSFUL in 7s
```

**GL:** not run under xvfb. Nothing in this change touches `udea-render`, `udea-agent-host` or a
context. `:moba:runUdpProof` not run (red before this ticket, no networking change here).

## 4. Images

None. Nothing in this ticket is drawn. The supporting evidence that a person would otherwise look
at is the replay equality below.

**A recorded match replays cell-for-cell identically before and after the port.** `moba-3600.udearep`
(a minute of real moba: abilities, lane, towers) replayed on `origin/kmp` and on this branch, then
compared by the replay-equality tool. `equals-branch-vs-kmp.log`:

```
replay-equality over 2 leg(s) of 'moba-3600.udearep', 3600 tick(s) from t1
  branch-e2fbcab  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 21.0.11]
  kmp-07eddef  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 21.0.11]

replay equality holds: 3600 tick(s) of 'moba-3600.udearep' are cell-for-cell identical
  fixture moba-3600.udearep
  A = 'branch-e2fbcab'  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 21.0.11]
  B = 'kmp-07eddef'  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 21.0.11]
```

And it can see udea-gas: with mutation M5 applied, the same comparison, `equals-m5-vs-kmp.log`:

```
replay-equality over 2 leg(s) of 'moba-3600.udearep', 3600 tick(s) from t1
  branch-m5-copyinto-swap  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 21.0.11]
  kmp-07eddef  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 21.0.11]

replay equality FAILED at t24 (23 tick(s) matched first)
  fixture moba-3600.udearep
  A = 'branch-m5-copyinto-swap'  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 21.0.11]
  B = 'kmp-07eddef'  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 21.0.11]
  world hash: 1632355463865648808 against -2226648479132909150
```
[... second differing cell and bisect guide elided ...]
```
BUILD FAILED in 22s
```

Commands (digests in `digests/` and `digests-m5/`):

```
sh gradlew :moba:udeaReplayDigest -Pudea.replay.label=branch-e2fbcab -Pudea.replay.out=<dir>/branch-e2fbcab.udeaeq
git switch --detach origin/kmp
sh gradlew :moba:udeaReplayDigest -Pudea.replay.label=kmp-07eddef -Pudea.replay.out=<dir>/kmp-07eddef.udeaeq
git switch issue-204-gas-kmp
sh gradlew :udea-replay:udeaReplayEquals -Pudea.replay.streams=<dir>
```

## 5. Acceptance criteria

**AC1 - builds for all four targets; tests pass on `jvmTest` and `wasmJsNodeTest`.** Read as the
three targets udea-core has (decision 1). Section 1: 115 JVM, 97 Wasm, 97 Android host tests, 0
failed, and `branch-build-2.log` green. iOS is not built or tested on this box and is not claimed.
The Wasm compile really enforces common code; one Java call put back (`red-wasm-java-call.diff`):

```diff
diff --git a/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AttributesReplicator.kt b/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AttributesReplicator.kt
index b07e433..a5c1a67 100644
--- a/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AttributesReplicator.kt
+++ b/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AttributesReplicator.kt
@@ -176,7 +176,7 @@ public class AttributesReplicator(
         while (word < maskWords) {
             val bits = src.readLong()
             words[word] = bits
-            changed += bits.countOneBits()
+            changed += java.lang.Long.bitCount(bits)
             word++
         }
         if (changed == 0) return 0
```

`red-wasm-java-call.log`:

```
> Task :udea-gas:compileKotlinWasmJs FAILED
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a8b2f894959c4489d/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AttributesReplicator.kt:179:24 Unresolved reference 'java'.
```

**AC2 - the gas determinism/seconds verifier still runs and still fails on a planted `System.nanoTime`.**
Plant P0 at `a47f6c8`, `p0.diff`:

```diff
diff --git a/udea-gas/src/jvmMain/kotlin/dev/wildware/udea/gas/PlantedClock.kt b/udea-gas/src/jvmMain/kotlin/dev/wildware/udea/gas/PlantedClock.kt
new file mode 100644
index 0000000..2c89a5c
--- /dev/null
+++ b/udea-gas/src/jvmMain/kotlin/dev/wildware/udea/gas/PlantedClock.kt
@@ -0,0 +1,4 @@
+package dev.wildware.udea.gas
+
+// PLANTED for issue #204: a wall-clock read in a platform source set. Must not be committed.
+internal fun plantedWallClock(): Long = System.nanoTime()
```

`p0.log`, the seconds gate, the determinism scan and the architecture test all red:

```
> Task :udea-gas:udeaVerifyGasTime FAILED
[... elided ...]
> Task :udea-gas:jvmTest FAILED

GasArchitectureTest[jvm] > no simulation source references a wall clock or a seconds-denominated duration()[jvm] FAILED
    org.opentest4j.AssertionFailedError at GasArchitectureTest.kt:37

7 tests completed, 1 failed
[... elided ...]
-----------
* What went wrong:
Execution failed for task ':udea-gas:udeaVerifyGasTime'.
> udea-gas simulation code is not tick-denominated:
    jvmMain/kotlin/dev/wildware/udea/gas/PlantedClock.kt:4 references 'System.nanoTime' — a wall clock; time comes from SimClock, denominated in Tick
[... elided ...]
    scanned :udea-net: 181 class files
    scanned :moba: 370 class files
    allowlist entries used: 0
    findings: 1
  udea-gas/src/jvmMain/kotlin/dev/wildware/udea/gas/PlantedClock.kt:4:1: error: [DET001] dev.wildware.udea.gas.PlantedClockKt.plantedWallClock is declared simulation (:udea-gas) and reads the wall clock: it references java.lang.System.nanoTime.
```

And the clock common code can reach, planted in `commonMain` beside a control file whose comment
only mentions `TimeSource.Monotonic` and `System.nanoTime` (a pre-commit tree; the plant files were
`PlantedCommonClock.kt`, `import kotlin.time.TimeSource` and
`internal fun plantedCommonClock(): Any = TimeSource.Monotonic.markNow()`, and `PlantedControl.kt`).
`plant-commonMain-timesource.log`: the gate names the plant's two lines and not the control file.

```
* What went wrong:
Execution failed for task ':udea-gas:udeaVerifyGasTime'.
> udea-gas simulation code is not tick-denominated:
    commonMain/kotlin/dev/wildware/udea/gas/PlantedCommonClock.kt:3 references 'TimeSource' — a wall clock (kotlin.time); time comes from SimClock, denominated in Tick
    commonMain/kotlin/dev/wildware/udea/gas/PlantedCommonClock.kt:6 references 'TimeSource' — a wall clock (kotlin.time); time comes from SimClock, denominated in Tick
```

### Mutation table

Each row: the literal `git diff` from the run, the base commit, and the failing tests from its log.
Reverted after each.

**M1** (at `a47f6c8`, plant kept) gate back to `commonMain` only. `m1.diff`:

```diff
diff --git a/udea-gas/build.gradle.kts b/udea-gas/build.gradle.kts
index 760c692..e9ac97f 100644
--- a/udea-gas/build.gradle.kts
+++ b/udea-gas/build.gradle.kts
@@ -89,7 +89,7 @@ val udeaVerifyGasTime = tasks.register("udeaVerifyGasTime") {
     // `System.nanoTime`, so a platform source set such as `jvmMain` is exactly where one would be
     // written. Test source sets are not simulation and end in `Test`, so the glob leaves them out.
     val sourceRoot = layout.projectDirectory.dir("src")
-    val sources = fileTree(sourceRoot) { include("*Main/kotlin/**/*.kt") }
+    val sources = fileTree(sourceRoot) { include("commonMain/kotlin/**/*.kt") }
     inputs.files(sources).withPropertyName("simulationSources").withPathSensitivity(PathSensitivity.RELATIVE)
     inputs.property("forbidden", forbidden.keys.sorted().joinToString(","))
 
diff --git a/udea-gas/src/jvmMain/kotlin/dev/wildware/udea/gas/PlantedClock.kt b/udea-gas/src/jvmMain/kotlin/dev/wildware/udea/gas/PlantedClock.kt
new file mode 100644
index 0000000..2c89a5c
--- /dev/null
+++ b/udea-gas/src/jvmMain/kotlin/dev/wildware/udea/gas/PlantedClock.kt
@@ -0,0 +1,4 @@
+package dev.wildware.udea.gas
+
+// PLANTED for issue #204: a wall-clock read in a platform source set. Must not be committed.
+internal fun plantedWallClock(): Long = System.nanoTime()
```

`m1.log`: the gate is green with a `System.nanoTime` in the module. This is the shape a straight
port produces, and the reason the gate scans every `*Main`.

```
> Task :udea-gas:udeaVerifyGasTime
[... elided ...]
BUILD SUCCESSFUL in 10s
```

**M2** (at `a47f6c8`, plant kept) architecture test back to `commonMain` only. `m2.diff`:

```diff
diff --git a/udea-gas/src/jvmMain/kotlin/dev/wildware/udea/gas/PlantedClock.kt b/udea-gas/src/jvmMain/kotlin/dev/wildware/udea/gas/PlantedClock.kt
new file mode 100644
index 0000000..2c89a5c
--- /dev/null
+++ b/udea-gas/src/jvmMain/kotlin/dev/wildware/udea/gas/PlantedClock.kt
@@ -0,0 +1,4 @@
+package dev.wildware.udea.gas
+
+// PLANTED for issue #204: a wall-clock read in a platform source set. Must not be committed.
+internal fun plantedWallClock(): Long = System.nanoTime()
diff --git a/udea-gas/src/jvmTest/kotlin/dev/wildware/udea/gas/GasArchitectureTest.kt b/udea-gas/src/jvmTest/kotlin/dev/wildware/udea/gas/GasArchitectureTest.kt
index 42d73db..a1d0038 100644
--- a/udea-gas/src/jvmTest/kotlin/dev/wildware/udea/gas/GasArchitectureTest.kt
+++ b/udea-gas/src/jvmTest/kotlin/dev/wildware/udea/gas/GasArchitectureTest.kt
@@ -113,7 +113,7 @@ internal object GasSources {
      */
     val mainSources: List<File> = moduleDir.resolve("src")
         .listFiles().orEmpty()
-        .filter { it.isDirectory && it.name.endsWith("Main") }
+        .filter { it.isDirectory && it.name == "commonMain" }
         .flatMap { sourceSet -> sourceSet.resolve("kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" } }
         .sortedBy { it.path }
 
```

`m2.log`: green with the plant present. (The JUnit XML of that run read `tests="7"`,
`failures="0"`; it was overwritten by later runs and is not kept.)

```
> Task :udea-gas:jvmTest
[... elided ...]
BUILD SUCCESSFUL in 5s
```

**M3** (at `e2fbcab`) `roundHalfUp` to `roundToInt()`. `m3-alltargets.diff`:

```diff
diff --git a/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AbilityActivation.kt b/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AbilityActivation.kt
index 07c8b4c..af5b24e 100644
--- a/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AbilityActivation.kt
+++ b/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AbilityActivation.kt
@@ -4,6 +4,7 @@ import dev.wildware.udea.core.Tick
 import dev.wildware.udea.core.identity.NetId
 import kotlin.jvm.JvmInline
 import kotlin.math.floor
+import kotlin.math.roundToInt
 
 /**
  * Whether this simulation may activate abilities on a given entity.
@@ -438,4 +439,4 @@ public class AbilityActivation(
  * `RoundHalfUpTest` holds it to `Math.round` on the JVM, and `RoundHalfUpEdgesTest` pins its ties and
  * edges on every target.
  */
-internal fun roundHalfUp(value: Float): Int = floor(value.toDouble() + 0.5).toInt()
+internal fun roundHalfUp(value: Float): Int = value.roundToInt()
```

`m3-alltargets.log`:

```
> Task :udea-gas:testAndroidHostTest FAILED

RoundHalfUpEdgesTest > out-of-range values saturate and NaN is zero FAILED
    java.lang.IllegalArgumentException at RoundHalfUpEdgesTest.kt:36

97 tests completed, 1 failed

> Task :udea-gas:jvmTest FAILED

RoundHalfUpEdgesTest[jvm] > out-of-range values saturate and NaN is zero()[jvm] FAILED
    java.lang.IllegalArgumentException at RoundHalfUpEdgesTest.kt:36

RoundHalfUpTest[jvm] > agrees with Math round at the edges of the Float and Int ranges()[jvm] FAILED
    java.lang.IllegalArgumentException at RoundHalfUpTest.kt:49

RoundHalfUpTest[jvm] > agrees with Math round on ten million seeded bit patterns()[jvm] FAILED
    java.lang.IllegalArgumentException at RoundHalfUpTest.kt:49

115 tests completed, 3 failed

> Task :udea-gas:compileTestDevelopmentExecutableKotlinWasmJs
> Task :udea-gas:wasmJsTestTestDevelopmentExecutableCompileSync

dev.wildware.udea.gas.RoundHalfUpEdgesTest.the float just below a half rounds down[wasmJs, node] FAILED
    kotlin.AssertionError at file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a8b2f894959c4489d/build/wasm/packages/udea-udea-gas-test/kotlin/udea-udea-gas-test.import-object.mjs:141

dev.wildware.udea.gas.RoundHalfUpEdgesTest.out-of-range values saturate and NaN is zero[wasmJs, node] FAILED
    kotlin.IllegalArgumentException at file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a8b2f894959c4489d/build/wasm/packages/udea-udea-gas-test/kotlin/udea-udea-gas-test.import-object.mjs:141

> Task :udea-gas:wasmJsNodeTest FAILED

97 tests completed, 2 failed
```

**M3b** (at `a47f6c8`) `roundHalfUp` to half-even `kotlin.math.round`. `m3b.diff`:

```diff
diff --git a/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AbilityActivation.kt b/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AbilityActivation.kt
index 2db7b32..4f74b13 100644
--- a/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AbilityActivation.kt
+++ b/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AbilityActivation.kt
@@ -437,4 +437,4 @@ public class AbilityActivation(
  * either way, and `Double.toInt()` saturates and maps `NaN` to `0` the same way.
  * `RoundHalfUpTest` holds it to `Math.round` on the JVM.
  */
-internal fun roundHalfUp(value: Float): Int = floor(value.toDouble() + 0.5).toInt()
+internal fun roundHalfUp(value: Float): Int = kotlin.math.round(value).toInt()
```

`m3b.log`:

```
> Task :udea-gas:jvmTest FAILED

RoundHalfUpTest[jvm] > agrees with Math round at the edges of the Float and Int ranges()[jvm] FAILED
    org.opentest4j.AssertionFailedError at RoundHalfUpTest.kt:51

RoundHalfUpTest[jvm] > agrees with Math round on ten million seeded bit patterns()[jvm] FAILED
    org.opentest4j.AssertionFailedError at RoundHalfUpTest.kt:51

RoundHalfUpTest[jvm] > agrees with Math round on every half and its neighbours()[jvm] FAILED
    org.opentest4j.AssertionFailedError at RoundHalfUpTest.kt:51

109 tests completed, 3 failed
```

**M3c** (at `ad6813a`) the same mutation, on Wasm and Android. `m3c.diff`:

```diff
diff --git a/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AbilityActivation.kt b/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AbilityActivation.kt
index 07c8b4c..31f4aec 100644
--- a/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AbilityActivation.kt
+++ b/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AbilityActivation.kt
@@ -438,4 +438,4 @@ public class AbilityActivation(
  * `RoundHalfUpTest` holds it to `Math.round` on the JVM, and `RoundHalfUpEdgesTest` pins its ties and
  * edges on every target.
  */
-internal fun roundHalfUp(value: Float): Int = floor(value.toDouble() + 0.5).toInt()
+internal fun roundHalfUp(value: Float): Int = kotlin.math.round(value).toInt()
```

`m3c.log`:

```
> Task :udea-gas:testAndroidHostTest FAILED

RoundHalfUpEdgesTest > ties round towards positive infinity in both signs FAILED
    java.lang.AssertionError at RoundHalfUpEdgesTest.kt:18

94 tests completed, 1 failed

> Task :udea-gas:compileTestDevelopmentExecutableKotlinWasmJs
> Task :udea-gas:wasmJsTestTestDevelopmentExecutableCompileSync

dev.wildware.udea.gas.RoundHalfUpEdgesTest.ties round towards positive infinity in both signs[wasmJs, node] FAILED
    kotlin.AssertionError at file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a8b2f894959c4489d/build/wasm/packages/udea-udea-gas-test/kotlin/udea-udea-gas-test.import-object.mjs:141

> Task :udea-gas:wasmJsNodeTest FAILED

94 tests completed, 1 failed
```

**M4** (at `ad6813a`) `runtimeName` to simple names on both sides of the expect. `m4.diff`:

```diff
diff --git a/udea-core/src/jvmAndAndroidMain/kotlin/dev/wildware/udea/core/module/TypeNames.jvmAndAndroid.kt b/udea-core/src/jvmAndAndroidMain/kotlin/dev/wildware/udea/core/module/TypeNames.jvmAndAndroid.kt
index eaff354..d4d6a0a 100644
--- a/udea-core/src/jvmAndAndroidMain/kotlin/dev/wildware/udea/core/module/TypeNames.jvmAndAndroid.kt
+++ b/udea-core/src/jvmAndAndroidMain/kotlin/dev/wildware/udea/core/module/TypeNames.jvmAndAndroid.kt
@@ -2,4 +2,4 @@ package dev.wildware.udea.core.module
 
 import kotlin.reflect.KClass
 
-public actual val KClass<*>.runtimeName: String get() = java.name
+public actual val KClass<*>.runtimeName: String get() = java.simpleName
diff --git a/udea-core/src/nonJvmMain/kotlin/dev/wildware/udea/core/module/TypeNames.nonJvm.kt b/udea-core/src/nonJvmMain/kotlin/dev/wildware/udea/core/module/TypeNames.nonJvm.kt
index b700d94..98d4ffe 100644
--- a/udea-core/src/nonJvmMain/kotlin/dev/wildware/udea/core/module/TypeNames.nonJvm.kt
+++ b/udea-core/src/nonJvmMain/kotlin/dev/wildware/udea/core/module/TypeNames.nonJvm.kt
@@ -3,4 +3,4 @@ package dev.wildware.udea.core.module
 import kotlin.reflect.KClass
 
 /** A local or anonymous class has no qualified name, and its `toString()` is the best left. */
-public actual val KClass<*>.runtimeName: String get() = qualifiedName ?: toString()
+public actual val KClass<*>.runtimeName: String get() = simpleName ?: toString()
```

`m4.log`:

```
> Task :udea-gas:testAndroidHostTest FAILED

AbilityExecStatelessTest > exec ids are the same on every target, nested classes included FAILED
    java.lang.AssertionError at AbilityActivationTest.kt:317

94 tests completed, 1 failed

dev.wildware.udea.gas.AbilityExecStatelessTest.exec ids are the same on every target, nested classes included[wasmJs, node] FAILED
    kotlin.AssertionError at file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a8b2f894959c4489d/build/wasm/packages/udea-udea-gas-test/kotlin/udea-udea-gas-test.import-object.mjs:141

> Task :udea-gas:wasmJsNodeTest FAILED

94 tests completed, 1 failed

> Task :udea-gas:jvmTest FAILED

AbilityExecStatelessTest[jvm] > exec ids are the same on every target, nested classes included()[jvm] FAILED
    org.opentest4j.AssertionFailedError at AbilityActivationTest.kt:317

112 tests completed, 1 failed
```

**M5** (at `ad6813a`) the classic `arraycopy` to `copyInto` argument swap on one column. `m5.diff`:

```diff
diff --git a/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/GameplayEffects.kt b/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/GameplayEffects.kt
index 25be167..23923a4 100644
--- a/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/GameplayEffects.kt
+++ b/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/GameplayEffects.kt
@@ -229,7 +229,7 @@ public class GameplayEffects(
         if (moved > 0) {
             // Shifts slots index+1 until count down by one. `copyInto` is documented to handle a
             // source and destination range that overlap, as `System.arraycopy` did.
-            handles.copyInto(handles, index, index + 1, count)
+            handles.copyInto(handles, index + 1, index, count - 1)
             defIndices.copyInto(defIndices, index, index + 1, count)
             appliedTicks.copyInto(appliedTicks, index, index + 1, count)
             durations.copyInto(durations, index, index + 1, count)
```

`m5.log` (first target shown; the same five fail on `jvm` and `wasmJs`, lines 178-214):

```
> Task :udea-gas:testAndroidHostTest FAILED

CooldownGroupTest > the plain grant does not adopt, which is what the group-aware one is for FAILED
    java.lang.AssertionError at CooldownGroupTest.kt:156

CooldownGroupTest > granting into a cooling group adopts the cooldown that is already running FAILED
    java.lang.AssertionError at CooldownGroupTest.kt:128

CooldownTickTest > a nine hundred tick cooldown counts down monotonically and ends on exactly zero FAILED
    java.lang.AssertionError at AbilityActivationTest.kt:41

CooldownTickTest > a rewind restores the remaining cooldown and the handle still resolves FAILED
    java.lang.AssertionError at AbilityActivationTest.kt:79

CooldownTickTest > an on-cooldown refusal carries the remaining ticks FAILED
    java.lang.AssertionError at AbilityActivationTest.kt:67

94 tests completed, 5 failed
```

**M6** (at `e2fbcab`) tag names no longer sorted. `m6.diff`:

```diff
diff --git a/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/GameplayTag.kt b/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/GameplayTag.kt
index bacb3e6..980ad42 100644
--- a/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/GameplayTag.kt
+++ b/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/GameplayTag.kt
@@ -84,7 +84,7 @@ public class GameplayTagTable private constructor(
          * with one name means one of them is unreachable — see [AttributeTable].
          */
         public fun of(names: Collection<String>): GameplayTagTable {
-            val sorted = names.distinct().sorted().toTypedArray()
+            val sorted = names.distinct().toTypedArray()
             require(sorted.none { it.isEmpty() }) { "a gameplay tag name must not be empty" }
             val byName = HashMap<String, Int>(sorted.size * 2)
             sorted.forEachIndexed { index, name -> byName[name] = index }
```

`m6.log`:

```
> Task :udea-gas:testAndroidHostTest FAILED

GameplayTagTableTest > names compare by character code, so upper case sorts before lower case FAILED
    java.lang.AssertionError at GameplayTagTableTest.kt:37

GameplayTagTableTest > a name declared twice is one tag FAILED
    java.lang.AssertionError at GameplayTagTableTest.kt:30

GameplayTagTableTest > ids follow ascending name order, not declaration order FAILED
    java.lang.AssertionError at GameplayTagTableTest.kt:21

97 tests completed, 3 failed
```

**M7** (at `e2fbcab`) rounding in `Float` rather than `Double` (the pre-Java-7 `Math.round` bug). `m7.diff`:

```diff
diff --git a/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AbilityActivation.kt b/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AbilityActivation.kt
index 07c8b4c..474060f 100644
--- a/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AbilityActivation.kt
+++ b/udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AbilityActivation.kt
@@ -438,4 +438,4 @@ public class AbilityActivation(
  * `RoundHalfUpTest` holds it to `Math.round` on the JVM, and `RoundHalfUpEdgesTest` pins its ties and
  * edges on every target.
  */
-internal fun roundHalfUp(value: Float): Int = floor(value.toDouble() + 0.5).toInt()
+internal fun roundHalfUp(value: Float): Int = floor(value + 0.5f).toInt()
```

`m7.log`:

```
> Task :udea-gas:testAndroidHostTest FAILED

RoundHalfUpEdgesTest > the float just below a half rounds down FAILED
    java.lang.AssertionError at RoundHalfUpEdgesTest.kt:26

97 tests completed, 1 failed

dev.wildware.udea.gas.RoundHalfUpEdgesTest.the float just below a half rounds down[wasmJs, node] FAILED
    kotlin.AssertionError at file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a8b2f894959c4489d/build/wasm/packages/udea-udea-gas-test/kotlin/udea-udea-gas-test.import-object.mjs:141

> Task :udea-gas:wasmJsNodeTest FAILED

97 tests completed, 1 failed

> Task :udea-gas:jvmTest FAILED

RoundHalfUpEdgesTest[jvm] > the float just below a half rounds down()[jvm] FAILED
    org.opentest4j.AssertionFailedError at RoundHalfUpEdgesTest.kt:26

RoundHalfUpTest[jvm] > agrees with Math round at the edges of the Float and Int ranges()[jvm] FAILED
    org.opentest4j.AssertionFailedError at RoundHalfUpTest.kt:51

RoundHalfUpTest[jvm] > agrees with Math round on ten million seeded bit patterns()[jvm] FAILED
    org.opentest4j.AssertionFailedError at RoundHalfUpTest.kt:51

RoundHalfUpTest[jvm] > agrees with Math round on every half and its neighbours()[jvm] FAILED
    org.opentest4j.AssertionFailedError at RoundHalfUpTest.kt:51

115 tests completed, 4 failed
```

What is not exercised: iOS (cannot build here); a Wasm or Android client actually predicting against
a JVM server (no such harness exists yet; `RoundHalfUpEdgesTest` and the exec-id ordering test are
the cross-target pins); the `ios-tests` CI job (udea-gas is deliberately not in it).

## 6. Regenerated files

None. No replicated component was added, removed or renamed; `net-protocol.lock` and
`expected-generated-hashes.txt` are untouched, and `udeaCheckProtocolLock` ran green inside
`build`.

