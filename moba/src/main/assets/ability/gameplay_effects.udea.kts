// Migrated from example/src/main/resources/assets/ability/gameplay_effects.udea.kts (issue #93).
//
// `gameplayEffect` has no runtime type: `udea-gas` declares no `GameplayEffect`, so these are
// `AssetKind.Unpublishable` and read back as opaque assets. Attribute targets, modifier types,
// cues and tags are names rather than `KProperty` and enum references, which is what stops an
// asset edit from depending on the game module compiling.

gameplayEffect(
    name = "damage",
    target = "CharacterAttributeSet.health",
    modifierType = "Additive",
    magnitude = setByCaller("Data.Damage"),
    effectDuration = instant(),
    cues = listOf("DamageCue"),
)

gameplayEffect(
    name = "knockback",
    effectDuration = instant(),
    cues = listOf("KnockbackCue"),
)

gameplayEffect(
    name = "stun",
    effectDuration = duration("Data.Duration"),
    tags = listOf("Debuffs.Stunned"),
)

gameplayEffect(
    name = "cost_mana",
    target = "CharacterAttributeSet.mana",
    modifierType = "Additive",
    magnitude = setByCaller("Cost.Mana"),
    effectDuration = instant(),
)

gameplayEffect(
    name = "heal",
    target = "CharacterAttributeSet.health",
    modifierType = "Additive",
    magnitude = setByCaller("Data.Heal"),
    effectDuration = instant(),
)

gameplayEffect(
    name = "heal_over_time",
    target = "CharacterAttributeSet.health",
    modifierType = "Additive",
    magnitude = setByCaller("Data.Heal"),
    effectDuration = duration("Data.Duration"),
    // `250.milliseconds` in the source. Seconds, because the pack format holds primitives and a
    // `kotlin.time.Duration` has no bundle encoding.
    period = 0.25F,
)

gameplayEffect(
    name = "cooldown",
    effectDuration = duration("Data.Cooldown"),
)

gameplayEffect(
    name = "passive_health_regen",
    target = "CharacterAttributeSet.health",
    modifierType = "Additive",
    magnitude = attribute("CharacterAttributeSet.healthRegen"),
    effectDuration = infinite(),
    period = 1.0F,
)
