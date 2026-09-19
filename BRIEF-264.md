# BRIEF-264.md — deterministic pathfinding for many ground units

9944efa — the last commit of code and tests. The branch head is the commit that adds this
brief, one on top of it, and carries no code.

## 1. The evidence command

```
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew :udea-nav:jvmTest
```

47 tests across eight classes. It is one command because `commonTest` and `jvmTest` compile into the
same JVM test compilation, so the grid, both planners, the systems, the crowd, the determinism legs
and the agent tools all run in it.

### It goes red when the feature is reverted

Six mutations, each one a shape the module could plausibly have had rather than a line broken at
random. Every diff below is the literal `git diff` from the run that produced the failures beside
it, and every failing-test list is `grep -E "^[A-Za-z].* > .*FAILED$"` over that run's log. The
transcripts are in
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue264/M*.log`
for as long as that scratchpad lives; the diffs are reproducible from the source in one edit each.

#### M1 — Separation off: units are never pushed apart after a step.

It fails at `NavCrowdTest.kt:75`, the in-transit fence, which is measured on every tick: the crowd folds into itself at the squeeze between the two buildings long before it gets anywhere.

```diff
diff --git a/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/NavMoveSystem.kt b/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/NavMoveSystem.kt
index f9a4651..75f42a1 100644
--- a/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/NavMoveSystem.kt
+++ b/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/NavMoveSystem.kt
@@ -620,7 +620,7 @@ public class NavMoveSystem(
          * at sixty ticks a second. It is a constant and not a convergence test on purpose - see the
          * determinism note on this class.
          */
-        public const val SEPARATION_PASSES: Int = 4
+        public const val SEPARATION_PASSES: Int = 0
 
         /**
          * How far apart two units may be and still count as touching, in metres.
```

Fails (1):

- `NavCrowdTest[jvm] > a hundred units ordered across a map with obstacles arrive without overlapping()[jvm] FAILED`

#### M2 — Corner cutting allowed: a diagonal step no longer checks the two cells it passes between.

A unit walks through the diagonal join of two buildings, which is a gap of zero width. Note the second failure: the flow field and A* share the rule, and the test that they agree about cost catches it too.

```diff
diff --git a/BRIEF-264.md b/BRIEF-264.md
index 933ec01..5ac7066 100644
--- a/BRIEF-264.md
+++ b/BRIEF-264.md
@@ -1,6 +1,6 @@
 # BRIEF-264.md — deterministic pathfinding for many ground units
 
-8433d6f
+9944efa
 
 ## 1. The evidence command
 
@@ -244,7 +244,7 @@ Each of these is also a comment on issue #264, so it is reviewable next to the c
    after it.** Refusing all penetration deadlocked the crowd: 99 of 100 still walking after the
    1200-tick budget. Measured alternatives are in `NavMoveSystem`'s KDoc.
 5. **`NavAgent` and `NavObstacle` are `@Replicated`**, so `net-components.lock` gains two lines.
-   They sort after every existing entry, so no id moved (section 7).
+   They sort after every existing entry, so no id moved (section 6).
 
 ### Things the reviewer should know I touched outside the module
 
@@ -276,9 +276,51 @@ Each of these is also a comment on issue #264, so it is reviewable next to the c
 ## 3. `sh gradlew build`
 
 ```
-(not yet run)
+ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
+  sh gradlew build --continue
+```
+
+Green, no exclusions. Spliced from the saved log,
+`scratchpad/issue264/full-build-raw.txt` (1923 lines); every elision is marked and each segment is
+a consecutive run of that file.
+
+```
+> Task :udeaVerifyAgentsMd
+[... 1596 lines elided ...]
+> Task :udeaVerifyDeterminism
+udeaVerifyDeterminism
+  scanned :udea-core: 624 class files
+  scanned :udea-gas: 272 class files
+  scanned :udea-net: 421 class files
+  scanned :udea-physics2d: 68 class files
+  scanned :udea-nav: 88 class files
+  scanned :moba:game: 706 class files
+  allowlist entries used: 0
+  findings: 0
+[... 177 lines elided ...]
+BUILD SUCCESSFUL in 3m 2s
+1058 actionable tasks: 809 executed, 13 from cache, 236 up-to-date
 ```
 
+`:udea-nav:jvmTest` reads `UP-TO-DATE` in that log because the evidence command had already run at
+this SHA. Its own run, `sh gradlew :udea-nav:jvmTest --rerun-tasks -i`, is the transcript in
+section 5.
+
+**The first full build of this branch failed, and both failures were mine.** They are fixed in
+`9944efa` and are worth reading because neither could have been caught by the module's own tests:
+
+- `:udea-render:udeaVerifyHeadless` — *"udea-render's build script and
+  ModuleGraphRules.HEADLESS_PROJECTS must between them designate every udea-* module except
+  [udea-render, udea-agent-host, udea-editor]"*, and the expected-minus-actual was exactly
+  `udea-nav`. A new module is not designated until it is added to the set.
+- `:udea-nav:compileTestKotlinIosArm64` and `…IosSimulatorArm64` — *"Name contains illegal
+  characters: "*""* and *"Name contains illegal characters: ",""*. Kotlin/Native refuses a
+  backticked test name holding `*` or `,`; the JVM target compiles both. Two test names were
+  reworded.
+
+`:udea-assets-compiler:udeaDaemonBudget` passed inside this build, on a box whose one-minute load
+average was 14.9 when the build started, so no solo re-run was needed.
+
 ## 4. The images
 
 All in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`, from
@@ -305,7 +347,7 @@ tick** rather than at the end. Its own printed output, from the run of
 ```
     NavCrowdTest: 100 units arrived at tick 773 of 1200; worst compression in transit 0.13775164m of a 0.6m contact distance, at tick 242, NetId(#9@0) at (0.9942008, -0.1420665) and NetId(#17@0) at (0.5944242, -0.37412727)
     NavCrowdTest: settled overlap 0.0035638213m at tick 773, NetId(#25@0) at (9.925581, 1.55114) and NetId(#61@0) at (10.239827, 1.0442026)
-BUILD SUCCESSFUL in 31s
+BUILD SUCCESSFUL in 44s
 ```
 
 So: arrival at tick 773 of 1200; worst in-transit compression 0.138m against a 0.6m contact
diff --git a/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/NavPathfinder.kt b/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/NavPathfinder.kt
index 90a977d..dc945c2 100644
--- a/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/NavPathfinder.kt
+++ b/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/NavPathfinder.kt
@@ -102,14 +102,6 @@ public class NavPathfinder(
                 // No corner cutting: a diagonal step is allowed only where both of the cells it
                 // passes between are open to this unit as well. Without it a unit walks through
                 // the diagonal join of two buildings, which is a gap of zero width.
-                if (NavSteps.isDiagonal(step) &&
-                    (
-                        !grid.fits(grid.cellOf(neighbourColumn, row), clearanceCells) ||
-                            !grid.fits(grid.cellOf(column, neighbourRow), clearanceCells)
-                        )
-                ) {
-                    continue
-                }
                 val index = neighbour.index
                 if (closed[index] == closedStamp) continue
                 val tentative = cost + NavSteps.cost(step)
```

Fails (2):

- `NavFlowFieldTest[jvm] > the field agrees with the A star search about what a route costs()[jvm] FAILED`
- `NavPathfinderTest[jvm] > a diagonal may not cut the corner of a blocked cell()[jvm] FAILED`

#### M3 — Clearance ignores the unit's size: every unit is treated as one cell wide.

A wide unit is told it fits through a one-cell gap. This is the mutation that makes `nav.path`'s radius argument mean nothing.

```diff
diff --git a/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/NavGrid.kt b/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/NavGrid.kt
index a0c33c5..4ac1fc1 100644
--- a/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/NavGrid.kt
+++ b/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/NavGrid.kt
@@ -116,8 +116,7 @@ public class NavGrid internal constructor(
         cell.isValid && clearance[cell.index] >= clearanceCells
 
     /** How many cells of [clearance] a unit of radius [radius] needs. At least one. */
-    public fun clearanceCellsFor(radius: Float): Int =
-        max(1, ceil(radius / cellSize).toInt())
+    public fun clearanceCellsFor(radius: Float): Int = 1
 
     override fun toString(): String =
         "NavGrid(${width}x$height @ ${cellSize}m from ($originX, $originY), ${footprints.size / 4} footprints)"
```

Fails (2):

- `NavGridTest[jvm] > a unit needs as many cells of clearance as its radius covers()[jvm] FAILED`
- `NavToolTest[jvm] > nav path answers a wide unit differently from a narrow one()[jvm] FAILED`

#### M4 — The grid is rebuilt every tick instead of when the buildings change.

Not wrong on the ground - the grid is the same - but it throws away both route caches sixty times a second, and the test that pins the comparison is what says so.

```diff
diff --git a/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/NavGridSystem.kt b/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/NavGridSystem.kt
index e86b9c9..3917180 100644
--- a/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/NavGridSystem.kt
+++ b/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/NavGridSystem.kt
@@ -60,7 +60,6 @@ public class NavGridSystem(
     override fun onTick() {
         count = 0
         netIds.forEachLive(visitor)
-        if (matchesCurrentGrid()) return
         val builder = NavGridBuilder(navigation.grid.layout)
         var offset = 0
         while (offset < count) {
```

Fails (1):

- `NavGridSystemTest[jvm] > a tick that changes no building leaves the grid alone()[jvm] FAILED`

#### M5 — `Navigation.rebuild` swaps the grid and keeps the caches.

A route found on ground that has since been built on is handed out again. **This is the one that nearly did not bite**: against the 44 tests this branch had before, it passed all of them. `NavigationCacheTest` exists because of it and is in the diff.

```diff
diff --git a/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/Navigation.kt b/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/Navigation.kt
index 688aa8d..ae9d03d 100644
--- a/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/Navigation.kt
+++ b/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/Navigation.kt
@@ -70,10 +70,6 @@ public class Navigation internal constructor(grid: NavGrid) {
     internal fun rebuild(grid: NavGrid) {
         this.grid = grid
         pathfinder = NavPathfinder(grid)
-        fields.fill(null)
-        nextField = 0
-        fieldSweeps = 0
-        hopFrom.fill(EMPTY_SLOT)
     }
 
     /**
```

Fails (3):

- `NavigationCacheTest[jvm] > a flow field swept before a wall went up is not handed out after it()[jvm] FAILED`
- `NavigationCacheTest[jvm] > a hop asked for before a wall went up is not answered from the cache after it()[jvm] FAILED`
- `NavigationCacheTest[jvm] > a rebuild that changes nothing still drops the caches - it is the grid that owns them()[jvm] FAILED`

#### M6 — Units remember the hop they are walking to, in a map in the system rather than in their components.

The design `Navigation`'s KDoc rejects, and the reason the rewind test is in the suite: a restore puts the components back and cannot put this map back, so the re-simulated ticks diverge.

```diff
diff --git a/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/NavMoveSystem.kt b/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/NavMoveSystem.kt
index f9a4651..410448b 100644
--- a/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/NavMoveSystem.kt
+++ b/udea-nav/src/commonMain/kotlin/dev/wildware/udea/nav/NavMoveSystem.kt
@@ -103,6 +103,9 @@ public class NavMoveSystem(
     /** How far the step pass looks for a unit in the way. Set once a tick, from the crowd. */
     private var stepReach = 1
 
+    /** The hop each unit is walking to, kept until it gets there. */
+    private val rememberedHops = HashMap<NavAgent, NavCell>()
+
     private val visitor = object : NetIdVisitor {
         override fun visit(netId: NetId, entity: Entity) {
             gather(entity)
@@ -222,10 +225,17 @@ public class NavMoveSystem(
         }
         val clearanceCells = clearance[index]
         if (standing != goal) {
-            val hop = if (shared[index]) {
-                navigation.flowField(goal, clearanceCells).nextHop(standing)
+            val remembered = rememberedHops[agent]
+            val hop = if (remembered != null && remembered != standing) {
+                remembered
             } else {
-                navigation.nextHop(standing, goal, clearanceCells)
+                val found = if (shared[index]) {
+                    navigation.flowField(goal, clearanceCells).nextHop(standing)
+                } else {
+                    navigation.nextHop(standing, goal, clearanceCells)
+                }
+                rememberedHops[agent] = found
+                found
             }
             if (!hop.isValid) {
                 agent.state = NavState.Unreachable
```

Fails (2):

- `NavCrowdTest[jvm] > a hundred units ordered across a map with obstacles arrive without overlapping()[jvm] FAILED`
- `NavDeterminismTest[jvm] > a world rewound to a snapshot re-simulates the ticks it had already run()[jvm] FAILED`


## 2. Summary

A new headless module, `udea-nav`, on `udea-core` alone.

- **`NavGrid`** is the ground plane cut into cells, with a Chebyshev clearance field so one grid
  answers for units of any size. `NavGridSystem` (PreSimulation) rebuilds it by *comparing* the
  footprints standing in the world with the ones the current grid was built from — a comparison,
  not an event — so a rewind re-derives the grid instead of replaying a history of placements.
- **`NavPathfinder`** is A* over integer octile costs (10 straight, 14 diagonal), a fixed neighbour
  table, a total ordering on `(f, h, cellIndex)`, no corner cutting, and generation stamps instead
  of clearing arrays. **`NavFlowField`** is one Dijkstra sweep from the goal over the same edges
  and the same corner rule, which is what a group ordered to one point reads.
- **`NavMoveSystem`** (Movement) is four passes over column arrays: gather in ascending `NetId`
  order, route, step, separate and settle. A unit's next step is a function of the cell it is
  standing in, its goal and the grid — **no per-unit routing state exists outside the components**,
  which is what makes the rewind leg pass.
- **`nav.path` and `nav.grid`** are the agent surface, reading the same `Navigation` the simulation
  does.
- **`:udea-nav:udeaNavShot`** draws the grid, a route, the field and the hundred-unit crowd to PNGs
  with no GL context.

### Decisions, and what was rejected

Each of these is also a comment on issue #264, so it is reviewable next to the code.

1. **The toolset is `jvmMain` over `compileOnly(project(":udea-agent"))`** — the bargain
   `udea-replay` strikes, because `UDEA-REL-002` keeps the agent surface off a shipped runtime
   classpath. Rejected: a separate `udea-nav-agent` module (a module and a registry for two
   functions); `implementation` (puts the surface in a release); registering on the generated
   registry facet (`ToolIndex.Builder.build` refuses a tool whose toolset was never registered, so
   every game listing `udea-nav` would fail at start-up unless it had built a `NavToolset` — and
   only the host that built the game holds the `Navigation`).
2. **No editor debug drawing.** The dispatch made it optional. The tools answer the same questions
   headless, over the bridge and in a test, and `udeaNavShot` produces the pictures. If it is wanted
   in the window later, the shapes to draw are exactly what `nav.grid` and `nav.path` return.
3. **A group's order is resolved to one goal cell**, from the ordered point, not per unit. A
   hundred units sent onto a building walk to the same reachable cell rather than fanning out to a
   hundred different ones.
4. **A unit may press 0.15 of a radius into a neighbour while stepping, with four separation passes
   after it.** Refusing all penetration deadlocked the crowd: 99 of 100 still walking after the
   1200-tick budget. Measured alternatives are in `NavMoveSystem`'s KDoc.
5. **`NavAgent` and `NavObstacle` are `@Replicated`**, so `net-components.lock` gains two lines.
   They sort after every existing entry, so no id moved (section 6).

### Things the reviewer should know I touched outside the module

- **`build-logic`**: one entry added to `DeterminismRules.SIMULATION_SCOPES` for `:udea-nav`.
  Without it `udeaVerifyDeterminism` silently ignores a new simulation module. #265 is also in
  `build-logic`; this is a list entry and should merge as text. I ran the control: with
  `kotlin.random.Random.Default.nextInt(0)` added to `NavMoveSystem.step`, the gate fails with
  `[DET002] dev.wildware.udea.nav.NavMoveSystem.step is declared simulation (:udea-nav) and draws
  from an unseeded random source` — so the scope really does scan, rather than reporting zero
  findings because it found nothing to look at. Without the mutation: `scanned :udea-nav: 88 class
  files`, `findings: 0`.
- **`AGENTS.md`** and **`docs/module-graph.md`**: a row each for `udea-nav`. `udeaVerifyAgentsMd`
  checks the first against `settings.gradle.kts`.
- **No file in `docs/contracts/` was touched**, and `net-protocol.lock` and
  `expected-generated-hashes.txt` are unchanged.

### What I did not exercise

- **iOS.** `udea-nav` is on `udea.kotlin-multiplatform`, so it has the iOS targets, and this box
  cannot build them. The `ios-tests` CI job is what covers it. I have not claimed otherwise.
- **A real network.** The determinism criterion is proved with two worlds in one process, a replay
  and a rewind (section 6, AC-2). No `udea-net` code is involved in this branch at all.
- **A game using it.** `moba` does not install `NavModule`; nothing in `moba` changed. The module
  is proved through its own real `UdeaGameDef`, which is the same construction a game uses.
- **GL.** Nothing in this branch opens a context: the module is headless and the shot renderer is
  `java.awt` into a `BufferedImage`. So there is no xvfb run to show, and its absence is not an
  omission.

## 3. `sh gradlew build`

```
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew build --continue
```

Green, no exclusions. Spliced from the saved log,
`scratchpad/issue264/full-build-raw.txt` (1923 lines); every elision is marked and each segment is
a consecutive run of that file.

```
> Task :udeaVerifyAgentsMd
[... 1596 lines elided ...]
> Task :udeaVerifyDeterminism
udeaVerifyDeterminism
  scanned :udea-core: 624 class files
  scanned :udea-gas: 272 class files
  scanned :udea-net: 421 class files
  scanned :udea-physics2d: 68 class files
  scanned :udea-nav: 88 class files
  scanned :moba:game: 706 class files
  allowlist entries used: 0
  findings: 0
[... 177 lines elided ...]
BUILD SUCCESSFUL in 3m 2s
1058 actionable tasks: 809 executed, 13 from cache, 236 up-to-date
```

`:udea-nav:jvmTest` reads `UP-TO-DATE` in that log because the same task had already run against
this tree, in the check that preceded the commit. Its own forced run,
`sh gradlew :udea-nav:jvmTest --rerun-tasks -i`, is the transcript in section 5.

**The first full build of this branch failed, and both failures were mine.** They are fixed in
`9944efa` and are worth reading because neither could have been caught by the module's own tests:

- `:udea-render:udeaVerifyHeadless` — *"udea-render's build script and
  ModuleGraphRules.HEADLESS_PROJECTS must between them designate every udea-* module except
  [udea-render, udea-agent-host, udea-editor]"*, and the expected-minus-actual was exactly
  `udea-nav`. A new module is not designated until it is added to the set.
- `:udea-nav:compileTestKotlinIosArm64` and `…IosSimulatorArm64` — *"Name contains illegal
  characters: "*""* and *"Name contains illegal characters: ",""*. Kotlin/Native refuses a
  backticked test name holding `*` or `,`; the JVM target compiles both. Two test names were
  reworded.

`:udea-assets-compiler:udeaDaemonBudget` passed inside this build, on a box whose one-minute load
average was 14.9 when the build started, so no solo re-run was needed.

## 4. The images

All in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`, from
`:udea-nav:udeaNavShot`.

| File | What it shows | What it proves |
|---|---|---|
| `issue264-crowd-sequence.png` | Eight named ticks of a hundred units crossing a map with two buildings, tiled | AC-1 end to end: they leave in a block, squeeze between the buildings, and 100 of 100 are standing still round the target at tick 773 with no circle inside another |
| `issue264-crowd-t0001.png` | The start: ten by ten, west of both buildings | The fixture, before anything has moved |
| `issue264-crowd-t0773.png` | The end: the crowd packed round the goal cross, every unit green (Arrived) | The settle terminates, and packs rather than piles |
| `issue264-astar-route.png` | One unit and the A* route `nav.path` reports for it: 51 cells, cost 612 | A single unit's route hugs the buildings and does not cross one, and the tool reports the route the unit would walk |
| `issue264-flow-field.png` | The field a group shares: cost shaded from the goal outward, one hop arrow per other cell | The group planner covers the whole map from one sweep, and the arrows converge on the goal from both sides of both buildings |

## 5. The issue, criterion by criterion

**AC-1: "100 units ordered across a map with obstacles arrive without overlapping within a set tick
budget."**

`NavCrowdTest` — `a hundred units ordered across a map with obstacles arrive without overlapping`.
100 units, two buildings, one order, a 1200-tick (20-second) budget, the overlap measured on **every
tick** rather than at the end. Its own printed output, from the run of
`sh gradlew :udea-nav:jvmTest --rerun-tasks -i` saved as `scratchpad/issue264/crowd-numbers.txt`:

```
    NavCrowdTest: 100 units arrived at tick 773 of 1200; worst compression in transit 0.13775164m of a 0.6m contact distance, at tick 242, NetId(#9@0) at (0.9942008, -0.1420665) and NetId(#17@0) at (0.5944242, -0.37412727)
    NavCrowdTest: settled overlap 0.0035638213m at tick 773, NetId(#25@0) at (9.925581, 1.55114) and NetId(#61@0) at (10.239827, 1.0442026)
BUILD SUCCESSFUL in 44s
```

So: arrival at tick 773 of 1200; worst in-transit compression 0.138m against a 0.6m contact
distance, at a named tick between two named units; settled overlap 0.0036m. The fences are
`TRANSIT_TOLERANCE = 0.18f` and `SETTLED_TOLERANCE = 0.01f`, both justified in the test's KDoc
against those measurements. Picture: `issue264-crowd-sequence.png`. Mutation M1 is the proof it
cannot pass with separation off.

Three more tests in that class cover what the hundred-unit run does not: `no unit ends its walk
standing inside a building`, `a unit ordered onto a building walks to the ground beside it instead`,
and `a unit with nowhere to go reports that it is unreachable`.

**AC-2: "the same orders give the same paths on server, client and replay."**

`NavDeterminismTest`, six tests:

- `two worlds given the same orders walk the same route tick for tick` — two independently built
  worlds, 600 ticks, `WorldHasher.hash` of a full capture compared on every tick. This is
  server-vs-client: a client re-runs the same systems over the same replicated components.
- `a replayed world reaches the same positions as the one it replays` — a third world fast-forwarded
  through the same ticks, with a named unit's position stream compared bit for bit
  (`toRawBits`), which is what `udea-replay` does with a `.udearep`.
- `a world rewound to a snapshot re-simulates the ticks it had already run` — the leg the criterion
  does not ask for and prediction needs. **Mutation M6 is the proof it bites**: a remembered hop in
  a map outside the components makes exactly this test fail.
- `a rewind puts an order back the way it was` — the order and state restore, not just the position.
- `the route a unit is given does not depend on how many units asked before it` — a cold service and
  one warmed with a thousand other routes answer the same cells. The cache cannot change an answer.
- `a unit walks a route as long as the one nav path reports, and ends where it ends` — the tool and
  the units are the same A*.

**"Nav grid on the ground plane blocked by building footprints, updated when buildings are placed
and destroyed."**

`NavGridTest` (6) for the grid and the clearance field; `NavGridSystemTest` (5) for the world:
a building placed blocks the ground, destroyed opens it, an unchanged tick does not rebuild
(mutation M4), a wall built across a route re-routes the unit, and a unit a building is put on top
of walks out from under it. `NavigationCacheTest` (3) pins that a rebuild drops the routes found on
the old ground (mutation M5).

**"Deterministic A* for single units."**

`NavPathfinderTest` (10): diagonal stepping, the cost of a detour round a wall, the corner rule
(M2), a walled-off goal, a gap a wide unit is refused and a narrow one walks (M3), a start that does
not fit, that a unit's next hop is the second cell of the route, standing on the goal, that the same
search run twice returns the same route, and that a reused `NavPath` holds only the latest route.

**"Flow field for group move orders to one point."**

`NavFlowFieldTest` (5), including `the field agrees with the A star search about what a route
costs` — the two
planners cannot drift apart about the same ground. `NavMoveSystem` switches to it at two units
sharing a goal cell. Picture: `issue264-flow-field.png`.

**"Local avoidance/separation, plus unit sizes."**

Separation is the fourth pass of `NavMoveSystem` and is measured every tick by `NavCrowdTest`
(M1). Unit size is the clearance field: `NavGridTest`'s clearance test and `NavToolTest`'s
`nav path answers a wide unit differently from a narrow one` (M3), where a 0.2m unit gets through a
one-cell gap and a 0.9m one is told there is no route.

**"Debug drawing in the editor and an agent tool that reports a path."**

The tool is required and is `NavToolTest` (8): both tools driven **by name, with string arguments,
through a real `ToolIndex`**, including the refusals (`radius = 0`, `halfSpan = 64`) and a walk over
every tool the module publishes. Editor drawing was optional and was not done; `udeaNavShot` and the
tools carry the debugging instead, and the reasoning is a comment on the issue and decision 2 above.

## 6. Regenerated files

**Only `net-components.lock`**, and only by appending. Two names —
`dev.wildware.udea.nav.NavAgent` and `dev.wildware.udea.nav.NavObstacle` — added after the previous
last entry, `dev.wildware.udea.core.spatial.Transform3D`. The list is sorted by FQN and nothing
sorts between, so **no existing component id moved**, and the rest of the diff is a comment block
saying why both components are replicated.

`udea-codegen/net-protocol.lock` and `udea-codegen/src/test/resources/expected-generated-hashes.txt`
are **unchanged**: they cover codegen's own fixtures, whose names sort before these, so no fixture
id moved either. `udeaCheckProtocolLock` and `udea-codegen`'s tests run in the `sh gradlew build`
above.
