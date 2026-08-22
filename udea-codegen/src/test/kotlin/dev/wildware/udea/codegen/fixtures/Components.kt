package dev.wildware.udea.codegen.fixtures

import dev.wildware.udea.annotations.Net
import dev.wildware.udea.annotations.Replicated
import dev.wildware.udea.annotations.Sim

/**
 * The components the processor is applied to.
 *
 * They are ordinary annotated classes in this module's **test** source set, and `kspTest` runs
 * `UdeaSymbolProcessor` over them, so the tests next door exercise real generated files rather
 * than a string the emitter happened to produce in memory.
 *
 * Between them they cover every field type this issue supports — `Int`, `Long`, `Float`,
 * `Boolean` and an enum — a component with both masks, and (in [AiBlackboard]) a `@Sim`-only
 * component whose `netMask` must come out empty.
 *
 * Property declaration order is deliberately **not** alphabetical: bit indices come from the
 * name ordering in `FieldOrder`, and a fixture that was already sorted would not prove it.
 */
@Replicated
public class Health(
    /** `@Net` — replicated and snapshotted. */
    @Net public var maximum: Float = 100f,
    /** `@Net` — replicated and snapshotted. */
    @Net public var current: Float = 100f,
    /** `@Net` — replicated and snapshotted. */
    @Net public var invulnerable: Boolean = false,
    /** `@Sim` — snapshotted only; the client is never told when the server last hit us. */
    @Sim public var lastDamageTick: Long = 0L,
)

/** How an entity is moving. Stored and sent as its ordinal. */
public enum class Stance { Standing, Crouching, Sprinting }

@Replicated
public class Movement(
    /** `@Net` — an enum field, replicated as its ordinal. */
    @Net public var stance: Stance = Stance.Standing,
    /** `@Net` */
    @Net public var speed: Float = 0f,
    /** `@Net` */
    @Net public var jumpsRemaining: Int = 2,
    /** `@Sim` — rewinds, never reaches a client. */
    @Sim public var groundedTicks: Long = 0L,
)

/**
 * A `@Sim`-only component: bot state that must rewind and must never reach a client.
 *
 * Its `netMask` is empty, which is the case `MaskOps.of()` with no arguments would get wrong,
 * and the reason the emitter special-cases it to `MaskOps.EMPTY`.
 */
@Replicated
public class AiBlackboard(
    /** `@Sim` */
    @Sim public var patrolIndex: Int = 0,
    /** `@Sim` */
    @Sim public var aggression: Float = 0f,
    /** `@Sim` */
    @Sim public var alerted: Boolean = false,
    /** `@Sim` */
    @Sim public var lastSeenTick: Long = 0L,
)
