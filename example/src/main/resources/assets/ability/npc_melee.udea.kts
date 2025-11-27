import dev.wildware.udea.example.ability.Debuffs
import dev.wildware.udea.example.ability.NPCMeleeAttack

ability(
    exec = NPCMeleeAttack::class,
    blockedBy = {
        add(Debuffs.Stunned)
    }
)
