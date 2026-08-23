package dev.wildware.moba

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import dev.wildware.moba.ability.CharacterAttributes
import dev.wildware.moba.level.GameUnit
import dev.wildware.moba.level.Team
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.camera.CameraRig

/**
 * The floating health bar over every living unit.
 *
 * ## The port, and the one line of it that is the whole point
 *
 * The old `HealthbarSystem` was `class HealthbarSystem(...) : IteratingSystem(family { all(Abilities) })`.
 * It drew. From `onTick`. Inside the ECS.
 *
 * Spec 3.3 forbids that and `RenderRegistry.build` now enforces it - a factory that returns
 * something which is both a `RenderSystem` and a Fleks `IntervalSystem` is refused by name at
 * registration. The reasons are not stylistic:
 *
 * - a headless server ran the drawing code, because `world.update(dt)` ran every system;
 * - a rewind re-ran it, because a rewind re-runs the world;
 * - it drew at the simulation's position rather than the interpolated one, so every bar trailed
 *   the sprite it belonged to by up to one tick at any frame rate above the tick rate;
 * - it read `gameScreen.uiViewport.camera` and `gameScreen.camera` - the file-level global §1
 *   opens with - and projected each unit's world position into screen space per entity, per
 *   frame, allocating a `Vector3` each time.
 *
 * This is a plain [RenderSystem]. It is not in the world's system list, the server never
 * constructs it, and it draws in world space through the same camera matrix and the same [batch]
 * the characters are drawn with, so a bar cannot be one tick behind its own sprite.
 *
 * ## Why a batch quad and not a `ShapeRenderer`
 *
 * The old system opened a `ShapeRenderer` inside `onTick` and drew filled rectangles.
 * `RenderResources` deliberately hands out exactly one `Batch`; a second immediate-mode renderer
 * would force a flush between the character pass and the bar pass on every frame. A 1x1 white
 * texture stretched to a rectangle is one quad in the batch that is already open.
 *
 * ## Both bars, off the attributes themselves
 *
 * Health and max health are read from the unit's `Attributes` - the same `health` attribute
 * `ability/damage` subtracts from and `ability/heal_over_time` adds to - rather than from
 * `Position.hp` and a constant on an enum. There is one number for how hurt a unit is and this
 * draws it, so a bar cannot disagree with the fight; `Position.hp` remains the mirror the
 * snapshot ring and the agent surface read, and is not what is drawn here.
 *
 * The **mana** bar is back, for the same reason it could not be before: mana is a GAS attribute,
 * and until the ability module was wired into the units the level spawns, drawing it would have
 * meant drawing a constant zero. It is drawn only for a unit that has any maximum mana at all -
 * which is the priest and the wizard - because an empty rail under every soldier on the field is
 * chrome rather than information.
 *
 * ## What it does not do yet
 *
 * The old bar coloured by *the local player's* team - `playerTeam == entity[Team]` - so an ally
 * was green wherever they stood. There is one local player and three teams here, and no system
 * yet publishes which team the viewer belongs to, so the colour is per-team ([ORC_COLOUR],
 * [SOLDIER_COLOUR], [UNDEAD_COLOUR]) rather than ally-versus-enemy.
 */
