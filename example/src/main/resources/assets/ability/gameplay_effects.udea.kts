import dev.wildware.udea.ability.ModifierType.Additive
import dev.wildware.udea.ability.duration
import dev.wildware.udea.ability.gameplayEffect
import dev.wildware.udea.ability.instant
import dev.wildware.udea.ability.value
import dev.wildware.udea.example.ability.*

bundle {
    gameplayEffect(
        name = "damage",
        target = CharacterAttributeSet::health,
        modifierType = Additive,
        magnitude = value(Data.Damage),
        effectDuration = instant(),
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
        effectDuration = duration(Data.Duration),
        tags = {
            add(Debuffs.Stunned)
        }
    )

    gameplayEffect(
        name = "cost_mana",
        target = CharacterAttributeSet::mana,
        modifierType = Additive,
        magnitude = value(Cost.Mana),
        effectDuration = instant()
    )

    gameplayEffect(
        name = "heal",
        target = CharacterAttributeSet::health,
        modifierType = Additive,
        magnitude = value(Data.Heal),
        effectDuration = instant(),
    )
    
    gameplayEffect(
        name = "cooldown",
        effectDuration = duration(Data.Cooldown),
    )
}
