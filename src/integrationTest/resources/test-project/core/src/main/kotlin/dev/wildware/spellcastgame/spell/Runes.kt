package dev.wildware.spellcastgame.spell

import dev.wildware.udea.assets.Asset
import dev.wildware.udea.assets.AssetType
import dev.wildware.udea.assets.Assets
import dev.wildware.spellcastgame.spell.modifiers.SpellModifier
import kotlinx.serialization.Serializable

fun rune(id: String, name: String, description: String, spellModifier: SpellModifier) {
    Assets[RuneDescriptor][id] = RuneDescriptor(name, description, spellModifier)
}

@Serializable
data class RuneDescriptor(
    val name: String,
    val description: String,
    val spellModifier: SpellModifier
) {
    companion object : AssetType<RuneDescriptor>() {
        override val id: String = "rune"
    }
}

sealed interface RuneParameter<T> {
    val name: String
    val defaultValue: T?

    data class FloatParameter(
        override val name: String,
        override val defaultValue: Float? = null
    ) :
        RuneParameter<Float>

    data class IntParameter(
        override val name: String,
        override val defaultValue: Int? = null
    ) : RuneParameter<Int>

    data class BoolParameter(
        override val name: String,
        override val defaultValue: Boolean? = null
    ) :
        RuneParameter<Boolean>

    data class SpellElementParameter(
        override val name: String,
        override val defaultValue: Asset<SpellElement>? = null
    ) : RuneParameter<Asset<SpellElement>>
}
