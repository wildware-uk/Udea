package dev.wildware.udea.net.wire

import dev.wildware.udea.core.replication.ComponentTypeId
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.ComponentSchema
import dev.wildware.udea.core.snapshot.fleksComponentType
import dev.wildware.udea.net.bits.BitBufferWriter
import dev.wildware.udea.net.harness.Mover
import dev.wildware.udea.net.harness.MoverReplicator
import dev.wildware.udea.net.harness.NetTestComponents
import dev.wildware.udea.net.harness.Vitals
import dev.wildware.udea.net.harness.VitalsReplicator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A protocol mismatch is refused **by name**, which no hash alone can do.
 *
 * `PacketUtil.kt:122-129` streamed components in Fleks bag order with no type tag, so two peers
 * with different component sets connected happily and then filled `Transform.position` from
 * `Attributes` bytes. Every assertion here is about making that specific outcome unreachable.
 */
class ProtocolIdentityTest {

    private fun moverOnlyRegistry(): ComponentRegistry = ComponentRegistry(
        listOf(
            fleksComponentType(
                MoverReplicator,
                ComponentSchema.of(MoverReplicator, "Mover", MoverReplicator.kinds),
                Mover,
            ) { Mover() },
        ),
    )

    private fun renamedFieldRegistry(): ComponentRegistry {
        // Same field count, same types, same ids: only a name differs. This is exactly the change
        // that decodes cleanly on both sides and means something else on each.
        val renamed = object : dev.wildware.udea.core.replication.Replicator<Vitals> by VitalsReplicator {
            override val fieldNames: List<String> = listOf("hitPoints", "shielded")
        }
        return ComponentRegistry(
            listOf(
                fleksComponentType(
                    MoverReplicator,
                    ComponentSchema.of(MoverReplicator, "Mover", MoverReplicator.kinds),
                    Mover,
                ) { Mover() },
                fleksComponentType(
                    renamed,
                    ComponentSchema.of(renamed, "Vitals", VitalsReplicator.kinds),
                    Vitals,
                ) { Vitals() },
            ),
        )
    }

    @Test
    fun `identical builds agree`() {
        val a = ProtocolDescriptor.of(NetTestComponents.registry())
        val b = ProtocolDescriptor.of(NetTestComponents.registry())
        assertEquals(a.protoHash, b.protoHash)
        assertTrue(a.compareTo(b).isEmpty())
        ProtocolMismatchException.check(a, b)
    }

    @Test
    fun `a missing component is refused naming the component`() {
        val full = ProtocolDescriptor.of(NetTestComponents.registry())
        val partial = ProtocolDescriptor.of(moverOnlyRegistry())

        assertNotEquals(full.protoHash, partial.protoHash)
        val failure = assertFailsWith<ProtocolMismatchException> { ProtocolMismatchException.check(full, partial) }
        assertEquals(1, failure.differences.size, failure.message)
        assertTrue(failure.differences.single().contains("Vitals"), failure.message)
        assertTrue(failure.differences.single().contains("here and not on the peer"), failure.message)
    }

    @Test
    fun `a renamed field is refused naming the component`() {
        val ours = ProtocolDescriptor.of(NetTestComponents.registry())
        val theirs = ProtocolDescriptor.of(renamedFieldRegistry())

        assertNotEquals(
            ours.protoHash,
            theirs.protoHash,
            "renaming a field left the protocol hash unchanged; a client would decode it as the old field",
        )
        val failure = assertFailsWith<ProtocolMismatchException> { ProtocolMismatchException.check(ours, theirs) }
        assertTrue(failure.differences.single().contains("Vitals"), failure.message)
        assertTrue(failure.differences.single().contains("different fields"), failure.message)
    }

    @Test
    fun `the advert round trips and carries the names the refusal needs`() {
        val descriptor = ProtocolDescriptor.of(NetTestComponents.registry())
        val buffer = ByteArray(512)
        val writer = BitBufferWriter(buffer)
        descriptor.write(writer)

        val decoded = ProtocolDescriptor.read(writer.toReader())
        assertEquals(descriptor.protoHash, decoded.protoHash)
        assertEquals(descriptor.components, decoded.components)
        assertEquals(listOf("Mover", "Vitals"), decoded.components.map { it.typeName })
        assertEquals(listOf(ComponentTypeId(1), ComponentTypeId(2)), decoded.components.map { it.typeId })
    }

    @Test
    fun `an advert whose declared hash disagrees with its own body is refused`() {
        val descriptor = ProtocolDescriptor.of(NetTestComponents.registry())
        val buffer = ByteArray(512)
        val writer = BitBufferWriter(buffer)
        descriptor.write(writer)
        // Flip a bit of the leading protoHash. A peer that trusted the declared hash over the
        // body would accept a lie about its own contents.
        buffer[0] = (buffer[0].toInt() xor 1).toByte()

        assertFailsWith<IllegalArgumentException> { ProtocolDescriptor.read(writer.toReader()) }
    }
}
