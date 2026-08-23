package dev.wildware.moba

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.udea.assets.AssetIndex
import dev.wildware.udea.assets.SpriteSheet
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
import dev.wildware.udea.generated.GameAssets

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
 * ## Every number it draws with came out of the bundle
 *
 * The frames are `AtlasIndex` regions cut at pack time out of one atlas page, and the world size
 * is that region's pixel size multiplied by the authored `SpriteSheet.scale`. Nothing here
 * divides a texture, and there is no `WORLD_SCALE` constant left to override an artist.
 *
 * What that deletes, precisely: `Gdx.files.classpath(SHEET)`, `texture.width / FRAME_SIZE`,
 * `TextureRegion(texture, index * FRAME_SIZE, ...)` and a `HEIGHT = 34f` the renderer chose for
 * itself - the runtime slicing path issue #123 asks for the removal of, and the four lines that
 * made `.udeapak` a proven, unused artifact.
 *
 * ## The scale is read every frame, and that is the hot-reload proof
 *
 * [scale] is not cached at bind time. It is `registry.at(sheetIndex)` per frame - one array
 * index into the live [dev.wildware.udea.assets.AssetRegistry], the same object `AssetHotReload`
 * swaps a new `SpriteSheet` into at the top of a `Simulation.step`. So an agent that patches
 * `championScale` in `moba/assets/champion/champion.udea.kts` changes the size of every champion
 * in the very next capture, through the simulation's own asset graph - and cannot fake it,
 * because no `world.*` tool can write a sprite size.
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
 * state and the asset graph: mutate and the diff is the mutation; rewind and the capture is
 * byte-identical to the one before it.
 *
 * `ctx.clock` is read, never written, and nothing here reaches [dev.wildware.udea.core.SimClock]
 * outside [render].
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
     * The live graph, and the slot the sheet occupies in it.
     *
     * The slot is resolved once because it is pack-time stable - that is the whole property an
     * `AssetIndex` exists for, and it survives a value-only reload by construction
     * (`AssetRegistry.applyDelta` swaps at the same slot). The *value* behind it is read fresh
     * every frame.
     */
    private val registry = MobaAssets.registry

    private val sheetIndex: AssetIndex = registry.indexOf(GameAssets.champion.idleSheet.id)

    /**
     * The frames, cut at pack time, pointing into the atlas pages.
     *
     * Built in the constructor rather than in [onBind] because the factory that calls this runs
     * on the render thread inside `RenderRegistry.build`, which is where a GL context exists.
     *
     * Bracketed as the asset phase of `StartupTrace`: this is `moba`'s entire asset load - one
     * bundle decoded, one atlas page uploaded - and naming it is what lets `udeaBenchStartup`
     * attribute a regression to assets rather than to "startup".
     */
    private val frames: Array<TextureRegion> =
        dev.wildware.moba.entry.StartupTrace.asset { loadFrames(resources) }

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
        // World units per pixel, out of the live graph. One array read per frame, and the reason
        // an `assets.patch` is visible in the next capture.
        val scale = (registry.at(sheetIndex) as SpriteSheet).scale
        val width = frame.regionWidth * scale
        val height = frame.regionHeight * scale
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
                        position.x - width / 2f,
                        position.y - height / 2f,
                        width,
                        height,
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
         * Simulation ticks per animation frame.
         *
         * At the default 60Hz tick that is a 10fps playhead, which is about right for a
         * six-frame idle and - more usefully here - means a `time.step` of one tick usually does
         * *not* change the picture, so a diff between two captures reports the mutation rather
         * than the animation.
         */
        const val TICKS_PER_FRAME: Long = 6L

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
         * The atlas pages uploaded, and the sheet's frames cut out of them.
         *
         * Every page is uploaded even though this game draws from one: a page the atlas declares
         * and nobody uploads is a `RenderAssets`-style "region on a page that was not loaded"
         * failure the moment a second sheet is declared, and the loop costs nothing today.
         *
         * @throws IllegalStateException when the bundle holds no frames for the sheet. Loud,
         *   because the alternative - drawing nothing - is a bug that looks like art direction,
         *   and it means the pack and the graph disagree, which is a packer defect rather than an
         *   authoring one.
         */
        private fun loadFrames(resources: RenderResources): Array<TextureRegion> {
            val bundle = MobaAssets.bundle
            val pages = List(bundle.atlas.pages.size) { page ->
                val encoded = bundle.atlasPage(page)
                val pixmap = Pixmap(encoded, 0, encoded.size)
                val texture = resources.own(Texture(pixmap))
                // The `Texture` copies the pixels on upload, so the decode buffer is this
                // function's to free; leaving it is a native leak GL never reports.
                pixmap.dispose()
                // Nearest, because the source is pixel art and a linear filter turns a 64px frame
                // scaled to 34 world units into mush.
                texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
                texture
            }
            val regions = bundle.atlas.framesOf(GameAssets.champion.idleSheet.id)
            check(regions.isNotEmpty()) {
                "the bundle declares ${GameAssets.champion.idleSheet.id} but packed no frames " +
                    "for it; the atlas holds ${bundle.atlas.size} region(s) across " +
                    "${bundle.atlas.sheets.size} sheet(s)"
            }
            return Array(regions.size) { at ->
                val region = regions[at]
                TextureRegion(pages[region.page], region.x, region.y, region.width, region.height)
            }
        }
    }
}
