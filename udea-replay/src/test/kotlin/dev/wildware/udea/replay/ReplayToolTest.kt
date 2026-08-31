package dev.wildware.udea.replay

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.core.RngStream
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.rng.DefaultRngService
import dev.wildware.udea.core.snapshot.WorldSnapshot
import dev.wildware.udea.replay.tools.ReplayHost
import dev.wildware.udea.replay.tools.ReplayToolModules
import dev.wildware.udea.replay.tools.ReplayToolset
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `replay.*` driven the way an agent drives it: through a real [ToolIndex].
 *
 * ## Why the dispatch path and not the methods
 *
 * Because calling `ReplayToolset.seek(1234)` in a test proves the Kotlin function works and
 * proves nothing about the *tool*. What an agent actually reaches is a generated
 * `AgentToolDef` - name, schema, argument coercion from strings - resolved by name against an
 * index that refuses a tool whose toolset was never registered. Every one of those is a place
 * the surface can be broken without the function changing, and `SimHarness`'s whole KDoc is
 * about not building a parallel path that resembles the real one.
 *
 * So the toolset is registered exactly as a host registers it, and every call here is
 * `index.invoke(AgentCommand(name, args))` with string arguments, which is what arrives over
 * HTTP.
 */
class ReplayToolTest {

    /** The same toy world `ReplayEngineTest` uses, and for the same reason. */
    private class Toy(seed: Long, override var tick: Tick) : ReplayWorld {
        private val rng = DefaultRngService(seed)
        private var state: Long = -0x340d631b7bdddcdbL
        private var pending: Long = 0L

        override fun applyInput(samples: Array<InputSample>) {
            var folded = 0L
            for (sample in samples) {
                for (axis in 0 until sample.schema.axisCount) {
                    folded = folded * PRIME xor sample.axisX(axis).toRawBits().toLong()
                }
                for (action in 0 until sample.schema.actionCount) {
                    folded = folded * PRIME xor sample.pressCount(action).toLong()
                }
            }
            pending = folded
        }

        override fun step() {
            state = (state xor pending) * PRIME
            state = (state xor rng.nextLong(RngStream.Combat)) * PRIME
            tick += 1L
        }

        override fun hash(): Long = state

        override fun snapshot(): WorldSnapshot? = null

        private companion object {
            const val PRIME: Long = 0x100000001b3L
        }
    }

    private class Host(override val recordingRoot: Path, override val identity: BuildIdentity) :
        ReplayHost {
        override fun worlds(recording: ReplayRecording): ReplayWorldFactory =
            ReplayWorldFactory { firstTick -> Toy(identity.rootSeed, firstTick) }
    }

    private val schema = InputSchema(listOf("toy/move"), listOf("toy/fire"))

    private val identity = BuildIdentity(
        rootSeed = 99L,
        protoHash = 0x0042,
        assetGraphHash = ByteArray(4) { 3 },
        inputSchemaHash = schema.hash,
    )

    private fun write(root: Path, name: String, ticks: Int = 200): ReplayRecording {
        val recorder = ReplayRecorder(identity, schema, 1, "toy", "1")
        val world = Toy(identity.rootSeed, FIRST)
        val slots = recorder.newSampleSlots()
        val pilot = Random(System.nanoTime())
        repeat(ticks) {
            val tick = world.tick
            slots[0].clear()
            slots[0].setAxis(0, pilot.nextInt(3) - 1f, 0f)
            slots[0].setPressCount(0, pilot.nextInt(2))
            world.applyInput(slots)
            world.step()
            recorder.record(tick, slots, world.hash())
        }
        val recording = recorder.seal()
        recording.writeTo(root.resolve(name + ReplayFormat.EXTENSION))
        return recording
    }

    private fun index(root: Path): ToolIndex =
        ReplayToolModules.wire(ToolIndex.builder(), ReplayToolset(Host(root, identity))).build()

    private fun call(index: ToolIndex, name: String, vararg args: Pair<String, String>): AgentResult =
        index.invoke(AgentCommand(name, args.toMap()))

