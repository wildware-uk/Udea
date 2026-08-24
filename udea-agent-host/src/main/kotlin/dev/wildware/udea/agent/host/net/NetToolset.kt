package dev.wildware.udea.agent.host.net

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentToolArg
import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.agent.Json
import dev.wildware.udea.agent.ToolModule
import dev.wildware.udea.agent.host.ToolSchema
import dev.wildware.udea.net.transport.NetConditions
import kotlin.reflect.KClass

/** Error kinds the `net.*` tools answer with. Open by design; see `AgentErrorKind`. */
public object NetErrors {

    /**
     * No session has been spawned in this process, so there is nothing to drive.
     *
     * Its own kind rather than a `bad_argument`, because the remedy is a different call
     * (`net.spawn_session`) rather than a different value, and an agent that could not tell the
     * two apart would retry the same call with a tweaked argument forever.
     */
    public val NO_NET_SESSION: AgentErrorKind = AgentErrorKind("no_net_session")

    /** An argument named a peer this session does not have. */
    public val NO_SUCH_PEER: AgentErrorKind = AgentErrorKind("no_such_peer")

    /** A session was asked for with values it cannot be built from. */
    public val BAD_SESSION: AgentErrorKind = AgentErrorKind("bad_session")
}

/**
 * `net.*`: one agent, both ends of a multiplayer session.
 *
 * ## What this closes
 *
 * `MobaServer`'s own KDoc said "no network socket: udea-net is not wired into moba yet", and
 * `SessionEndToEndTest` said "there is no `net.start_host` / `net.spawn_session` tool, so a
 * launcher cannot yet start this pair from the agent surface". `:udea-net` was real, tested and
 * unreachable: every one of its twenty test files drove it from Kotlin, and no agent could call
 * any of it. This is the join. An agent stands a session up, holds a direction on one client,
 * steps, and reads the moved position back from the **server** - which is the observable form of
 * "input travelled and authority held".
 *
 * ## One session per process, replaced rather than accumulated
 *
 * [spawnSession] drops whatever session was there and builds a new one. A pool of sessions keyed
 * by a handle would need a handle in every argument list, an eviction rule and a leak story, to
 * serve a case nobody has: an agent debugging a desync is debugging *one* desync. Spawning again
 * is how a run is reset, and it is the same call, so there is nothing extra to learn.
 *
 * ## Everything below is `:udea-net`, unchanged
 *
 * The desync report is `DesyncReport.compare`, not a second implementation of it; the link is
 * `SimulatedTransport`, so latency, jitter and loss are seeded draws and a failure reproduces
 * from its seed; the baselines come out of the `SnapshotRing` that is also the rewind buffer,
 * because spec 3.1 says there is one of those and not two.
 */
public class NetToolset {

    /**
     * The live session, or null before the first spawn.
     *
     * Mutated only from the simulation thread, inside a barrier drain, which is where every tool
     * runs - so no synchronisation, and none would help: a second thread mutating a session
     * mid-tick is the torn state the barrier exists to prevent, not a locking problem.
     */
    private var session: NetSession? = null

    /** The session, for a host or a test that wants to assert on it directly. */
    public val current: NetSession? get() = session

    /** Stands up a server and [clients] clients in this JVM, replacing any previous session. */
    public fun spawnSession(
        clients: Int,
        seed: Long,
        latencyMs: Int,
        jitterMs: Int,
        loss: Float,
        visionRadius: Float = 0f,
    ): AgentResult {
        session?.close()
        session = null
        val spawned = try {
            NetSession(clients, seed, visionRadius = visionRadius).also {
                it.conditions(client = null, latencyMs = latencyMs, jitterMs = jitterMs, loss = loss)
            }
        } catch (e: IllegalArgumentException) {
            return AgentResult.failed(NetErrors.BAD_SESSION, e.message ?: "the session arguments were refused")
        }
        session = spawned
        return AgentResult.ok { describe(spawned) }
    }

    /** Reshapes one link, or every link when [client] is absent. */
    public fun setConditions(client: Int?, latencyMs: Int, jitterMs: Int, loss: Float): AgentResult =
        withSession { live ->
            val applied = live.conditions(client, latencyMs, jitterMs, loss)
            AgentResult.ok {
                put("ok", true)
                put("client", client ?: 0)
                put("appliedToAll", client == null)
                renderConditions(applied, latencyMs, jitterMs)
            }
        }

