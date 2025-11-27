import dev.wildware.udea.ability.GameplayEffectDuration.Instant
import dev.wildware.udea.ability.ModifierType.Additive
import dev.wildware.udea.ability.gameplayEffect
import dev.wildware.udea.ability.value
import dev.wildware.udea.example.ability.CharacterAttributeSet
import dev.wildware.udea.example.ability.MeleeHitCue

gameplayEffect(
    target = CharacterAttributeSet::health,
    modifierType = Additive,
    source = value(-10F),
    effectDuration = Instant,
    cues = {
        add(MeleeHitCue)
    }
)