    private fun ok(result: AgentResult): String {
        assertTrue(result is AgentResult.Ok, "expected success, got $result")
        return result.json
    }

    private fun failure(result: AgentResult): String {
        assertTrue(result is AgentResult.Failed, "expected a refusal, got $result")
        return result.error.kind.id
    }

    /** Every field of a flat JSON object this test needs, without adding a JSON parser. */
    private fun field(json: String, name: String): String {
        val key = "\"$name\":"
        val at = json.indexOf(key)
        assertTrue(at >= 0, "no '$name' in $json")
        val rest = json.substring(at + key.length)
        val end = rest.indexOfFirst { it == ',' || it == '}' }
        return rest.substring(0, end).trim().trim('"')
    }

    @Test
    fun `the module publishes exactly the tools the toolset declares`() {
        val root = Files.createTempDirectory("replay-tools")
        val names = index(root).tools.map { it.name }.filter { it.startsWith("replay.") }
        assertEquals(
            listOf("replay.info", "replay.load", "replay.rewind", "replay.seek", "replay.step", "replay.verify"),
            names,
        )
    }

    /**
     * The hand-written module list agrees with the generated manifest.
     *
     * The list in `ReplayToolModules` is compile-checked against objects that exist; what it
     * cannot catch is a **new** `@AgentTool` nobody added to it, which would be a tool an agent
     * could never reach. The generated manifest fragment is the independent record of what the
     * KSP pass actually emitted, so comparing the two closes that hole - the same job
     * `EngineToolSurfaceTest` does for the engine's own toolsets.
     */
    @Test
    fun `no generated replay tool is missing from the module`() {
        // Read as a stream, not through `Path.of(url.toURI())`. Once this module publishes test
        // fixtures the project's own jar joins the test runtime classpath, so the fragment
        // resolves to a `jar:` URL and `Path.of` throws `FileSystemNotFoundException` - a test
        // that fails for a packaging reason having nothing to do with what it asserts. A stream
        // reads the same bytes from a directory and from a jar.
        val manifest = ReplayToolModules::class.java.classLoader
            .getResourceAsStream("udea/UdeaReplay-agent-tools.json")
            ?.use { it.readBytes().decodeToString() }
        assertTrue(manifest != null, "the KSP manifest fragment was not emitted or not packaged")
        manifest!!
        val declared = ReplayToolModules.Replay.tools.map { it.name }
        val generated = Regex("\"name\"\\s*:\\s*\"(replay\\.[a-z_]+)\"")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()
        assertEquals(
            generated,
            declared,
            "the generated manifest and ReplayToolModules.Replay disagree; a tool in one and " +
                "not the other is a tool an agent can see and not call, or call and not see",
        )
    }

    @Test
    fun `load then verify reports a bit-exact recording`() {
        val root = Files.createTempDirectory("replay-tools")
        val recording = write(root, "match")
        val index = index(root)

        val loaded = ok(call(index, "replay.load", "name" to "match"))
        assertEquals(recording.tickCount.toString(), field(loaded, "tickCount"))
        assertEquals(recording.firstTick.value.toString(), field(loaded, "firstTick"))

        val verified = ok(call(index, "replay.verify"))
        assertEquals("true", field(verified, "bitExact"), verified)
        assertEquals(recording.tickCount.toString(), field(verified, "ticksCompared"))
    }

    @Test
    fun `seek, step and rewind land exactly and report the rebuild`() {
        val root = Files.createTempDirectory("replay-tools")
        val recording = write(root, "match")
        val index = index(root)
        ok(call(index, "replay.load", "name" to "match"))

        val landing = recording.firstTick.value + 150
        val sought = ok(call(index, "replay.seek", "tick" to landing.toString()))
        assertEquals(landing.toString(), field(sought, "tickAfter"))
        assertEquals("false", field(sought, "rebuilt"))
        assertEquals("true", field(sought, "matchesRecording"))

        val stepped = ok(call(index, "replay.step", "ticks" to "1"))
        assertEquals((landing + 1).toString(), field(stepped, "tickAfter"))
        assertEquals("1", field(stepped, "ticksStepped"))

        val back = ok(call(index, "replay.rewind", "ticks" to "51"))
        assertEquals((landing - 50).toString(), field(back, "tickAfter"))
        assertEquals("true", field(back, "rebuilt"))

        // The bisect position survives a verify, which builds its own world.
        ok(call(index, "replay.verify"))
        val info = ok(call(index, "replay.info"))
        assertEquals((landing - 50).toString(), field(info, "tick"))
    }

