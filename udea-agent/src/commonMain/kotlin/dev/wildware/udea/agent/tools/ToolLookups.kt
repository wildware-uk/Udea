package dev.wildware.udea.agent.tools

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentToolException
import dev.wildware.udea.agent.query.AgentComponentType
import dev.wildware.udea.agent.query.FieldValues
import dev.wildware.udea.core.blueprint.BlueprintSpawner
import dev.wildware.udea.core.blueprint.SpawnPosition
import dev.wildware.udea.core.blueprint.SpawnRequest
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex

/*
 * The lookups `world.*` and `editor.*` share: resolving an id, naming a field, reading typed text
 * into a slot and spawning from the catalogue. One copy, because the two toolsets are two doors
 * onto the same world and must refuse the same mistakes in the same words.
 */

/**
 * The entity behind [netId].
 *
 * @throws AgentToolException `no_such_entity` when the id is stale, free or never issued.
 */
internal fun NetIdIndex.requireLive(netId: NetId): Entity = resolveOrNull(netId) ?: throw AgentToolException(
    AgentErrorKind.NO_SUCH_ENTITY,
    "no live entity for NetId #${netId.index}@${netId.generation}; it has been destroyed, " +
        "or its slot has been recycled since the id was issued",
)

/**
 * The index of [field] on this component.
 *
 * @throws AgentToolException `no_such_field`, listing the fields the component does have.
 */
internal fun AgentComponentType.requireFieldIndex(field: String): Int {
    val fieldIndex = fieldIndexOf(field)
    if (fieldIndex < 0) {
        throw AgentToolException(
            AgentErrorKind.NO_SUCH_FIELD,
            "$name has no field $field; it has " + fieldNames.joinToString(),
        )
    }
    return fieldIndex
}

/**
 * [text] coerced to the type the slot already holds, as [FieldValues.parse] reads it.
 *
 * @throws AgentToolException `bad_argument` naming [tool], the field and what it holds.
 */
internal fun parseFieldText(
    tool: String,
    component: AgentComponentType,
    fieldIndex: Int,
    current: Any?,
    text: String,
): Any = FieldValues.parse(current, text) ?: throw AgentToolException(
    AgentErrorKind.BAD_ARGUMENT,
    "$tool got value=$text for ${component.name}.${component.fieldNames[fieldIndex]}, which holds " +
        FieldValues.typeNameOf(current),
)

/**
 * Creates the blueprint called [name] now, at [x],[y] when either is given, and returns its id.
 *
 * Either coordinate present means a placement was asked for, and the missing half is 0.
 *
 * @throws AgentToolException `no_spawner` when the game wired none, and `no_such_blueprint` with
 *   a did-you-mean when the catalogue has no such name.
 */
internal fun BlueprintCatalog.spawnNow(
    world: World,
    spawner: BlueprintSpawner?,
    name: String,
    x: Float?,
    y: Float?,
): NetId {
    if (spawner == null) {
        throw AgentToolException(
            WorldToolset.NO_SPAWNER,
            "this game has no BlueprintSpawner wired, so nothing can be spawned; a spawner " +
                "needs the game's SpawnPlacement, which names its spatial component",
        )
    }
    val found = find(name) ?: throw AgentToolException(
        WorldToolset.NO_SUCH_BLUEPRINT,
        "no blueprint named $name; ${didYouMean(name)}",
    )
    val position = if (x == null && y == null) null else SpawnPosition(x ?: 0f, y ?: 0f)
    return spawner.spawnNow(world, SpawnRequest(found, position))
}

private fun BlueprintCatalog.didYouMean(name: String): String {
    if (names.isEmpty()) return "this game's blueprint catalogue is empty"
    val nearest = names.minByOrNull { editDistance(name, it) }
    return "did you mean $nearest? (world.list_blueprints has all ${names.size})"
}

/**
 * Levenshtein distance, iterative and over one row.
 *
 * Blueprint names are short and a catalogue is tens of entries, so this runs once on a failed
 * spawn and never on a hot path.
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