    /**
     * Sets what [client] is holding on its stick.
     *
     * Held, not fired: see [HeldInput]. The reply reports the axes **after** the wire's
     * quantisation is accounted for in the description, so an agent that sent `1.0` and reads a
     * position built from `0.996` is not surprised by it.
     */
    public fun input(client: Int, moveX: Float, moveY: Float, aim: Float, buttons: Int): AgentResult =
        withSession { live ->
            withPeer(live, client) {
                live.hold(client, moveX, moveY, aim, buttons)
                AgentResult.ok {
                    put("ok", true)
                    put("client", client)
                    put("moveX", moveX)
                    put("moveY", moveY)
                    put("aim", aim)
                    put("buttons", buttons)
                    put("tick", live.tick.value)
                }
            }
        }

    /** Runs [ticks] ticks of the whole session: both ends and every link. */
    public fun step(ticks: Int): AgentResult = withSession { live ->
        val reached = try {
            live.step(ticks)
        } catch (e: IllegalArgumentException) {
            return@withSession AgentResult.failed(
                AgentErrorKind.BAD_ARGUMENT,
                e.message ?: "the tick count was refused",
            )
        }
        AgentResult.ok {
            put("ok", true)
            put("ticks", ticks)
            put("tick", reached.value)
            put("arenaTick", live.arena.tick.value)
        }
    }

    /** The authoritative state of [client]'s avatar, read on the server. */
    public fun serverState(client: Int): AgentResult = withSession { live ->
        withPeer(live, client) { AgentResult.ok { renderPeer(live.serverState(client)) } }
    }

    /** What [client] itself believes, which is the server's answer as of its last applied packet. */
    public fun clientState(client: Int): AgentResult = withSession { live ->
        withPeer(live, client) { AgentResult.ok { renderPeer(live.clientState(client)) } }
    }

    /** Every replicated field on which [client] disagrees with the server. */
    public fun desyncReport(client: Int): AgentResult = withSession { live ->
        withPeer(live, client) {
            val measured = live.desync(client)
            AgentResult.ok {
                put("ok", true)
                put("client", client)
                put("clientTick", measured.clientTick.value)
                put("comparedAtTick", measured.comparedAt?.value ?: -1L)
                put("converged", measured.converged)
                put("fieldCount", measured.fields.size)
                if (measured.comparedAt == null) {
                    put(
                        "note",
                        "the server snapshot for tick ${measured.clientTick.value} has aged out " +
                            "of the ring, so nothing could be compared; step fewer ticks between " +
                            "calls or read the report sooner",
                    )
                }
                arr("fields") {
                    for (field in measured.fields.take(MAX_REPORTED_FIELDS)) {
                        element {
                            put("netId", field.netId.toString())
                            put("component", field.componentName)
                            put("field", field.fieldName)
                            put("server", field.serverValue?.toString())
                            put("client", field.clientValue?.toString())
                        }
                    }
                }
                if (measured.fields.size > MAX_REPORTED_FIELDS) {
                    put("fieldsTruncated", true)
                }
            }
        }
    }

    /** Ends the session and releases its links. */
    public fun closeSession(): AgentResult = withSession { live ->
        live.close()
        session = null
        AgentResult.ok {
            put("ok", true)
            put("closed", true)
            put("tick", live.tick.value)
        }
    }

    override fun toString(): String = "NetToolset(${session ?: "no session"})"

    private inline fun withSession(body: (NetSession) -> AgentResult): AgentResult {
        val live = session ?: return AgentResult.failed(
            NetErrors.NO_NET_SESSION,
            "no multiplayer session is running in this process; call net.spawn_session first",
        )
        return body(live)
    }

    private inline fun withPeer(live: NetSession, client: Int, body: () -> AgentResult): AgentResult {
        if (client !in 1..live.clients) {
            return AgentResult.failed(
                NetErrors.NO_SUCH_PEER,
                "this session has clients 1..${live.clients}; there is no client $client",
            )
        }
        return body()
    }

    private fun Json.describe(live: NetSession) {
        put("ok", true)
        put("clients", live.clients)
        put("seed", live.seed)
        put("tick", live.tick.value)
        put("protoHash", live.protocol.protoHash)
        put("visionRadius", live.visionRadius)
        put("fog", live.fog != null)
        arr("peers") {
            element { put("peer", "server") }
            for (end in live.ends) element { put("peer", end.peer.toString()) }
        }
    }

    private fun Json.renderConditions(applied: NetConditions, latencyMs: Int, jitterMs: Int) {
        put("latencyMs", latencyMs)
        put("jitterMs", jitterMs)
        put("latencyTicks", applied.latencyTicks)
        put("jitterTicks", applied.jitterTicks)
        put("loss", applied.lossChance)
    }

