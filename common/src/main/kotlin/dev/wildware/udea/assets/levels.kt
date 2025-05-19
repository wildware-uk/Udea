package dev.wildware.udea.assets

import com.github.quillraven.fleks.IntervalSystem
import com.github.quillraven.fleks.Snapshot

/**
 * A level is a collection of entities and components that define a game level.
 * */
data class Level(
    /**
     * The systems that will be used to update the entities in this level.
     * */
    val systems: List<UClass<out IntervalSystem>> = listOf(UClass("dev.wildware.udea.ecs.system.Box2DSystem")),

    /**
     * Component snapshots that will be placed on entities.
     * */
    val entities: List<Snapshot> = emptyList()
) : Asset()
