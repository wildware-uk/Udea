package dev.wildware.udea.agent.query

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.agent.Json
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.identity.NetIdVisitor

/**
 * Runs an [EntityQuery] over the live world and renders the page it matched.
 *
 * ## Identity
 *
 * Results are keyed by [NetId] and never by a Fleks `Entity`. An `Entity` is a slot index into
 * one world in one process at one moment - the old engine shipped one across the wire and read
 * another machine's slot index as its own - so it is not something an agent may hold. Every id
 * in a result is the packed `NetId` word, **generation included**, and it resolves back through
 * [NetIdIndex.resolveOrNull], which answers `null` for a recycled slot rather than handing back
 * whatever now lives there. That is what stops a tool call twenty seconds later from mutating
 * the wrong entity, and it is asserted across a destroy and a respawn by `EntityIdentityTest`.
 *
 * ## Cost
 *
 * One walk of the live set in ascending id order ([NetIdIndex.forEachLive], an array scan
 * bounded by the high-water mark), with the field predicates resolved to indices *before* the
 * walk starts. Matching is `getField` per predicate per entity, which boxes - the frozen
 * contract allows that here precisely because it is once per agent call and not once per tick.
 * The matched page is collected as packed ids into a reused `IntArray`, so the render pass
 * touches only the entities that will actually be returned.
 *
 * ## One query at a time
 *
 * This class carries the walk's state in fields, which is what keeps the visitor
 * allocation-free. It belongs to the simulation thread and runs inside a tool call, so there is
 * one caller by construction; [run] is not re-entrant and says so.
 */
