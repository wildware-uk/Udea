package dev.wildware.udea.codegen.fixtures

import dev.wildware.udea.annotations.AgentState
import dev.wildware.udea.annotations.AgentTool
import dev.wildware.udea.annotations.Arg
import dev.wildware.udea.core.identity.NetId

/**
 * The `@AgentTool` functions the processor is applied to.
 *
 * Ordinary annotated functions in this module's **test** source set, processed by `kspTest`
 * like the components next door, so the tests exercise real generated dispatchers compiled
 * under `-Werror` rather than a string an emitter returned in memory.
 *
 * Between them the parameters cover every supported shape once: a required `String`, an
 * optional `Int` with a folded default, an optional nullable `Float`, an enum, a `NetId`, a
 * `List<String>` and a `Boolean` with a default — which is also every branch of the coercion
 * emitter and every JSON Schema type the bridge understands.
 */
public class Playground {

    /** Everything spawned so far, so a test can assert the dispatcher really called through. */
    public val spawned: MutableList<String> = mutableListOf()

    @AgentTool(
        description = "Create entities from a blueprint at the world origin. Use it to set up " +
            "a scenario before stepping the simulation; it is the only way to add entities " +
            "without loading a scene.",
    )
    public fun spawnBlueprint(
        @Arg(description = "Blueprint id, as declared in the asset graph, e.g. 'creep_melee'.")
        blueprint: String,
        @Arg(description = "How many to create, 1..64.", required = false, default = "1")
        count: Int = 1,
        @Arg(
            description = "Uniform scale applied to each spawned entity; omit to leave it alone.",
            required = false,
        )
        scale: Float? = null,
    ): Int {
        repeat(count) { spawned += blueprint }
        return count * (scale?.toInt() ?: 1)
    }

    @AgentTool(
        name = "set_stance",
        description = "Change one entity's movement stance. Reach for this when a test needs a " +
            "unit crouched or sprinting without driving it through the input system.",
    )
    public fun setStance(
        @Arg(description = "The entity to change, as its NetId packed word.")
        target: NetId,
        @Arg(description = "The stance to apply.")
        stance: Stance,
        @Arg(description = "Whether to keep the stance across a rewind.", required = false, default = "true")
        sticky: Boolean = true,
    ): String = "${target.raw}:${stance.name}:$sticky"

    @AgentTool(
        description = "Attach labels to an entity so later queries can select it by name. " +
            "Useful for marking the entity a scenario is about before stepping many ticks.",
    )
    public fun tagEntity(
        @Arg(description = "The entity to label, as its NetId packed word.")
        target: NetId,
        @Arg(description = "Labels to attach; each must be lower_snake_case.")
        labels: List<String>,
    ): Int = target.index + labels.size
}

/**
 * The `@AgentState` scalars the processor is applied to.
 *
 * [MatchClock] carries one property of every allowed scalar type plus an enum. There is no
 * `Double` among them on purpose: `GameStateSink` publishes floats and rounds them to four
 * decimal places, so a `Double` would need a `.toFloat()` nobody wrote. The other half
 * of the claim - that `@AgentState` is a *separate* channel from replication - is made on
 * `Health` in `Components.kt`, which carries both annotations on one class.
 */
public class MatchClock {
    @AgentState
    public var elapsedTicks: Int = 0

    @AgentState(name = "elapsed_ms")
    public var elapsedMillis: Long = 0L

    @AgentState
    public var timeScale: Float = 1f

    @AgentState
    public var meanFrameMillis: Float = 0f

    @AgentState
    public var paused: Boolean = false

    @AgentState(name = "match_name")
    public var name: String = "unnamed"

    @AgentState
    public var phase: MatchPhase = MatchPhase.Warmup
}

/** Match lifecycle, published into the digest by constant name rather than by ordinal. */
public enum class MatchPhase { Warmup, Running, Finished }
