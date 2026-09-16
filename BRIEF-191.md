757a7f2
# BRIEF-191: save and load levels as binary Fleks snapshots with kotlinx CBOR

Branch `issue-191-level-save-load`, rebased onto `origin/example` at `5e37c99`.
Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a2f9401b541e763b3`.

`757a7f2` is the last commit of the change (the review round 1 fix, section 0). The commit after it changes
only this file. Sections 1 to 8 were produced at `131bca8`, the round-1 SHA, and name it where they quote a run.
Every artefact named below is under `/srv/ssd1/workspace/Udea/build/issue191-evidence/` (called `EV/`).

---

## 0. Review round 1

Finding: public declarations nothing outside `udea-core` reads. `git grep` over every module found no reader
outside `udea-core` for any of them. `udea-core`'s own tests use `LevelComponentModule.discover`, which
`internal` still allows. Now `internal`:
- `LevelComponent.type`, `LevelComponent.serializer`, `LevelComponent.serialName`. The constructor stays
  public for generated code in other modules.
- `LevelComponentModule.discover`.
- `Level.entityCount`.

Nothing else changed. `sh gradlew build` at `757a7f2`, no exclusions (`EV/build-full-r2.log`):

```
BUILD SUCCESSFUL in 1m 10s
211 actionable tasks: 56 executed, 155 up-to-date
exit=0
```

Tests summed from the JUnit XML afterwards (`EV/test-count-r2.txt`):

```
tests 2549 skipped 0 failures 0 errors 0
```

---

## 1. The evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew :moba:test --tests 'dev.wildware.moba.level.LevelSaveLoadProofTest' --rerun -Pudea.levelEvidenceDir=/srv/ssd1/workspace/Udea/build/issue191-evidence
```

It plays the real moba level until a fight is at its busiest (a death, casts in flight, effects applied,
hit flashes, and an action already queued on the barrier), saves a level, loads it into a brand-new game,
and compares the two worlds four ways: `WorldHasher.hash`, the component classes per entity, a second save
byte for byte, and every JVM field of every component (`FieldByField`). It also checks one tick later,
and that a component no list names refuses the save by name.

**Green at `131bca8`** (`EV/evidence-green.log`, `EV/transcript.txt`, the level itself in `EV/level.udealevel`):

```
saved at      tick=t1309 entities=28 (from 29) activeCasts=5 appliedEffects=26 spriteViews=4 queuedBarrierActions=1
level bytes   47459
saved hash    3871816b9807a4c9
fresh hash    ee91f34ec09cff0c (the fresh world, before the load)
loaded hash   3871816b9807a4c9
entities      saved=29 loaded=29
components    saved=210 loaded=210
census equal  true
resave equal  true
field by field differences  0
component kinds saved:
  dev.wildware.moba.CharacterView
  dev.wildware.moba.Player
  dev.wildware.moba.Position
  dev.wildware.moba.SpriteView
  dev.wildware.moba.ability.Combatant
  dev.wildware.moba.ability.Corpse
  dev.wildware.moba.ability.Motion
  dev.wildware.moba.ability.Projectile
  dev.wildware.moba.item.Inventory
  dev.wildware.moba.lane.LaneCreep
  dev.wildware.moba.lane.LaneState
  dev.wildware.moba.lane.LastHit
  dev.wildware.moba.lane.Tower
  dev.wildware.moba.lane.Wallet
  dev.wildware.moba.level.GameUnit
  dev.wildware.moba.match.MatchState
  dev.wildware.moba.match.Respawn
  dev.wildware.udea.gas.Abilities
  dev.wildware.udea.gas.Attributes
  dev.wildware.udea.gas.GameplayEffects
```

`entities=28` on the first line is counted before the save and `saved=29` after it: the save drains the
barrier first, and the queued action was a spawn. That is the point of the save going through the barrier.

**Red at `131bca8` with the NetId rebind removed** (`EV/evidence-red-M1.diff`, `EV/evidence-red-M1.log`,
`EV/red/transcript.txt`, first 11 lines):

