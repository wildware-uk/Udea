package dev.wildware.udea.net.wire

import dev.wildware.udea.core.replication.BitReader
import dev.wildware.udea.core.replication.BitWriter
import dev.wildware.udea.core.replication.ComponentTypeId
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.net.bits.readVarInt
import dev.wildware.udea.net.bits.writeVarInt

/**
 * One component type as the protocol sees it.
 *
 * [hash] folds everything two peers must agree on before a byte of that component can be
 * decoded: its id, its name, its lowered field names *in order*, and which of those fields are
 * replicated. Two builds that disagree about any of it produce different hashes.
 *
 * Field *names* are in the hash rather than only the count, because renaming `x` to `positionX`
 * while keeping the count is exactly the change that decodes cleanly and means something else.
 */
public data class ComponentDescriptor(
    public val typeId: ComponentTypeId,
    public val typeName: String,
    public val hash: Int,
)

/**
 * A peer's whole view of the wire protocol, and the thing a connection is refused over.
 *
 * ## Why the handshake carries descriptors and not just [protoHash]
 *
 * A 16-bit hash on every packet tells a receiver *that* the peers disagree; it can never tell
 * it *what* about. Issue #106's acceptance is a refusal that names the differing component
 * FQNs, and no amount of care with a hash produces a name. So the connect handshake sends the
 * component list — id, name, per-component hash — once, and [compareTo] turns a mismatch into
 * a list of named differences. After that, [protoHash] rides every packet as the cheap
 * continuous check that nothing swapped underneath.
 *
 * This is the direct fix for the old stack's failure mode. `PacketUtil.kt:122-129` streamed
 * components in Fleks bag order with no type tag at all, so a client with a different component
 * set did not fail to connect and did not fail to parse — it filled `Transform.position` from
 * `Attributes` bytes and carried on.
 */
public class ProtocolDescriptor(

    /** Every component type, ascending by [ComponentTypeId]. */
    public val components: List<ComponentDescriptor>,
) {

    init {
        for (index in 1 until components.size) {
            require(components[index - 1].typeId.raw < components[index].typeId.raw) {
                "components must be ascending by type id; ${components[index - 1].typeId} " +
                    "is followed by ${components[index].typeId}"
            }
        }
    }

    /**
     * The 16 bits that ride every packet header.
     *
     * Folded from the 32-bit whole-protocol hash rather than truncated, so a difference in the
     * high half cannot cancel out and every input bit reaches the output.
     */
    public val protoHash: Int = fold16(wholeHash(components))

    /**
     * Every way [other] differs from this, as sentences naming component types.
     *
     * Empty when the two agree. Ordered by type id so two peers produce the same report.
     */
    public fun compareTo(other: ProtocolDescriptor): List<String> {
        val differences = ArrayList<String>()
        val mine = components.associateBy { it.typeId.raw }
        val theirs = other.components.associateBy { it.typeId.raw }
        for (id in (mine.keys + theirs.keys).sorted()) {
            val a = mine[id]
            val b = theirs[id]
            when {
                a == null -> differences += "${b!!.typeName} (${b.typeId}) is on the peer and not here"
                b == null -> differences += "${a.typeName} (${a.typeId}) is here and not on the peer"
                a.typeName != b.typeName ->
                    differences += "${a.typeId} is ${a.typeName} here and ${b.typeName} on the peer"
                a.hash != b.hash ->
                    differences += "${a.typeName} (${a.typeId}) has different fields on each side"
            }
        }
        return differences
    }

    /** Writes the handshake advert: id, name and hash for every component. */
    public fun write(out: BitWriter) {
        out.writeBits(protoHash, PROTO_HASH_BITS)
        out.writeVarInt(components.size)
        for (component in components) {
            out.writeVarInt(component.typeId.raw)
            out.writeAsciiString(component.typeName)
            out.writeInt(component.hash)
        }
    }

    override fun toString(): String =
        "ProtocolDescriptor(${components.size} components, protoHash=0x${protoHash.toString(16)})"

    public companion object {

        /** Width of [protoHash] on the wire. */
        public const val PROTO_HASH_BITS: Int = 16

        private const val FNV_OFFSET_BASIS: Int = -2128831035
        private const val FNV_PRIME: Int = 16777619

        /**
         * The protocol this simulation speaks, derived from the one registry it captures with.
         *
         * Taking the registry rather than a loose replicator list is what stops a second id
         * space existing: `ComponentRegistry` is already the sorted-by-type-id assignment that
         * snapshot capture, the world hash and `net-components.lock` all agree on.
         */
        public fun of(registry: ComponentRegistry): ProtocolDescriptor {
            val components = ArrayList<ComponentDescriptor>(registry.size)
            for (index in 0 until registry.size) {
                val type = registry.typeAt(index)
                val schema = type.schema
                var hash = FNV_OFFSET_BASIS
                hash = mix(hash, schema.typeName)
                hash = mix(hash, schema.typeId.raw)
                hash = mix(hash, schema.fieldCount)
                for (field in 0 until schema.fieldCount) hash = mix(hash, schema.nameOf(field))
                hash = mix(hash, MaskOps.word(type.replicator.netMask, 0))
                // Lifetime is in the hash because it changes what a delta may contain: two
                // peers disagreeing about whether a field is create-only disagree about the
                // meaning of every update packet carrying that component (issue #114).
                hash = mix(hash, MaskOps.word(LifetimePolicy.createOnlyMask(type.replicator), 0))
                components += ComponentDescriptor(schema.typeId, schema.typeName, hash)
            }
            return ProtocolDescriptor(components)
        }

        /** Reads an advert written by [write]. */
        public fun read(src: BitReader): ProtocolDescriptor {
            val declaredHash = src.readBits(PROTO_HASH_BITS)
            val count = src.readVarInt()
            require(count in 0..MAX_COMPONENTS) {
                "protocol advert declares $count components, over the $MAX_COMPONENTS limit"
            }
            val components = ArrayList<ComponentDescriptor>(count)
            repeat(count) {
                val typeId = ComponentTypeId(src.readVarInt())
                val typeName = src.readAsciiString()
                components += ComponentDescriptor(typeId, typeName, src.readInt())
            }
            val descriptor = ProtocolDescriptor(components)
            require(descriptor.protoHash == declaredHash) {
                "protocol advert declares protoHash 0x${declaredHash.toString(16)} but its " +
                    "component list hashes to 0x${descriptor.protoHash.toString(16)}"
            }
            return descriptor
        }

        /** As many components as a `varint` count is allowed to claim before it is nonsense. */
        public const val MAX_COMPONENTS: Int = 4096

        private fun wholeHash(components: List<ComponentDescriptor>): Int {
            var hash = FNV_OFFSET_BASIS
            for (component in components) {
                hash = mix(hash, component.typeId.raw)
                hash = mix(hash, component.hash)
            }
            return hash
        }

        private fun fold16(hash: Int): Int = (hash ushr 16) xor (hash and 0xFFFF)

        private fun mix(hash: Int, value: Int): Int {
            var result = hash
            for (shift in 0 until 4) {
                result = (result xor ((value ushr (shift * 8)) and 0xFF)) * FNV_PRIME
            }
            return result
        }

        private fun mix(hash: Int, value: Long): Int =
            mix(mix(hash, (value and 0xFFFF_FFFFL).toInt()), (value ushr 32).toInt())

        private fun mix(hash: Int, value: String): Int {
            var result = hash
            for (character in value) result = (result xor (character.code and 0xFF)) * FNV_PRIME
            return mix(result, value.length)
        }
    }
}

