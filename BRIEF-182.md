47ea8e8

# BRIEF-182 — the last wall-clock budgets left `build`, and the sentence that said so is now checked

Branch `issue-182-remaining-wall-clock-budgets`, off `origin/example` at `293649b`.
Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a937a08ec67e02f7e`.

---

## 1. The evidence command

```
sh gradlew udeaLatencyBudgets --no-parallel --max-workers=1
```

(Prefix `JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem` on this box; Gradle 8.13 does not
support the default Temurin 25.0.2 and says so in one line, `25.0.2`.)

It runs all **eleven** members of the aggregate — the eight #175 left, plus the three this ticket
moved — with the runner to itself. Green on `47ea8e8`, at load average 7.06. The whole run is
`scratchpad/evidence-green-47ea8e8.txt`; the budget tasks in it are what this returns, and the
lines it drops are the compile and up-to-date tasks Gradle interleaves between them:

```
$ grep -E '^> Task .*:udea(SnapshotBudget|BenchTickLoop|BenchCharacterMover|PhysicsRebuildBudget|DaemonBudget|GraphBudget|ScanBudget|WarmEditBudget|DigestBudget|QueryBudget|Phase2Exit|LatencyBudgets)$' evidence-green-47ea8e8.txt
> Task :udea-agent:udeaDigestBudget
> Task :udea-agent:udeaQueryBudget
> Task :udea-agent-host:udeaPhase2Exit
> Task :udea-assets-compiler:udeaDaemonBudget
> Task :udea-assets-compiler:udeaGraphBudget
> Task :udea-assets-compiler:udeaScanBudget
> Task :udea-assets-compiler:udeaWarmEditBudget
> Task :udea-core:udeaBenchCharacterMover
> Task :udea-core:udeaBenchTickLoop
> Task :udea-core:udeaPhysicsRebuildBudget
> Task :udea-core:udeaSnapshotBudget
> Task :udeaLatencyBudgets
```

and the last four lines of the same file, contiguous:

```

BUILD SUCCESSFUL in 35s
58 actionable tasks: 11 executed, 47 up-to-date
Configuration cache entry stored.
```

`11 executed` is not incidental: a `Test` task is cacheable, and #175's first fully green latency
job had served every gate `FROM-CACHE` and measured nothing. `outputs.upToDateWhen { false }` and
`cacheIf { false }` in the root script are derived from `latencyBudgetTasks`, so the three new
members inherited both without a line of their own — and `LatencyBudgetJobTest`'s
`a latency budget is never up to date and never served from the build cache` still guards the
mechanism.

Everything the eleven gates printed on that run — the verbatim output of one grep over the same
file, nothing removed from within it and nothing reordered:

```
$ grep -E '^    [a-zA-Z\[]' evidence-green-47ea8e8.txt
    digest render: 0B across 1000 builds
    digest build at 500 entities: median 5270ns (budget 300000ns), 1611 chars
    digest build: 7120ns at 0 entities, 7101ns at 500 - ratio 1.00
    digest publish: 1656B for a 1611-char document
    query allocation at 500 entities: 22344B
    query over 500 entities: median 27010ns (budget 1000000ns)
    [phase2-demo] daemon start: ok=true 2725ms 3 assets
    [phase2-demo] assetRoot=/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a937a08ec67e02f7e/udea-agent-host/build/tmp/phase2-test-301ec38b/assets
    [phase2-demo] probe entity netId=0
    [phase2-demo] listening on http://127.0.0.1:36535 with 39 tools
    phase 2 exit: typo'd reference rejected in 9ms (median of [345, 9, 9])
    [phase2-demo] daemon start: ok=true 317ms 3 assets
    [phase2-demo] assetRoot=/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a937a08ec67e02f7e/udea-agent-host/build/tmp/phase2-test-593d5f39/assets
    [phase2-demo] probe entity netId=0
    [phase2-demo] listening on http://127.0.0.1:46665 with 39 tools
    phase 2 exit: agent request -> running world observed changed in 456ms
    warm reload decision: median 237ms over 4 samples [272, 237, 236, 183]
    warm validate of one script: median 160ms over 4 samples [11, 160, 198, 152]
    graph deserialisation: best=4.749277ms median=5.357589ms over 2000 assets (budget 15ms)
    warm scan of the example tree: 96.237573ms over 19 files
    moba warm edit -> observed: max 201ms, median 169ms, min 167ms over 5 samples [201, 181, 167, 169, 167] (budget 3000ms, corpus 147 assets)
    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) best 1.941ms, median 2.232ms, worst 3.218ms, budget 4.0ms
    udeaBenchTickLoop: 300 steady-state ticks allocated 0 bytes; ring held 621 slots over 14533884 bytes
    udeaBenchTickLoop: 600 ticks at 200 entities, median 6.823115ms, p95 9.040807ms, budget 50.0ms
    PhysicsRebuildBudgetTest: 500 bodies rebuilt in 537us (median of 21, best 465us, worst 2482us)
    udeaSnapshotBudget: ring held 700 slots, 63691600 bytes of 67108864, sparseInterval 6 after 0 degrade(s)
    udeaSnapshotBudget: warm capture allocated 0 bytes
    udeaSnapshotBudget: capture of 1000 entities median 83921ns, p95 127092ns, budget 1000000ns
```

### It goes red on a deliberate slowdown

The slowdown goes into the **production method being measured**, not into the test. Each mutation's
literal `git diff` is below, taken from the run, not retyped.

**The named command itself, red.** Physics mutation applied, whole aggregate run
(`scratchpad/evidence-red.txt`):

Lines 176-182 of `scratchpad/evidence-red.txt`, contiguous, then line 195:

```
> Task :udea-core:udeaPhysicsRebuildBudget FAILED

PhysicsRebuildBudgetTest > rebuilding 500 bodies completes in under 2ms() STANDARD_OUT
    PhysicsRebuildBudgetTest: 500 bodies rebuilt in 3442us (median of 21, best 3416us, worst 3917us)

PhysicsRebuildBudgetTest > rebuilding 500 bodies completes in under 2ms() FAILED
    org.opentest4j.AssertionFailedError at PhysicsRebuildBudgetTest.kt:68
