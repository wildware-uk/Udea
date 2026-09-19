# The Fleks and LibGDX determinism audit

**Issue #151. Manual, one-off, and the reasoning column is the point.**

Spec section 7 says why this file exists, and it is not flattering to its sibling:

> The determinism ASM scanner produces false confidence — it catches direct calls but not
> nondeterminism laundered through Fleks internals, LibGDX math, HashMap iteration order, or
> float differences across JVMs, and its green light will be trusted.

So: `udeaVerifyDeterminism` going green is **not** evidence that the simulation is
deterministic. This file is the written-down version of what a green run leaves unchecked, and
the gate is the `WorldHasher` snapshot-equivalence test plus the cross-OS `replay-equality` job
in `.github/workflows/ci.yml`. When the two disagree, the replay result wins and the scanner
grows a rule.

- **Audited against:** Fleks `2.14+sha256:220b74bf865c68a5e99f9f340855148d9a078b7a1e158eaba82d35f7230441bf`,
  LibGDX `1.14.2` — the versions pinned in `determinism-allowlist.txt`, which
  `udeaVerifyDeterminism` compares to the resolved ones on every run and fails on drift
  (`ALLOW005`). An upgrade invalidates the rows below silently, so an upgrade has to fail the
  build until somebody re-reads them. Fleks is vendored source in `udea-fleks` since issue #215,
  so its version is the release it was copied from plus a digest of `udea-fleks/src/commonMain`:
  an edit to that source fails the pin exactly as a release bump does. Section 2.0 records the
  re-read the move to vendored source demanded.
- **Audited surface:** the members actually referenced from the source sets
  `DeterminismRules.SIMULATION_SCOPES` declares simulation. This is a **used-surface** audit,
  not a library review: a Fleks method nothing calls is not a determinism risk this project has.
- **Evidence:** `javap -p -c` over `Fleks-jvm-2.14.jar` and `gdx-1.14.2.jar` from the Gradle
  module cache, plus a `Math`/`StrictMath` probe run on this JDK. Every row says what was
  looked at. No row says "assumed fine"; `AuditTest` in `build-logic` parses this table and fails the build if one does.
  The gdx rows were first written against `1.13.5` and re-read against `1.14.2` in issue #186;
  section 3.0 records that re-read, because a pin that moves without a written reason is a pin
  somebody moved to make a build go green.

---

## 0. What the gate replays, and how to rebuild it

Since issue #172 the `replay-equality` legs and the `replay-equality-nightly` legs replay
**`moba`**, not a fixture world. That matters because the fixture world they used to replay,
`DriftWorld`, routes its trigonometry through `StrictMath` on purpose — it is written to be
deterministic, so a green matrix reported the health of its own fixture rather than of the game.
`DriftWorld` stays as the gate's **self-test**: it is what `:udea-replay:udeaReplayEqualityProof`
plants a one-ulp divergence into across five processes, and what `CrossPlatformDivergenceTest`
pins the rendered cross-platform failure against.

| | replays | checked in at | length |
| --- | --- | --- | --- |
| `replay-equality`, every push | `moba-3600.udearep` | `moba/desktop/src/test/resources/fixtures/` | 3600 ticks |
| `replay-equality-nightly` | `moba-36000.udearep` | the same directory | 36000 ticks |
| `:udea-replay:udeaReplayEqualityProof`, the self-test | `drift-3600.udearep` | `udea-replay/src/testFixtures/resources/fixtures/` | 3600 ticks |

### Rebuilding a fixture

A `.udearep` carries the `BuildIdentity` of the build that recorded it — root seed, `protoHash`,
asset graph hash, input schema hash — and a replay refuses it the moment any of the four moves.
`moba`'s `protoHash` moves whenever a replicated component is added or removed, its asset graph
hash whenever an asset changes, and its input schema hash whenever a key is rebound, so this is
ordinary gameplay work rather than a rare event. `:moba:desktop:test` fails on the machine that made the
change, with `MobaReplayFixturesCurrentTest` naming which identity field moved and what both
sides hold.

Two front doors, one reconciliation (`ReplayFixtures.reconcile`), so they cannot disagree about
what "stale" means or about what they write:

