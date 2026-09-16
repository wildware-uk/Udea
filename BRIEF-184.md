24d8991
# BRIEF-184 — the warm edit gate keeps its 3 s promise and fails at 1.5 s

Branch `issue-184-warm-edit-budget`, off `origin/example` at `f08db40`.
Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3ca34cdec2180138`.

`24d8991` is the last commit of the change. The commit after it adds only this file.
Evidence artefacts are all under `/srv/ssd1/workspace/Udea/build/issue184-evidence/` (called `EV/` below).

---

## 1. The evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew :udea-assets-compiler:udeaWarmEditBudget --rerun --no-parallel --max-workers=1
```

**It goes red on a real slowdown, and the old gate does not.** The slowdown is M2 (diff in section 3):
`AssetDaemon.reload` recompiles every script instead of the edited one, and `AssetCompiler` loses its
compiled-script jar cache. Taken together, that is a daemon with no warm path left.

With M2 applied, **the gate as it is on `origin/example` (3000 ms): green in 3 runs out of 3.** The first line of each log
is `/proc/loadavg` before the run.

```
mut-M2-on-origin-gate.log:1:7.13 5.74 4.37 6/1962 2379162
mut-M2-on-origin-gate.log:47:    moba warm edit -> observed: max 2649ms, median 2424ms, min 2209ms over 5 samples [2209, 2272, 2424, 2428, 2649] (budget 3000ms, corpus 161 assets)
mut-M2-on-origin-gate.log:49:BUILD SUCCESSFUL in 30s
mut-M2-on-origin-gate-2.log:1:6.70 6.01 4.65 3/1903 2388250
mut-M2-on-origin-gate-2.log:47:    moba warm edit -> observed: max 2948ms, median 2582ms, min 2513ms over 5 samples [2948, 2652, 2582, 2513, 2525] (budget 3000ms, corpus 161 assets)
mut-M2-on-origin-gate-2.log:49:BUILD SUCCESSFUL in 27s
mut-M2-on-origin-gate-3.log:1:6.55 6.04 4.71 5/1906 2390094
mut-M2-on-origin-gate-3.log:47:    moba warm edit -> observed: max 2976ms, median 2761ms, min 2525ms over 5 samples [2976, 2761, 2582, 2921, 2525] (budget 3000ms, corpus 161 assets)
mut-M2-on-origin-gate-3.log:49:BUILD SUCCESSFUL in 28s
```

With M2 applied, **this branch's gate (1500 ms): red in 4 runs out of 4.**
- The first run was against the first, uncommitted draft of the test, before its KDoc and method name were edited. The assertion is the same one.
- The next two ran at `043d860`.
- The last ran at `894cbfb`, which differs from `24d8991` only in two lines of `docs/budgets.md`.

`git diff --stat 043d860 24d8991` touches only `docs/budgets.md` and two comment lines of the test. The load was
5.09 when the last run started and 16.55 when it ended.

