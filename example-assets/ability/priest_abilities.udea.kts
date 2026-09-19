import dev.wildware.udea.example.ability.AIHint
import dev.wildware.udea.example.ability.Cost
import dev.wildware.udea.example.ability.Data
import dev.wildware.udea.example.ability.Debuffs
import dev.wildware.udea.example.ability.PriestHeal

bundle {
    ability(
        name = "priest_heal",
        exec = PriestHeal::class,
        cost = {
            add(reference("ability/cost_mana"))
        },
        cooldownEffect = reference("ability/cooldown"),
        setByCallerTags = mapOf(
            Cost.Mana to -10F,
            Data.Cooldown to 10F,
        ),
        blockedBy = {
            Debuffs.Stunned
        },
        blockAnimations = true,
        tags = {
            add(AIHint.AOE)
            add(AIHint.Heal)
        }
    )
}
