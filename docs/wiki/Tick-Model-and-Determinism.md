# Tick Model and Determinism

The simulation moves in fixed steps called ticks: 60 a second, each one exactly the same length. Every duration, deadline and timestamp in simulation code is a `Tick` (which tick) or a `Ticks` (how many), never seconds and never a wall-clock time. Random numbers come from seeded, named streams whose state is part of a snapshot. Together these make a run repeatable: the same inputs from the same start give the same world, bit for bit, on any machine.

A plain picture: a board game with a referee who only moves on the beat of a metronome. Nobody measures how long a turn took; you count turns. Two tables playing the same moves from the same deal end with the same board.

## `Tick` and `Ticks`

`Tick` is in `udea-core/src/commonMain/kotlin/dev/wildware/udea/core/Tick.kt`. It is a value class over a `Long` counted from the start of the simulation, so it never wraps in practice.

```kotlin
@Serializable
@JvmInline
public value class Tick(public val value: Long) : Comparable<Tick> {
    public operator fun plus(ticks: Long): Tick = Tick(value + ticks)
    public operator fun minus(ticks: Long): Tick = Tick(value - ticks)
    public infix fun until(end: Tick): TickRange = TickRange(this, end)
    public fun ticksSince(earlier: Tick): Long = value - earlier.value
    // ...
}
```

Two design choices are worth noticing:

- **There is no `Tick - Tick` operator.** The difference of two moments is a length, not a moment, so it has its own name: `ticksSince`. That stops `a - b` from quietly producing a `Tick` that really means "a number of ticks".
- **`a until b` is half-open.** Replaying `a until b` runs exactly `b - a` steps and leaves the clock reading `b`.

`Ticks` (in `udea-core/src/commonMain/kotlin/dev/wildware/udea/core/Ticks.kt`) is the length type: how many ticks, where a `Tick` is which one. An animation clip's length and a crossfade's length are `Ticks`. `6.ticks` builds one.

No type in `udea-core` converts a `Tick` to seconds. That is deliberate.

## `SimClock`: time is derived, never added up

`SimClock` (`udea-core/src/commonMain/kotlin/dev/wildware/udea/core/SimClock.kt`) holds the current tick and the tick rate (60 by default).

```kotlin
public class SimClock(public val tickRate: Int = DEFAULT_TICK_RATE) {
    public val dt: Float = 1f / tickRate
    public var tick: Tick = Tick.ZERO
        internal set
    public val time: Double get() = tick.value * dt.toDouble()
}
```

`time` is computed from `tick` on every read. It is never accumulated with `time += delta`. Adding up floats drifts, two machines that ran the same ticks would disagree about the time, and a rewind could not restore an accumulator. Deriving it removes all three problems. Only the kernel advances the clock, one whole tick at a time. A system reads the current tick as `tick` (on `SimSystem`) or `ctx.clock.tick`.

## One tick, step by step

`WorldSimulation.step()` (`udea-core/src/commonMain/kotlin/dev/wildware/udea/core/loop/Simulation.kt`) is the whole contract, in four lines:

```kotlin
override fun step() {
    barrier.drain(world, ctx)   // 1. apply every queued outside change
    world.update(dt)            // 2. run the systems, phase by phase
    ctx.clock.advance()         // 3. the tick is finished
    stepCount++
    travel?.captureIfDue()      // 4. take a snapshot if one is due
}
```

1. **Drain the barrier.** Everything queued from outside the simulation lands now, before any system looks at the world.
2. **Run the systems** in their `SimPhase` order (see [Architecture](Architecture)). This is pure simulation, because drawing code is not a Fleks system.
3. **Advance the clock.** `clock.tick` names the tick *about to be* simulated, so it moves only after the tick is done.
4. **Capture a snapshot** if this tick is due one. By default that is every third tick (`EngineConfig.snapshotIntervalTicks = 3`), which is 20 snapshots a second.

## `SimBarrier`: the only door into the world

Anything that changes the world from outside a system goes through one queue, `SimBarrier` (`udea-core/src/commonMain/kotlin/dev/wildware/udea/core/loop/SimBarrier.kt`). Scene swaps, asset hot-reloads, agent tool edits, level loads and incoming network snapshots all queue here. The queue is drained at the top of the next `step()`, so **no system ever sees a half-applied change**.

Each queued item is a `BarrierAction`, which has a name:

```kotlin
public interface BarrierAction {
    public val label: String
    public fun apply(world: World, ctx: GameContext)
}
```

The label is not decoration. When someone asks why the world changed between tick 412 and tick 413, the label is the answer, and it shows in logs and desync reports.

The rules:

- `submit` is thread-safe. Tool calls arrive on an HTTP thread and asset changes on the asset daemon's thread. The drain, and all of the simulation, stay on one thread.
- Actions run in the order they were submitted.
- Something submitted *during* a drain waits for the next tick. A drain is bounded by the queue length when it started.
- An action that throws is logged with its label and tick, and the drain carries on. `failedActions` counts them.

Spawning follows the same rule. `moba`'s `EffectSpawnSystem` notes that "a spawn is a barrier action, so this entity exists from the start of the *next* tick".

## The game loop and the render alpha

