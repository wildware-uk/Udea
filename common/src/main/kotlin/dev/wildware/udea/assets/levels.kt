package dev.wildware.udea.assets

import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.Snapshot
import dev.wildware.udea.ecs.system.BackgroundDrawSystem
import dev.wildware.udea.ecs.system.Box2DSystem
import dev.wildware.udea.ecs.system.SpriteBatchSystem
import dev.wildware.udea.uClass

/**
 * A level is a collection of entities and components that define a game level.
 * */
data class Level(
    /**
     * The systems that will be used to update the entities in this level.
     * */
    val systems: List<UClass<out IntervalSystem>> = listOf(
        Box2DSystem::class.uClass,
        SpriteBatchSystem::class.uClass,
    ),

    /**
     * Component snapshots that will be placed on entities.
     * */
    val entities: List<Snapshot> = emptyList()
) : Asset()
