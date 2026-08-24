package dev.wildware.moba

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.moba.level.GameUnit
import dev.wildware.moba.level.Team
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.AssetIndex
import dev.wildware.udea.assets.SpriteAnimation
import dev.wildware.udea.assets.SpriteSheet
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.moba.lane.LaneRenderSystem
import dev.wildware.udea.render.RenderPhase
import dev.wildware.udea.render.RenderPipeline
import dev.wildware.udea.render.RenderRegistry
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.camera.CameraRig
import dev.wildware.udea.render.control.PresentationControl
import dev.wildware.udea.render.draw.DebugDraw

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
 * [CharacterRenderSystem] for the tick-timed playhead, and for what happens on a clone with no
 * art extracted.
 *
 * ## What it is honestly not
 *
 * - **Nothing here interpolates.** [PositionPoses] returns the simulated position and ignores
 *   the frame alpha, because a `moba` unit moves in whole ticks and carries no `Interp` to
 *   interpolate from. On a 60Hz display that is exact; above it, it is the judder `Interp`
 *   exists to remove, and closing it means giving these units physics bodies.
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

    /**
     * Puts the camera on [netId] from the next frame on. A `null` stops following.
     *
     * `requestFollow` and not a direct write, because this is called from the thread that booted
     * the game and the camera is the render thread's: the request is consumed at the top of a
     * frame, so the projection matrix a frame is being drawn with never changes underneath it.
     *
     * The rig eases in from wherever the camera was - the default framing this scene asked for at
     * build time - over about a fifth of a second, rather than cutting. That is deliberate: a cut
     * on the first frame of a session reads as a glitch, and a short glide reads as the camera
     * finding the player. `CameraRig.snapToTarget` is the cut, and belongs to the frames where
     * easing would be wrong (a restore), not to this one.
     */
    public fun follow(netId: NetId?) {
        camera.requestFollow(netId)
    }

    /**
     * Points the camera at the level's four clearings and stops following anything.
     *
     * ## Why this is a call and not something [build] does
     *
     * It used to be the last line of [build], and it silently broke every follow in the game.
     * `CameraRig.applyRequests` drains the follow request and *then* the look-at, and a look-at
     * clears the follow target by design - "placing the camera by hand and following are two
     * answers to the same question". Both requests are made before the first frame ever runs:
     * `build` asked for the framing and `MobaEntry.follow` asked for the player. So frame one
     * consumed both, the framing won, and the camera sat on a fixed point for the life of the
     * process while `render.follow_entity` reported `{"following": N}` quite truthfully.
     *
     * The symptom was not "the camera is in the wrong place". It was that the twenty-seven units
     * converge into one melee, the melee is nowhere near (25, -25), and by tick six hundred a
     * screenshot of the game is a screenshot of the empty half of the field - which reads as the
     * fight having stopped.
     *
     * So the framing is now something a caller asks for, and only a caller with nothing to follow
     * asks. `MobaShot` does, because a roster capture has no player in it.
     */
    public fun frameLevel() {
        camera.requestLookAt(CAMERA_X, CAMERA_Y, 1f)
    }

    public companion object {

        /**
         * World units kept visible across the wider axis.
         *
         * Framed on `level/test_level` rather than on a number that once suited one drifting
         * unit: the level's four clearings span about two hundred and thirty world units after
         * `TestLevelScene.SCATTER` is applied at both ends, and a camera narrower than the field
         * makes a screenshot of a battle a screenshot of an empty corner of one.
         */
        public const val WORLD_WIDTH: Float = 320f

        /** World units kept visible across the shorter axis. Same aspect the window asks for. */
        public const val WORLD_HEIGHT: Float = 180f

        /** Where [frameLevel] looks: the centre of the level's four clearings. */
        public const val CAMERA_X: Float = 25f

        /** @see CAMERA_X */
        public const val CAMERA_Y: Float = -25f

        /**
         * Builds the scene for [definition].
         *
         * Takes the definition and not a [GameContext] because [CameraRig] needs the net id
         * index, and that is reachable off the definition's core module before any host exists -
         * which matters, since the backend must be constructed before the host and the registry
         * must be complete before the backend.
         */
        public fun build(definition: UdeaGameDef): MobaScene {
            // Off the definition's own module list, so the attribute ids the bars read are the
            // ones the world's units were actually built with. See `HealthbarRenderSystem`.
            val combat = definition.modules.filterIsInstance<MobaModule>().singleOrNull()?.combat
                ?: error(
                    "this definition has no MobaModule, so there is no attribute table to draw " +
                        "health out of; `MobaGame.definition()` is what assembles one",
                )
            val registry = RenderRegistry()
            val debug = DebugDraw(enabled = false)
            val camera = CameraRig(
                netIds = definition.core.netIds,
                poses = PositionPoses,
                frameTime = registry.frameTime,
                worldWidth = WORLD_WIDTH,
                worldHeight = WORLD_HEIGHT,
            )
            // No `requestLookAt` here. See `frameLevel` - a framing requested at build time is
            // drained in the same frame as the follow every entry point asks for, and wins.
            // Positional, not a trailing lambda: `register`'s trailing lambda is the ordering
            // constraint block, and a factory written there registers nothing at all.
            registry.register(RenderPhase.PreRender, { camera })
            // The ground, first in the phase and constrained rather than merely registered first:
            // `RenderOrder` tie-breaks by registration index, so this would in fact draw first
            // today - and an edit that moved a line would have painted the field over the units
            // with nothing to say so. See `BackgroundRenderSystem` for why `moba` had no ground
            // at all until now (`gameConfig.backgroundTexture` was null, and the old
            // `BackgroundDrawSystem` drew nothing when it was).
            val ground = registry.register(
                RenderPhase.World,
                { resources -> BackgroundRenderSystem(resources, camera) },
            )
            // The lane and its towers, over the ground and under everything alive. There is no
            // structure art in this repository, so `LaneRenderSystem` draws shapes; see its
            // KDoc. It is constrained `after(ground)` and the characters are constrained after
            // *it*, so a creep walking past a tower is drawn in front of it rather than behind
            // whichever of the two happened to be registered second.
            val lane = registry.register(
                RenderPhase.World,
                { resources -> LaneRenderSystem(resources, camera) },
            ) { after(ground) }
            val characters = registry.register(
                RenderPhase.World,
                { resources -> CharacterRenderSystem(resources, camera) },
            ) { after(lane) }
            // Arrows and flashes over bodies. A second pass and not a wider family on the first:
            // `CharacterView` indexes the `CharacterRoster`, which refuses anything short of the
            // five `UnitState`s, and an arrow has one frame and no states. See `MobaVfx.kt`.
            registry.register(
                RenderPhase.World,
                { resources -> SpriteRenderSystem(resources, camera) },
            ) { after(characters) }
            // Bars over bodies. `after` and not registration order: both are in `RenderPhase.World`
            // and the phase alone does not order two systems inside it, so a later edit that moves
            // this line above the characters would silently draw every bar behind its own sprite.
            registry.register(
                RenderPhase.World,
                { resources -> HealthbarRenderSystem(resources, camera, combat.attributes) },
            ) { after(characters) }
            // The player's own HUD: health, mana, and the two ability slots with their cooldowns.
            // `RenderPhase.UI` and not `World`, because it is screen space and because the phase
            // is what puts it above every world pass without a constraint against each one. It is
            // still before the capture point on purpose - see `MobaHudSystem`.
            registry.register(
                RenderPhase.UI,
                { resources ->
                    MobaHudSystem(
                        resources = resources,
                        frameTime = registry.frameTime,
                        attributeIds = combat.attributes,
                        abilityTable = combat.abilities.table,
                        activation = combat.gas.activation,
                    )
                },
            )
            return MobaScene(registry, camera, debug)
        }
    }
}


