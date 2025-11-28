package dev.wildware.udea.example

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.Vector2
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.component.render.AnimationHolder
import dev.wildware.udea.example.assets.Effect
import dev.wildware.udea.example.component.Effect as EffectComponent

context(world: World)
fun spawnEffect(effect: AssetReference<Effect>, entity: Entity) {
    val effect = effect.value

    Assets.get<Blueprint>("blueprint/effect").newInstance(world) {
        it += EffectComponent(effect)
        it += AnimationHolder(effect.animationSet, effect.animation)
        it[Transform].parent = entity[Transform]
    }
}
