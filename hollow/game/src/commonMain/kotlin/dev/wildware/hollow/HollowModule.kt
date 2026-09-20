package dev.wildware.hollow

import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.ServiceKey
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.module.after
import dev.wildware.udea.core.serviceKey
import dev.wildware.udea.render.input.IntentSampleSystem
import dev.wildware.udea.render.input.IntentState

/**
 * Hollow's own module: the launch level, and the four systems that make a character a character.
 *
 * In H1 (issue #249) it published the level and contributed no system, because the clearing was
 * static. Issue #250 adds the player, and its four systems are one per question, in tick order:
 *
 * | Phase | System | Answers |
 * |---|---|---|
 * | `Intent` | [PlayerControlSystem] | what did the player ask for |
 * | `PreSimulation` | [ClearingBodySystem] | what can a character not walk through |
 * | `Movement` | [PlayerMovementSystem] | how fast, and in which direction |
 * | `PostPhysics` | [PlayerPoseSystem] | which way is it facing, and which clip is playing |
 *
 * The solver itself is between the last two, contributed by `Physics2DModule`, which is why the
 * character stops at a rock rather than walking through it.
 *
 * The creatures and their systems register here in later tickets of epic #245.
 */
internal class HollowModule(
    private val level: LaunchLevel,
    /**
     * Whether this world decides anything. False on a client, whose world is a replicated view, and
     * which therefore registers none of the four systems - see `HollowGame`'s "What a client does
     * not run".
     */
    private val authoritative: Boolean = true,
) : UdeaModule {

    override val name: String get() = HollowGame.NAME

    override fun context(builder: GameContextBuilder) {
        builder.service(LaunchLevel.KEY, level)
    }

    override fun simulation(registry: SimRegistry) {
        if (!authoritative) return
        registry.add(SimPhase.Intent, { ctx -> PlayerControlSystem(ctx[IntentState.KEY]) }) {
            // Declared rather than left to registration order: the axis a character moves on must
            // be the one sampled on this tick, not the previous one.
            after<IntentSampleSystem>()
        }
        registry.add(SimPhase.PreSimulation, { ClearingBodySystem() })
        registry.add(SimPhase.Movement, { PlayerMovementSystem() })
        registry.add(SimPhase.PostPhysics, { PlayerPoseSystem() })
    }

    override fun toString(): String =
        "HollowModule($level, ${if (authoritative) "authoritative" else "replicated view"})"
}

/**
 * The launch level's bytes, published on the context so [HollowGame.seed] loads the level the
 * definition's scene was registered with, rather than being handed it a second time.
 */
internal class LaunchLevel(
    /** The `.udealevel` file's contents. Never written to. */
    val bytes: ByteArray,
) {
    override fun toString(): String = "LaunchLevel(${bytes.size} bytes)"

    companion object {
        val KEY: ServiceKey<LaunchLevel> = serviceKey("hollow.level")
    }
}
