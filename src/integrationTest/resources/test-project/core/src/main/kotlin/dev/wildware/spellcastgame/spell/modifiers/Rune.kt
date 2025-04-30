package dev.wildware.spellcastgame.spell.modifiers

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.spellcastgame.spell.RuneParameter
import dev.wildware.spellcastgame.spell.SpellComponent
import dev.wildware.spellcastgame.spell.copyRunes
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.Serializable

typealias ParamValue = @Serializable(with = PolymorphicSerializer::class) Any

@Serializable
data class Rune(
    val spellModifier: SpellModifier,
    val parameters: MutableMap<String, ParamValue> = mutableMapOf(),
    val children: List<Rune> = emptyList<Rune>(),
    var active: Boolean = true
) {
    var activeTime = 0F

    fun World.apply(entity: Entity, spellComponent: SpellComponent) {
        if (active) {
            activeTime += deltaTime
            with(spellModifier) {
                applyToProjectile(this@Rune, spellComponent, entity)
            }
        }
    }

    inline operator fun <reified T> get(key: RuneParameter<T>): T {
        return parameters.getOrPut(key.name) { key.defaultValue!! } as T
    }

    inline fun <reified T> getOrNull(key: RuneParameter<T>): T? {
        return parameters[key.name] as T?
    }

    operator fun set(key: RuneParameter<*>, value: Any) {
        parameters[key.name] = value
    }

    fun clone(): Rune = this.copy(
        parameters = parameters.toMutableMap(),
        children = children.copyRunes(),
    ).apply {
        this.activeTime = activeTime
    }
}

sealed interface MaxChildren {
    data object None : MaxChildren
    data object Unlimited : MaxChildren
    open class Value(val max: Int) : MaxChildren
    data object One : Value(1)
    data object Two : Value(2)
}
