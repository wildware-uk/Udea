package dev.wildware.spellcastgame.spell

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity
import dev.wildware.udea.assets.Asset
import dev.wildware.math.Vector2
import dev.wildware.network.NetworkComponent
import dev.wildware.udea.network.Networked
import dev.wildware.network.SyncStrategy.Create
import dev.wildware.spellcastgame.spell.modifiers.Rune
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Networked
@Serializable
class SpellComponent(
    val element: Asset<@Contextual SpellElement>,
    @Transient
    val runes: List<Rune> = emptyList(),
    val source: Entity
) : Component<SpellComponent> {

    var active = true
    var target: Vector2? = null
    var speed = 7.0F
    var size = 1.0F
    var power = 1.0F
    var age = 0F
    var lifetime: Float = 5F

    fun markInactive() {
        active = false
    }

    override fun type() = SpellComponent

    companion object : ComponentType<SpellComponent>(), NetworkComponent<SpellComponent> {
        override val syncStrategy = Create
    }
}

fun List<Rune>.copyRunes() = map { it.clone() }
