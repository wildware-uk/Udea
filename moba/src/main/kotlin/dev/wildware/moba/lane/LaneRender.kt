package dev.wildware.moba.lane

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import dev.wildware.moba.Position
import dev.wildware.moba.level.Team
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.camera.CameraRig

/**
 * The lane and the two towers on it, drawn as shapes.
 *
 * ## Why shapes and not art
 *
 * `docs/art-assets.md` has forty characters in it and no structures, and there is no ground
 * tilesheet either - `BackgroundRenderSystem` computes its own tile for exactly that reason. A
 * tower wearing a `CharacterView` would be an orc standing very still, which reads as a bug
 * rather than as a building; a `SpriteView` needs an authored `spriteAnimation` and there is no
 * tower PNG to author one over. So a tower is a plinth, a shaft and a team-coloured crown, and
 * the lane under it is a translucent ribbon between the waypoints [LaneMarchSystem] walks.
 *
 * The brief said not to block on art, and this is what not blocking on it looks like: the
 * geometry is legible in a capture, the colours say whose it is, and swapping in a sheet later
 * changes this file and nothing else.
 *
 * ## Why it draws the path at all
 *
 * The ribbon is not decoration. `LaneGeometry.PATH_X`/`PATH_Y` is the only thing that makes a
 * creep's route a *lane* rather than a diagonal, and a capture with no ribbon in it cannot
 * distinguish "creeps are walking the lane" from "creeps are walking toward each other". It is
 * drawn from the same two arrays the march reads, so it cannot disagree with where creeps go.
 *
 * ## Not a Fleks system, and no per-frame allocation
 *
 * Spec 3.3: this is a `RenderSystem`, so it is not in the world's system list, a headless server
 * never constructs it, and a rewind does not re-run it. It draws one 1x1 white texture stretched
 * into rectangles through the frame's single [RenderResources.batch] - the same trick
 * `HealthbarRenderSystem` uses, and for the same reason: `RenderResources` hands out exactly one
 * `Batch`, and a second immediate-mode renderer would force a flush between every pass.
 */
