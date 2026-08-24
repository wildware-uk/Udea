package dev.wildware.moba

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.EntityCreateContext
import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import dev.wildware.moba.ability.MobaCues
import dev.wildware.moba.ability.Motion
import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.AssetIndex
import dev.wildware.udea.assets.SpriteAnimation
import dev.wildware.udea.assets.SpriteSheet
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.blueprint.Blueprint
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.core.blueprint.BlueprintSpawner
import dev.wildware.udea.core.blueprint.SpawnOverrides
import dev.wildware.udea.core.blueprint.SpawnPosition
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.gas.GasCueQueue
import dev.wildware.udea.render.OffscreenTarget
import dev.wildware.udea.render.RenderResources
import dev.wildware.udea.render.RenderSystem
import dev.wildware.udea.render.camera.CameraRig

/**
 * Everything in this game that is drawn and is **not** a character: the arrow, and the flashes.
 *
 * ## The hole this fills
 *
 * Three separate pictures were lost in the port and each of them is something a human looks for
 * before they look at a health number:
 *
 * - **The arrow was invisible.** `blueprint/arrow.udea.kts` carried a `spriteRenderer` over
 *   `sprites/arrow/arrow.png`; the ported `ArrowBlueprint` is `Position` + `Motion` +
 *   `Projectile` and the PNG was never copied into this module. A soldier fired, a skeleton
 *   forty world units away lost ten health, and nothing crossed the gap between them.
 * - **Nothing marked a blow landing.** `effects/heal_effect`, `blueprint/effect`, `EffectSystem`
 *   and `PriestHealCue` all existed and none migrated, so damage in this build is a bar getting
 *   shorter. `Priest-Attack_Effect.png` and `Wizard-Attack01_Effect.png` were not copied either.
 * - **[CharacterView] is only for characters.** Its `character` field is an index into
 *   [CharacterRoster], and [CharacterRoster.read] refuses any `spriteAnimationSet` that is short
 *   one of the five [UnitState]s - correctly, since a unit with no death animation is a unit
 *   that vanishes. An arrow has one frame and no states, so dressing one as a roster entry would
 *   mean declaring four fake animations and putting "arrow" in the character list every roster
 *   capture and every blueprint lookup walks.
 *
 * So there is a second, much smaller view component here: [SpriteView], which names one authored
 * `spriteAnimation` by id and nothing else, and one [SpriteRenderSystem] that draws it.
 *
 * ## What is honestly not here
 *
 * - **No shadows.** Every old character pack had a `shadows/` folder drawn under the unit and no
 *   shadow sheet was copied into this module's `sprites` tree. Those sheets are character art and
 *   belong beside the character sheets, which this file does not own.
 * - **Draw order is a shared concern now, and it is [WorldDrawOrder].** The old blueprints
 *   carried `spriteRenderer(order = 10, offset = ...)`; both halves of that are back, as a
 *   per-entity [DrawLayer] and a per-entity depth, and [SpriteRenderSystem] and
 *   [CharacterRenderSystem] sort through the same buffer. [SpriteView.offsetY] stays, because a
 *   flash has to sit on a unit's chest rather than at its feet, which is a different question
 *   from which of two things is in front.
 * - **An effect is a real entity in the simulation**, spawned through the barrier, exactly as
 *   `blueprint/effect` was. That means a hit costs a spawn and a `NetId`, and it means a
 *   `world.query_entities` sees flashes. The alternative - a presentation-side particle pool
 *   that the simulation never knows about - is the better long-term shape and needs a cue
 *   *reader* on the presentation side, which is `udea-render` work rather than a game's.
 */
