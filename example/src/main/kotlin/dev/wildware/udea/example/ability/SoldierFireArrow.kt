package dev.wildware.udea.example.ability

import com.github.quillraven.fleks.World
import dev.wildware.udea.ability.AbilitySpec
import dev.wildware.udea.ability.AbilityExec
import dev.wildware.udea.ability.AbilityInfo
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.ecs.component.base.Transform
import dev.wildware.udea.ecs.component.physics.Body
import dev.wildware.udea.ecs.system.AnimationSetSystem
import dev.wildware.udea.example.component.Projectile
import dev.wildware.udea.example.component.Team
import dev.wildware.udea.position

class SoldierFireArrow : AbilityExec() {

    context(world: World, activation: AbilitySpec)
    override fun activate(abilityInfo: AbilityInfo) {
        val (source, targetPos) = abilityInfo

        commitAbility(abilityInfo)

        world.system<AnimationSetSystem>().setAnimation(source, "soldier_fire_arrow")?.apply {
            onNotify("fire_arrow") {
                val heading = targetPos.cpy().sub(source.position).nor().scl(5.0F)
                Assets.get<Blueprint>("blueprint/arrow").newInstance(world) {
                    it[Projectile].owner = source
                    it[Team].teamId = source[Team].teamId
                    it[Transform].position.set(source.position)
                    it[Body].body.setLinearVelocity(heading.x, heading.y)
                }
            }

            onFinish { endAbility() }
        }
    }
}