`GameLoop` (`udea-core/src/commonMain/kotlin/dev/wildware/udea/core/loop/GameLoop.kt`) turns wall time into whole ticks. It takes the frame's wall delta as a parameter and reads no clock itself. That is what lets a test or an agent feed it synthetic time.

Its accumulator is an integer counted in microsecond-ticks, not float seconds. `1f / 60f` is slightly more than a sixtieth, so a float accumulator turns ten wall seconds into 599 ticks. The integer one gives exactly 600. `EngineConfig.maxCatchUpTicks` (default 5) caps how many ticks one frame may run to catch up.

The leftover fraction of a tick is the **interpolation alpha**. The renderer draws between the last two ticks at that alpha, so motion looks smooth at any frame rate while the simulation stays on the beat. The alpha is a presentation value and never reaches a system.

## Seconds live only at the edges

Seconds are allowed in exactly two places: `udea-render` and audio.

- A `RenderSystem` gets `render(target, alpha)`: the interpolation alpha, not a time.
- An `OverlaySystem` gets `render(target, dtSeconds)`: wall seconds since the last frame. It cannot read simulation time at all, and the signature is the enforcement.
- Animation time is `Animator.clipTime(now)`, a `Ticks` derived from the clip's start tick. The renderer turns it into seconds at the last moment.

Content authors may write seconds. The ability asset `moba/game/assets/ability/gameplay_effects.udea.kts` says `period = 0.25F`, and `udea-gas`'s `ticksFromSeconds` converts it once, at load, to 15 ticks.

## Randomness: seeded, named streams

Simulation code must not call `Math.random`, `Random.Default` or anything seeded from the clock. It uses `ctx.rng`, an `RngService` with five named streams:

```kotlin
// udea-core/src/commonMain/kotlin/dev/wildware/udea/core/Services.kt
public enum class RngStream { Combat, Loot, AI, Spawn, Wave }
```

```kotlin
// moba/game/src/commonMain/kotlin/dev/wildware/moba/lane/LaneSystems.kt
val rng = ctx.rng
// ...
val jitterX = scatter(rng.nextFloat(RngStream.Wave))
val jitterY = scatter(rng.nextFloat(RngStream.Wave))
```

Streams are separate so that drawing a loot roll cannot shift the combat sequence. A change to loot then does not change who wins a fight in a replay. Each stream is a `SimRandom`: xoshiro256\*\*, whose whole state is four longs. That state is saved in every snapshot and level file, so a rewind or a load puts the random sequence back exactly where it was. Every stream is derived from one root seed, `EngineConfig.seed`.

Presentation gets its own generator, `PresentationRandom` in `udea-render`. It is a different type, seeded from the wall clock on purpose (screen shake need not repeat), and simulation code cannot reach it.

## What keeps the simulation deterministic

Rules on a page are not enough, so the build checks them. [Build and Verification](Build-and-Verification) has the full list; these are the determinism ones:

| Gate | What it catches |
|---|---|
| `udeaVerifyDeterminism` (root, on `check`) | A bytecode scan of the source sets declared as simulation (`DeterminismRules.SIMULATION_SCOPES` in `build-logic`) for wall-clock reads (`System.currentTimeMillis`, `nanoTime`), `Math.random`, `Random.Default`, unseeded `java.util.Random` and similar. Exceptions need an entry in `determinism-allowlist.txt` with a reason. |
| `:udea-gas:udeaVerifyGasTime` (on `check`) | `udea-gas` simulation code naming seconds, a wall clock or LibGDX, such as `kotlin.time.Duration`. |
| `WorldHasher` tests | One `Long` that summarises a captured world, independent of spawn order. The rewind and snapshot-equivalence tests assert against it. |
| The `replay-equality` CI job | Replays checked-in `.udearep` recordings on Linux and Windows and compares the per-tick digests. See [Replay and Time Travel](Replay-and-Time-Travel). |
| `UDEA0035` (asset build) | A `.udea.kts` that reads a clock or an unseeded random, so two builds of the same sources would pack different bytes. |

**A green `udeaVerifyDeterminism` does not prove the simulation is deterministic.** The scanner sees direct calls, not nondeterminism hidden inside a library, in hash-map iteration order, or in float differences between machines. `determinism-audit.md` at the repository root is the hand-written record of what the scanner cannot see. The real gate is the hash stream: replays that agree tick by tick on two operating systems. When the two disagree, the replay wins and the scanner grows a rule.

## Rules of thumb

- Store deadlines as a `Tick` and durations as `Ticks` or a tick count. `SpriteView.expiryTick` in `moba` is a good model.
- Read time as `tick`, never `deltaTime`. `SimSystem`'s KDoc says it plainly: seconds are a presentation unit.
- Draw random numbers from `ctx.rng` with the stream that matches the purpose.
- Change the world from outside a system only through the barrier.
- Iterate in a stable order. Removing entities while walking a Fleks family? Walk backwards, as `EffectExpirySystem` does.

## See also

- [Architecture](Architecture)
- [ECS and Components](ECS-and-Components)
- [Replay and Time Travel](Replay-and-Time-Travel)
- [Replication and Networking](Replication-and-Networking)
- [Build and Verification](Build-and-Verification)