public class HealthbarRenderSystem(
    resources: RenderResources,
    private val camera: CameraRig,
    /**
     * Which slot in an [Attributes] holds health, max health and mana.
     *
     * Handed in rather than looked up, because an [dev.wildware.udea.gas.AttributeId] is an index
     * into one `AttributeTable`, and this game builds a fresh one per `UdeaGameDef`. A renderer
     * that called `CharacterAttributes.create()` for itself would hold ids into a table nothing in
     * the world is using - which would read correctly today, because both tables are built from
     * the same list in the same order, and would start drawing the wrong attribute the first time
     * somebody inserted a name into that list.
     */
    private val attributes: CharacterAttributes,
) : RenderSystem {

    private val batch = resources.batch

    /**
     * One white pixel, stretched.
     *
     * `resources.own` and not a field the caller has to remember to dispose: the pipeline disposes
     * everything the registry handed out, which is the only arrangement where a render system that
     * is registered twice does not leak a texture.
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

    private var units: Family? = null

    /** This frame's bars, back to front. Reused; see [WorldDrawOrder] on allocation. */
    private val order = WorldDrawOrder()

    /**
     * Bars drawn in the last frame.
     *
     * Not decorative: a capture of a battle with zero bars in it and a capture of a battle the
     * renderer silently skipped are the same PNG, and `MobaHealthbarTest` asserts against this
     * rather than against pixels.
     */
    public var drawnCount: Int = 0
        private set

    override fun onBind(world: World, ctx: GameContext) {
        this.world = world
        units = world.family { all(GameUnit, Position, Attributes) }
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        val world = this.world ?: return
        val units = this.units ?: return
        drawnCount = 0
        batch.projectionMatrix = camera.camera.combined
        batch.begin()
        try {
            with(world) {
                collect(units)
                var index = 0
                while (index < order.size) {
                    val entity = order.entityAt(index)
                    val position = entity[Position]
                    val values = entity[Attributes]
                    draw(
                        x = position.x,
                        y = position.y,
                        team = entity[GameUnit].team,
                        health = values.current(attributes.health),
                        maxHealth = values.current(attributes.maxHealth),
                        mana = values.current(attributes.mana),
                        maxMana = values.current(attributes.maxMana),
                        player = entity.has(Player),
                    )
                    drawnCount++
                    index++
                }
            }
        } finally {
            // In a `finally` because a `Batch` left begun poisons every later pass in the frame
            // with a "batch already begun" failure that names the wrong system - the same reason
            // `CharacterRenderSystem` does it.
            batch.end()
            batch.color = Color.WHITE
        }
    }

    /**
     * Which units get a bar this frame, back to front.
     *
     * ## Why this is a filter and not "every unit"
     *
     * It used to be every living unit, and a play agent measured what that looks like when the
     * fight closes: eleven soldiers inside two sprite widths, eleven bars at exactly the same
     * height, stacked into stripes that no viewer can attribute to a body. Every one of those
     * bars was full, so all eleven carried the same information - none.
     *
     * So a bar is drawn for a unit that is **hurt**, has **spent mana**, or is **the player** -
     * which is the set a human is actually reading a bar for. A screen of full bars is noise; a
     * bar appearing the moment a unit takes a hit is the fight becoming legible.
     *
     * Sorted back to front for the same reason the sprites are: two bars that do overlap must
     * resolve the same way the bodies under them did, or the front unit's health is hidden behind
     * the one standing behind it. See [WorldDrawOrder].
     */
    private fun World.collect(units: Family) {
        order.begin()
        val entities = units.entities
        var index = 0
        while (index < entities.size) {
            val entity = entities[index]
            index++
            val values = entity[Attributes]
            val health = values.current(attributes.health)
            // A corpse is left on the field by `DeathSystem`, so a unit at or below zero is one
            // that has already died. Drawing a full-width empty bar over a body reads as a
            // glitch; skipping it reads as death.
            if (health <= 0f) continue
            if (!isWorthDrawing(entity, values, health)) continue
            order.add(entity, DrawLayer.UNIT, entity[Position].y)
        }
        order.sort()
    }

    /** Whether [entity] is hurt, has spent mana, or is the unit the human is driving. */
    private fun World.isWorthDrawing(entity: Entity, values: Attributes, health: Float): Boolean =
        health < values.current(attributes.maxHealth) ||
            values.current(attributes.mana) < values.current(attributes.maxMana) ||
            entity.has(Player)

    @Suppress("LongParameterList")
    private fun draw(
        x: Float,
        y: Float,
        team: Int,
        health: Float,
        maxHealth: Float,
        mana: Float,
        maxMana: Float,
        player: Boolean,
    ) {
        val left = x - WIDTH / 2f
        val bottom = y + OFFSET_Y
        // The player's bar is boxed in the same white-gold the ring under their feet is drawn in,
        // so "which of these bars is mine" has the same answer as "which of these units is mine".
        if (player) {
            batch.color = PLAYER_COLOUR
            batch.draw(
                pixel,
                left - PLAYER_OUTLINE,
                bottom - PLAYER_OUTLINE,
                WIDTH + PLAYER_OUTLINE * 2f,
                HEIGHT + PLAYER_OUTLINE * 2f,
            )
        }
        rail(left, bottom, fractionOf(health, maxHealth), colourOf(team))
        // Only a unit that has mana gets a rail for it: an always-empty second bar under every
        // soldier is chrome, and a viewer learns to ignore it exactly when the priest needs them
        // to notice it.
        if (maxMana > 0f) rail(left, bottom - HEIGHT - GAP, fractionOf(mana, maxMana), MANA_COLOUR)
    }

    /** One bar: the dark backing, then [filled] of [WIDTH] in [colour]. */
    private fun rail(left: Float, bottom: Float, filled: Float, colour: Color) {
        batch.color = BACKGROUND
        batch.draw(pixel, left, bottom, WIDTH, HEIGHT)
        if (filled <= 0f) return
        batch.color = colour
        batch.draw(pixel, left, bottom, WIDTH * filled, HEIGHT)
    }

    public companion object {

        /**
         * How wide a bar is, in world units.
         *
         * A character frame is 64px at the roster's authored `SpriteSheet.scale` of 0.53125, so a
         * unit is about 34 world units across and tall - see `MobaAssetsTest`, which pins
         * `64 * scale == 34f`. A bar slightly narrower than the sprite reads as belonging to it.
         */
        public const val WIDTH: Float = 30f

        public const val HEIGHT: Float = 3.5f

        /** The gap between the health rail and the mana rail under it. */
        public const val GAP: Float = 1f

        /**
         * How far above a unit's position the bar sits.
         *
         * `CharacterRenderSystem` draws a frame centred on the position, so the top of a 34-unit
         * sprite is 17 units up. 20 clears it with a small margin and keeps the bar inside the
         * camera for a unit standing at the top of the field.
         */
        public const val OFFSET_Y: Float = 20f

        /** Fraction of [WIDTH] to fill, clamped: an over-heal must not draw outside the frame. */
        public fun fractionOf(value: Float, maximum: Float): Float =
            if (maximum <= 0f || !value.isFinite()) 0f else (value / maximum).coerceIn(0f, 1f)

        /** The bar colour for a team. Unknown teams draw neutral rather than throwing mid-frame. */
        public fun colourOf(team: Int): Color = when (team) {
            Team.ORC -> ORC_COLOUR
            Team.SOLDIER -> SOLDIER_COLOUR
            Team.UNDEAD -> UNDEAD_COLOUR
            else -> NEUTRAL_COLOUR
        }

        internal val BACKGROUND: Color = Color(0.10f, 0.03f, 0.03f, 0.85f)

        internal val ORC_COLOUR: Color = Color(0.85f, 0.35f, 0.18f, 1f)

        internal val SOLDIER_COLOUR: Color = Color(0.32f, 0.68f, 0.95f, 1f)

        internal val UNDEAD_COLOUR: Color = Color(0.62f, 0.85f, 0.45f, 1f)

        internal val NEUTRAL_COLOUR: Color = Color(0.7f, 0.7f, 0.7f, 1f)

        /** The mana rail. Blue, and the same blue in every team's colours. */
        internal val MANA_COLOUR: Color = Color(0.30f, 0.36f, 0.90f, 1f)

        /** How far the player's box extends past their rail, in world units. */
        public const val PLAYER_OUTLINE: Float = 1.2f

        /**
         * The box around the player's own bar.
         *
         * The same object the ring under the player's feet and the chevron over their head are
         * drawn with - one constant, three readers - so "which of these bars is mine" and "which
         * of these units is mine" can never come to have different answers.
         */
        internal val PLAYER_COLOUR: Color = WorldMarkers.PLAYER_COLOUR
    }
}