public class SpriteView(
    /**
     * The authored `spriteAnimation` this draws, by id.
     *
     * An [AssetId] and not an index into a roster: there is no roster for these. It is resolved
     * to frames once per id by [SpriteRenderSystem], not per entity per frame.
     *
     * **No default, deliberately.** It had one - `AssetId("")` - and `AssetId`'s own `init`
     * refuses an empty name, so every flash this game spawned threw inside
     * `Blueprint.configure`. `SimBarrier` logs a throwing action and carries on, so the throw was
     * invisible: the entity survived with the `Position` the spawner had already placed and no
     * view at all, nothing drew it, nothing expired it, and six hundred ticks of a fight left a
     * hundred and seventy-six of them in the world. A required parameter is the only version of
     * this field that cannot be got wrong.
     */
    public var animation: AssetId,
    /** The tick the animation started on. The playhead is `clock.tick - startTick`. */
    public var startTick: Long = 0L,
    /**
     * The tick this entity is removed on, or [FOREVER].
     *
     * A tick and not a duration in seconds, for the reason everything else in this game is
     * tick-denominated: a flash that expired on wall time would expire while a world was paused
     * for an agent to look at it, which is exactly the frame somebody wanted a picture of.
     */
    public var expiryTick: Long = FOREVER,
    /**
     * Whether the sprite is turned to point along its [Motion].
     *
     * True for the arrow, whose art points right and which is fired in every direction; false
     * for a flash, which has no heading. The old arrow was a Box2D kinematic body whose angle
     * nothing ever set, so it flew sideways; this is the fix rather than the port.
     */
    public var facesMotion: Boolean = false,
    /** World units above the entity's [Position] the sprite is centred on. */
    public var offsetY: Float = 0f,
) : Component<SpriteView> {

    override fun type(): ComponentType<SpriteView> = SpriteView

    override fun toString(): String = "SpriteView(${animation.value}@$startTick)"

    /** Fleks' handle for this component. */
    public companion object : ComponentType<SpriteView>() {

        /** An [expiryTick] no clock reaches: the entity is removed by something else, or never. */
        public const val FOREVER: Long = Long.MAX_VALUE
    }
}

/**
 * The three flashes this game can spawn, and every number that differs between them.
 *
 * ## Why this is Kotlin and not an authored `effect(...)`
 *
 * The old corpus spelled it `effect(name = "heal_effect", animationSet = ..., duration = 5.0F)`,
 * and that is the better spelling. `effect` used to be `AssetKind.Unpublishable`: a record declared with
 * it packs as an opaque blob with no runtime type, so a game that read its durations from one
 * would not boot. The **art** is authored - `moba/assets/effects/effects.udea.kts` declares the
 * sheets and the animations, and changing a frame count or a scale there changes what is drawn
 * with no Kotlin edit - and the one field the packer cannot carry is here. It goes away with
 * issue #84, exactly as `character(...)`'s stats do.
 */
public enum class EffectKind(
    /** The authored `spriteAnimation`. */
    public val animation: AssetId,
    /** How many ticks the entity lives before [EffectExpirySystem] removes it. */
    public val lifeTicks: Long,
    /** World units above the target's centre it is drawn at. */
    public val offsetY: Float,
) {

    /**
     * A blow connecting: melee or arrow.
     *
     * Five frames at the default `frameTime` of 0.1s is thirty ticks, which is the whole
     * animation exactly once. A flash that outlived its own frames would sit on the last one.
     */
    Hit(AssetId("effects/hit_effect"), lifeTicks = 30L, offsetY = 8f),

    /**
     * The priest's heal.
     *
     * `ability/heal_over_time` re-emits [MobaCues.HEAL] every period for its whole duration, so
     * this is respawned while the heal is ticking rather than living for the five seconds the old
     * `duration = 5.0F` asked for. One period is fifteen ticks and this lives twenty-four - the
     * four-frame animation once through - so a heal that is still running always has one on the
     * field and one that stopped fades within half a second.
     */
    Heal(AssetId("effects/heal_effect"), lifeTicks = 24L, offsetY = 0f),

    /**
     * The wizard's bolt. **Spawned by nothing.**
     *
     * `MobaUnits.kinds`' wizard grants `ability/npc_melee` and nothing else, so there is no cue
     * that means "a wizard cast". Declared because the sheet is packed and addressable and the
     * next wave needs a name for it; a reader should not have to grep to find out it is unwired.
     */
    Spell(AssetId("effects/spell_effect"), lifeTicks = 60L, offsetY = 8f),
    ;
}

