package dev.wildware.udea.render

import com.github.quillraven.fleks.World
import dev.wildware.udea.core.GameContext

/**
 * A presentation system: it draws, and it is deliberately **not** a Fleks system.
 *
 * ## Why this is an interface here and not an `IteratingSystem` there
 *
 * In the old tree every drawing system was a Fleks `IteratingSystem` registered into
 * `GameScreen.world` (`common/UdeaGameManager.kt:85`), so `world.update(delta)` issued GL
 * calls (`UdeaGameManager.kt:222`). Three things followed, and all three were structural
 * rather than accidental: a headless simulation was impossible, the simulation advanced by
 * whatever the GPU handed it, and "does this system draw?" was a question you answered by
 * reading the body.
 *
 * A `RenderSystem` is not in the world's system list at all. It is driven by
 * [RenderPipeline], which the loop calls *after* the tick. `world.update(dt)` is therefore
 * pure simulation **by construction** (spec 3.3): there is no drawing system in there to
 * forget about.
 *
 * ## The two parameters, and what is missing from them
 *
 * [render] gets an [OffscreenTarget] -- the capturable one -- and an interpolation `alpha`.
 * It does *not* get a `Tick`, a `SimClock` or a delta in seconds: a renderer that wanted to
 * advance state over time would be simulation, and would then be simulation running outside
 * the tick.
 *
 * ## Families are resolved in [onBind]
 *
 * `World.family { }` builds or looks up a family; doing it inside [render] puts a lookup on
 * a 60-plus-times-a-second path for a value that never changes. Resolve every `Family`
 * handle once in [onBind] and hold it in a field. A test in this module scans the compiled
 * bytecode of every `RenderSystem` and fails if `render` calls `World.family`.
 */
public interface RenderSystem {

    /**
     * Called once, when the pipeline is built, before the first [render].
     *
     * This is where `Family` handles, texture regions and shader uniforms are resolved. The
     * world and the context are handed in rather than injected, because a `RenderSystem` is
     * not a Fleks system and so has no injection point -- which is the whole point.
     */
    public fun onBind(world: World, ctx: GameContext) {}

    /**
     * Draws one frame into [target].
     *
     * @param alpha how far this frame sits between the last simulated tick and the next,
     *   always in `[0, 1)`. Interpolate transforms by it; ignoring it draws at the last tick
     *   and judders. It is presentation state and never enters a snapshot.
     */
    public fun render(target: OffscreenTarget, alpha: Float)
}

/**
 * A system that draws on the human's screen and must never appear in a capture (spec 3.7).
 *
 * The agent activity overlay is the reason this type exists: a corner panel showing what the
 * agent said it is doing and which tools it has called. An agent doing visual verification --
 * capture, act, capture, diff -- must not see its own narration in the frame, or it concludes
 * the game changed when only the caption did.
 *
 * Two things in the signature carry that guarantee:
 *
 * - the target is a [ScreenTarget], which no capture reads, and which nothing outside this
 *   module can construct or convert to an [OffscreenTarget];
 * - the time parameter is `dtSeconds` -- wall seconds since the previous frame -- and **not**
 *   `alpha` or a `Tick`. An overlay is not allowed to read simulation time, and the type says
 *   so. Overlay state is wall-timed presentation state: never snapshotted, never touching
 *   `SimClock` or `RngService`, so it cannot perturb a replay.
 *
 * There is deliberately no `onBind(world, ctx)` here either. An overlay narrates the agent,
 * not the world; handing it the world would hand it simulation state through the back door.
 */
public interface OverlaySystem {

    /**
     * Draws the overlay into [target].
     *
     * @param dtSeconds wall-clock seconds since the previous frame, clamped by the pipeline
     *   to [RenderPipeline.MAX_FRAME_SECONDS] so a breakpoint or a GC pause cannot make an
     *   animation jump. Zero on the very first frame, when there is no previous one.
     */
    public fun render(target: ScreenTarget, dtSeconds: Float)
}
