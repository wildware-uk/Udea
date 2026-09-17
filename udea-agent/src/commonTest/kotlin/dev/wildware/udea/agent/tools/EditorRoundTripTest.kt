package dev.wildware.udea.agent.tools

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.EntityCreateContext
import com.github.quillraven.fleks.World
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.activity.AgentSessionId
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.agent.harness.SimHarness
import dev.wildware.udea.agent.query.AgentComponentIndex
import dev.wildware.udea.agent.query.PositionRef
import dev.wildware.udea.agent.query.agentComponent
import dev.wildware.udea.agent.state.StateDigest
import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.blueprint.Blueprint
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.core.blueprint.BlueprintSpawner
import dev.wildware.udea.core.blueprint.SpawnPlacement
import dev.wildware.udea.core.blueprint.blueprintSpawner
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.core.module.UdeaModule
import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.core.replication.BitWriter
import dev.wildware.udea.core.replication.ComponentTypeId
import dev.wildware.udea.core.replication.FieldMask
import dev.wildware.udea.core.replication.FieldStore
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.replication.NoSuchFieldIndexException
import dev.wildware.udea.core.replication.Replicator
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.FieldKind
import dev.wildware.udea.core.snapshot.SnapshotService
import dev.wildware.udea.core.snapshot.WorldHasher
import dev.wildware.udea.core.snapshot.fleksComponentType
import dev.wildware.udea.generated.UdeaAgentUdeaRegistry
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `editor.*` undo round trip and a level save, on every target this module builds for
 * (issue #193).
 *
 * Common code, so the same assertions run as `jvmTest` and as `wasmJsNodeTest`: the editor
 * toolset is `commonMain`, and a history that restored a world on the JVM but not in a browser
 * build would be found here rather than by a person authoring a level. `EditorUndoTest` (JVM) is
 * the fuller suite - authors, conflicts, the cap - over the JVM harness's four components.
 *
 * Everything goes through [SimHarness], so every call is submitted to the bridge and dispatched in
 * a `SimBarrier` drain, as an HTTP command is.
 */
class EditorRoundTripTest {

    @Test
    fun `edits then the same number of undos return the world hash to its start`() {
        val game = Game()
        val kept = game.place(x = 1f, y = 2f, level = 3)
        val deleted = game.place(x = 5f, y = 6f, level = 7)
        val start = game.fieldHash()
        val designer = game.sessions.intern("designer")

        // `level` is not agent-writable, so `world.set_component_field` would refuse this one.
        game.edit(designer, "editor.set_field", "id" to "${kept.raw}", "component" to "Plot", "field" to "level", "value" to "9")
        game.edit(designer, "editor.move", "id" to "${kept.raw}", "x" to "40", "y" to "-3")
        game.edit(designer, "editor.spawn", "blueprint" to "marker", "x" to "8", "y" to "8")
        game.edit(designer, "editor.delete", "id" to "${deleted.raw}")

        assertNotEquals(start, game.fieldHash(), "four edits left the hash where it was, so the test proves nothing")
        assertNull(game.netIds.resolveOrNull(deleted), "a deleted entity still resolves")

        repeat(4) { game.edit(designer, "editor.undo") }

        assertEquals(start, game.fieldHash())
        val restored = assertNotNull(game.netIds.resolveOrNull(deleted), "the undone delete lost its NetId")
        with(game.world) { assertEquals(7, restored[Plot].level) }
    }

    @Test
    fun `a save writes the level file and leaves the history undoable`() {
        val directory = Path(SystemTemporaryDirectory, "udea-editor-${Random.nextLong().toULong()}")
        val game = Game(levelDirectory = directory)
        val plot = game.place(x = 0f, y = 0f, level = 1)
        val designer = game.sessions.intern("designer")
        // `Plot` is not `@Serializable`, so a level can only carry the world once it is gone - and
        // deleting it through the editor is also what puts an entry in the history.
        game.edit(designer, "editor.delete", "id" to "${plot.raw}")

        val file = Path(directory, "arena.udealevel")
        try {
            game.edit(designer, "editor.save", "name" to "arena")

            val size = SystemFileSystem.metadataOrNull(file)?.size ?: 0L
            assertTrue(size > 0, "no level written at $file")
            game.edit(designer, "editor.undo")
            assertNotNull(game.netIds.resolveOrNull(plot), "a delete made before the save could not be undone")
        } finally {
            if (SystemFileSystem.exists(file)) SystemFileSystem.delete(file)
            if (SystemFileSystem.exists(directory)) SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun `an editor given no level directory refuses a save with a typed error`() {
        val game = Game(levelDirectory = null)

        val refused = assertIs<AgentResult.Failed>(game.sim.call("editor.save", mapOf("name" to "arena"), game.sessions.intern("designer")))

        assertEquals(EditorToolset.NO_LEVEL_STORE, refused.error.kind)
    }

    /** A headless game with the editor toolset wired over one component, [Plot]. */
    private class Game(levelDirectory: Path? = null) {
        private val module = SpawnerModule()

        private val definition = UdeaGameDef(registry = UdeaAgentUdeaRegistry, modules = listOf(module))

        val netIds = definition.core.netIds

        private val spawner = BlueprintSpawner(definition.core.barrier, netIds, PlotPlacement)

        val host: GameHost

        val world: World

        val sessions = AgentSessions()

        val sim: SimHarness

        private val registry = ComponentRegistry(
            listOf(fleksComponentType(PlotReplicator, ComponentSchema.of(PlotReplicator, "Plot", PlotReplicator.KINDS), Plot) { Plot() }),
        )

        init {
            module.spawner = spawner
            host = GameHost(RenderMode.Headless, definition)
            world = host.world
            val bridge = AgentBridge()
            val plot = agentComponent(name = "Plot", replicator = PlotReplicator, componentType = Plot)
            val editor = EditorToolset(
                world = world,
                components = AgentComponentIndex(listOf(plot)),
                netIds = netIds,
                sessions = sessions,
                bridge = bridge,
                clock = host.ctx.clock,
                position = PositionRef(plot, PlotReplicator.X, PlotReplicator.Y),
                catalog = BlueprintCatalog.of(listOf(MarkerBlueprint)),
                spawner = spawner,
                levels = levelDirectory?.let { EditorLevelStore(host.game.levels, it) },
            )
            val tools = EngineToolModules.wireAll(ToolIndex.builder(), editor).build()
            sim = SimHarness(host, bridge, tools, StateDigest(bridge))
        }

        fun place(x: Float, y: Float, level: Int): NetId =
            netIds.allocate(world.entity { it += Plot(x, y, level) })

        fun fieldHash(): Long =
            WorldHasher.hash(SnapshotService(registry, world, host.ctx, netIds).capture().fields)

        fun edit(author: AgentSessionId, tool: String, vararg args: Pair<String, String>): String {
            val result = sim.call(tool, args.toMap(), author)
            return assertIs<AgentResult.Ok>(result, "$tool failed: $result").json
        }
    }
}

/** A position and a level: two float fields the editor moves, and an int nothing may write but it. */
private class Plot(var x: Float = 0f, var y: Float = 0f, var level: Int = 0) : Component<Plot> {
    override fun type(): ComponentType<Plot> = Plot

    companion object : ComponentType<Plot>()
}

/** The whole frozen contract for [Plot], by hand, as `docs/contracts/replicator.md` states it. */
private object PlotReplicator : Replicator<Plot> {
    const val X = 0
    const val Y = 1
    const val LEVEL = 2
    private const val FIELD_COUNT = 3

    val KINDS: List<FieldKind> = listOf(FieldKind.Float, FieldKind.Float, FieldKind.Int)

    override val typeId: ComponentTypeId = ComponentTypeId(1)

    override val fieldNames: List<String> = listOf("x", "y", "level")

    override val netMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override fun capture(component: Plot, store: FieldStore, slot: Int) {
        store.setFloat(slot, X, component.x)
        store.setFloat(slot, Y, component.y)
        store.setInt(slot, LEVEL, component.level)
    }

    override fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask {
        var mask = MaskOps.EMPTY
        if (store.getFloat(slotA, X).toRawBits() != store.getFloat(slotB, X).toRawBits()) mask = MaskOps.set(mask, X)
        if (store.getFloat(slotA, Y).toRawBits() != store.getFloat(slotB, Y).toRawBits()) mask = MaskOps.set(mask, Y)
        if (store.getInt(slotA, LEVEL) != store.getInt(slotB, LEVEL)) mask = MaskOps.set(mask, LEVEL)
        return mask
    }

    override fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter) {
        if (MaskOps.isEmpty(mask)) return
        MaskOps.writeTo(mask, out, FIELD_COUNT)
        if (MaskOps.test(mask, X)) out.writeFloat(store.getFloat(slot, X))
        if (MaskOps.test(mask, Y)) out.writeFloat(store.getFloat(slot, Y))
        if (MaskOps.test(mask, LEVEL)) out.writeInt(store.getInt(slot, LEVEL))
    }

    override fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask {
        val mask = MaskOps.readFrom(src, FIELD_COUNT)
        if (MaskOps.test(mask, X)) store.setFloat(slot, X, src.readFloat())
        if (MaskOps.test(mask, Y)) store.setFloat(slot, Y, src.readFloat())
        if (MaskOps.test(mask, LEVEL)) store.setInt(slot, LEVEL, src.readInt())
        return mask
    }

    override fun apply(store: FieldStore, slot: Int, component: Plot, mask: FieldMask) {
        if (MaskOps.test(mask, X)) component.x = store.getFloat(slot, X)
        if (MaskOps.test(mask, Y)) component.y = store.getFloat(slot, Y)
        if (MaskOps.test(mask, LEVEL)) component.level = store.getInt(slot, LEVEL)
    }

    override fun getField(component: Plot, fieldIndex: Int): Any? = when (fieldIndex) {
        X -> component.x
        Y -> component.y
        LEVEL -> component.level
        else -> throw NoSuchFieldIndexException("Plot", fieldIndex, FIELD_COUNT)
    }

    override fun setField(component: Plot, fieldIndex: Int, value: Any?) {
        when (fieldIndex) {
            X -> component.x = requireNotNull(value as? Float) { "Plot.x is a Float, got $value" }
            Y -> component.y = requireNotNull(value as? Float) { "Plot.y is a Float, got $value" }
            LEVEL -> component.level = requireNotNull(value as? Int) { "Plot.level is an Int, got $value" }
            else -> throw NoSuchFieldIndexException("Plot", fieldIndex, FIELD_COUNT)
        }
    }
}

/** Puts the spawner on the context, where `ctx.blueprints` reads it. */
private class SpawnerModule : UdeaModule {
    var spawner: BlueprintSpawner? = null

    override fun context(builder: GameContextBuilder) {
        builder.blueprintSpawner(checkNotNull(spawner) { "wire the spawner before building" })
    }
}

/** [Plot] is this game's spatial component. */
private object PlotPlacement : SpawnPlacement {
    override fun defaultIfAbsent(world: World, entity: Entity) {
        with(world) { if (entity.getOrNull(Plot) == null) entity.configure { it += Plot() } }
    }

    override fun moveTo(world: World, entity: Entity, x: Float, y: Float) {
        with(world) {
            val plot = entity[Plot]
            plot.x = x
            plot.y = y
        }
    }
}

/** The one blueprint `editor.spawn` can name here. */
private object MarkerBlueprint : Blueprint {
    override val id: BlueprintId = BlueprintId("marker")

    override fun configure(context: EntityCreateContext, entity: Entity) {
        with(context) { entity += Plot(level = 1) }
    }
}
