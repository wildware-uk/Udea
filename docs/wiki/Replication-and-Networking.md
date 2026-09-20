# Replication and Networking

Udea's networking is server-authoritative. The server runs the real simulation; clients send only their **input**, never their state. The server captures the world into snapshots, compares each one with what a client last acknowledged, and sends only the fields that changed. Entities are named by `NetId` everywhere. A 16-bit protocol hash rides on every packet, so two different builds refuse each other by name instead of misreading each other's bytes.

A plain picture: a chess game by post. You never send "my knight is now on f3"; you send "I move my knight to f3", and the referee's board is the one that counts. The referee sends back only the squares that changed since the last letter you confirmed.

All of this lives in `udea-net` (`udea-net/src/commonMain/kotlin/dev/wildware/udea/net/`). It runs on `jvm`, `android` and `wasmJs`.

## Identity: `NetId`

A Fleks `Entity` is a slot in one world on one machine. Across the wire, entities are named by `NetId`: a 16-bit index plus an 8-bit generation, so a stale reference is detected rather than silently pointing at a new entity. [ECS and Components](ECS-and-Components) covers it in full. The rule is simple: nothing serialised, snapshotted or sent to an agent is typed `Entity`.

## Capture and diff

There is no dirty flag and no setter tracking. Replication works like this:

1. At the end of a tick the world is **captured**: each registered component's generated `Replicator` copies its fields into a columnar `FieldStore` slot in the `SnapshotRing`.
2. For each client, the server looks up the tick that client last acknowledged. That older snapshot is the client's **baseline**.
3. `Replicator.diff` compares the baseline slot with the new one and returns a `FieldMask` of changed fields. It is masked with `netMask`, so `@Sim` fields never go out.
4. `write` packs just those fields, quantised where a field has `@Q`.

The snapshot ring that backs rewind **is** the baseline store. There is no second per-client copy of the world. If a client's baseline has already fallen out of the ring, the server simply sends that entity in full. That is the whole of baseline-loss recovery.

The engine's default capture cadence is every third tick (`EngineConfig.snapshotIntervalTicks = 3`), 20Hz at 60 ticks a second. `moba`'s server session (`moba/game/src/commonMain/kotlin/dev/wildware/moba/net/MobaHostSession.kt`) forces a capture every tick instead, because a baseline a client has acknowledged must still be in the ring when the next delta is written against it. Its whole authoritative tick is:

```kotlin
public fun tick() {
    drainInput()                     // client commands into the jitter buffers
    host.run(1)                      // one simulation tick
    travel.captureNow()              // capture the world as it ended the tick
    solveVision()                    // fog of war, before packing
    replication.broadcast(state())   // one delta per client
}
```

`ReplicationServer` packs one datagram per client per broadcast. A `BandwidthBudget` (1200 bytes a packet by default) decides when to stop, and a Source-style `PriorityAccumulator` decides what goes first: each entity's priority grows by `base * ticksSinceSent * distanceWeight` until it is sent. A hero in view climbs fast; a minion across the map climbs slowly but always climbs, so nothing is starved forever. The distance weight comes from a `RelevancySet`.

## The packet header

Every datagram starts with a fixed header (`udea-net/src/commonMain/kotlin/dev/wildware/udea/net/wire/Packet.kt`):

```
u16 protoHash | u16 seq | u16 ack | u32 ackBits | u8 flags | varint serverTick | varint baselineTick
```

- `protoHash` is the continuous "are we still the same build" check.
- `seq`, `ack` and `ackBits` give both directions one shared view of what arrived.
- `serverTick` and `baselineTick` name exactly which two ring slots the payload was diffed between.
- A flag says whether the ack fields mean anything yet. Sequence 0 is a real sequence, so "nothing acknowledged" cannot be spelled `ack = 0`.

## Clients send input, and only input

