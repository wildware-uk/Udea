package dev.wildware.udea.ability

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World

/**
 * Allows modification of GameplayEffectSpec attributes.
 * */
interface AttributeModificationExec {
    context(world: World)
    fun onAttributeModified(source: Entity, target: Entity, effect: GameplayEffectSpec)
}
