package dev.wildware.udea.agent.query

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentToolException
import dev.wildware.udea.agent.Json
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.replication.MaskOps

/**
 * Everything about one entity: every component, every field, its value, its mask and whether
 * an agent may write it.
 *
 * Tier 2 of the tiered state design. With entity detail out of `/state` entirely, this is where
 * an agent goes once a query has told it *which* entity to look at, and it is the one place the
 * cost of a full dump is paid - once, for one entity, because somebody asked.
 *
 * ## Why the mask and the writability are in the output
 *
 * `@Net` and `@Sim` are not decoration: a `@Sim` field rewinds but never reaches a client, so
 * an agent debugging a desync needs to know which half it is looking at. And
 * `agentWritable = false` is the default (spec 5), so **most fields cannot be written**. An
 * agent that discovers that by trying wastes a round trip and a confusing error; one that reads
 * `"agentWritable":false` beside the value knows before it asks. That is the difference between
 * a surface a model can plan against and one it has to probe.
 */
public class EntityDetail(
    private val index: AgentComponentIndex,
    private val netIds: NetIdIndex,
    private val world: World,
) {

    /**
     * Renders [netId] into [out] as one JSON value.
     *
     * @throws AgentToolException `no_such_entity` when the id is stale, freed, or never named
     *   anything. The generation counter is what makes that answer possible: a recycled slot is
     *   *detected* here rather than quietly described as though it were the entity the agent
     *   was asking about.
     */
    public fun render(netId: NetId, out: Json) {
        val entity = netIds.resolveOrNull(netId) ?: throw AgentToolException(
            AgentErrorKind.NO_SUCH_ENTITY,
            "no live entity for NetId #${netId.index}@${netId.generation}; it has been " +
                "destroyed, or its slot has been recycled since the id was issued",
        )

        out.beginObject()
        out.put("id", netId.raw)
        out.put("index", netId.index)
        out.put("generation", netId.generation)
        out.key("components")
        out.beginArray()
        for (position in 0 until index.size) {
            val component = index.typeAt(position)
            if (component.isPresent(world, entity)) renderComponent(component, entity, out)
        }
        out.endArray()
        out.endObject()
    }

    /** Renders [netId] to a fresh string. For tools and tests. */
    public fun render(netId: NetId): String {
        val json = Json()
        render(netId, json)
        return json.toString()
    }

    private fun renderComponent(component: AgentComponentType, entity: Entity, out: Json) {
        out.beginObject()
        out.put("name", component.name)
        out.key("fields")
        out.beginArray()
        val names = component.fieldNames
        for (fieldIndex in names.indices) {
            out.beginObject()
            out.put("name", names[fieldIndex])
            out.key("value")
            FieldValues.renderInto(out, component.read(world, entity, fieldIndex))
            out.put("mask", maskOf(component, fieldIndex))
            out.put("agentWritable", component.isAgentWritable(fieldIndex))
            out.endObject()
        }
        out.endArray()
        out.endObject()
    }

    /**
     * `net` for a replicated-and-snapshotted field, `sim` for a snapshotted-only one.
     *
     * `unmasked` is unreachable through a generated replicator - every field is in `allMask` by
     * construction - and is rendered rather than asserted because this is a diagnostic surface:
     * a hand-written or mis-generated replicator should be *visible* here, not throw at the one
     * moment somebody is trying to look at it.
     */
    private fun maskOf(component: AgentComponentType, fieldIndex: Int): String = when {
        MaskOps.test(component.replicator.netMask, fieldIndex) -> "net"
        MaskOps.test(component.replicator.allMask, fieldIndex) -> "sim"
        else -> "unmasked"
    }
}
