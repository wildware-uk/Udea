# ECS and Components

Udea's world is a Fleks entity-component-system. An entity is an id; a component is a small class of plain data (floats, ints, booleans, enums, ticks and net ids); a system is code that runs every tick over the entities that have certain components. You mark a component with `@Replicated`, and each field with `@Net` (sent over the network) or `@Sim` (kept in snapshots only). The build then generates one `Replicator<T>` for it, and that one class serves networking, rewind, replays and the agent's field access.

A plain picture: a spreadsheet. Each row is an entity, each column group is a component, and systems are formulas that run down the rows once a tick. The generated replicator is the one clerk who knows how to copy a row onto paper and back, so everybody who needs a copy (the network, the rewind buffer, the agent) asks the same clerk.

## Fleks, vendored

Fleks 2.14 lives in the repository as source, in `udea-fleks`. It is third-party code under its own MIT licence (`udea-fleks/NOTICE.md`). It is vendored only because Fleks publishes no iOS artifact. Do not refactor it: an edit to it fails `udeaVerifyDeterminism` until `determinism-audit.md` has been re-read, because that audit covers exactly the Fleks code the simulation uses.

The ordinary Fleks API applies: `world.family { all(A, B) }`, `entity[Component]`, `entity.getOrNull(Component)`, `entity.configure { it += Component() }`, `entity.remove()`.

## Components are plain data

A component is a class that implements `Component<T>` and has a `ComponentType<T>` companion. Its fields are plain values. This is the smallest real one in `moba`, from `moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaComponents.kt` (abridged: one KDoc shortened, `toString` left out):

```kotlin
@Serializable
@Replicated
@PositionHandle
public class Position(
    /** World x. Agent-writable. */
    @Net(agentWritable = true) public var x: Float = 0f,
    /** World y. Agent-writable. */
    @Net(agentWritable = true) public var y: Float = 0f,
    /** Hit points. Snapshotted but never replicated, and deliberately not agent-writable. */
    @Sim public var hp: Float = 100f,
) : Component<Position> {

    override fun type(): ComponentType<Position> = Position

    public companion object : ComponentType<Position>()
}
```

What each annotation does:

- `@Serializable` lets a level file save the component. See [Levels](Levels).
- `@Replicated` asks `udea-codegen` for a `PositionReplicator`.
- `@Net` puts a field on the wire *and* in snapshots.
- `@Sim` puts a field in snapshots only. It rewinds but never reaches a client. A jungle respawn timer or a bot's memory is `@Sim`.
- `@PositionHandle` gives the component a move handle in the editor. See [Gizmos](Gizmos).

### Why plain floats and not vectors

`Transform3D` (in `udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Transform3D.kt`) has nine float fields: `x`, `y`, `z`, three rotations and three scales. It has no vector. A vector object per field is an allocation on every write and an indirection on every read. Worse, to be useful to a renderer it would have to be the renderer's vector type, and `udea-core` must not name a Kool type. So no component holds a Kool type, ever. The same reasoning is why `PhysicsBody` stores `x`, `y` and `angle`.

A composite value type is allowed as a field when every property on it is a `var` of a directly storable type. The generator *lowers* it to one field per part, named `position.x`, `position.y`. It goes one level deep only.

### Field types the generator accepts

Directly: `Boolean`, `Int`, `Long`, `Float`, an enum (stored as its ordinal), `NetId` and `Tick`. Lowered: a value type made only of those. Anything else is a build error with a reason (`UDEA0006`), never a silent slow path.

### The authority vocabulary on `@Net`

`@Net` takes four optional parameters, defined in `udea-annotations/src/commonMain/kotlin/dev/wildware/udea/annotations/Net.kt`:

| Parameter | Values | Default | Meaning |
|---|---|---|---|
| `authority` | `Server`, `OwnerPredicted`, `OwnerWritable` | `Server` | Who may write the field. `OwnerPredicted` lets the owning client predict it. `OwnerWritable` is for cosmetic state only; no gameplay field is ever `OwnerWritable` |
| `lifetime` | `OnCreate`, `Always` | `Always` | `OnCreate` is sent once, in the spawn, and never again |
| `visibility` | `All`, `OwnerOnly` | `All` | `OwnerOnly` fields are stripped for everyone but the owner |
| `agentWritable` | `true`, `false` | `false` | Whether `world.set_component_field` may write it. Off by default, so a debug tool can never become a gameplay back door |

`@Q(bits, min, max)` quantises a `Float` on the wire. `min` and `max` have no defaults on purpose: a default range would silently clamp a value nobody thought about. Snapshots keep full precision.

The K2 compiler plugin checks these at compile time: `@Net` or `@Sim` on a `val` (`UDEA0001`, `UDEA0005`), `@Q` on a non-float (`UDEA0003`), and more than 64 replicated fields (`UDEA0002`). See [Diagnostics and Compiler Plugin](Diagnostics-and-Compiler-Plugin).

## What the build generates: `Replicator<T>`

`udea-codegen` is a KSP2 processor. For each `@Replicated` class it writes a `<Name>Replicator` that implements the frozen contract in `docs/contracts/replicator.md`:

```kotlin
interface Replicator<T> {
    val typeId: ComponentTypeId
    val fieldNames: List<String>
    val netMask: FieldMask
    val allMask: FieldMask

    fun capture(component: T, store: FieldStore, slot: Int)
    fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask
    fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter)
    fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask
    fun apply(store: FieldStore, slot: Int, component: T, mask: FieldMask)
    fun getField(component: T, fieldIndex: Int): Any?
    fun setField(component: T, fieldIndex: Int, value: Any?)
}
```

One replicator has five users, and because they share it they cannot disagree about what an entity is:

| User | Calls |
|---|---|
| network delta write | `capture` → `diff` → mask with `netMask` → `write` |
| network full write | `capture` → `write` with `netMask` |
| snapshot capture | `capture` with `allMask` |
| snapshot restore | `apply` in place |
| agent field access | `getField` / `setField`, `fieldNames` |

### Field order is alphabetical, and that is load-bearing

A field's index is its position in the component's lowered field names **sorted alphabetically**, not its position in the source. `Transform3D`'s order is `rotationX`, `rotationY`, `rotationZ`, `scaleX`, `scaleY`, `scaleZ`, `x`, `y`, `z`. The generated replicator exposes constants for them, such as `PositionReplicator.FIELD_X`.

The contract has one invariant that fails silently if broken:

> `fieldNames[i]` == `FieldMask` bit *i* == `FieldStore` field index *i*.

A desync report names the differing field by indexing `fieldNames` with each set bit of a mask diff. If the three ever disagree, the report does not fail; it names the wrong field. Moving a property in the source file cannot change the wire format. Renaming one always does.

### No setters, no delegates: capture and diff

There is no `by net(...)` delegate and no setter that marks a field dirty. An in-place change such as `vector.set(x, y)` fires no setter, so setter tracking would miss it. Instead, whenever a snapshot is taken, the replicator captures every field into a columnar `FieldStore`, and `diff` compares two captured slots. Whatever differs is what changed.

## Registering a component for snapshots

Capture walks a `ComponentRegistry`. A component that is not in the registry is not partly captured; it is invisible to rewind, replay hashing and replication. `Transform3D` offers its entry ready-made:

```kotlin
// in a game's ComponentRegistry
Transform3D.snapshotType()
```

`moba` builds its registry in `MobaGame.componentRegistry` and passes it to `snapshotTimeTravel(...)`. The KDoc there records what a short list once cost: a rewind brought back bare shells of units because seven of their nine components had never been captured. `SnapshotCoverage` and `SnapshotRestoreProofTest` exist so that cannot happen again unnoticed.

## Module registries: how generated code reaches a game

`udea-codegen` also writes, per Gradle module, one `object <Module>ModuleRegistry` in the package `dev.wildware.udea.generated`. It implements a *facet* interface for each kind of thing the module contributes:

| Facet | Declared in | Carries |
|---|---|---|
| `LevelComponentModule` | `udea-core` | the `@Serializable` components a level file may hold |
| `NetModule` | `udea-net` | the generated replicators |
| `ToolModule`, `StateModule` | `udea-agent` | the generated agent tools and agent state |

Each launcher gets a `<Module>UdeaRegistry` listing every module registry on its runtime classpath, in sorted order. `moba` passes `MobaUdeaRegistry` to its `UdeaGameDef`. A consumer finds its facet with an `is` check. There is no classpath scanning and no reflection, which also means it works on Kotlin/Native and Kotlin/Wasm.

KSP is configured per module in its build script through options such as `udea.moduleName` and `udea.registryModules`. `moba/game/build.gradle.kts` explains at length why `:moba:game` runs KSP through `kspJvm`.

## Type ids and the protocol lock

Every replicated component gets a `ComponentTypeId`. There is one generator, and within a module it assigns ids in the **sorted order of the components' fully qualified names**, starting at 0. So adding a component renumbers every component of that module that sorts after it. That is why the ids are pinned in a checked-in file per module: `udea-core/net-protocol.lock`, `udea-codegen/net-protocol.lock` and `moba/game/net-protocol.lock`.

`udeaCheckProtocolLock` runs on `check` and fails when the generated protocol and the lock disagree. `udeaWriteProtocolLock` rewrites the lock. Review the diff: it is the wire contract. A protocol hash computed from the lock rides in the first bytes of every packet. See [Replication and Networking](Replication-and-Networking).

## Systems

A simulation system extends `SimSystem` (in `udea-core/src/commonMain/kotlin/dev/wildware/udea/core/SimSystem.kt`), which is a Fleks `IntervalSystem` with two additions: `ctx`, the `GameContext` resolved from the world, and `tick`, the tick being simulated. Here is one from `moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaVfx.kt` (abridged: a counter and some comments left out):

```kotlin
public class EffectExpirySystem : SimSystem() {

    private val views: Family = world.family { all(SpriteView) }

    private val netIds: NetIdIndex = ctx[dev.wildware.udea.core.module.CoreModule.NET_IDS]

    override fun onTick() {
        val entities = views.entities
        val now = tick.value
        // Backwards, because a removal compacts the Fleks bag.
        var index = entities.size - 1
        while (index >= 0) {
            val entity: Entity = entities[index]
            if (with(world) { entity[SpriteView] }.expiryTick <= now) {
                netIds.free(netIds.netIdOf(entity))
                with(world) { entity.remove() }
            }
            index--
        }
    }
}
```

Notice three habits: the deadline is a tick (`expiryTick`), not a time; the entity's `NetId` is freed when it is removed; and the loop runs backwards because removal reorders the family.

A system is registered by a module with a phase, as [Architecture](Architecture) shows. Drawing code is never a `SimSystem`; it is a `RenderSystem` in `udea-render`.

## Entity identity: `NetId`, never `Entity`

A Fleks `Entity` is a slot in one world, in one process. It means nothing on another machine. Anything that leaves the world, such as a snapshot, a packet, a tool call or a level file, names an entity by `NetId` instead (`udea-core/src/commonMain/kotlin/dev/wildware/udea/core/identity/NetId.kt`).

A `NetId` packs a 16-bit index and an 8-bit generation into an `Int`. The index is recycled; the generation makes a stale reference *detectable*: `NetIdIndex.resolveOrNull` returns `null` for a stale id instead of silently resolving to whoever now holds the slot. `NetId.NONE` is the "no entity" value. The `NetIdIndex` service (`CoreModule.NET_IDS`) maps between the two with an `IntArray`, in constant time.

`NetId` is also a direct field type, so a component may hold a reference to another entity as a `@Net` or `@Sim` field.

## See also

- [Architecture](Architecture)
- [Tick Model and Determinism](Tick-Model-and-Determinism)
- [Replication and Networking](Replication-and-Networking)
- [Levels](Levels)
- [Diagnostics and Compiler Plugin](Diagnostics-and-Compiler-Plugin)
- [Gizmos](Gizmos)