```
./gradlew :moba:desktop:udeaWriteReplayFixture                 # rebuilds moba's two, and nothing else
./gradlew :moba:desktop:test -Dupdate.replay.fixtures=true     # the --update-goldens-shaped route
./gradlew :udea-replay:udeaWriteReplayFixture          # the self-test world's two
```

Review the diff. The pilot is a `java.util.Random` LCG with a fixed seed, and that algorithm is in
the class's specification, so the same seed rebuilds the same input stream on any conforming JVM —
which is what makes a checked-in binary something a reviewer can reproduce rather than trust.
`MobaReplayEqualityTest` compares the checked-in input stream against a fresh recording sample for
sample on every push.

Nothing in CI runs these. Regenerating a fixture is how a gate gets silenced, so it is a command
somebody types on purpose — the same bargain `udeaWriteProtocolLock` strikes with
`net-protocol.lock`.

### Running the gate on one machine

```
./gradlew :moba:desktop:udeaReplayEqualityProof         # five processes: two honest legs agree, a planted one fails
./gradlew :udea-replay:udeaReplayEqualityProof  # the same shape over the self-test world
```

The planted half is the one that matters. A gate that has only ever been seen to pass is a gate
nobody has watched fail.

---

## 1. What the scanner structurally cannot see

Written first because it is the part people skip.

| Blind spot | Why the scan misses it | What does catch it |
| --- | --- | --- |
| Hash order across a class boundary | Kotlin **never** emits the concrete owner at an iteration site: `for ((k, v) in someHashMap)` compiles to `checkcast java/util/Map` + `INVOKEINTERFACE java/util/Map.entrySet` whatever the static type is (verified by `javap` on a planted probe). The concrete type appears only at the `NEW`. `DET004` therefore joins the two halves **at class level** - a class that constructs a hash-ordered collection *and* walks a map or set. A `HashMap` built in one class and iterated in another, or a class handed a map it did not build, is invisible. | Replay equality; `WorldHasher` over two runs with different insertion histories |
| Indirection | The scan sees direct references only. `helper()` calling `System.nanoTime()` in a module nobody declared simulation, called from a system, reports nothing. | Replay equality |
| Fleks internals | Nothing in `udea-fleks` is in a declared simulation scope, so no rule inspects Fleks' own bytecode. It is not declared one because the allowlist excuses a *referenced member* wherever it is referenced: excusing Fleks' own `Random.nextInt` would excuse it in every simulation scope. Section 2 below is the manual substitute, and `DET002` matches calls to the one Fleks surface that launders unseeded randomness (section 2.0). | This document; replay equality |
| Float differences across JVMs | Bytecode is identical on both platforms. That is exactly the failure mode `Math.sin` has (section 3.1). | The cross-OS `replay-equality` CI job, replaying `moba` since issue #172. Nothing else. |
| Iteration order of a `LinkedHashMap` fed in nondeterministic order | Insertion-ordered is only reproducible if the *insertions* are. A `LinkedHashMap` filled from a `HashSet` is as unstable as the `HashSet`. | Replay equality |
| A clock received through its interface | `DET001` and `DET003` match the system clocks by owner: `System`, `java.time`, `TimeSource.Monotonic` and its marks, and `Clock.System` from the stdlib and kotlinx-datetime (issue #217). A `kotlin.time.TimeSource` or `kotlin.time.Clock` parameter compiles to an interface call whose receiver the scan cannot see, and it may be a clock driven by the tick, so it is not matched. The reference that picks the system clock is matched wherever it is written in declared simulation; picked outside it and handed in, it is invisible. | Replay equality |
| A source set that compiles only to a klib | The scan reads JVM and Android bytecode (`DeterminismLayout`). That covers `commonMain` and every source set feeding those two targets, and nothing that reaches only `wasmJs` or iOS: a `TimeSource.Monotonic` read in `wasmJsMain`, or in a shared set such as `udea-core`'s `nonJvmMain`, produces no class file for ASM to read. `udea-gas`'s own `udeaVerifyGasTime` scans the source text of every `*Main` source set, so that module is covered there; the others are not. | Nothing today. The replay-equality legs replay `moba`, a JVM module, so a read that exists only on another target never runs under them |
| Anything reflective, or loaded by name | No reference exists in the bytecode to match. | Replay equality |

---

## 2. Fleks 2.14 — the used surface

### 2.0 The Maven-to-vendored re-read (issue #215)

Issue #215 replaced the `io.github.quillraven.fleks:Fleks:2.14` artifact with Fleks' own source,
vendored in `udea-fleks` from the `2.14` tag, and moved the pin to include a digest of that
source. `ALLOW005` failed the build as designed, and this is the re-read it demanded.

**What was compared.** The vendored `src/commonMain` against upstream's `2.14` tag: `diff -r`
reports no difference. Then the bytecode, because the rows below were written against
`Fleks-jvm-2.14.jar` and the vendored source is compiled here with this repository's Kotlin rather
than upstream's: `javap -p` over the seven classes these rows name most (`DefaultEntityProvider`,
`Family`, `collection.Bag`, `World`, `EntityService`, `collection.MutableEntityBag`,
`collection.BitArray`), in the Maven jar and in `udea-fleks/build/classes/kotlin/jvm/main`,
diffed per class.