```diff
diff --git a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
index 4287c1b..cc8f86e 100644
--- a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
+++ b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
@@ -152,7 +152,6 @@ public class LevelService internal constructor(
         val document = level.document
         world.loadSnapshot(document.world)
         netIds.restoreFrom(document.handles.toHandleState())
-        for (binding in document.netIds) netIds.bind(binding.entity, binding.netId)
         ctx.clock.moveTo(document.tick)
         streams.restoreFrom(document.rng, 0)
         // Box2D bodies are never level content, exactly as they are never snapshot content: a
```

```
> Task :moba:test FAILED
LevelSaveLoadProofTest > a match saved mid-fight loads into a fresh world identical to the one it was saved from() FAILED
LevelSaveLoadProofTest > the tick after loading a level is the tick after saving it() FAILED
3 tests completed, 2 failed
BUILD FAILED in 8s
```

```
saved at      tick=t1309 entities=28 (from 29) activeCasts=5 appliedEffects=26 spriteViews=4 queuedBarrierActions=1
level bytes   47459
saved hash    3871816b9807a4c9
fresh hash    ee91f34ec09cff0c (the fresh world, before the load)
loaded hash   891dc91ebdf704be
entities      saved=29 loaded=0
components    saved=210 loaded=0
census equal  false
resave equal  false
field by field differences  1
component kinds saved:
```

Section 6 has the mutation that is exactly the first acceptance criterion (a component left out, M11), and
the one that showed the first three comparisons were not enough (M12).

---

## 2. Summary

**What a level is.** `UdeaGame.levels` (a `LevelService`) saves the world to bytes and loads bytes back.
The file is Fleks' own `world.snapshot()`, encoded with kotlinx CBOR, plus what Fleks does not hold: the
NetId bindings and allocator state, the clock tick, every random stream, and each module's `LevelSection`.
CBOR writes field names, so a component that gains a field loads an old level with the default, and one that
loses a field skips it (tested).

**Which components.** `udea-codegen` generates `<Module>LevelComponents` (KotlinPoet, `%T` code blocks, one
`ServiceLoader` entry) listing every `@Serializable` Fleks component a module declares, in name order.
`udea-core`, `udea-gas` and `moba` run it. A world holding a component no list names refuses to save with
`LevelSaveException` naming the class and the entity. It is never dropped.

**Both save and load go through `SimBarrier`.** Load was decided by the lead; save was my call (issue comment).
Measured reason: a save beside the barrier missed a spawn queued during the last tick, and the loaded match
parted from the saved one a tick later. M6 shows the proof catches it.

**Loading** decodes and validates the whole file first (`read`), so a bad file never touches the world. Then
the barrier action restores entities, rebuilds `NetIdIndex`, moves the clock and streams, rebuilds physics
bodies, and puts back the sections.

**Things components point at but do not own**, via `UdeaModule.level(hooks)`:
- `hooks.reference`: `udea-gas` stores the attribute table as its names in order, and refuses a level
  saved against different attributes.
- `hooks.section`: `udea-gas` saves its effect-handle counter, because handles are never reused and are
  replicated state.

**Making real components saveable.** `@Serializable` on the moba, core and gas components. Hand-written
serializers where the plugin's would be wrong:
- `NetId`: through `ofRaw`'s reserved-bit check.
- `Chain`: a surrogate.
- `GameplayEffects`: live slots only, rebuilt with the capacity it needs.
- `SpriteView.animation`: `AssetId` by name.
- `PhysicsBody.handle`: `@Transient`, because it is rebuilt.

`Abilities` got a private constructor for the serializer and checks each slot index. `Attributes` checks its
arrays match its table.

**Decisions posted on #191** (comments 5700015107, 5700015314, 5700015533, 5700015788):
- Levels carry the clock and the streams.
- Save goes through the barrier.
- The reference and section hooks exist, and the service lives on `UdeaGame`, not `GameContext`.
- The Fleks recycle-order finding below is out of scope.

