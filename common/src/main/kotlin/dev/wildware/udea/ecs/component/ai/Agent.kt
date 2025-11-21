package dev.wildware.udea.ecs.component.ai

import com.badlogic.gdx.math.Vector2
import com.github.quillraven.fleks.Component
import dev.wildware.udea.ecs.component.UdeaComponentType

/**
 * Represents an agent in the game world, agents can move around.
 * */
data class Agent(
    /**
     * Whether this agent should use pathfinding or not.
     * */
    var pathfindingStyle: PathfindingStyle = None,
) : Component<Agent> {

    /**
     * Target for this agent to move towards.
     * */
    var moveTarget: Vector2? = null

    override fun type() = Agent

    companion object : UdeaComponentType<Agent>()
}

/**
 * Defines how this agent moves around the world.
 * */
enum class PathfindingStyle {
    /**
     * No path-finding, the agent will attempt to move directly towards the target.
     * */
    None,

    /**
     * Agent will use floor-based path finding.
     * */
    Walk,

    /**
     * Agent will fly around obstacles.
     * */
    Fly
}
