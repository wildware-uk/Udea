package dev.wildware.events

import com.github.quillraven.fleks.Entity
import dev.wildware.udea.assets.Asset
import dev.wildware.ability.Ability

class Events {
    private val listeners = mutableListOf<EventListener>()

    fun fire(event: Event) {
        listeners.forEach { it.onEvent(event) }
    }

    fun register(listener: EventListener) {
        listeners += listener
    }

    fun unregister(listener: EventListener) {
        listeners -= listener
    }
}

fun interface EventListener {
    fun onEvent(event: Event)
}

sealed interface Event {
    data class ProjectileHit(val source: Entity, val target: Target, val projectile: Entity)
    data class GameplayEffectApplied(val source: Entity, val target: Entity)
    data class AbilityActivated(val source: Entity, val ability: Asset<Ability>)
}