**What moved, and why no verdict moves with it.** Every class has the same member count in both.
The only differing lines are the names of `internal` members, which Kotlin suffixes with the
module name: `getMutableSystems$Fleks` is `getMutableSystems$dev_wildware_udea_udea_fleks`, and
likewise across `Family`, `World` and `EntityService`. A name is not an order, a table or a
pool. The fields the rows below cite are unchanged: `recycledEntities` is still a
`kotlin.collections.ArrayDeque`, `Bag.values` still a `T[]`, `World.allFamilies` a `Family[]`,
`mutableSystems` a `java.util.ArrayList`, `injectables` and `tagCache` a `java.util.Map`.

**Found by the re-read, and added below.** A reference scan over every class in the vendored JVM
output finds exactly one thing the determinism rules key on: `MutableEntityBag.random` and
`randomOrNull` read `kotlin.random.Random.Default` and call `nextInt`. `Family.random` and
`randomOrNull` delegate to them. None is referenced from a declared simulation scope today, and
the used-surface audit never looked at them for that reason. But a system calling
`family.random()` makes no reference to `Random.Default` of its own, so `DET002` could not see
it. `DET002` now matches the calls themselves, and the row is `banned`.

The referenced members were enumerated by running `javap -p -c` over every compiled class of
`:udea-core` and `:udea-gas` and extracting `com/github/quillraven/fleks/*` targets, sorted by
call count. `:udea-net`'s declared prefixes (`prediction`, `input`) reference **no** Fleks
member at all. The high-frequency members are `ComponentType.getId` (94), `ComponentService.getHoldersBag` (90),
`Bag.get` (41), `Entity.getId` (40), `Bag.set` (33), `World.getCapacity` (30).

