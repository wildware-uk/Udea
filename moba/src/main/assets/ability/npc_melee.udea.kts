// Migrated from example/src/main/resources/assets/ability/npc_melee.udea.kts (issue #93).

ability(
    name = "npc_melee",
    exec = "dev.wildware.udea.example.ability.UnitMeleeAttack",
    range = 0.5F,
    blockedBy = listOf("Debuffs.Stunned"),
    cooldown = reference("ability/cooldown"),
    setByCaller = mapOf("Data.Cooldown" to 0.8F),
    blockAnimations = true,
    tags = listOf("AIHint.Damage", "AIHint.TargetEnemy", "AIHint.Melee"),
)