**A surprise, recorded as out of scope.** Fleks' `loadSnapshot` restores entity ids and versions but not the
order its free list recycles ids. After the first recycled spawn, the two worlds hold the same NetIds under
different Fleks ids. Some moba systems sum forces in family order, so positions drift in the fourth decimal
after a few hundred ticks. A rewind has the same property. So the continuation proofs are:
- one tick on moba (`the tick after loading a level is the tick after saving it`);
- 40 ticks on a GAS-only game (`GasLevelTest`), where nothing depends on recycle order;
- the pixel comparison one tick on, in a separate process (section 5).

**Contracts.** `docs/contracts/` is untouched and `docs/contracts.lock` did not move. Level files do not add a
codec to the replication path: runtime snapshots still go only through `Replicator<T>`. `AGENTS.md` states
this split next to the "No separate snapshot codec" bullet, and `docs/module-graph.md`'s `udea-core` row names
the new kotlinx dependencies.

**Limits, stated plainly:**
- Fleks entity tags are refused, not saved: no list names them.
- `MatchService` is a per-tick mirror, not saved. It refills on the next tick (the missing score strip in
  `issue191-3`).
- The C1 mutation (the generator stops checking for a Fleks component) was caught by the build itself, not by
  my test. `udea-core`'s private `ChainSurrogate` is `@Serializable` but not a component. With the filter
  removed, the processor reports it as an unlistable component, and `:udea-core:kspKotlin` fails first
  (`EV/mutations/C1-no-component-filter.diff` and `.log`):

```
e: [ksp] /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a2f9401b541e763b3/udea-core/src/main/kotlin/dev/wildware/udea/core/physics/PhysicsComponents.kt:200: dev.wildware.udea.core.physics.ChainSurrogate is a @Serializable Fleks component, but a level file cannot list it because it is private. Make it a concrete, non-private class, or remove @Serializable if it is never saved.
```

---

## 3. `sh gradlew build`

At `131bca8`, no exclusions (`EV/build-full.log`):

```
BUILD SUCCESSFUL in 1m 39s
211 actionable tasks: 50 executed, 3 from cache, 158 up-to-date
exit=0
```

The tests in that run, summed from every JUnit XML under the worktree's `build/test-results/test/` directories
afterwards (`EV/test-count.txt`):

```
tests 2549 skipped 0 failures 0 errors 0
```

The first full build at this branch failed. `udeaVerifyDeterminism` flagged two `HashSet`s in
`LevelService.validate` (DET004). They were membership checks only, and are now a per-index `BooleanArray`
and a `LinkedHashSet`. The rerun above is green.

**GL, under xvfb with GL required** (`EV/gl-tests.log`). My change does not touch `udea-render`, but
`LevelShot` opens a context, so I ran them. The last two lines of the log were appended afterwards by a sum over
each task's JUnit XML, not printed by Gradle. Every Gradle command in this brief was typed as `./gradlew` with the
executable bit set locally (never staged) and `JAVA_HOME` at 21.0.11-tem:

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true --rerun
```
```
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest
BUILD SUCCESSFUL in 10s
udea-render/build/test-results/udeaGlTest tests 21 skipped 0 failures 0
udea-agent-host/build/test-results/udeaAgentGlTest tests 8 skipped 0 failures 0
```

**Module graph verifiers** (`EV/verifiers.log`): `udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd`

```
BUILD SUCCESSFUL in 3s
```

---

## 4. Tests added

- `udea-core` `LevelServiceTest` (14):
  - round trip with Entity and NetId references, freed ids, an entity with no NetId, clock, streams and the
    next allocations
  - physics rebuilt
  - re-save byte-identical
  - an unlisted component refused by name
  - save and load wait for the barrier
  - another format version refused
  - garbage refused without touching the world
  - an unknown component class refused
  - a field added and a field removed still load
  - sections round trip, a missing section is refused, and duplicate section names are refused
  - contradictory ids refused
- `udea-gas` `GasLevelTest` (2):
  - 40 ticks of continuation with a grown effect list, a mid-channel ability and the next effect handle
  - a level saved against other attributes is refused, naming them
- `udea-codegen` `LevelIndexTest` (4):
  - only `@Serializable` Fleks components are listed, in name order, with the service file at the path
    ServiceLoader reads
  - no list for a module without any
  - a private one is a located error
  - components with no module name are an error
- `moba` `LevelSaveLoadProofTest` (3), the evidence command.
- `moba` `LevelShot` plus `:moba:runLevelShot` / `:moba:runLevelShotSave`, the picture harness (section 5).
  Run by name, not on `check`, for the GL reason `runMatchShot` gives.

TDD, honestly: the moba proof test came first and failed to compile (no `levels`), then failed on the
continuation for the barrier reason above. The core, gas and codegen unit tests were written after the
implementation, so every one is backed by a mutation in section 6 rather than by having watched it fail first.

---

## 5. Images

All in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`. Made by
`xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew :moba:runLevelShot -Pudea.levelshot.dir=/srv/ssd1/workspace/Udea/build/issue191-evidence/levelshot`.
It saves in one JVM and loads in a second, so nothing in the loaded picture can be left over from the saved game.

