// Every gameplay effect this game applies, authored - which it was not until `gameplayEffect`
// became a published kind.
//
// ## Where these numbers used to live twice
//
// `moba/src/main/assets/ability/gameplay_effects.udea.kts` declared eight of these in the
// migrated corpus that nothing packed, and `dev.wildware.moba.ability.MobaEffects` wrote seven of
// them out again in Kotlin, with its own KDoc saying so:
//
//   > `gameplayEffect` is `AssetKind.Unpublishable`, so the packer cannot publish one and nothing
//   > loads these at boot. This is the runtime that corpus is waiting for.
//
// Nothing compared the two. `MobaAuthoredContentTest` compares them now - name for name, duration
// for duration, period for period - so the table the simulation runs on and the file a designer
// edits cannot drift apart silently. The table is still built in Kotlin, because a
// `GameplayEffectDef` holds interned attribute ids and a `TagSet` that only a running game has;
// what this file removes is the *unchecked* copy.
//
// ## Seconds here, ticks there
//
// `period` is seconds, as a designer writes it. `udea-gas`'s `ticksFromSeconds` is the one
// deterministic conversion and it runs once, at load. 0.25s at 60Hz is `HEAL_PERIOD_TICKS = 15`.
//
// ## `knockback` is deliberately absent
//
// The corpus declared one with `cues = listOf("KnockbackCue")` and no attribute target - an effect
// whose entire behaviour was a physics impulse applied from inside a presentation cue. A knockback
// is simulation, so `MobaExecs` applies it as an impulse on `Motion` and `MobaCues.KNOCKBACK` is
// only the flash that goes with it. Declaring it here would put a handle and a table entry behind
// nothing.

gameplayEffect(
    name = "damage",
    target = "health",
    modifierType = "Additive",
    magnitude = setByCaller("Data.Damage"),
    effectDuration = instant(),
    cues = listOf("DamageCue"),
)

gameplayEffect(
    name = "heal",
    target = "health",
    modifierType = "Additive",
    magnitude = setByCaller("Data.Heal"),
    effectDuration = instant(),
    cues = listOf("HealCue"),
)

// `250.milliseconds` in the source corpus.
gameplayEffect(
    name = "heal_over_time",
    target = "health",
    modifierType = "Additive",
    magnitude = setByCaller("Data.Heal"),
    effectDuration = duration("Data.Duration"),
    period = 0.25F,
    cues = listOf("HealCue"),
)

gameplayEffect(
    name = "stun",
    effectDuration = duration("Data.Duration"),
    tags = listOf("Debuffs.Stunned"),
)

gameplayEffect(
    name = "cost_mana",
    target = "mana",
    modifierType = "Additive",
    magnitude = setByCaller("Cost.Mana"),
    effectDuration = instant(),
)

gameplayEffect(
    name = "cooldown",
    effectDuration = duration("Data.Cooldown"),
)

// Applied by nothing today: `UnitBlueprint.dress` seeds `healthRegen` as an attribute and no unit
// is granted this effect. It is declared because `MobaEffects.passiveHealthRegen` is in the table
// the simulation runs on, and an effect in the table with no authored declaration is exactly the
// drift this file exists to stop.
gameplayEffect(
    name = "passive_health_regen",
    target = "health",
    modifierType = "Additive",
    magnitude = attribute("healthRegen"),
    effectDuration = infinite(),
    period = 1.0F,
)

// Carried by a corpse and by nothing else. It modifies no attribute: its whole content is the
// `Debuffs.Dead` tag, which every ability in this game names in `blockedBy`, so a dead champion
// cannot cast through a key press, through the autopilot or through the `activateAbility` RPC.
// `DeathTagSystem` applies it and takes it away; see `MobaTags.DEAD` for what it fixes.
gameplayEffect(
    name = "dead",
    effectDuration = infinite(),
    tags = listOf("Debuffs.Dead"),
)
