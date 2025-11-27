import dev.wildware.udea.ability.duration
import dev.wildware.udea.ability.gameplayEffect
import dev.wildware.udea.ability.instant
import dev.wildware.udea.ability.value
import dev.wildware.udea.example.ability.CharacterAttributeSet
import dev.wildware.udea.example.ability.DamageCue
import dev.wildware.udea.example.ability.Data
import dev.wildware.udea.example.ability.Debuffs
import dev.wildware.udea.example.ability.KnockbackCue

bundle {
    gameplayEffect(
        name = "damage",
        target = CharacterAttributeSet::health,
        modifierType = Additive,
        magnitude = value(Data.Damage),
        effectDuration = Instant,
        cues = {
            add(DamageCue)
        }
    )

    gameplayEffect(
        name = "knockback",
        effectDuration = instant(),
        cues = {
            add(KnockbackCue)
        }
    )

    gameplayEffect(
        name = "stun",
        effectDuration = duration(Data.StunDuration),
        tags = {
            add(Debuffs.Stunned)
        }
    )
}
