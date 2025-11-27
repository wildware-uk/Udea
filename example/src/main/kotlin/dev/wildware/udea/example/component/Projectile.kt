package dev.wildware.udea.example.component

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity
import dev.wildware.udea.ability.GameplayEffect
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.example.ability.OnHitEffect

class Projectile(
    val onHitEffects: List<OnHitEffect>
) : Component<Projectile> {
    var owner: Entity? = null
    override fun type() = Projectile

    companion object : ComponentType<Projectile>()
}
