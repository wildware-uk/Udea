package dev.wildware.udea.agent.tools

import com.github.quillraven.fleks.World
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.agent.AgentToolException
import dev.wildware.udea.agent.Json
import dev.wildware.udea.agent.query.AgentComponentIndex
import dev.wildware.udea.agent.query.AgentComponentType
import dev.wildware.udea.agent.query.EntityDetail
import dev.wildware.udea.agent.query.EntityQuery
import dev.wildware.udea.agent.query.EntityQueryEngine
import dev.wildware.udea.agent.query.EntityQueryParser
import dev.wildware.udea.agent.query.FieldValues
import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.blueprint.BlueprintSpawner
import dev.wildware.udea.core.blueprint.SpawnPosition
import dev.wildware.udea.core.blueprint.SpawnRequest
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.replication.MaskOps

/**
 * The toolset every Udea game gets for free: look at the world, and change it.
 *
 * ## Where the mutation happens, and why there is no second barrier hop
 *
 * Issue #70 asks for mutations to apply through `SimBarrier`, and they do - one hop, not two.
 * `AgentRuntime.beforeFrame` posts every drained command to the barrier as a `ToolCall`, so by
 * the time a function on this class runs the simulation is *already* at the top of a step, in
 * the drain, before any system. Submitting a second barrier action from in here would push the
 * mutation to the *following* tick and complete the command before it had happened, which is
 * the one thing an agent cannot recover from: the confirmation would be a lie.
 *
 * So the guarantee an agent observes is the barrier's own, unchanged: a mutation asked for
 * while tick 412 was running is seen by every system on tick 413 and by none on tick 412. A
 * spawn goes through [BlueprintSpawner.spawnNow] for the same reason - it is the immediate path
 * documented for a caller that is already inside the mutation it owns, and it is what lets
 * `spawn_blueprint` return a live [NetId] rather than a reservation the agent cannot query yet.
 *
 * ## Writability
 *
 * `agentWritable = false` is the default on `@Net` (spec 5), so most fields refuse a write.
 * There is one declaration and not three opinions: `set_component_field` reads exactly the flag
 * `describe_entity` publishes beside the value, which is exactly the flag the annotation set. A
 * refusal is typed [FIELD_NOT_WRITABLE] and names the tool to reach for instead, because
 * "permission denied" without an alternative costs an agent a round trip and a guess.
 *
 * ## Audit
 *
 * Every successful mutation writes one line into the event ring naming the tool, the entity and
 * the field. When somebody later asks why an entity is wrong, the ring is the only place that
 * can answer "the agent did it at tick 412".
 */
