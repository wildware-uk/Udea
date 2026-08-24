// The priest's heal-over-time on nearby damaged allies.
//
// `Cost.Mana` is authored positive here and negated when it is spent, which is the one place this
// file differs from the source corpus's `-10F`. `MobaAbilities.PRIEST_HEAL_MANA_COST` says the
// same thing in the same direction; a cost that is a negative number in one file and a positive
// one in the other is how a heal comes to *grant* mana.

ability(
    name = "priest_heal",
    exec = "dev.wildware.moba.ability.PriestHealExec",
    // `PriestHealExec.RADIUS`: `3.0f * MobaScale.WORLD`.
    range = 120F,
    cooldown = reference("ability/cooldown"),
    costs = listOf(reference("ability/cost_mana")),
    setByCaller = mapOf(
        "Cost.Mana" to 10F,
        "Data.Cooldown" to 10F,
    ),
    blockedBy = listOf("Debuffs.Stunned"),
    blockAnimations = true,
    tags = listOf("AIHint.AOE", "AIHint.Heal"),
)
