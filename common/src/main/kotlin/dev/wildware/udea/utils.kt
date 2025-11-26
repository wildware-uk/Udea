package dev.wildware.udea

import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.assets.UClass
import dev.wildware.udea.ecs.component.NetworkComponent
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.ecs.component.base.Blueprint
import dev.wildware.udea.ecs.component.base.Networkable
import dev.wildware.udea.ecs.component.base.Transform
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
        return this@blueprint[Blueprint].blueprint.value
    }
}

// TODO this is unacceptable!!! use a family instead.
fun World.getNetworkEntity(remoteEntity: Entity): Entity = asEntityBag()
    .find { it.getOrNull(Networkable)?.remoteEntity == remoteEntity } ?: error("No entity with network id: $remoteEntity")

fun World.getNetworkEntityOrNull(remoteEntity: Entity): Entity? = asEntityBag()
    .find { it.getOrNull(Networkable)?.remoteEntity == remoteEntity }

fun World.hasAuthority(entity: Entity) =
    (Networkable !in entity) || entity[Networkable].owner == gameScreen.clientId

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
    camera: Camera? = null,
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

/**
 * Shortcut to get an entity position.
 * */
context(world: World)
val Entity.position: Vector2
    get() = this[Transform].position

/**
 * Returns the remote entity reference for an entity.
 * */
context(world: World)
val Entity.remoteEntity: Entity
    get()= this[Networkable].remoteEntity