/**
 * One animated character per [Position], in world space, through the shared batch.
 *
 * ## Three things it does that make a melee readable, and one that makes an ability visible
 *
 * - **It y-sorts.** The pass used to walk the family in spawn order, so a unit behind another was
 *   drawn over it whenever it happened to spawn later, and a rewound world drew the same fight
 *   differently because the restore repopulated the bag in a different order. See
 *   [WorldDrawOrder].
 * - **A corpse is a layer, not a body.** `DeathSystem` leaves the dead on the field and they used
 *   to occlude the living. [DrawLayer.CORPSE] puts every one of them under every fighting unit.
 * - **Every unit stands on a team-coloured footprint, and the player's is a ring with a chevron
 *   over it.** A play agent measured eleven soldiers inside two sprite widths, fully overlapping,
 *   with nothing at all marking which one the human was driving. Sorting decides which sprite
 *   wins; it cannot make eleven copies of one sprite countable, and discs on the ground plane
 *   can. See [WorldMarkers].
 * - **The special is drawn.** `orc_elite_spin` is eleven frames of `orc_elite_attack02.png`,
 *   packed, cut into the atlas, and shown by nothing: `CharacterRoster` files it under
 *   [CharacterEntry.extras] because its id ends `_spin` rather than in one of the five state
 *   suffixes, and this pass only ever asked for `entry.animation(state)`. So the elite's spin -
 *   an ability with a `TargetPolicy`, a cue and 150% AoE damage behind it - looked exactly like
 *   its ordinary sword swing. See [specialOf].
 *
 * ## What replaced what
 *
 * `ChampionRenderSystem`, with the two things it could not do added: it draws **more than one
 * sheet**, and it draws **the sheet the entity's state selects**. The old one resolved a single
 * `champion/idle_sheet` in its constructor and drew that one strip on every unit - which is how a
 * demo comes to score two distinct colours: one placeholder sprite, drawn once per entity. Every
 * sheet in the atlas is cut here, and which one an entity shows is [CharacterView.state] resolved
 * through [CharacterRoster].
 *
 * ## Every number it draws with came out of the bundle
 *
 * The frames are `AtlasIndex` regions cut at pack time out of the atlas pages, and the world size
 * is a region's pixel size multiplied by the authored `SpriteSheet.scale`. Nothing here divides a
 * texture, there is no `WORLD_SCALE` constant left to override an artist, and there is no
 * `Gdx.files` call that would let a renderer decide a frame grid for itself again - the three
 * defects issue #123 names, checked from the other end by `MobaAssetsTest`.
 *
 * ## The scale is read every frame, and that is the hot-reload proof
 *
 * The scale is not cached at bind time. It is `registry.at(sheetIndex)` per frame - one array
 * index into the live [dev.wildware.udea.assets.AssetRegistry], the same object `AssetHotReload`
 * swaps a new `SpriteSheet` into at the top of a `Simulation.step`. So an agent that patches
 * `orcScale` in `moba/assets/character/orc.udea.kts` changes the size of every orc in the very
 * next capture, through the simulation's own asset graph - and cannot fake it, because no
 * `world.*` tool can write a sprite size.
 *
 * ## The playhead is the simulation tick, and that is deliberate
 *
 * See [CharacterAnimator] for the argument in full. The short version: an agent captures a
 * **paused** world and `render.compare_artifacts` measures the difference between two captures,
 * so a wall-timed playhead would make two screenshots of an identical, paused, unmutated world
 * differ by however long the agent spent thinking. `ctx.clock` is read, never written.
 */
