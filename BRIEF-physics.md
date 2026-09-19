4814d78

# BRIEF-physics: 2D physics on Box2D 3, in `udea-physics2d`

Branch `physics2d-box2d`. It was cut from `origin/kmp` at `5821d25`. `origin/kmp` `48554d4` (#188) was merged in as `4814d78`. Every result below comes from `4814d78` unless its line says otherwise. The commit that adds this file sits on top of that merge and changes nothing else. There is no GitHub issue for this work; it was approved on the owner's dashboard and is tracked on epic #199.

## Evidence command

```
sh gradlew :udea-physics2d:jvmTest --rerun
```

This runs the module's eight test classes against the real Box2D native library:

- determinism
- rewind
- the chain rule
- the wire
- native memory
- the `PhysicsWorld` contract
- the public-API scan
- the bindings mirror

**It goes red when the feature is reverted.** The table under "Mutations" gives the literal diff and the failing tests for each mutation. Two of them are the feature itself:

- **M5** removes the write-back of solved bodies into `PhysicsBody`, so the solver still steps but nothing reaches the components. 11 tests fail.
- **M6** makes one step's sub-step count depend on the wall clock. 5 tests fail, including `two runs of the same scene hash identically at every tick`.

On `origin/kmp` the task does not exist at all.

The run at `4814d78`, from `scratchpad/physics/evidence-4814d78.log` (last three lines):

```
BUILD SUCCESSFUL in 5s
64 actionable tasks: 7 executed, 57 up-to-date
Configuration cache entry stored.
```

The test counts, read from that run's JUnit XML (`udea-physics2d/build/test-results/jvmTest/*.xml`, sorted):

```
testsuite name="dev.wildware.udea.physics2d.Box2DBindingsMirrorTest" tests="2" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.physics2d.Box2DDeterminismTest" tests="2" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.physics2d.Box2DNativeMemoryTest" tests="3" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.physics2d.Box2DPhysicsWorldTest" tests="14" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.physics2d.Box2DRewindTest" tests="5" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.physics2d.ChainStaticGeometryTest" tests="5" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.physics2d.NoBox2DInPublicApiTest" tests="3" skipped="0" failures="0" errors="0"
testsuite name="dev.wildware.udea.physics2d.PhysicsNeverOnTheWireTest" tests="2" skipped="0" failures="0" errors="0"
```

## Summary

**What is new.**

- `udea-physics2d` implements `udea-core`'s `PhysicsWorld` over `de.fabmax.box2d-jni:box2d-jni` 1.0.0.
- It uses the Box2D 3.1.1 JVM jar and the `box2d-jni-android` AAR. It does not use `kool-physics-2d`, which pulls in `kool-core`, and UDEA-MG-002 bans that from a headless module.
- A game installs it with `Physics2DModule(Physics2DSettings(...))`.
- A system runs at `SimPhase.Physics`, before `PhysicsStepSystem`. It reconciles bodies with components:
  - it creates bodies for new entities, in ascending `NetId` order;
  - it destroys bodies whose entity or `PhysicsBody` is gone;
  - it rebuilds a body whose kind, sensor flag or shape changed;
  - it applies `Teleport`.
- `PhysicsStepSystem` then steps Box2D once, `1 / tickRate` seconds with the configured sub-steps. The solved pose and velocity are copied into `PhysicsBody` inside that step.
- Contacts and sensor events reach `ContactListener` as `BodyHandle` pairs.
- `raycast` and `overlap` answer through Box2D's own queries.
- No Box2D type is in any public signature. Box2D is reached only through the module's `internal` typealiases and `internal` classes. `NoBox2DInPublicApiTest` checks this with Kotlin reflection over the compiled classes and with a known-negative class.

**What changed in `udea-core`, and why it had to.**

- `PhysicsBody`, `Box`, `Circle`, `Capsule` and `Teleport` were not `@Replicated`, so no snapshot captured physics state at all. A rewind put nothing back.
- They are now `@Replicated` with every field `@Sim`:
  - they are captured and restored;
  - their `netMask` is empty, so no delta packet carries them (the lead's guard 2);
  - they take ids 22-26.
- `PhysicsSnapshotTypes.all()` gives a game the five registrations.
- `udea-core` now generates and checks its own `net-protocol.lock`.
- The rest of the `udea-core` diff is KDoc that said "there is no Box2D backend", corrected.

**Decisions.** Each is posted on #199 with its alternative and what to change if the owner disagrees:

1. Physics components are `@Sim`-only replicated, and are never on the wire.
2. `Chain` is static level geometry. A chain that changes at runtime is refused loudly with `StaticGeometryChangedException` (the lead's guard 3).
3. The rewind limitation is stated and measured (below, and on #199).
4. The Box2D version is 3.1.1. The binding's README says 3.3.1 and is wrong. The worker count cannot be set, because `box2d-jni` does not bind `workerCount`.
5. Targets, bindings and NetIds:
   - a new convention, `udea.kotlin-multiplatform-jvm-android`, for the `jvm` and `android` targets;
   - one shared `src/box2dMain` over per-target typealias files;
   - a `PhysicsBody` with no `NetId` fails loudly.

**The named rewind limitation.**

- **Holds:** a run that restores a snapshot at tick T equals, bit for bit and at every later tick, a run that rebuilt its solver from its own components at T. `Box2DRewindTest` checks this with stacking, rolling, spinning and a chain ramp, over 240 re-simulated ticks.
- **Does not hold:** a restored run does *not* equal the run that never rewound once bodies touch or rotate. There are two causes:
  1. Box2D's warm-start impulses, contact manifolds and sleep timers are not in any component.
  2. `angle` goes through Box2D's approximate rotation round trip.
- **Measured:** the test prints, in the same run's XML:

  ```
  stacking scene: restored run departs from the original at re-simulated tick 0 of 240
  spinning scene: restored run departs from the original at re-simulated tick 0 of 240
  ```

- **Holds exactly:** a contact-free scene that does not rotate replays the original exactly. This is tested.
- **The rule for callers:** prediction must not re-run the solver. `DET005` now matches `box2d.` and `box2dandroid.` as well as LibGDX's Box2D.

**Determinism settings, explicit.**

- Sub-steps, continuous collision and sleep are set on the world definition and read back from Box2D in a test.
- The step is one tick; no clock is read.
- The worker count is `b2DefaultWorldDef`'s single worker with no task system. The bindings expose no field to set it. I grepped every binding source for `workerCount` and `enqueueTask`, and it returned nothing.

**Gates honoured.**

- `SnapshotExclusions.Box2DSolverState` is untouched and is exactly what the rebuild does.
- `NoBox2DInCoreTest` still passes.
- `:udea-physics2d` is in `HEADLESS_PROJECTS`, which puts UDEA-MG-002 on it.
- It is a declared `udeaVerifyDeterminism` scope. `determinism-audit.md` has two new rows for `box2d.*` and `box2dandroid.*`, and a dated note.

**Native memory, as counts.** `Box2DNativeMemoryTest` checks three things:

- 40 cycles of spawning 150 bodies (100 shapes on boxes and circles, 100 chain segments), stepping them into contact and despawning them. After every cycle, `b2World_GetCounters` reports 0 bodies, 0 shapes and 0 contacts.
- 20 worlds, each opened, run and closed. `b2GetByteCount` returns to its baseline after each close, and the module's own scratch-struct count returns to 0.
- 40 rebuilds of one world. The byte count is identical after each rebuild.

I first wrote the per-cycle check as byte equality, and it failed: 553564 to 565708 bytes over 40 cycles, then flat. That is Box2D growing its arrays to a high-water mark when contacts arrive in a new order. The live-object counts are the check that means "nothing leaked". Mutation M9, which stops destroying bodies, turns it red.

**Things the ticket left open that I ruled on.**

- A shape edit on a resting body rebuilds that body awake. Built asleep, a grown circle sat inside the floor. Mutation M7 covers this.
- `destroyAllBodies` is the scene-teardown path, and chains may leave that way.
- `overlap` with a `Chain` query shape throws `IllegalArgumentException`, because a chain has no inside.

**No moba change**, as the lead decided. So three public declarations have no production caller yet. Each is the API a game uses to install physics, and each is exercised through `udea-physics2d`'s tests:

- `Physics2DModule`
- `Physics2DSettings`
- `PhysicsSnapshotTypes` (in `udea-core`; a game appends it to its registry, as `moba` does with its own types)

I am naming this against the standards' "public declaration nobody outside the module uses". The first real caller is whichever game adopts 2D physics.

**Not exercised here.** I did not run the Android target. `compileAndroidMain` builds it, and the `build` below builds the AAR variant, but no device or emulator ran it. The desktop natives for macOS and Windows are on the runtime classpath, but only `natives-linux` was loaded on this box. Cross-platform bit equality is not claimed.

## `sh gradlew build`

Run at `4814d78` with `JAVA_HOME` set to Temurin 21.0.11, `ANDROID_HOME=/home/shaun/Android/Sdk`, and `--continue`. The log is `scratchpad/physics/build-4814d78.log`. Its last lines:

```
BUILD SUCCESSFUL in 2m 52s
958 actionable tasks: 808 executed, 15 from cache, 135 up-to-date
Configuration cache entry stored.
```

`grep -c FAILED` over that log gives 0. The baseline was the green `5821d25` (928 tasks). This branch adds a module, and the merge of #188 adds its own tasks, so the count is 958.

**No GL run.** This branch touches no file in `udea-render` or `udea-agent-host` and opens no context. `udea-physics2d` is headless and is gated as such by `HEADLESS_PROJECTS`, so `udeaGlTest` and `udeaAgentGlTest` cover nothing this branch changed.

The build-logic tests (an included build, run on their own at the pre-merge tree with `sh gradlew -p build-logic test`) all passed, including the new `DET005` test and the new convention test. The `DET005` test failed first, before the rule changed: `expected: <[DET005 ...Android, DET005 ...Desktop]> but was: <[]>`.

## Other gates

`sh gradlew udeaReplayEqualityProof udeaVerifyModuleGraph udeaVerifyAgentsMd udeaVerifyDeterminism udeaCheckProtocolLock` at `4814d78`. The log is `scratchpad/physics/gates-4814d78.log`. Below are its task lines, its verdict lines and its result, in log order, with everything else elided:

```
> Task :udeaVerifyAgentsMd UP-TO-DATE
> Task :udea-core:udeaCheckProtocolLock
> Task :moba:game:udeaCheckProtocolLock
> Task :udea-codegen:udeaCheckProtocolLock
> Task :udeaVerifyModuleGraph UP-TO-DATE
> Task :udeaVerifyDeterminism UP-TO-DATE
replay equality holds: 3600 tick(s) of 'drift-3600.udearep' are cell-for-cell identical
replay equality FAILED at t1200 (1200 tick(s) matched first)
replay equality holds: 3600 tick(s) of 'drift-3600.udearep' are cell-for-cell identical
replay equality FAILED at t1200 (1200 tick(s) matched first)
replay equality holds: 3600 tick(s) of 'moba-3600.udearep' are cell-for-cell identical
replay equality FAILED at t1200 (1199 tick(s) matched first)
replay equality holds: 3600 tick(s) of 'moba-3600.udearep' are cell-for-cell identical
replay equality FAILED at t1200 (1199 tick(s) matched first)
BUILD SUCCESSFUL in 11s
220 actionable tasks: 28 executed, 192 up-to-date
```

The four `FAILED` verdicts are the proof's planted legs, which must fail, and they fail at the planted tick 1200. Each equal leg reports that it holds. `udeaVerifyDeterminism` wrote `build/reports/udea/determinism.txt`, which contains `scanned :udea-physics2d: 56 class files` and `findings: 0` (the report as it stands after the build at `4814d78`). The natives resolve and load: every `jvmTest` above ran against `natives-linux`.

## Images

None. `udea-physics2d` has no presentation: nothing in the tree draws a Box2D body, and the lead ruled out a moba change. The evidence is the test report and the transcripts above.

## Acceptance criteria

| Criterion | Proof |
|---|---|
| New module `udea-physics2d` implements `PhysicsWorld` using the existing components (PhysicsBody, Box, Circle, Capsule, Chain, Teleport) | `Box2DPhysicsWorldTest` (14 tests): step length and sub-steps against the closed form, write-back, kinematic, contacts, sensor, raycast, overlap, Teleport, setAwake, shape edit, dead handle, missing NetId. `ChainStaticGeometryTest` for Chain |
| No Box2D type in any public API | `NoBox2DInPublicApiTest`: a reflection scan of every compiled class, a source scan of top-level declarations, and a known-negative `Leaky` class that the scan must flag |
| Deterministic: two runs give identical `WorldHasher.hash` at every tick | `Box2DDeterminismTest > two runs of the same scene hash identically at every tick`, over 300 ticks. It first checks the scene did something: more than 20 contacts, a drop of more than 5 units, and at least one body asleep |
| A test fails when non-determinism is injected | `Box2DDeterminismTest > the hash sees a one-ulp difference in a single body` (the control), and mutation M6 (the wall clock in the step), which turns the determinism test red |
| Snapshot and rewind give the same hashes, or the strongest honest version plus a named limitation | `Box2DRewindTest` (5 tests), the limitation above, and the #199 comment |
| Module graph: no Kool or GL, nothing below depends on it, natives resolve, AGENTS.md row | `udeaVerifyModuleGraph` (HEADLESS_PROJECTS), `udeaVerifyAgentsMd`, and the natives loaded in every jvmTest; see "Other gates" |
| `sh gradlew build` fully green, including `udeaVerifyDeterminism` and `udeaReplayEqualityProof` | "`sh gradlew build`" and "Other gates" above |
| Lead guard 1: locks regenerated only by their tasks, with the id diff | "Regenerated files" below |
| Lead guard 2: `@Sim`-only is never on the wire | `PhysicsNeverOnTheWireTest`: 120 moving ticks of creates and deltas write only the terminator bits, and a removal record is read back as the id with an empty mask. Mutation M2 (one field to `@Net`) turns it red |
| Lead guard 3: static chains rewind, and a changed chain is refused loudly | `ChainStaticGeometryTest` (5 tests), and mutation M4 |
| No native leak across create/destroy, as a count | `Box2DNativeMemoryTest` (3 tests), and mutation M9 |

## Mutations

Each row is the literal `git diff` that `scratchpad/physics/mutate.py` applied to the committed tree. The failing tests come from that run's JUnit XML. The tree was restored after each run, and the script printed `git status --short` as empty.

M1 and M2 run `:udea-core:jvmTest --tests *PhysicsSnapshotTypesTest*` (M2 also runs `:udea-physics2d:jvmTest`). The rest run `:udea-physics2d:jvmTest`. `tests_seen` counts the testcases in the XML the run left behind. M6a was the first form of the nondeterminism injection, which reads the clock when the world opens. It turned the two rewind-equality tests red but not the determinism test, and I did not establish why. So I replaced it with M6, a clock read in the step itself, which turns the determinism test red. Both are kept here because both ran.

M1 to M9 ran before the merge of `origin/kmp`, on commit `bd3c017` (the physics files are byte-identical at `4814d78`; `git diff bd3c017 4814d78 -- udea-physics2d udea-core/src/commonMain/kotlin/dev/wildware/udea/core/physics` is empty).

### M1-schema-kind

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/physics/PhysicsSnapshotTypes.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/physics/PhysicsSnapshotTypes.kt
index 09dd978..3cb697f 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/physics/PhysicsSnapshotTypes.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/physics/PhysicsSnapshotTypes.kt
@@ -59,7 +59,7 @@ public object PhysicsSnapshotTypes {
                     FieldKind.Float,
                     FieldKind.Bool,
                     FieldKind.Bool,
-                    FieldKind.Int,
+                    FieldKind.Float,
                     FieldKind.Float,
                     FieldKind.Float,
                     FieldKind.Float,
```

`exit=1 tests_seen=3 failed=1 compile_failed=False`

- PhysicsSnapshotTypesTest > every field of every physics component comes back from a snapshot()[jvm]

### M2-sim-to-net

```diff
diff --git a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/physics/PhysicsComponents.kt b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/physics/PhysicsComponents.kt
index 97869ee..de84289 100644
--- a/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/physics/PhysicsComponents.kt
+++ b/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/physics/PhysicsComponents.kt
@@ -37,7 +37,7 @@ import kotlinx.serialization.encoding.Encoder
 @Replicated
 public class PhysicsBody(
     @Sim public var kind: BodyKind = BodyKind.Dynamic,
-    @Sim public var x: Float = 0f,
+    @dev.wildware.udea.annotations.Net public var x: Float = 0f,
     @Sim public var y: Float = 0f,
     /** Radians. */
     @Sim public var angle: Float = 0f,
```

`exit=1 tests_seen=39 failed=3 compile_failed=False`

- PhysicsSnapshotTypesTest > no physics field is in a network mask, and every one is in the snapshot mask()[jvm]
- PhysicsNeverOnTheWireTest > a physics component leaving a live entity is named with an empty mask and nothing else()[jvm]
- PhysicsNeverOnTheWireTest > creates and deltas of moving bodies write no physics bits at all()[jvm]

### M3-rebuild-reuses-world

```diff
diff --git a/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt b/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
index c85d379..c551ae7 100644
--- a/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
+++ b/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
@@ -559,9 +559,8 @@ internal class Box2DPhysicsWorld(
     override fun rebuildFrom(world: World, netIds: NetIdIndex) {
         checkOpen()
         // A fresh Box2D world, not the old one emptied: see the class KDoc.
-        B2World.destroyWorld(worldId)
+        for (slot in 0 until highWater) if (live[slot]) B2Body.destroyBody(bodyIds[slot])
         clearTable()
-        worldId = openWorld()
         PhysicsRebuildPlan.of(world, netIds).rebuild(::createBody)
         rebuildCount++
     }
```

`exit=1 tests_seen=36 failed=1 compile_failed=False`

- ChainStaticGeometryTest > a static chain rewinds correctly with bodies rolling down it()[jvm]

### M4-chain-change-unchecked

```diff
diff --git a/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt b/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
index c85d379..3924589 100644
--- a/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
+++ b/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
@@ -268,7 +268,7 @@ internal class Box2DPhysicsWorld(
         if (builtChain == null && chain != null) {
             throw StaticGeometryChangedException(owner, "a Chain was added after its body was built")
         }
-        if (builtChain != null && (chain == null || !chain.vertices.contentEquals(builtChain))) {
+        if (builtChain != null && chain == null) {
             throw StaticGeometryChangedException(owner, "its Chain changed after its body was built")
         }
         if (signatureChanged(slot, component, checkNotNull(entity), world)) {
```

`exit=1 tests_seen=36 failed=2 compile_failed=False`

- ChainStaticGeometryTest > mutating a chain's array in place is refused too()[jvm]
- ChainStaticGeometryTest > editing a chain's vertices is refused at the next tick, naming the entity()[jvm]

### M5-no-write-back

```diff
diff --git a/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt b/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
index c85d379..564d5e1 100644
--- a/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
+++ b/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
@@ -159,7 +159,6 @@ internal class Box2DPhysicsWorld(
     override fun stepOneTick() {
         checkOpen()
         B2World.step(worldId, tickSeconds, settings.subSteps)
-        writeBack()
         if (listeners.isNotEmpty()) dispatchContacts()
     }
 
```

`exit=1 tests_seen=36 failed=11 compile_failed=False`

- Box2DDeterminismTest > two runs of the same scene hash identically at every tick()[jvm]
- Box2DPhysicsWorldTest > one tick is one fixed step of 1 over tickRate seconds with the configured sub-steps()[jvm]
- Box2DPhysicsWorldTest > a Teleport component moves the body once and the solver carries on from there()[jvm]
- Box2DPhysicsWorldTest > setAwake puts a body to sleep and the component says so()[jvm]
- Box2DPhysicsWorldTest > editing a shape component rebuilds the body from the new shape()[jvm]
- Box2DPhysicsWorldTest > a kinematic body moves at its velocity and ignores gravity()[jvm]
- Box2DPhysicsWorldTest > a sensor reports a body passing through it and never stops it()[jvm]
- Box2DPhysicsWorldTest > the solved pose and velocity are copied into PhysicsBody every tick()[jvm]
- Box2DRewindTest > how far a contact-heavy scene departs from the unrewound run()[jvm]
- Box2DRewindTest > a spinning scene departs too, because an angle does not round-trip through Box2D()[jvm]
- PhysicsNeverOnTheWireTest > creates and deltas of moving bodies write no physics bits at all()[jvm]

### M6-wall-clock-in-step

```diff
diff --git a/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt b/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
index c85d379..38fb31c 100644
--- a/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
+++ b/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
@@ -158,7 +158,7 @@ internal class Box2DPhysicsWorld(
 
     override fun stepOneTick() {
         checkOpen()
-        B2World.step(worldId, tickSeconds, settings.subSteps)
+        B2World.step(worldId, tickSeconds, settings.subSteps + (System.nanoTime() and 1L).toInt())
         writeBack()
         if (listeners.isNotEmpty()) dispatchContacts()
     }
```

`exit=1 tests_seen=36 failed=5 compile_failed=False`

- Box2DDeterminismTest > two runs of the same scene hash identically at every tick()[jvm]
- Box2DPhysicsWorldTest > one tick is one fixed step of 1 over tickRate seconds with the configured sub-steps()[jvm]
- Box2DRewindTest > without contacts or rotation a restored run replays the original exactly()[jvm]
- Box2DRewindTest > a restored run hashes identically to a run that rebuilt from the same components()[jvm]
- ChainStaticGeometryTest > a static chain rewinds correctly with bodies rolling down it()[jvm]

### M6a-wall-clock-in-open

```diff
diff --git a/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt b/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
index c85d379..b64bd07 100644
--- a/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
+++ b/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
@@ -593,7 +593,7 @@ internal class Box2DPhysicsWorld(
     private fun openWorld(): Long {
         val def = allocate(B2WorldDef.b2WorldDef())
         B2World.defaultWorldDef(def)
-        B2WorldDef.setGravity(def, vec(vecA, settings.gravityX, settings.gravityY))
+        B2WorldDef.setGravity(def, vec(vecA, settings.gravityX, settings.gravityY + (System.nanoTime() % 7) * 1e-6f))
         B2WorldDef.setEnableSleep(def, settings.enableSleep)
         B2WorldDef.setEnableContinuous(def, settings.enableContinuous)
         val id = B2World.createWorld(def)
```

`exit=1 tests_seen=36 failed=2 compile_failed=False`

- Box2DRewindTest > a restored run hashes identically to a run that rebuilt from the same components()[jvm]
- ChainStaticGeometryTest > a static chain rewinds correctly with bodies rolling down it()[jvm]

### M7-edited-body-built-asleep

```diff
diff --git a/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt b/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
index c85d379..2654764 100644
--- a/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
+++ b/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
@@ -277,7 +277,6 @@ internal class Box2DPhysicsWorld(
             // is built awake: a resting body whose shape grew into the floor must be pushed out,
             // and a body built asleep would sit there overlapping it until something woke it.
             destroySlot(slot)
-            component.awake = true
             component.handle = BodyHandle.NONE
         }
     }
```

`exit=1 tests_seen=36 failed=1 compile_failed=False`

- Box2DPhysicsWorldTest > editing a shape component rebuilds the body from the new shape()[jvm]

### M8-no-netid-check

```diff
diff --git a/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt b/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
index c85d379..d42ed7b 100644
--- a/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
+++ b/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
@@ -247,7 +247,7 @@ internal class Box2DPhysicsWorld(
         creator.withBody = 0
         netIds.forEachLive(creator)
         creator.world = null
-        requireEveryBodyHasANetId(world, netIds, creator.withBody)
+
     }
 
     /** Destroys, rebuilds or refuses one existing body according to its entity's components. */
```

`exit=1 tests_seen=36 failed=1 compile_failed=False`

- Box2DPhysicsWorldTest > a PhysicsBody with no NetId fails loudly instead of silently never moving()[jvm]

### M9-destroy-leaks-body

```diff
diff --git a/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt b/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
index c85d379..87766ca 100644
--- a/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
+++ b/udea-physics2d/src/box2dMain/kotlin/dev/wildware/udea/physics2d/Box2DPhysicsWorld.kt
@@ -624,7 +624,6 @@ internal class Box2DPhysicsWorld(
     }
 
     private fun destroySlot(slot: Int) {
-        B2Body.destroyBody(bodyIds[slot])
         live[slot] = false
         bodyIds[slot] = 0L
         components[slot] = null
```

`exit=1 tests_seen=36 failed=2 compile_failed=False`

- Box2DNativeMemoryTest > creating and destroying bodies over and over leaves Box2D holding nothing()[jvm]
- Box2DPhysicsWorldTest > editing a shape component rebuilds the body from the new shape()[jvm]

## Regenerated files

- **`udea-core/net-protocol.lock`: new, written by `sh gradlew :udea-core:udeaWriteProtocolLock`.** `udea-core` had no replicated component before, so it had no lock. The file's non-comment content:

```
lockFormat 1
protoHash 0x0826
component 22 dev.wildware.udea.core.physics.Box
  field 0 halfHeight f32:32
  field 1 halfWidth f32:32
component 23 dev.wildware.udea.core.physics.Capsule
  field 0 halfHeight f32:32
  field 1 radius f32:32
component 24 dev.wildware.udea.core.physics.Circle
  field 0 radius f32:32
component 25 dev.wildware.udea.core.physics.PhysicsBody
  field 0 angle f32:32
  field 1 angularVelocity f32:32
  field 2 awake bool:1
  field 3 isSensor bool:1
  field 4 kind enum:32:Static,Kinematic,Dynamic
  field 5 linearX f32:32
  field 6 linearY f32:32
  field 7 x f32:32
  field 8 y f32:32
component 26 dev.wildware.udea.core.physics.Teleport
  field 0 angle f32:32
  field 1 x f32:32
  field 2 y f32:32
```

- **`net-components.lock`: five names appended.** No task writes this file. Its header says the list is "made in a reviewed diff rather than by a processor", so the lead's "only with their tasks" guard has no task to apply here. I appended the five names at the end, where they sort. **Id diff: before, ids 0-21 (`CharacterView` to `codegen.fixtures.QuantisedProbe`). After, the same 0-21 unchanged, plus 22 `Box`, 23 `Capsule`, 24 `Circle`, 25 `PhysicsBody` and 26 `Teleport`. No existing id moved.**
- **`udea-codegen/net-protocol.lock`, `udea-codegen/src/test/resources/expected-generated-hashes.txt` and `moba/game/net-protocol.lock`: unchanged.** `git diff --stat 5821d25 HEAD` over those three paths is empty, and `udeaCheckProtocolLock` passes for `udea-core`, `udea-codegen` and `moba:game` (above). No fixture id moved, so neither task had anything to regenerate.