    private fun Json.renderPeer(peer: PeerSnapshot) {
        put("ok", true)
        put("peer", peer.peer)
        put("tick", peer.tick.value)
        put("x", peer.x ?: Float.NaN)
        put("y", peer.y ?: Float.NaN)
        put("owner", peer.owner ?: -1)
        put("present", peer.x != null)
        put("inputsSent", peer.inputsSent)
        put("inputsApplied", peer.inputsApplied)
        obj("traffic") {
            put("packetsSent", peer.stats.packetsSent)
            put("bytesSent", peer.stats.bytesSent)
            put("packetsReceived", peer.stats.packetsReceived)
            put("bytesReceived", peer.stats.bytesReceived)
            put("packetsDropped", peer.stats.packetsDropped)
        }
    }

    private companion object {

        /**
         * How many disagreeing fields one report prints.
         *
         * The digest is byte-budgeted and a whole-world desync would fill it with rows an agent
         * cannot act on faster than it can act on the first thirty-two. The count is reported
         * separately and is never truncated, so "how bad" and "what exactly" stay separable.
         */
        const val MAX_REPORTED_FIELDS: Int = 32
    }
}

// --- the tool declarations -----------------------------------------------------------------
//
// Hand-written [AgentToolDef]s for the same reason `CompareArtifactsTool` is: this module runs
// no KSP round, and adding one to generate seven declarations would put a build-time dependency
// on the debug host for no behaviour. The shape is what the processor emits, and `inputSchema`
// is derived from `args` through `ToolSchema` rather than written a second time beside them.

private val CLIENT_ARG = AgentToolArg(
    name = "client",
    type = "integer",
    description = "Which client, one-based: 1 is the first client the session stood up.",
    required = true,
    default = null,
)

/** `net.spawn_session`. */
public object NetSpawnSessionTool : AgentToolDef<NetToolset> {

    override val name: String = "net.spawn_session"

    override val description: String =
        "Stand up an authoritative server and N clients inside this one process, replacing any " +
            "session already running. Reach for it first whenever you need to debug replication, " +
            "prediction or a desync: it gives you both ends of a match at once, so you can send " +
            "input on a client and assert authoritative state on the server without juggling " +
            "ports. No sockets and no threads - the link is simulated and every latency, jitter " +
            "and loss draw comes from the seed, so a failure reproduces exactly. Returns the " +
            "peer list, the seed and the protocol hash."

    override val args: List<AgentToolArg> = listOf(
        AgentToolArg(
            name = "clients",
            type = "integer",
            description = "How many clients to stand up, 1..8. Two is the usual number for a desync hunt.",
            required = false,
            default = "2",
        ),
        AgentToolArg(
            name = "seed",
            type = "integer",
            description = "Root seed for the arena and for every link's loss and jitter draws. " +
                "Reuse a seed to reproduce a failure exactly.",
            required = false,
            default = "22026",
        ),
        AgentToolArg(
            name = "latency_ms",
            type = "integer",
            description = "One-way link delay in milliseconds, applied to every link at spawn. " +
                "Rounded to whole simulation ticks.",
            required = false,
            default = "0",
        ),
        AgentToolArg(
            name = "jitter_ms",
            type = "integer",
            description = "Maximum extra delay on top of latency_ms, drawn per datagram from the seed.",
            required = false,
            default = "0",
        ),
        AgentToolArg(
            name = "loss",
            type = "number",
            description = "Probability 0..1 that a datagram is discarded outright, e.g. 0.05 for 5%.",
            required = false,
            default = "0",
        ),
        AgentToolArg(
            name = "vision_radius",
            type = "number",
            description = "Sight radius in world units, turning on per-team fog of war. Zero, the " +
                "default, means every client is told about every entity. Above zero the clients " +
                "are split alternately across two teams, spawn out of each other's sight, and the " +
                "server stops serialising what a client cannot see - which is what net.relevancy " +
                "and net.assert_not_visible report on.",
            required = false,
            default = "0",
        ),
    )

    override val inputSchema: String = ToolSchema.of(args)

    override val owner: KClass<*> = NetToolset::class

    override fun invoke(receiver: NetToolset, command: AgentCommand): Any? = receiver.spawnSession(
        clients = command.int("clients", DEFAULT_CLIENTS),
        seed = command.long("seed", DEFAULT_SEED),
        latencyMs = command.int("latency_ms", 0),
        jitterMs = command.int("jitter_ms", 0),
        loss = command.float("loss", 0f),
        visionRadius = command.float("vision_radius", 0f),
    )

    /** Two ends and an authority: the smallest session a desync can exist in. */
    private const val DEFAULT_CLIENTS: Int = 2

