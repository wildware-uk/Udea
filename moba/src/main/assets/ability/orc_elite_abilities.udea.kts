// Migrated from example/src/main/resources/assets/ability/orc_elite_abilities.udea.kts (#93).
//
// The source wrote `blockedBy = { Debuffs.Stunned }` — an expression statement inside a list
// builder, with no `add`, so the list it built was empty and the ability was never blocked.
// Migrated as what it plainly meant.

ability(
    name = "orc_elite_spin",
    exec = "dev.wildware.udea.example.ability.OrcSpinAttack",
    cooldown = reference("ability/cooldown"),
    setByCaller = mapOf("Data.Cooldown" to 15.0F),
    tags = listOf("AIHint.AOE", "AIHint.Damage", "AIHint.Melee", "AIHint.AimEnemy"),
    blockedBy = listOf("Debuffs.Stunned"),
    blockAnimations = true,
)