`InputCommand` (`udea-net/src/commonMain/kotlin/dev/wildware/udea/net/input/InputCommand.kt`) is what the player *did*, never where the player *is*. A client that pushed its own position would own it, and there would be nothing to cheat past and nothing to predict. So the wire vocabulary is one-way by construction, and `NoClientStateUploadTest` checks that no client-to-server datagram carries a replicated field.

- **30Hz input.** `ReplicationClient` sends every second tick (`DEFAULT_INPUT_INTERVAL = 2`) against the 60Hz simulation.
- **Redundancy.** Every input packet carries the last three commands (`InputRing`), so one lost packet costs nothing.
- **The jitter buffer.** On the server, a `JitterBuffer` per client holds a few commands and consumes exactly one per tick. That makes the simulation's input rate a function of the tick rate, not of the network. It drops a command it has already queued or already simulated, and inserts the rest in sequence order, not arrival order.

In `moba` a consumed command becomes an `IntentSource`: the same seam a keyboard and an agent's `input.*` tools drive. So a remote player goes down the identical code path as a local one.

## Remote procedure calls

`@Rpc` (`udea-net/src/commonMain/kotlin/dev/wildware/udea/net/rpc/Rpc.kt`) marks a function as a remote call. `udea-codegen` generates the codec, the send helper and the **authority guard**. The guard is generated into `RpcDescriptor.receive`, ahead of the function body, so there is no path from a datagram to the body that skips it. An RPC declares its `direction`, `authority`, `reliability`, `relevancy` and an optional rate limit. With the default `authority = Server`, every client call is refused outright, so forgetting to think about the sender makes an RPC stricter, not looser.

`moba`'s ability activation is an `@Rpc(authority = OwnerPredicted)`: the generated guard checks that the calling connection owns the unit it names (`moba/game/src/commonMain/kotlin/dev/wildware/moba/ability/AbilityRpc.kt`).

## Prediction and interpolation

A client draws two kinds of entity differently.

**Its own character is predicted.** `LocalPrediction` (`udea-net/src/commonMain/kotlin/dev/wildware/udea/net/prediction/LocalPrediction.kt`) applies each local command at once through a `MoveModel`, the same step function the server runs. When the server's answer arrives for command `ackedSeq`, `reconcile` writes the server's position in and replays every newer command on top. On a clean link the replay reproduces the prediction exactly and the correction is zero. Under loss, a correction is real information, and it is smoothed: the drawn position carries a residual offset that decays over a few frames instead of snapping.

**Everyone else is interpolated.** `EntityInterpolation` draws remote entities a little in the past (`DEFAULT_DELAY_TICKS = 6`, which is 100ms: two 30Hz send intervals plus slack), so there is almost always a later sample to slide towards. Its render clock advances one tick per client tick and is only nudged towards the target, so it does not inherit the network's jitter. No wall clock is involved; everything is in ticks.

**Authority** comes from `@Net(authority = ...)`. Only `OwnerPredicted` fields may be predicted by a client. See [ECS and Components](ECS-and-Components).

**3D entities replicate too.** Every field of `Transform3D` is `@Net`, so a client sees a model move. Position and heading change tick to tick; scale and the other rotations are sent once in the spawn and again only when they change. `udea-render` draws them between ticks at the render alpha, as it does 2D positions. See [Rendering with Kool](Rendering-with-Kool).

## Relevancy and fog of war

A client is sent only what its team can see. `udea-net/src/commonMain/kotlin/dev/wildware/udea/net/relevancy/` holds the pieces:

- `VisionGrid` buckets this tick's vision sources into a uniform grid, so the cost tracks local density instead of `entities × sources × teams`. It allocates nothing once warm and iterates in a fixed order.
- `FogOfWar` decides visibility per team, with hysteresis and a linger window so a unit on the edge of vision does not flicker.
- `TeamVision` records *why* a team can see an entity (a scout sees it, it is one of ours, or we are inside the grace window), because those are three different bugs when a flicker is reported.

`@Net(visibility = OwnerOnly)` strips a field for everyone but its owner, on top of this.

## Transports

`Transport` is a small interface: `send`, `poll`, `stats` and `close`, plus the local peer id. The replication code never knows which one it is talking to.

