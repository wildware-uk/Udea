package com.example.newgame

import com.example.newgame.sim.RoverSystem
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.generated.NewGameUdeaRegistry

/**
 * This game's content, as a module: the systems it runs and the order they run in.
 *
 * A module is the unit the engine composes. Every entry point - a dedicated server, a player's
 * client, an agent's instance, a replay - builds the same [UdeaGameDef] out of the same modules,
 * which is what makes "it happens on the server and not in my client" a bug in a renderer rather
 * than a difference between two simulations.
 */
public class NewGameModule : UdeaModule {

    override val name: String get() = "new-game"

    override fun simulation(registry: SimRegistry) {
        registry.add(SimPhase.Movement, { RoverSystem() })
    }
}

/** How every entry point builds this game. */
public object NewGame {

    /**
     * The definition: the generated registry, and this game's modules.
     *
     * [NewGameUdeaRegistry] is written by `udea-codegen` from the modules on this project's
     * runtime classpath. It is what a level file, a snapshot and the agent's field access all
     * read a component's identity out of, so it is required rather than defaulted.
     */
    public fun definition(): UdeaGameDef = UdeaGameDef(
        registry = NewGameUdeaRegistry,
        modules = listOf(NewGameModule()),
    )

    /** A host for [mode], with the world built and nothing running yet. */
    public fun host(mode: RenderMode): GameHost = GameHost(mode, definition())
}
