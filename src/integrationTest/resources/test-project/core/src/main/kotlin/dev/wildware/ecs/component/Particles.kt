package dev.wildware.ecs.component

import com.badlogic.gdx.graphics.g2d.ParticleEffect
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType

class Particles : Component<Particles> {
    val particleEffects = mutableListOf<ParticleEffect>()
    override fun type()= Particles
    companion object : ComponentType<Particles>()
}
