package dev.wildware.moba.net

import dev.wildware.udea.core.Tick
import dev.wildware.udea.net.input.MoveInput
import dev.wildware.udea.net.transport.NetConditions

/**
 * `moba.netproof`: one server, two clients, the **real** game, and three numbers that must match.
 *
 * ## What it proves, and why it is not a fixture
 *
 * The server here is `MobaGame.host(Headless)` with `level/test_level` loaded - the same
 * twenty-seven units in three factions that `runServer` simulates and `runClient` draws, with the
 * same abilities, the same match rules and the same corpses. Nothing is stubbed for the sake of
 * the measurement. The clients hold real Fleks worlds and seed no level at all: every entity in
 * them was put there by `Replicator.apply` off a datagram.
 *
 * Two runs:
 *
 *  1. **`NetConditions.PERFECT`** - the listen-server case, no latency and no loss.
 *  2. **150ms and 5% loss** (`NetConditions.TRELLO_8`: 9 ticks of latency at 60Hz, 2 of jitter,
 *     5% loss, 2% reorder), applied to every link in both directions.
 *
 * Each run reports, for the server and for both clients: how many `GameUnit`s the world holds, how
 * many entities carry replicated state, and [NetStateProbe.netHash] - a fold of every `@Net` field
 * of every entity in ascending id order.
 *
 * ## Each peer is checked at the tick it actually holds
 *
 * The server captures and sends at T; the earliest a client can have applied that is T + 1. So a
 * client is compared against the ring slot for **its own** `serverTick`, not the server's newest.
 * Comparing against the newest would be asserting that replication is instantaneous rather than
 * that it is correct, and under 150ms of latency it would fail on a perfectly healthy session.
 *
 * ## Why the MTU is raised for the measurement
 *
 * At the production 1200-byte MTU the packer defers whatever does not fit to the next tick, so a
 * client's world is legitimately a *mix* of server ticks and no two of the three would ever fold
 * to one number however healthy the session was. [MEASURE_MTU] is large enough that a whole tick
 * fits in one datagram - [Report.deferrals] is printed so that claim is checked rather than
 * asserted - which turns "did the same state arrive" into a question with a yes-or-no answer.
 * Nothing else changes: the same packer, the same delta encoding, the same baselines out of the
 * same ring, the same loss.
 *
 * `./gradlew :moba:runServer` is the dedicated server; this is the same simulation with two
 * clients bolted to it and an assertion at the end.
 */
public object MobaNetProof {

    /** Ticks of live battle before the reading is taken. */
    private const val BATTLE_TICKS: Int = 240

    /**
     * Datagram ceiling for the measurement.
     *
     * Large enough for a whole tick of a 27-unit level in one packet. Not what a link carries -
     * the game's own default is `LoopbackNetwork.DEFAULT_MTU`, 1200 - and see the class KDoc for
     * why a hash comparison needs it.
     */
    private const val MEASURE_MTU: Int = 16384

    /** Boots two sessions, prints both reports, and exits non-zero if either disagrees. */
    @JvmStatic
    public fun main(args: Array<String>) {
        val perfect = run("PERFECT (no latency, no loss)", NetConditions.PERFECT)
        val steady = run(
            "150ms, 5% loss, no jitter and no reorder",
            NetConditions(latencyTicks = 9, lossChance = 0.05f),
        )
        val lossy = run(
            "TRELLO_8 (150ms +/- 33ms, 5% loss, 2% reorder, both directions)",
            NetConditions.TRELLO_8,
        )
        println()
        println("[moba.netproof] perfect        units ${verdict(perfect)}")
        println("[moba.netproof] 150ms+5% loss  units ${verdict(steady)}")
        println("[moba.netproof] TRELLO_8       units ${verdict(lossy)}")
    }

