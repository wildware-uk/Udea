package dev.wildware.udea.ecs.render

import com.badlogic.gdx.graphics.g2d.ParticleEffect as LibGDXParticleEffect
import com.github.quillraven.fleks.Component
import dev.wildware.udea.ecs.component.UdeaComponentType

class ParticleEffect : Component<ParticleEffect> {
    val particleEffects = mutableListOf<LibGDXParticleEffect>()
    override fun type()= ParticleEffect
    companion object : UdeaComponentType<ParticleEffect>()
}
