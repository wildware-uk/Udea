# Physics

Udea splits physics in two. **Authoritative movement**, anything a player controls or a client predicts, is `CharacterMover` in `udea-core`: a small, allocation-free capsule sweep that gives bit-identical results on every machine. **Everything else physical**, such as sensors, debris, projectiles and pushing things around, goes through the `PhysicsWorld` interface. The kernel's default `PhysicsWorld` does bookkeeping only; `udea-physics2d` puts real Box2D 3 behind it. A 3D game uses the same 2D solver on its ground plane: `Transform3D`'s `x`, `y` and heading are the body's position and angle.

A plain picture: the referee decides where the players stand (`CharacterMover`). The physics engine is the stagehand who handles the props: the balls, the crates, the dust. The stagehand never tells the referee where a player is.

## Why two systems

A solver such as Box2D keeps hidden state between steps: contact caches, warm-start impulses and island order. No snapshot carries that state, and Box2D is not guaranteed to give the same answer on two different machines. So a move decided by the solver can be neither replayed from a snapshot nor predicted by a client. That is fatal to rollback and prediction, so the solver is never in charge of a player.

## `CharacterMover`: authoritative movement

`CharacterMover` (`udea-core/src/commonMain/kotlin/dev/wildware/udea/core/movement/CharacterMover.kt`) sweeps a capsule against `StaticCollision` geometry. One tick of movement is:

```kotlin
mover.move(state, intent, config, geometry, dt)
```

Every input is a parameter and every output lands in `MoverState`, which is seven plain fields (`x`, `y`, `velocityX`, `velocityY`, `grounded`, `groundNormalX`, `groundNormalY`). Restore the state, supply the same intents, and the same positions come back. That is the primitive prediction's reconciliation is built on.

- `MoveIntent` holds the input: a `move` axis and a `jump` flag.
- `MoverConfig` holds the tuning: `radius`, `halfHeight`, `maxSpeed`, `acceleration`, `gravity`, `jumpSpeed`, `stepDownHeight` and `minGroundNormalY`.
- The float operation order is part of the contract. Every value is a `Float`, nothing is widened to `Double` and back, and expressions are not reassociated, so Windows and Linux agree to the last bit.

`CharacterMoverSystem` runs the mover over every entity with a state, a config and an intent. `CoreModule` registers it in `SimPhase.Movement`, which is before `SimPhase.Physics`, so the solver reacts to movement and never decides it. The walls come from a `SceneCollision` service. Replace its geometry only from a `SimBarrier` action, never during a tick. `:udea-core:udeaBenchCharacterMover` benchmarks it.

## `PhysicsWorld`: the interface

`PhysicsWorld` (`udea-core/src/commonMain/kotlin/dev/wildware/udea/core/physics/PhysicsWorld.kt`) is what a game reads as `ctx.physics`. No method on it names a Box2D type, and `NoBox2DInCoreTest` keeps it that way. Its main operations:

| Operation | Meaning |
|---|---|
| `stepOneTick()` | Advance exactly one tick. There is no `step(seconds)` |
| `createBody(def)`, `destroyBody(handle)`, `destroyAllBodies()` | Body lifetime |
| `poseOf(handle, out)`, `velocityOf(handle, out)` | Read a body back, into a caller-owned object |
| `teleport(handle, pose)`, `setAwake(handle, awake)` | Discontinuous changes |
| `raycast(...)`, `overlap(shape, pose, out)` | Queries |
| `addContactListener(...)`, `removeContactListener(...)` | Contact begin and end |
| `rebuildFrom(world, netIds)` | Throw every body away and rebuild from the components |

`NoOpPhysicsWorld` is the kernel's default. It keeps the bookkeeping and has no solver.

### Components

Physics state lives in ordinary components (`udea-core/src/commonMain/kotlin/dev/wildware/udea/core/physics/PhysicsComponents.kt`), as plain primitives:

- `PhysicsBody`: `kind` (`Static`, `Kinematic` or `Dynamic`), `x`, `y`, `angle`, `linearX`, `linearY`, `angularVelocity`, `awake` and `isSensor`. Every field is `@Sim`: snapshotted and hashed, never sent. A client gets positions from the game's own replicated components, never from a solver it does not run.
- Shapes: `Box`, `Circle`, `Capsule` and `Chain`. An entity may have several, and each shape type has a fixed creation order so two machines build the same fixtures in the same order.
- `Teleport`: a one-shot command to move a body discontinuously. `TeleportSystem` applies it in `SimPhase.PreSimulation` and removes it, so it cannot fire twice.

`PhysicsBody.handle` is the live solver body. It is deliberately not `@Net`, not `@Sim` and `@Transient` in level files: every restore rebuilds the bodies, so a saved handle could only be stale.

