package dev.wildware.spellcastgame.spell

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.ParticleEffect
import dev.wildware.udea.assets.Asset
import dev.wildware.udea.assets.AssetType
import dev.wildware.udea.assets.Assets
import dev.wildware.ability.GameplayEffect
import ktx.assets.toInternalFile

data class SpellElement(
    val gravity: Boolean,
    val speed: Float,
    val size: Float,
    val particleEffect: ParticleEffect,
    val onHitEffect: Asset<GameplayEffect>,
    val colour: Color
) {
    companion object : AssetType<SpellElement>() {
        override val id: String = "spell_element"
    }
}

fun spellElement(
    name: String,
    gravity: Boolean,
    speed: Float,
    size: Float,
    particleEffect: ParticleEffect,
    onHitEffect: Asset<GameplayEffect>,
    colour: Color = Color(0.1F, 0.1F, 0.1F, 0.5F),
) {
    Assets[SpellElement][name] = SpellElement(gravity, speed, size, particleEffect, onHitEffect, colour)
}

val ARCANE_PARTICLE = ParticleEffect().apply {
    load(
        "particles/arcane/arcane.p".toInternalFile(),
        "particles/arcane".toInternalFile()
    )
    scaleEffect(0.01F)
}

val DIVINE_PARTICLE = ParticleEffect().apply {
    load(
        "particles/divine/divine.p".toInternalFile(),
        "particles/divine".toInternalFile()
    )
    scaleEffect(0.02F)
}

val FIRE_PARTICLE = ParticleEffect().apply {
    load(
        "particles/fire/fire.p".toInternalFile(),
        "particles/fire".toInternalFile()
    )
    scaleEffect(0.02F)
}

val BLOOD_PARTICLE = ParticleEffect().apply {
    load(
        "particles/blood/blood.p".toInternalFile(),
        "particles/blood".toInternalFile()
    )
    scaleEffect(0.02F)
}
