package dev.wildware.moba

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
 * So this draws exactly one thing: a quad per entity, at that entity's [Position]. It is not art
 * and it is not a placeholder for art. It is the game state made visible, which is the property
 * the render toolset exists to serve and the only property the demo's diff measures.
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
                { resources -> UnitQuadRenderSystem(resources, camera) },
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
 * One quad per [Position], in world space, through the shared batch.
 *
 * The quad is [SIZE] world units and centred on the entity, so a unit at `x = 9` and a unit at
 * `x = 9.25` produce *different pictures* - which is what makes a one-tick step and a hundred-tick
 * rewind both visible in a capture rather than merely reported in JSON.
 */
internal class UnitQuadRenderSystem(
    private val resources: RenderResources,
    private val camera: CameraRig,
) : RenderSystem {

    private var world: World? = null

    private var units: Family? = null

    /** One white texel, tinted per draw. Owned by the pipeline, disposed with it. */
    private val pixel: TextureRegion = TextureRegion(
        resources.own(
            Texture(
                Pixmap(1, 1, Pixmap.Format.RGBA8888).apply {
                    setColor(Color.WHITE)
                    fill()
                },
            ),
        ),
    )

    override fun onBind(world: World, ctx: GameContext) {
        this.world = world
        units = world.family { all(Position) }
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        val world = this.world ?: return
        val units = this.units ?: return
        val batch = resources.batch
        batch.projectionMatrix = camera.camera.combined
        batch.color = Color.WHITE
        batch.begin()
        try {
            with(world) {
                units.forEach { entity ->
                    val position = entity[Position]
                    batch.draw(pixel, position.x - HALF_SIZE, position.y - HALF_SIZE, SIZE, SIZE)
                }
            }
        } finally {
            batch.end()
            batch.color = Color.WHITE
        }
    }

    private companion object {
        /** Side of a unit's quad, in world units. */
        const val SIZE: Float = 3f
        const val HALF_SIZE: Float = SIZE / 2f
    }
}
