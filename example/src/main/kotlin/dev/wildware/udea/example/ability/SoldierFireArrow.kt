package dev.wildware.udea.example.ability

import com.github.quillraven.fleks.World
import dev.wildware.udea.Mouse
import dev.wildware.udea.ability.AbilityExec
import dev.wildware.udea.ability.AbilitySpec
import dev.wildware.udea.ability.AbilityTargeting
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.component.physics.Body
import dev.wildware.udea.ecs.system.AnimationSetSystem
import dev.wildware.udea.example.component.Projectile
import dev.wildware.udea.example.component.Team
import dev.wildware.udea.position

class SoldierFireArrow : AbilityExec() {

    context(world: World, spec: AbilitySpec)
    override fun activate() {
        commitAbility()

        world.system<AnimationSetSystem>().setAnimation(spec.entity, "soldier_fire_arrow")?.apply {
            onNotify("fire_arrow") {
                val target = spec.getTarget<AbilityTargeting.Location>()
                val heading = target.position.cpy().sub(spec.entity.position).nor().scl(5.0F)

                Assets.get<Blueprint>("blueprint/arrow").newInstance(world) {
                    it[Projectile].owner = spec.entity
                    it[Team].teamId = spec.entity[Team].teamId
                    it[Transform].position.set(spec.entity.position)
                    it[Body].body.setLinearVelocity(heading.x, heading.y)
                }
            }

            onFinish { endAbility() }
        } ?: endAbility()
    }

    context(world: World, spec: AbilitySpec)
    override fun tick() {
        spec.targeting = AbilityTargeting.Location(Mouse.mouseWorldPos)
    }
}
