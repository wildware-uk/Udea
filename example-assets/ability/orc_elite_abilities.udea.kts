import dev.wildware.udea.example.ability.AIHint
import dev.wildware.udea.example.ability.Data
import dev.wildware.udea.example.ability.Debuffs
import dev.wildware.udea.example.ability.OrcSpinAttack

bundle {
    ability(
        name = "orc_elite_spin",
        exec = OrcSpinAttack::class,
        cooldownEffect = reference("ability/cooldown"),
        setByCallerTags = mapOf(
            Data.Cooldown to 15.0F
        ),
        tags = {
            add(AIHint.AOE)
            add(AIHint.Damage)
            add(AIHint.Melee)
            add(AIHint.AimEnemy)
        },
        blockedBy = {
            Debuffs.Stunned
        },
        blockAnimations = true
    )
}