- `issue191-1-saved-mid-fight-tick420.png`: the real match at tick 420, paused and saved; camera on the player.
- `issue191-2-fresh-game-before-load.png`: the second process before loading, empty grass. Proves the next
  picture came from the file.
- `issue191-3-loaded-from-file-tick420.png`: the same fight, loaded from the file. It differs from shot 1 only
  in the score strip (rows 18-49), which `MatchSystem` republishes from the saved `MatchState` on the next tick.
- `issue191-4-saved-game-tick421.png`: the saved game one tick on.
- `issue191-5-loaded-game-tick421.png`: the loaded game one tick on, pixel-identical to shot 4. The score strip
  is back.
- `issue191-sequence.png`: the five tiled.

The gallery copies are from the first run. The run below, at `131bca8`, rewrote `EV/levelshot/*.png` and printed
the same counts (`EV/levelshot-run.log`). The harness exits non-zero unless the tick-421 pair is identical and
the loaded picture differs from the fresh one:

```
[level.shot] saved 77327 bytes at tick 420, camera at (-74.06404, 12.005532)
[level.shot] wrote /srv/ssd1/workspace/Udea/build/issue191-evidence/levelshot/saved.png 1280x720 at tick 420
[level.shot] wrote /srv/ssd1/workspace/Udea/build/issue191-evidence/levelshot/saved-next.png 1280x720 at tick 421
[level.shot] wrote /srv/ssd1/workspace/Udea/build/issue191-evidence/levelshot/fresh.png 1280x720 at tick 0
[level.shot] loaded 77327 bytes; clock now 420
[level.shot] wrote /srv/ssd1/workspace/Udea/build/issue191-evidence/levelshot/loaded.png 1280x720 at tick 420
[level.shot] wrote /srv/ssd1/workspace/Udea/build/issue191-evidence/levelshot/loaded-next.png 1280x720 at tick 421
[level.shot] fresh.png vs loaded.png: 182357 of 921600 pixels differ, rows 0-702 from the top
[level.shot] saved.png vs loaded.png: 40960 of 921600 pixels differ, rows 18-49 from the top
[level.shot] saved-next.png vs loaded-next.png: 0 of 921600 pixels differ
BUILD SUCCESSFUL in 14s
```

---

## 6. Mutations, with the literal diffs

Each diff is the `git diff` saved at the time of the run (`EV/mutations/<id>.diff`), and each failing list is
grepped from that run's log. Everything except the section 1 red run was done before the rebase onto `5e37c99`
and before the `validate` change in section 3. The hunks' text is unchanged at `131bca8`, but their line numbers
may be off by a few. M1-M10 ran against a 13-test `LevelServiceTest`: the 14th test was added with M13. After
every mutation the file was restored with `git checkout`, and `git status` showed only the `gradlew` mode bit.

### M1: Load forgets to re-bind NetIds to entities

```diff
diff --git a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
index d770854..667a219 100644
--- a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
+++ b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
@@ -152,7 +152,6 @@ public class LevelService internal constructor(
         val document = level.document
         world.loadSnapshot(document.world)
         netIds.restoreFrom(document.handles.toHandleState())
-        for (binding in document.netIds) netIds.bind(binding.entity, binding.netId)
         ctx.clock.moveTo(document.tick)
         streams.restoreFrom(document.rng, 0)
         // Box2D bodies are never level content, exactly as they are never snapshot content: a
```

