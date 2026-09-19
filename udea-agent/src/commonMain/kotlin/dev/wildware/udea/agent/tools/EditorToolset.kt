package dev.wildware.udea.agent.tools

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentClock
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentSubmission
import dev.wildware.udea.agent.AgentToolException
import dev.wildware.udea.agent.Json
import dev.wildware.udea.agent.activity.AgentSessionId
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.agent.dispatch.AgentContext
import dev.wildware.udea.agent.query.AgentComponentIndex
import dev.wildware.udea.agent.query.AgentComponentType
import dev.wildware.udea.agent.query.FieldRef
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
 * `editor.*`: the level editor's actions, as agent tools (issues #193 and #232).
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
 * back, arguments as text, ready to send.
 *
 * ## Edit sessions: a drag is live, and still one undo entry
 *
 * [beginEdit] records what some fields hold on some entities; [updateEdit] writes new values as
 * often as a drag moves, with no undo entry; [commitEdit] closes the session as **one** undo entry
 * whose reverse is the starting values, and [cancelEdit] puts the starting values back and records
 * nothing. An author has at most one session open, and beginning another commits the first.
 * Another author may write a session's field meanwhile - the last write wins - and the committed
 * entry still records the session's starting values, so an undo is refused over that write just
 * as it is over any other.
 *
 * A session nobody updates for [IDLE_TIMEOUT_SECONDS] is cancelled, and so is one whose author
 * calls [leave]. HTTP gives the host no connection to watch close, so an agent that goes away
 * without leaving is an idle one. The idle time is measured on [idleClock] - the agent host's wall
 * clock, as `AgentClock`'s KDoc sets out - and never on the simulation's: the sweep runs at the
 * start of each `AgentBridge.drain`, between ticks, and ends a session by submitting the same
 * `editor.cancel_edit` its author could have sent. So the wall clock decides only *when* a cancel
 * is asked for, and the cancel itself is an ordinary call, applied between ticks and kept in the
 * [journal] like any other.
 *
 * ## The journal: what a replay of an editing session needs
 *
 * Every call that changed the world or an edit session is kept in [journal] with the tick it was
 * applied on, so a replay can make the same calls before the same ticks. See [EditorJournal].
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
    /** Stamps the audit entries and the journal. */
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
    /** What an edit session's idle time is measured on. The platform clock unless a test moves its own. */
    private val idleClock: AgentClock = AgentClock.System,
) {

    private val history = EditorHistory(sessions.capacity, HISTORY_CAPACITY, ::release)

    private val openEdits = EditSessionTable(sessions.capacity)

    private val selections = Selections(sessions.capacity)

    /** Every call that changed the world or an edit session, in order. */
    public val journal: EditorJournal = EditorJournal()

    init {
        bridge.beforeEachDrain(::sweepIdle)
    }

    // --- edits ---------------------------------------------------------------------------

    @AgentTool(
        name = "editor.set_field",
        description = "Set any field of any component on one entity, or on several at once, while " +
            "authoring a level, ignoring agentWritable. Several entities are one write and one " +
            "undo entry. Answers the value left behind and the reverse call. Recorded in your own " +
            "undo history; send session=<your name> to keep it yours.",
    )
    public fun setField(
        context: AgentContext,
        @Arg(description = "The entity, or several comma separated, as NetId packed words (world.query_entities reports them).")
        id: List<NetId>,
        @Arg(description = "Component name, as world.list_components spells it.")
        component: String,
        @Arg(description = "Field name within that component.")
        field: String,
        @Arg(description = "The new value as text; it is coerced to the field's declared type.")
        value: String,
    ): AgentResult = journaled(context) {
        requireDistinct(SET_FIELD, "id", id)
        val type = components.requireByName(component)
        val fieldIndex = type.requireFieldIndex(field)
        val entities = id.map { netId -> netIds.requireLive(netId).also { requirePresent(type, it, netId) } }
        // Every value is coerced before any is written, so a refusal leaves no entity half-set.
        val parsed = entities.map { parseFieldText(SET_FIELD, type, fieldIndex, type.read(world, it, fieldIndex), value) }
        val changes = entities.mapIndexed { index, entity ->
            val before = type.read(world, entity, fieldIndex)
            type.write(world, entity, fieldIndex, parsed[index])
            FieldChange(id[index], type, fieldIndex, before, type.read(world, entity, fieldIndex))
        }
        record(EditorEdit.Fields(history.nextSequence(), context.command.session, SET_FIELD, changes))

        val befores = changes.map { FieldValues.textOf(it.before) }.distinct()
        AgentResult.ok {
            put("id", id[0].raw)
            if (id.size > 1) ids("ids", id)
            put("component", type.name)
            put("field", changes[0].fieldName)
            key("value")
            FieldValues.renderInto(this, changes[0].after)
            if (befores.size == 1) {
                reverse(SET_FIELD) {
                    put("id", id.joinToString(",") { it.raw.toString() })
                    put("component", type.name)
                    put("field", changes[0].fieldName)
                    put("value", befores[0])
                }
            } else {
                // No one set_field puts back several different values.
                reverse(UNDO) {}
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
    ): AgentResult = journaled(context) {
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
            moveField(id, entity, position, position.xIndex, x, fromX),
            moveField(id, entity, position, position.yIndex, y, fromY),
        )
        record(EditorEdit.Fields(history.nextSequence(), context.command.session, MOVE, changes))

        AgentResult.ok {
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
    ): AgentResult = journaled(context) {
        val netId = catalog.spawnNow(world, spawner, blueprint, x, y)
        record(EditorEdit.Spawn(history.nextSequence(), context.command.session, netId))
        AgentResult.ok {
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
    ): AgentResult = journaled(context) {
        val entity = netIds.requireLive(id)
        val removed = world.snapshotOf(entity)
        // Detached rather than freed: the id stays held, unresolvable, so an undo can put the
        // entity back behind it. `release` frees it for good once no history can undo this.
        netIds.detach(id)
        world -= entity
        record(EditorEdit.Delete(history.nextSequence(), context.command.session, id, removed))
        AgentResult.ok {
            put("id", id.raw)
            put("value", null as String?)
            reverse(UNDO) {}
        }
    }

    // --- edit sessions -------------------------------------------------------------------

    @AgentTool(
        name = "editor.begin_edit",
        description = "Open a live edit of some fields on some entities - a drag, or a value " +
            "being scrubbed - recording what each holds now, and answer its sessionId. " +
            "editor.update_edit then writes values with no undo entry, editor.commit_edit closes " +
            "it as one undo entry and editor.cancel_edit puts every starting value back. You " +
            "have one session at a time: beginning another commits the first. A session with no " +
            "update for 30 seconds is cancelled.",
    )
    public fun beginEdit(
        context: AgentContext,
        @Arg(description = "The entities to edit, comma separated NetId packed words.")
        entities: List<NetId>,
        @Arg(
            description = "The fields to edit on every one of those entities, each written " +
                "Component.field as world.list_components spells it, e.g. Position.x.",
        )
        fields: List<String>,
    ): AgentResult = journaled(context) {
        val author = context.command.session
        requireDistinct(BEGIN_EDIT, "entities", entities)
        val refs = fields.map { path ->
            if (path.isBlank()) {
                throw AgentToolException(AgentErrorKind.BAD_ARGUMENT, "$BEGIN_EDIT got an empty field name in fields=${fields.joinToString(",")}")
            }
            components.resolveField(path.trim(), emptyList())
        }
        if (refs.distinctBy { it.toString() }.size != refs.size) {
            throw AgentToolException(AgentErrorKind.BAD_ARGUMENT, "$BEGIN_EDIT names one field twice in fields=${fields.joinToString(",")}")
        }
        val live = entities.map { netId ->
            netIds.requireLive(netId).also { entity -> for (ref in refs) requirePresent(ref.component, entity, netId) }
        }
        // Everything is checked before the earlier session is committed, so a refused begin
        // leaves that session open exactly as it was.
        val committed = openEdits.of(author)?.let { earlier -> earlier.id.also { commit(earlier) } }
        val start = Array(live.size * refs.size) { slot ->
            val ref = refs[slot % refs.size]
            ref.component.read(world, live[slot / refs.size], ref.fieldIndex)
        }
        val session = EditSession(openEdits.nextId(), author, entities, refs, start, idleClock.nowNanos())
        openEdits.open(session)
        bridge.event("editor_begin_edit:${session.id.raw}:${label(author)}", clock.tick.value)

        AgentResult.ok {
            put("sessionId", session.id.raw)
            if (committed != null) put("committed", committed.raw)
            arr("start") {
                for (entity in entities.indices) {
                    for (field in refs.indices) {
                        element {
                            put("id", entities[entity].raw)
                            put("component", refs[field].component.name)
                            put("field", refs[field].name)
                            field("value", session.startOf(entity, field))
                        }
                    }
                }
            }
        }
    }

    @AgentTool(
        name = "editor.update_edit",
        description = "Write new values into your open edit session, live, with no undo entry. " +
            "Each value is Component.field=value for every entity in the session, or " +
            "<id>:Component.field=value for one of them. Only fields the session opened can be " +
            "written. Every update restarts the session's 30 second idle count.",
    )
    public fun updateEdit(
        context: AgentContext,
        @Arg(description = "The sessionId editor.begin_edit answered.")
        sessionId: Int,
        @Arg(
            description = "Comma separated Component.field=value or <id>:Component.field=value " +
                "entries, each value exact and as text, coerced to the field's type.",
        )
        values: List<String>,
    ): AgentResult = journaled(context) {
        val session = requireOwnSession(context, sessionId, UPDATE_EDIT)
        val writes = values.map { parseSessionValue(session, it) }
        // Resolve and coerce everything before writing anything, so a refusal writes nothing.
        val gone = ArrayList<NetId>()
        val planned = ArrayList<PlannedWrite>()
        for (write in writes) {
            val targets = if (write.entity >= 0) listOf(write.entity) else session.entities.indices.toList()
            for (entityIndex in targets) {
                val netId = session.entities[entityIndex]
                val ref = session.fields[write.field]
                val entity = liveWithOrNull(netId, ref.component)
                if (entity == null) {
                    if (netId !in gone) gone.add(netId)
                    continue
                }
                val current = ref.component.read(world, entity, ref.fieldIndex)
                planned.add(PlannedWrite(entity, ref, parseFieldText(UPDATE_EDIT, ref.component, ref.fieldIndex, current, write.text)))
            }
        }
        for (write in planned) write.ref.component.write(world, write.entity, write.ref.fieldIndex, write.value)
        session.touchedNanos = idleClock.nowNanos()

        AgentResult.ok {
            put("sessionId", session.id.raw)
            put("written", planned.size)
            if (gone.isNotEmpty()) ids("gone", gone)
        }
    }

    @AgentTool(
        name = "editor.commit_edit",
        description = "Close your open edit session as one undo entry whose reverse is the " +
            "values it started from. A session that changed nothing closes with no entry. " +
            "Answers whether an entry was recorded.",
    )
    public fun commitEdit(
        context: AgentContext,
        @Arg(description = "The sessionId editor.begin_edit answered.")
        sessionId: Int,
    ): AgentResult = journaled(context) {
        val session = requireOwnSession(context, sessionId, COMMIT_EDIT)
        val recorded = commit(session)
        AgentResult.ok {
            put("sessionId", session.id.raw)
            put("recorded", recorded != null)
            if (recorded != null) put("fields", recorded.changes.size)
        }
    }

    @AgentTool(
        name = "editor.cancel_edit",
        description = "Close your open edit session and put back every value it started from, " +
            "exactly, whoever changed it since. Records no undo entry. What Escape does during " +
            "a drag.",
    )
    public fun cancelEdit(
        context: AgentContext,
        @Arg(description = "The sessionId editor.begin_edit answered.")
        sessionId: Int,
    ): AgentResult = journaled(context) {
        val session = requireOwnSession(context, sessionId, CANCEL_EDIT)
        val gone = cancel(session)
        AgentResult.ok {
            put("sessionId", session.id.raw)
            if (gone.isNotEmpty()) ids("gone", gone)
        }
    }

    @AgentTool(
        name = "editor.leave",
        description = "Say you are done editing: your open edit session, if any, is cancelled " +
            "with its starting values put back, and your selection is cleared. Call it before " +
            "disconnecting; a session left open is cancelled anyway after 30 seconds idle.",
    )
    public fun leave(context: AgentContext): AgentResult = journaled(context) {
        val author = context.command.session
        val open = openEdits.of(author)
        if (open != null) cancel(open)
        selections.clear(author)
        AgentResult.ok {
            put("author", label(author))
            if (open != null) put("cancelled", open.id.raw)
        }
    }

    // --- selection and inspection ----------------------------------------------------------

    @AgentTool(
        name = "editor.select",
        description = "Change your selection: replace it with these entities, add them to it, or " +
            "remove them from it. Each author has one selection, and every author can read every " +
            "selection with editor.selection. Replace with no entities clears it.",
    )
    public fun select(
        context: AgentContext,
        @Arg(description = "Comma separated NetId packed words. Omit to clear with mode=replace.", required = false)
        entities: List<NetId>?,
        @Arg(description = "replace, add or remove.", required = false, default = "replace")
        mode: SelectMode,
    ): AgentResult {
        val author = context.command.session
        val ids = entities.orEmpty()
        requireDistinct(SELECT, "entities", ids)
        if (mode != SelectMode.remove) for (id in ids) netIds.requireLive(id)
        selections.select(author, ids, mode)
        return AgentResult.ok {
            put("author", label(author))
            ids("ids", liveOnly(selections.of(author)))
        }
    }

    @AgentTool(
        name = "editor.selection",
        description = "Every author's current selection, yours included, as NetIds. An entity " +
            "that has since been removed is not listed.",
    )
    public fun selection(context: AgentContext): AgentResult = AgentResult.ok {
        put("you", label(context.command.session))
        arr("authors") {
            selections.forEachAuthor { author, selected ->
                val live = liveOnly(selected)
                if (live.isNotEmpty()) {
                    element {
                        put("author", label(author))
                        ids("ids", live)
                    }
                }
            }
        }
    }

    @AgentTool(
        name = "editor.common_fields",
        description = "The fields several entities share, for an inspector showing a " +
            "multi-selection: every field of every component all of them carry, with its value " +
            "when they agree, or mixed=true when they differ.",
    )
    public fun commonFields(
        @Arg(description = "Comma separated NetId packed words.")
        entities: List<NetId>,
    ): AgentResult {
        requireDistinct(COMMON_FIELDS, "entities", entities)
        val live = entities.map { netIds.requireLive(it) }
        return AgentResult.ok {
            put("entities", entities.size)
            arr("fields") {
                for (type in components.all()) {
                    if (live.any { !type.isPresent(world, it) }) continue
                    for (fieldIndex in type.fieldNames.indices) {
                        val first = type.read(world, live[0], fieldIndex)
                        val mixed = live.any { type.read(world, it, fieldIndex) != first }
                        element {
                            put("component", type.name)
                            put("field", type.fieldNames[fieldIndex])
                            put("mixed", mixed)
                            if (!mixed) field("value", first)
                        }
                    }
                }
            }
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
    ): AgentResult = journaled(context) {
        val author = context.command.session
        when (val edit = history.newest(author)) {
            null -> AgentResult.failed(
                NOTHING_TO_UNDO,
                "${label(author)} has nothing to undo; editor.history lists what an author can undo, " +
                    "and each author undoes only their own edits",
            )
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
                        if (edit is EditorEdit.Fields && edit.netIds.size > 1) ids("ids", edit.netIds)
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
        val entities = ArrayList<Entity>(edit.changes.size)
        for (change in edit.changes) {
            entities.add(liveWithOrNull(change.netId, change.component) ?: return gone(edit, overwrite))
        }
        val conflictAt = edit.changes.indices.firstOrNull { index ->
            val change = edit.changes[index]
            change.component.read(world, entities[index], change.fieldIndex) != change.after
        }
        if (conflictAt != null && !overwrite) {
            val conflict = edit.changes[conflictAt]
            val current = conflict.component.read(world, entities[conflictAt], conflict.fieldIndex)
            val changer = history.latestByOthers(edit) { it.touches(conflict.netId, conflict.component, conflict.fieldIndex) }
            return AgentResult.failed(
                EDIT_CONFLICT,
                "refused to undo your ${edit.tool} on ${describe(conflict.netId)}: " +
                    "${conflict.component.name}.${conflict.fieldName} now holds " +
                    "${FieldValues.textOf(current)}, not the ${FieldValues.textOf(conflict.after)} " +
                    "your edit left, because ${changedBy(changer)}. Call editor.undo with " +
                    "overwrite=true to put back ${FieldValues.textOf(conflict.before)} anyway.",
            )
        }
        for ((index, change) in edit.changes.withIndex()) {
            change.component.write(world, entities[index], change.fieldIndex, change.before)
        }
        history.pop(edit.author)
        audit(edit, "undo")
        return AgentResult.ok {
            put("undone", edit.tool)
            put("id", edit.netId.raw)
            put("overwrote", conflictAt != null)
            if (edit.netIds.size == 1) {
                obj("value") { for (change in edit.changes) field(change.fieldName, change.before) }
            } else {
                ids("ids", edit.netIds)
                arr("values") {
                    for (change in edit.changes) {
                        element {
                            put("id", change.netId.raw)
                            put("component", change.component.name)
                            put("field", change.fieldName)
                            field("value", change.before)
                        }
                    }
                }
            }
        }
    }

    private fun undoSpawn(edit: EditorEdit.Spawn, overwrite: Boolean): AgentResult {
        val entity = netIds.resolveOrNull(edit.netId) ?: return gone(edit, overwrite)
        val later = history.latestByOthers(edit) { it.touchesEntity(edit.netId) }
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

    // --- sessions ------------------------------------------------------------------------

    /**
     * Closes [session], recording what it changed as one undo entry.
     *
     * Each field's entry runs from its starting value to what it holds now, whoever wrote that
     * last. A field back where it started, or on an entity that has gone, is left out; a session
     * that changed nothing records nothing and answers `null`.
     */
    private fun commit(session: EditSession): EditorEdit.Fields? {
        val changes = ArrayList<FieldChange>()
        for ((entityIndex, netId) in session.entities.withIndex()) {
            for ((fieldIndex, ref) in session.fields.withIndex()) {
                val entity = liveWithOrNull(netId, ref.component) ?: continue
                val before = session.startOf(entityIndex, fieldIndex)
                val after = ref.component.read(world, entity, ref.fieldIndex)
                if (after != before) changes.add(FieldChange(netId, ref.component, ref.fieldIndex, before, after))
            }
        }
        openEdits.close(session)
        if (changes.isEmpty()) {
            bridge.event("editor_commit_edit:${session.id.raw}:${label(session.author)}:unchanged", clock.tick.value)
            return null
        }
        val edit = EditorEdit.Fields(history.nextSequence(), session.author, COMMIT_EDIT, changes)
        record(edit)
        return edit
    }

    /** Closes [session], putting every starting value back. Answers the entities no longer there to restore. */
    private fun cancel(session: EditSession): List<NetId> {
        val gone = ArrayList<NetId>()
        for ((entityIndex, netId) in session.entities.withIndex()) {
            for ((fieldIndex, ref) in session.fields.withIndex()) {
                val entity = liveWithOrNull(netId, ref.component)
                if (entity == null) {
                    if (netId !in gone) gone.add(netId)
                    continue
                }
                ref.component.write(world, entity, ref.fieldIndex, session.startOf(entityIndex, fieldIndex))
            }
        }
        openEdits.close(session)
        bridge.event("editor_cancel_edit:${session.id.raw}:${label(session.author)}", clock.tick.value)
        return gone
    }

    /**
     * Asks for every session idle past [IDLE_TIMEOUT_SECONDS] to be cancelled.
     *
     * Runs at the start of each `AgentBridge.drain`, on the simulation thread and between ticks,
     * never inside a tool call. It does not cancel anything itself: it submits the
     * `editor.cancel_edit` the session's author could have sent, so the cancel crosses the same
     * queue and barrier, is answered in the same ring and is journaled like any other.
     */
    private fun sweepIdle() {
        val now = idleClock.nowNanos()
        for (raw in 0 until sessions.capacity) {
            val session = openEdits.of(AgentSessionId(raw)) ?: continue
            if (session.expiring || now - session.touchedNanos < IDLE_TIMEOUT_NANOS) continue
            val cancel = AgentCommand(CANCEL_EDIT, mapOf(SESSION_ID to session.id.raw.toString()), session = session.author)
            // Refused only by a full queue; the next drain tries again.
            if (bridge.submit(cancel) is AgentSubmission.Accepted) {
                session.expiring = true
                bridge.event("editor_expired:${session.id.raw}:${label(session.author)}", clock.tick.value)
            }
        }
    }

    private fun requireOwnSession(context: AgentContext, raw: Int, tool: String): EditSession {
        val session = openEdits.find(EditSessionId(raw)) ?: throw AgentToolException(
            NO_SUCH_EDIT,
            "$tool: there is no open edit session $raw; it was committed, cancelled, replaced by " +
                "a later editor.begin_edit, or cancelled after $IDLE_TIMEOUT_SECONDS seconds with no update",
        )
        if (session.author != context.command.session) {
            throw AgentToolException(
                NOT_YOUR_EDIT,
                "$tool: edit session $raw belongs to ${label(session.author)}, and only its author " +
                    "can update, commit or cancel it; send the same session= it was begun with",
            )
        }
        return session
    }

    /** One `update_edit` entry: which session field, on which entity (-1 for all), and the text. */
    private class SessionValue(val entity: Int, val field: Int, val text: String)

    /** One write `update_edit` has checked and will make. */
    private class PlannedWrite(val entity: Entity, val ref: FieldRef, val value: Any)

    private fun parseSessionValue(session: EditSession, entry: String): SessionValue {
        val equals = entry.indexOf('=')
        if (equals <= 0) {
            throw AgentToolException(
                AgentErrorKind.BAD_ARGUMENT,
                "$UPDATE_EDIT got '$entry'; each value is Component.field=value or <id>:Component.field=value",
            )
        }
        var target = entry.substring(0, equals).trim()
        var entity = -1
        val colon = target.indexOf(':')
        if (colon >= 0) {
            val raw = target.substring(0, colon).trim().toIntOrNull() ?: throw AgentToolException(
                AgentErrorKind.BAD_ARGUMENT,
                "$UPDATE_EDIT got '$entry'; the part before ':' must be a NetId packed word",
            )
            val netId = try {
                NetId.ofRaw(raw)
            } catch (reserved: IllegalArgumentException) {
                // Adapted, not swallowed: the agent is told which entry and why.
                throw AgentToolException(AgentErrorKind.BAD_ARGUMENT, "$UPDATE_EDIT got '$entry': ${reserved.message}")
            }
            entity = session.entities.indexOf(netId)
            if (entity < 0) {
                throw AgentToolException(
                    NOT_IN_EDIT,
                    "$UPDATE_EDIT got '$entry', but ${describe(netId)} is not in edit session " +
                        "${session.id.raw}; it holds ${session.entities.joinToString { it.raw.toString() }}",
                )
            }
            target = target.substring(colon + 1).trim()
        }
        val ref = components.resolveField(target, session.fields.map { it.component }.distinct())
        val field = session.fieldIndexOf(ref)
        if (field < 0) {
            throw AgentToolException(
                NOT_IN_EDIT,
                "$UPDATE_EDIT got '$entry', but $ref is not a field edit session ${session.id.raw} " +
                    "opened; it opened ${session.fields.joinToString()}. Begin a new session to edit it",
            )
        }
        return SessionValue(entity, field, entry.substring(equals + 1).trim())
    }

    // --- shared --------------------------------------------------------------------------

    private fun moveField(id: NetId, entity: Entity, position: PositionRef, fieldIndex: Int, to: Float, from: Float?): FieldChange {
        val type = position.component
        val current = type.read(world, entity, fieldIndex)
        val before = if (from == null) current else parseFieldText(MOVE, type, fieldIndex, current, from.toString())
        type.write(world, entity, fieldIndex, parseFieldText(MOVE, type, fieldIndex, current, to.toString()))
        return FieldChange(id, type, fieldIndex, before, type.read(world, entity, fieldIndex))
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

    private fun requireDistinct(tool: String, argument: String, ids: List<NetId>) {
        val repeated = ids.groupBy { it }.entries.firstOrNull { it.value.size > 1 } ?: return
        throw AgentToolException(
            AgentErrorKind.BAD_ARGUMENT,
            "$tool names ${describe(repeated.key)} more than once in $argument",
        )
    }

    /** The entity behind [netId] when it is live and still carries [component], else `null`. */
    private fun liveWithOrNull(netId: NetId, component: AgentComponentType): Entity? {
        val entity = netIds.resolveOrNull(netId) ?: return null
        return if (component.isPresent(world, entity)) entity else null
    }

    private fun liveOnly(ids: List<NetId>): List<NetId> = ids.filter { netIds.resolveOrNull(it) != null }

    /** [body], journaled when it succeeded. A refused or throwing call changed nothing, so nothing is kept. */
    private inline fun journaled(context: AgentContext, body: () -> AgentResult): AgentResult {
        val result = body()
        if (result is AgentResult.Ok) {
            val command = context.command
            journal.record(EditorJournalEntry(clock.tick, label(command.session), command.name, command.args))
        }
        return result
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

        /** How long an edit session may go without an update before it is cancelled. */
        internal const val IDLE_TIMEOUT_SECONDS: Long = 30

        private const val IDLE_TIMEOUT_NANOS: Long = IDLE_TIMEOUT_SECONDS * 1_000_000_000L

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

        /** A session call naming a session that is not open: committed, cancelled or timed out. */
        internal val NO_SUCH_EDIT: AgentErrorKind = AgentErrorKind("no_such_edit")

        /** A session call from an author the session does not belong to. */
        internal val NOT_YOUR_EDIT: AgentErrorKind = AgentErrorKind("not_your_edit")

        /** An update naming an entity or a field its session did not open. */
        internal val NOT_IN_EDIT: AgentErrorKind = AgentErrorKind("not_in_edit")

        private const val SET_FIELD = "editor.set_field"
        private const val MOVE = "editor.move"
        private const val DELETE = "editor.delete"
        private const val UNDO = "editor.undo"
        private const val BEGIN_EDIT = "editor.begin_edit"
        private const val UPDATE_EDIT = "editor.update_edit"
        private const val COMMIT_EDIT = "editor.commit_edit"
        private const val CANCEL_EDIT = "editor.cancel_edit"
        private const val SELECT = "editor.select"
        private const val COMMON_FIELDS = "editor.common_fields"

        /** `cancel_edit`'s argument, as the idle sweep sends it. */
        private const val SESSION_ID = "sessionId"

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

        private fun Json.ids(name: String, ids: List<NetId>) {
            key(name)
            beginArray()
            for (id in ids) value(id.raw)
            endArray()
        }
    }
}