/**
 * Spawns one short-lived [SpriteView] entity.
 *
 * The port of `blueprint/effect.udea.kts`, which was a `spriteRenderer(order = 10, offset =
 * Vector2(0, -0.1))` plus an `animations()` holder. Which animation, how long it lives and where
 * it sits are [SpawnOverrides] rather than fields on the blueprint, so one object serves all
 * three [EffectKind]s and a spawn allocates one closure rather than one blueprint.
 *
 * The [SpriteView] is added by the override and not here, because [SpriteView.animation] has no
 * default and cannot have one - see its KDoc for the hundred and seventy-six invisible entities
 * that argument is paid for with.
 */
public class EffectBlueprint : Blueprint {

    override val id: BlueprintId = BlueprintId("blueprint/effect")

    override fun configure(context: EntityCreateContext, entity: Entity) {
        with(context) { entity += Position() }
    }

    override fun toString(): String = "EffectBlueprint"
}

/**
 * Turns this tick's gameplay cues into flashes on the field.
 *
 * ## Why it reads the queue rather than being called by the thing that hit
 *
 * The old `PriestHealCue.onGameplayEffectApplied` spawned its entity from inside
 * `Abilities.applyGameplayEffect`, which is what made a headless server spawn heal effects and a
 * rollback re-simulation spawn them twice. [MobaCues]' own KDoc says why they are ids now. This
 * system is the consumer that KDoc says does not exist yet: it reads [GasCueQueue] **without
 * draining it**, so `GasCueForwardSystem` still forwards every cue to `GameContext.cues`
 * afterwards, and it inherits [dev.wildware.udea.gas.CueMode.Suppress] for free - a rewind's
 * re-simulation emits no cues, so it spawns no flashes.
 *
 * ## Ordering
 *
 * `SimPhase.Cleanup`, before `GasCueForwardSystem`. Cleanup because every phase that can emit a
 * cue has run by then - the swing in `Ability`, the arrow landing in `PostPhysics`, the death in
 * `Gameplay` - and before the forwarder because the forwarder is what empties the queue.
 *
 * ## What it deliberately ignores
 *
 * [MobaCues.DEATH]. A death is drawn by the corpse the dying unit leaves behind (see
 * `dev.wildware.moba.ability.DeathSystem`), and a flash on top of it would hide the one frame
 * this whole change exists to make visible.
 */
public class EffectSpawnSystem(
    private val cues: GasCueQueue,
    private val netIds: NetIdIndex,
    private val spawner: BlueprintSpawner,
    private val blueprint: EffectBlueprint,
) : SimSystem() {

    /** Flashes spawned since the process started. Zero over a running fight is a broken seam. */
    public var spawned: Long = 0L
        private set

    override fun onTick() {
        val now = tick.value
        var index = 0
        while (index < cues.size) {
            val event = cues.eventAt(index)
            val kind = kindOf(event.cueId)
            if (kind != null) spawn(kind, event.target, now)
            index++
        }
    }

    /** Which flash a cue means, or `null` for a cue that is not drawn. */
    private fun kindOf(cueId: Int): EffectKind? = when (cueId) {
        MobaCues.MELEE_HIT, MobaCues.ARROW_HIT -> EffectKind.Hit
        MobaCues.HEAL -> EffectKind.Heal
        else -> null
    }

    /**
     * Puts one [kind] on [target].
     *
     * Resolved through the [NetIdIndex] and this world's `Position` rather than through
     * `CombatWorld`: the combat index is rebuilt in `PreSimulation` and holds only units that
     * still carry a `Combatant`, so a unit that died earlier in *this* tick - the one a hit flash
     * most wants to be drawn on - is already out of it.
     */
    private fun spawn(kind: EffectKind, target: NetId, now: Long) {
        if (target.isNone) return
        val entity = netIds.resolveOrNull(target) ?: return
        val position = with(world) { entity.getOrNull(Position) } ?: return
        // One closure per flash, on a hit path rather than a per-tick one - the same trade
        // `CombatIndex.fireArrow` makes, and for the same reason: a blueprint object per spawn
        // allocates strictly more.
        val overrides = SpawnOverrides { context, spawned ->
            with(context) {
                spawned += SpriteView(
                    animation = kind.animation,
                    // `now + 1`, not `now`: a spawn is a barrier action, so this entity exists
                    // from the start of the *next* tick. Starting the playhead on the tick it was
                    // asked for would draw frame one of the animation on the first frame it is
                    // visible.
                    startTick = now + 1,
                    expiryTick = now + 1 + kind.lifeTicks,
                    offsetY = kind.offsetY,
                )
            }
        }
        spawner.spawn(blueprint, SpawnPosition(position.x, position.y), overrides)
        this.spawned++
    }
}