/**
 * The connection was refused because the two peers do not speak the same protocol.
 *
 * Typed, and carrying [differences], because the alternative is the old behaviour: connect
 * anyway and misparse. Every element of [differences] names a component type.
 */
public class ProtocolMismatchException(
    public val localHash: Int,
    public val remoteHash: Int,
    public val differences: List<String>,
) : RuntimeException(
    buildString {
        append("protocol mismatch: local 0x").append(localHash.toString(16))
        append(" vs peer 0x").append(remoteHash.toString(16))
        if (differences.isEmpty()) {
            append(" (component lists agree; the hash itself is the disagreement)")
        } else {
            for (difference in differences) append("\n  - ").append(difference)
        }
    },
) {

    public companion object {

        /**
         * Accepts [remote] against [local], or throws naming what differs.
         *
         * Both directions are reported, not just "the peer has an extra component": a client
         * missing a component the server has and a client having one the server does not are
         * different mistakes with different fixes, and telling them apart is the whole point of
         * sending names.
         */
        public fun check(local: ProtocolDescriptor, remote: ProtocolDescriptor) {
            if (local.protoHash == remote.protoHash && local.compareTo(remote).isEmpty()) return
            throw ProtocolMismatchException(local.protoHash, remote.protoHash, local.compareTo(remote))
        }
    }
}

/**
 * Writes [value] as a length-prefixed run of 8-bit characters.
 *
 * ASCII only, and it throws rather than mangling: a component FQN outside ASCII would encode
 * to bytes that decode to a different name, which in a *protocol identity* check is the one
 * failure that must never be silent.
 */
public fun BitWriter.writeAsciiString(value: String) {
    writeVarInt(value.length)
    for (character in value) {
        require(character.code in 0..127) {
            "'$value' is not ASCII; the protocol advert cannot encode U+${character.code.toString(16)}"
        }
        writeBits(character.code, 8)
    }
}

/** Reads a string written by [writeAsciiString]. */
public fun BitReader.readAsciiString(): String {
    val length = readVarInt()
    require(length in 0..MAX_STRING_LENGTH) {
        "string length $length is outside 0..$MAX_STRING_LENGTH"
    }
    val builder = StringBuilder(length)
    repeat(length) { builder.append(readBits(8).toChar()) }
    return builder.toString()
}

/** Longest string the advert will decode. A guard against a hostile length prefix. */
private const val MAX_STRING_LENGTH: Int = 512