public class EntityQueryEngine(
    private val index: AgentComponentIndex,
    private val netIds: NetIdIndex,
    private val world: World,
) : NetIdVisitor {

    /** Packed `NetId` words of the current page. */
    private val page = IntArray(EntityQuery.MAX_LIMIT)

    private var active: EntityQuery = EMPTY_QUERY

    private var pageSize: Int = 0

    private var total: Int = 0

    private var running: Boolean = false

    /**
     * Runs [query] and writes `{"total":…, "returned":…, "truncated":…, "entities":[…]}` into
     * [out] as one JSON value.
     *
     * `total` counts every match, not just the page, so an agent can tell "three of these" from
     * "three so far" without paging through the rest.
     *
     * @throws IllegalStateException if called re-entrantly - from inside a projection, say -
     *   which would overwrite the page the outer call is still filling.
     */
    public fun run(query: EntityQuery, out: Json): QuerySummary {
        check(!running) { "EntityQueryEngine.run is not re-entrant; one query at a time" }
        running = true
        try {
            active = query
            pageSize = 0
            total = 0
            netIds.forEachLive(this)
            val summary = QuerySummary(total, pageSize, total > query.offset + pageSize)
            render(query, out, summary)
            return summary
        } finally {
            active = EMPTY_QUERY
            running = false
        }
    }

    /**
     * Runs [query] and hands each row of the matched page to [row], already rendered.
     *
     * ## Why a tool needs the rows and not the document
     *
     * [run] renders the whole page as one JSON value, and that is exactly what made
     * `world.query_entities` unreadable above about twenty rows: a command answer reaches the
     * agent only through the digest's `commandResults` array, which drops - rather than shortens
     * - any entry past [dev.wildware.udea.agent.state.DigestBudgets.RESULT_CEILING]. A 27-unit
     * "every unit's health" answer is some 900 characters, so the tool ran, matched, rendered
     * and delivered **nothing**. Paging it needs the rows one at a time, because a page is
     * decided by measuring rows rather than by guessing how many fit.
     *
     * The walk and the render both happen inside the re-entrancy guard, which is not an
     * implementation detail: a projection reaches a game's own `AgentComponentType.read`, and
     * that is the one place a re-entrant `run` can come from. Rendering outside the guard would
     * leave that call unrefused and let it overwrite the page being emitted.
     *
     * A row that resolves to nothing - an id freed between the walk and the render - is skipped
     * rather than handed over empty, so a caller never has to filter blanks out of its own page.
     *
     * Allocating one `String` per row, once per tool call on the simulation thread and never per
     * tick. That is the same trade [run] makes by building a document, in a shape a pager can
     * use.
     *
     * @return the unpaged [QuerySummary], so a caller can report how many matched as well as how
     *   many it was handed.
     */
    public fun forEachRow(query: EntityQuery, row: (String) -> Unit): QuerySummary {
        check(!running) { "EntityQueryEngine.run is not re-entrant; one query at a time" }
        running = true
        try {
            active = query
            pageSize = 0
            total = 0
            netIds.forEachLive(this)
            val summary = QuerySummary(total, pageSize, total > query.offset + pageSize)
            val scratch = Json()
            var position = 0
            while (position < pageSize) {
                scratch.reset()
                renderEntity(query, scratch, page[position])
                if (scratch.length > 0) row(scratch.toString())
                position++
            }
            return summary
        } finally {
            active = EMPTY_QUERY
            running = false
        }
    }

    /** Renders the same document [run] does, into a fresh string. For tools and tests. */
    public fun render(query: EntityQuery): String {
        val json = Json()
        run(query, json)
        return json.toString()
    }

    /** The entity [netId] names, or `null` if the id is stale, freed, or was never live. */
    public fun resolve(netId: NetId): Entity? = netIds.resolveOrNull(netId)

    override fun visit(netId: NetId, entity: Entity) {
        if (!matches(entity)) return
        // Counted before the page is considered, so `total` is the unpaged answer.
        val rank = total
        total++
        if (rank < active.offset) return
        if (pageSize >= active.limit) return
        page[pageSize] = netId.raw
        pageSize++
    }

    private fun matches(entity: Entity): Boolean {
        val required = active.with
        for (position in required.indices) {
            if (!required[position].isPresent(world, entity)) return false
        }

        val predicates = active.where
        for (position in predicates.indices) {
            val predicate = predicates[position]
            val value = predicate.field.component.read(world, entity, predicate.field.fieldIndex)
            if (!predicate.matches(value)) return false
        }

        val near = active.near ?: return true
        val position = index.requirePosition()
        val x = position.component.read(world, entity, position.xIndex) as? Float ?: return false
        val y = position.component.read(world, entity, position.yIndex) as? Float ?: return false
        return near.contains(x, y)
    }

    private fun render(query: EntityQuery, out: Json, summary: QuerySummary) {
        out.beginObject()
        out.put("total", summary.total)
        out.put("returned", summary.returned)
        out.put("truncated", summary.truncated)
        out.key("entities")
        out.beginArray()
        var position = 0
        while (position < pageSize) {
            renderEntity(query, out, page[position])
            position++
        }
        out.endArray()
        out.endObject()
    }

    private fun renderEntity(query: EntityQuery, out: Json, raw: Int) {
        val netId = NetId.ofRaw(raw)
        val entity = netIds.resolveOrNull(netId) ?: return
        out.beginObject()
        // Unconditional, and first: a projection that omitted the id would produce rows the
        // agent has no way to act on.
        out.put("id", raw)
        val projections = query.fields
        for (position in projections.indices) {
            when (val projection = projections[position]) {
                Projection.Id -> Unit
                Projection.Position -> renderPosition(out, entity)
                is Projection.Field -> {
                    out.key(projection.key)
                    FieldValues.renderInto(
                        out,
                        projection.ref.component.read(world, entity, projection.ref.fieldIndex),
                    )
                }
            }
        }
        out.endObject()
    }

    private fun renderPosition(out: Json, entity: Entity) {
        val position = index.requirePosition()
        out.key(Projection.Position.key)
        out.beginArray()
        out.value(position.component.read(world, entity, position.xIndex) as? Float ?: Float.NaN)
        out.value(position.component.read(world, entity, position.yIndex) as? Float ?: Float.NaN)
        out.endArray()
    }

    private companion object {
        /** Parked in [active] between runs so the field never has to be nullable. */
        val EMPTY_QUERY: EntityQuery = EntityQuery()
    }
}

/** How many entities a query matched, and how many of them were returned. */
public class QuerySummary(
    /** Every match, whatever the page. */
    public val total: Int,
    /** Rows in this page. */
    public val returned: Int,
    /** Whether matches were left unreturned - explicitly, rather than left for the agent to infer. */
    public val truncated: Boolean,
) {
    override fun toString(): String = "QuerySummary(total=$total, returned=$returned, truncated=$truncated)"
}