| Member | Verdict | Rule or replacement | Reasoning and evidence inspected |
| --- | --- | --- | --- |
| `Entity` id recycling (`DefaultEntityProvider.recycledEntities`) | deterministic | — | The field is `kotlin.collections.ArrayDeque<Entity>` (`javap -p DefaultEntityProvider`), not a hash set. Recycling is therefore a queue over the *history* of removals: the same sequence of create/remove calls yields the same id handed back, on any JVM. The order depends on the simulation's own call order, which is what determinism means. |
| `Family.getEntities` / `Family.iterator` iteration order | deterministic | — | `Family` stores `activeEntities: Bag<Entity>` and `mutableEntities: MutableEntityBag` (`javap -p Family`); `Bag`'s only storage is `private T[] values` (`javap -p collection.Bag`). Iteration is array index order, not hash order. Membership updates are driven by `onEntityCfgChanged`, so the array order is a function of the simulation's own add/remove sequence. |
| `Family.forEach` | deterministic | — | Delegates to the same `Bag` walk as above; `isIterating`/`isDirty` only defer *mutation*, not reorder the walk. Deferred removals go to `EntityService.delayedEntities`, itself a `MutableEntityBag`. |
| `Family.sort(Comparator)` | deterministic-if-used-thus | replacement: pass a **total** comparator | Sorting an array with a comparator that returns 0 for distinct entities leaves their relative order to the sort algorithm. Not referenced from any declared simulation scope today. If it becomes so, the comparator must break every tie — `Entity.id` is the obvious tiebreak. |
| `Family.associate` / `associateBy` (no destination) | deterministic | — | Kotlin's `associate` builds a `LinkedHashMap`, so the result is insertion-ordered over a deterministic walk. Not currently referenced from simulation. |
| `Family.random` / `randomOrNull`, `EntityBag.random` / `randomOrNull` | banned | DET002 | `MutableEntityBag.random` is `values[Random.nextInt(size)]`: `javap -c` over the vendored `MutableEntityBag` shows `getstatic kotlin/random/Random.Default` then `invokevirtual kotlin/random/Random$Default.nextInt`, and `Family` delegates to its `mutableEntities`. That is the unseeded default generator behind an ECS-looking call. The replacement is a pick by index from a named `RngService` stream over the family's ordered walk. Not referenced from any declared simulation scope today. |
| `Family.associateTo(M)` / `associateByTo(M)` | banned | DET004 | The destination is the caller's. Passing a `HashMap` reintroduces hash order behind an insertion-ordered-looking API. `DET004` catches the `NEW java/util/HashMap` *if* the constructing class also walks a map or set; a map built here and iterated elsewhere is in the blind-spot table above. |
| `World.getAllFamilies` | deterministic | — | The field is `Family[]` (`javap -p World`), populated in configuration order. System and family registration order is decided by `SimRegistry`, not by Fleks. |
| `World.getSystems` / `mutableSystems` | deterministic | — | `java.util.ArrayList<IntervalSystem>`, in the order `SystemConfiguration.add` was called. |
| `World.update` | deterministic | — | Walks `mutableSystems` in index order and calls `onUpdate` on each; there is no scheduling, no parallelism and no reordering anywhere in the path. |
| `World.snapshot()` / `snapshotOf` / `loadSnapshot` | deterministic-if-used-thus | replacement: never serialise the map's iteration order | Returns `java.util.Map<Entity, Snapshot>`. Kotlin's `mutableMapOf` builds a `LinkedHashMap`, so the map is insertion-ordered over the deterministic entity walk — but the *type* is `Map`, and a future Fleks release may change the implementation without a signature change. Treat the snapshot as keyed data, never as an ordered stream. `WorldHasher` must sort by `Entity.id` rather than trusting the map. |
| `World.getInjectables` / `unusedInjectables` | deterministic-if-used-thus | replacement: read by key only | `java.util.Map<String, Injectable>`, read by name at configuration time. Order never reaches simulation state; do not iterate it into anything a hash covers. |
| `World.getTagCache` | deterministic-if-used-thus | replacement: read by key only | `java.util.Map<Integer, UniqueId<?>>`. Same reasoning: a lookup table, not an ordered one. |
| `ComponentType.getId`, `ComponentService.getHoldersBag`, `ComponentsHolder.get`/`getOrNull`/`set`/`minusAssign` | deterministic | — | Component ids are assigned by `UniqueId` at class-init in declaration order and are dense ints; holders are indexed by that id into a `Bag`. Every one of these is an array index, and the 94 + 90 + 17 call sites are the hot path of the whole kernel. |
| `Bag.get` / `set` / `getSize` / `hasNoValueAtIndex`, `getValues` | deterministic | — | `private T[] values` plus a size. Growth is `copyOf` to a larger array preserving indices; there is no compaction and no rehash, so an index is stable for the life of the bag. |
| `collection.BitArray.set` / `clear` | deterministic | — | A `LongArray` bitset indexed by component id. Iteration by bit index is ascending and platform-independent. |
| `EntityService.getCompMasks`, `createId`, `updateId`, `delayRemoval`, `cleanupDelays` | deterministic | — | Ints and a `Bag<BitArray>`. `createId` is a monotone counter; `delayRemoval` defers removal to `cleanupDelays`, which drains `delayedEntities` (a `MutableEntityBag`) in index order. |
| `World.family` / `FamilyDefinition.all` | deterministic | — | Builds a `BitArray` from component ids. Family identity is the three bit arrays, so two definitions written in different orders are the same family. |
| `Injectable.getInjObj` / `setUsed`, `WorldCfgKt.configureWorld` | deterministic | — | Configuration-time only; nothing here runs inside a tick. |

**`associateTo`/`associateByTo` are banned for what the *caller* passes rather than for anything
Fleks does.** The random picks are banned for what Fleks does, and that row came from the
section 2.0 re-read.

---

## 3. LibGDX 1.14.2 — the used surface

