import dev.wildware.udea.example.ability.AIHint
import dev.wildware.udea.example.ability.Data
import dev.wildware.udea.example.ability.Debuffs
import dev.wildware.udea.example.ability.UnitMeleeAttack

bundle {
    ability(
        name = "npc_melee",
        exec = UnitMeleeAttack::class,
        range = 0.5F,
        blockedBy = {
            add(Debuffs.Stunned)
        },
        cooldownEffect = reference("ability/cooldown"),
        setByCallerTags = mapOf(
            Data.Cooldown to 0.8F
        ),
        blockAnimations = true,
        tags = {
            add(AIHint.Damage)
            add(AIHint.TargetEnemy)
            add(AIHint.Melee)
        }
    )
}
