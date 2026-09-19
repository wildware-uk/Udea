// The soldier's arrow.

ability(
    name = "soldier_fire_arrow",
    exec = "dev.wildware.moba.ability.FireArrowExec",
    // `FireArrowExec.RANGE`: `4.0f * MobaScale.WORLD`.
    range = 160F,
    cooldown = reference("ability/cooldown"),
    setByCaller = mapOf("Data.Cooldown" to 5.0F),
    blockedBy = listOf("Debuffs.Stunned"),
    blockAnimations = true,
    tags = listOf("AIHint.Ranged", "AIHint.Damage", "AIHint.TargetEnemy"),
)
