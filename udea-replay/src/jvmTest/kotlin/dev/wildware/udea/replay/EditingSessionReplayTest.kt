package dev.wildware.udea.replay

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.World
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentClock
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.activity.AgentSessions
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.agent.harness.SimHarness
import dev.wildware.udea.agent.query.AgentComponentIndex
import dev.wildware.udea.agent.query.agentComponent
import dev.wildware.udea.agent.state.StateDigest
import dev.wildware.udea.agent.tools.EditorToolset
import dev.wildware.udea.agent.tools.EngineToolModules
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
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
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.core.snapshot.fleksComponentType
import dev.wildware.udea.generated.CoreUdeaRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A recorded editing session replays into the same world, tick for tick (issue #232).
 *
 * The recording is one `.udearep`: this game's input, which is none, the world hash every tick
 * produced, and - format 2 - every `editor.*` call that changed something, with the tick whose
 * barrier drain applied it, taken from the editor's journal as the match is recorded. The replay
 * is [ReplayVerifier]'s, the same verifier every `.udearep` goes through: it builds a fresh game,
 * hands each tick's edits to [ReplayWorld.applyEdits] before the tick, steps, and compares hashes.
 * Nothing but the decoded file reaches the replay.
 *
 * [Drifter] moves by its velocity every tick, so *when* an edit lands is visible in every later
 * hash: an edit applied one tick late is a different world from that tick on. The script covers
 * every kind of editing call that changes something - a session begun, updated across ticks and
 * committed; a multi-entity `set_field`; an undo; a session cancelled by `editor.leave`; and a
 * session left idle until the sweep cancels it on the recording's wall clock.
 */
class EditingSessionReplayTest {

    @Test
    fun `a recorded editing session replays bit-identically, with every edit applied between ticks`() {
        val recording = ReplayRecording.decode(record().encode())

        val verification = ReplayVerifier.verify(recording, replaying(), IDENTITY)

        println("[edit-replay] ${recording.edits.size} recorded edits over ${recording.tickCount} ticks: ${verification.describe()}")
        assertTrue(verification.isBitExact, verification.describe())
        assertEquals(TICKS, verification.ticksCompared)
    }

    @Test
    fun `an edited recording is format 2 and carries every edit, and the idle cancel is one of them`() {
        val bytes = record().encode()
        val recording = ReplayRecording.decode(bytes)

        assertEquals(ReplayFormat.FORMAT_VERSION, bytes[ReplayFormat.MAGIC.size].toInt())
        assertEquals(SCRIPT.values.sumOf { it.size } + 1, recording.edits.size, recording.edits.joinToString("\n"))
        // The sweep's cancel is an ordinary cancel_edit, stamped with the tick it landed on, so a
        // replay needs no clock to make it.
        val expired = recording.edits.single { it.tool == "editor.cancel_edit" && it.author == "alice" }
        assertEquals(Tick(IDLE_TICK), expired.tick)
        assertEquals(listOf(expired), recording.editsAt(Tick(IDLE_TICK)))
    }

    /**
     * The proof bites: the same file replayed by a world that drops its edits diverges on the first
     * tick an edit changed the world. Without this, a world the edits never touched would pass the
     * test above just as well.
     */
    @Test
    fun `a replay that ignores the recorded edits diverges on the first edited tick`() {
        val recording = ReplayRecording.decode(record().encode())

        val verification = ReplayVerifier.verify(recording, replaying { edits, _ -> edits.clear() }, IDENTITY)

        println("[edit-replay] edits ignored: ${verification.describe()}")
        assertFalse(verification.isBitExact)
        assertEquals(Tick(FIRST_WRITE_TICK), verification.firstDivergentTick)
    }

    /** And an edit applied one tick later than recorded is a different world from that tick on. */
    @Test
    fun `a replay that applies each edit one tick late diverges on the first edited tick`() {
        val recording = ReplayRecording.decode(record().encode())
        val held = ArrayList<ReplayEdit>()

        val verification = ReplayVerifier.verify(
            recording,
            replaying { edits, _ ->
                val now = edits.toList()
                edits.clear()
                edits.addAll(held.map { it.copy(tick = it.tick + 1L) })
                held.clear()
                held.addAll(now)
            },
            IDENTITY,
        )

        println("[edit-replay] one tick late: ${verification.describe()}")
        assertEquals(Tick(FIRST_WRITE_TICK), verification.firstDivergentTick)
    }

    @Test
    fun `a replay world that cannot apply edits refuses an edited recording rather than diverging`() {
        val recording = ReplayRecording.decode(record().encode())
        val inputOnly = ReplayWorldFactory { InputOnlyWorld(EditGame(ManualClock())) }

        val refused = assertFailsWith<IllegalStateException> { ReplayVerifier.verify(recording, inputOnly, IDENTITY) }

        assertTrue(refused.message!!.contains("cannot apply edits"), refused.message)
    }

    // --- recording ---------------------------------------------------------------------------

    /** Plays [SCRIPT] into a fresh game, recording each tick's edits and hash into one `.udearep`. */
    private fun record(): ReplayRecording {
        val clock = ManualClock()
        val game = EditGame(clock)
        val recorder = ReplayRecorder(IDENTITY, SCHEMA, peerCount = 1, gameId = "edit-replay", gameVersion = "1")
        val slots = recorder.newSampleSlots()
        var journaled = 0
        repeat(TICKS) {
            val tick = game.sim.tick
            if (tick.value == IDLE_TICK) clock.nanos += 31_000_000_000L
            for ((author, tool, args) in SCRIPT[tick.value].orEmpty()) {
                val answer = game.sim.call(tool, args.toMap(), game.sessions.intern(author))
                assertIs<AgentResult.Ok>(answer, "$tool at $tick: $answer")
            }
            game.sim.step(1)
            // Everything the editor journaled since the last tick was applied before this one.
            val journal = game.editor.journal.entries
            while (journaled < journal.size) {
                val entry = journal[journaled++]
                recorder.recordEdit(ReplayEdit(entry.tick, entry.author, entry.tool, entry.args))
            }
            recorder.record(tick, slots, game.hash())
        }
        return recorder.seal()
    }

    /**
     * A fresh game per replay. Its idle clock never moves, so the only cancels are recorded ones.
     *
     * [tamper] may rewrite a tick's edits before they are made - what the two negative controls
     * use to drop them or hold them back a tick.
     */
    private fun replaying(tamper: (MutableList<ReplayEdit>, Tick) -> Unit = { _, _ -> }) = ReplayWorldFactory { firstTick ->
        EditingReplayWorld(EditGame(ManualClock()), tamper).also {
            check(it.tick == firstTick) { "the edit game starts at ${it.tick}, the recording at $firstTick" }
        }
    }

    /** Serves a recording's edits through the editor's own tools, as the recorded run made them. */
    private class EditingReplayWorld(
        private val game: EditGame,
        private val tamper: (MutableList<ReplayEdit>, Tick) -> Unit,
    ) : ReplayWorld {

        override val tick: Tick get() = game.sim.tick

        override fun applyEdits(edits: List<ReplayEdit>) {
            val made = edits.toMutableList()
            tamper(made, tick)
            for (edit in made) {
                val answer = game.sim.call(edit.tool, edit.args, game.sessions.intern(edit.author))
                check(answer is AgentResult.Ok) { "replaying $edit was refused: $answer" }
            }
        }

        override fun applyInput(samples: Array<InputSample>) {
            // This game reads no input: what changes it between ticks is the edits.
        }

        override fun step() {
            game.sim.step(1)
        }

        override fun hash(): Long = game.hash()

        override fun snapshot(): WorldSnapshot = game.capture()
    }

    /** A replay world that takes input only, as every game's did before format 2. */
    private class InputOnlyWorld(private val game: EditGame) : ReplayWorld {
        override val tick: Tick get() = game.sim.tick

        override fun applyInput(samples: Array<InputSample>) {}

        override fun step() {
            game.sim.step(1)
        }

        override fun hash(): Long = game.hash()

        override fun snapshot(): WorldSnapshot = game.capture()
    }

    /** A headless game with one moving component, the editor toolset, and a [SimHarness] over both. */
    private class EditGame(idleClock: AgentClock) {

        val sessions = AgentSessions()

        val sim: SimHarness

        val editor: EditorToolset

        private val host: GameHost

        private val netIds: NetIdIndex

        private val registry = ComponentRegistry(
            listOf(fleksComponentType(DrifterReplicator, ComponentSchema.of(DrifterReplicator, "Drifter", DrifterReplicator.KINDS), Drifter) { Drifter() }),
        )

        init {
            val definition = UdeaGameDef(CoreUdeaRegistry, listOf(DriftModule))
            host = GameHost(RenderMode.Headless, definition)
            netIds = definition.core.netIds
            // Three entities, allocated in a fixed order so their NetIds are the ones the script names.
            val placed = listOf(0f to 1f, 10f to 1f, 20f to -1f).map { (x, velocity) ->
                netIds.allocate(host.world.entity { it += Drifter(x, velocity) })
            }
            check(placed == listOf(A, B, C)) { "the script names $A, $B and $C and the game allocated $placed" }
            val bridge = AgentBridge()
            editor = EditorToolset(
                world = host.world,
                components = AgentComponentIndex(listOf(agentComponent(name = "Drifter", replicator = DrifterReplicator, componentType = Drifter))),
                netIds = netIds,
                sessions = sessions,
                bridge = bridge,
                clock = host.ctx.clock,
                position = null,
                idleClock = idleClock,
            )
            sim = SimHarness(host, bridge, EngineToolModules.wireAll(ToolIndex.builder(), editor).build(), StateDigest(bridge))
        }

        fun capture(): WorldSnapshot = SnapshotService(registry, host.world, host.ctx, netIds).capture()

        fun hash(): Long = WorldHasher.hash(capture())
    }

    /** A clock only the recording moves, and the replay never does. */
    private class ManualClock : AgentClock {
        var nanos = 0L

        override fun nowNanos(): Long = nanos
    }

    private companion object {
        const val TICKS = 40

        /** Where the recording's wall clock jumps past the idle timeout with alice's session open. */
        const val IDLE_TICK = 25L

        /** The first call that changes the world: alice's first update. */
        const val FIRST_WRITE_TICK = 4L

        val A = NetId.ofRaw(0)
        val B = NetId.ofRaw(1)
        val C = NetId.ofRaw(2)

        /** What each author calls, and before which tick. */
        val SCRIPT: Map<Long, List<Triple<String, String, List<Pair<String, String>>>>> = mapOf(
            3L to listOf(Triple("alice", "editor.begin_edit", listOf("entities" to "${A.raw},${B.raw}", "fields" to "Drifter.x,Drifter.velocity"))),
            4L to listOf(Triple("alice", "editor.update_edit", listOf("sessionId" to "1", "values" to "Drifter.velocity=3"))),
            6L to listOf(Triple("alice", "editor.update_edit", listOf("sessionId" to "1", "values" to "${A.raw}:Drifter.x=50,${B.raw}:Drifter.x=60"))),
            7L to listOf(Triple("alice", "editor.update_edit", listOf("sessionId" to "1", "values" to "Drifter.velocity=-2"))),
            9L to listOf(Triple("alice", "editor.commit_edit", listOf("sessionId" to "1"))),
            11L to listOf(Triple("bob", "editor.set_field", listOf("id" to "${B.raw},${C.raw}", "component" to "Drifter", "field" to "velocity", "value" to "5"))),
            // Overwrite: the drift has moved x since the commit, so a plain undo is refused.
            14L to listOf(Triple("alice", "editor.undo", listOf("overwrite" to "true"))),
            17L to listOf(
                Triple("bob", "editor.begin_edit", listOf("entities" to "${C.raw}", "fields" to "Drifter.velocity")),
                Triple("bob", "editor.update_edit", listOf("sessionId" to "2", "values" to "Drifter.velocity=9")),
            ),
            20L to listOf(
                Triple("alice", "editor.begin_edit", listOf("entities" to "${A.raw}", "fields" to "Drifter.velocity")),
                Triple("alice", "editor.update_edit", listOf("sessionId" to "3", "values" to "Drifter.velocity=7")),
            ),
            22L to listOf(Triple("bob", "editor.leave", emptyList())),
            // Alice goes quiet; the recording's clock passes 30 seconds at tick 25 and the sweep
            // cancels her session there. Nothing in the script calls cancel_edit for her.
        )

        val SCHEMA = InputSchema(axes = emptyList(), actions = listOf("edit/none"))

        val IDENTITY = BuildIdentity(
            rootSeed = 232L,
            protoHash = 0x0232,
            assetGraphHash = ByteArray(8),
            inputSchemaHash = SCHEMA.hash,
        )
    }
}

/** Moves along x by [velocity] every tick. */
internal class Drifter(var x: Float = 0f, var velocity: Float = 0f) : Component<Drifter> {
    override fun type(): ComponentType<Drifter> = Drifter

    companion object : ComponentType<Drifter>()
}

/** Adds each [Drifter]'s velocity to its x, once per tick. */
internal class DriftSystem : SimSystem() {
    private val drifters = world.family { all(Drifter) }

    override fun onTick() {
        drifters.forEach { it[Drifter].x += it[Drifter].velocity }
    }
}

internal object DriftModule : UdeaModule {
    override fun simulation(registry: SimRegistry) {
        registry.add(SimPhase.Movement, { DriftSystem() })
    }
}

/** [Drifter]'s contract, by hand, as `docs/contracts/replicator.md` states it. */
internal object DrifterReplicator : Replicator<Drifter> {
    private const val X = 0
    private const val VELOCITY = 1
    private const val FIELD_COUNT = 2

    val KINDS: List<FieldKind> = listOf(FieldKind.Float, FieldKind.Float)

    override val typeId: ComponentTypeId = ComponentTypeId(1)

    override val fieldNames: List<String> = listOf("x", "velocity")

    override val netMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override val allMask: FieldMask = MaskOps.lowest(FIELD_COUNT)

    override fun capture(component: Drifter, store: FieldStore, slot: Int) {
        store.setFloat(slot, X, component.x)
        store.setFloat(slot, VELOCITY, component.velocity)
    }

    override fun diff(store: FieldStore, slotA: Int, slotB: Int): FieldMask {
        var mask = MaskOps.EMPTY
        if (store.getFloat(slotA, X).toRawBits() != store.getFloat(slotB, X).toRawBits()) mask = MaskOps.set(mask, X)
        if (store.getFloat(slotA, VELOCITY).toRawBits() != store.getFloat(slotB, VELOCITY).toRawBits()) mask = MaskOps.set(mask, VELOCITY)
        return mask
    }

    override fun write(store: FieldStore, slot: Int, mask: FieldMask, out: BitWriter) {
        if (MaskOps.isEmpty(mask)) return
        MaskOps.writeTo(mask, out, FIELD_COUNT)
        if (MaskOps.test(mask, X)) out.writeFloat(store.getFloat(slot, X))
        if (MaskOps.test(mask, VELOCITY)) out.writeFloat(store.getFloat(slot, VELOCITY))
    }

    override fun read(src: BitReader, store: FieldStore, slot: Int): FieldMask {
        val mask = MaskOps.readFrom(src, FIELD_COUNT)
        if (MaskOps.test(mask, X)) store.setFloat(slot, X, src.readFloat())
        if (MaskOps.test(mask, VELOCITY)) store.setFloat(slot, VELOCITY, src.readFloat())
        return mask
    }

    override fun apply(store: FieldStore, slot: Int, component: Drifter, mask: FieldMask) {
        if (MaskOps.test(mask, X)) component.x = store.getFloat(slot, X)
        if (MaskOps.test(mask, VELOCITY)) component.velocity = store.getFloat(slot, VELOCITY)
    }

    override fun getField(component: Drifter, fieldIndex: Int): Any? = when (fieldIndex) {
        X -> component.x
        VELOCITY -> component.velocity
        else -> throw NoSuchFieldIndexException("Drifter", fieldIndex, FIELD_COUNT)
    }

    override fun setField(component: Drifter, fieldIndex: Int, value: Any?) {
        when (fieldIndex) {
            X -> component.x = requireNotNull(value as? Float) { "Drifter.x is a Float, got $value" }
            VELOCITY -> component.velocity = requireNotNull(value as? Float) { "Drifter.velocity is a Float, got $value" }
            else -> throw NoSuchFieldIndexException("Drifter", fieldIndex, FIELD_COUNT)
        }
    }
}
