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
 * Hollow's own module: the launch level, and the systems that make a character a character and a
 * fox a fox.
 *
 * In H1 (issue #249) it published the level and contributed no system, because the clearing was
 * static. Issue #250 added the player and issue #251 the foxes, one system per question, in tick
 * order:
 *
 * | Phase | System | Answers |
 * |---|---|---|
 * | `Intent` | [PlayerControlSystem] | what did the player ask for |
 * | `PreSimulation` | [ClearingBodySystem] | what can a character not walk through |
 * | `PreSimulation` | [FoxWaveSystem] | does a wave of foxes arrive this tick, and where |
 * | `Movement` | [PlayerMovementSystem] | how fast, and in which direction |
 * | `Movement` | [FoxBrainSystem] | is each fox wandering, chasing or fleeing, and where to |
 * | `PostPhysics` | [PlayerPoseSystem] | which way is the character facing, and which clip is playing |
 * | `PostPhysics` | [FoxPoseSystem] | the same, for each fox |
 *
 * The solver itself is between `Movement` and `PostPhysics`, contributed by `Physics2DModule`,
 * which is why a character stops at a rock rather than walking through it, and a fox at a tree.
 */
internal class HollowModule(
    private val level: LaunchLevel,
    /**
     * Whether this world decides anything. False on a client, whose world is a replicated view, and
     * which therefore registers none of the systems above - see `HollowGame`'s "What a client does
     * not run".
     */
    private val authoritative: Boolean = true,
    /** The fox waves this world sends (issue #251), or null for none: a test about a player. */
    private val waves: FoxWaves? = FoxWaves.DEFAULT,
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
        // Issue #251: the foxes. The wave arrives before anything decides, the mind decides
        // beside the players' movement, and the pose is read off what the solver did.
        if (waves != null) registry.add(SimPhase.PreSimulation, { FoxWaveSystem(waves) })
        registry.add(SimPhase.Movement, { FoxBrainSystem() })
        registry.add(SimPhase.PostPhysics, { FoxPoseSystem() })
    }

    override fun toString(): String =
        "HollowModule($level, ${if (authoritative) "authoritative" else "replicated view"}, waves=$waves)"
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