| Transport | Where | Use |
|---|---|---|
| `LoopbackTransport` | common | In-memory, for tests and in-process client and server |
| `SimulatedTransport` | common | Latency, jitter, loss and reordering from a seed. `NetConditions.TRELLO_8` is 9 ticks of latency, 2 of jitter, 5% loss and 2% reordering |
| `UdpTransport` | `jvm` and `android` (the shared `socketMain` source set) | Real sockets, through Ktor |
| `WebSocketTransport` | common client; server in `jvmMain` | The only client transport on `wasmJs` |

`UdpTransport` is driven by the game loop and reads no wall clock: every decision (handshake, timeouts, keep-alives) is taken in ticks on the thread that calls it.

### The UDP handshake

Connecting is a three-way exchange (`UdpPacketType` in `udea-net/src/commonMain/kotlin/dev/wildware/udea/net/transport/UdpPacket.kt`):

1. The client sends a `ConnectionRequest`, padded to the MTU, with its protocol hash.
2. The server answers with a `ConnectionChallenge` carrying a token. The token is an HMAC of the client's address, a salt and an expiry, keyed by `ConnectionSecret`. So the server remembers nothing about an unverified peer.
3. The client returns the token in a `ConnectionResponse` from the same address. The server verifies it by recomputing it, and answers `ConnectionAccepted` or `ConnectionDenied` with a reason.

Two protections come from this shape. A spoofed-address flood costs the server no memory. And `AntiAmplification` makes sure an unverified peer is never sent more bytes than it just sent, so the server cannot be used to amplify an attack on someone else. A mismatched protocol hash is denied with a reason, not ignored.

## The protocol hash and the lock

Two builds can only talk if they agree on every replicated component: its id, its name, its field names in order, and which fields are replicated. `ProtocolDescriptor` (`udea-net/src/commonMain/kotlin/dev/wildware/udea/net/wire/Protocol.kt`) folds all of that into `protoHash`. When the hashes differ, `compareTo` turns the difference into sentences that name the components, and `ProtocolMismatchException` carries them. That replaces the old engine's failure, where a client with a different component set parsed one component's bytes as another's and carried on.

Component ids come from the sorted fully qualified names, so they are pinned per module in a checked-in `net-protocol.lock`. `udeaCheckProtocolLock` fails the build when the generated protocol drifts from the lock; `udeaWriteProtocolLock` rewrites it on purpose. Both files that encode that ordering, the lock and `udea-codegen/src/test/resources/expected-generated-hashes.txt`, are regenerated by their tasks and never edited by hand. See [Build and Verification](Build-and-Verification).

## Finding a desync

`DesyncReport` compares server and client state **field by field**, not byte by byte. Both sides run the same replicators over the same `FieldStore` layout, so a report names the `NetId`, the component and the field, with both values. Only `@Net` fields are compared, since `@Sim` fields never reach a client.

## Running it in `moba`

| Command | What it shows |
|---|---|
| `sh gradlew :moba:desktop:runNetProof` | One server and two clients over the real level, on a perfect link, on 150ms with 5% loss, and on `TRELLO_8`. It prints three world hashes that must agree |
| `sh gradlew :moba:desktop:runUdpProof` | Three OS processes over real UDP, lossy leg included. Not on `check`, because it measures wall-clock timing across processes |
| `sh gradlew :moba:desktop:runServer` | A headless server with no GPU and no agent surface |
| `sh gradlew :moba:desktop:runClient` | A window. Its modes are `local`, `listen`, `host [port]` and `join <host[:port]>` |

On this project's shared Linux box the wrapper is run as `sh gradlew` rather than `./gradlew`, because the checked-in wrapper has no executable bit.

## See also

- [ECS and Components](ECS-and-Components)
- [Tick Model and Determinism](Tick-Model-and-Determinism)
- [Replay and Time Travel](Replay-and-Time-Travel)
- [Abilities (GAS)](Abilities-GAS)
- [Build and Verification](Build-and-Verification)
