import dev.wildware.udea.ability.duration
import dev.wildware.udea.ability.gameplayEffect
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
        cooldownEffect = reference("ability/npc_melee_cooldown")
    )

    gameplayEffect(
        name = "npc_melee_cooldown",
        effectDuration = duration(1.0F)
    )
}