> **Issue #212, 2026-09-18: `moba` resolves no LibGDX artifact any more, and this section has not
> been re-audited against what replaced it.** `UDEA-MG-009` fails the build if any `moba` project
> puts `com.badlogicgames.gdx:*` on a compile or runtime classpath, so every row below is now a
> statement about a library the game cannot reach. Three specific claims in it are **false as
> written** and are marked inline where they appear:
>
> - `dev.wildware.moba.physics.Box2DPhysicsWorld` is deleted, with the whole `physics` package,
>   `Box2DPhysicsWorldTest` and `MobaPhysicsProofTest` (spec D4). `MobaGame.definition()` never
>   installed `MobaPhysicsModule`, so the shipped game always ran on `NoOpPhysicsWorld`; what the
>   deletion removes is code nothing reached, not behaviour.
> - The 3.0 negative control was pointed at those classes. **It names a tree that no longer
>   exists**, so re-running it would return zero for a reason that says nothing about the grep.
>   Re-point it before trusting it again.
> - `:moba` is three projects (`:moba:game`, `:moba:desktop`, `:moba:android`), and the declared
>   simulation scope is `:moba:game`.
>
> What has **not** been done here is the audit of Kool's used surface, which is what the rows
> below will have to become. That is issue #211's tree and neither #212 nor this note closes it:
> until it is written, this section documents the risk the *old* renderer carried and nothing
> about the new one.
>
> **Issue #213, 2026-09-19: LibGDX is out of the tree entirely.** The old tree is deleted, the
> version catalog has no `com.badlogicgames` coordinate left, `UDEA-MG-009` now bans LibGDX from
> every project rather than only `moba`, and `determinism-allowlist.txt` no longer pins `gdx`,
> because there is no resolved version for these rows to drift from. The rows stay as the record
> of what the old renderer exposed simulation to.

**The declared simulation scopes of `:udea-core`, `:udea-gas` and `:udea-net` reference no
`com.badlogic.gdx` member at all.** That was checked twice: `grep -rn "^import com.badlogic"`
over their `src/main` returns nothing, and `javap -p -c` over their compiled classes yields zero
`com/badlogic/gdx` targets. The two mentions of Box2D in `udea-core` are KDoc prose in
`PhysicsWorld.kt` and `NoOpPhysicsWorld.kt`. The kernel talks to physics through its own
`PhysicsWorld` interface, and the Box2D implementation lived in `:moba`
(`dev.wildware.moba.physics.Box2DPhysicsWorld`). **Deleted in issue #212**; `NoOpPhysicsWorld` is
what the shipped game always resolved.

