package dev.wildware.udea.ability

import com.github.quillraven.fleks.World
import dev.wildware.udea.hasAuthority

class AwaitTargetTask(
    val find: () -> AbilityTarget,
    val onTarget: (AbilityTarget) -> Unit
) {
    context(world: World, spec: AbilitySpec)
    fun start() {
        val entity = spec.entity

        if(world.hasAuthority(entity)) {
            val target = find()
            spec.target = target
            onTarget(target)
        } else {
            spec.onTargetSet {
                onTarget(it)
            }
        }
    }
}

/**
 * Use in abilities to find a target to activate the ability against.
 * [find] is only called if you have authority over the entity.
 * [onTarget] is called when a target is acquired, either from [find] or
 * */
context(world: World, spec: AbilitySpec)
fun awaitTarget(find: () -> AbilityTarget, onTarget: (AbilityTarget) -> Unit)=
    AwaitTargetTask(find, onTarget).start()
