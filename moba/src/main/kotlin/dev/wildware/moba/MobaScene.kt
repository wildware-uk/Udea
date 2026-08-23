package dev.wildware.moba

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderPipeline
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.camera.CameraRig
import dev.wildware.udea.render.control.PresentationControl
import dev.wildware.udea.render.draw.DebugDraw
import dev.wildware.udea.render.interp.Interpolator
import dev.wildware.udea.render.interp.PoseHistory

/**
 * What `moba` draws, and the control surface an agent steers it through.
 *
 * ## Why this exists, when the previous answer was "nothing"
 *
 * `MobaEntry.renderRegistry` used to return an empty [RenderRegistry], with a defensible argument
 * attached: `moba` has no art, and a renderer that drew a coloured rectangle would make
 * screenshots *look* more finished than the game is.
 *
 * That argument is right about art and wrong about observability. Spec 6's Phase 1 demo is
 * "screenshot, rewind 100, screenshot again, **diff the images**". Against an empty registry both
 * captures are the same cleared framebuffer, the diff is zero pixels for every possible state of
 * the simulation, and the demo passes while proving nothing - which is strictly worse than a
 * rectangle, because it is a green result with no signal in it.
 *
 * So this draws exactly one thing: a champion per entity, at that entity's [Position], out of the
 * character pack `docs/art-assets.md` describes. It is the game state made visible, which is the
 * property the render toolset exists to serve and the only property the demo's diff measures -
 * and it is a real texture through a real region slice, so a green `render.screenshot` is
 * evidence about the sprite path and not only about the clear colour. See
 * [ChampionRenderSystem] for the tick-timed playhead, and for what happens on a clone with no
 * art extracted.
 *
 * ## What it is honestly not
 *
 * - **Following an entity does not work.** [CameraRig] resolves a followed net id through
 *   [Interpolator], which reads `PhysicsBody`; a `moba` unit has [Position] and nothing else, so
 *   `render.follow_entity` is accepted, answers `ok`, and the camera does not move. `set_camera`
 *   *does* work and is observable in a capture. Closing this means giving `moba` physics bodies,
 *   which is Phase 3 work, not a line here.
 * - **[PoseHistory] is a constant.** Nothing in `moba` interpolates, because nothing here is
 *   drawn between ticks: every capture an agent takes is taken while paused.
 * - **No debug renderer is registered**, so `render.toggle_debug_draw` flips a switch that
 *   nothing reads. The switch is wired to the real [DebugDraw] the pipeline shares, so the tool
 *   reports the true state of it; there is simply nothing in this game drawing debug shapes yet.
 */
public class MobaScene private constructor(
    /** Handed to the GL backend. Registration is complete by the time this is visible. */
    public val registry: RenderRegistry,
    private val camera: CameraRig,
    private val debug: DebugDraw,
) {

    /**
     * The control surface over a booted [pipeline].
     *
     * Takes the pipeline rather than reaching for it, because only a caller that booted a GL
     * backend has one, and a `Headless` `moba` process legitimately has none to pass.
     */
    public fun presentation(pipeline: RenderPipeline): PresentationControl =
        PresentationControl(pipeline, camera, debug)

    public companion object {

        /** World units kept visible across the shorter axis. Wide enough to hold a drifted field. */
        public const val WORLD_WIDTH: Float = 140f

        /** World units kept visible across the taller axis. */
        public const val WORLD_HEIGHT: Float = 80f

        /** Where the camera looks by default. Chosen so a seeded field sits inside the frame. */
        public const val CAMERA_X: Float = 45f

        /** @see CAMERA_X */
        public const val CAMERA_Y: Float = 12f

        /**
         * Builds the scene for [definition].
         *
         * Takes the definition and not a [GameContext] because [CameraRig] needs the net id
         * index, and that is reachable off the definition's core module before any host exists -
         * which matters, since the backend must be constructed before the host and the registry
         * must be complete before the backend.
         */
        public fun build(definition: UdeaGameDef): MobaScene {
            val registry = RenderRegistry()
            val debug = DebugDraw(enabled = false)
            val camera = CameraRig(
                netIds = definition.core.netIds,
                interpolator = Interpolator(SimClock(), NoPoseHistory),
                frameTime = registry.frameTime,
                worldWidth = WORLD_WIDTH,
                worldHeight = WORLD_HEIGHT,
            )
            camera.requestLookAt(CAMERA_X, CAMERA_Y, 1f)
            // Positional, not a trailing lambda: `register`'s trailing lambda is the ordering
            // constraint block, and a factory written there registers nothing at all.
            registry.register(RenderPhase.PreRender, { camera })
            registry.register(
                RenderPhase.World,
                { resources -> ChampionRenderSystem(resources, camera) },
            )
            return MobaScene(registry, camera, debug)
        }
    }
}

