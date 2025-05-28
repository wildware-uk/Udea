package dev.wildware.ability

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World

interface AttributeModificationExec {
    fun World.onAttributeModified(source: Entity, target: Entity, effect: GameplayEffectSpec)
}
