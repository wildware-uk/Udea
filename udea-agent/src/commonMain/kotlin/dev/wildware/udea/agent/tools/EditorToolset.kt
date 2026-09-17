package dev.wildware.udea.agent.tools

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentToolException
import dev.wildware.udea.agent.Json
import dev.wildware.udea.agent.activity.AgentSessionId
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.agent.dispatch.AgentContext
import dev.wildware.udea.agent.query.AgentComponentIndex
import dev.wildware.udea.agent.query.AgentComponentType
import dev.wildware.udea.agent.query.FieldValues
import dev.wildware.udea.agent.query.PositionRef
import dev.wildware.udea.annotations.AgentTool
import dev.wildware.udea.annotations.Arg
import dev.wildware.udea.core.SimClock
import dev.wildware.udea.core.blueprint.BlueprintSpawner
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.level.LevelSaveException
import dev.wildware.udea.core.level.LevelService
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Where `editor.save` writes: the game's [LevelService] and the directory level files go in.
 *
 * One value, because the two are only meaningful together - a directory with nothing to encode,
 * or an encoder with nowhere to put the bytes, is a save that cannot happen.
 *
 * The directory is a kotlinx-io [Path], written through [SystemFileSystem], so the same save runs
 * on the JVM, on Android and on Wasm under Node. A browser has no file system to name a directory
 * in, so a host there builds its [EditorToolset] with no store and `editor.save` answers
 * `no_level_store` rather than failing inside kotlinx-io.
 */
public class EditorLevelStore(
    /** Encodes the world. `UdeaGame.levels`. */
    internal val levels: LevelService,
    /** Every save lands here as `<name>.udealevel`, and nowhere else. */
    internal val directory: Path,
) {
    override fun toString(): String = "EditorLevelStore($directory)"
}

/**
 * `editor.*`: the level editor's actions, as agent tools (issue #193).
 *
 * ## The editor is a screen over this surface
 *
 * Every editor action is a tool here. The editor window calls them in-process and an agent calls
 * the same functions over HTTP, so there is one implementation of "move this unit" and it is the
 * one both are tested through. Like every tool, a call runs inside a `SimBarrier` drain at the
 * top of a tick - which is why [spawn] uses `spawnNow` and [save] uses `LevelService.saveNow`
 * rather than queueing a second barrier action that would land a tick after the answer.
 *
 * ## `agentWritable` does not apply here, and why that is safe
 *
 * `world.set_component_field` refuses any field not declared `@Net(agentWritable = true)`, so an
 * agent cannot change a champion's health mid-match. Authoring a level is a different job: a
 * person and an agent must both be able to set any field. This toolset writes every field the
 * component index names. That is safe only because a host registers it when it was started as an
 * editor and never in a normal game run - `world.*` keeps its rule unchanged either way.
 *
 * ## One undo history per author
 *
 * The author of a call is the [AgentSessionId] its command carried: the `?session=` an agent sends
 * with it, or, if it sent none, its remote address, both interned by the host into the same
 * [AgentSessions] passed here. An in-process caller is [AgentSessionId.LOCAL]. Each author's
 * history keeps its newest [HISTORY_CAPACITY] edits. Saving does not clear it.
 *
 * Every edit answers with the value it left behind and its `reverse`: the call that puts things
 * back, arguments as text, ready to send. A drag is one edit - the window calls [move] once, on
 * release, with where the drag began.
 *
 * ## An undo refuses rather than clobbers
 *
 * Before undoing a field edit, every field it wrote must still hold what the edit left. If one
 * does not, the undo is refused with [EDIT_CONFLICT] naming whoever changed it - the author of the
 * latest later edit to that field, or "not an editor tool" when no author's history explains it -
 * and `overwrite=true` undoes it anyway. An undone spawn refuses the same way when another author
 * has since edited the spawned entity.
 */