/**
 * Removes a [SpriteView] entity once its [SpriteView.expiryTick] has passed.
 *
 * The port of `example/.../system/EffectSystem.kt`, which compared `gameScreen.time` - a
 * wall-clock float on a global - against a `destroyTime` computed at construction from the same
 * global. Two consequences it had and this does not: an effect spawned during a paused world
 * expired the instant the world resumed, and a rewind could not take one back because neither
 * number was simulation state.
 *
 * The arrow is in this family too and is never touched by it: [SpriteView.FOREVER] is a tick no
 * clock reaches, and an arrow is despawned by `ProjectileSystem` on contact or on expiry.
 */
public class EffectExpirySystem : SimSystem() {

    private val views: Family = world.family { all(SpriteView) }

    private val netIds: NetIdIndex = ctx[dev.wildware.udea.core.module.CoreModule.NET_IDS]

    /** Flashes removed since the process started. */
    public var expired: Long = 0L
        private set

    override fun onTick() {
        val entities = views.entities
        val now = tick.value
        // Backwards, because a removal compacts the Fleks bag and walking forwards would skip the
        // entry that moved into the slot just vacated - the same reason `ProjectileSystem` does.
        var index = entities.size - 1
        while (index >= 0) {
            val entity: Entity = entities[index]
            if (with(world) { entity[SpriteView] }.expiryTick <= now) {
                netIds.free(netIds.netIdOf(entity))
                with(world) { entity.remove() }
                expired++
            }
            index--
        }
    }
}

/**
 * Draws every [SpriteView]: arrows in flight, and the flashes a hit or a heal leaves.
 *
 * ## Why a second render system rather than a wider family on the first
 *
 * [CharacterRenderSystem]'s family is `all(Position, CharacterView)` and its whole loop is
 * "resolve the roster entry, pick the animation for its state". An arrow has no state and is not
 * in the roster (see [SpriteView]'s KDoc). Widening that loop would put an `if` on the per-entity
 * path of the pass that draws twenty-seven units.
 *
 * ## What it costs, stated plainly
 *
 * It uploads the atlas pages **a second time**. `CharacterRenderSystem` owns its own upload and
 * the loader is private to it; sharing one page cache between the two passes is a change to
 * `MobaScene.kt`, which this file does not own. Today the bundle packs to a single 92KB page, so
 * the duplicate is one small texture; on a bundle with twenty pages it would be twenty, and the
 * fix is a shared `resources.own` cache rather than anything here.
 *
 * The playhead is the simulation tick, for the reason [CharacterAnimator] gives at length: two
 * captures of an identical, paused world must be the same PNG.
 */