    /** An arbitrary but fixed default, so an unseeded session is still reproducible. */
    private const val DEFAULT_SEED: Long = 22_026L
}

/** `net.set_conditions`. */
public object NetSetConditionsTool : AgentToolDef<NetToolset> {

    override val name: String = "net.set_conditions"

    override val description: String =
        "Make the network as bad as you need it, mid-session: one-way latency, jitter and packet " +
            "loss, on one client's link or on every link at once. Reach for it to reproduce a " +
            "field report - 200ms and 10% loss, say - and then read net.desync_report to see " +
            "whether the clients still converge. Milliseconds are rounded to whole simulation " +
            "ticks and the resolved tick counts come back in the reply."

    override val args: List<AgentToolArg> = listOf(
        AgentToolArg(
            name = "client",
            type = "integer",
            description = "Which client's link to reshape, one-based. Omit to apply to every link.",
            required = false,
            default = null,
        ),
        AgentToolArg(
            name = "latency_ms",
            type = "integer",
            description = "One-way delay in milliseconds before jitter.",
            required = false,
            default = "0",
        ),
        AgentToolArg(
            name = "jitter_ms",
            type = "integer",
            description = "Maximum extra delay on top of latency_ms, drawn per datagram from the seed.",
            required = false,
            default = "0",
        ),
        AgentToolArg(
            name = "loss",
            type = "number",
            description = "Probability 0..1 that a datagram is discarded outright, e.g. 0.1 for 10%.",
            required = false,
            default = "0",
        ),
    )

    override val inputSchema: String = ToolSchema.of(args)

    override val owner: KClass<*> = NetToolset::class

    override fun invoke(receiver: NetToolset, command: AgentCommand): Any? = receiver.setConditions(
        client = if ("client" in command) command.int("client") else null,
        latencyMs = command.int("latency_ms", 0),
        jitterMs = command.int("jitter_ms", 0),
        loss = command.float("loss", 0f),
    )
}

/** `net.input`. */
public object NetInputTool : AgentToolDef<NetToolset> {

    override val name: String = "net.input"

    override val description: String =
        "Set what one client is holding on its stick: move axes, aim and buttons. The client " +
            "samples this every tick and puts it on the wire as input - never as state - so the " +
            "server is the only thing that ever moves the body. It is a held direction, not a " +
            "one-shot: call it, then net.step, and read the result with net.server_state. Set " +
            "the axes back to zero to stop."

    override val args: List<AgentToolArg> = listOf(
        CLIENT_ARG,
        AgentToolArg(
            name = "move_x",
            type = "number",
            description = "Move axis, -1..1. Quantised to 8 bits on the wire, so 1.0 arrives as about 0.996.",
            required = false,
            default = "0",
        ),
        AgentToolArg(
            name = "move_y",
            type = "number",
            description = "Move axis, -1..1. Quantised to 8 bits on the wire.",
            required = false,
            default = "0",
        ),
        AgentToolArg(
            name = "aim",
            type = "number",
            description = "Aim direction in radians, -PI..PI. Quantised to 12 bits.",
            required = false,
            default = "0",
        ),
        AgentToolArg(
            name = "buttons",
            type = "integer",
            description = "Button bitfield, 8 bits: one bit per action, 0 for none held.",
            required = false,
            default = "0",
        ),
    )

    override val inputSchema: String = ToolSchema.of(args)

    override val owner: KClass<*> = NetToolset::class

    override fun invoke(receiver: NetToolset, command: AgentCommand): Any? = receiver.input(
        client = command.int("client"),
        moveX = command.float("move_x", 0f),
        moveY = command.float("move_y", 0f),
        aim = command.float("aim", 0f),
        buttons = command.int("buttons", 0),
    )
}

/** `net.step`. */
public object NetStepTool : AgentToolDef<NetToolset> {

    override val name: String = "net.step"

    override val description: String =
        "Advance the whole session by a number of simulation ticks: every link releases what is " +
            "due, both ends consume it, the server simulates and broadcasts. Reach for it after " +
            "net.input to let the input actually travel - at 60Hz, 200ms of latency is twelve " +
            "ticks each way, so nothing observable happens in fewer. Nothing sleeps: the ticks " +
            "run on the calling thread against a manual clock."

    override val args: List<AgentToolArg> = listOf(
        AgentToolArg(
            name = "ticks",
            type = "integer",
            description = "How many simulation ticks to run, 0..600. 60 is one second at 60Hz.",
            required = false,
            default = "60",
        ),
    )

    override val inputSchema: String = ToolSchema.of(args)

    override val owner: KClass<*> = NetToolset::class