`EV/mutations/M1-no-rebind.log`:

```
LevelServiceTest > a loaded body is rebuilt in physics from its component() FAILED
LevelServiceTest > saving the loaded world again reproduces the file byte for byte() FAILED
LevelServiceTest > a level loads into a fresh game as the world, ids, clock and streams it was saved from() FAILED
```

`EV/mutations/M1-no-rebind-evidence.log`:

```
LevelSaveLoadProofTest > a match saved mid-fight loads into a fresh world identical to the one it was saved from() FAILED
LevelSaveLoadProofTest > the tick after loading a level is the tick after saving it() FAILED
```

### M2: Load leaves the clock where it was

```diff
diff --git a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
index d770854..6cee78f 100644
--- a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
+++ b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
@@ -153,7 +153,6 @@ public class LevelService internal constructor(
         world.loadSnapshot(document.world)
         netIds.restoreFrom(document.handles.toHandleState())
         for (binding in document.netIds) netIds.bind(binding.entity, binding.netId)
-        ctx.clock.moveTo(document.tick)
         streams.restoreFrom(document.rng, 0)
         // Box2D bodies are never level content, exactly as they are never snapshot content: a
         // body's handle is `@Transient` and the bodies come back from `PhysicsBody` and the shape
```

`EV/mutations/M2-no-clock.log`:

```
LevelServiceTest > saving the loaded world again reproduces the file byte for byte() FAILED
LevelServiceTest > a level loads into a fresh game as the world, ids, clock and streams it was saved from() FAILED
LevelServiceTest > a load waits for the barrier() FAILED
```

### M3: Load leaves the random streams where they were

```diff
diff --git a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
index d770854..7e2a550 100644
--- a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
+++ b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
@@ -154,7 +154,6 @@ public class LevelService internal constructor(
         netIds.restoreFrom(document.handles.toHandleState())
         for (binding in document.netIds) netIds.bind(binding.entity, binding.netId)
         ctx.clock.moveTo(document.tick)
-        streams.restoreFrom(document.rng, 0)
         // Box2D bodies are never level content, exactly as they are never snapshot content: a
         // body's handle is `@Transient` and the bodies come back from `PhysicsBody` and the shape
         // components in one deterministic pass, after every component is in place.
```

`EV/mutations/M3-no-streams.log`:

```
LevelServiceTest > saving the loaded world again reproduces the file byte for byte() FAILED
LevelServiceTest > a level loads into a fresh game as the world, ids, clock and streams it was saved from() FAILED
```

### M4: Load keeps the NetId allocator (free queue, watermarks) it had

```diff
diff --git a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
index d770854..ea9e431 100644
--- a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
+++ b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
@@ -151,7 +151,6 @@ public class LevelService internal constructor(
     internal fun apply(level: Level) {
         val document = level.document
         world.loadSnapshot(document.world)
-        netIds.restoreFrom(document.handles.toHandleState())
         for (binding in document.netIds) netIds.bind(binding.entity, binding.netId)
         ctx.clock.moveTo(document.tick)
         streams.restoreFrom(document.rng, 0)
```

`EV/mutations/M4-no-handles.log`:

```
LevelServiceTest > saving the loaded world again reproduces the file byte for byte() FAILED
LevelServiceTest > a level loads into a fresh game as the world, ids, clock and streams it was saved from() FAILED
```

### M5: Load does not rebuild physics bodies

```diff
diff --git a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
index d770854..300d09f 100644
--- a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
+++ b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
@@ -158,7 +158,6 @@ public class LevelService internal constructor(
         // Box2D bodies are never level content, exactly as they are never snapshot content: a
         // body's handle is `@Transient` and the bodies come back from `PhysicsBody` and the shape
         // components in one deterministic pass, after every component is in place.
-        ctx.physics.rebuildFrom(world, netIds)
         for (section in level.sections) section.load(world)
     }
 
```

`EV/mutations/M5-no-physics.log`:

```
LevelServiceTest > a loaded body is rebuilt in physics from its component() FAILED
```

