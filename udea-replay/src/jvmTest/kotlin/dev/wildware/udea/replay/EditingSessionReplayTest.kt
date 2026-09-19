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
import dev.wildware.udea.agent.tools.EditorJournalEntry
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A recorded editing session replays into the same world, tick for tick (issue #232).
 *
 * The recording is the two halves an edited match has: a `.udearep` - this game's input, which is
 * none, and the world hash every tick produced - and the `editor.*` journal, each call with the
 * tick whose barrier drain applied it. The replay builds a fresh game, submits each journaled call
 * through the bridge before the tick it was stamped with, steps, and lets [ReplayVerifier] - the
 * same verifier every `.udearep` goes through - compare its hashes with the recorded ones.
 *
 * [Drifter] moves by its velocity every tick, so *when* an edit lands is visible in every later
 * hash: an edit applied one tick late is a different world from that tick on. The script covers
 * every kind of editing call that changes something - a session begun, updated across ticks and
 * committed; a multi-entity `set_field`; an undo; a session left idle until the sweep cancels it
 * on the wall clock; and a session cancelled by `editor.leave`.
 */
class EditingSessionReplayTest {

    @Test
    fun `a recorded editing session replays bit-identically, with every edit applied between ticks`() {
        val (recording, journal) = record()

        val verification = ReplayVerifier.verify(recording, replaying(journal), IDENTITY)

        println("[edit-replay] ${journal.size} journaled calls over ${recording.tickCount} ticks: ${verification.describe()}")
        assertTrue(verification.isBitExact, verification.describe())
        assertEquals(TICKS, verification.ticksCompared)
    }

    @Test
    fun `the journal holds the idle sweep's cancel as an ordinary cancel_edit, so a replay needs no clock`() {
        val (_, journal) = record()

        val expired = journal.single { it.tool == "editor.cancel_edit" && it.author == "alice" }

        assertEquals(Tick(EXPIRY_TICK), expired.tick, "the idle cancel was not applied on the tick after the clock passed 30 seconds")
    }

    /**
     * The proof bites: the same recording replayed without its journal diverges on the first tick
     * an edit changed the world. Without this, a world the edits never touched would pass the test
     * above just as well.
     */
    @Test
    fun `replaying the input without the edit journal diverges on the first edited tick`() {
        val (recording, _) = record()

        val verification = ReplayVerifier.verify(recording, replaying(emptyList()), IDENTITY)

        println("[edit-replay] without the journal: ${verification.describe()}")
        assertFalse(verification.isBitExact)
        assertEquals(Tick(FIRST_WRITE_TICK), verification.firstDivergentTick)
    }

    /** And an edit applied one tick later than recorded is a different world from that tick on. */
    @Test
    fun `replaying every edit one tick late diverges on the first edited tick`() {
        val (recording, journal) = record()
        val late = journal.map { EditorJournalEntry(it.tick + 1L, it.author, it.tool, it.args) }

        val verification = ReplayVerifier.verify(recording, replaying(late), IDENTITY)

        println("[edit-replay] one tick late: ${verification.describe()}")
        assertEquals(Tick(FIRST_WRITE_TICK), verification.firstDivergentTick)
    }

    // --- recording ---------------------------------------------------------------------------

    /** Plays [SCRIPT] into a fresh game, recording each tick's hash and the editor's journal. */
    private fun record(): Pair<ReplayRecording, List<EditorJournalEntry>> {
        val clock = ManualClock()
        val game = EditGame(clock)
        val recorder = ReplayRecorder(IDENTITY, SCHEMA, peerCount = 1, gameId = "edit-replay", gameVersion = "1")
        val slots = recorder.newSampleSlots()
        repeat(TICKS) {
            val tick = game.sim.tick
            if (tick.value == IDLE_TICK) clock.nanos += 31_000_000_000L
            for ((author, tool, args) in SCRIPT[tick.value].orEmpty()) {
                val answer = game.sim.call(tool, args.toMap(), game.sessions.intern(author))
                assertIs<AgentResult.Ok>(answer, "$tool at $tick: $answer")
            }
            game.sim.step(1)
            recorder.record(tick, slots, game.hash())
        }
        // Through the file format and back, so what is replayed is what a `.udearep` carries.
        return ReplayRecording.decode(recorder.seal().encode()) to game.editor.journal.entries.toList()
    }

    /** A fresh game per replay, fed [journal] before each tick. Its idle clock never moves. */
    private fun replaying(journal: List<EditorJournalEntry>) = ReplayWorldFactory { firstTick ->
        JournalReplayWorld(EditGame(ManualClock()), journal).also {
            check(it.tick == firstTick) { "the edit game starts at ${it.tick}, the recording at $firstTick" }
        }
    }

    private class JournalReplayWorld(
        private val game: EditGame,
        private val journal: List<EditorJournalEntry>,
    ) : ReplayWorld {

        override val tick: Tick get() = game.sim.tick

        override fun applyInput(samples: Array<InputSample>) {
            // This game reads no input; its tick's changes are the edits made before it.
            for (entry in journal) {
                if (entry.tick != tick) continue
                val command = entry.command(game.sessions)
                val answer = game.sim.call(command.name, command.args, command.session)
                check(answer is AgentResult.Ok) { "replaying $entry was refused: $answer" }
            }
        }

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

        /** The sweep runs in the pump that steps tick [IDLE_TICK], so its cancel lands on that tick. */
        const val EXPIRY_TICK = IDLE_TICK

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