    @Test
    fun `a replay tool called with nothing loaded refuses by name`() {
        val index = index(Files.createTempDirectory("replay-tools"))
        // Each tool with the arguments it actually declares: the surface refuses an unknown one
        // with `unknown_argument` before the tool runs, so a shared argument map would assert
        // the wrong refusal and pass for the wrong reason.
        val calls = listOf<Pair<String, Array<Pair<String, String>>>>(
            "replay.info" to emptyArray(),
            "replay.verify" to emptyArray(),
            "replay.step" to arrayOf("ticks" to "1"),
            "replay.rewind" to arrayOf("ticks" to "1"),
            "replay.seek" to arrayOf("tick" to "5"),
        )
        for ((tool, args) in calls) {
            assertEquals(
                ReplayToolset.NOT_LOADED.id,
                failure(call(index, tool, *args)),
                "$tool answered something other than no_replay_loaded",
            )
        }
    }

    @Test
    fun `a missing recording, a corrupt one and an unreplayable one are three different refusals`() {
        val root = Files.createTempDirectory("replay-tools")
        write(root, "good")
        val index = index(root)

        assertEquals(
            ReplayToolset.NO_SUCH_RECORDING.id,
            failure(call(index, "replay.load", "name" to "absent")),
        )

        val corrupt = root.resolve("corrupt.udearep")
        Files.write(corrupt, ByteArray(80) { 'z'.code.toByte() })
        assertEquals(
            ReplayToolset.BAD_RECORDING.id,
            failure(call(index, "replay.load", "name" to "corrupt")),
        )

        // A recording this build cannot reproduce: same file, a host on a different seed.
        val otherBuild = ReplayToolModules.wire(
            ToolIndex.builder(),
            ReplayToolset(Host(root, identity.copy(rootSeed = identity.rootSeed + 1))),
        ).build()
        val refused = call(otherBuild, "replay.load", "name" to "good")
        assertEquals(ReplayToolset.REFUSED.id, failure(refused))
        assertTrue(
            "rootSeed" in (refused as AgentResult.Failed).error.message,
            "the refusal must name the field that differs: ${refused.error.message}",
        )
    }

    @Test
    fun `a name that escapes the recording root is refused as a bad argument`() {
        val root = Files.createTempDirectory("replay-tools")
        val index = index(root)
        assertEquals(
            dev.wildware.udea.agent.AgentErrorKind.BAD_ARGUMENT.id,
            failure(call(index, "replay.load", "name" to "../../../secrets")),
        )
    }

    @Test
    fun `verify names the first divergent tick when the recording was altered`() {
        val root = Files.createTempDirectory("replay-tools")
        val recording = write(root, "match")
        val at = recording.firstTick + 77L

        val recorder = ReplayRecorder(identity, schema, 1, "toy", "1")
        val slots = recording.newSampleSlots()
        for (offset in 0 until recording.tickCount) {
            val tick = recording.firstTick + offset.toLong()
            recording.samplesInto(tick, slots)
            if (tick == at) slots[0].setAxis(0, 0.75f, 0f)
            recorder.record(tick, slots, recording.hashAt(tick))
        }
        recorder.seal().writeTo(root.resolve("altered.udearep"))

        val index = index(root)
        ok(call(index, "replay.load", "name" to "altered"))
        val verified = ok(call(index, "replay.verify"))
        assertEquals("false", field(verified, "bitExact"), verified)
        assertEquals(at.value.toString(), field(verified, "firstDivergentTick"), verified)
        assertFalse(
            field(verified, "fieldsAvailable").toBoolean(),
            "this host supplies no baseline, so the report must say the fields are unnamed",
        )
    }

    private companion object {
        val FIRST: Tick = Tick(1)
    }
}