/**
 * Reports every frame as a restore frame, so [Interpolator] always draws the simulated pose.
 *
 * `moba` captures while paused and has no `Interp` components, so there is never anything to
 * interpolate between. A real history would be recorded by `InterpSnapshotSystem`, which
 * `RenderModule` already contributes; wiring the two together is worth doing when something in
 * this game actually moves between ticks on screen.
 */
private object NoPoseHistory : PoseHistory {
    override val lastTick: Tick get() = Tick(-1L)
}

/**
 * One animated champion per [Position], in world space, through the shared batch.
 *
 * ## What it draws, and why it is a sprite rather than the quad it replaced
 *
 * A white quad made the demo's image diff *possible* - two captures of different simulation
 * states produced different pixels - and that was the whole of what it bought. It did not
 * exercise a texture upload, a region slice, a frame index or a non-opaque draw, so
 * "`render.screenshot` returns real bytes" and "`render.screenshot` returns real bytes **of a
 * real renderer**" were still two different claims. This draws a frame of [SHEET] - a 100x100
 * strip out of the character pack `docs/art-assets.md` describes - which closes that gap with
 * the smallest thing that actually goes through the sprite path.
 *
 * ## The playhead is the simulation tick, and that is deliberate
 *
 * Every other animation in this engine is wall-timed presentation state, on purpose (see
 * `SpriteAnimation`): a playhead advanced by the tick would freeze on a paused game and rewind
 * with a snapshot. Here **both of those are the point.** An agent captures while paused, and
 * `render.compare_artifacts` measures the difference between two captures. A wall-timed playhead
 * would make two screenshots of an identical, paused, unmutated world differ by however long the
 * agent spent thinking - which is precisely the signal the tool exists to report, drowned in
 * noise the agent cannot attribute. Tick-timed, the picture is a pure function of the simulation
 * state: mutate and the diff is the mutation; rewind and the capture is byte-identical to the
 * one before it.
 *
 * `ctx.clock` is read, never written, and nothing here reaches [dev.wildware.udea.core.SimClock]
 * outside [render]. A game whose animations should keep running while an agent stares at them
 * should use `SpriteAnimation` and `AnimationRenderSystem` instead; this is the trade a game
 * built to be *inspected* makes.
 *
 * ## When the art is not there
 *
 * `moba/src/main/resources/assets/sprites/` is gitignored - the pack is third-party licensed and
 * this repository is public - so a fresh clone has no pixels until somebody runs
 * `python scripts/extract-art.py`. [loadFrames] says so on stderr and falls back to a single
 * white texel drawn at the same world size, which is the quad this replaced. **The fallback is
 * not a stub for the sprite path**: it is one frame instead of six, through the identical draw
 * call, so a capture on a machine with no art is still a capture of a real renderer - it just
 * does not animate.
 */