public class EditorToolset(
    private val world: World,
    private val components: AgentComponentIndex,
    private val netIds: NetIdIndex,
    /** The host's session table, so a refusal can name an author. The same one `AgentHost` interns into. */
    private val sessions: AgentSessions,
    private val bridge: AgentBridge,
    /** Stamps the audit entries. */
    private val clock: SimClock,
    /**
     * The two fields [move] writes. The component index's lowered `position.x`/`position.y` by
     * default; a game whose position is spelled otherwise names its own. `null` refuses a move.
     */
    private val position: PositionRef? = components.position,
    /** Where [spawn] finds a blueprint. */
    private val catalog: BlueprintCatalog = BlueprintCatalog.EMPTY,
    /** The spawner, or `null` for a game that has not wired one; [spawn] then refuses. */
    private val spawner: BlueprintSpawner? = null,
    /** Where [save] writes, or `null` when this host has none; [save] then refuses. */
    private val levels: EditorLevelStore? = null,
) {

    private val history = EditorHistory(sessions.capacity, HISTORY_CAPACITY, ::release)

    // --- edits ---------------------------------------------------------------------------

    @AgentTool(
        name = "editor.set_field",
        description = "Set any field of any component on one entity while authoring a level, " +
            "ignoring agentWritable. Answers the value left behind and the reverse call. " +
            "Recorded in your own undo history; send session=<your name> to keep it yours.",
    )
    public fun setField(
        context: AgentContext,
        @Arg(description = WorldToolset.ID_DESCRIPTION)
        id: NetId,
        @Arg(description = "Component name, as world.list_components spells it.")
        component: String,
        @Arg(description = "Field name within that component.")
        field: String,
        @Arg(description = "The new value as text; it is coerced to the field's declared type.")
        value: String,
    ): AgentResult {
        val entity = netIds.requireLive(id)
        val type = components.requireByName(component)
        val fieldIndex = type.requireFieldIndex(field)
        requirePresent(type, entity, id)
        val before = type.read(world, entity, fieldIndex)
        type.write(world, entity, fieldIndex, parseFieldText(SET_FIELD, type, fieldIndex, before, value))
        val change = FieldChange(type, fieldIndex, before, type.read(world, entity, fieldIndex))
        record(EditorEdit.Fields(history.nextSequence(), context.command.session, SET_FIELD, id, listOf(change)))

        return AgentResult.ok {
            put("id", id.raw)
            put("component", type.name)
            put("field", change.fieldName)
            key("value")
            FieldValues.renderInto(this, change.after)
            reverse(SET_FIELD) {
                put("id", id.raw.toString())
                put("component", type.name)
                put("field", change.fieldName)
                put("value", FieldValues.textOf(before))
            }
        }
    }

    @AgentTool(
        name = "editor.move",
        description = "Move one entity to a world position while authoring a level. A drag is " +
            "one call, made on release: pass fromX and fromY with where the drag began so the " +
            "reverse and undo return there. Answers the new position and the reverse call.",
    )
    public fun move(
        context: AgentContext,
        @Arg(description = WorldToolset.ID_DESCRIPTION)
        id: NetId,
        @Arg(description = "World x to move it to.")
        x: Float,
        @Arg(description = "World y to move it to.")
        y: Float,
        @Arg(
            description = "Where a drag began, x. Give fromX and fromY together, or neither to " +
                "use where the entity is now.",
            required = false,
        )
        fromX: Float?,
        @Arg(description = "Where a drag began, y.", required = false)
        fromY: Float?,
    ): AgentResult {
        if ((fromX == null) != (fromY == null)) {
            throw AgentToolException(
                AgentErrorKind.BAD_ARGUMENT,
                "$MOVE got only one of fromX and fromY; a drag begins at a point, so send both or neither",
            )
        }
        val position = position ?: throw AgentToolException(
            NO_POSITION,
            "this editor was given no position fields, so it cannot move anything; the host " +
                "names them when it builds the editor toolset",
        )
        val entity = netIds.requireLive(id)
        requirePresent(position.component, entity, id)
        val changes = listOf(
            moveField(entity, position, position.xIndex, x, fromX),
            moveField(entity, position, position.yIndex, y, fromY),
        )
        record(EditorEdit.Fields(history.nextSequence(), context.command.session, MOVE, id, changes))

        return AgentResult.ok {
            put("id", id.raw)
            obj("value") {
                field("x", changes[0].after)
                field("y", changes[1].after)
            }
            reverse(MOVE) {
                put("id", id.raw.toString())
                put("x", FieldValues.textOf(changes[0].before))
                put("y", FieldValues.textOf(changes[1].before))
            }
        }
    }

    @AgentTool(
        name = "editor.spawn",
        description = "Create one entity from a named blueprint while authoring a level, " +
            "optionally at a position. Answers its NetId and the reverse call, editor.delete. " +
            "Undoing it is refused while another author's later edit to that entity stands.",
    )
    public fun spawn(
        context: AgentContext,
        @Arg(description = "Blueprint name, as world.list_blueprints spells it.")
        blueprint: String,
        @Arg(
            description = "World x to place it at. Omit x and y to let the blueprint place itself.",
            required = false,
        )
        x: Float?,
        @Arg(description = "World y to place it at.", required = false)
        y: Float?,
    ): AgentResult {
        val netId = catalog.spawnNow(world, spawner, blueprint, x, y)
        record(EditorEdit.Spawn(history.nextSequence(), context.command.session, netId))
        return AgentResult.ok {
            put("id", netId.raw)
            put("blueprint", blueprint)
            put("value", netId.raw)
            reverse(DELETE) { put("id", netId.raw.toString()) }
        }
    }

    @AgentTool(
        name = "editor.delete",
        description = "Remove one entity while authoring a level, keeping its components so " +
            "editor.undo brings it back under the very same NetId. Its reverse is editor.undo: " +
            "a deleted entity's components cannot be sent back as arguments.",
    )
    public fun delete(
        context: AgentContext,
        @Arg(description = WorldToolset.ID_DESCRIPTION)
        id: NetId,
    ): AgentResult {
        val entity = netIds.requireLive(id)
        val removed = world.snapshotOf(entity)
        // Detached rather than freed: the id stays held, unresolvable, so an undo can put the
        // entity back behind it. `release` frees it for good once no history can undo this.
        netIds.detach(id)
        world -= entity
        record(EditorEdit.Delete(history.nextSequence(), context.command.session, id, removed))
        return AgentResult.ok {
            put("id", id.raw)
            put("value", null as String?)
            reverse(UNDO) {}
        }
    }

    // --- history -------------------------------------------------------------------------

    @AgentTool(
        name = "editor.undo",
        description = "Undo your newest editor edit. Refused, naming the author, if another " +
            "author has since changed what it wrote; call again with overwrite=true to undo " +
            "anyway. Only your own history is undone, so send the same session= every call.",
    )
    public fun undo(
        context: AgentContext,
        @Arg(
            description = "Undo even though someone else changed it since, putting back what " +
                "your edit replaced.",
            required = false,
            default = "false",
        )
        overwrite: Boolean,
    ): AgentResult {
        val author = context.command.session
        val edit = history.newest(author) ?: return AgentResult.failed(
            NOTHING_TO_UNDO,
            "${label(author)} has nothing to undo; editor.history lists what an author can undo, " +
                "and each author undoes only their own edits",
        )
        return when (edit) {
            is EditorEdit.Fields -> undoFields(edit, overwrite)
            is EditorEdit.Spawn -> undoSpawn(edit, overwrite)
            is EditorEdit.Delete -> undoDelete(edit, overwrite)
        }
    }

    @AgentTool(
        name = "editor.history",
        description = "List the edits editor.undo would undo for you, newest first, with the " +
            "tool and entity of each. Every author has their own history of up to a thousand " +
            "edits; saving a level does not clear it.",
    )
    public fun history(
        context: AgentContext,
        @Arg(description = "How many edits to list, newest first.", required = false, default = "20")
        limit: Int,
    ): AgentResult {
        val author = context.command.session
        return AgentResult.ok {
            put("author", label(author))
            put("size", history.size(author))
            arr("edits") {
                for (edit in history.newestFirst(author, limit.coerceAtLeast(0))) {
                    element {
                        put("sequence", edit.sequence)
                        put("tool", edit.tool)
                        put("id", edit.netId.raw)
                    }
                }
            }
        }
    }

    // --- saving --------------------------------------------------------------------------

    @AgentTool(
        name = "editor.save",
        description = "Save the whole world as a level file named <name>.udealevel in this " +
            "editor's level directory, answering the path and size. Saving never clears any " +
            "author's undo history, so edits made before a save can still be undone.",
    )
    public fun save(
        @Arg(description = "Level name: letters, digits, '-' and '_', no path.")
        name: String,
    ): AgentResult {
        val store = levels ?: return AgentResult.failed(
            NO_LEVEL_STORE,
            "this editor was started with no level directory, so there is nowhere to save to",
        )
        if (!LEVEL_NAME.matches(name)) {
            return AgentResult.failed(
                BAD_LEVEL_NAME,
                "'$name' is not a level name; use letters, digits, '-' and '_' only, so a save " +
                    "cannot land outside ${store.directory}",
            )
        }
        val bytes = try {
            store.levels.saveNow()
        } catch (refused: LevelSaveException) {
            return AgentResult.failed(LEVEL_NOT_SAVEABLE, refused.message ?: "the world cannot be saved as a level")
        }
        SystemFileSystem.createDirectories(store.directory)
        val file = Path(store.directory, "$name.${LevelService.FILE_EXTENSION}")
        SystemFileSystem.sink(file).buffered().use { it.write(bytes) }
        return AgentResult.ok {
            put("path", SystemFileSystem.resolve(file).toString())
            put("bytes", bytes.size)
        }
    }

    // --- undo ----------------------------------------------------------------------------

    private fun undoFields(edit: EditorEdit.Fields, overwrite: Boolean): AgentResult {
        val entity = netIds.resolveOrNull(edit.netId)
        if (entity == null || edit.changes.any { !it.component.isPresent(world, entity) }) {
            return gone(edit, overwrite)
        }
        val conflict = edit.changes.firstOrNull { it.component.read(world, entity, it.fieldIndex) != it.after }
        if (conflict != null && !overwrite) {
            val current = conflict.component.read(world, entity, conflict.fieldIndex)
            val changer = history.latestByOthers(edit) { it.touches(edit.netId, conflict.component, conflict.fieldIndex) }
            return AgentResult.failed(
                EDIT_CONFLICT,
                "refused to undo your ${edit.tool} on ${describe(edit.netId)}: " +
                    "${conflict.component.name}.${conflict.fieldName} now holds " +
                    "${FieldValues.textOf(current)}, not the ${FieldValues.textOf(conflict.after)} " +
                    "your edit left, because ${changedBy(changer)}. Call editor.undo with " +
                    "overwrite=true to put back ${FieldValues.textOf(conflict.before)} anyway.",
            )
        }
        for (change in edit.changes) change.component.write(world, entity, change.fieldIndex, change.before)
        history.pop(edit.author)
        audit(edit, "undo")
        return AgentResult.ok {
            put("undone", edit.tool)
            put("id", edit.netId.raw)
            put("overwrote", conflict != null)
            obj("value") { for (change in edit.changes) field(change.fieldName, change.before) }
        }
    }

    private fun undoSpawn(edit: EditorEdit.Spawn, overwrite: Boolean): AgentResult {
        val entity = netIds.resolveOrNull(edit.netId) ?: return gone(edit, overwrite)
        val later = history.latestByOthers(edit) { it.netId == edit.netId }
        if (later != null && !overwrite) {
            return AgentResult.failed(
                EDIT_CONFLICT,
                "refused to undo your ${edit.tool} of ${describe(edit.netId)}: ${changedBy(later)}, " +
                    "and undoing the spawn would delete that work. Call editor.undo with " +
                    "overwrite=true to delete it anyway.",
            )
        }
        netIds.free(edit.netId)
        world -= entity
        history.pop(edit.author)
        audit(edit, "undo")
        return AgentResult.ok {
            put("undone", edit.tool)
            put("id", edit.netId.raw)
            put("overwrote", later != null)
        }
    }

    private fun undoDelete(edit: EditorEdit.Delete, overwrite: Boolean): AgentResult {
        if (!netIds.isOutstandingReservation(edit.netId)) {
            // A rewind or a level load replaced the world, and with it the reservation that held
            // this id: there is no longer an empty id to bring the entity back behind.
            return gone(edit, overwrite)
        }
        val entity = world.entity { }
        world.loadSnapshotOf(entity, edit.removed)
        netIds.attach(entity, edit.netId)
        history.pop(edit.author)
        audit(edit, "undo")
        return AgentResult.ok {
            put("undone", edit.tool)
            put("id", edit.netId.raw)
        }
    }

    /**
     * The answer for an edit whose entity is not there to undo it on.
     *
     * Refused by default, because the author should know their undo did nothing. With
     * `overwrite=true` the edit is discarded from the history instead - otherwise an author whose
     * newest edit named a vanished entity could never undo anything older.
     */
    private fun gone(edit: EditorEdit, overwrite: Boolean): AgentResult {
        if (!overwrite) {
            return AgentResult.failed(
                ENTITY_GONE,
                "cannot undo your ${edit.tool}: ${describe(edit.netId)} is no longer in the world " +
                    "as it was. Call editor.undo with overwrite=true to discard this edit from " +
                    "your history without changing anything.",
            )
        }
        release(history.pop(edit.author))
        return AgentResult.ok {
            put("discarded", edit.tool)
            put("id", edit.netId.raw)
        }
    }

    // --- shared --------------------------------------------------------------------------

    private fun moveField(entity: Entity, position: PositionRef, fieldIndex: Int, to: Float, from: Float?): FieldChange {
        val type = position.component
        val current = type.read(world, entity, fieldIndex)
        val before = if (from == null) current else parseFieldText(MOVE, type, fieldIndex, current, from.toString())
        type.write(world, entity, fieldIndex, parseFieldText(MOVE, type, fieldIndex, current, to.toString()))
        return FieldChange(type, fieldIndex, before, type.read(world, entity, fieldIndex))
    }

    private fun requirePresent(type: AgentComponentType, entity: Entity, id: NetId) {
        if (!type.isPresent(world, entity)) {
            throw AgentToolException(
                AgentErrorKind.NO_SUCH_FIELD,
                "${describe(id)} does not carry ${type.name}; world.describe_entity lists the " +
                    "components it does carry",
            )
        }
    }

    private fun record(edit: EditorEdit) {
        history.push(edit)
        audit(edit, "edit")
    }

    /** Frees what a dropped or discarded edit held: a deleted entity's id, which nothing can undo now. */
    private fun release(edit: EditorEdit) {
        if (edit is EditorEdit.Delete && netIds.isOutstandingReservation(edit.netId)) netIds.free(edit.netId)
    }

    private fun changedBy(changer: EditorEdit?): String = if (changer == null) {
        "something other than an editor tool changed it since - the running simulation, or a world.* call"
    } else {
        "${label(changer.author)} changed it since with ${changer.tool}"
    }

    private fun label(author: AgentSessionId): String = sessions.label(author)

    private fun describe(id: NetId): String = "entity #${id.index}@${id.generation}"

    private fun audit(edit: EditorEdit, what: String) {
        bridge.event(
            "editor_$what:${edit.tool}:#${edit.netId.index}@${edit.netId.generation}:${label(edit.author)}",
            clock.tick.value,
        )
    }

    override fun toString(): String = "EditorToolset(${components.size} components)"

    internal companion object {

        /** Edits each author's history keeps. The oldest is dropped past this. */
        internal const val HISTORY_CAPACITY: Int = 1000

        /** An undo refused because another author, or the game, changed what the edit wrote. */
        internal val EDIT_CONFLICT: AgentErrorKind = AgentErrorKind("edit_conflict")

        /** An undo with an empty history. */
        internal val NOTHING_TO_UNDO: AgentErrorKind = AgentErrorKind("nothing_to_undo")

        /** An undo whose entity is no longer there to undo it on. */
        internal val ENTITY_GONE: AgentErrorKind = AgentErrorKind("entity_gone")

        /** A move in an editor given no position fields. */
        internal val NO_POSITION: AgentErrorKind = AgentErrorKind("no_position")

        /** A save in an editor given no level directory. */
        internal val NO_LEVEL_STORE: AgentErrorKind = AgentErrorKind("no_level_store")

        /** A save name that is not a bare level name. */
        internal val BAD_LEVEL_NAME: AgentErrorKind = AgentErrorKind("bad_level_name")

        /** A save refused because the world holds something a level cannot carry. Names it. */
        internal val LEVEL_NOT_SAVEABLE: AgentErrorKind = AgentErrorKind("level_not_saveable")

        private const val SET_FIELD = "editor.set_field"
        private const val MOVE = "editor.move"
        private const val DELETE = "editor.delete"
        private const val UNDO = "editor.undo"

        private val LEVEL_NAME = Regex("[A-Za-z0-9_-]{1,64}")

        private inline fun Json.reverse(tool: String, args: Json.() -> Unit) {
            obj("reverse") {
                put("tool", tool)
                obj("args", args)
            }
        }

        private fun Json.field(name: String, value: Any?) {
            key(name)
            FieldValues.renderInto(this, value)
        }
    }
}