public class WorldToolset(
    private val world: World,
    private val components: AgentComponentIndex,
    private val netIds: NetIdIndex,
    private val bridge: AgentBridge,
    /** Stamps the audit entries. The tick the mutation actually landed on, not the last published one. */
    private val clock: SimClock,
    /** Where `spawn_blueprint` finds a blueprint. [BlueprintCatalog.EMPTY] for a game with none. */
    private val catalog: BlueprintCatalog = BlueprintCatalog.EMPTY,
    /**
     * The spawner, or `null` for a game that has not wired one.
     *
     * Null is a legitimate configuration - a spawner needs a `SpawnPlacement`, which names the
     * game's spatial component, and the kernel has none - so `spawn_blueprint` answers a typed
     * refusal rather than throwing a `NullPointerException` at whoever asked.
     */
    private val spawner: BlueprintSpawner? = null,
) {

    private val queries = EntityQueryEngine(components, netIds, world)

    private val detail = EntityDetail(components, netIds, world)

    /**
     * Mutations applied since construction. Read by tests and by `diag.frame_report`; the
     * per-mutation record an investigation reads is the event ring.
     */
    public var mutations: Long = 0L
        private set

    // --- reads ---------------------------------------------------------------------------

    private fun queryEntities(command: AgentCommand): AgentResult {
        val query = EntityQueryParser.parse(
            index = components,
            with = command.args["with"],
            where = command.args["where"],
            near = command.args["near"],
            fields = command.args["fields"],
            limit = command.int("limit", EntityQuery.DEFAULT_LIMIT),
            offset = command.int("offset", 0),
        )
        val json = Json()
        queries.run(query, json)
        return AgentResult.Ok(json.toString())
    }

    private fun describeEntity(command: AgentCommand): AgentResult =
        AgentResult.Ok(detail.render(netIdArg(command)))

    private fun getComponent(command: AgentCommand): AgentResult {
        val netId = netIdArg(command)
        val entity = requireEntity(netId)
        val component = components.requireByName(command.str("component"))
        if (!component.isPresent(world, entity)) {
            throw AgentToolException(
                AgentErrorKind.NO_SUCH_FIELD,
                "entity #${netId.index}@${netId.generation} does not carry ${component.name}; " +
                    "world.describe_entity lists the components it does carry",
            )
        }
        return AgentResult.ok {
            put("id", netId.raw)
            put("component", component.name)
            obj("fields") {
                component.fieldNames.forEachIndexed { fieldIndex, fieldName ->
                    key(fieldName)
                    FieldValues.renderInto(this, component.read(world, entity, fieldIndex))
                }
            }
        }
    }

    private fun listComponents(): AgentResult = AgentResult.ok {
        arr("components") {
            for (position in 0 until components.size) {
                val component = components.typeAt(position)
                element {
                    put("name", component.name)
                    arr("fields") {
                        component.fieldNames.forEachIndexed { fieldIndex, fieldName ->
                            element {
                                put("name", fieldName)
                                put("index", fieldIndex)
                                put("mask", maskOf(component, fieldIndex))
                                put("agentWritable", component.isAgentWritable(fieldIndex))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun listBlueprints(): AgentResult = AgentResult.ok {
        put("spawnable", spawner != null)
        arr("blueprints") { catalog.names.forEach { value(it) } }
    }

    // --- mutations -----------------------------------------------------------------------

    private fun setComponentField(command: AgentCommand): AgentResult {
        val netId = netIdArg(command)
        val entity = requireEntity(netId)
        val component = components.requireByName(command.str("component"))
        val fieldName = command.str("field")
        val fieldIndex = component.fieldIndexOf(fieldName)
        if (fieldIndex < 0) {
            throw AgentToolException(
                AgentErrorKind.NO_SUCH_FIELD,
                "${component.name} has no field $fieldName; it has " +
                    component.fieldNames.joinToString(),
            )
        }
        if (!component.isAgentWritable(fieldIndex)) {
            return AgentResult.failed(FIELD_NOT_WRITABLE, refusalFor(component, fieldName))
        }

        val current = component.read(world, entity, fieldIndex)
        val supplied = command.str("value")
        val parsed = FieldValues.parse(current, supplied)
            ?: throw AgentToolException(
                AgentErrorKind.BAD_ARGUMENT,
                "world.set_component_field got value=$supplied for ${component.name}.$fieldName, " +
                    "which holds ${FieldValues.typeNameOf(current)}",
            )
        component.write(world, entity, fieldIndex, parsed)
        audit("set_component_field", netId, "${component.name}.$fieldName=$supplied")

        return AgentResult.ok {
            put("id", netId.raw)
            put("component", component.name)
            put("field", fieldName)
            key("previous")
            FieldValues.renderInto(this, current)
            key("value")
            FieldValues.renderInto(this, component.read(world, entity, fieldIndex))
        }
    }

    private fun spawnBlueprint(command: AgentCommand): AgentResult {
        val name = command.str("blueprint")
        val spawner = this.spawner ?: return AgentResult.failed(
            NO_SPAWNER,
            "this game has no BlueprintSpawner wired, so nothing can be spawned; a spawner " +
                "needs the game's SpawnPlacement, which names its spatial component",
        )
        val blueprint = catalog.find(name) ?: return AgentResult.failed(
            NO_SUCH_BLUEPRINT,
            "no blueprint named $name; ${didYouMean(name)}",
        )
        val position = when {
            "x" in command || "y" in command -> SpawnPosition(command.float("x"), command.float("y"))
            else -> null
        }
        val netId = spawner.spawnNow(world, SpawnRequest(blueprint, position))
        audit("spawn_blueprint", netId, name)
        return AgentResult.ok {
            put("id", netId.raw)
            put("index", netId.index)
            put("generation", netId.generation)
            put("blueprint", name)
        }
    }

    private fun destroyEntity(command: AgentCommand): AgentResult {
        val netId = netIdArg(command)
        // A stale or recycled id is an answer, not a crash: `free` reports whether the id was
        // the one that owned the slot, so a destroy racing a respawn cannot take the newcomer.
        val entity = netIds.resolveOrNull(netId) ?: return AgentResult.failed(
            AgentErrorKind.NO_SUCH_ENTITY,
            "no live entity for NetId #${netId.index}@${netId.generation}; it has already been " +
                "destroyed, or its slot has been recycled since the id was issued",
        )
        netIds.free(netId)
        world -= entity
        audit("destroy_entity", netId, "-")
        return AgentResult.ok { put("id", netId.raw) }
    }

    // --- shared --------------------------------------------------------------------------

    private fun netIdArg(command: AgentCommand): NetId = NetId.ofRaw(command.int("id"))

    private fun requireEntity(netId: NetId) = netIds.resolveOrNull(netId) ?: throw AgentToolException(
        AgentErrorKind.NO_SUCH_ENTITY,
        "no live entity for NetId #${netId.index}@${netId.generation}; it has been destroyed, " +
            "or its slot has been recycled since the id was issued",
    )

    private fun maskOf(component: AgentComponentType, fieldIndex: Int): String = when {
        MaskOps.test(component.replicator.netMask, fieldIndex) -> "net"
        MaskOps.test(component.replicator.allMask, fieldIndex) -> "sim"
        else -> "unmasked"
    }

    /**
     * The refusal text, which names an alternative rather than only a rule.
     *
     * The alternative is keyed on the component, because that is the only thing a read-only
     * field's *reason* is knowable from: an `Attributes` write belongs to the GAS effect
     * pipeline, and going round it with a raw field poke produces a value the next effect
     * recalculation silently overwrites.
     */
    private fun refusalFor(component: AgentComponentType, fieldName: String): String {
        val alternative = ALTERNATIVES[component.name.lowercase()]
        val instead = if (alternative == null) {
            "nothing else writes it today, so change it in the game's own code or mark it " +
                "@Net(agentWritable = true) if an agent is meant to"
        } else {
            "use $alternative, not world.set_component_field on ${component.name}"
        }
        return "${component.name}.$fieldName is not agent-writable - agentWritable = false is " +
            "the default on @Net, so an agent write is opt-in per field; $instead"
    }

    private fun didYouMean(name: String): String {
        if (catalog.names.isEmpty()) return "this game's blueprint catalogue is empty"
        val nearest = catalog.names.minByOrNull { editDistance(name, it) }
        return "did you mean $nearest? (world.list_blueprints has all ${catalog.names.size})"
    }

    private fun audit(tool: String, netId: NetId, detail: String) {
        mutations++
        bridge.event(
            "agent_mutation:world.$tool:#${netId.index}@${netId.generation}:$detail",
            clock.tick.value,
        )
    }

    override fun toString(): String = "WorldToolset(${components.size} components, $mutations mutation(s))"

    public companion object {

        /** A write to a field whose `@Net(agentWritable)` says no. Names the tool to use instead. */
        public val FIELD_NOT_WRITABLE: AgentErrorKind = AgentErrorKind("field_not_writable")

        /** The named blueprint is not in this game's catalogue. Carries a did-you-mean. */
        public val NO_SUCH_BLUEPRINT: AgentErrorKind = AgentErrorKind("no_such_blueprint")

        /** This game wired no `BlueprintSpawner`, so nothing can be spawned at all. */
        public val NO_SPAWNER: AgentErrorKind = AgentErrorKind("no_spawner")

        /**
         * Components whose fields have a *named* owner an agent should go through instead.
         *
         * Keyed by lowercased component name, which is how [AgentComponentIndex] matches them.
         * The GAS entry is here rather than in `udea-gas` because the refusal has to be
         * answerable by a build that does not have that module: naming the tool costs nothing
         * and telling an agent "no" with no route forward costs it a round trip.
         */
        private val ALTERNATIVES: Map<String, String> = mapOf(
            "attributes" to "gas.apply_effect",
            "abilities" to "gas.grant_ability",
            "tags" to "gas.apply_effect",
        )

        /** The eight tools, ascending by name. Registered by [engineToolModule]. */
        public fun tools(): List<AgentToolDef<WorldToolset>> = listOf(
            EngineToolDef<WorldToolset>(
                name = "world.describe_entity",
                description = "Dump every component and field of one entity, with each field's " +
                    "replication mask and whether an agent may write it. Reach for it after " +
                    "world.query_entities has told you which entity to look at.",
                owner = WorldToolset::class,
                args = listOf(ID_ARG),
            ) { toolset, command -> toolset.describeEntity(command) },

            EngineToolDef<WorldToolset>(
                name = "world.destroy_entity",
                description = "Remove one entity from the world and release its NetId. Use it " +
                    "to isolate a behaviour by deleting what is interfering with it. A stale " +
                    "or already-destroyed id answers no_such_entity rather than failing.",
                owner = WorldToolset::class,
                args = listOf(ID_ARG),
            ) { toolset, command -> toolset.destroyEntity(command) },

            EngineToolDef<WorldToolset>(
                name = "world.get_component",
                description = "Read every field of one named component on one entity. Cheaper " +
                    "than world.describe_entity when you already know which component you are " +
                    "interested in and want to watch it change.",
                owner = WorldToolset::class,
                args = listOf(
                    ID_ARG,
                    agentArg("component", "string", "Component name, as world.list_components spells it."),
                ),
            ) { toolset, command -> toolset.getComponent(command) },

            EngineToolDef<WorldToolset>(
                name = "world.list_blueprints",
                description = "List every blueprint this game can spawn from. Read it before " +
                    "world.spawn_blueprint so a spawn is not a guess at a name.",
                owner = WorldToolset::class,
            ) { toolset, _ -> toolset.listBlueprints() },

            EngineToolDef<WorldToolset>(
                name = "world.list_components",
                description = "List every component type with its field names, indices, " +
                    "replication masks and writability. This is how you discover a game's " +
                    "schema without reading its source, and what makes a query writable by name.",
                owner = WorldToolset::class,
            ) { toolset, _ -> toolset.listComponents() },

            EngineToolDef<WorldToolset>(
                name = "world.query_entities",
                description = "Find entities by component, by field predicate and by proximity, " +
                    "returning only the fields you name. This is the tool to reach for instead " +
                    "of reading /state: entity detail is deliberately absent from the digest.",
                owner = WorldToolset::class,
                args = listOf(
                    agentArg(
                        "with", "string",
                        "Comma-separated component names an entity must carry, e.g. Champion,Health.",
                        required = false,
                    ),
                    agentArg(
                        "where", "string",
                        "Comma-separated field predicates, e.g. team=1,health.current<400. " +
                            "Comparisons are = != < <= > >=.",
                        required = false,
                    ),
                    agentArg(
                        "near", "string",
                        "Proximity filter as x,y,radius in world units.",
                        required = false,
                    ),
                    agentArg(
                        "fields", "string",
                        "Comma-separated fields to return; id and pos are special keys. " +
                            "Omit for ids only.",
                        required = false,
                    ),
                    agentArg(
                        "limit", "integer",
                        "Rows to return, at most ${EntityQuery.MAX_LIMIT}.",
                        required = false,
                        default = EntityQuery.DEFAULT_LIMIT.toString(),
                    ),
                    agentArg(
                        "offset", "integer",
                        "Matches to skip before the page, for reading past the first page.",
                        required = false,
                        default = "0",
                    ),
                ),
            ) { toolset, command -> toolset.queryEntities(command) },

            EngineToolDef<WorldToolset>(
                name = "world.set_component_field",
                description = "Write one field of one component on one entity, at the next tick " +
                    "boundary. Only fields declared @Net(agentWritable = true) accept a write; " +
                    "anything else answers field_not_writable and names the tool that owns it.",
                owner = WorldToolset::class,
                args = listOf(
                    ID_ARG,
                    agentArg("component", "string", "Component name, as world.list_components spells it."),
                    agentArg("field", "string", "Field name within that component."),
                    agentArg(
                        "value", "string",
                        "The new value as text; it is coerced to the field's declared type.",
                    ),
                ),
            ) { toolset, command -> toolset.setComponentField(command) },

            EngineToolDef<WorldToolset>(
                name = "world.spawn_blueprint",
                description = "Create one entity from a named blueprint, optionally at a given " +
                    "position, and return its NetId. Use it to build a scenario directly " +
                    "instead of playing the game up to it.",
                owner = WorldToolset::class,
                args = listOf(
                    agentArg("blueprint", "string", "Blueprint name, as world.list_blueprints spells it."),
                    agentArg(
                        "x", "number",
                        "World x to place it at. Omit x and y to let the blueprint place itself.",
                        required = false,
                    ),
                    agentArg("y", "number", "World y to place it at.", required = false),
                ),
            ) { toolset, command -> toolset.spawnBlueprint(command) },
        )

        private val ID_ARG = agentArg(
            "id", "integer",
            "The entity's packed NetId, generation included, exactly as a query returned it.",
        )

        /**
         * Levenshtein distance, iterative and over one row.
         *
         * Blueprint names are short and a catalogue is tens of entries, so this runs once on a
         * failed spawn and never on a hot path. It is here rather than borrowed because
         * `udea-assets`, which owns the build-time validator's did-you-mean, does not exist yet
         * - and when it does, this whole answer moves there under its rule id.
         */
        private fun editDistance(a: String, b: String): Int {
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                for (j in 1..b.length) {
                    val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
                }
                val swap = previous
                previous = current
                current = swap
            }
            return previous[b.length]
        }
    }
}
