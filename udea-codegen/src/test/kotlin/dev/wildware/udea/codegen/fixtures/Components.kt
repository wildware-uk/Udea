package dev.wildware.udea.codegen.fixtures

import dev.wildware.udea.annotations.AgentState
import dev.wildware.udea.annotations.Net
import dev.wildware.udea.annotations.Q
import dev.wildware.udea.annotations.Replicated
import dev.wildware.udea.annotations.Sim
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.fixtures.Vec2
import dev.wildware.udea.core.identity.NetId

/**
 * The components the processor is applied to.
 *
 * They are ordinary annotated classes in this module's **test** source set, and `kspTest` runs
 * `UdeaSymbolProcessor` over them, so the tests next door exercise real generated files rather
 * than a string the emitter happened to produce in memory.
 *
 * Between them they cover every field type the generator supports — `Int`, `Long`, `Float`,
 * `Boolean`, an enum, `NetId`, `Tick`, a lowered composite and a `@Q`-quantised float — a
 * component with both masks, and (in [AiBlackboard]) a `@Sim`-only component whose `netMask`
 * must come out empty.
 *
 * Property declaration order is deliberately **not** alphabetical: bit indices come from the
 * name ordering in `FieldOrder`, and a fixture that was already sorted would not prove it.
 */
@Replicated
public class Health(
    /** `@Net` — replicated and snapshotted. */
    @Net public var maximum: Float = 100f,
    /**
     * `@Net` **and** `@AgentState`: replicated on the wire *and* published as a digest scalar.
     *
     * The two annotations mean two unrelated things and neither knows about the other. This
     * property owns field index 1 of `HealthReplicator` — a `fieldNames` entry, a `FieldMask`
     * bit and a `FieldStore` slot, all the same index, as the frozen contract requires — and
     * separately owns the `game` block key `health`. `AgentStateIsolationTest` pins that the
     * digest key changes nothing about the replicator.
     */
    @Net @AgentState(name = "health") public var current: Float = 100f,
    /** `@Net` — replicated and snapshotted. */
    @Net public var invulnerable: Boolean = false,
    /** `@Sim` — snapshotted only; the client is never told when the server last hit us. */
    @Sim public var lastDamageTick: Long = 0L,
) {
    /**
     * `@AgentState` **only**, and the load-bearing half of the fixture.
     *
     * It is published to the agent and is not replicated, not snapshotted and not restored by
     * a rewind, so it must take **no** index in `HealthReplicator` at all: no `fieldNames`
     * entry, no mask bit, no store slot. A generator that let `@AgentState` into the field
     * space would put a name in `fieldNames` with no bit and no slot behind it, and
     * `desync_report` — which walks set bits and indexes `fieldNames` with them — would report
     * the wrong field's name for every divergence past this index.
     */
    @AgentState(name = "deaths")
    public var deaths: Int = 0
}

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

/**
 * The composite, quantised and value-typed fields — every widening this module gained after
 * the first three components froze the primitive path.
 *
 * [position] is the case the frozen contract calls out by name: a composite value type is
 * **lowered** to one field per component, so this class has four fields and three annotated
 * properties, and `fieldNames` reads `position.x`, `position.y`, `rotation`, `settledAt`.
 * The property is a `val` on purpose — `apply` restores a vector by writing through it, which
 * keeps the identity that rendering and physics hold references to, so requiring `var` here
 * would reject the very shape the contract specifies.
 *
 * [rotation] carries `@Q`, so it costs 12 bits on the wire instead of 32 and the three
 * constants are folded into the generated `write`/`read` as literals. The snapshot still
 * holds the full-precision float: quantisation is a wire concern and must never degrade a
 * rewind.
 */
@Replicated
public class Placement(
    /** `@Net` — a composite, lowered to `position.x` and `position.y`. */
    @Net public val position: Vec2 = Vec2(),
    /** `@Net @Q` — a full turn in 12 bits, to about 0.0015 radians. */
    @Net @Q(bits = 12, min = -3.1416f, max = 3.1416f) public var rotation: Float = 0f,
    /** `@Sim` — a `Tick`, which is a field type and not a `Long` in disguise. */
    @Sim public var settledAt: Tick = Tick.ZERO,
)

/**
 * Entity references and a normalised float.
 *
 * [target] is spec 5's "treat `NetId` as a primitive field type": it goes through the store's
 * own `setNetId`/`getNetId` rather than being smuggled through `setInt`, and it comes back off
 * the wire through `NetId.ofRaw`, which rejects a word whose reserved bits are set instead of
 * conjuring an id that means something else in a future layout.
 */
@Replicated
public class Combat(
    /** `@Net` — who we are attacking. */
    @Net public var target: NetId = NetId.NONE,
    /** `@Net` — an unquantised float, so a `NetId` field sits beside an ordinary one. */
    @Net public var chargeFraction: Float = 0f,
    /** `@Sim` — who hit us last. Rewinds; never reaches a client. */
    @Sim public var lastAttacker: NetId = NetId.NONE,
)

/**
 * One `@Q` field at each of the widths the acceptance criteria name.
 *
 * The four ranges are deliberately different: quantisation error is
 * `(max - min) / (2^bits - 1) / 2`, so a test that used one range for every width would pass
 * for a generator that ignored `bits` and picked its own. Each field's epsilon is stated in
 * the generated KDoc and asserted in `GeneratedReplicatorQuantisationTest`.
 */
@Replicated
public class QuantisedProbe(
    /** 8 bits over a 0..1 fraction: step 1/255. */
    @Net @Q(bits = 8, min = 0f, max = 1f) public var fraction: Float = 0f,
    /** 12 bits over a full turn in radians. */
    @Net @Q(bits = 12, min = -3.1416f, max = 3.1416f) public var angle: Float = 0f,
    /** 14 bits over a 0..5000 hit-point pool. */
    @Net @Q(bits = 14, min = 0f, max = 5000f) public var pool: Float = 0f,
    /** 16 bits over a 2048-unit world axis: the position case, at 3.1 cm. */
    @Net @Q(bits = 16, min = -1024f, max = 1024f) public var axis: Float = 0f,
)
