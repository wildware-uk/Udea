# Abilities (GAS)

`udea-gas` is Udea's gameplay ability system, modelled on the familiar attributes-effects-abilities design. **Attributes** are numbers on an entity, such as health, mana and strength. **Effects** change attributes, instantly, for a duration or periodically, and can carry tags such as "stunned". **Abilities** are the things a unit does, such as a sword swing or a heal; they have cooldowns and costs and run game code. **Cues** are the "something happened" signals, a hit or a heal, that sound and visual effects react to. Every duration is counted in ticks, and all of it is snapshot state, so it rewinds and replays exactly.

A plain picture: a character sheet in a tabletop game. The sheet has base stats (attributes). Spells and potions stick notes on it (effects) that change a stat until they expire. The moves you can make (abilities) have a recharge time and a mana cost. When something happens, the game master rings a bell (a cue) so the players know to react.

## The module

A game builds a `GasModule` from its own content and lists it in its `UdeaGameDef`:

```kotlin
// udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/GasModule.kt (constructor, abridged)
public class GasModule(
    public val attributes: AttributeTable,
    public val effects: GameplayEffectTable,
    public val abilities: AbilityTable,
    public val execs: AbilityExecRegistry,
    public val authority: AbilityAuthority = AbilityAuthority.All,
    public val sharing: CooldownSharing = CooldownSharing.None,
    cueCapacity: Int = GasCueQueue.DEFAULT_CAPACITY,
) : UdeaModule
```

The tables are constructor parameters because they are the game's content. The engine cannot invent them, and discovering them at run time would turn a missing registration into a start-up failure instead of a compile error. Two `GasModule`s in one process share nothing.

The module registers three systems, each in its own phase:

| System | Phase | Job |
|---|---|---|
| `AbilitySystem` | `SimPhase.Ability` | Runs every in-flight ability activation for one tick |
| `AttributeSystem` | `SimPhase.Attribute` | Recomputes every attribute from its base plus active effects |
| `GasCueForwardSystem` | `SimPhase.Cleanup` | Drains GAS cues into `GameContext.cues`, where audio and rendering pick them up |

Everything a host needs is published as one `GasServices` value under `GasServices.KEY`: the tables, the handle allocator, the cue queue, the `EffectApplier`, the `AbilityActivation` and the `AttributeRecompute`.

In `moba`, `MobaAbilityModule` (`moba/game/src/commonMain/kotlin/dev/wildware/moba/ability/MobaAbilityModule.kt`) owns the `GasModule` and the tables, and the other game modules are handed that same instance. That matters: an `AttributeId` is an index into one table, and a module built over a second table would restore a unit's strength into its armour.

## Attributes

An attribute is declared with an `AttributeDecl`: a name, a default base value, optional minimum and maximum (each a `ValueResolver`, so a maximum can be another attribute), and whether it is replicated. Modules contribute declarations through `AttributeModule`, and `AttributeTableBuilder` merges them into one `AttributeTable` with dense `AttributeId`s. Two modules declaring the same name is a `DuplicateAttributeException`.

This is `moba`'s set, from `moba/game/src/commonMain/kotlin/dev/wildware/moba/ability/CharacterAttributes.kt`:

```kotlin
AttributeDecl(HEALTH, defaultBase = 100f, min = value(0f), max = value(maxHealth)),
AttributeDecl(MAX_HEALTH, defaultBase = 100f, min = value(0f)),
AttributeDecl(MANA, defaultBase = 0f, min = value(0f), max = value(maxMana)),
AttributeDecl(MAX_MANA, defaultBase = 0f, min = value(0f)),
AttributeDecl(STRENGTH, defaultBase = 10f),
AttributeDecl(ARMOUR, defaultBase = 0f),
AttributeDecl(MAGIC_RESIST, defaultBase = 0f),
AttributeDecl(HEALTH_REGEN, defaultBase = 0f),
```

An entity's values live in its `Attributes` component. Each tick, `AttributeRecompute` resets every attribute to its base and reapplies every active modifier. Because the current value is a pure function of the base values and the effect list, a snapshot needs only those two, and a rewind cannot leave a corrupted stat behind. Modifiers are applied in a total order, by modifier type, then attribute, then effect handle, so the result never depends on how the effect list happened to be laid out in memory. The recompute allocates nothing.

## Gameplay effects

A `GameplayEffectDef` (`udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/GameplayEffect.kt`) says what an effect does:

| Field | Meaning |
|---|---|
| `name` | Its name, which is also its id in the asset graph |
| `target` | The attribute it modifies, or none |
| `modifierType` | `Additive` (`value + magnitude`), `Multiplicative` (`value * magnitude`) or `Override` (`magnitude`) |
| `magnitude` | A `ValueResolver`: a constant, another attribute, or a value the caller sets through a tag |
| `duration` | `Instant`, `Infinite`, `Ticks(count)`, or `SetByCaller(tag)` |
| `periodTicks` | For a periodic effect, how many ticks between applications |
| `tags` | The gameplay tags the effect grants while it is active |
| `cueIds` | The cues it emits when applied |

Here is `moba`'s heal over time, from `moba/game/src/commonMain/kotlin/dev/wildware/moba/ability/MobaEffects.kt`:

```kotlin
GameplayEffectDef(
    name = HEAL_OVER_TIME,
    target = attributes.health,
    modifierType = ModifierType.Additive,
    magnitude = value(tags.dataHeal),
    duration = GameplayEffectDuration.SetByCaller(tags.dataDuration),
    periodTicks = HEAL_PERIOD_TICKS,
    tags = tags.table.newSet(),
    cueIds = intArrayOf(MobaCues.HEAL),
),
```

