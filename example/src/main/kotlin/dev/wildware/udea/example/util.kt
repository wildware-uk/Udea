package dev.wildware.udea.example

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.contains
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.component.render.AnimationHolder
import dev.wildware.udea.ecs.system.Box2DSystem
import dev.wildware.udea.example.assets.Effect
import dev.wildware.udea.example.component.GameUnit
import dev.wildware.udea.get
import dev.wildware.udea.position
import ktx.box2d.query
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

context(world: World)
fun getUnitsWithin(entity: Entity, radius: Float, alive: Boolean = true): Set<Entity> {
    val result = mutableSetOf<Entity>()
    val position = entity.position
    world.system<Box2DSystem>().box2DWorld.query(
        position.x - radius,
        position.y - radius,
        position.x + radius,
        position.y + radius
    ) { fixture ->
        val other = fixture.body?.userData as? Entity ?: return@query true
        if (GameUnit in other && other[GameUnit].isDead != alive) {
            if (other.position.dst(position) > radius) return@query true
            result += other
        }

        true
    }

    return result
}