internal class SpriteRenderSystem(
    private val resources: RenderResources,
    private val camera: CameraRig,
) : RenderSystem {

    private var world: World? = null

    private var views: Family? = null

    /** Read in [render] for the frame index. Never written. */
    private var clock: SimClock? = null

    /** The live graph, so an `assets.patch` of a scale is visible in the next capture. */
    private val registry = MobaAssets.registry

    /**
     * The frames of every sheet in the atlas, cut at pack time.
     *
     * All of them rather than only the ones this pass draws: the set is decided by
     * [EffectKind] and by what a blueprint writes into a [SpriteView], and a sheet resolved
     * lazily on first use would put a decode on the frame a unit first fires an arrow on.
     */
    private val framesBySheet: Map<AssetId, Array<TextureRegion>> = loadFrames(resources)

    /** The slot each sheet occupies, resolved once. The value behind it is read every frame. */
    private val sheetSlots: Map<AssetId, AssetIndex> =
        framesBySheet.keys.associateWith { registry.indexOf(it) }

    /** Sprites drawn by the most recent [render]. A health signal, not state. */
    internal var drawnCount: Int = 0
        private set

    /**
     * This frame's order, back to front.
     *
     * The same [WorldDrawOrder] the character pass uses and for the same reason: this family is
     * walked in spawn order, so which of two overlapping flashes won was decided by which cue
     * fired first rather than by which is nearer the viewer - and a rewound world, whose bag is
     * repopulated in a different order, drew the pair the other way round.
     */
    private val order = WorldDrawOrder()

    override fun onBind(world: World, ctx: GameContext) {
        this.world = world
        this.clock = ctx.clock
        views = world.family { all(Position, SpriteView) }
    }

    override fun render(target: OffscreenTarget, alpha: Float) {
        val world = this.world ?: return
        val views = this.views ?: return
        val clock = this.clock ?: return
        drawnCount = 0
        val now = clock.tick.value
        val tickRate = clock.tickRate
        val batch = resources.batch
        batch.projectionMatrix = camera.camera.combined
        batch.color = Color.WHITE
        batch.begin()
        try {
            with(world) {
                order.begin()
                val entities = views.entities
                var collected = 0
                while (collected < entities.size) {
                    val entity = entities[collected]
                    order.add(entity, DrawLayer.EFFECT, entity[Position].y)
                    collected++
                }
                order.sort()
                var index = 0
                while (index < order.size) {
                    val entity = order.entityAt(index)
                    index++
                    val view = entity[SpriteView]
                    val animation = registry.find(view.animation) as? SpriteAnimation
                        ?: continue
                    val frames = framesBySheet[animation.sheet.id] ?: continue
                    val at = CharacterAnimator.frameAt(
                        animation,
                        frames.size,
                        now - view.startTick,
                        tickRate,
                    )
                    val frame = frames[at]
                    val scale = (registry.at(sheetSlots.getValue(animation.sheet.id)) as SpriteSheet)
                        .scale
                    val width = frame.regionWidth * scale
                    val height = frame.regionHeight * scale
                    val position = entity[Position]
                    val degrees = if (view.facesMotion) headingOf(entity) else 0f
                    // The nine-argument `draw`, because the arrow has to point where it is going
                    // and the five-argument one cannot rotate. The origin is the frame's centre,
                    // so a rotation spins the sprite about the entity rather than swinging it
                    // around a corner.
                    batch.draw(
                        frame,
                        position.x - width / 2f,
                        position.y - height / 2f + view.offsetY,
                        width / 2f,
                        height / 2f,
                        width,
                        height,
                        1f,
                        1f,
                        degrees,
                    )
                    drawnCount++
                }
            }
        } finally {
            // In a `finally` because a `Batch` left begun poisons every later pass in the frame
            // with a failure that names the wrong system.
            batch.end()
            batch.color = Color.WHITE
        }
    }

    /** Which way [entity] is travelling, in degrees, or zero when it is not moving. */
    private fun headingOf(entity: Entity): Float {
        val motion = with(world ?: return 0f) { entity.getOrNull(Motion) } ?: return 0f
        if (motion.vx == 0f && motion.vy == 0f) return 0f
        return Math.toDegrees(kotlin.math.atan2(motion.vy.toDouble(), motion.vx.toDouble()))
            .toFloat()
    }

    private companion object {

        /**
         * Every atlas page uploaded, and every sheet's frames cut out of them.
         *
         * A copy of `CharacterRenderSystem`'s loader, which is `private` to it. Duplicated rather
         * than shared because making it `internal` and hoisting the page cache is an edit to
         * `MobaScene.kt`; see this class's KDoc for what the duplication costs.
         */
        fun loadFrames(resources: RenderResources): Map<AssetId, Array<TextureRegion>> {
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
                pixmap.dispose()
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