The same effect is also authored as an asset, in `moba/game/assets/ability/gameplay_effects.udea.kts`, with its period written in seconds (`period = 0.25F`) as a designer would. `udea-gas`'s `ticksFromSeconds` turns that into 15 ticks at load. The Kotlin table is still built in code, because a `GameplayEffectDef` holds interned attribute ids and tag sets that only a running game has; `MobaAuthoredContentTest` checks that the two agree name for name, duration for duration and period for period.

### Applying an effect

An effect is applied with the `EffectApplier`, a small builder: open an application, stage any set-by-caller magnitudes, then apply it to a target. This is `moba`'s damage rule, from `moba/game/src/commonMain/kotlin/dev/wildware/moba/ability/CombatRules.kt`:

```kotlin
context.applier
    .begin(effects.damage)
    .magnitude(tags.dataDamage, -amount)
    .applyTo(targetEffects, targetAttributes, context.tick, targetId = target, source = context.self)
```

`applyTo` returns an `EffectHandle`, which `remove` takes to end the effect early. Handles come from a per-world `HandleAllocator`. They are never reused, and the counter is saved with a level (see [Levels](Levels)), because handles are replicated state and a reset counter would make a loaded match diverge on its first cast.

### Tags

A `GameplayTag` is an interned name in a `GameplayTagTable`, such as `Debuffs.Stunned`. A `TagSet` is a set of them. An effect grants its tags while it is active. An ability lists tags that block it (`blockedBy`), so a stun is simply an effect that grants the stunned tag and modifies nothing. Tags also name set-by-caller magnitudes, such as `Data.Damage` and `Data.Duration`.

## Abilities

An `AbilityDef` (`udea-gas/src/commonMain/kotlin/dev/wildware/udea/gas/AbilityDef.kt`) holds an ability's `name`, the `AbilityExecId` of the code it runs, its `cooldownTicks`, its `costs` (each an attribute, an amount and the effect that spends it), its own `tags` and the `blockedBy` tags.

The behaviour is an `AbilityExec`:

```kotlin
public interface AbilityExec {
    public fun onActivate(context: AbilityContext)
    public fun onTick(context: AbilityContext) {}
    public fun onEnd(context: AbilityContext, cancelled: Boolean) {}
}
```

The `AbilityContext` gives the exec the `tick`, the entity using it (`self`), its `attributes`, how many ticks it has been running (`elapsedTicks`), the `applier`, the `cues` queue, and `endAbility()`. `moba`'s melee attack emits its swing cue on activation (`moba/game/src/commonMain/kotlin/dev/wildware/moba/ability/MobaExecs.kt`):

```kotlin
public class MeleeAttackExec(private val rules: CombatRules) : AbilityExec {

    override fun onActivate(context: AbilityContext) {
        context.cues.emit(MobaCues.MELEE_SWOOSH, context.tick, source = context.self)
    }
    // ...
}
```

An entity's granted abilities live in its `Abilities` component, one slot each. `AbilityActivation.activate(...)` tries to start one and returns an `ActivationResult`, never an exception. The result is `Activated`, or the reason it was refused: `OnCooldown(remainingTicks)`, `BlockedByTag(tag)`, `InsufficientResource`, `NotGranted`, `NoAuthority` or `AlreadyActive`.

- **Cooldowns** are effects. Starting an ability applies its cooldown effect (`cooldownEffectIndex`) for the ability's cooldown in ticks, less any reduction from `cooldownReductionAttribute`. So a cooldown rewinds with everything else. `CooldownSharing` puts slots into groups that cool down together; a game with an item-active slot uses it.
- **Authority** is a per-entity policy, `AbilityAuthority`, not a global "am I the server" flag. A client controls one champion and predicts for it; a listen server controls one and is authoritative for the rest.

## Cues

A cue is a small "this happened" event with an id, a tick and a source `NetId`. Simulation code emits cues and never reads them back. `GasCueQueue` collects them during a tick, and `GasCueForwardSystem` drains them into `GameContext.cues` in the `Cleanup` phase. From there `udea-audio` plays sounds and `udea-render` shows effects. `moba`'s `EffectSpawnSystem` also reads the queue, just before the forwarder, to spawn the hit and heal flashes. See [Audio](Audio).

## Snapshots and networking

`Attributes`, `GameplayEffects` and `Abilities` are all captured in snapshots, so a rewind restores stats, active effects, cooldowns and in-flight abilities together. Their codecs are hand-written in `GasReplicators.kt` and `AttributesReplicator.kt`, because an array of activation records does not fit the generator's one-value-per-field model yet. The cost of that, one allocation per component per capture, is stated in the code. The per-tick path is held to zero allocation: `:udea-gas:udeaGasAllocationBudget` gates the attribute recompute at 500 entities, 8 effects each, over 600 ticks, at zero bytes. `:udea-gas:udeaVerifyGasTime` fails the build if `udea-gas` simulation code names seconds or a wall clock.

A client asks the server to use an ability through an `@Rpc`. See [Replication and Networking](Replication-and-Networking).

## See also

- [Tick Model and Determinism](Tick-Model-and-Determinism)
- [ECS and Components](ECS-and-Components)
- [Assets](Assets)
- [Audio](Audio)
- [Levels](Levels)
