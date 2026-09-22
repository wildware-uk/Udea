package com.example.newgame

import com.example.newgame.sim.RoverSystem
import dev.wildware.udea.assets.AssetRegistry
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.generated.NewGameUdeaRegistry
import dev.wildware.udea.render.RenderModule

/**
 * This game's content, as a module: the systems it runs and the order they run in.
 *
 * A module is the unit the engine composes. Every entry point - a dedicated server, a player's
 * client, an agent's instance, a replay - builds the same [UdeaGameDef] out of the same modules,
 * which is what makes "it happens on the server and not in my client" a bug in a renderer rather
 * than a difference between two simulations.
 *
 * @param assets the packed asset graph the systems resolve their models in.
 */
public class NewGameModule(private val assets: AssetRegistry) : UdeaModule {

    override val name: String get() = "new-game"

    override fun simulation(registry: SimRegistry) {
        registry.add(SimPhase.Movement, { RoverSystem(assets) })
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
     *
     * `RenderModule` is in every mode, headless included. Its one simulation system records where
     * each `Transform3D` stood on each tick, which is what a window draws between two ticks from;
     * it draws nothing itself, and a server that ran a different set of systems from its clients
     * would be running a different simulation.
     */
    public fun definition(assets: AssetRegistry = NewGameAssets.registry): UdeaGameDef = UdeaGameDef(
        registry = NewGameUdeaRegistry,
        modules = listOf(NewGameModule(assets), RenderModule()),
    )

    /** A host for [mode] with no window, and nothing running yet: a server, a test, an agent. */
    public fun host(mode: RenderMode): GameHost = GameHost(mode, definition())
}