internal class ChampionRenderSystem(
    private val resources: RenderResources,
    private val camera: CameraRig,
) : RenderSystem {

    private var world: World? = null

    private var units: Family? = null

    /** Read in [render] for the frame index. Never written. See the class KDoc. */
    private var clock: SimClock? = null

    /**
     * The strip, sliced once. Its [Texture] is owned by the pipeline and disposed with it.
     *
     * Built in the constructor rather than in [onBind] because the factory that calls this runs
     * on the render thread inside `RenderRegistry.build`, which is where a GL context exists;
     * `onBind` runs there too, but a texture is not a world lookup and does not belong with them.
     */
    private val frames: Array<TextureRegion> = loadFrames(resources)

    /** Frames actually drawn by the most recent [render]. A health signal, not state. */
    internal var drawnCount: Int = 0
        private set

    override fun onBind(world: World, ctx: GameContext) {
        this.world = world
        this.clock = ctx.clock
        units = world.family { all(Position) }
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        val world = this.world ?: return
        val units = this.units ?: return
        drawnCount = 0
        val frame = frames[frameIndex(clock?.tick ?: Tick.ZERO, frames.size)]
        val batch = resources.batch
        batch.projectionMatrix = camera.camera.combined
        batch.color = Color.WHITE
        batch.begin()
        try {
            with(world) {
                units.forEach { entity ->
                    val position = entity[Position]
                    batch.draw(
                        frame,
                        position.x - HALF_WIDTH,
                        position.y - HALF_HEIGHT,
                        WIDTH,
                        HEIGHT,
                    )
                    drawnCount++
                }
            }
        } finally {
            // In a `finally` because a `Batch` left begun poisons every later pass in the frame
            // with a "batch already begun" failure that names the wrong system.
            batch.end()
            batch.color = Color.WHITE
        }
    }

    internal companion object {

        /**
         * The idle strip. `archer` because it is the first name in `docs/art-assets.md`'s roster,
         * which is the least arbitrary reason available; nothing depends on the choice.
         */
        const val SHEET: String = "assets/sprites/champions/archer/idle.png"

        /** Every sheet in the pack is a horizontal strip of 100x100 frames (docs/art-assets.md). */
        const val FRAME_SIZE: Int = 100

        /**
         * Simulation ticks per animation frame.
         *
         * At the default 60Hz tick that is a 10fps playhead, which is about right for a
         * hand-drawn six-frame idle and - more usefully here - means a `time.step` of one tick
         * usually does *not* change the picture, so a diff between two captures reports the
         * mutation rather than the animation.
         */
        const val TICKS_PER_FRAME: Long = 6L

        /**
         * How tall a champion's **frame** is drawn, in world units.
         *
         * Not how tall the champion looks, and the difference is large enough to be worth the
         * arithmetic. The pack's characters are small islands in a big frame - the `archer` idle
         * frame is 100x100 with 22x17 of it opaque - so the drawn character is about a sixth of
         * this. At 34 world units against `MobaScene.WORLD_HEIGHT` of 80, on a 1280x720
         * framebuffer, that is roughly a 52x40 pixel character: readable in a screenshot, and
         * three of them across the [DriftSystem] field rather than one filling it.
         *
         * Sized by measurement rather than by eye, because 14 - the first value here - drew a
         * 22-pixel character that a reviewer could reasonably have called a blank frame.
         */
        const val HEIGHT: Float = 34f

        /** Square, because the source frames are. */
        const val WIDTH: Float = HEIGHT

        const val HALF_WIDTH: Float = WIDTH / 2f
        const val HALF_HEIGHT: Float = HEIGHT / 2f

        /**
         * Which frame [tick] is showing, for [count] frames.
         *
         * `floorMod` and not `%`: a rewind can put the clock on a negative tick, and `%` would
         * hand back a negative index and take the render thread down with an
         * `ArrayIndexOutOfBoundsException` on a path an agent can reach from `time.rewind`.
         * Extracted and internal so `MobaSceneTest` can drive it with no GL context.
         */
        fun frameIndex(tick: Tick, count: Int): Int {
            require(count > 0) { "an animation with no frames cannot be drawn" }
            return Math.floorMod(tick.value / TICKS_PER_FRAME, count.toLong()).toInt()
        }

        /**
         * [SHEET] sliced into frames, or one white texel when it is not on the classpath.
         *
         * @throws IllegalStateException if the sheet is present but narrower than one frame. A
         *   silent zero-frame array would fail later, on the render thread, with an index error
         *   that names nothing.
         */
        private fun loadFrames(resources: RenderResources): Array<TextureRegion> {
            val handle = Gdx.files?.classpath(SHEET)
            if (handle == null || !handle.exists()) {
                System.err.println(
                    "[moba] $SHEET is not on the classpath, so units are drawn as plain white " +
                        "squares. The character pack is gitignored (docs/art-assets.md); run " +
                        "`python scripts/extract-art.py` to put it back.",
                )
                return arrayOf(TextureRegion(resources.own(whitePixel())))
            }
            val texture = resources.own(Texture(handle))
            // Nearest, because the source is pixel art and a linear filter turns a 100px frame
            // scaled to 14 world units into mush.
            texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
            val count = texture.width / FRAME_SIZE
            check(count > 0) {
                "$SHEET is ${texture.width}x${texture.height}, which holds no whole " +
                    "${FRAME_SIZE}x$FRAME_SIZE frame. docs/art-assets.md says every sheet in the " +
                    "pack is a horizontal strip of ${FRAME_SIZE}x$FRAME_SIZE frames."
            }
            return Array(count) { index ->
                TextureRegion(texture, index * FRAME_SIZE, 0, FRAME_SIZE, texture.height)
            }
        }

        /** The no-art fallback. One texel, tinted white, stretched over the same world quad. */
        private fun whitePixel(): Texture = Texture(
            Pixmap(1, 1, Pixmap.Format.RGBA8888).apply {
                setColor(Color.WHITE)
                fill()
            },
        )
    }
}
