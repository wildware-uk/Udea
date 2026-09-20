package dev.wildware.hollow

import dev.wildware.hollow.net.HollowNet
import dev.wildware.udea.core.NetRole
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.PresentationFactory
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.level.LevelScene
import dev.wildware.udea.core.loop.barrier
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.snapshot.snapshotTimeTravel
import dev.wildware.udea.generated.HollowUdeaRegistry
import dev.wildware.udea.physics2d.Physics2DModule
import dev.wildware.udea.physics2d.Physics2DSettings
import dev.wildware.udea.render.RenderModule
import dev.wildware.udea.render.input.InputModule

/**
 * The one Hollow simulation, and the one place it is assembled: every launcher - the window, the
 * shot, a headless test - builds its host here and differs only in the [RenderMode] and the
 * presentation it passes. `MobaGame` is the same object for `moba`, and says at length why that
 * matters.
 *
 * In H1 (issue #249) the game was a lit clearing and nothing moved in it. Issue #250 puts a person
 * in it: the modules below now include the physics that stops a character at a rock and the input
 * model that steers it. The creatures join in later tickets of epic #245.
 *
 * ## What a client does not run
 *
 * A [NetRole.Client] gets no solver and none of Hollow's own systems. Its world is a **replicated
 * view**: every character in it arrived over the wire, and the server decides where each one is and
 * what it is playing. A client that also ran the authoritative systems would move a second copy of
 * the character every tick and have replication write over it, and its dynamic body would then hold
 * `Transform3D` at wherever the body had got to rather than where the server says.
 *
 * It still **ticks**, which is the difference from `moba`'s client and the reason its animation
 * plays: `SimClock` advances, `RenderModule`'s interpolation records a pose per tick, and
 * `IntentSampleSystem` reads the keyboard so the window has a command to send. What the client's
 * clock is *not* is the server's - `SimClock.moveTo` is `internal` to `udea-core` - so a clip plays
 * at the right speed from an arbitrary phase. That is visible to nobody and is written down here
 * rather than left to be rediscovered.
 *
 * ## Why a build has to be closed
 *
 * [Physics2DModule] opens a Box2D world, which is native memory, and nothing in `udea-core` frees
 * it: a `GameHost` has no module list and `GameHost.stop` only stops the loop. So the way to build a
 * Hollow game is [build], which hands back a [HollowHost] that owns both - and every entry point
 * closes it. That is the whole reason this object no longer has a plain `host(...)`.
 */
public object HollowGame {

    /** How the game names itself. */
    internal const val NAME: String = "hollow"

    /**
     * The solver Hollow runs.
     *
     * No gravity: the world is a ground plane seen from above, and nothing falls across it. `z` is
     * up and is the game's alone (issue #247), so a character's height above the ground is never
     * something the solver has an opinion about.
     */
    public val PHYSICS: Physics2DSettings = Physics2DSettings(gravityX = 0f, gravityY = 0f)

    /**
     * A fresh definition playing [level] (see [HollowLevel]) over [physics], as [role].
     *
     * Fresh per call, because building one constructs a world: two hosts over one definition would
     * tick each other's. [physics] is a parameter and not made here so that whoever built it can
     * free it; [build] is the caller that pairs the two correctly, and a launcher that has to build
     * its scene over `definition.core.netIds` before the backend exists calls this and pairs them
     * itself.
     *
     * Input starts at `IntentSource.NONE` and is swapped in later through `IntentState.source`,
     * which is what that field is for: a window wires its keyboard once it has one, and a server
     * wires each connection's command as it joins.
     */
    public fun definition(
        physics: Physics2DModule?,
        level: ByteArray = HollowLevel.bundledBytes(),
        role: NetRole = NetRole.Standalone,
    ): UdeaGameDef {
        val definition = UdeaGameDef(
            role = role,
            // Generated from this module's runtime classpath (issue #202), so a level can hold
            // every @Serializable component of every module the game is made of.
            registry = HollowUdeaRegistry,
            // `RenderModule` in every mode, headless included, for the reason `MobaGame` gives: its
            // one simulation system is part of the simulation, and the modes must run the same one.
            // `InputModule` before `HollowModule`, so `IntentState` is on the context by the time
            // `PlayerControlSystem`'s factory asks for it - a module's `context` hook runs for
            // every module before any module's `simulation` hook does.
            modules = buildList {
                // No solver on a client: nothing in its world is a body, so opening a Box2D world
                // would be native memory nothing puts anything in.
                if (physics != null) add(physics)
                add(InputModule(HollowControls.BINDINGS))
                add(HollowModule(LaunchLevel(level), authoritative = role.isAuthoritative))
                add(RenderModule())
            },
            // The snapshot ring: a server's replication baselines, and what `time.*` rewinds. Over
            // `HollowNet.registry()`, so what is captured is exactly what replicates.
            timeTravel = snapshotTimeTravel(HollowNet.registry()),
        )
        // Registered, not loaded: [seed] asks for the swap.
        definition.core.scenes.register(LevelScene(HollowLevel.SCENE_ID, level))
        return definition
    }

    /**
     * A Hollow game in [mode] as [role], not yet seeded. [presentation] is ignored in `Headless`.
     *
     * The caller closes what this returns, which frees the Box2D world when there is one. A test, a
     * shot and a session all take the same route.
     */
    public fun build(
        mode: RenderMode,
        presentation: PresentationFactory? = null,
        level: ByteArray = HollowLevel.bundledBytes(),
        role: NetRole = NetRole.Standalone,
    ): HollowHost {
        val physics = if (role.isAuthoritative) Physics2DModule(PHYSICS) else null
        val host = try {
            GameHost(mode, definition(physics, level, role), presentation)
        } catch (failure: RuntimeException) {
            physics?.close()
            throw failure
        }
        return HollowHost(host, physics)
    }

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

/**
 * A built Hollow game and the native solver behind it, closed together.
 *
 * `udea-core` has no lifecycle for a module: `UdeaGameDef` holds the list, `UdeaGame` does not, and
 * `GameHost.stop` stops the loop. A Box2D world is native memory, so something has to hold the two
 * ends, and this is the smallest thing that can. [close] is idempotent, because
 * `Physics2DModule.close` is.
 */
public class HollowHost(
    /** The game. Seeded by [HollowGame.seed] before it is stepped. */
    public val host: GameHost,
    /** The solver this game opened, or null for a client, which has none. */
    private val physics: Physics2DModule?,
) : AutoCloseable {

    override fun close() {
        host.stop()
        physics?.close()
    }

    override fun toString(): String = "HollowHost($host)"
}