### M6: Save reads the world immediately instead of queuing on the barrier

```diff
diff --git a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
index d770854..1092e41 100644
--- a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
+++ b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
@@ -75,7 +75,7 @@ public class LevelService internal constructor(
      *   or a [LevelSaveException] naming the component or tag a level cannot carry.
      */
     public fun save(barrier: SimBarrier): LevelAction<ByteArray> =
-        LevelAction("save level") { encode() }.also(barrier::submit)
+        LevelAction("save level") { encode() }.also { it.apply(world, ctx) }
 
     internal fun encode(): ByteArray {
         val format = format
```

`EV/mutations/M6-save-beside-barrier.log`:

```
LevelServiceTest > a save waits for the barrier, and sees what was queued ahead of it() FAILED
LevelServiceTest > a component no module lists refuses the save and names the class() FAILED
```

`EV/mutations/M6-save-beside-barrier-evidence.log`:

```
LevelSaveLoadProofTest > saving a match that holds a component no level can carry fails naming it() FAILED
LevelSaveLoadProofTest > a match saved mid-fight loads into a fresh world identical to the one it was saved from() FAILED
LevelSaveLoadProofTest > the tick after loading a level is the tick after saving it() FAILED
```

### M7: Load replaces the world immediately instead of queuing on the barrier

```diff
diff --git a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
index d770854..10fef56 100644
--- a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
+++ b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
@@ -146,7 +146,7 @@ public class LevelService internal constructor(
      * @return the queued action, whose [LevelAction.outcome] completes with [level] once it applied.
      */
     public fun load(level: Level, barrier: SimBarrier): LevelAction<Level> =
-        LevelAction("load level saved at ${level.tick}") { level.also(::apply) }.also(barrier::submit)
+        LevelAction("load level saved at ${level.tick}") { level.also(::apply) }.also { it.apply(world, ctx) }
 
     internal fun apply(level: Level) {
         val document = level.document
```

`EV/mutations/M7-load-beside-barrier.log`:

```
LevelServiceTest > a load waits for the barrier() FAILED
```

### M8: Module sections are decoded but never put back

```diff
diff --git a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
index d770854..1885965 100644
--- a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
+++ b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
@@ -159,7 +159,6 @@ public class LevelService internal constructor(
         // body's handle is `@Transient` and the bodies come back from `PhysicsBody` and the shape
         // components in one deterministic pass, after every component is in place.
         ctx.physics.rebuildFrom(world, netIds)
-        for (section in level.sections) section.load(world)
     }
 
     private fun saveable(format: LevelFormat, entity: Entity, snapshot: Snapshot): Snapshot {
```

`EV/mutations/M8-no-section-load.log`:

```
LevelServiceTest > a section saves world-level state and puts it back after the entities() FAILED
```

### M9: Format version is not checked

```diff
diff --git a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
index d770854..8d0070c 100644
--- a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
+++ b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
@@ -123,7 +123,7 @@ public class LevelService internal constructor(
     public fun read(bytes: ByteArray): Level {
         val format = format
         val header = decoding { format.decodeHeader(bytes) }
-        if (header.formatVersion != LevelFormat.VERSION) {
+        if (false) {
             throw LevelFormatException(
                 "level format version ${header.formatVersion} cannot be read by this build, which " +
                     "reads version ${LevelFormat.VERSION}",
```

`EV/mutations/M9-no-version-check.log`:

```
LevelServiceTest > a level from another format version is refused by number, before anything else is read() FAILED
```

### M10: A component no list names is silently skipped instead of refused

```diff
diff --git a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
index d770854..dc5f14a 100644
--- a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
+++ b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
@@ -171,11 +171,7 @@ public class LevelService internal constructor(
         }
         val named = ArrayList<Pair<String, Component<out Any>>>(snapshot.components.size)
         for (component in snapshot.components) {
-            val name = format.serialNameOf(component) ?: throw LevelSaveException(
-                "entity $entity holds ${component::class.qualifiedName}, which no generated level " +
-                    "component list names. Mark it @Serializable in a module that runs " +
-                    "udea-codegen, or keep it out of worlds that are saved as levels.",
-            )
+            val name = format.serialNameOf(component) ?: continue
             named += name to component
         }
         // Fleks orders a snapshot's components by component-type id, which is assigned in the
```