    /** One second at 60Hz: long enough for a round trip at any latency this can simulate. */
    private const val DEFAULT_TICKS: Int = 60

    override fun invoke(receiver: NetToolset, command: AgentCommand): Any? =
        receiver.step(command.int("ticks", DEFAULT_TICKS))
}

/** `net.server_state`. */
public object NetServerStateTool : AgentToolDef<NetToolset> {

    override val name: String = "net.server_state"

    override val description: String =
        "Read the authoritative position of one client's body, on the server. This is the answer " +
            "that counts: the server is the only thing that writes it, so a position that moved " +
            "here is proof the client's input arrived and was accepted. Compare it against " +
            "net.client_state to see how far behind or how wrong a client is. Also reports the " +
            "server's traffic counters for that client's link."

    override val args: List<AgentToolArg> = listOf(CLIENT_ARG)

    override val inputSchema: String = ToolSchema.of(args)

    override val owner: KClass<*> = NetToolset::class

    override fun invoke(receiver: NetToolset, command: AgentCommand): Any? =
        receiver.serverState(command.int("client"))
}

/** `net.client_state`. */
public object NetClientStateTool : AgentToolDef<NetToolset> {

    override val name: String = "net.client_state"

    override val description: String =
        "Read what one client believes about its own body: the server's answer as of the last " +
            "packet that client applied, plus the server tick that packet carried. Reach for it " +
            "beside net.server_state when a client looks wrong - a client under latency is " +
            "legitimately several ticks behind, and the tick in this reply is what tells the two " +
            "cases apart. Never authoritative; net.server_state is."

    override val args: List<AgentToolArg> = listOf(CLIENT_ARG)

    override val inputSchema: String = ToolSchema.of(args)

    override val owner: KClass<*> = NetToolset::class

    override fun invoke(receiver: NetToolset, command: AgentCommand): Any? =
        receiver.clientState(command.int("client"))
}

/** `net.desync_report`. */
public object NetDesyncReportTool : AgentToolDef<NetToolset> {

    override val name: String = "net.desync_report"

    override val description: String =
        "Compare a client's replicated state against the server's, field by field, and name " +
            "every entity, component and field the two disagree on. This is the tool to reach " +
            "for when a client looks wrong and you do not know why: a hash comparison can only " +
            "say 'somewhere', and 'somewhere' is what makes a desync a week of work. The " +
            "comparison is made at the server tick the client last applied, so ordinary latency " +
            "does not read as a desync. An empty field list means that client has converged."

    override val args: List<AgentToolArg> = listOf(CLIENT_ARG)

    override val inputSchema: String = ToolSchema.of(args)

    override val owner: KClass<*> = NetToolset::class

    override fun invoke(receiver: NetToolset, command: AgentCommand): Any? =
        receiver.desyncReport(command.int("client"))
}

/** `net.close_session`. */
public object NetCloseSessionTool : AgentToolDef<NetToolset> {

    override val name: String = "net.close_session"

    override val description: String =
        "End the running session and release its links, dropping any traffic still in flight. " +
            "Reach for it when you are finished with a session and want the process left clean; " +
            "you do not need it before spawning another, because net.spawn_session replaces " +
            "whatever was running. Takes no arguments."

    override val args: List<AgentToolArg> = emptyList()

    override val inputSchema: String = ToolSchema.of(args)

    override val owner: KClass<*> = NetToolset::class

    override fun invoke(receiver: NetToolset, command: AgentCommand): Any? = receiver.closeSession()
}

/**
 * The `net.*` toolset as a [ToolModule] a host registers.
 *
 * Assembled by hand rather than discovered through `ServiceLoader`, for the reason
 * `EngineToolModules` gives: `ToolIndex.Builder.build` refuses a tool whose toolset was never
 * registered, so a service entry would turn every process with this module on its classpath into
 * a start-up failure unless it wired a [NetToolset]. A host that wants the session tools adds
 * this module and registers the instance; one that does not gets neither, and its manifest then
 * advertises no capability it cannot serve.
 */
public object NetToolModule : ToolModule {

    override val moduleName: String = "UdeaAgentNet"

    override val tools: List<AgentToolDef<*>> = listOf(
        NetAssertNotVisibleTool,
        NetClientStateTool,
        NetCloseSessionTool,
        NetDesyncReportTool,
        NetInputTool,
        NetRelevancyTool,
        NetServerStateTool,
        NetSetConditionsTool,
        NetSpawnSessionTool,
        NetStepTool,
    )

    override fun toString(): String = "ToolModule($moduleName, ${tools.size} tools)"
}
