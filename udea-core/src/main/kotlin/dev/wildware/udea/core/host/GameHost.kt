package dev.wildware.udea.core.host

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.loop.GameLoop
import dev.wildware.udea.core.loop.Presentation
import dev.wildware.udea.core.loop.TimeControl
import dev.wildware.udea.core.module.UdeaGame
import dev.wildware.udea.core.module.UdeaGameDef

/**
 * The single entry point that stands a game up, in any [RenderMode].
 *
 * ## What it replaces: the `GameScreen` responsibility map
 *
 * `GameScreen` (`common/UdeaGameManager.kt:85`) owned the viewport, the camera, the Box2D
 * world, the `RayHandler`, the `SpriteBatch`, the `ShapeRenderer`, the `Stage`, the console,
 * the Fleks world, the network systems *and* level loading, in one class that could not be
 * constructed without a GL context — so nothing it owned could run headless. Each
 * responsibility now has one owner:
 *
 * | `GameScreen` owned | New owner |
 * |---|---|
 * | Fleks world, system list | `UdeaGameDef.build()` → [UdeaGame.world], ordered by `SimRegistry` |
 * | the tick, `world.update(delta)` | `GameLoop` + `WorldSimulation`, fixed 60Hz |
 * | Box2D world | `PhysicsWorld` on `GameContext`, stepped by `PhysicsStepSystem` |
 * | level loading, the `started` flag | `SceneManager`, applied through `SimBarrier` |
 * | `isServer`, `gameConfig`, `time` | `GameContext.role` / `.config` / `.clock` |
 * | viewport, camera, sprite batch, shape renderer | `udea-render`'s pipeline, behind [Presentation] |
 * | ray handler (Box2D lights) | `udea-render` — presentation, not physics |
 * | stage, console, input multiplexer | `udea-render` and the agent host; not the kernel's |
 * | network systems | `udea-net`, registered as a module like anything else |
 *
 * What is left here is small on purpose: a loop, a game, a mode, and an optional way to draw.
 *
 * ## One path, three modes
 *
 * Dedicated server, CI, the agent's harness, fast-forward and the player all construct this
 * class with the same [UdeaGameDef]. There is no headless fork of the simulation, so a
 * behaviour that only reproduces in one mode is a bug in a renderer rather than a second
 * engine to debug.
 *
 * In [RenderMode.Headless] the [presentationFactory] is **never invoked**, even when one is
 * supplied — so no `RenderSystem` is constructed, [presentation] is `null`, and a capture
 * request returns [RenderUnavailable.NoRenderContext] rather than throwing or handing back a
 * blank image. `udea-core` implements only this mode; the LWJGL3 backends for
 * [RenderMode.Offscreen] and [RenderMode.Windowed] live in `udea-render` and reach this class
 * through [presentationFactory].
 */