    /**
     * Runs one whole session under [conditions] and reports it.
     *
     * @return true when every client's `@Net` hash equals the server's at the tick that client
     *   holds.
     */
    public fun run(label: String, conditions: NetConditions): Boolean {
        val session = MobaLoopbackSession(
            clientCount = 2,
            conditions = conditions,
            mtu = MEASURE_MTU,
            // Client 1 drives the level's player unit; client 2 is a spectator. Walking is a real
            // input going the only direction a client may send: a command, never a state.
            input = { client, tick ->
                if (client.peer.raw == 1) {
                    client.command(tick, moveX = walk(tick), moveY = 0f)
                } else {
                    null
                }
            },
        )
        return session.use { live ->
            live.step(BATTLE_TICKS)
            val report = report(live)
            println()
            println("=== $label ===")
            val covered = NetStateProbe.coveredComponents(live.server.registry)
            println("  mtu ${live.mtu}B; ${covered.size} replicated component types on the wire: " +
                covered.joinToString())
            println("  server   tick=${report.serverTick.value} units=${report.serverUnits} " +
                "entities=${report.serverEntities}")
            for (row in report.clients) {
                println("  ${row.peer}  tick=${row.tick.value} units=${row.units} " +
                    "entities=${row.entities} applied=${row.applied} stale=${row.stale}")
                println("           unitHash  client=${hex(row.unitHash)} " +
                    "server@t${row.tick.value}=${hex(row.serverUnitHash)} " +
                    (if (row.agrees) "MATCH" else "DIFFER"))
                println("           worldHash client=${hex(row.hash)} " +
                    "server@t${row.tick.value}=${hex(row.serverHash)} " +
                    (if (row.hash == row.serverHash) "MATCH" else "DIFFER"))
                for (line in row.differences) println("             ! " + line)
            }
            println("  budget deferrals ${report.deferrals} (must be 0 for the hash claim), " +
                "baseline recoveries ${report.recoveries}")
            // The input path, end to end and in one line. The player unit spawns at (0, 0)
            // (`Player.SPAWN_X`/`SPAWN_Y`); a soldier that is still there after 240 ticks of
            // `walk` means the client's `@InputCommand` never reached `PlayerControlSystem`.
            println("  player unit ${live.server.playerId} moved to " +
                "(${"%.2f".format(report.playerX)}, ${"%.2f".format(report.playerY)}) " +
                "from spawn (0.00, 0.00) -- the client's input, applied by the server")
            report.agrees
        }
    }

    /** Reads every peer at the tick that peer holds. */
    private fun report(session: MobaLoopbackSession): Report {
        val rows = session.clients.map { client ->
            val at = client.serverTick
            val state = client.state()
            ClientRow(
                peer = client.peer.toString(),
                tick = at,
                units = client.unitCount(),
                entities = NetStateProbe.entityCount(state.fields),
                hash = NetStateProbe.netHash(state.fields),
                serverHash = NetStateProbe.netHash(session.server.stateAt(at).fields),
                unitHash = NetStateProbe.unitHash(state.fields),
                serverUnitHash = NetStateProbe.unitHash(session.server.stateAt(at).fields),
                applied = client.applied,
                stale = client.staleDropped,
                differences = NetStateProbe.differences(
                    session.server.stateAt(at).fields,
                    state.fields,
                ),
            )
        }
        val newest = session.server.state()
        val player = session.server.host.world
        val position = with(player) {
            session.server.host.ctx[dev.wildware.udea.core.module.CoreModule.NET_IDS]
                .resolveOrNull(session.server.playerId)
                ?.getOrNull(dev.wildware.moba.Position)
        }
        return Report(
            serverTick = session.server.tick,
            serverUnits = NetStateProbe.unitCount(session.server.host.world),
            serverEntities = NetStateProbe.entityCount(newest.fields),
            deferrals = session.server.replication.budgetDeferrals,
            recoveries = session.server.replication.baselineRecoveries,
            playerX = position?.x ?: Float.NaN,
            playerY = position?.y ?: Float.NaN,
            clients = rows,
        )
    }

    /** A slow left-right walk, so the player's own unit is genuinely moving under replication. */
    private fun walk(tick: Tick): Float = if ((tick.value / 30L) % 2L == 0L) 1f else -1f

    private fun hex(value: Long): String = "0x" + java.lang.Long.toHexString(value).padStart(16, '0')

    /** One reading of a whole session. */
    public data class Report(
        public val serverTick: Tick,
        public val serverUnits: Int,
        public val serverEntities: Int,
        public val deferrals: Long,
        public val recoveries: Long,
        /** Where the player unit ended up. Proof that a client's command reached the simulation. */
        public val playerX: Float,
        public val playerY: Float,
        public val clients: List<ClientRow>,
    ) {
        /** True when every client folded the same `@Net` state the server did at that client's tick. */
        public val agrees: Boolean get() = clients.all { it.agrees }
    }

    /** One client's reading, beside the server's at the same tick. */
    public data class ClientRow(
        public val peer: String,
        public val tick: Tick,
        public val units: Int,
        public val entities: Int,
        public val hash: Long,
        public val serverHash: Long,
        public val unitHash: Long,
        public val serverUnitHash: Long,
        public val applied: Long,
        public val stale: Long,
        /** The first `@Net` fields this client and the server disagree about. Empty on a match. */
        public val differences: List<String> = emptyList(),
    ) {
        /**
         * True when this client holds exactly the server's `@Net` state **for the battle**.
         *
         * The units, and not the whole world, for the reason [NetStateProbe.unitHash] carries: a
         * recycled `NetId` index waits one acknowledgement by protocol, and `moba` recycles
         * indices every few ticks because projectiles are short-lived, so a whole-world fold is
         * very likely to be one entity short at any given tick without anything being wrong.
         */
        public val agrees: Boolean get() = unitHash == serverUnitHash
    }

    private fun verdict(agreed: Boolean): String = if (agreed) "AGREED" else "DISAGREED"

    /** Kept so a caller can mint a command without reaching for the wire type. */
    public fun idle(tick: Tick): MoveInput = MoveInput(0, tick, 0f, 0f, 0f, 0)
}
