package dev.wildware.ecs

import dev.wildware.ecs.component.BlueprintComp
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.EntityCreateContext
import com.github.quillraven.fleks.World
import dev.wildware.udea.assets.Asset
import dev.wildware.udea.assets.AssetType
import dev.wildware.udea.assets.Assets
import dev.wildware.ecs.component.DebugComponent
import dev.wildware.ecs.component.Transform

class Blueprint(
    val name: String,
    val builder: EntityCreateContext.(Entity) -> Unit = {}
)  {
    companion object : AssetType<Blueprint>() {
        override val id: String = "blueprint"
    }
}

fun Asset<Blueprint>.newInstance(world: World, init: EntityCreateContext.(Entity) -> Unit = {})= world.entity {
    it += Transform()
    it += BlueprintComp(this@newInstance)
    it += DebugComponent(debugPhysics = true)
    this@newInstance.value.builder.invoke(this, it)
    init(this, it)
}

fun blueprint(name: String, builder: EntityCreateContext.(Entity) -> Unit) {
    Assets[Blueprint][name] = Blueprint(name, builder)
}

fun blueprint(name: String, extends: String, builder: EntityCreateContext.(Entity) -> Unit): Blueprint {
    return Blueprint(name) {
        Assets[Blueprint][extends]().builder.invoke(this, it)
        builder.invoke(this, it)
    }
}
