import dev.wildware.udea.example.ability.Data
import dev.wildware.udea.example.ability.Debuffs
import dev.wildware.udea.example.ability.SoldierFireArrow

bundle {
    ability(
        name = "soldier_fire_arrow",
        exec = SoldierFireArrow::class,
        range = 2.0F,
        blockedBy = {
            add(Debuffs.Stunned)
        },
        cooldownEffect = reference("ability/cooldown"),
        setByCallerTags = mapOf(
            Data.Cooldown to 1.0F
        ),
        blockAnimations = true
    )
}