public class GameHost(
    /** Reported by `/health`, so an agent knows which toolsets are live before calling one. */
    public val mode: RenderMode,
    definition: UdeaGameDef,
    /**
     * Builds the presentation for a mode that has a render context.
     *
     * Passed regardless of mode — the caller does not branch — and consulted only when
     * [RenderMode.hasRenderContext] is true.
     */
    private val presentationFactory: PresentationFactory? = null,
) {

    /** The built simulation: context, world, stepper and resolved system order. */
    public val game: UdeaGame = definition.build()

    private val backend: PresentationBackend? =
        if (mode.hasRenderContext) presentationFactory?.create(game) else null

    /** The presentation, or `null` in [RenderMode.Headless]. */
    public val presentation: Presentation? get() = backend?.presentation

    /** The fixed-step loop. Public because [time] drives it for the agent. */
    public val loop: GameLoop = GameLoop.forWorld(game.simulation, presentation)

    /**
     * Pause, step, rewind and the snapshot ring, as the agent surface sees them.
     *
     * Built here because this is the first place both halves exist: the loop is this class's,
     * and the ring came out of `UdeaGameDef.build()` attached to the simulation that records
     * into it. Assembling it anywhere else would let a host hand an agent a [TimeControl] over
     * a *different* ring than the one the loop fills — `listSnapshots` would answer empty
     * while the simulation captured happily into a ring nobody could reach.
     *
     * A host built from a definition with no `timeTravel` factory still gets a working
     * [TimeControl]: it pauses and steps, and every time-travel call answers
     * [dev.wildware.udea.core.loop.RewindFailure.NoSnapshotRing].
     */
    public val time: TimeControl = TimeControl(loop, game.simulation.travel)

    /** Shorthand for the built game's context. */
    public val ctx: GameContext get() = game.ctx

    public val world: World get() = game.world

    /** The tick about to be simulated. */
    public val tick: Tick get() = game.ctx.clock.tick

    /** Ticks run since construction. */
    public val totalTicks: Long get() = loop.totalTicks

    /**
     * False once [stop] is called. [run] returns when it goes false.
     *
     * `@Volatile` because [run]'s loop reads it every tick and [stop] may be called from
     * another thread — an agent host, an MCP request handler, a supervisor. Without it the JIT
     * is entitled to hoist the read out of the loop, and a cross-thread `stop()` would never be
     * observed: `run()` would spin forever with no error and no way to interrupt it.
     * `HeadlessHostTest` pins both the modifier and a real cross-thread stop.
     */
    @Volatile
    public var running: Boolean = true
        private set

    /**
     * Advances exactly [ticks] ticks with no wall clock and no rendering.
     *
     * The primitive behind fast-forward, CI runs and the agent's `step(n)`: no real time is
     * consulted, so a 10 000-tick run costs what the simulation costs and nothing else, and it
     * produces the same world on a fast machine and a slow one.
     *
     * **Leaves the loop's pause state exactly as it found it.** `GameLoop.stepTicks` pauses —
     * it is the primitive behind `TimeControl.step`, where a stopped clock afterwards is the
     * point and `resume()` is right there. Here it is not: a host that fast-forwards a loading
     * sequence with `run(300)` and then hands the frame cadence back to its render backend
     * would draw at full rate over a simulation frozen forever, with nothing thrown and no
     * counter moving. So the pause is saved and restored rather than left set.
     */
    public fun run(ticks: Int) {
        require(ticks >= 0) { "tick count must not be negative, was $ticks" }
        val wasPaused = loop.paused
        try {
            loop.stepTicks(ticks)
        } finally {
            loop.paused = wasPaused
        }
    }

    /**
     * Runs ticks as fast as the CPU allows until [stop] is called.
     *
     * Headless only, and checked rather than assumed: a host with a render context is driven
     * by its windowing backend, which owns the frame cadence and calls [frame]. Spinning a
     * simulation here as well would give a windowed game two drivers.
     *
     * Something must be able to call [stop] — a system, a barrier action, or an agent on
     * another thread — or this does not return.
     */
    public fun run() {
        check(mode == RenderMode.Headless) {
            "run() paces nothing and is for ${RenderMode.Headless}; a $mode host is driven by " +
                "its render backend calling frame(wallDelta)"
        }
        while (running) game.simulation.step()
    }

    /**
     * Makes [run] return after the tick in flight.
     *
     * Safe from a system, from a [dev.wildware.udea.core.loop.BarrierAction], and from another
     * thread — [running] is volatile, so the loop sees the write on its next read.
     */
    public fun stop() {
        running = false
    }

    /**
     * Advances by whole ticks worth of [wallDelta] and presents once.
     *
     * Called by the render backend's frame callback. In [RenderMode.Headless] there is nothing
     * to present, and the loop's null [Presentation] makes that a no-op rather than a branch.
     */
    public fun frame(wallDelta: Float) {
        loop.frame(wallDelta)
    }

    /**
     * Captures the most recent frame.
     *
     * @return [CaptureOutcome.Unavailable] with [RenderUnavailable.NoRenderContext] in
     *   [RenderMode.Headless]. It never throws and never returns a blank image: an agent
     *   diffing screenshots would read a blank one as a black screen and act on it.
     */
    public fun screenshot(): CaptureOutcome {
        if (!mode.hasRenderContext) {
            return CaptureOutcome.Unavailable(RenderUnavailable.NoRenderContext)
        }
        val capture = backend?.capture
            ?: return CaptureOutcome.Unavailable(RenderUnavailable.NoCaptureBackend)
        return capture.capture()
    }

    override fun toString(): String = "GameHost(mode=$mode, tick=$tick, running=$running)"
}

/**
 * Builds the drawing half of a host, for a mode that has a render context.
 *
 * A factory rather than a constructed instance because it must be given the [UdeaGame] — a
 * renderer reads the same world the simulation writes — and the game does not exist until the
 * host has built it.
 */
public fun interface PresentationFactory {
    public fun create(game: UdeaGame): PresentationBackend
}

/**
 * What a render backend gives a host: a way to draw, and optionally a way to read frames back.
 *
 * Two fields rather than one interface with two methods, because a backend may legitimately
 * draw without supporting capture, and a type that forced both would push a
 * throw-or-return-blank decision into every implementation — which is the decision
 * [RenderUnavailable] exists to make once, here.
 */
public class PresentationBackend(
    public val presentation: Presentation,
    public val capture: FrameCapture? = null,
) {
    override fun toString(): String = "PresentationBackend(capture=${capture != null})"
}