`EV/mutations/M10-drop-unlisted.log`:

```
LevelServiceTest > a component no module lists refuses the save and names the class() FAILED
```

`EV/mutations/M10-drop-unlisted-evidence.log`:

```
LevelSaveLoadProofTest > saving a match that holds a component no level can carry fails naming it() FAILED
```

### M11: The generator leaves one real component (SpriteView) out of the list

```diff
diff --git a/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/level/LevelComponentScanner.kt b/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/level/LevelComponentScanner.kt
index 08b6905..895f894 100644
--- a/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/level/LevelComponentScanner.kt
+++ b/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/level/LevelComponentScanner.kt
@@ -36,6 +36,7 @@ internal class LevelComponentScanner(private val logger: KSPLogger) {
         resolver.getSymbolsWithAnnotation(LevelNames.SERIALIZABLE)
             .filterIsInstance<KSClassDeclaration>()
             .filter(::isFleksComponent)
+            .filter { it.simpleName.asString() != "SpriteView" }
             .sortedBy { it.qualifiedName?.asString() ?: it.simpleName.asString() }
             .mapNotNull(::listable)
             .toList()
```

`EV/mutations/M11-generator-omits-SpriteView-evidence.log`:

```
LevelSaveLoadProofTest > a match saved mid-fight loads into a fresh world identical to the one it was saved from() FAILED
LevelSaveLoadProofTest > the tick after loading a level is the tick after saving it() FAILED
```

### M12: One field of a real component (GameUnit.movingTick) is never written

```diff
diff --git a/moba/src/main/kotlin/dev/wildware/moba/level/GameUnit.kt b/moba/src/main/kotlin/dev/wildware/moba/level/GameUnit.kt
index 81ec8b3..e1c1e41 100644
--- a/moba/src/main/kotlin/dev/wildware/moba/level/GameUnit.kt
+++ b/moba/src/main/kotlin/dev/wildware/moba/level/GameUnit.kt
@@ -87,7 +87,7 @@ public class GameUnit(
      * position - a per-entity scratch component whose only reader would be the animation. One
      * `Long` written by the one system that moves a unit is cheaper and cannot disagree with it.
      */
-    @JvmField public var movingTick: Long = Long.MIN_VALUE
+    @kotlinx.serialization.Transient @JvmField public var movingTick: Long = Long.MIN_VALUE
 
     /** The kind, resolved. Out of range means a blueprint wrote a `kind` no constant has. */
     public val unitKind: UnitKind get() = UnitKind.of(kind)
```

`EV/mutations/M12-field-not-saved-evidence.log`:

```
LevelSaveLoadProofTest > a match saved mid-fight loads into a fresh world identical to the one it was saved from() FAILED
```

### M13: A NetId bound twice, or bound while on the free queue, is accepted

```diff
diff --git a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
index 4287c1b..fa334e9 100644
--- a/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
+++ b/udea-core/src/main/kotlin/dev/wildware/udea/core/level/LevelService.kt
@@ -218,7 +218,7 @@ public class LevelService internal constructor(
         val bound = LinkedHashSet<Entity>()
         for (binding in document.netIds) {
             val index = binding.netId.index
-            if (binding.netId.isNone || index >= capacity || claimed[index]) {
+            if (binding.netId.isNone || index >= capacity) {
                 throw LevelFormatException("level binds ${binding.netId}, which is none, free, repeated or out of range")
             }
             claimed[index] = true
```

`EV/mutations/M13-no-claim-check.log`:

```
LevelServiceTest > a level whose ids contradict each other is refused before the world is touched() FAILED
```

### G1: udea-gas stops saving its effect-handle counter

