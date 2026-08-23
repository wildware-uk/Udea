// Migrated from example/src/main/resources/assets/ability/priest_abilities.udea.kts (#93).
//
// `blockedBy = { Debuffs.Stunned }` in the source built an empty list; see orc_elite_abilities.

ability(
    name = "priest_heal",
    exec = "dev.wildware.udea.example.ability.PriestHeal",
    costs = listOf(reference("ability/cost_mana")),
    cooldown = reference("ability/cooldown"),
    setByCaller = mapOf(
        "Cost.Mana" to -10F,
        "Data.Cooldown" to 10F,
    ),
    blockedBy = listOf("Debuffs.Stunned"),
    blockAnimations = true,
    tags = listOf("AIHint.AOE", "AIHint.Heal"),
)
