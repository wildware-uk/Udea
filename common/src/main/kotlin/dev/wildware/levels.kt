package dev.wildware

import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.Snapshot
import dev.wildware.udea.AssetType
import kotlin.reflect.KClass

/**
 * A level is a collection of entities and components that define a game level.
 * */
data class Level(
    val systems: List<KClass<out IntervalSystem>> = emptyList(),
    val entities: List<EntityInstance> = emptyList()
) {
    companion object : AssetType<Level>() {
        override val id: String = "level"
    }
}

/**
 * Contains the components required to instantiate an entity.
 * */
data class EntityInstance(
    val snapshot: Snapshot
) {
    companion object {
        val Empty = EntityInstance(Snapshot(emptyList(), emptyList()))
    }
}