```diff
diff --git a/udea-gas/src/main/kotlin/dev/wildware/udea/gas/GasModule.kt b/udea-gas/src/main/kotlin/dev/wildware/udea/gas/GasModule.kt
index 63f0733..a5b306c 100644
--- a/udea-gas/src/main/kotlin/dev/wildware/udea/gas/GasModule.kt
+++ b/udea-gas/src/main/kotlin/dev/wildware/udea/gas/GasModule.kt
@@ -149,6 +149,5 @@ public class GasModule(
      */
     override fun level(hooks: LevelHooks) {
         hooks.reference(AttributeTable::class, AttributeTableReference(attributes))
-        hooks.section(EffectHandleSection(handles))
     }
 }
```

`EV/mutations/G1-no-handle-section.log`:

```
GasLevelTest > a loaded game plays on exactly as the saved one does, new effect handles included() FAILED
```

### G2: Attribute table checked by count only, not by name

```diff
diff --git a/udea-gas/src/main/kotlin/dev/wildware/udea/gas/GasLevel.kt b/udea-gas/src/main/kotlin/dev/wildware/udea/gas/GasLevel.kt
index 098ee5d..e1c6b1f 100644
--- a/udea-gas/src/main/kotlin/dev/wildware/udea/gas/GasLevel.kt
+++ b/udea-gas/src/main/kotlin/dev/wildware/udea/gas/GasLevel.kt
@@ -40,7 +40,7 @@ internal class AttributeTableReference(private val table: AttributeTable) : KSer
 
     override fun deserialize(decoder: Decoder): AttributeTable {
         val saved = decoder.decodeSerializableValue(codec)
-        if (saved != names) {
+        if (saved.size != names.size) {
             throw SerializationException(
                 "the level was saved against attributes $saved, and this game declares $names",
             )
```

`EV/mutations/G2-attribute-names-unchecked.log`:

```
GasLevelTest > a level saved against other attributes is refused, naming them() FAILED
```

### C2: Generator lists components in declaration order, not by name

```diff
diff --git a/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/level/LevelComponentScanner.kt b/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/level/LevelComponentScanner.kt
index 08b6905..3b8b628 100644
--- a/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/level/LevelComponentScanner.kt
+++ b/udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/level/LevelComponentScanner.kt
@@ -36,7 +36,6 @@ internal class LevelComponentScanner(private val logger: KSPLogger) {
         resolver.getSymbolsWithAnnotation(LevelNames.SERIALIZABLE)
             .filterIsInstance<KSClassDeclaration>()
             .filter(::isFleksComponent)
-            .sortedBy { it.qualifiedName?.asString() ?: it.simpleName.asString() }
             .mapNotNull(::listable)
             .toList()
 
```

`EV/mutations/C2-no-sort.log`:

```
LevelIndexTest > every serializable Fleks component is listed, in ascending name order, and nothing else is(File) FAILED
```

---

## 7. The issue, criterion by criterion

1. **A real moba match saved mid-fight and loaded into a fresh world has an identical `WorldHasher.hash`, and
   the test fails if any component is left out.**
   - Hash: `LevelSaveLoadProofTest`, section 1 transcript (saved `3871816b9807a4c9` = loaded `3871816b9807a4c9`,
     fresh `ee91f34ec09cff0c`).
   - Left out: M11 (the generator omits `SpriteView`, a component outside the hash registry) turns it red.
   - A field left out: M12 turns it red.
   - Picture: images 1-5.
2. **Saving a world that holds a non-serializable component fails with an error naming that component.**
   - `saving a match that holds a component no level can carry fails naming it` (moba) and
     `a component no module lists refuses the save and names the class` (core). M10 turns both red.
   - Generator side: `LevelIndexTest` shows a non-`@Serializable` component is not listed.
3. **`AGENTS.md` states the runtime-snapshot / level-file split, and `sh gradlew build` is green.**
   - The paragraph under "No separate snapshot codec" in `AGENTS.md`.
   - Build: section 3.

---

## 8. Regenerated files

None. `udea-codegen/net-protocol.lock` and `udea-codegen/src/test/resources/expected-generated-hashes.txt` are
unchanged against `origin/example`. The change adds no `@Replicated` component, and the level list is a separate
generated file the codegen test fixtures do not produce. `udeaCheckProtocolLock` ran in the build above.
`docs/contracts.lock` is unchanged.
