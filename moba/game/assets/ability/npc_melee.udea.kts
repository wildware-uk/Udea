// The basic attack every unit in this game has.
//
// Migrated from `moba/src/main/assets/ability/npc_melee.udea.kts`, with the one correction the
// merge forces: `exec` named `dev.wildware.udea.example.ability.UnitMeleeAttack`, a class in the
// deleted `example` module. The class that runs this ability in *this* game is
// `dev.wildware.moba.ability.MeleeAttackExec`, and `MobaAuthoredContentTest` checks that the name
// resolves - an `exec` naming a class no `AbilityExecRegistry` knows is a build-time typo that
// used to be a load-time absence.
//
// `range` is authored and read: `MeleeAttackExec.RANGE` is the same number, and the autopilot's
// `TargetPolicy` uses it. The source corpus wrote `range = 0.5F` in a world where a character was
// one unit across; a character here is about forty (see `MobaScale`).

ability(
    name = "npc_melee",
    exec = "dev.wildware.moba.ability.MeleeAttackExec",
    // `MeleeAttackExec.RANGE`, in world units: `0.8f * MobaScale.WORLD`.
    range = 32F,
    cooldown = reference("ability/cooldown"),
    setByCaller = mapOf("Data.Cooldown" to 0.8F),
    blockedBy = listOf("Debuffs.Stunned"),
    blockAnimations = true,
    tags = listOf("AIHint.Damage", "AIHint.TargetEnemy", "AIHint.Melee"),
)