### Rewind: rebuild, do not restore

The solver is never snapshot state. A rewind or a level load restores the components and then calls `rebuildFrom`, which creates every body again from `PhysicsBody` and its shapes. Contact caches and warm-start impulses are lost, on purpose, because nothing authoritative lives in them. The code's own advice is blunt: do not "fix" the fidelity of a restore by snapshotting the solver; if something is worth restoring, it belongs in a component. `:udea-core:udeaPhysicsRebuildBudget` measures how long a rebuild takes.

For the components to be captured at all, add `PhysicsSnapshotTypes.all()` to the game's `ComponentRegistry`.

## `udea-physics2d`: Box2D

`udea-physics2d` puts Box2D 3, through `box2d-jni`, behind `PhysicsWorld`. It builds for `jvm` and `android` only, because that is all `box2d-jni` publishes. The Android package puts the same API under another Java package name, so the solver code lives in `udea-physics2d/src/box2dMain/`, compiled into both targets, over a per-target `Box2DBindings.kt`.

A game adds it as a module:

```kotlin
val physics = Physics2DModule()          // Physics2DSettings() by default
UdeaGameDef(registry = ..., modules = listOf(physics, /* the game's modules */))
// ...
physics.close()                          // frees the native Box2D world
```

Its `context` hook opens a Box2D world and installs it as `ctx.physics`, replacing `NoOpPhysicsWorld`. Then every entity with a `PhysicsBody`, at least one shape and a `NetId` becomes a Box2D body at the next tick. Nothing else in the game changes: systems keep reading and writing plain floats, and no Box2D type appears anywhere a game can see.

`Physics2DSettings` holds `gravityX`, `gravityY` (default `-10`), `subSteps` (default 4), `enableSleep` and `enableContinuous`. The Box2D world is native memory, opened when the game is built and freed by `close()`. A `Physics2DModule` builds one game; building a second from the same instance fails instead of leaking a world.

### What a tick does

1. `PreSimulation`: `TeleportSystem` applies queued teleports.
2. `Physics`: `PhysicsReconcileSystem` makes the solver match the components: a body for each new entity, none for a destroyed one, a fresh body where a shape changed. Bodies are created in ascending `NetId` order, so two machines agree whatever order the entities spawned in. Changing static `Chain` geometry after it was built throws `StaticGeometryChangedException`.
3. `Physics`: `PhysicsStepSystem` advances Box2D one tick and copies every body's pose, velocity and sleep state back into its `PhysicsBody`. That copy is the only way a solver result reaches the components.

## 3D on the ground plane: `Transform3D`

`Transform3D` places an entity in 3D with Z up. Its ground plane is `z = 0`, the same plane a 2D game lives on, so the 2D solver serves a 3D game directly. An entity with both a `PhysicsBody` and a `Transform3D` is handled by three more `Physics` systems in `udea-physics2d/src/commonMain/kotlin/dev/wildware/udea/physics2d/Transform3DSystems.kt`:

| System | When | What |
|---|---|---|
| `Transform3DSeedSystem` | before the reconcile | builds a new body where `Transform3D.x`, `y` and `rotationZ` say |
| `BodyFollowsTransform3DSystem` | after the reconcile, before the step | carries each kinematic body to its `Transform3D` in one step, pushing what it meets, and teleports a static body whose `Transform3D` moved |
| `Transform3DFromBodySystem` | after the step | copies each dynamic body's solved pose into its `Transform3D` |

So a 3D game moves a **dynamic** body the way a 2D game does, with velocity or a `Teleport`, and moves a **kinematic or static** body by writing its `Transform3D`. `z`, the other two rotations and the scale stay the game's own. An entity with no `Transform3D` is untouched by all three.

This is the physics the 3D example game, Hollow, is designed to use. Hollow is in progress; its design is `docs/superpowers/specs/2026-09-19-hollow-3d-game-design.md`. See [Example Games](Example-Games).

## `moba` and physics

`moba` does not install `Physics2DModule` today. Its units move by their own rules, and `MobaGame.definition()` explains why the solver is left out: with crowd separation, units no longer stand inside each other, which changes the balance of the fight in a way nobody has retuned yet. `udea-physics2d`'s own tests (`Box2DPhysicsWorldTest`, `Box2DRewindTest`, `Box2DDeterminismTest` and others in `udea-physics2d/src/jvmTest/`) drive the solver directly.

## See also

- [Tick Model and Determinism](Tick-Model-and-Determinism)
- [ECS and Components](ECS-and-Components)
- [Replay and Time Travel](Replay-and-Time-Travel)
- [Levels](Levels)
- [Replication and Networking](Replication-and-Networking)
