package dev.wildware.udea.agent.activity

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentToolArg

/**
 * What a tool call was *about*, in world terms: an entity, a point, or nothing.
 *
 * The overlay turns this into a marker - a ring that follows the entity a call inspected, a pin
 * where a spawn landed (spec 3.7). It is deliberately a tiny closed set of primitives rather
 * than a rich type: [AgentActivityRing] stores one per entry in parallel arrays, and a marker
 * that cost an allocation per tool call would allocate on the simulation thread.
 */
public enum class AnchorKind {
    /** The call named nothing an overlay can point at. */
    NONE,

    /** The call named an entity by packed [dev.wildware.udea.core.identity.NetId]. */
    ENTITY,

    /** The call named a world position. */
    POINT,
}

/**
 * Derives an [AnchorKind] and its payload from a tool's **declared** arguments.
 *
 * ## Why declared arguments and not a table of tool names
 *
 * The obvious implementation is `when (toolName) { "world.describe_entity" -> ... }`, and it is
 * wrong for a reason that gets worse over time rather than better: the overlay would silently
 * stop marking every tool added after it was written, and nothing would fail. Half the tool
 * surface is *generated* from `@AgentTool` in a game module this class has never heard of, so a
 * per-tool switch here could not even in principle be complete.
 *
 * What every tool does have is [AgentToolDef.args] - the published schema, the same text the
 * model reasons over. So the rule is stated once, over declarations:
 *
 * - a tool declaring an **integer** argument with an identity name ([IDENTITY_ARGS]) anchors to
 *   the entity that argument names;
 * - otherwise a tool declaring **number** arguments `x` and `y` anchors to that point;
 * - otherwise it anchors to nothing.
 *
 * A new tool that takes an entity id is marked the day it is written, with no edit here.
 *
 * ## What is a judgement call, and is admitted as one
 *
 * [IDENTITY_ARGS] is a name table. It has to be: `id` and `netId` are both in the tree today
 * (`world.describe_entity` declares `id`, `render.follow_entity` declares `netId`) and nothing
 * in an `AgentToolArg` distinguishes "an integer that is an entity" from "an integer that is a
 * limit". Widening it is one line. Getting it wrong draws a marker on the wrong entity or none
 * at all, which is a cosmetic defect on a surface the agent cannot see, and never a wrong
 * answer to a tool call.
 */
public object AgentAnchors {

    /**
     * Declared argument names that mean "an entity's packed NetId", when the type is `integer`.
     *
     * `id` is what the engine's own world tools call it; `netId` is what the host's render
     * tools call it. Both spellings are in the frozen manifests, so both are here.
     */
    public val IDENTITY_ARGS: Set<String> = setOf("id", "netid", "entityid", "entity")

    /** The declared `integer` type name, as `AgentToolArg.type` spells it. */
    private const val INTEGER: String = "integer"

    /** The declared `number` type name. */
    private const val NUMBER: String = "number"

    /**
     * The name of the entity-identity argument [args] declares, or `null`.
     *
     * Resolved once per tool - at index build, not per call - because it depends only on the
     * declaration. The first match in declaration order wins; a tool declaring two identity
     * arguments is anchored to whichever it published first, which is at least stable.
     */
    public fun identityArg(args: List<AgentToolArg>): String? =
        args.firstOrNull { it.type == INTEGER && it.name.lowercase() in IDENTITY_ARGS }?.name

    /** Whether [args] declares both `x` and `y` as numbers. */
    public fun declaresPoint(args: List<AgentToolArg>): Boolean =
        args.any { it.name == "x" && it.type == NUMBER } &&
            args.any { it.name == "y" && it.type == NUMBER }
}

/**
 * A tool's anchoring rule, resolved once from its declaration.
 *
 * Held per tool name by [AgentActivityRing]'s caller (the dispatcher's index), so recording a
 * call is a map lookup and two `String.toXOrNull` calls rather than a walk over the argument
 * list on every invocation.
 */
public class AnchorRule private constructor(
    private val identityArg: String?,
    private val point: Boolean,
) {

    /**
     * The kind this rule produces for [command], and its payload, written into [out].
     *
     * @param out a three-slot scratch array the caller owns: `[0]` the packed NetId as a float
     *   bit pattern is *not* what goes here - `[0]` is unused for a point and carries the netId
     *   for an entity, `[1]` and `[2]` carry x and y. Taking the array rather than returning an
     *   object is what keeps recording allocation-free.
     * @return the resolved kind. [AnchorKind.NONE] when the declared argument was not actually
     *   supplied, or did not parse: an optional `x`/`y` that a caller omitted is a spawn that
     *   let the blueprint place itself, and pinning it at the origin would be a lie.
     */
    public fun resolve(command: AgentCommand, out: IntArray, coords: FloatArray): AnchorKind {
        require(out.isNotEmpty()) { "out needs one slot for the packed NetId" }
        require(coords.size >= 2) { "coords needs two slots" }
        if (identityArg != null) {
            val raw = command.args[identityArg]?.toIntOrNull()
            if (raw != null) {
                out[0] = raw
                return AnchorKind.ENTITY
            }
        }
        if (point) {
            val x = command.args["x"]?.toFloatOrNull()
            val y = command.args["y"]?.toFloatOrNull()
            if (x != null && y != null && x.isFinite() && y.isFinite()) {
                coords[0] = x
                coords[1] = y
                return AnchorKind.POINT
            }
        }
        return AnchorKind.NONE
    }

    override fun toString(): String =
        "AnchorRule(identity=$identityArg, point=$point)"

    public companion object {

        /** A rule that anchors nothing. What an unknown tool name resolves to. */
        public val NONE: AnchorRule = AnchorRule(identityArg = null, point = false)

        /** The rule [args] implies. Returns [NONE] rather than null so a caller needs no branch. */
        public fun of(args: List<AgentToolArg>): AnchorRule {
            val identity = AgentAnchors.identityArg(args)
            val point = AgentAnchors.declaresPoint(args)
            return if (identity == null && !point) NONE else AnchorRule(identity, point)
        }
    }
}
