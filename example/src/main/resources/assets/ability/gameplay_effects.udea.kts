import dev.wildware.udea.ability.*
import dev.wildware.udea.ability.ModifierType.Additive
import dev.wildware.udea.example.ability.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
        name = "heal_over_time",
        target = CharacterAttributeSet::health,
        modifierType = Additive,
        magnitude = value(Data.Heal),
        effectDuration = duration(Data.Duration),
        period = 250.milliseconds
    )

    gameplayEffect(
        name = "cooldown",
        effectDuration = duration(Data.Cooldown),
    )

    gameplayEffect(
        name = "passive_health_regen",
        effectDuration = infinite(),
        target = CharacterAttributeSet::health,
        modifierType = Additive,
        magnitude = value(CharacterAttributeSet::healthRegen),
        period = 1.seconds
    )
}
