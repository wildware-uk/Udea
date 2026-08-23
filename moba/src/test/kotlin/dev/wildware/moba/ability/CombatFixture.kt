package dev.wildware.moba.ability

import com.github.quillraven.fleks.Entity
import dev.wildware.moba.Position
import dev.wildware.moba.PositionPlacement
import dev.wildware.udea.core.blueprint.Blueprint
import dev.wildware.udea.core.blueprint.BlueprintSpawner
import dev.wildware.udea.core.blueprint.SpawnPosition
import dev.wildware.udea.core.blueprint.blueprints
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.gas.ActivationResult
import dev.wildware.udea.gas.AttributeId
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.gas.GameplayEffects
import dev.wildware.udea.gas.GasCueForwardSystem
import dev.wildware.udea.gas.GasCueQueue

/**
 * A real game with nothing in it but combat, driven a tick at a time.
 *
 * A `GameHost` over a real `UdeaGameDef` and not a hand-assembled bag of objects: the phases, the
 * barrier, the spawner and the tick are the ones a shipped process runs, so a test that passes
 * here is evidence about the game rather than about the fixture. `RenderMode.Headless`, so it
 * needs no GL driver and runs on any machine.
 */
internal class CombatFixture(autopilot: Boolean = true) {

    val module: MobaAbilityModule = MobaAbilityModule(autopilot = autopilot)

    /** Cues seen on the GAS queue, oldest first. */
    val cues: MutableList<RecordedCue> = mutableListOf()

    private val definition: UdeaGameDef =
        UdeaGameDef(modules = listOf(module, CueRecorderModule(module.gas.cues, cues)))

    init {
        // Before `build()`, which `GameHost` calls: a spawner needs the barrier and the id index,
        // and both come off the definition's core module, which cannot exist before the module
        // list does. Exactly the two-step `MobaGame.definition()` does for the same reason.
        module.spawner = BlueprintSpawner(
            barrier = definition.core.barrier,
            netIds = definition.core.netIds,
            placement = PositionPlacement,
        )
    }

    val host: GameHost = GameHost(RenderMode.Headless, definition, null)

    val netIds: NetIdIndex = host.ctx[CoreModule.NET_IDS]

    /**
     * Queues a unit of [kind] at ([x], [y]) **in corpus units** and returns the id it will have.
     *
     * Scaled by [MobaScale.WORLD] on the way in, so a test still reads in the units the old game's
     * assets were authored in: `spawn("soldier", 0f, 0f)` and `spawn("orc", 3f, 0f)` are three
     * character-widths apart, which is the sentence `SoldierFireArrow`'s `range = 2.0F` was
     * written against. Every ability constant is scaled by the same factor at its declaration, so
     * a test that says "two units apart, inside the heal radius of three" stays true of the game
     * rather than only of the fixture.
     */
    fun spawn(kind: String, x: Float, y: Float): NetId {
        val blueprint: Blueprint = requireNotNull(module.unit(kind)) { "no unit kind '$kind'" }
        return host.ctx.blueprints.spawn(
            blueprint,
            SpawnPosition(x * MobaScale.WORLD, y * MobaScale.WORLD),
        )
    }

    /** Advances [ticks] ticks. Cues land in [cues] as they are emitted. */
    fun step(ticks: Int) {
        host.run(ticks)
    }

    /** How many [cueId] cues have been seen. */
    fun cueCount(cueId: Int): Int = cues.count { it.cueId == cueId }

    fun entityOf(id: NetId): Entity = requireNotNull(netIds.resolveOrNull(id)) { "$id is not live" }

    fun isLive(id: NetId): Boolean = netIds.resolveOrNull(id) != null

    fun attributesOf(id: NetId): Attributes = with(host.world) { entityOf(id)[Attributes] }

    fun effectsOf(id: NetId): GameplayEffects = with(host.world) { entityOf(id)[GameplayEffects] }

    fun positionOf(id: NetId): Position = with(host.world) { entityOf(id)[Position] }

    fun current(id: NetId, attribute: AttributeId): Float = attributesOf(id).current(attribute)

    /** [id]'s health right now. */
    fun health(id: NetId): Float = current(id, module.attributes.health)

    /** [id]'s mana right now. */
    fun mana(id: NetId): Float = current(id, module.attributes.mana)

    /** Sets [id]'s health, the way a wound would. */
    fun wound(id: NetId, to: Float) {
        val attributes = attributesOf(id)
        attributes.setBase(module.attributes.health, to)
        attributes.current[module.attributes.health.index] = to
    }

    /** Activates [slot] on [id] the way an input path or an AI would. */
    fun activate(id: NetId, slot: Int): ActivationResult = module.gas.activation.activate(
        id,
        abilitiesOf(id),
        attributesOf(id),
        effectsOf(id),
        slot,
        host.tick,
    )

    /** Whether [id] could activate [slot] right now, and if not, why. */
    fun canActivate(id: NetId, slot: Int): ActivationResult = module.gas.activation.canActivate(
        id,
        abilitiesOf(id),
        attributesOf(id),
        effectsOf(id),
        slot,
        host.tick,
    )

    /** Whether [id]'s [slot] has an activation in flight. */
    fun isActive(id: NetId, slot: Int): Boolean = abilitiesOf(id).instanceAt(slot).isActive

    fun abilitiesOf(id: NetId): Abilities = with(host.world) { entityOf(id)[Abilities] }

    /** How many arrows are in flight. */
    fun projectileCount(): Int = with(host.world) { host.world.family { all(Projectile) }.entities.size }
}

/**
 * One cue, copied out of the queue.
 *
 * A copy and not the `CueEvent` itself: the queue reuses its event objects in place, so holding
 * one past the tick that emitted it would report whatever the next cue overwrote it with.
 */
internal data class RecordedCue(
    val cueId: Int,
    val tick: Long,
    val source: NetId,
    val target: NetId,
    val payload0: Float,
    val payload1: Float,
) {
    override fun toString(): String =
        "${MobaCues.nameOf(cueId)}@$tick(source=$source, target=$target, $payload0, $payload1)"
}

/**
 * Copies every GAS cue into a list before anything drains the queue.
 *
 * A module and a system rather than a read after `host.run(1)`, because `GasCueForwardSystem`
 * empties the queue inside the same tick that filled it - so a test reading the queue afterwards
 * sees nothing and would conclude, wrongly, that no cue was emitted. Ordered with the registry's
 * own `before` constraint, which is the mechanism a real presentation layer would use.
 */
internal class CueRecorderModule(
    private val queue: GasCueQueue,
    private val into: MutableList<RecordedCue>,
) : UdeaModule {

    override val name: String get() = "cue-recorder"

    override fun simulation(registry: SimRegistry) {
        registry.add(
            SimPhase.Cleanup,
            { CueRecorderSystem(queue, into) },
            { before(GasCueForwardSystem::class) },
        )
    }
}

internal class CueRecorderSystem(
    private val queue: GasCueQueue,
    private val into: MutableList<RecordedCue>,
) : SimSystem() {

    override fun onTick() {
        var index = 0
        while (index < queue.size) {
            val event = queue.eventAt(index)
            into += RecordedCue(
                cueId = event.cueId,
                tick = event.tick.value,
                source = event.source,
                target = event.target,
                payload0 = event.payload0,
                payload1 = event.payload1,
            )
            index++
        }
    }
}