```
tdd-red-M2-new-gate.log:1:5.32 5.64 4.47 3/1899 2385577
tdd-red-M2-new-gate.log:47:    moba warm edit -> observed: max 3080ms, median 2628ms, min 2537ms over 5 samples [3080, 2628, 2785, 2579, 2537] (threshold 1500ms, deadline 3000ms, corpus 161 assets)
tdd-red-M2-new-gate.log:49:MobaWarmEditBudgetTest > a warm edit of the real moba corpus is observed in under three seconds() FAILED
tdd-red-M2-new-gate.log:54:> Task :udea-assets-compiler:udeaWarmEditBudget FAILED
tdd-red-M2-new-gate.log:65:BUILD FAILED in 27s
red-M2-043d860-1.log:1:4.40 6.32 5.32 3/2019 2419397
red-M2-043d860-1.log:47:    moba warm edit -> observed: max 2980ms, median 2531ms, min 2035ms over 5 samples [2980, 2747, 2531, 2387, 2035] (threshold 1500ms, deadline 3000ms, corpus 161 assets)
red-M2-043d860-1.log:49:MobaWarmEditBudgetTest > a warm edit of the real moba corpus is observed well inside the three-second deadline() FAILED
red-M2-043d860-1.log:54:> Task :udea-assets-compiler:udeaWarmEditBudget FAILED
red-M2-043d860-1.log:65:BUILD FAILED in 28s
red-M2-043d860-2.log:1:5.41 6.41 5.38 4/2021 2420498
red-M2-043d860-2.log:47:    moba warm edit -> observed: max 2926ms, median 2664ms, min 2494ms over 5 samples [2926, 2849, 2637, 2664, 2494] (threshold 1500ms, deadline 3000ms, corpus 161 assets)
red-M2-043d860-2.log:49:MobaWarmEditBudgetTest > a warm edit of the real moba corpus is observed well inside the three-second deadline() FAILED
red-M2-043d860-2.log:54:> Task :udea-assets-compiler:udeaWarmEditBudget FAILED
red-M2-043d860-2.log:65:BUILD FAILED in 27s
red-M2-894cbfb.log:1:5.09 9.76 8.02 5/2082 2452338
red-M2-894cbfb.log:46:    moba warm edit -> observed: max 5389ms, median 4113ms, min 3317ms over 5 samples [4113, 3822, 3317, 5376, 5389] (threshold 1500ms, deadline 3000ms, corpus 161 assets)
red-M2-894cbfb.log:48:MobaWarmEditBudgetTest > a warm edit of the real moba corpus is observed well inside the three-second deadline() FAILED
red-M2-894cbfb.log:53:> Task :udea-assets-compiler:udeaWarmEditBudget FAILED
red-M2-894cbfb.log:64:BUILD FAILED in 42s
red-M2-894cbfb.log:67:16.55 11.93 8.81 36/3495 2458742
```

The failure message, from `EV/tdd-red-M2-new-gate.xml`, starts:

```
<failure message="org.opentest4j.AssertionFailedError: a warm edit of moba's corpus is gated at 1500ms to catch a regression long before it reaches spec 6's 3000ms deadline; the slowest of [3080, 2628, 2785, 2579, 2537] missed it, and missed the deadline too. This is a wall-clock latency measurement, and a wall-clock measurement [...]
```

(That run's slowest sample was over 3000 ms, so it shows the "missed the deadline too" branch, as does the `894cbfb`
run. The two `043d860` runs were under 3000 ms and take the other branch.)

**Reverting the feature** means setting the threshold back to 3000 ms, which is exactly the
`origin/example` gate. Under M2 that is the green block above.

## 2. Summary

**The ruling: both.** 3000 ms is the product contract, and it is a poor regression detector. So the
gate now fails at a separate, lower regression threshold.

- `CONTRACT_MS = 3_000`: spec 6's Phase 2 exit criterion, *asset edit-to-observe <3s* (spec line 351).
  It is not moved. It is the promise to a person editing assets.
- `REGRESSION_MS = 1_500`: what `udeaWarmEditBudget` fails at. Still `samples.max()`, as the lead required.
- **No second assertion at 3000 ms.** 1500 < 3000, so an assertion at 3000 could only fail after the one at
  1500 had already failed. That is the section 8 "test that cannot fail". The failure message says
  instead whether a red run also missed the deadline.
- No budget was widened.

**Why 1500.** It has to sit above the slowest honest edit on every runner that runs the gate, and below
the regression.
- *Above:* every completed `latency budgets` CI job on `example` since #182 (`784f614`), read from the job logs
  saved in `EV/ci/`. Slowest sample per run: `ubuntu-latest` 219–330 ms (8 runs), `windows-latest`
  277–674 ms (6 runs). Two more Windows jobs (`99716670860`, `104795789091`) stopped at an earlier failing budget
  and never reached this one. 1500 is 2.2x the worst, 674.
- *Below:* the fastest single M2 sample in any of its seven runs was 2035 ms, so 1500 is 1.36x below it. M2 has not been measured on a CI runner.

