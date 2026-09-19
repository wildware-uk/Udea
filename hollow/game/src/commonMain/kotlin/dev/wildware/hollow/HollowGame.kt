package dev.wildware.hollow

import dev.wildware.hollow.net.HollowNet
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.PresentationFactory
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.level.LevelScene
import dev.wildware.udea.core.loop.barrier
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.snapshot.snapshotTimeTravel
import dev.wildware.udea.generated.HollowUdeaRegistry
import dev.wildware.udea.render.RenderModule

/**
 * The one Hollow simulation, and the one place it is assembled: every launcher - the window, the
 * shot, a headless test - builds its host here and differs only in the [RenderMode] and the
 * presentation it passes. `MobaGame` is the same object for `moba`, and says at length why that
 * matters.
 *
 * In H1 (issue #249) the game is a lit clearing and nothing moves in it. The player, the creatures
 * and their systems join in later tickets of epic #245, as modules in [definition]'s list.
 */
public object HollowGame {

    /** How the game names itself. */
    public const val NAME: String = "hollow"

    /**
     * A fresh definition playing [level] (see [HollowLevel]). Fresh per call, because building one
     * constructs a world: two hosts over one definition would tick each other's.
     */
    public fun definition(level: ByteArray = HollowLevel.bundledBytes()): UdeaGameDef {
        val definition = UdeaGameDef(
            // Generated from this module's runtime classpath (issue #202), so a level can hold
            // every @Serializable component of every module the game is made of.
            registry = HollowUdeaRegistry,
            // `RenderModule` in every mode, headless included, for the reason `MobaGame` gives: its
            // one simulation system is part of the simulation, and the modes must run the same one.
            modules = listOf(HollowModule(LaunchLevel(level)), RenderModule()),
            // The snapshot ring: a server's replication baselines, and what `time.*` rewinds. Over
            // `HollowNet.registry()`, so what is captured is exactly what replicates.
            timeTravel = snapshotTimeTravel(HollowNet.registry()),
        )
        // Registered, not loaded: [seed] asks for the swap.
        definition.core.scenes.register(LevelScene(HollowLevel.SCENE_ID, level))
        return definition
    }

    /** A host for [mode] over a fresh [definition]. [presentation] is ignored in `Headless`. */
    public fun host(
        mode: RenderMode,
        presentation: PresentationFactory? = null,
        level: ByteArray = HollowLevel.bundledBytes(),
    ): GameHost = GameHost(mode, definition(level), presentation)

    /**
     * Loads the launch level and runs the tick that applies it, so the world is populated when this
     * returns. Every entry point calls it. `MobaEntry.seed`'s two barrier actions, in its order: the
     * swap to [HollowLevel.SCENE_ID], which puts the level's entities in and makes it the active
     * scene, then the whole level over that - the clock and the random streams as well.
     *
     * @throws dev.wildware.udea.core.level.LevelFormatException from `read`, before anything is
     *   queued, when the launch bytes are not a level this game can load.
     */
    public fun seed(host: GameHost) {
        val levels = host.game.levels
        val level = levels.read(host.ctx[LaunchLevel.KEY].bytes)
        host.ctx.scenes.requestScene(HollowLevel.SCENE_ID)
        levels.load(level, host.ctx.barrier)
        host.run(1)
    }
}