internal class CharacterRenderSystem(
    private val resources: RenderResources,
    private val camera: CameraRig,
) : RenderSystem {

    private var world: World? = null

    private var units: Family? = null

    /** Read in [render] for the frame index. Never written. See the class KDoc. */
    private var clock: SimClock? = null

    /** The live graph. The values behind the slots below are read fresh every frame. */
    private val registry = MobaAssets.registry

    /** Which animation each character plays for each state, read out of the bundle. */
    private val roster = MobaCharacters.roster

    /**
     * The frames of every sheet in the atlas, cut at pack time, pointing into the pages.
     *
     * Built in the constructor rather than in [onBind] because the factory that calls this runs
     * on the render thread inside `RenderRegistry.build`, which is where a GL context exists.
     *
     * Bracketed as the asset phase of `StartupTrace`: this is `moba`'s entire asset load, and
     * naming it is what lets `udeaBenchStartup` attribute a regression to assets rather than to
     * "startup".
     */
    private val framesBySheet: Map<AssetId, Array<TextureRegion>> =
        dev.wildware.moba.entry.StartupTrace.asset { loadFrames(resources) }

    /**
     * The slot each sheet occupies in the graph, resolved once.
     *
     * Once because a slot is pack-time stable - that is the whole property an `AssetIndex` exists
     * for - and it survives a value-only reload by construction, since `AssetRegistry.applyDelta`
     * swaps at the same slot. The value behind it is read every frame.
     */
    private val sheetSlots: Map<AssetId, AssetIndex> =
        framesBySheet.keys.associateWith { registry.indexOf(it) }

    /** The footprint, the player's ring and the player's chevron. Built once, disposed by the pipeline. */
    private val markers = WorldMarkers(resources)

    /** This frame's back-to-front order. Reused every frame; see [WorldDrawOrder] on allocation. */
    private val order = WorldDrawOrder()

    /**
     * Each roster entry's special animation, by roster index, or `null` where it has none.
     *
     * Resolved once, against the roster, because it is a pure function of the bundle: a map
     * lookup per entity per frame for an answer that cannot change without a new bundle is the
     * "linear scans as lookups" smell §1 names, one level up.
     */
    private val specials: Array<SpriteAnimation?> =
        Array(roster.size) { at -> roster.at(at).extras[SPECIAL_SUFFIX] }

    /** Characters actually drawn by the most recent [render]. A health signal, not state. */
    internal var drawnCount: Int = 0
        private set

    /**
     * Units drawn with their special animation in the most recent [render].
     *
     * Not decorative, and the same argument [drawnCount] carries: "the spin is never drawn" and
     * "no orc elite spun during those frames" produce the same PNG, and only one of them is a
     * renderer defect.
     */
    internal var specialCount: Int = 0
        private set

    override fun onBind(world: World, ctx: GameContext) {
        this.world = world
        this.clock = ctx.clock
        units = world.family { all(Position, CharacterView) }
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        val world = this.world ?: return
        val units = this.units ?: return
        val clock = this.clock ?: return
        drawnCount = 0
        specialCount = 0
        val now = clock.tick.value
        val tickRate = clock.tickRate
        val batch = resources.batch
        batch.projectionMatrix = camera.camera.combined
        batch.color = Color.WHITE
        batch.begin()
        try {
            with(world) {
                collect(units)
                var index = 0
                while (index < order.size) {
                    draw(order.entityAt(index), batch, now, tickRate)
                    index++
                }
            }
        } finally {
            // In a `finally` because a `Batch` left begun poisons every later pass in the frame
            // with a "batch already begun" failure that names the wrong system.
            batch.end()
            batch.color = Color.WHITE
        }
    }

    /**
     * Puts this frame's units into [order], back to front.
     *
     * Walks `Family.entities` rather than `Family.forEach`: Fleks' `forEach` takes a
     * `Function2`, so a lambda that captures anything is one allocation per pass per frame - and
     * a frame budget is measured in bytes, not in whether the allocation is small.
     */
    private fun World.collect(units: Family) {
        order.begin()
        val entities = units.entities
        var index = 0
        while (index < entities.size) {
            val entity = entities[index]
            val layer =
                if (entity[CharacterView].state == UnitState.Death) DrawLayer.CORPSE
                else DrawLayer.UNIT
            order.add(entity, layer, entity[Position].y)
            index++
        }
        order.sort()
    }

    /** One unit: its footprint, its frame, and the player's markers if this is the player. */
    private fun World.draw(entity: Entity, batch: Batch, now: Long, tickRate: Int) {
        val position = entity[Position]
        val view = entity[CharacterView]
        val entry = roster.at(view.character)
        val special = specialOf(entity, view)
        val animation = special ?: entry.animation(view.state)
        val frames = framesBySheet[animation.sheet.id] ?: return
        // The special's playhead starts at the activation and not at `view.startTick`: a unit
        // that was already in `Attack` when the special fired - which is every elite that spins
        // straight out of a sword swing - would otherwise start the spin part-way through.
        val startTick = if (special == null) view.startTick else activationTick(entity, view)
        val at = CharacterAnimator.frameAt(animation, frames.size, now - startTick, tickRate)
        val frame = frames[at]
        // World units per pixel, out of the live graph. One array read per frame, and
        // the reason an `assets.patch` is visible in the next capture.
        val sheetIndex = sheetSlots.getValue(animation.sheet.id)
        val scale = (registry.at(sheetIndex) as SpriteSheet).scale
        val width = frame.regionWidth * scale
        val height = frame.regionHeight * scale
        val dead = view.state == UnitState.Death
        val player = entity.has(Player)
        // Under the sprite, so a unit standing on its own marker hides the top of it - which is
        // what makes the disc read as being on the ground rather than painted on the unit.
        marks(batch, position, height, entity.getOrNull(GameUnit)?.team ?: Team.NONE, dead, player)
        // A negative width and a shifted origin rather than `TextureRegion.flip`: the
        // regions are shared by every entity drawing that sheet, so flipping one in
        // place would mirror the whole roster for the rest of the frame.
        val drawWidth = if (view.flipX) -width else width
        batch.color = Color.WHITE
        batch.draw(
            frame,
            position.x - drawWidth / 2f,
            position.y - height / 2f,
            drawWidth,
            height,
        )
        if (player) {
            markers.chevron(batch, position.x, position.y + CHEVRON_TIP_Y, CHEVRON_WIDTH, PLAYER_COLOUR, PLAYER_ALPHA)
        }
        batch.color = Color.WHITE
        drawnCount++
        if (special != null) specialCount++
    }

    /** The footprint under one unit, and the ring instead of it when the unit is the player. */
    @Suppress("LongParameterList")
    private fun marks(
        batch: Batch,
        position: Position,
        height: Float,
        team: Int,
        dead: Boolean,
        player: Boolean,
    ) {
        val y = position.y - height * FOOT_OF_HEIGHT
        val width = height * FOOTPRINT_OF_HEIGHT
        val colour = HealthbarRenderSystem.colourOf(team)
        // A corpse keeps a footprint, faint: it is what stops a body on the ground reading as a
        // living unit lying down, and it is how a viewer sees where the fight has already been.
        markers.footprint(batch, position.x, y, width, colour, if (dead) CORPSE_ALPHA else FOOT_ALPHA)
        if (player) markers.ring(batch, position.x, y, width * PLAYER_RING_SCALE, PLAYER_COLOUR, PLAYER_ALPHA)
    }

    /**
     * The special animation [entity] should be showing, or `null` for the plain state animation.
     *
     * ## Why this reads the ability and not a sixth [UnitState]
     *
     * `CharacterStateSystem` derives one `Attack` state from "any ability instance is active",
     * deliberately: the picture is then a pure function of restored components and survives a
     * rewind. Adding `SpinAttack` to [UnitState] would mean a sixth suffix every character in the
     * bundle has to declare, and `CharacterEntry`'s own `require` refuses a character short of
     * one - so five of the six characters would stop loading.
     *
     * So the *state* stays five-valued and the renderer asks the one further question it needs:
     * is the unit's **special** slot the one that is active? That is a read of simulation state
     * on the render thread and writes nothing, which is exactly what a presentation system is
     * allowed to do (spec 3.3).
     *
     * The slot is [PlayerControlSystem.SLOT_SECONDARY] rather than a constant of this file's own,
     * because there must be exactly one answer to "which slot is the special": `UnitBlueprint.dress`
     * grants in that order, `PlayerControlSystem` fires that slot on the second attack key, and a
     * renderer that disagreed would draw the spin over the sword swing.
     */
    private fun World.specialOf(entity: Entity, view: CharacterView): SpriteAnimation? {
        if (view.state != UnitState.Attack) return null
        val special = specials[Math.floorMod(view.character, specials.size)] ?: return null
        val abilities = entity.getOrNull(Abilities) ?: return null
        if (PlayerControlSystem.SLOT_SECONDARY >= abilities.slotCount) return null
        val instance = abilities.instanceAt(PlayerControlSystem.SLOT_SECONDARY)
        return if (instance.isGranted && instance.isActive) special else null
    }

    /** The tick the special slot was activated on, falling back to the state's own start. */
    private fun World.activationTick(entity: Entity, view: CharacterView): Long {
        val abilities = entity.getOrNull(Abilities) ?: return view.startTick
        return abilities.instanceAt(PlayerControlSystem.SLOT_SECONDARY).activatedTick.value
    }

    internal companion object {

        /**
         * The id suffix of an animation that is a character's special rather than one of the five
         * states.
         *
         * The same convention `UnitState.suffix` is: `character/orc_elite_spin` is the `spin` of
         * `orc_elite`, and `CharacterRoster` already files anything whose suffix is not a state
         * under [CharacterEntry.extras] keyed by exactly this string. Naming it here rather than
         * writing `"spin"` inline is what makes the contract greppable from both ends.
         */
        const val SPECIAL_SUFFIX: String = "spin"

        /**
         * How far below a unit's [Position] its feet are, as a fraction of the drawn frame.
         *
         * A fraction and not a constant in world units, because the six characters are authored
         * at scales from 1.25 to 1.88 and a fixed drop would put the orc's marker at its knees
         * and the elite's under the ground. The frames are mostly transparent margin - a 100px
         * sheet at 1.88 draws 188 world units and the character inside it is about 30 - so this
         * is small.
         */
        const val FOOT_OF_HEIGHT: Float = 0.085f

        /** A footprint's width, as a fraction of the drawn frame height. @see FOOT_OF_HEIGHT */
        const val FOOTPRINT_OF_HEIGHT: Float = 0.17f

        /**
         * Where the point of the player's chevron sits, in world units above their [Position].
         *
         * Clear of the health bar rather than a fraction of the frame, and derived from the bar's
         * own numbers: a chevron placed by frame height lands at a different height on each of
         * the six characters, and on the elite it landed *inside* the rail. Both markers are
         * screen furniture at a fixed size, so both are placed in fixed world units.
         */
        const val CHEVRON_TIP_Y: Float =
            HealthbarRenderSystem.OFFSET_Y + HealthbarRenderSystem.HEIGHT + 1.5f

        /** The chevron's width, in world units. About a third of a unit's shoulders. */
        const val CHEVRON_WIDTH: Float = 11f

        /** The player's ring, relative to the footprint it replaces: wider, so it reads as a ring. */
        const val PLAYER_RING_SCALE: Float = 1.35f

        /** A living unit's footprint. Solid enough to count, faint enough not to be a sprite. */
        const val FOOT_ALPHA: Float = 0.55f

        /** A corpse's footprint. */
        const val CORPSE_ALPHA: Float = 0.22f

        /** The player's ring and chevron. Fully opaque: it is the one thing that must not be missed. */
        const val PLAYER_ALPHA: Float = 1f

        /** The ring, the chevron and the box around the player's own rail. @see WorldMarkers */
        val PLAYER_COLOUR: Color = WorldMarkers.PLAYER_COLOUR

        /**
         * Every atlas page uploaded, and every sheet's frames cut out of them.
         *
         * All pages, not only the ones the first frame draws from: a page the atlas declares and
         * nobody uploads is a "region on a page that was not loaded" failure the moment a unit
         * enters a state whose sheet landed there - a bug that appears seconds into a session
         * rather than at boot, which is the worst kind to attribute.
         *
         * @throws IllegalStateException when the atlas holds no regions at all. Loud, because the
         *   alternative - drawing nothing - is a bug that looks like art direction, and it means
         *   the pack and the graph disagree, which is a packer defect rather than an authoring
         *   one.
         */
        private fun loadFrames(resources: RenderResources): Map<AssetId, Array<TextureRegion>> {
            val bundle = MobaAssets.bundle
            val atlas = bundle.atlas
            check(atlas.size > 0) {
                "the bundle packed no atlas regions at all, so there is nothing to draw; " +
                    "`:moba:udeaPackBundle` reports the sheet count it packed"
            }
            val pages = List(atlas.pages.size) { page ->
                val encoded = bundle.atlasPage(page)
                val pixmap = Pixmap(encoded, 0, encoded.size)
                val texture = resources.own(Texture(pixmap))
                // The `Texture` copies the pixels on upload, so the decode buffer is this
                // function's to free; leaving it is a native leak GL never reports.
                pixmap.dispose()
                // Nearest, because the source is pixel art and a linear filter turns a 100px
                // frame scaled to 16 world units into mush.
                texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
                texture
            }
            return atlas.sheets.associate { sheet ->
                val id = AssetId(sheet)
                val regions = atlas.framesOf(id)
                check(regions.isNotEmpty()) {
                    "the atlas names sheet " + sheet + " and holds no regions for it"
                }
                id to Array(regions.size) { at ->
                    val region = regions[at]
                    TextureRegion(pages[region.page], region.x, region.y, region.width, region.height)
                }
            }
        }
    }
}
