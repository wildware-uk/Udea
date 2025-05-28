package dev.wildware.udea.ecs

import dev.wildware.udea.ecs.UdeaSystem.Runtime.Game

annotation class UdeaSystem(
    vararg val runIn: Runtime = [Game]
) {
    enum class Runtime {
        Editor, Game
    }
}
