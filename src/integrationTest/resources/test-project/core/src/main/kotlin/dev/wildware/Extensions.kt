package dev.wildware

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.ecs.Blueprint
import dev.wildware.ecs.component.BlueprintComp
import dev.wildware.ecs.component.Networkable
import dev.wildware.math.Vector2
import dev.wildware.network.NetworkComponent
import dev.wildware.udea.assets.Asset
import kotlin.contracts.ExperimentalContracts


inline fun <T> MutableList<T>.processAndRemoveEach(onEach: (T) -> Unit) {
    while (this.isNotEmpty()) {
        onEach(this.removeFirst())
    }
}

fun Entity.blueprint(world: World): Asset<Blueprint> {
    with(world) {
        return this@blueprint[BlueprintComp].blueprint
    }
}

fun World.getNetworkEntity(id: Int): Entity = asEntityBag()
    .find { it.getOrNull(Networkable)?.remoteId == id } ?: error("No entity with network id: $id")

fun World.getNetworkEntityOrNull(id: Int): Entity? = asEntityBag()
    .find { it.getOrNull(Networkable)?.remoteId == id }

fun World.hasAuthority(entity: Entity) =
    entity[Networkable].owner == game.clientId

/**
 * @return [Vector2] perpendicular to this vector.
 * */
fun Vector2.perp() = set(-y, x)

@OptIn(ExperimentalContracts::class)
fun Component<*>.isNetworkable(): Boolean {
    return this.type() is NetworkComponent<*>
}

fun Component<*>.getNetworkData(): NetworkComponent<*> {
    return this.type() as NetworkComponent<*>
}
