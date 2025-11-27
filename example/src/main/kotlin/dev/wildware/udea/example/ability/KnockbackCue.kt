package dev.wildware.udea.example.ability

import com.badlogic.gdx.math.Vector2.Zero
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.ability.GameplayEffectCue
import dev.wildware.udea.ability.GameplayEffectSpec
import dev.wildware.udea.ecs.component.physics.Body
import dev.wildware.udea.get
import dev.wildware.udea.position

object KnockbackCue : GameplayEffectCue {
    context(world: World)
    override fun onGameplayEffectApplied(
        source: Entity,
        target: Entity,
        spec: GameplayEffectSpec
    ) {
        val diff = target.position.cpy().sub(source.position).nor()
        val knockback = diff.scl(spec.getSetByCallerMagnitude(Data.Knockback))
        target[Body].body.applyLinearImpulse(knockback, Zero, true)
    }
}
