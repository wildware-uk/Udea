package dev.wildware.udea.net.wire

import dev.wildware.udea.core.Tick
import dev.wildware.udea.net.bits.BitBufferWriter
import dev.wildware.udea.net.harness.NetTestComponents
import dev.wildware.udea.net.harness.NetTestWorld
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The packet layout, pinned as hex.
 *
 * A round-trip test cannot catch a layout change: swap two fields, widen the op field, reorder the
 * component list, and write and read still agree with each other while disagreeing with every
 * build that came before. The wire format is a cross-version contract, so it is checked against
 * bytes on disk.
 *
 * The fixture deliberately contains a **create and then a delta of the same entity**, so the
 * `@Net(lifetime = OnCreate)` field is visible in the first and provably absent from the second
 * (issue #114): a reviewer can read the difference in byte counts without running anything.
 *
 * Regenerate deliberately with `./gradlew :udea-net:test -Dupdate.goldens=true`, and treat a diff
 * in the regenerated file as a protocol break needing a `net-protocol.lock` bump.
 */
class SnapshotLayoutGoldenTest {

    @Test
    fun `the fixed packet sequence produces the recorded bytes`() {
        val actual = renderFixture()
        val golden = File(File(System.getProperty("udea.projectDir") ?: "."), GOLDEN_PATH)

        if (System.getProperty("update.goldens") == "true") {
            golden.parentFile.mkdirs()
            golden.writeText(actual)
            println("updated golden ${golden.absolutePath}")
        }
        if (!golden.exists()) {
            fail(
                "missing golden ${golden.absolutePath}. Regenerate with " +
                    "./gradlew :udea-net:test -Dupdate.goldens=true",
            )
        }
        assertEquals(
            golden.readText().replace("\r\n", "\n"),
            actual,
            "the packet layout changed. If that was deliberate it is a protocol break: " +
                "regenerate with ./gradlew :udea-net:test -Dupdate.goldens=true",
        )
    }

    @Test
    fun `the delta is strictly smaller than the create it follows`() {
        // Cheap, and it is what makes the golden readable: the create carries every @Net field
        // including the create-only one, the delta carries one changed float.
        val rendered = renderFixture().lines()
        val createBytes = rendered.first { it.startsWith("create bytes=") }.substringAfter('=').toInt()
        val deltaBytes = rendered.first { it.startsWith("delta bytes=") }.substringAfter('=').toInt()
        assertTrue(deltaBytes < createBytes, "a delta of one field ($deltaBytes B) was not smaller than the create ($createBytes B)")
    }

    private fun renderFixture(): String {
        val registry = NetTestComponents.registry()
        val world = NetTestWorld(registry = registry)
        val netId = world.spawn(x = 1.5f, y = -2.25f, teamId = 3)
        val first = world.captureTick()

        world.mover(netId).x = 9.75f
        world.mover(netId).teamId = 7
        world.vitals(netId).hp = 42
        val second = world.captureTick()

        val writer = SnapshotWriter(registry)

        val createBuffer = ByteArray(256)
        val createOut = BitBufferWriter(createBuffer)
        header(Tick(1), hasBaseline = false).write(createOut)
        val createFrames = FrameWriter(createOut)
        val createPayload = createFrames.beginMessage(MessageType.Snapshot)
        writer.begin()
        writer.writeCreate(createPayload, first.fields, first.fields.rowOf(netId), recipientOwnsEntity = true)
        writer.end(createPayload)
        createFrames.endMessage()

        val deltaBuffer = ByteArray(256)
        val deltaOut = BitBufferWriter(deltaBuffer)
        header(Tick(2), hasBaseline = true).write(deltaOut)
        val deltaFrames = FrameWriter(deltaOut)
        val deltaPayload = deltaFrames.beginMessage(MessageType.Snapshot)
        writer.begin()
        writer.writeUpdate(
            deltaPayload,
            second.fields,
            second.fields.rowOf(netId),
            first.fields,
            first.fields.rowOf(netId),
            recipientOwnsEntity = true,
        )
        writer.end(deltaPayload)
        deltaFrames.endMessage()

        return buildString {
            append("protoHash=").append(ProtocolDescriptor.of(registry).protoHash.toString(16)).append('\n')
            append("create bytes=").append(createFrames.byteLength).append('\n')
            append(createBuffer.toHex(createFrames.byteLength)).append('\n')
            append("delta bytes=").append(deltaFrames.byteLength).append('\n')
            append(deltaBuffer.toHex(deltaFrames.byteLength)).append('\n')
        }
    }

    private fun header(tick: Tick, hasBaseline: Boolean) = PacketHeader(
        protoHash = ProtocolDescriptor.of(NetTestComponents.registry()).protoHash,
        seq = 1,
        ack = 0,
        ackBits = 0,
        serverTick = tick,
        baselineTick = if (hasBaseline) Tick(1) else Tick.ZERO,
        hasBaseline = hasBaseline,
    )

    private fun ByteArray.toHex(length: Int): String =
        (0 until length).joinToString(separator = "") { "%02x".format(this[it]) }
            .chunked(32)
            .joinToString(separator = "\n")

    private companion object {

        const val GOLDEN_PATH: String = "src/test/resources/goldens/snapshot-layout.txt"
    }
}
