// The elite orc's spin, and the only ability granted to exactly one unit.
//
// The source corpus wrote `blockedBy = { Debuffs.Stunned }` - an expression statement inside a
// list builder, with no `add`, so the list was empty and no ability was ever blocked by a stun.
// Migrated as what it plainly meant, which is also what `MobaAbilities` implements.

ability(
    name = "orc_elite_spin",
    exec = "dev.wildware.moba.ability.OrcSpinExec",
    // `OrcSpinExec.RADIUS`: `1.0f * MobaScale.WORLD`. The corpus wrote no range at all and the
    // autopilot used a number of its own, which is the decoration this closes.
    range = 40F,
    cooldown = reference("ability/cooldown"),
    setByCaller = mapOf("Data.Cooldown" to 15.0F),
    blockedBy = listOf("Debuffs.Stunned"),
    blockAnimations = true,
    tags = listOf("AIHint.AOE", "AIHint.Damage", "AIHint.Melee", "AIHint.AimEnemy"),
)
