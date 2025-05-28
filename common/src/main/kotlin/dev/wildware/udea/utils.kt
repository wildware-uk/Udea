package dev.wildware.udea

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.assets.UClass
import dev.wildware.udea.ecs.NetworkComponent
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.ecs.component.base.Blueprint
import dev.wildware.udea.ecs.component.base.Networkable
import dev.wildware.udea.ecs.system.BackgroundDrawSystem
import ktx.graphics.use
import kotlin.contracts.ExperimentalContracts
import kotlin.reflect.KClass
import dev.wildware.udea.assets.Blueprint as BlueprintAsset

inline fun <T> MutableList<T>.processAndRemoveEach(onEach: (T) -> Unit) {
    while (this.isNotEmpty()) {
        onEach(this.removeFirst())
    }
}

fun Entity.blueprint(world: World): BlueprintAsset {
    with(world) {
        return this@blueprint[Blueprint].blueprint
    }
}

fun World.getNetworkEntity(id: Int): Entity = asEntityBag()
    .find { it.getOrNull(Networkable)?.remoteId == id } ?: error("No entity with network id: $id")

fun World.getNetworkEntityOrNull(id: Int): Entity? = asEntityBag()
    .find { it.getOrNull(Networkable)?.remoteId == id }

fun World.hasAuthority(entity: Entity) =
    (Networkable !in entity) || entity[Networkable].owner == game.clientId

/**
 * @return [Vector2] perpendicular to this vector.
 * */
fun Vector2.perp() = set(-y, x)

@OptIn(ExperimentalContracts::class)
fun Component<*>.isNetworkable(): Boolean {
    val componentType = this.type() as? UdeaComponentType<*> ?: return false
    return componentType.networkComponent != null
}

fun Component<*>.getNetworkData(): NetworkComponent<*> {
    return (this.type() as? UdeaComponentType<*>)?.networkComponent
        ?: error("Component is not networkable")
}

inline fun SpriteBatch.use(
    camera: Camera?,
    action: (SpriteBatch) -> Unit
) {
    if(camera != null) {
        this.use(camera, action)
    } else {
        this.use(action = action)
    }
}

val <T : Any> KClass<T>.uClass: UClass<T>
    get()= UClass(this.qualifiedName ?: error("Cannot get qualified name of class: $this"))
