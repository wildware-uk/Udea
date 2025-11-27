import dev.wildware.udea.ability.duration
import dev.wildware.udea.ability.gameplayEffect
import dev.wildware.udea.example.ability.Debuffs

gameplayEffect(
    effectDuration = duration(.5F),
    tags = {
        add(Debuffs.Stunned)
    }
)
