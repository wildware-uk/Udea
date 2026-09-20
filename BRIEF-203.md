b22fc59

# BRIEF-203: udea-core on Kotlin Multiplatform

Branch `issue-203-udea-core-kmp`, off `origin/kmp` at 739deda (re-checked with `git ls-remote origin kmp` just before writing this: still `739deda58ffd1504b33e839ceffa5e628b9d1fde`).
The SHA above is the last commit of the change. This brief goes in the commit after it, the way BRIEF-202 did.

Commits, oldest first:
- `d021709`: pins the JVM world hash. Recorded on the untouched tree before anything moved.
- `fce38f0`: `udeaVerifyDeterminism` learns the multiplatform layout, and adds the no-iOS convention.
- `5532aaf`: the port itself.
- `b22fc59`: puts the udea-core budget tasks back on JUnit 5, plus an import order and a KDoc.

Every log quoted below is in `/tmp/claude-1000/-srv-ssd1-workspace-Udea/4e799f26-f118-4598-a578-666a1f04b13f/scratchpad/` (`logs/`, `mut/`), and each block is spliced from the file it names.

## 1. Evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem ANDROID_HOME=$HOME/Android/Sdk sh gradlew :udea-core:jvmTest :udea-core:wasmJsNodeTest :udea-core:testAndroidHostTest :udea-agent:test --tests dev.wildware.udea.agent.harness.SimHarnessWorldHashPinTest
```

It runs udea-core's tests on three targets, plus the world-hash pin test that was recorded before the port.

**Red with the port reverted.** I checked out `d021709`, which is the pin commit on the unported tree, and ran the command there (`logs/evidence-red-d021709.log`):

```
* What went wrong:
Cannot locate tasks that match ':udea-core:jvmTest' as task 'jvmTest' not found in project ':udea-core'. Some candidates are: 'test'.
```
```
BUILD FAILED in 9s
```

That red only proves the targets exist. So the parts of the command that check behaviour were also shown red, by mutating the ported code (section 8):
- The pin test goes red when entity-id assignment shifts by one (m4).
- `wasmJsNodeTest` goes red when the new portable system-order heap is broken (m1).

**Green on the branch** (`logs/build-final.log`, full `build`): udea-core `jvmTest` 452 tests in 60 classes, `wasmJsNodeTest` 144 in 18, `testAndroidHostTest` 144 in 18. All three have 0 failures and 0 skipped; the counts are read from the result XML, copied to `logs/udea-core-test-results-final/`. `SimHarnessWorldHashPinTest` is 1 test with 0 failures (XML timestamp 2026-09-16T22:50:27Z, same build).

## 2. Summary

udea-core is now multiplatform on **jvm, android and wasmJs**.

**Source layout.**
- `src/main` moved to `src/commonMain`, and nothing in it imports `java.*`.
- Platform code sits behind small `internal expect` declarations:
  - `loop/Threads.kt`: a thread token, yield, and park.
  - `module/TypeNames.kt`: `KClass.runtimeName`.
- The actuals are:
  - `jvmAndAndroidMain`: `Thread`, `LockSupport`, `java.name`.
  - `wasmJsMain`: one thread.
  - `nativeMain`: kept for when iOS returns.
  - `nonJvmMain`: `qualifiedName`.

**Library replacements in common code.**
- `PriorityQueue` became a private index min-heap. `SystemOrderSortTest` is new, in commonTest: 300 random graphs checked against a reference sort.
- `System.arraycopy` became `copyInto`.
- `Integer.bitCount` / `numberOfTrailingZeros` became `countOneBits` / `countTrailingZeroBits`.
- `synchronized` became atomicfu's `SynchronizedObject`. Atomicfu is new in the catalog at `0.33.0`, as an `implementation` dependency.
- `Class.cast` became `isInstance` plus `ClassCastException`.

**Tests.**
- The portable test sources moved to `commonTest`: `git diff -M --name-status origin/kmp HEAD` shows 23 renames into it, plus the new `SystemOrderSortTest`. They run as 18 test classes. Everything else stayed in `jvmTest`.
- Test fixtures moved to `src/jvmTestFixtures` (still `dev.wildware.udea.jvm-test-fixtures`), so every consumer's `testFixtures(project(":udea-core"))` is unchanged.
- commonTest names containing `,` or `()` were renamed, because Kotlin/Native rejects those characters in backticked names. Every rename, old and new, is listed in `logs/renamed-tests.txt`.

**Build.**
- KSP runs once, over `kspCommonMainMetadata`, into common code. The generated registry is byte-identical to the baseline JVM output (`logs/ksp-baseline` vs `logs/ksp-port`).
- `udeaModule("Core")` is kept. `udeaModule` reads `jvmRuntimeClasspath` for a multiplatform module.

**Determinism gate (the carry-over from #201).**
- `DeterminismLayout` is new. It picks the layout from the module's sources:
  - a multiplatform module scans `build/classes/kotlin/{jvm,android}/main` and every `src/*Main/kotlin`;
  - otherwise the JVM layout.
- This means a stale `build/classes/kotlin/main` is never read. After the port, that stale directory still held 277 classes and would have scanned clean (m5).

**Decisions.** Each one is commented on #203 (https://github.com/wildware-uk/Udea/issues/203#issuecomment-5705686407).
- **iOS off, lead-approved.** Fleks has no iOS artifact. The evidence is in section 5a. Follow-up is #215: "Fleks has no iOS artifact: blocks iOS for udea-core and dependents", Part of #199, naming both fixes (upstream PR, vendoring).
  - The switch is one named line in `udea-core/build.gradle.kts`, under `// THE iOS SWITCH (issue #215)`: `id("dev.wildware.udea.kotlin-multiplatform-no-ios")`.
  - Re-enabling means changing it to `id("dev.wildware.udea.kotlin-multiplatform")`, then adding udea-core to `ios-tests`.
  - **udea-core is deliberately not added to the `ios-tests` CI job.** The comment in `ci.yml` says so and names #215.
  - The #203 criterion "all four targets" was ruled met-as-far-as-possible by the lead.
- **New convention `dev.wildware.udea.kotlin-multiplatform-no-ios`.** It is the multiplatform set minus iOS. `dev.wildware.udea.kotlin-multiplatform-render` now applies it rather than repeating the configuration. `KotlinMultiplatformConventionTest` pins the target set; it was red before the plugin existed (`logs/noios-red.log`).
- **Test fixtures stay JVM-only.** udea-codegen consumes them as a JVM variant. So tests that use them stay in `jvmTest`, including these, which I tried in commonTest and moved back when they would not compile there: PhysicsRestoreOrder, SceneTeardown, DivergenceReport, LoopDrivenCapture, TornWorldRestore, WorldFieldStoreDiff.
- **`runtimeName` is `java.name` on JVM/Android.** The manifest golden and error text keep `$` for nested classes on the authoritative JVM.
- **`Thread.yield()` rather than `onSpinWait()`.** `onSpinWait` needs Android API 33.
- **Wasm actuals `yieldToTickingThread`/`parkWhilePaused` throw a stated error.**
  - Yield is reached only when another thread holds a tick, which cannot happen on one thread.
  - Park is reached only from `GameHost.run()` while paused, which on one thread could never be resumed. A no-op there would be a silent infinite loop.
  - Neither is a stub: each names the misuse.
- **Native actual checked, then the probe removed.** I added a temporary `linuxX64` target to udea-core and udea-annotations: 144/144 common tests passed (`logs/native-probe-linuxX64.log`, `logs/linuxX64Test-results/`), then reverted it. The iOS target itself was not built or tested: this box cannot build iOS.

**A defect found and fixed on the way (b22fc59).**
- After the move, the four udea-core budget tasks (`udeaSnapshotBudget`, `udeaBenchTickLoop`, `udeaBenchCharacterMover`, `udeaPhysicsRebuildBudget`) failed with "No tests found for given includes". They were not on JUnit Platform, and `build` stayed green because none of them is on `check`.
- They now share `Test.runsJvmTestClasses()`.
- `TickLoopBudgetTest`'s kotlin-reflect check accepts the quoted `"jvmTestImplementation"` bucket.
- All four pass, run on their own at b22fc59 (`logs/budgets-b22fc59.log`):

```
    PhysicsRebuildBudgetTest: 500 bodies rebuilt in 593us (median of 21, best 477us, worst 744us)
```
```
    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) best 2.175ms, median 2.862ms, worst 3.583ms, budget 4.0ms
```
```
    udeaBenchTickLoop: 600 ticks at 200 entities, median 6.04509ms, p95 6.533137ms, budget 50.0ms
```
```
    udeaSnapshotBudget: capture of 1000 entities median 85881ns, p95 92212ns, budget 1000000ns
```
```
BUILD SUCCESSFUL in 14s
```

**Other files touched to follow the move.**
- `ci.yml`: `:udea-core:jvmTestClasses`; the planted-violation path is now `src/commonMain`.
- `docs/module-graph.md` and `AGENTS.md`: the udea-core convention, and #215.
- Source-path tests: `ModuleFiles`, `CoreModuleManifestGoldenTest`, `NoReflectiveRegistrationTest`, `ReplicatorApiShapeTest`, `WallClockBudgetCensusTest`, and udea-render's `RepoLayout`.

## 3. Build output

**Full build at b22fc59**, with nothing else building on the box (`pgrep` showed only idle daemons, load 2.2). The command was `sh gradlew build --continue` (`logs/build-final.log`):

```
BUILD SUCCESSFUL in 1m 34s
364 actionable tasks: 222 executed, 2 from cache, 140 up-to-date
```

The determinism gate, from the same log:

```
> Task :udeaVerifyDeterminism
udeaVerifyDeterminism
  scanned :udea-core: 560 class files
  scanned :udea-gas: 135 class files
  scanned :udea-net: 181 class files
  scanned :moba: 370 class files
  allowlist entries used: 0
  findings: 0
```

The 560 classes are 280 JVM plus 280 Android. The baseline scanned the old 277.

**Tasks.**
- Turned green (new tasks): `:udea-core:jvmTest`, `:udea-core:wasmJsNodeTest`, `:udea-core:testAndroidHostTest`, and the udea-core compile tasks for jvm/android/wasmJs.
- Baseline failures: **none**. `logs/baseline-build.log` at 739deda ends `BUILD SUCCESSFUL in 1m 18s`, and nothing on the branch is red.

**GL, run for real** (the change touches udea-render's `RepoLayout` and every host on udea-core). The command was:

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew udeaGlTest --rerun udeaAgentGlTest --rerun -Pudea.render.requireGl=true
```

From `logs/gl-final.log`:

```
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest
```
```
BUILD SUCCESSFUL in 10s
61 actionable tasks: 5 executed, 56 up-to-date
```

Read from the XML, copied to `logs/gl-results/`:
- `udeaGlTest`: 21 tests, 0 failures, **0 skipped**.
- `udeaAgentGlTest`: 8 tests, 0 failures, **0 skipped**.

`--rerun` is there for a reason. Without it, the ordinary `build` restored a cached no-GL `udeaGlTest` result (20 skipped) over an earlier real run. That is existing build-cache behaviour, not this change, but it means result XML on disk says nothing about GL unless the run that wrote it is known.

**Gates outside `check`.** The command was `sh gradlew udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd` (`logs/gates.log`):

```
BUILD SUCCESSFUL in 21s
77 actionable tasks: 40 executed, 37 up-to-date
```

`:udeaVerifyDeterminism` in `build` needs no separate run. `runUdpProof` and `runLaneShot` were not run: nothing in them changed, and runUdpProof is red on the baseline.

## 4. Images

None. This ticket changes nothing a player or an agent sees. Its proof is test results, a replay digest and transcripts.

## 5. Acceptance criteria

**(a) "udea-core builds for all four targets, and its tests pass on jvmTest and wasmJsNodeTest."**
- jvm, android and wasmJs build, and the tests pass (section 1: 452 / 144 / 144).
- iOS is off by the lead's ruling, because of Fleks. What each Fleks version publishes, read from its Gradle `.module` on Maven Central (`logs/fleks-variants.txt`, last four rows):

```
2.12 ['jsApiElements-published', 'jvmApiElements-published', 'linuxArm64ApiElements-published', 'linuxX64ApiElements-published', 'macosArm64ApiElements-published', 'macosX64ApiElements-published', 'mingwX64ApiElements-published', 'wasmJsApiElements-published']
2.13 ['jvmApiElements-published', 'linuxArm64ApiElements-published', 'linuxX64ApiElements-published', 'macosArm64ApiElements-published', 'macosX64ApiElements-published', 'mingwX64ApiElements-published', 'wasmJsApiElements-published']
2.14 ['jvmApiElements-published', 'linuxArm64ApiElements-published', 'linuxX64ApiElements-published', 'macosArm64ApiElements-published', 'mingwX64ApiElements-published', 'wasmJsApiElements-published']
2.15 ['jvmApiElements-published', 'linuxArm64ApiElements-published', 'linuxX64ApiElements-published', 'macosArm64ApiElements-published', 'mingwX64ApiElements-published', 'wasmJsApiElements-published']
```

- The same file lists every version back to `preRelease-20211120`, and none names iOS.
- With iOS on, resolution fails on this Linux box (`logs/ios-fleks-resolution-KEEP.log`):

```
> Could not resolve all files for configuration ':udea-core:iosArm64CompilationDependenciesMetadata'.
   > Could not resolve io.github.quillraven.fleks:Fleks:2.14.
```

**(b) "No `java.*` import in `commonMain`."**
- `grep -rnE "^import java(x)?\." udea-core/src/commonMain | wc -l` prints `0`.
- The only file under `jvmAndAndroidMain` that imports `java.*` is `loop/Threads.jvmAndAndroid.kt`.
- The stronger proof is that `commonMain` compiles for wasmJs, where no `java.*` exists, including fully qualified uses an import grep would miss.

**(c) "The JVM world hash for an existing SimHarness scenario is unchanged versus master."**
- `SimHarnessWorldHashPinTest` (udea-agent) runs the Phase 1 SimHarness workflow: spawn 20 grunts, step 200, snapshot, edit one Health, step 100, rewind 100.
- It asserts four `WorldHasher` hashes, recorded at 739deda, where udea-core is unchanged from master.
- It passed there (`logs/pin-baseline-pass.log`) and passes on the branch (section 1). It goes red when id assignment moves (m4).
- A wider check alongside it: the moba 3600-tick replay digest (`sh gradlew :moba:udeaReplayDigest -Pudea.replay.label=issue203 -Pudea.replay.out=<file>`). Run on 739deda and on b22fc59, the two outputs are identical: `cmp` is silent, and both have sha256 `c9f3fa6f4d6a0288f3dcca5ba4561961d45570e3beabefe1241c0d8aeb7b7749` (`logs/origin-kmp-739deda.udeaeq`, `logs/branch-b22fc59.udeaeq`). The same comparison was also run at 5532aaf.

**Carry-over from #201:**
- **Fix `udeaVerifyDeterminism` for the KMP layout.**
  - `DeterminismLayoutTest` was red before the fix (`logs/layout-red.log`): `DeterminismLayoutTest > a multiplatform module is read from the bytecode of its JVM and Android targets only() FAILED`.
  - End-to-end: a wall-clock read planted in `jvmAndAndroidMain` is caught, and missed with the fix reverted (m5).
- **Add udea-core to `ios-tests`.** Superseded by the lead's ruling. Not added, and #215 carries it.

## 6. Regenerated files

None. No replicated component was added or removed. `net-protocol.lock` and `expected-generated-hashes.txt` are untouched, and `udeaCheckProtocolLock` passed inside `build`.

## 7. `docs/contracts/`

Untouched. `udeaVerifyContracts` passed inside `build`.

## 8. Mutations

Each diff below is the literal file from `mut/`. Every mutation was reverted afterwards, and `git status` is clean.

**m1: min-heap sift-down picks the larger child** (`mut/m1-heap-siftdown.diff`)

```diff
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/module/SystemOrder.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/module/SystemOrder.kt
@@ -174,7 +174,7 @@
             val left = 2 * parent + 1
             if (left >= size) break
             val right = left + 1
-            val smaller = if (right < size && heap[right] < heap[left]) right else left
+            val smaller = if (right < size && heap[right] > heap[left]) right else left
             if (heap[parent] <= heap[smaller]) break
             swap(parent, smaller)
             parent = smaller
```

Result (`mut/m1-heap-siftdown.log`):
- Red on both jvm and wasm: `SystemOrderSortTest` had 3 failures in each.
- The existing `SystemOrderTest` stayed green, which is why `SystemOrderSortTest` was added.

```
SystemOrderSortTest[jvm] > random acyclic graphs sort exactly as the lowest-ready-index rule says()[jvm] FAILED
    org.opentest4j.AssertionFailedError at SystemOrderSortTest.kt:39

SystemOrderSortTest[jvm] > a constraint delays only what it constrains, and an unconstrained node may overtake it()[jvm] FAILED
    org.opentest4j.AssertionFailedError at SystemOrderSortTest.kt:29

SystemOrderSortTest[jvm] > unconstrained nodes run in registration order()[jvm] FAILED
    org.opentest4j.AssertionFailedError at SystemOrderSortTest.kt:21

452 tests completed, 3 failed
```

**m2: SimBarrier drains in reverse** (`mut/m2-barrier-drain-order.diff`)

```diff
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/loop/SimBarrier.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/loop/SimBarrier.kt
@@ -164,7 +164,7 @@
         try {
             var index = 0
             while (index < size) {
-                val action = running[index]
+                val action = running[size - 1 - index]
                 try {
                     action.apply(world, ctx)
                 } catch (failure: Exception) {
```

Result:
- The pin test **passed** (`mut/m2-pin.log`), because every drain in that scenario holds a single command. That is a limit of the pin, stated here rather than hidden.
- The moba replay digest caught it: `cmp mut/m2.udeaeq logs/origin-kmp-739deda.udeaeq` prints `mut/m2.udeaeq logs/origin-kmp-739deda.udeaeq differ: byte 24847, line 226`.

**m3: regrow copies from the new offset, not the old** (`mut/m3-regrow-source-offset.diff`)

```diff
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/snapshot/ColumnarFieldStore.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/snapshot/ColumnarFieldStore.kt
@@ -344,7 +344,7 @@
         if (count == 0) return current
         val grown = allocate(count * capacity)
         for (column in 0 until count) {
-            val from = column * slotCount
+            val from = column * capacity
             current.copyRange(grown, column * capacity, from, from + slotCount)
         }
         return grown
```

Result:
- `jvmTest` has 7 failures (`mut/m3.log`: `452 tests completed, 7 failed`), in ColumnarFieldStoreTest ×4, DivergenceReportTest, FieldStoreAllocationTest and SnapshotRoundTripTest.
- The moba digest was unchanged (`cmp` silent on `mut/m3.udeaeq`), because that store never regrows in the replay.

**m4: NetId allocation skips index 0** (`mut/m4-netid-first-index.diff`)

```diff
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/identity/NetIdIndex.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/identity/NetIdIndex.kt
@@ -201,7 +201,7 @@
             recycled
         }
 
-        nextFresh < capacity -> nextFresh++
+        nextFresh < capacity -> ++nextFresh
         else -> throw NetIdExhaustedException(capacity)
     }
 
```

Result (`mut/m4-pin.log`):

```
SimHarnessWorldHashPinTest > the Phase 1 workflow hashes on the JVM exactly as it did before the multiplatform port() FAILED
```

**m5: determinism layout ignores multiplatform** (`mut/m5-layout-ignores-multiplatform.diff`)

```diff
--- a/build-logic/src/main/kotlin/dev/wildware/udea/build/determinism/DeterminismLayout.kt
+++ b/build-logic/src/main/kotlin/dev/wildware/udea/build/determinism/DeterminismLayout.kt
@@ -28,7 +28,7 @@
     fun scopeInput(repoRoot: File, scope: SimScope): DeterminismScan.ScopeInput {
         val module = repoRoot.resolve(scope.project.removePrefix(":").replace(':', '/'))
         val suffix = scope.sourceSet.replaceFirstChar { it.uppercase() }
-        return if (isMultiplatform(module)) {
+        return if (false) {
             DeterminismScan.ScopeInput(
                 scope = scope,
                 classRoots = BYTECODE_TARGETS.map { module.resolve("build/classes/kotlin/$it/${scope.sourceSet}") },
```

The test plants a file, `udea-core/src/jvmAndAndroidMain/kotlin/dev/wildware/udea/core/PlantedWallClock.kt`, that calls `System.currentTimeMillis`. The planted file was removed afterwards.
- **Correct layout** (`mut/m5a-planted-fixed-layout.log`):

```
    scanned :udea-core: 562 class files
    scanned :udea-gas: 135 class files
    scanned :udea-net: 181 class files
    scanned :moba: 370 class files
    allowlist entries used: 0
    findings: 1
  udea-core/src/jvmAndAndroidMain/kotlin/dev/wildware/udea/core/PlantedWallClock.kt:4:1: error: [DET001] dev.wildware.udea.core.PlantedWallClockKt.plantedWallClock is declared simulation (:udea-core) and reads the wall clock: it references java.lang.System.currentTimeMillis.
```

- **Mutated** (`mut/m5b-planted-mutated-layout.log`): it scans the stale JVM-layout classes and passes.

```
  scanned :udea-core: 277 class files
  scanned :udea-gas: 135 class files
  scanned :udea-net: 181 class files
  scanned :moba: 370 class files
  allowlist entries used: 0
  findings: 0
```

- **Unit test under the same mutation** (`mut/m5c-layout-unit.log`): `DeterminismLayoutTest > a multiplatform module is read from the bytecode of its JVM and Android targets only() FAILED`.

## 9. Not exercised

- **iOS.** It cannot be built here, and it is off anyway. The native actual was checked on linuxX64 only.
- **The Android device runtime.** Only `testAndroidHostTest` (a JVM host run of common tests) was run.
- **Wasm in a browser.** The Node test runner only.
- **`runUdpProof`, `runLaneShot` and a live bridge session.** The change is invisible to all three. The JVM behaviour is pinned by the world hash, the replay digest and `jvmTest`.
