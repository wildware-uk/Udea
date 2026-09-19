# Levels

A level is a whole world saved to a file with the extension `.udealevel`: every entity and its components, the `NetId` bindings, the clock, the random streams, and any extra state a module asks to keep. `LevelService` saves one and loads one back exactly. A game can start from a level, swap one in as a scene, and the editor saves the world you built as one. `moba`'s launchers take `-Plevel=<path>` to boot a level other than the bundled one.

A plain picture: a save file in a video game, except it is also how the level designer ships the map. Loading it puts back not only where everything is, but the dice too, so the match carries on exactly as it would have.

## Levels are not snapshots

This is the rule to remember. Udea has two ways to write a world down, and they never share code.

| | Runtime snapshot | Level file |
|---|---|---|
| What for | Rewind, replication, desync reports, replay hashing | Content saved by a person or an agent |
| Written by | The generated `Replicator<T>` into a `FieldStore` | Fleks' own `world.snapshot()`, encoded with kotlinx CBOR |
| How often | Every few ticks, with no allocation | When somebody asks |
| Survives a component changing? | No, and it does not need to | Yes: it stores field names |
| Feeds the network or rewind? | Yes | Never |

`AGENTS.md` states the split in its do-not list. A level is saved content, not a snapshot codec.

## What goes into a level

`LevelService.saveNow()` (`udea-core/src/commonMain/kotlin/dev/wildware/udea/core/level/LevelService.kt`) writes a `LevelDocument` holding:

- the format version (`LevelFormat.VERSION`, currently 1);
- the tick the world was saved at;
- the random stream state, as raw words;
- every entity and its components, in entity id order;
- the `NetId` bound to each entity, and the `NetId` allocator's state;
- one named section per `LevelSection` a module contributed.

### Which components can be saved

A component can be saved when it is `@Serializable` and its module runs `udea-codegen`. The processor lists those per module in a `LevelComponentModule`, part of the module's generated registry (see [ECS and Components](ECS-and-Components)). Saving a world that holds any other component **fails** with a `LevelSaveException` naming the class. It never drops the component: a level that silently lost one would load into a different game. Fleks entity tags are refused the same way.

Fields that must not be saved are `@Transient`. `PhysicsBody.handle` is the example: a load rebuilds every physics body from the components, so a saved handle could only ever be stale.

### Module hooks: references and sections

A module's `level(hooks: LevelHooks)` hook adds two kinds of thing:

- **A reference** (`hooks.reference(type, serializer)`) is for a live object a saved component points at but does not own. `udea-gas` registers its `AttributeTable` this way. A level records the attribute names in order and, on load, refuses a level saved against a different table, instead of restoring one game's strength into another game's armour.
- **A section** (`hooks.section(section)`) is extra state a module must put back after a load. A `LevelSection<T>` has a `name`, a `serializer`, `save(world)` and `load(world, saved)`. `udea-gas` saves its effect-handle counter this way, because handles are replicated state, and a load that reset the counter would give the next effect a different handle from the one the saved match would have issued.

This is the real hook, from `udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/GasModule.kt`:

```kotlin
override fun level(hooks: LevelHooks) {
    hooks.reference(AttributeTable::class, AttributeTableReference(attributes))
    hooks.section(EffectHandleSection(handles))
}
```

## Saving and loading

Both go through the `SimBarrier`, so neither ever sees or produces a half-finished world.

```kotlin
val levels = host.game.levels

// Save: queued, so it sees the world the next tick would start from.
val saving = levels.save(host.ctx.barrier)          // LevelAction<ByteArray>

// Load: read and check first, then queue the swap.
val level = levels.read(bytes)                      // throws LevelFormatException if unusable
levels.load(level, host.ctx.barrier)                // applies at the next drain
```

- `save(barrier)` queues the save. A spawn requested during the last tick is still sitting in the queue between ticks; a save taken beside it would miss that entity. Queued behind it, the save sees what the next tick would. The bytes arrive in the returned `LevelAction`'s `outcome`.
- `read(bytes)` decodes and validates the whole file before anything is touched: the format version, every component class, every reference, the bindings and the streams. Anything wrong is a `LevelFormatException`.
- `load(level, barrier)` queues the replacement. When it applies, it loads the entities, restores the `NetId` index and bindings, moves the clock, drops any snapshots newer than the level's tick, restores the random streams, rebuilds physics bodies from their components with `PhysicsWorld.rebuildFrom`, and finally lets every section put its state back.

`saveNow()` and `loadNow(level)` do the same work immediately. They are for callers that are already inside a barrier drain, such as the editor's `editor.save` and `editor.stop` tools; queuing from there would land one tick late.

## A level as a scene

`LevelScene` (`udea-core/src/commonMain/kotlin/dev/wildware/udea/core/level/LevelScene.kt`) wraps level bytes as a `Scene`. Every swap to its id puts the level's entities back. That is how a game restarts a match in-process.

A scene swap restores less than `load` does, on purpose. It puts back the entities and rebuilds their physics bodies. It does **not** move the clock, restore the random streams or reload module sections, because a restart that wound the clock back and replayed the same random numbers would make every match the same match. `NetId`s are minted fresh from index zero, in the order the level saved them.

## How `moba` boots its level

`moba`'s bundled level is `moba/game/levels/test_level.udealevel`. `MobaGame.definition()` registers it as a `LevelScene` with the id `MobaLevel.SCENE_ID`, and every entry point then calls `MobaEntry.seed(host)` (`moba/game/src/commonMain/kotlin/dev/wildware/moba/entry/MobaEntry.kt`):

```kotlin
public fun seed(host: GameHost): NetId {
    val levels = host.game.levels
    val level = levels.read(host.ctx[MobaLevel.KEY].bytes)
    host.ctx.scenes.requestScene(MobaLevel.SCENE_ID)
    levels.load(level, host.ctx.barrier)
    host.run(1)
    return playerId(host)
}
```

### Booting another level with `-Plevel`

Every task in `:moba:desktop` that launches the game accepts `-Plevel=<path>`:

```
sh gradlew :moba:desktop:run -Plevel=moba/game/levels/test_level.udealevel
```

A relative path is resolved against the repository root. The build script forwards it to the forked JVM as the system property `moba.level`, because a Gradle property stops at the daemon and never reaches a `JavaExec` process. `MobaLaunchLevel` (`moba/desktop/src/main/kotlin/dev/wildware/moba/entry/MobaLaunchLevel.kt`) reads it; without it, the bundled test level is used.

## Saving from the editor

The editor's Save button calls the `editor.save` tool with a level name (letters, digits, `-` and `_`, no path). It writes `<name>.udealevel` into the editor's level directory and answers with the path and size. In `moba` that directory is `build/editor-levels`, relative to the working directory the launcher runs in. Saving never clears undo history. Play and Stop in the editor use the same encoder: Play saves the world, and Stop loads it back. See [The Editor](The-Editor).

## Proof in `moba`

| Command | What it shows |
|---|---|
| `sh gradlew :moba:desktop:runLevelShot` | Plays the real level, saves it mid-fight and photographs it, then loads the saved match into a fresh process and checks the picture matches |
| `sh gradlew :moba:desktop:runLevelShotBoot` | Photographs the launch level on its boot tick (`-Plevel` or the test level) |

`LevelSaveLoadProofTest` in `moba/desktop/src/test/kotlin/dev/wildware/moba/level/` is the test behind them.

## See also

- [ECS and Components](ECS-and-Components)
- [Replay and Time Travel](Replay-and-Time-Travel)
- [The Editor](The-Editor)
- [Physics](Physics)
- [Assets](Assets)