**Box load while measuring** (24 processors; other Udea developers, melon-merge and composegl were building):
- M2 runs at load 4.40–7.13.
- Unmutated solo runs (not beside a build of mine) at load 6.05–16.55.
- Unmutated runs *beside my own full parallel `build`* at load 12.73–25.06. That is harsher than the gate's intended
  conditions, and I ran it on purpose to look for flakes.

**Decided and recorded** on the issue: https://github.com/wildware-uk/Udea/issues/184#issuecomment-5699493268.
The comment gives the decision, the two rejected alternatives, and what to change if the owner disagrees. I edited it twice:
to correct the fastest regression sample from 2209 to 2035 ms after two more runs, and to
state the load range exactly (6.55–7.13, where it had rounded to 6.5–7.2).

**Rejected alternatives.**
- Keeping 3000 ms as the only line: M2 goes green.
- Two assertions: one of them could not fail.
- Changing spec 6's number: that is the owner's product claim, not a threshold.

**A surprise, not fixed here (possible follow-up ticket).** M1 alone, a non-incremental reload with the jar cache still working, was no slower.
In one run on the old gate its slowest sample was 266 ms, against 322 ms for the unmutated gate a few minutes earlier (322 ms is from `EV/baseline-0.log`,
whose load line was overwritten by the run's own output, so no load is cited for it).
So no stopwatch on this gate can see reload losing incrementality at this corpus size. `AssetDaemon`'s KDoc says compiling
one script is "a fraction of the cost of compiling nineteen even against a hot jar cache". This measurement
does not show that. I found no count-based assertion that `reload` compiles only the touched files:
`DaemonLatencyBudgetTest` asserts `report.recompiled > 0` for `validate`, and nothing reads a count off `reload`.

**Files changed:**
- `MobaWarmEditBudgetTest.kt`: two constants, the assertion and message, a KDoc section, and a method rename,
  because "in under three seconds" no longer names what it fails at.
- `udea-assets-compiler/build.gradle.kts`: the task description.
- `docs/budgets.md`: the warm-edit row in the latency table, a new section "The warm edit has a deadline and a
  threshold (issue #184)", and the stale "Asset edit-to-observe" placeholder row. That row said Phase 3, "(assets epic)" and
  "not yet measured", but the gate exists and the spec puts it in Phase 2. It is the same budget, so I counted it as
  part of the warm-edit scope. I did not touch the clean-build row, which #181 owns.

```diff
diff --git a/udea-assets-compiler/build.gradle.kts b/udea-assets-compiler/build.gradle.kts
index 7148e7a..6df3e24 100644
--- a/udea-assets-compiler/build.gradle.kts
+++ b/udea-assets-compiler/build.gradle.kts
@@ -155,7 +155,7 @@ tasks.register<Test>("udeaDaemonBudget") {
 
 tasks.register<Test>("udeaWarmEditBudget") {
     group = "verification"
-    description = "Gates spec 6 Phase 2: an edit of moba's real corpus is observed under 3s."
+    description = "Gates an edit of moba's real corpus at 1.5s, inside spec 6 Phase 2's 3s deadline."
     testClassesDirs = sourceSets.test.get().output.classesDirs
     classpath = sourceSets.test.get().runtimeClasspath
     filter.includeTestsMatching("dev.wildware.udea.assets.compiler.daemon.MobaWarmEditBudgetTest")
diff --git a/udea-assets-compiler/src/test/kotlin/dev/wildware/udea/assets/compiler/daemon/MobaWarmEditBudgetTest.kt b/udea-assets-compiler/src/test/kotlin/dev/wildware/udea/assets/compiler/daemon/MobaWarmEditBudgetTest.kt
index ebdac82..c84ae21 100644
--- a/udea-assets-compiler/src/test/kotlin/dev/wildware/udea/assets/compiler/daemon/MobaWarmEditBudgetTest.kt
+++ b/udea-assets-compiler/src/test/kotlin/dev/wildware/udea/assets/compiler/daemon/MobaWarmEditBudgetTest.kt
@@ -40,7 +40,7 @@ import kotlin.test.assertTrue
  *
  * Six edits are made, the first is discarded as the warm-up (it pays for classloading the
  * scripting host - about two seconds on this machine, which is start-up and not the editing loop),
- * and the **maximum** of the rest is what the budget is asserted against.
+ * and the **maximum** of the rest is what the threshold is asserted against.
  *
  * Issue #182 asked whether that should become a median, because #175 made exactly that change to
  * `DaemonLatencyBudgetTest`'s reload gate - where the maximum of five was "the worst scheduling
@@ -58,18 +58,41 @@ import kotlin.test.assertTrue
  * A median would also have made the gate strictly easier to pass, and this repository does not buy
  * that without a demonstration that it still catches the regression it is for.
  *
+ * ## Two numbers, one assertion (issue #184)
+ *
+ * [CONTRACT_MS] is spec 6's promise to a person editing assets, and it is not this file's to move.
+ * [REGRESSION_MS] is what the gate fails at. They were one constant until #184, and the constant
+ * did the second job badly: measured alone, a warm edit's slowest sample is a few hundred
+ * milliseconds, so three seconds let through a regression of more than tenfold.
+ *
+ * That is not hypothetical. Making every reload recompile the whole corpus with the compiled-script
+ * jar cache switched off - a daemon that has lost both of the things that make the warm path warm -
+ * measured a slowest sample of 2 649, 2 948 and 2 976ms in three solo runs on this repository's
+ * 24-processor box at one-minute load averages of 6.55 to 7.13, and all three were green against
+ * three seconds.
+ *
+ * There is deliberately no second assertion at [CONTRACT_MS]. [REGRESSION_MS] is below it, so any
+ * run that passes the threshold has passed the deadline, and an assertion that can only fail when
+ * the one before it already has is an assertion that cannot fail. The failure message says which
+ * of the two a red run missed. That reasoning holds only while the threshold is the lower number,
+ * which is why the remedy below rules out raising it to the deadline or past it.
+ *
+ * The threshold is 1 500ms because it has to sit between two measurements, both in
+ * `docs/budgets.md`: the slowest warm edit CI has recorded on either runner image, and the fastest
+ * sample of the regression above on this repository's own box.
+ *
  * ## Where it is measured
  *
  * On `udeaLatencyBudgets`, through `:udea-assets-compiler:udeaWarmEditBudget`, and no longer inside
  * `check` (issue #182). [LatencyBudget.measuredBy] refuses to let it run anywhere else.
  *
  * If it fails, the remedy is the daemon's incremental scope - re-walk less of the graph - never a
- * wider budget.
+ * wider threshold, and never a threshold at or above the deadline.
  */
 class MobaWarmEditBudgetTest {
 
     @Test
-    fun `a warm edit of the real moba corpus is observed in under three seconds`() {
+    fun `a warm edit of the real moba corpus is observed well inside the three-second deadline`() {
         LatencyBudget.measuredBy(TASK)
 
         val harness = MobaWarmEdit("moba-warm-edit")
@@ -85,16 +108,21 @@ class MobaWarmEditBudgetTest {
             if (iteration > 0) samples += elapsedMs
         }
 
+        val slowest = samples.max()
         println(
-            "moba warm edit -> observed: max ${samples.max()}ms, " +
+            "moba warm edit -> observed: max ${slowest}ms, " +
                 "median ${samples.sorted()[samples.size / 2]}ms, min ${samples.min()}ms over " +
                 "${samples.size} samples $samples " +
-                "(budget ${BUDGET_MS}ms, corpus ${harness.assetCount} assets)",
+                "(threshold ${REGRESSION_MS}ms, deadline ${CONTRACT_MS}ms, " +
+                "corpus ${harness.assetCount} assets)",
         )
         assertTrue(
-            samples.max() <= BUDGET_MS,
-            "spec 6 Phase 2 gates an asset edit at ${BUDGET_MS}ms; the slowest of $samples " +
-                "missed it. " + LatencyBudget.contentionNote(TASK),
+            slowest <= REGRESSION_MS,
+            "a warm edit of moba's corpus is gated at ${REGRESSION_MS}ms to catch a regression " +
+                "long before it reaches spec 6's ${CONTRACT_MS}ms deadline; the slowest of " +
+                "$samples missed it" +
+                (if (slowest > CONTRACT_MS) ", and missed the deadline too. " else ". ") +
+                LatencyBudget.contentionNote(TASK),
         )
     }
 
@@ -103,8 +131,14 @@ class MobaWarmEditBudgetTest {
         /** The task that measures this, and the one to re-run alone before believing a red. */
         const val TASK = ":udea-assets-compiler:udeaWarmEditBudget"
 
-        /** Spec 6 Phase 2: an asset edit is observed in under three seconds. */
-        const val BUDGET_MS = 3_000L
+        /** Spec 6 Phase 2: an asset edit is observed in under three seconds. A product promise. */
+        const val CONTRACT_MS = 3_000L
+
+        /**
+         * What this gate fails at: a regression threshold inside [CONTRACT_MS] (issue #184).
+         * The class KDoc says where the number comes from.
+         */
+        const val REGRESSION_MS = 1_500L
 
         /** Six edits, five counted. */
         const val ITERATIONS = 6
```

## 3. Mutations, with their literal diffs

Both diffs are saved from `git diff -- udea-assets-compiler` at the time of the run, and printed here from those files.

| | Diff file | Old gate (3000 ms) | This gate (1500 ms) |
|---|---|---|---|
| M1 | `EV/mut-M1-nonincremental.diff` | green, slowest 266 ms (1 run) | not run. At 266 ms against the old gate there is no reason to expect it near 1500. That is the surprise above |
| M2 | `EV/mut-M2-nonincremental-nocache.diff` | green 3/3, slowest 2649 / 2948 / 2976 ms | **red 4/4**, slowest 3080 / 2980 / 2926 / 5389 ms |

M1:

```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt
index 9739334..e6f49a4 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt
@@ -170,13 +170,13 @@ public class AssetDaemon(
         val touched = changed.map { it.toAbsolutePath().normalize() }.distinct()
         if (touched.isEmpty()) return ReloadOutcome.NoChange(millisSince(began))
 
-        val live = touched.filter { it.isScript() }
+        val live = (touched + scripts()).distinct().filter { it.isScript() }
 
         // The candidate graph: everything the daemon holds, with the touched files' contribution
         // replaced by what they declare now. A deleted file contributes nothing, which is how a
         // deletion becomes an `asset_removed` shape change instead of a stale entry nobody notices.
         val candidateByFile = LinkedHashMap(byFile)
-        touched.forEach { candidateByFile.remove(it) }
+        live.forEach { candidateByFile.remove(it) }
         val compileDiagnostics = live.flatMap { compileInto(candidateByFile, it) }
 
         val candidate = AssetGraph.of(candidateByFile.values.flatten())
```

```
mut-M1-on-origin-gate.log:1:8.22 5.72 4.31 3/1903 2377165
mut-M1-on-origin-gate.log:47:    moba warm edit -> observed: max 266ms, median 252ms, min 222ms over 5 samples [229, 266, 252, 265, 222] (budget 3000ms, corpus 161 assets)
mut-M1-on-origin-gate.log:49:BUILD SUCCESSFUL in 18s
```

M2 (M1 plus the cache removal):

```diff
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/AssetCompiler.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/AssetCompiler.kt
index 4bfa928..9211929 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/AssetCompiler.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/AssetCompiler.kt
@@ -236,15 +236,6 @@ public class AssetCompiler(
         createJvmCompilationConfigurationFromTemplate<UdeaAssetScript> {
             jvm {
                 updateClasspath(scriptClasspath.map { it.toFile() })
-                hostConfiguration(
-                    ScriptingHostConfiguration {
-                        jvm {
-                            compilationCache(
-                                CompiledScriptJarsCache { script, _ -> jarFor(script, file) },
-                            )
-                        }
-                    },
-                )
             }
         }
 
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt
index 9739334..e6f49a4 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt
@@ -170,13 +170,13 @@ public class AssetDaemon(
         val touched = changed.map { it.toAbsolutePath().normalize() }.distinct()
         if (touched.isEmpty()) return ReloadOutcome.NoChange(millisSince(began))
 
-        val live = touched.filter { it.isScript() }
+        val live = (touched + scripts()).distinct().filter { it.isScript() }
 
         // The candidate graph: everything the daemon holds, with the touched files' contribution
         // replaced by what they declare now. A deleted file contributes nothing, which is how a
         // deletion becomes an `asset_removed` shape change instead of a stale entry nobody notices.
         val candidateByFile = LinkedHashMap(byFile)
-        touched.forEach { candidateByFile.remove(it) }
+        live.forEach { candidateByFile.remove(it) }
         val compileDiagnostics = live.flatMap { compileInto(candidateByFile, it) }
 
         val candidate = AssetGraph.of(candidateByFile.values.flatten())
```

Why this is the real shape: `reload` built as `start()` builds, over every script, is what the daemon's own
KDoc ("Incremental") says it avoids. A `BasicJvmScriptingHost` with no `compilationCache` is the scripting
host's default. Every mutation was reverted with `git checkout HEAD -- udea-assets-compiler/src/main`, and
`git status --short` after it shows only ` M gradlew` (the executable bit, never staged).

## 4. It does not flake: unmutated runs, load noted

```
green-6.log:1:10.32 7.54 5.53 5/2133 2415058
green-6.log:47:    moba warm edit -> observed: max 186ms, median 169ms, min 147ms over 5 samples [169, 156, 147, 186, 169] (threshold 1500ms, deadline 3000ms, corpus 161 assets)
beside-build-2.log:1:18.63 9.95 6.70 13/2573 2429331
beside-build-2.log:47:    moba warm edit -> observed: max 274ms, median 217ms, min 203ms over 5 samples [207, 246, 217, 203, 274] (threshold 1500ms, deadline 3000ms, corpus 161 assets)
beside-build-4.log:1:25.06 13.22 8.04 9/2805 2435679
beside-build-4.log:47:    moba warm edit -> observed: max 230ms, median 223ms, min 192ms over 5 samples [230, 216, 223, 192, 224] (threshold 1500ms, deadline 3000ms, corpus 161 assets)
beside-build-3.log:1:15.27 9.75 6.71 13/2638 2430625
beside-build-3.log:47:    moba warm edit -> observed: max 627ms, median 534ms, min 476ms over 5 samples [476, 627, 534, 619, 499] (threshold 1500ms, deadline 3000ms, corpus 161 assets)
green-2.log:1:6.64 6.14 4.81 4/1931 2394606
green-2.log:47:    moba warm edit -> observed: max 233ms, median 201ms, min 159ms over 5 samples [180, 233, 214, 201, 159] (threshold 1500ms, deadline 3000ms, corpus 161 assets)
green-5.log:1:9.99 7.35 5.44 6/2139 2414198
green-5.log:47:    moba warm edit -> observed: max 248ms, median 197ms, min 183ms over 5 samples [183, 248, 211, 186, 197] (threshold 1500ms, deadline 3000ms, corpus 161 assets)
green-3.log:1:6.05 6.04 4.80 3/1929 2395858
green-3.log:47:    moba warm edit -> observed: max 199ms, median 174ms, min 156ms over 5 samples [199, 173, 191, 156, 174] (threshold 1500ms, deadline 3000ms, corpus 161 assets)
green-4.log:1:10.00 7.16 5.34 5/2112 2410234
green-4.log:70:    moba warm edit -> observed: max 247ms, median 198ms, min 171ms over 5 samples [241, 198, 247, 176, 171] (threshold 1500ms, deadline 3000ms, corpus 161 assets)
beside-build-1.log:1:12.73 7.74 5.88 49/2989 2425760
beside-build-1.log:48:    moba warm edit -> observed: max 239ms, median 220ms, min 200ms over 5 samples [231, 220, 239, 200, 214] (threshold 1500ms, deadline 3000ms, corpus 161 assets)
green-1.log:1:6.32 6.06 4.77 3/1940 2393222
green-1.log:47:    moba warm edit -> observed: max 172ms, median 159ms, min 151ms over 5 samples [151, 162, 159, 172, 155] (threshold 1500ms, deadline 3000ms, corpus 161 assets)
green-894cbfb.log:1:16.55 11.93 8.81 43/3493 2458761
green-894cbfb.log:47:    moba warm edit -> observed: max 231ms, median 227ms, min 200ms over 5 samples [227, 228, 211, 231, 200] (threshold 1500ms, deadline 3000ms, corpus 161 assets)
```

Every one of those runs also printed `BUILD SUCCESSFUL` (`grep -n "BUILD" EV/green-*.log EV/beside-build-*.log`).
The slowest sample in that block is 627 ms, at load 15.27 beside a full build, which is 2.4x inside the threshold.

And the CI job's own command, `udeaLatencyBudgets --rerun-tasks --no-parallel --max-workers=1`, at `086cd09`. That commit differs
from `24d8991` only in docs and comment lines. Every budget in the aggregate passed:

```
latency-budgets-086cd09.log:1:21.28 13.59 8.36 17/2862 2437692
latency-budgets-086cd09.log:189:    moba warm edit -> observed: max 213ms, median 182ms, min 163ms over 5 samples [213, 182, 163, 189, 166] (threshold 1500ms, deadline 3000ms, corpus 161 assets)
latency-budgets-086cd09.log:232:BUILD SUCCESSFUL in 1m 26s
```

## 5. `sh gradlew build`

The full build ran at `086cd09`. `git diff --stat 086cd09 24d8991` shows only `docs/budgets.md` (nothing reads it at build time)
and comment lines in `MobaWarmEditBudgetTest.kt`. `EV/build-086cd09.log`:

```
480:BUILD SUCCESSFUL in 1m 57s
481:213 actionable tasks: 128 executed, 63 from cache, 22 up-to-date
```

Tests, counted from every `TEST-*.xml` in the worktree after that build, excluding my own `udeaWarmEditBudget` runs
(`EV/build-086cd09-test-counts.txt`):

```
{'tests': 2597, 'failures': 0, 'errors': 0, 'skipped': 37}
{'test': 2526, 'udeaVerifyPluginOptional': 5, 'udeaGlTest': 21, 'udeaVerifyHeadless': 3, 'udeaAssetTools': 9, 'udeaGasAllocationBudget': 3, 'udeaAgentGlTest': 8, 'udeaPackGate': 22}
```

Then `build` again, incremental: at `894cbfb` (`EV/build-894cbfb.log`, recompiling the edited test) and at `24d8991` (`EV/build-24d8991.log`):

```
build-894cbfb.log:424:BUILD SUCCESSFUL in 23s
build-894cbfb.log:425:204 actionable tasks: 6 executed, 198 up-to-date
build-24d8991.log:424:BUILD SUCCESSFUL in 3s
build-24d8991.log:425:204 actionable tasks: 2 executed, 202 up-to-date
```

Note that 63 of the full build's tasks came from the build cache, so not every test class was re-executed in
that run. What changed here is a test excluded from `test`, a task description and a document. `udeaGlTest` and `udeaAgentGlTest` ran
without `-Pudea.render.requireGl=true` and no `DISPLAY`. This ticket touches no GL, so I did not do an xvfb run.

`udeaDaemonBudget` is not on `build`. It passed inside the `udeaLatencyBudgets` run in section 4, so it needed no solo re-run.

## 6. Images

None. Nothing in this ticket renders. The evidence is the timing transcripts above.

## 7. The issue, criterion by criterion

| Criterion | Proof |
|---|---|
| A recorded decision on whether 3000 ms is a product contract, a regression threshold, or both, with the measurement behind it | "Both": section 2; `docs/budgets.md` "The warm edit has a deadline and a threshold"; issue comment 5699493268. The measurement: section 1 (M2 green against 3000 ms, 3/3) and the CI table below |
| If a threshold changes, `docs/budgets.md` records the old number, the new one and why | That section opens "used to fail at **3 000 ms**. It now fails at **1 500 ms**", then gives why, why 1500, and what to change if the owner disagrees. The latency table row now reads `1 500 ms (deadline 3 000 ms, see below)` |
| The gate still goes red on a deliberate slowdown, per #175's standard | Section 1: M2 red 4/4 on this branch, the last at `894cbfb`, with the literal diff in section 3 |
| (lead) no widening | The only threshold that moved went down, from 3000 to 1500 |
| (lead) keep `samples.max()` | `val slowest = samples.max()` in the diff |
| (lead) survives the shared box under load, without flakes | Section 4: 11 unmutated runs at load 6.05–25.06, all green, worst 627 ms |

The CI lines behind the table, `grep -H "moba warm edit ->" EV/ci/job-*.log` (saved as `EV/ci-warm-edit-lines.txt`; each line trimmed to its file name and message):

```
job-100734656461-windows.log:    moba warm edit -> observed: max 326ms, median 302ms, min 252ms over 5 samples [326, 281, 302, 252, 315] (budget 3000ms, corpus 147 assets)
job-99715040638-windows.log:    moba warm edit -> observed: max 485ms, median 306ms, min 259ms over 5 samples [306, 310, 485, 276, 259] (budget 3000ms, corpus 147 assets)
job-100747061574-ubuntu.log:    moba warm edit -> observed: max 273ms, median 237ms, min 219ms over 5 samples [241, 273, 219, 234, 237] (budget 3000ms, corpus 161 assets)
job-104642725316-windows.log:    moba warm edit -> observed: max 277ms, median 259ms, min 236ms over 5 samples [236, 259, 277, 266, 252] (budget 3000ms, corpus 161 assets)
job-104642725283-ubuntu.log:    moba warm edit -> observed: max 270ms, median 198ms, min 177ms over 5 samples [270, 193, 177, 240, 198] (budget 3000ms, corpus 161 assets)
job-104795788860-ubuntu.log:    moba warm edit -> observed: max 330ms, median 273ms, min 235ms over 5 samples [284, 273, 330, 244, 235] (budget 3000ms, corpus 161 assets)
job-99715040609-ubuntu.log:    moba warm edit -> observed: max 258ms, median 248ms, min 231ms over 5 samples [258, 248, 231, 251, 244] (budget 3000ms, corpus 147 assets)
job-100747061461-windows.log:    moba warm edit -> observed: max 674ms, median 491ms, min 440ms over 5 samples [485, 498, 440, 674, 491] (budget 3000ms, corpus 161 assets)
job-100734656260-ubuntu.log:    moba warm edit -> observed: max 219ms, median 168ms, min 161ms over 5 samples [219, 167, 161, 171, 168] (budget 3000ms, corpus 147 assets)
job-99969466468-ubuntu.log:    moba warm edit -> observed: max 265ms, median 220ms, min 212ms over 5 samples [265, 219, 220, 212, 256] (budget 3000ms, corpus 147 assets)
job-100728075183-windows.log:    moba warm edit -> observed: max 428ms, median 298ms, min 272ms over 5 samples [284, 327, 428, 298, 272] (budget 3000ms, corpus 147 assets)
job-99969466479-windows.log:    moba warm edit -> observed: max 455ms, median 299ms, min 264ms over 5 samples [264, 305, 264, 299, 455] (budget 3000ms, corpus 147 assets)
job-100728074774-ubuntu.log:    moba warm edit -> observed: max 236ms, median 228ms, min 209ms over 5 samples [236, 217, 232, 209, 228] (budget 3000ms, corpus 147 assets)
job-99716670852-ubuntu.log:    moba warm edit -> observed: max 263ms, median 239ms, min 230ms over 5 samples [263, 239, 233, 242, 230] (budget 3000ms, corpus 147 assets)
```

## 8. Regenerated files

None. No replicated component was touched, and neither `net-protocol.lock` nor `expected-generated-hashes.txt` moved.
