// Migrated from example/src/main/resources/assets/ability/soldier_abilities.udea.kts (#93).

ability(
    name = "soldier_fire_arrow",
    exec = "dev.wildware.udea.example.ability.SoldierFireArrow",
    range = 2.0F,
    blockedBy = listOf("Debuffs.Stunned"),
    cooldown = reference("ability/cooldown"),
    setByCaller = mapOf("Data.Cooldown" to 5.0F),
    blockAnimations = true,
    tags = listOf("AIHint.Ranged", "AIHint.Damage", "AIHint.TargetEnemy"),
)
