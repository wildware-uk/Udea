package dev.wildware.moba.net

import dev.wildware.moba.MobaGame
import dev.wildware.moba.ability.CharacterAttributes
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.gas.AttributeTable
import dev.wildware.udea.net.wire.ProtocolDescriptor

/**
 * The one place `moba` and `udea-net` are joined.
 *
 * ## What this closes
 *
 * `MobaServer` said in its own KDoc that it bound "no network socket: `udea-net` is not wired
 * into `moba` yet", and that was true: the whole networking module - transports, wire format,
 * baselines, jitter buffer, twenty test files - was exercised only by its own tests against a
 * synthetic component registry. The game was a single process, which is a regression against
 * the old KryoNet example, which really did run two machines.
 *
 * Everything in this package is written against [dev.wildware.udea.net.transport.Transport] and
 * nothing below it. There is no branch anywhere here on *which* transport: a
 * [dev.wildware.udea.net.transport.LoopbackTransport] and a UDP socket are the same type to
 * [MobaHostSession] and [MobaClientSession], which is what lets the in-process session be the
 * thing every proof runs against and still be the same code a socket drives.
 *
 * ## What is deliberately not rebuilt
 *
 * The old `common/.../network` stack is the worked example of the mistakes:
 * `PacketUtil.kt:122-129` streamed components in Fleks bag order with no type tag and no length
 * prefix; `packets.kt:66` wrote a fixed 2048-byte buffer past a 1500-byte MTU;
 * `NetworkServerSystem.kt:110` sent every entity to every client every tick; and
 * `PacketUtil.kt:148` carried `// TODO validate the sender!`, so any client could fire any
 * ability on any entity. None of the four is reachable from here: the wire format is
 * self-describing and length-prefixed, the datagram is MTU-bounded and budgeted,
 * `ReplicationServer` packs by priority against a per-client baseline, and the *only* thing a
 * client-to-server datagram can carry is an ack and an `@InputCommand` - `ReplicationServer`
 * has no code path that writes a replicated component field.
 */
public object MobaNet {

    /**
     * The registry both ends of a session must share, built over [attributes].
     *
     * Delegates to [MobaGame.componentRegistry] rather than listing components again: a second
     * list is a second thing that can fall behind, and a component missing from the registry is
     * not partly replicated - it is **invisible** to capture and therefore never reaches a
     * client at all.
     */
    public fun registry(attributes: AttributeTable = CharacterAttributes.create().table): ComponentRegistry =
        MobaGame.componentRegistry(attributes)

    /**
     * This build's protocol, derived from [registry].
     *
     * A server and a client built from different sources produce different `protoHash`es and
     * refuse each other by name (`ProtocolMismatchException`), rather than decoding each
     * other's bytes as the wrong component - which is exactly what the old stack did.
     */
    public fun protocol(registry: ComponentRegistry): ProtocolDescriptor = ProtocolDescriptor.of(registry)

    /** The port `net.start_host` binds when the caller names none. Nobody has to choose one. */
    public const val DEFAULT_PORT: Int = 27015
}