> **2026-09-19 (epic #199): Box2D is back, in its own module and not through LibGDX.**
> `:udea-physics2d` implements `PhysicsWorld` over `box2d-jni` (Box2D 3.1.1), is a declared
> simulation scope, and `DET005` now also matches `box2d.` and `box2dandroid.`. The two rows in
> section 3.2 that name those packages are the audit of it.

The rows below are therefore an audit of what simulation **would** be exposed to the moment
somebody reaches for the obvious LibGDX helper — which is why they carry rules and replacements
rather than "not applicable".

### 3.0 The 1.13.5 to 1.14.2 re-read (issue #186)

Issue #186 moved `gdx` from `1.13.5` to `1.14.2`, `ALLOW005` failed the build as designed, and
this is the re-read it demanded. It is written down rather than summarised as "checked", because
the next toolchain move will want to know *how much* was looked at, and a pin that moves without
a reason is a pin somebody moved to make a build go green.

**Method.** Both jars are in the Gradle module cache, so the re-read is a diff rather than a
reading: `javap -p -c` over each class this section makes a claim about, in `1.13.5` and in
`1.14.2`, then a per-class textual diff. That is stronger than re-reading the new jar alone,
because it cannot miss a change in a method nobody thought to re-check. Constant-pool indexes
were also normalised and diffed a second time, so a pool index shifting does not read as a
behaviour change and a behaviour change does not hide behind one.

**These audited classes disassemble identically:** `MathUtils`, `MathUtils$Sin`,
`RandomXS128` and `Pool`. Each produced zero diff lines between the two versions — so the float story
in 3.1, the `MathUtils.sin` table filled at class-init by `java.lang.Math.sin(double)`, the
`RandomXS128()` seeding and the LIFO `Pool.freeObjects` are the same bytecode the rows were
written against.

**What did move, and why no verdict moves with it:**

| Class | What changed between 1.13.5 and 1.14.2 | Why the row's verdict is unaffected |
| --- | --- | --- |
| `Vector2` | One added static: `public static final Vector2 One`, with the matching `<clinit>` store. Nothing else. | `len`, `len2`, `nor` and `angleDeg` are byte-identical, and those are what the rows are about. |
| `ObjectMap`, `IntMap`, `LongMap` | An added `putMissing`, which reads `locateKey` and `resize` like every other write path. | Additive. The iteration rows depend on the open-addressed `keyTable`/`valueTable` plus `shift`, all still present and unmodified, so order is still a function of hash, capacity and insertion history. |
| `ObjectSet` | One bytecode offset moved inside an existing method. | Same structure, same fields, same iteration. |
| `Array` | The copy constructor widened from `Array<T>` to `Array<? extends T>`, and `toString` builds with `java.lang.StringBuilder` instead of gdx's own. | Neither touches the backing array or its order. |
| `IntArray` | `truncate` now throws `IllegalArgumentException` on a negative argument; `toString` moved to `java.lang.StringBuilder`. | A new input rejection is not a new nondeterminism: it either throws or does exactly what it did before. |

**The two checks that make this a used-surface audit were re-run, not assumed.**
`grep -rn "^import com.badlogic"` over the three scopes' `src/main` still returns nothing — the
only matches for `com.badlogic` anywhere in them are the two KDoc sentences in `PhysicsWorld.kt`
and `NoOpPhysicsWorld.kt` this section already names. And `javap -p -c` over their compiled
classes yields zero `com/badlogic/gdx` targets — over every `.class` file in each scope's
`build/classes/kotlin/main`, at the class-file counts `udeaVerifyDeterminism` itself printed on
the same tree (223, 120 and 181 when issue #186 ran it), so the dump covered the whole scope and
not a subdirectory of it.

A grep that returns nothing is worth nothing until it has been seen returning something, so the
identical pipeline was pointed at `:moba`'s `dev.wildware.moba.physics` classes — a place in the
tree that used gdx when this was written — and returned 197 matching lines. The zeros above are
therefore about `udea-core`, `udea-gas` and `udea-net` rather than about the grep.

**That control is dead as written (issue #212).** `dev.wildware.moba.physics` is deleted, so
pointing the pipeline at it now returns zero — and a zero from a package that does not exist is
not evidence about the grep. Whoever next re-reads this section needs a *new* known-positive: a
place in the current tree that really does resolve the library being audited. There is no such
place for gdx any more, which is itself the point, and for Kool the honest candidate is
`udea-render`'s own `build/classes`.

### 3.1 The float story

This is the section that feeds the cross-OS gate, and it is the one genuine finding.

`java.lang.Math.sin` is permitted a 1-ulp error and is **not** required to be identical between
JVM implementations; `java.lang.StrictMath.sin` is bit-exact by specification. Probed directly
on this machine (Amazon Corretto 17.0.8, `x = i * 1e-5` for i in `[0, 2_000_000)`):

```
sin differs: 67912 of 2000000    atan2 differs: 0    sqrt differs: 0
first divergence x=0.030720000000000004  Math=0.03071516838978161  Strict=0.030715168389781614
```

`Math.sin` and `StrictMath.sin` disagree in the last bit on **3.4% of sampled inputs on a single
JVM**. Two JVMs are under no obligation to disagree in the same places. `Math.sqrt` is
correctly rounded by IEEE-754 and agrees exactly — which is why the `Vector2` length rows below
are green and the angle row is not.

**Decision taken:** simulation does not call trigonometry through LibGDX. Where simulation needs
an angle it stores and integrates the angle itself rather than recovering it from a vector, and
if a trig function becomes unavoidable in authoritative state it must be `StrictMath`, a fixed
lookup table this project owns and checks in, or fixed-point. This decision is only enforceable
by the `replay-equality` job: no bytecode rule can see a `Math.sin` that returns a different
bit on a different machine.

### 3.2 The rows

| Member | Verdict | Rule or replacement | Reasoning and evidence inspected |
| --- | --- | --- | --- |
| `Vector2.len()` / `len2()` | deterministic | — | `javap -c Vector2` shows `len()` as `Math.sqrt` over the float squares. `Math.sqrt` is correctly rounded per IEEE-754, so it is bit-identical on every conforming JVM; the probe above confirms zero divergence in 2M samples. |
| `Vector2.nor()` | deterministic | — | `nor()` calls `len()` and divides both fields by it (`javap -c`). Division and `sqrt` are both exactly specified. The zero-length branch is a plain comparison. |
| `Vector2.angleDeg()` | banned | replacement: store the angle in the component and integrate it; do not recover it from a vector | `javap -c` shows `Math.atan2`. `atan2` is permitted 2 ulp of error and is not specified to agree between implementations. It happens to agree with `StrictMath.atan2` on this JVM (0 of 2M) — which is exactly the kind of accident that makes this class of bug reach production before it is found. |
| `MathUtils.sin` / `cos` / `sinDeg` / `cosDeg` | banned | replacement: `StrictMath`, or a checked-in fixed table, or fixed-point | The table is `MathUtils$Sin`, and `javap -c 'com.badlogic.gdx.math.MathUtils$Sin'` shows it is filled at class-init by `java.lang.Math.sin(double)`. So the table's *contents* are a per-JVM-implementation artefact, and section 3.1 measured that implementation differing from the specified one 67,912 times. Two runners with different JITs or different vendors can build two different tables and then agree on every subsequent bit of arithmetic. |
| `MathUtils.random` (the field) and every `MathUtils.random*(...)` | banned | DET002 | The static field is initialised to `new RandomXS128()` (`javap -c MathUtils` static init), whose no-arg constructor seeds itself from `new java.util.Random().nextLong()` (`javap -c RandomXS128`) — that is, from the JVM's nanoTime-derived seed uniquifier. It is process-global, publicly mutable, and shared with every LibGDX internal that draws from it. `DET002` matches `MathUtils.random*` by name prefix. |
| `MathUtils.floor` / `ceil` / `round` / `clamp` / `abs` / `isEqual` | deterministic | — | `javap -c` shows `Math.floor`/`Math.abs`/`Math.signum` and integer arithmetic. All are exactly specified. |
| `ObjectMap` / `ObjectSet` iteration (`keys()`, `values()`, `entries()`) | banned | DET004 | `javap -p ObjectMap` shows open addressing over `K[] keyTable` / `V[] valueTable` with a `shift`. Iteration walks the table, so the order is a function of every key's `hashCode`, the capacity at the time, and the insertion history. For any key whose `hashCode` is identity-derived, that is a different order on every run of the same program. `DET004` lists `ObjectMap`/`ObjectSet` beside `HashMap`. |
| `IntMap` / `IntSet` / `LongMap` iteration | banned | DET004 | Same open-addressed table, keyed on the int itself. The hash is stable across runs, but the *order* still depends on capacity and insertion history, so two worlds that reached the same contents by different routes iterate differently. Listed in `DET004`. |
| `Array` / `IntArray` (gdx) iteration | deterministic | — | Backed by a plain array in insertion order; `removeIndex` shifts, `removeValueSwap` does not, and the difference is the caller's decision, not a hidden one. Not currently referenced from simulation. |
| `Pool.obtain` / `free` / `freeAll` | deterministic-if-used-thus | replacement: never let pooled identity or residual field values reach state | `javap -p Pool` shows `freeObjects: Array<T>` — a LIFO stack, so reuse order is a pure function of the obtain/free history and is reproducible. The risk is not the order: it is that `obtain()` returns an object whose fields hold the previous user's values unless `reset()` clears every one of them. That is a correctness bug that *looks* like nondeterminism. |
| `com.badlogic.gdx.physics.box2d.*` from **predicted** code | banned | DET005 | The solver accumulates state across steps and is not re-enterable from an arbitrary rewind point, so re-running a predicted tick against it does not reproduce the server's answer. The server owns the solver; prediction re-runs `CharacterMover`, which is closed-form. |
| `com.badlogic.gdx.physics.box2d.*` from **authoritative** code | deterministic-if-used-thus | replacement: `PhysicsWorld`, stepped exactly once per tick from `PhysicsStepSystem` | Box2D is deterministic for an identical sequence of identical steps in the same process, and *not* across platforms — it is C++ float code compiled per platform in `gdx-platform` natives. `:moba`'s `Box2DPhysicsWorld` was the only implementation and it was reached only through the `PhysicsWorld` interface. **Both are gone (issue #212):** the class is deleted and no `moba` project resolves gdx at all, so this row describes a risk the tree no longer carries. |
| `box2d.*` / `box2dandroid.*` (`box2d-jni`) from **predicted** code | banned | DET005 | The same reasoning as the LibGDX row above, for the Box2D 3.1.1 that `:udea-physics2d` binds to: the solver keeps warm-start impulses, contact manifolds and sleep timers across steps, none of which is in a snapshot, so a predicted tick re-run against it from a rewind point does not reproduce the server's answer. `box2d.` is the desktop jar and `box2dandroid.` the Android AAR; both are the one solver, so `DET005` names both. |
| `box2d.*` / `box2dandroid.*` (`box2d-jni`) from **authoritative** code | deterministic-if-used-thus | replacement: `PhysicsWorld`, stepped exactly once per tick from `PhysicsStepSystem` | `:udea-physics2d` is a declared simulation scope and the only caller. Box2D 3 is deterministic for an identical sequence of identical steps with an identical body-creation order, which is why the module creates bodies in ascending `NetId` and opens its world with no task system (one worker) and fixed sub-steps; `Box2DDeterminismTest` hashes two runs tick by tick. It is **not** claimed across platforms: the natives are C compiled per platform. And a rebuilt world is not the world it replaced - it starts without the warm-start and contact state - which `Box2DPhysicsWorld`'s KDoc names and `Box2DRewindTest` measures. |
| `Gdx.graphics` / `Gdx.input` / `Gdx.files` / `Gdx.app` | banned | DET006 | The device. `Gdx.graphics.getDeltaTime()` is a frame duration in seconds, which is a presentation unit; input must arrive as a replicated `InputCommand`; files must come from the compiled asset registry. This is the exact defect `common/.../UIScreen.kt:16` shipped. |

---

## 4. Findings, and what happened to them

Issue #151 asks for at least one nondeterminism the audit found to be reproduced, or for an
explicit record that none was found. Both halves apply here, so both are written out.

**Found and reproduced:** `Math.sin` diverging from `StrictMath.sin` on 67,912 of 2,000,000
sampled inputs on this JDK (section 3.1), which makes LibGDX's `MathUtils$Sin` table a per-JVM
artefact. `FloatPortabilityTest` in `build-logic` re-runs that probe and pins the two facts the
audit leans on: `Math.sqrt` is bit-exact (so the `Vector2.len`/`nor` rows are safe) and
`Math.sin` is not guaranteed to be. It does **not** assert that sin diverges — that would be a
test of this JDK rather than of the property — it asserts that the audit documents the risk and
that the exactly-specified operations really are exact.

**Not fixed here, and not fixable by a bytecode rule:** nothing in the tree currently calls
`MathUtils.sin` from simulation, because simulation references no LibGDX member at all. The
finding is therefore latent rather than live. It needs a follow-up: a scanner rule for
`MathUtils.sin`/`cos` and `Vector2.angleDeg` in simulation scopes, which this table's
`banned` verdicts document a replacement for in the meantime.

**Searched for and not found:** no unseeded RNG, no wall-clock read, no calendar read and no
iterated hash-ordered collection is reachable by a direct reference from a declared simulation
scope — that is what a green `udeaVerifyDeterminism` reports, and section 1 is the list of what
that sentence does not cover.

**Found and fixed in the rule rather than in the tree:** `DET004`'s first form fired on
*construction* of a hash-ordered collection and reported seven findings on the real tree, every
one of them false — `SimRegistry.resolve`, `SystemOrder.findCycle`, `AbilityTable.of`,
`AttributeTableBuilder.build`, `GameplayEffectTable.of` and `GameplayTagTable.of` each build a
`HashMap` or `HashSet` as a **lookup index** whose ordering comes from a sorted array beside it
and never iterate it. Its second form fired on the iteration site and was *inert* against Kotlin
for the reason in section 1. The shipped form joins the two at class level. Both dead ends are
recorded because a reviewer's first question about a hash-order rule is which of the two mistakes
it made.

**Gap worth naming:** `DET005` decides what "predicted" means from a **package list**
(`DeterminismRules.PREDICTED_PACKAGES`) because the tree has no `@Predicted` annotation. A
predicted system written outside `dev.wildware.udea.net.prediction` is not covered. The fix is
an annotation the scanner can read off the bytecode; the package list should be deleted the day
it exists.