[... 7 tests completed line and Gradle's FAILURE block, lines 183-194 ...]
BUILD FAILED in 30s
```

---

## 2. Mutation table — every gate and every fence, with its diff

M1–M3 are the three moved gates, one each. M4–M8 are the fences this ticket adds, because a fence
nobody has watched refuse is the same object as a test nobody has watched fail.

`sh gradlew build` is not among the commands below on purpose: none of these gates is reachable
from it any more, which is criterion 1.

### M1 — `:udea-core:udeaPhysicsRebuildBudget` (2 000 us median)

```diff
--- a/udea-core/src/main/kotlin/dev/wildware/udea/core/physics/NoOpPhysicsWorld.kt
+++ b/udea-core/src/main/kotlin/dev/wildware/udea/core/physics/NoOpPhysicsWorld.kt
@@ -151,6 +151,7 @@ public class NoOpPhysicsWorld : PhysicsWorld {
     internal val contactListenerCount: Int get() = listeners.size
 
     override fun rebuildFrom(world: World, netIds: NetIdIndex) {
+        Thread.sleep(3)
         destroyAllBodies()
         // `plan.rebuild` rather than a loop over `plan.bodies`: the shared walk also clears the
         // handle of every entity the plan skips, which is the half a hand-written loop forgets.
```

`sh gradlew :udea-core:udeaPhysicsRebuildBudget --no-parallel --max-workers=1`, exit 1
(`scratchpad/mut-physics.log`, lines 58-64 contiguous, then line 85):

```
> Task :udea-core:udeaPhysicsRebuildBudget FAILED

PhysicsRebuildBudgetTest > rebuilding 500 bodies completes in under 2ms() STANDARD_OUT
    PhysicsRebuildBudgetTest: 500 bodies rebuilt in 3440us (median of 21, best 3414us, worst 3617us)

PhysicsRebuildBudgetTest > rebuilding 500 bodies completes in under 2ms() FAILED
    org.opentest4j.AssertionFailedError at PhysicsRebuildBudgetTest.kt:68
[... lines 65-84 ...]
BUILD FAILED in 2s
```

Predicted 546 + 3000 = 3546 us; measured 3440 us. The arithmetic of the explanation matches the
size of the effect.

### M2 — `:udea-assets-compiler:udeaScanBudget` (200 ms)

```diff
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/scan/UdeaDeclarationScanner.kt
@@ -171,6 +171,7 @@ public class UdeaDeclarationScanner @JvmOverloads constructor(
     /** Every `.udea.kts` under the asset root, sorted, scanned. */
     @OptIn(kotlin.io.path.ExperimentalPathApi::class)
     public fun scanTree(): ScanReport {
+        Thread.sleep(300)
         val files = assetRoot.walk()
             .filter { it.isRegularFile() && it.name.endsWith(SCRIPT_SUFFIX) }
             .sortedBy { it.toString().replace('\\', '/') }
```

`sh gradlew :udea-assets-compiler:udeaScanBudget --no-parallel --max-workers=1`, exit 1
(`scratchpad/mut-scan.log`, lines 62-66 contiguous, then line 87):

```
WarmScanBudgetTest > a warm scan of the whole tree is under 200ms() STANDARD_OUT
    warm scan of the example tree: 365.297631ms over 19 files

WarmScanBudgetTest > a warm scan of the whole tree is under 200ms() FAILED
    org.opentest4j.AssertionFailedError at WarmScanBudgetTest.kt:52
[... lines 67-86 ...]
BUILD FAILED in 3s
```

58–96 ms plus 300 ms of sleep is 358–396 ms; measured 365 ms.

### M3 — `:udea-assets-compiler:udeaWarmEditBudget` (3 000 ms)

```diff
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt
@@ -166,6 +166,7 @@ public class AssetDaemon(
      * daemon must not move its own graph at decision time.
      */
     public fun reload(changed: Collection<Path>): ReloadOutcome {
+        Thread.sleep(3_500)
         val began = System.nanoTime()
         val touched = changed.map { it.toAbsolutePath().normalize() }.distinct()
         if (touched.isEmpty()) return ReloadOutcome.NoChange(millisSince(began))
```

`sh gradlew :udea-assets-compiler:udeaWarmEditBudget --no-parallel --max-workers=1`, exit 1
(`scratchpad/mut-warmedit.log`, lines 61-65 contiguous, then line 88):

```
MobaWarmEditBudgetTest > a warm edit of the real moba corpus is observed in under three seconds() STANDARD_OUT
    moba warm edit -> observed: max 3688ms, median 3659ms, min 3652ms over 5 samples [3652, 3659, 3668, 3688, 3658] (budget 3000ms, corpus 147 assets)

MobaWarmEditBudgetTest > a warm edit of the real moba corpus is observed in under three seconds() FAILED
    org.opentest4j.AssertionFailedError at MobaWarmEditBudgetTest.kt:94
[... lines 66-87 ...]
BUILD FAILED in 32s
```

167 ms plus 3 500 ms of sleep is 3 667 ms; measured 3 652–3 688 ms.

### M4 — the `measuredBy` guard, which is what stops a gate creeping back onto `check`

Delete the one line that holds a budget out of `test`:

```diff
diff --git a/udea-core/build.gradle.kts b/udea-core/build.gradle.kts
index f207336..712f843 100644
--- a/udea-core/build.gradle.kts
+++ b/udea-core/build.gradle.kts
@@ -94,7 +94,6 @@ val budgetTestClasses = listOf(
     // Split out of `PhysicsRebuildTest` by issue #182. It had never been listed as a latency
     // budget by anybody, so a 2ms line was read inside every parallel `build` - and passed, which
     // is why nobody noticed. The rest of that class is reproducibility and stays on `check`.
-    "dev.wildware.udea.core.physics.PhysicsRebuildBudgetTest",
 )
 
 tasks.named<Test>("test") {
```

`sh gradlew :udea-core:test --tests '*PhysicsRebuildBudgetTest*'`, exit 1
(`scratchpad/mut-exclusion.log`, message read out of
`udea-core/build/test-results/test/TEST-dev.wildware.udea.core.physics.PhysicsRebuildBudgetTest.xml`):

Lines 58-63 of `scratchpad/mut-exclusion.log`, contiguous:

```
> Task :udea-core:test FAILED

PhysicsRebuildBudgetTest > rebuilding 500 bodies completes in under 2ms() FAILED
    java.lang.IllegalStateException at PhysicsRebuildBudgetTest.kt:44

1 test completed, 1 failed
```

The message itself, one physical line, unwrapped, from
`scratchpad/mut-exclusion-report.xml`:

```
java.lang.IllegalStateException: this is a wall-clock latency budget and it is being run by `:udea-core:test`, not by `:udea-core:udeaPhysicsRebuildBudget`. A budget measured by anything other than its own task is measured beside whatever else that task's build is doing, which is what issue #175 and issue #182 were filed for - and it will pass anyway, because these gates carry tens of times the headroom they need. Restore the `filter.excludeTestsMatching` line that keeps this class out of `:udea-core:test`, or if the budget genuinely moved, change the task named here and in `latencyBudgetTasks`.
```

**This mutation was not only a demonstration.** Partway through the ticket a `git checkout --` of
mine reverted `udea-core/build.gradle.kts` wholesale, silently dropping both the new task and its
exclusion. `sh gradlew build` went red on exactly this message, and that is how I found out. The
census fence did **not** catch it — it reads the root list, which still named the task. The two
fences cover different halves and neither is complete alone.

### M5 — the census fence, on a timing test planted where none has ever been

```diff
diff --git a/udea-audio/src/test/kotlin/dev/wildware/udea/audio/CueAudioTest.kt b/udea-audio/src/test/kotlin/dev/wildware/udea/audio/CueAudioTest.kt
index e913455..0a0423a 100644
--- a/udea-audio/src/test/kotlin/dev/wildware/udea/audio/CueAudioTest.kt
+++ b/udea-audio/src/test/kotlin/dev/wildware/udea/audio/CueAudioTest.kt
@@ -16,6 +16,14 @@ import kotlin.test.assertTrue
  */
 class CueAudioTest {
 
+    @Test
+    fun `draining a thousand cues is under fifty milliseconds`() {
+        val began = System.nanoTime()
+        repeat(1000) { }
+        val elapsedMs = (System.nanoTime() - began) / 1_000_000
+        assertTrue(elapsedMs < 50, "draining took it ms")
+    }
+
     private val hit = CueId(2)
     private val swoosh = CueId(3)
 
```

`sh gradlew :udea-gradle:test --tests '*WallClockBudgetCensusTest'`, exit 1
(`scratchpad/mut-planted.log`, lines 63-68 contiguous, then line 87):

```
WallClockBudgetCensusTest > every wall-clock reading in a test source is a budget or a censused non-budget() FAILED
    org.opentest4j.AssertionFailedError at WallClockBudgetCensusTest.kt:47

7 tests completed, 1 failed

> Task :udea-gradle:test FAILED
[... lines 69-86 ...]
BUILD FAILED in 6s
```

The message itself, from
`udea-gradle/build/test-results/test/TEST-dev.wildware.udea.gradle.ci.WallClockBudgetCensusTest.xml`:

```
org.opentest4j.AssertionFailedError: these test sources read a wall clock and are neither a declared latency budget nor a row in this file's census:
  udea-audio/src/test/kotlin/dev/wildware/udea/audio/CueAudioTest.kt reads System.nanoTime

If the reading is asserted against a number of milliseconds it is a latency budget: give it its own `Test` task, add that task to `latencyBudgetTasks` in build.gradle.kts, and have it call `LatencyBudget.measuredBy` and `LatencyBudget.contentionNote`. If it is a timeout, a seed or a printed figure, add a row to `NOT_A_BUDGET` saying which.
```

### M6 — `LatencyBudget.measuredBy` gutted

`check(running == taskPath)` to `check(true)`, everything else untouched:

```diff
diff --git a/udea-diagnostics/src/testFixtures/kotlin/dev/wildware/udea/diagnostics/bench/LatencyBudget.kt b/udea-diagnostics/src/testFixtures/kotlin/dev/wildware/udea/diagnostics/bench/LatencyBudget.kt
index 951c3f8..4c2d721 100644
--- a/udea-diagnostics/src/testFixtures/kotlin/dev/wildware/udea/diagnostics/bench/LatencyBudget.kt
+++ b/udea-diagnostics/src/testFixtures/kotlin/dev/wildware/udea/diagnostics/bench/LatencyBudget.kt
@@ -59,7 +59,7 @@ object LatencyBudget {
      */
     fun measuredBy(taskPath: String) {
         val running = System.getProperty(TEST_TASK_PROPERTY) ?: return
-        check(running == taskPath) {
+        check(true) {
             "this is a wall-clock latency budget and it is being run by `$running`, not by " +
                 "`$taskPath`. A budget measured by anything other than its own task is measured " +
                 "beside whatever else that task's build is doing, which is what issue #175 and " +
```

`sh gradlew :udea-diagnostics:test --tests '*LatencyBudgetTest'`, exit 1
(`scratchpad/mut-measuredby.log`, lines 21-26 contiguous, then line 37):

```
LatencyBudgetTest > measuredBy refuses a budget run by the wrong task and allows one run by no task() FAILED
    org.opentest4j.AssertionFailedError at LatencyBudgetTest.kt:68

4 tests completed, 1 failed

> Task :udea-diagnostics:test FAILED
[... Gradle's FAILURE block, lines 27-36 ...]
BUILD FAILED in 16s
```

### M7 — a census row that no longer describes anything

`PhysicsRebuildTest` no longer reads a clock, so a row claiming it does must be refused:

```diff
diff --git a/udea-gradle/src/test/kotlin/dev/wildware/udea/gradle/ci/WallClockBudgetCensusTest.kt b/udea-gradle/src/test/kotlin/dev/wildware/udea/gradle/ci/WallClockBudgetCensusTest.kt
index 1ab91e0..87af242 100644
--- a/udea-gradle/src/test/kotlin/dev/wildware/udea/gradle/ci/WallClockBudgetCensusTest.kt
+++ b/udea-gradle/src/test/kotlin/dev/wildware/udea/gradle/ci/WallClockBudgetCensusTest.kt
@@ -310,6 +310,8 @@ class WallClockBudgetCensusTest {
          *   claim about complexity and cancels the machine out.
          */
         val NOT_A_BUDGET: Map<String, String> = mapOf(
+            "udea-core/src/test/kotlin/dev/wildware/udea/core/physics/PhysicsRebuildTest.kt" to
+                "a stale row: the clock reading moved to PhysicsRebuildBudgetTest",
             "moba/src/test/kotlin/dev/wildware/moba/net/MobaUdpTwoProcessTest.kt" to
                 "a deadline: how long to wait for a line from a forked process",
             "moba/src/test/kotlin/dev/wildware/moba/replay/MobaReplayProofTest.kt" to
```

`sh gradlew :udea-gradle:test --tests '*WallClockBudgetCensusTest'`, exit 1
(`scratchpad/mut-staleRow.log`, lines 46-51 contiguous, then line 62):

```
WallClockBudgetCensusTest > no census row names a file that has stopped reading a clock() FAILED
    org.opentest4j.AssertionFailedError at WallClockBudgetCensusTest.kt:66

7 tests completed, 1 failed

> Task :udea-gradle:test FAILED
[... Gradle's FAILURE block, lines 52-61 ...]
BUILD FAILED in 7s
```

### M8 — the walk pointed one letter away from the tree

The vacuity guard, which is the assertion that stops every other assertion in the file from passing
over an empty map:

```diff
diff --git a/udea-gradle/src/test/kotlin/dev/wildware/udea/gradle/ci/WallClockBudgetCensusTest.kt b/udea-gradle/src/test/kotlin/dev/wildware/udea/gradle/ci/WallClockBudgetCensusTest.kt
index 1ab91e0..3b60e42 100644
--- a/udea-gradle/src/test/kotlin/dev/wildware/udea/gradle/ci/WallClockBudgetCensusTest.kt
+++ b/udea-gradle/src/test/kotlin/dev/wildware/udea/gradle/ci/WallClockBudgetCensusTest.kt
@@ -233,7 +233,7 @@ class WallClockBudgetCensusTest {
             .onEnter { it.name !in PRUNED }
             .filter { it.isFile && it.extension == "kt" }
             .map { it.relativeTo(root).path.replace(File.separatorChar, '/') to it }
-            .filter { (path, _) -> "/src/test/" in path || "/src/testFixtures/" in path }
+            .filter { (path, _) -> "/src/tests/" in path || "/src/testFixtures/" in path }
             .associate { (path, file) -> path to KotlinSource(file.readText()) }
         check(sources.isNotEmpty()) {
             "no Kotlin test source found under ${root.absolutePath}; the fence would pass over " +
```

`sh gradlew :udea-gradle:test --tests '*WallClockBudgetCensusTest'`, exit 1
(`scratchpad/mut-vacuity.log`, lines 44-52 contiguous, then line 63):

```
> Task :udea-gradle:test FAILED

WallClockBudgetCensusTest > the scan actually found sources to scan() FAILED
    org.opentest4j.AssertionFailedError at WallClockBudgetCensusTest.kt:187

WallClockBudgetCensusTest > no census row names a file that has stopped reading a clock() FAILED
    org.opentest4j.AssertionFailedError at WallClockBudgetCensusTest.kt:66

7 tests completed, 2 failed
[... Gradle's FAILURE block, lines 53-62 ...]
BUILD FAILED in 2s
```

The guard's own message, from `scratchpad/mut-vacuity-report.xml`:

```
org.opentest4j.AssertionFailedError: only 0 test source(s) read a clock; that is too few for a repository this size, so the walk is looking in the wrong place
```

Worth noting what this run does **not** say. Only two of the seven went red, not all seven — the
`src/testFixtures` half of the filter still matched, so the map was non-empty and
`check(sources.isNotEmpty())` never fired. The three assertions about undecided candidates,
censused files and declared budgets all passed **over one file**, silently and vacuously, which is
precisely the failure the twenty-file floor exists to catch and precisely why a non-empty check
would not have been enough on its own.

Which of the fence's seven assertions I have watched refuse, and how:

| Assertion | Watched red by |
|---|---|
| `every wall-clock reading in a test source is a budget or a censused non-budget` | section 8, and again in M5 |
| `no censused file asserts an elapsed wall-clock time` | section 8 |
| `every declared budget names a task the aggregate measures` | section 8 |
| `the root build script tells every test task which task it is` | section 8 |
| `no census row names a file that has stopped reading a clock` | M7, and again in M8 |
| `the scan actually found sources to scan` | M8 |
| `the scan is blind to comments and strings and not to code` | not watched red — it is itself the known-negative control, asserting in both directions over four synthetic sources within one method |

### M5-control — the same file, the same words, in a comment

A fence that fails on prose is as wrong as one that passes on code. `udea-net`'s `NetHarness` and
`Transport` both *name* `Thread.sleep` in their KDoc, and `DeterminismScannerTest` holds
`System.nanoTime()` inside Java fixture text, so this is not hypothetical.

```diff
diff --git a/udea-audio/src/test/kotlin/dev/wildware/udea/audio/CueAudioTest.kt b/udea-audio/src/test/kotlin/dev/wildware/udea/audio/CueAudioTest.kt
index e913455..1722ad5 100644
--- a/udea-audio/src/test/kotlin/dev/wildware/udea/audio/CueAudioTest.kt
+++ b/udea-audio/src/test/kotlin/dev/wildware/udea/audio/CueAudioTest.kt
@@ -16,6 +16,7 @@ import kotlin.test.assertTrue
  */
 class CueAudioTest {
 
+    // A drain must never call System.nanoTime(); an elapsedMs < 50 assertion would be wrong here.
     private val hit = CueId(2)
     private val swoosh = CueId(3)
 
```

Exit 0. The last five lines of `scratchpad/control-comment.log`, contiguous:

```
> Task :udea-gradle:test

BUILD SUCCESSFUL in 6s
17 actionable tasks: 1 executed, 16 up-to-date
Configuration cache entry reused.
```

The same control also runs inside the fence itself, on synthetic sources, as
`the scan is blind to comments and strings and not to code` — a clock in a comment, a clock in a
plain string, a clock in a raw string, and a clock in code, with the code case additionally
required to be seen asserting a value **two derivations** away from the reading.

---

## 3. What I did, and what I decided

The issue names two gates. I did not take that on faith, and it was five. Section 6 has the
enumeration.

### The rule I applied, stated once

For each wall-clock assertion inside `build`: **move it** to the aggregate if the millisecond line
measures a property nothing else asserts; **drop it** if a machine-independent assertion already
discriminates the same regression — and if dropping, say what does the discriminating.

| Gate | Ruling | Why |
|---|---|---|
| `MobaWarmEditBudgetTest`, 3 000 ms | **moved** → `:udea-assets-compiler:udeaWarmEditBudget` | Spec 6 Phase 2 exit criterion; nothing else measures edit-to-observe over the real corpus |
| `ExampleScanTest`, 200 ms | **moved** → `:udea-assets-compiler:udeaScanBudget` | Issue #85's number; nothing else measures a warm pass-1 scan |
| `PhysicsRebuildTest`, 2 ms | **moved** → `:udea-core:udeaPhysicsRebuildBudget` | Spec 3.4 restore cost; nothing else measures a rebuild |
| `AssetCompilerTest`, 1 s | **dropped** | `assertEquals(scripts.size, warm.cacheHits)`, two lines below, decides the same regression exactly and on any machine |
| `NetHarnessTest`, 2 s | **dropped** | `Thread.sleep` added to `NoWallClockInTransportTest.BANNED`, which asserts "no sleeps" by file and line rather than by stopwatch |

Both drops are written into `docs/budgets.md` under *"Two wall-clock assertions issue #182 dropped
rather than moved"*, because a deleted gate is exactly what a later reader assumes was an oversight.

### `ExampleScanTest` — the ruling the issue asked for explicitly

**Moved, not dropped.** The issue describes it as *"one assertion inside a test whose real subject
is the golden scan"*; it is in fact its own whole `@Test` method, `a warm scan of the whole tree is
under 200ms`, whose entire subject is the budget. The only other assertion in it,
`assertEquals(19, report.files.size)`, is the control that stops it timing an empty scan and moved
with it. So the split cost one file, not surgery, and issue #85's number goes on being checked
instead of quietly ceasing to exist. Every other claim `ExampleScanTest` makes — the golden, the
spans, the per-file cache, byte-identical output from two checkouts — is untouched and still on
`check`.

**Rejected:** deleting the assertion, which the issue also offers as defensible. To take that
option instead: delete `WarmScanBudgetTest.kt`, and remove `:udea-assets-compiler:udeaScanBudget`
from `latencyBudgetTasks` and from `budgetTestClasses` in `udea-assets-compiler/build.gradle.kts`.
Nothing else moves.

### `MobaWarmEditBudgetTest` keeps `samples.max()`

I measured before deciding. On this box at load 1.17, on `origin/example` before I touched
anything, the five counted samples were:

> `moba warm edit -> observed: max 147ms, median 132ms, min 127ms over 5 samples`
> `[147, 131, 132, 127, 142] (budget 3000ms, corpus 147 assets)`

**That one is prose, not a transcript, and deliberately so.** It came from
`udea-assets-compiler/build/test-results/test/TEST-…MobaWarmEditBudgetTest.xml`, which
`sh gradlew clean build` later deleted. I looked for it rather than assuming it was gone:
`grep -rl 'max 147ms, median 132ms' /srv/ssd1/workspace/Udea /tmp/claude-1000` returns this
document and nothing else. It is not reproducible either — it is a measurement of a particular
machine at a particular moment on a tree that no longer exists.

It does not have to carry the argument alone. The **live** artefact,
`scratchpad/evidence-green-47ea8e8.txt` at load 7.06, says the same thing about the spread:
`max 201ms, median 169ms, min 167ms over 5 samples [201, 181, 167, 169, 167]`, quoted in section 1.
Roughly a 20% spread on a box four times busier.

A 16% spread between fastest and slowest. #175 changed `DaemonLatencyBudgetTest`'s reload gate from
the maximum to the median because there the maximum of five was *"the worst scheduling hiccup in a
two-minute window rather than anything about the daemon"* — 172 ms alone against 528 ms beside a
build, two to four times. That is not this gate's shape.

And the criterion is a deadline, not a throughput: *"an asset edit is observed in under three
seconds"* is a claim about every edit a person makes, so the tail **is** the subject. A median would
let one edit in three miss the deadline and report green. Changing it would have made the gate
strictly easier to pass, and the ticket forbids that without a demonstration that it still catches
the regression — and there was nothing to buy.

**Rejected:** switching to `samples.sorted()[samples.size / 2]`. If the owner disagrees, that
expression and the corresponding paragraph of the class KDoc are the whole change.

**Stated because it is the real weakness, and it is not what this ticket was asked to fix:** the
gate has ~18x headroom, so neither estimator catches anything short of an 18x regression. Narrowing
3 000 ms is a separate judgement and I have not made it.

### Criterion 3: the description is enforced, not asserted

Two fences, because "every wall-clock latency budget" is a claim about the whole tree and that
claim has now been made wrong twice.

**`:udea-gradle:WallClockBudgetCensusTest`** reads every `src/test` and `src/testFixtures` Kotlin
file in the repository, strips comments and string literals, and requires each file that reads a
clock to be **either** a declared budget naming a member of `latencyBudgetTasks` **or** a row in a
census stating what the reading is instead. Eighteen census rows, four dispositions — a deadline, a
seed, a printed figure, a ratio. What it asserts:

- an undecided candidate fails, naming the file and the token (M5 above);
- a census row that no longer names a clock-reading file fails, so the list cannot rot;
- a **censused file that asserts an elapsed value after all** fails — this is what would have caught
  `AssetCompilerTest` had somebody written its row without reading it, and it is what makes the
  prose in a row less load-bearing than it looks;
- every declared budget resolves its task path and that path is in `latencyBudgetTasks`;
- the walk found at least twenty candidate files, so nothing above can pass over an empty map.

**`LatencyBudget.measuredBy(taskPath)`**, called by all eleven budgets, compares the budget's own
task path against `udea.testTaskPath`, which the root build script now puts on **every** subproject
`Test` task. Absent property means an IDE or a plain `java` run and is allowed; a *different* task
is refused. Covered by `LatencyBudgetTest.measuredBy refuses a budget run by the wrong task and
allows one run by no task`, which exercises all three states.

Neither fence is complete alone and I have not claimed otherwise in the code: the census reads
source, so it cannot see a clock read inside production code and handed back as a number —
`DaemonLatencyBudgetTest` asserts `report.durationMs` and is invisible to it, which is why the
task-path half exists. M4 is the recorded case of the reverse.

### Things I deliberately did not do

- **No budget number widened or narrowed.** Not 3 000 ms, not 200 ms, not 2 ms.
- **`build-logic/` untouched** — `dev-180` owns it this wave.
- **`docs/contracts/` untouched.** `udeaVerifyContracts` is green.
- **`.github/workflows/ci.yml`'s `latency-budgets` job unchanged** in behaviour. It runs the
  aggregate, so the three new members joined it with no YAML edit. The one YAML change is the
  header comment on line 10, which enumerated six budget task names while the aggregate held
  eight; it now names `latencyBudgetTasks` instead of listing members, because a list in a comment
  is a count and counts go stale — that one had already gone stale before I arrived.
- **No screenshot.** See section 4.

---

## 4. There is nothing to photograph

This ticket is task-graph membership, build-script wiring and two source fences. Nothing it does
appears in a frame: no simulation behaviour changed, no HUD, no sprite, no asset. The evidence is
task lists, mutation diffs and executed transcripts, and inventing a screenshot to fill a section
would be worse than saying so.

It touches no GL either. No file under `udea-render` changed, and nothing in the diff opens a
context, so `udeaGlTest` / `udeaAgentGlTest` under `xvfb` would assert nothing about this branch and
I have not run them. The full diff is 31 files: three build scripts, one workflow comment, one
document, and 26 test/testFixtures sources.

---

## 5. `sh gradlew build`, real output

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew clean build --console=plain
```

Run on the committed tree at `47ea8e8`, exit 0. Load average at start `6.65 9.00 10.12`, at finish
`9.07 9.44 10.25` (`scratchpad/build-clean-load.txt`) — `melon-merge`'s scenario suite was on the
box throughout, which is now a non-event for this command and was the point of the ticket.

The last twelve lines of `scratchpad/build-clean-full.txt`, contiguous:

```
> Task :check
> Task :build
> Task :udea-compiler-plugin:test
> Task :udea-compiler-plugin:check
> Task :udea-compiler-plugin:build
> Task :moba:test
> Task :moba:check
> Task :moba:build

BUILD SUCCESSFUL in 16s
223 actionable tasks: 142 executed, 74 from cache, 7 up-to-date
Configuration cache entry reused.
```

**Read `16s` as "142 tasks executed, 74 served from the build cache", not as a cold-build time.**
`clean` deletes the output directories, not the Gradle build cache, so most of the compilation came
back from it. What the run does establish is that every `check` task executed and passed, over
every report it produced (`scratchpad/count-tests.py`, saved as `scratchpad/q-test-count.txt`):

```
suites=379 tests=2549 failures=0 errors=0 skipped=34
```

The 34 skips are the GL suites with no `$DISPLAY`, unchanged by this branch.

`sh gradlew udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd
udeaVerifyContracts` — `BUILD SUCCESSFUL`, 43 tasks up to date.

CI on the pushed branch: run **33459621243**,
<https://github.com/wildware-uk/Udea/actions/runs/33459621243> — includes the `latency budgets
(ubuntu-latest)` and `latency budgets (windows-latest)` legs, which is the first time the three new
gates are measured on a runner rather than on this box.

Every job in that run is green **except `clean build under budget`**, which needs more than a
pointer at #181 — see below. Both `latency budgets` legs passed, which is the first time the three
new gates have been measured on a runner rather than on this box.

**Not mine, and pre-existing on `origin/example`:** `KotlinPinCheckTest` (no JDK 17 on this box),
the `gl tests (xvfb)` `OffscreenBackendTest` shutdown flake (#178), and `:moba:runUdpProof` under
5% loss. None of them is touched by this diff.

### `clean build under budget`: what I can and cannot say

It failed on my branch:

```
##[error]clean build took 98749 ms, over the 90000 ms budget (spec 6, Phase 0 exit)
```

That is #181's gate, which the ticket says explicitly is not mine and which already has an issue
for being badly conditioned. **I am not going to leave it there, because the numbers do not quite
let me.** Two runs of `origin/example` within the hour, from the same job:

| Ref | Run | Measured |
|---|---|---|
| `example` | 33457093150 | `clean build took 82636 ms, within the 90000 ms budget` |
| `example` | 33460337882 | `clean build took 77598 ms, within the 90000 ms budget` |
| this branch | 33459621243 | `clean build took 98749 ms, over the 90000 ms budget` |

16-21 seconds above the two nearest mainline samples, and above the 81 426-94 984 ms range #181
records across five runs of identical work. **One sample is not a measurement, and I will not
assert either way from it.** What I can say about the mechanism:

- The one place this branch changes *what `build` executes* makes it **cheaper**, not dearer.
  `MobaWarmEditBudgetTest` used to run inside `:udea-assets-compiler:test`: a full copy of
  `moba/assets`, a daemon start and **six** warm edits, each a real Kotlin script compile. It is
  gone from `test`. What replaced it, `MobaWarmEditTest`, does the same setup and **two** edits.
- Everything else it adds to `build` is source to compile — about 1 350 lines across four modules —
  plus a repo-wide source walk in `:udea-gradle:test` that reads roughly 500 small files. Neither
  is a plausible sixteen seconds.
- So the arithmetic does not support "this branch made the clean build 20% slower", and it does not
  refute it either, because the gate's own recorded variance for identical work is 13 558 ms and
  five samples is not a distribution.

The discriminating experiment is another sample, and pushing this brief takes one. Whatever it
says, the reviewer should read this against #181 rather than against this ticket: the gate measures
a whole cold build on a shared runner, which is the same family of defect one scale up.

---

## 6. The enumeration — what I grepped for, and every wall-clock assertion in the tree

**This is the part the ticket said to assume was still incomplete, so it is machine-generated
rather than hand-listed, and it is now a test.**

### What I searched for

Every `*.kt` under any `src/test` or `src/testFixtures`, with `//` tails, block comments, raw
strings, string literals and char literals removed **first**, then matched against:

`KotlinSource.CLOCK_READINGS`, quoted from the file:

```kotlin
        val CLOCK_READINGS = listOf(
            "System.nanoTime",
            "System.currentTimeMillis",
            "TimeSource.",
            "elapsedNow",
            "measureTime",
            "measureNanoTime",
            "measureTimeMillis",
            "Instant.now",
            "LocalDateTime.now",
            "LocalTime.now",
            "Clock.system",
        )
```

Deliberately generous: over-reporting costs a census row with a reason on it, under-reporting costs
a budget nobody notices is inside `build`. The stripping is what stops it over-reporting on prose —
`DeterminismScannerTest` holds `System.nanoTime()` in Java fixture text, `NoWallClockInTransportTest`
holds it in a planted string, `NonLiteralIdTest` holds `System.currentTimeMillis()` inside an asset
script it compiles, `GasArchitectureTest` and `FileValidatorTest` hold them in banned-word lists.
All five fall out correctly and none of them needed a census row.

### What it found: 29 files read a clock; eleven assert an elapsed value

**Already on the aggregate before this ticket (8):**

| File | Task |
|---|---|
| `SnapshotBudgetTest` | `:udea-core:udeaSnapshotBudget` |
| `TickLoopBudgetTest` | `:udea-core:udeaBenchTickLoop` |
| `CharacterMoverBudgetTest` | `:udea-core:udeaBenchCharacterMover` |
| `DaemonLatencyBudgetTest` | `:udea-assets-compiler:udeaDaemonBudget` |
| `GraphBudgetTest` | `:udea-assets-compiler:udeaGraphBudget` |
| `DigestBudgetTest` | `:udea-agent:udeaDigestBudget` |
| `EntityQueryBudgetTest` | `:udea-agent:udeaQueryBudget` |
| `Phase2ExitTest` | `:udea-agent-host:udeaPhase2Exit` |

**Named by issue #182 (2):** `MobaWarmEditBudgetTest:140`, `ExampleScanTest:180`.

**Found by this ticket, named by nobody (3):**

| File:line | Assertion | Ran inside |
|---|---|---|
| `AssetCompilerTest.kt:162` | `assertTrue(warmElapsed.inWholeMilliseconds < 1000, …)` | `:udea-assets-compiler:test` |
| `PhysicsRebuildTest.kt:250` | `assertTrue(medianNanos < 2_000_000L, …)` | `:udea-core:test` |
| `NetHarnessTest.kt:72` | `assertTrue(elapsedMillis < 2_000, …)` | `:udea-net:test` |

`PhysicsRebuildTest` is the one that matters. Its **worst** sample was over its own budget in both
of my full aggregate runs — `worst 2973us` at load 12.05 and `worst 2482us` at load 7.06, against
a 2 000 us line. The median-of-21 estimator is all that stood between it and a red build, inside
every parallel `./gradlew build`, and it had never been listed as a latency budget by anyone. That
is the same "never failed, which was luck" the issue says about its own two.

### The eighteen that read a clock and are not budgets

Recorded in `WallClockBudgetCensusTest.NOT_A_BUDGET` with a reason each, and machine-checked in two
directions — the row must still name a clock-reading file, and that file must not assert an elapsed
value.

| Disposition | Files |
|---|---|
| **a deadline** — bounds how long a poll or socket read waits; missing it means the event never arrived | `MobaUdpTwoProcessTest`, `LiveInstance`, `OverlayCaptureIsolationTest`, `NetSessionEndToEndTest`, `HeadlessHostTest`, `UdpProofClient`, `UdpProofServer`, `UdpTwoProcessTest`, `CaptureOrderingTest`, `GlOverlayIsolationTest`, `OffscreenBackendTest` |
| **a seed** — `Random(System.nanoTime())`, so a proof does not repeat one run | `MobaReplayProofTest`, `ReplayEngineTest`, `ReplayToolTest` |
| **printed, not asserted** | `AssetCompilerTest` (after this change), `TranspilerParityTest`, `WorkerTest` |
| **a ratio** — two readings from the same machine divided, so the machine cancels | `NetIdIndexTest` (resolution at 64 000 ids over resolution at 64: an O(1) claim, not a duration) |

### The honest limits of the enumeration

- It sees a clock read **in a test source file**. It does not see one taken in production code and
  returned as a number; `DaemonLatencyBudgetTest` asserts `report.durationMs` and is invisible to
  it. The task-path fence covers that direction, and M4 is the recorded case.
- The disposition text in a census row is a human judgement. What is machine-checked is that the row
  exists, still describes a real file, and that the file does not assert an elapsed value.
- Nested block comments are not handled. Kotlin permits them, this repository does not use them, and
  the failure mode over-reports rather than under-reports — it cannot turn a red fence green.
- The `elapsedNames` walk is textual and one function call breaks the chain, which is why the
  primary fence is the generous token scan and the elapsed-value check is a second, sharper pass
  applied only to censused files.

---

## 7. The issue, criterion by criterion

### ☑ 1. `sh gradlew build` runs no wall-clock latency assertion, on any module. Show it — a task list, not a claim.

**The task list.** `sh gradlew build --dry-run` on `47ea8e8` emits **421** task lines
(`scratchpad/build-dryrun.txt`, `grep -c '^:'`). Grepping that list for every member of the
aggregate returns nothing:

```
$ grep -nE 'udeaSnapshotBudget|udeaBenchTickLoop|udeaBenchCharacterMover|udeaPhysicsRebuildBudget|udeaDaemonBudget|udeaGraphBudget|udeaScanBudget|udeaWarmEditBudget|udeaDigestBudget|udeaQueryBudget|udeaPhase2Exit|udeaLatencyBudgets' build-dryrun.txt
$ echo "grep exit status: $?"
grep exit status: 1
```

No output lines, and `grep`'s exit status 1 is "matched nothing" rather than "could not read the
file", which would be 2.

The negative was run, not assumed.

The control, so the pattern is known to match something: the same twelve names against the green
aggregate run, `grep -oE ... | sort -u`, all twelve present
(`scratchpad/grep-control.txt`):

```
udeaBenchCharacterMover
udeaBenchTickLoop
udeaDaemonBudget
udeaDigestBudget
udeaGraphBudget
udeaLatencyBudgets
udeaPhase2Exit
udeaPhysicsRebuildBudget
udeaQueryBudget
udeaScanBudget
udeaSnapshotBudget
udeaWarmEditBudget
```

The `:*:test` tasks that **are** in `build`'s graph, spliced whole from the same dry-run file
(`grep -E '^:[a-zA-Z:-]+:test SKIPPED$'`, saved as `scratchpad/build-dryrun-test-tasks.txt`):

```
:common:test SKIPPED
:example:test SKIPPED
:gradle-plugin:test SKIPPED
:moba:test SKIPPED
:udea-agent:test SKIPPED
:udea-agent-host:test SKIPPED
:udea-annotations:test SKIPPED
:udea-assets:test SKIPPED
:udea-assets-compiler:test SKIPPED
:udea-audio:test SKIPPED
:udea-codegen:test SKIPPED
:udea-compiler-plugin:test SKIPPED
:udea-core:test SKIPPED
:udea-diagnostics:test SKIPPED
:udea-gas:test SKIPPED
:udea-gradle:test SKIPPED
:udea-net:test SKIPPED
:udea-render:test SKIPPED
:udea-replay:test SKIPPED
:example:assets:test SKIPPED
```

(`SKIPPED` is what `--dry-run` prints for every task in the graph; these are the tasks `build`
would run.)

That those twenty run **no** wall-clock assertion is section 6's enumeration plus the census fence,
which is a test rather than a claim, plus `LatencyBudget.measuredBy`, which fails the build if one
of them executes a budget class after all (M4).

### ☑ 2. Whatever moves is shown going red on a deliberate slowdown, per gate.

Three gates moved; M1, M2 and M3 in section 2, each with the literal diff of the mutation, the
command, the exit status, the measured number and the arithmetic check. M4 additionally shows the
guard that keeps them moved.

The two that were **dropped** rather than moved have no slowdown to show, by construction — that is
what dropping means. What replaces each is named in section 3 and in `docs/budgets.md`, and the
`Thread.sleep` ban that replaces `NetHarnessTest`'s bound has both a planted-violation case and a
comment-blindness control in `NoWallClockInTransportTest.theScanFindsAPlantedViolation`.

### ☑ 3. `udeaLatencyBudgets`' description is true as written, and a test enforces it if that is cheap.

The description is unchanged and now true:

> `"Measures every wall-clock latency budget. Run it with --no-parallel --max-workers=1 and nothing else on the machine, or it measures the machine."`

Enforced by `WallClockBudgetCensusTest` (section 3) and `LatencyBudget.measuredBy` (three states,
`LatencyBudgetTest`). Both were watched failing before they were watched passing:
the census fence's first run on the unmodified tree named all four undecided files and the one
lying census row, and that transcript is section 8.

---

## 8. The failing test, first

Written before any production change, run against the tree at `293649b` plus the fence itself.
`7 tests completed, 4 failed`, and between them the four name the whole ticket
(`scratchpad/red/census-red-messages.txt`, extracted from
`scratchpad/red/census-red.xml`):

```
### every wall-clock reading in a test source is a budget or a censused non-budget()
org.opentest4j.AssertionFailedError: these test sources read a wall clock and are neither a declared latency budget nor a row in this file's census:
  udea-net/src/test/kotlin/dev/wildware/udea/net/transport/NetHarnessTest.kt reads System.nanoTime
  udea-assets-compiler/src/test/kotlin/dev/wildware/udea/assets/compiler/daemon/MobaWarmEditBudgetTest.kt reads System.nanoTime
  udea-assets-compiler/src/test/kotlin/dev/wildware/udea/assets/compiler/scan/ExampleScanTest.kt reads TimeSource., elapsedNow
  udea-core/src/test/kotlin/dev/wildware/udea/core/physics/PhysicsRebuildTest.kt reads System.nanoTime
```

```
### no censused file asserts an elapsed wall-clock time()
org.opentest4j.AssertionFailedError: these files are censused as not being latency budgets, and they assert a value derived from a wall-clock reading anyway:
  udea-assets-compiler/src/test/kotlin/dev/wildware/udea/assets/compiler/AssetCompilerTest.kt
      line 162: assertTrue(warmElapsed.inWholeMilliseconds < 1000, )
The census row is wrong. Either the assertion is a latency budget and belongs on `udeaLatencyBudgets`, or it is not about time and should not be comparing a stopwatch reading.
```

That second one is the eleventh gate, found by the fence rather than by me — I had written
`AssetCompilerTest`'s census row as *"printed, not asserted"* on the strength of its KDoc, which
says exactly that about the **cold** number, and the fence refused it.

The other two failures were `every declared budget names a task the aggregate measures` (no existing
budget yet called `measuredBy`, and it named each one) and `the root build script tells every test
task which task it is` (the property did not yet exist).

---

## 9. Regenerated files

**None.** No replicated component was added or removed, so `udea-codegen/net-protocol.lock` and
`udea-codegen/src/test/resources/expected-generated-hashes.txt` are byte-identical to
`origin/example`; `git diff origin/example..HEAD --stat` lists neither, and `udeaCheckProtocolLock`
is green inside the `build` above. No id moved.

`docs/contracts/` is likewise untouched and `udeaVerifyContracts` is green.

---

## 10. Files changed

31 files, +1350 −276, against `origin/example` at `293649b`.

**Build and CI**
- `build.gradle.kts` — three new members of `latencyBudgetTasks`; `udea.testTaskPath` on every
  subproject `Test` task; the note on why the list is no longer the enumeration.
- `udea-core/build.gradle.kts` — `udeaPhysicsRebuildBudget`, and the exclusion that keeps it out of
  `test`.
- `udea-assets-compiler/build.gradle.kts` — `udeaWarmEditBudget`, `udeaScanBudget`, and
  `budgetTestClasses` replacing the single-class exclusion.
- `udea-gradle/build.gradle.kts` — every repository test source declared as an input of
  `:udea-gradle:test`, so the census cannot go `UP-TO-DATE` over a tree that has changed.
- `.github/workflows/ci.yml` — header comment only; behaviour unchanged.

**The fences**
- `udea-diagnostics/src/testFixtures/.../LatencyBudget.kt` — `TEST_TASK_PROPERTY`, `measuredBy`.
- `udea-diagnostics/src/test/.../LatencyBudgetTest.kt` — its three states.
- `udea-gradle/src/test/.../ci/WallClockBudgetCensusTest.kt` — new.
- `udea-gradle/src/test/.../ci/KotlinSource.kt` — new; the stripper and the elapsed-value walk.
- `udea-gradle/src/test/.../ci/LatencyBudgetAggregate.kt` — new; the single reader of
  `latencyBudgetTasks`, extracted from `LatencyBudgetJobTest` now that there are two readers.
- `udea-gradle/src/test/.../ci/LatencyBudgetJobTest.kt` — uses it; no assertion changed.

**The moves**
- `.../daemon/MobaWarmEdit.kt` (new, shared harness), `MobaWarmEditTest.kt` (new, the correctness
  half, on `check`), `MobaWarmEditBudgetTest.kt` (rewritten, the stopwatch half).
- `.../scan/WarmScanBudgetTest.kt` (new), `ExampleScanTest.kt` (method removed, KDoc says where it
  went).
- `.../physics/PhysicsRebuildBudgetTest.kt` (new), `PhysicsRebuildFixture.kt` (new, the world both
  classes build), `PhysicsRebuildTest.kt` (method removed, `Fixture` renamed at its 10 call sites).

**The drops**
- `.../AssetCompilerTest.kt` — timing assertion removed, method renamed, KDoc says why.
- `.../transport/NetHarnessTest.kt` — timing removed, method renamed, KDoc says where the property
  is asserted now.
- `.../transport/NoWallClockInTransportTest.kt` — `Thread.sleep` banned, with its control.

**The `measuredBy` call**, one line each, in every budget class that already existed:
`SnapshotBudgetTest`, `TickLoopBudgetTest`, `CharacterMoverBudgetTest`, `DaemonLatencyBudgetTest`,
`GraphBudgetTest`, `DigestBudgetTest`, `EntityQueryBudgetTest`, `Phase2ExitTest`.

**Documentation**
- `docs/budgets.md` — the three new rows with measured numbers and headroom, the census gate, the
  two drops, and the paragraph on why the list is now checked rather than stated.

---

## 11. Where the artefacts are

Under `/tmp/claude-1000/-srv-ssd1-workspace-Udea/01ec1be7-305f-4987-ab53-69f61b72d43e/scratchpad/`:

| File | What it is |
|---|---|
| `red/census-red.xml`, `red/census-red-messages.txt` | the fence's first run, red, before any production change |
| `evidence-green-47ea8e8.txt` | the evidence command, green, on the committed SHA |
| `evidence-red.txt` | the evidence command, red, under M1 |
| `mut-physics.diff` / `.log` | M1 |
| `mut-scan.diff` / `.log` | M2 |
| `mut-warmedit.diff` / `.log` | M3 |
| `mut-exclusion.diff` / `.log` / `mut-exclusion-report.xml` | M4 |
| `mut-planted.diff` / `.log` / `mut-planted-report.xml` | M5 |
| `mut-measuredby.diff` / `.log` | M6 |
| `mut-staleRow.diff` / `.log` | M7 |
| `mut-vacuity.diff` / `.log` / `mut-vacuity-report.xml` | M8 |
| `mut-control-comment.diff`, `control-comment.log` | M5-control |
| `grep-control.txt` | the twelve aggregate names, present in the green run |
| `q-dryrun-negative.txt` | criterion 1's negative grep and its exit status |
| `build-dryrun-test-tasks.txt` | the twenty `:*:test` tasks in `build`'s graph |
| `build-dryrun.txt` | the 421-task graph of `build` |
| `build-clean-full.txt`, `build-clean-load.txt`, `q-test-count.txt` | `clean build`, green, with the loadavg either side and the test count |
| `count-tests.py` | what produced that count |
| `verify-brief.py`, `verify-diffs.py` | what checks this document against all of the above |

Every block quoted in this brief was spliced from one of those files, from a source file in the
worktree, or from a JUnit XML report named at the point of quotation. Nothing has been rewrapped or
reordered, every elision is marked, and each segment between markers is a consecutive in-order run
of its source.

That is checked rather than claimed. `verify-brief.py` splits every fenced block on its own elision
markers and requires each resulting segment to appear as a **consecutive, in-order run** in one of
the artefacts; `verify-diffs.py` does the same for the nine `diff` blocks against the saved
`git diff` output of each mutation. Their final runs:

```
9 diff blocks in the brief, 10 saved .diff artefacts
every diff block appears verbatim in a saved .diff artefact
checked 34 spliced segments against 2036 artefacts

2 SEGMENT(S) NOT FOUND AS A CONSECUTIVE RUN IN ANY ARTEFACT:

----------------------------------------------------------------------
sh gradlew udeaLatencyBudgets --no-parallel --max-workers=1
----------------------------------------------------------------------
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew clean build --console=plain
```

The two it will not clear are the two commands a reader is meant to run, which have no transcript
by definition. Everything else in this document clears.

It earned itself: it caught two Gradle console orderings I had transposed while reading them out of
a `grep` rather than out of the file, a `232 actionable tasks` line from a superseded build, and the
one measurement whose JUnit report `clean build` had deleted underneath me — the last is now marked
as prose in section 3, with the search that established it is gone.