public class LaneRenderSystem(
    resources: RenderResources,
    private val camera: CameraRig,
) : RenderSystem {

    private val batch = resources.batch

    /**
     * One white texel, stretched into every rectangle this system draws.
     *
     * Owned by [RenderResources.own], so the pipeline disposes it in reverse construction order
     * and a scene rebuilt twice does not leak a texture.
     */
    private val pixel: TextureRegion = TextureRegion(
        resources.own(
            Texture(
                Pixmap(1, 1, Pixmap.Format.RGBA8888).apply {
                    setColor(Color.WHITE)
                    fill()
                },
            ).also { it.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest) },
        ),
    )

    private var world: World? = null

    private var towers: Family? = null

    /**
     * Towers drawn in the last frame.
     *
     * Not decorative: a capture of a lane with no towers in it and a capture of a lane whose
     * renderer silently skipped them are the same PNG. `LaneRenderTest` asserts against this
     * rather than against pixels - the same argument `HealthbarRenderSystem.drawnCount` makes.
     */
    public var drawnTowers: Int = 0
        private set

    override fun onBind(world: World, ctx: GameContext) {
        this.world = world
        towers = world.family { all(Tower, Position) }
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        val world = this.world ?: return
        val towers = this.towers ?: return
        drawnTowers = 0
        batch.projectionMatrix = camera.camera.combined
        batch.begin()
        try {
            drawLane()
            with(world) {
                val entities = towers.entities
                var index = 0
                while (index < entities.size) {
                    val entity = entities[index]
                    index++
                    val position = entity[Position]
                    drawTower(position.x, position.y, entity[Tower])
                    drawnTowers++
                }
            }
        } finally {
            // In a `finally` because a `Batch` left begun poisons every later pass in the frame
            // with a failure that names the wrong system. Same reason `CharacterRenderSystem`
            // and `HealthbarRenderSystem` both do it.
            batch.end()
            batch.color = Color.WHITE
        }
    }

    /** The ribbon, one quad per segment, rotated to lie along it. */
    private fun drawLane() {
        batch.color = LANE_COLOUR
        var index = 0
        while (index < LaneGeometry.WAYPOINTS - 1) {
            val x0 = LaneGeometry.PATH_X[index]
            val y0 = LaneGeometry.PATH_Y[index]
            val x1 = LaneGeometry.PATH_X[index + 1]
            val y1 = LaneGeometry.PATH_Y[index + 1]
            index++
            val dx = x1 - x0
            val dy = y1 - y0
            val length = kotlin.math.sqrt(dx * dx + dy * dy)
            if (length < 1e-4f) continue
            val degrees = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
            // Rotated about the middle of the left edge, so the quad starts at the waypoint and
            // runs to the next one however the segment is angled.
            batch.draw(
                pixel,
                x0,
                y0 - LANE_WIDTH * 0.5f,
                0f,
                LANE_WIDTH * 0.5f,
                length,
                LANE_WIDTH,
                1f,
                1f,
                degrees,
            )
        }
        batch.color = Color.WHITE
    }

    /**
     * One tower: a plinth, a shaft, and a crown in the owner's colour.
     *
     * Drawn upward from the entity's [Position], because a `Position` in this game is where a
     * unit's feet are - see `CharacterRenderSystem` - and a tower whose middle sat on its own
     * feet would be half buried in the ground.
     */
    private fun drawTower(x: Float, y: Float, tower: Tower) {
        batch.color = PLINTH_COLOUR
        batch.draw(pixel, x - PLINTH_WIDTH * 0.5f, y, PLINTH_WIDTH, PLINTH_HEIGHT)
        batch.color = SHAFT_COLOUR
        batch.draw(
            pixel,
            x - SHAFT_WIDTH * 0.5f,
            y + PLINTH_HEIGHT,
            SHAFT_WIDTH,
            SHAFT_HEIGHT,
        )
        batch.color = colourOf(tower.team)
        batch.draw(
            pixel,
            x - CROWN_WIDTH * 0.5f,
            y + PLINTH_HEIGHT + SHAFT_HEIGHT,
            CROWN_WIDTH,
            CROWN_HEIGHT,
        )
        // A lit crown while the tower is holding a target, so a still frame says which tower is
        // firing. It is read off simulation state and nothing here writes any.
        if (tower.hasTarget) {
            batch.color = MUZZLE_COLOUR
            batch.draw(
                pixel,
                x - MUZZLE_SIZE * 0.5f,
                y + PLINTH_HEIGHT + SHAFT_HEIGHT + CROWN_HEIGHT,
                MUZZLE_SIZE,
                MUZZLE_SIZE,
            )
        }
        batch.color = Color.WHITE
    }

    private fun colourOf(team: Int): Color = when (team) {
        Team.SOLDIER -> SOLDIER_COLOUR
        Team.UNDEAD -> UNDEAD_COLOUR
        Team.ORC -> ORC_COLOUR
        else -> NEUTRAL_COLOUR
    }

    override fun toString(): String = "LaneRenderSystem(towers=$drawnTowers)"

    public companion object {

        /** How wide the ribbon is, in world units. About two sprite widths. */
        public const val LANE_WIDTH: Float = 44f

        /** Plinth footprint. */
        public const val PLINTH_WIDTH: Float = 54f

        /** @see PLINTH_WIDTH */
        public const val PLINTH_HEIGHT: Float = 14f

        /** @see PLINTH_WIDTH */
        public const val SHAFT_WIDTH: Float = 32f

        /** @see PLINTH_WIDTH */
        public const val SHAFT_HEIGHT: Float = 62f

        /** @see PLINTH_WIDTH */
        public const val CROWN_WIDTH: Float = 46f

        /** @see PLINTH_WIDTH */
        public const val CROWN_HEIGHT: Float = 20f

        /** The lit block on top of a tower that is holding a target. */
        public const val MUZZLE_SIZE: Float = 14f

        private val LANE_COLOUR = Color(0.72f, 0.66f, 0.44f, 0.30f)
        private val PLINTH_COLOUR = Color(0.32f, 0.30f, 0.28f, 1f)
        private val SHAFT_COLOUR = Color(0.52f, 0.50f, 0.46f, 1f)
        private val MUZZLE_COLOUR = Color(1f, 0.92f, 0.45f, 1f)

        /** The same three team colours `HealthbarRenderSystem` draws bars in. */
        private val SOLDIER_COLOUR = Color(0.30f, 0.55f, 0.95f, 1f)

        /** @see SOLDIER_COLOUR */
        private val UNDEAD_COLOUR = Color(0.65f, 0.35f, 0.85f, 1f)

        /** @see SOLDIER_COLOUR */
        private val ORC_COLOUR = Color(0.85f, 0.30f, 0.25f, 1f)

        /** @see SOLDIER_COLOUR */
        private val NEUTRAL_COLOUR = Color(0.6f, 0.6f, 0.6f, 1f)
    }
}
