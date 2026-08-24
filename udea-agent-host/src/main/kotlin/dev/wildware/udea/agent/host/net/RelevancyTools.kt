package dev.wildware.udea.agent.host.net

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentToolArg
import dev.wildware.udea.agent.AgentToolDef
import dev.wildware.udea.agent.Json
import dev.wildware.udea.agent.host.ToolSchema
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.net.relevancy.FogOfWar
import dev.wildware.udea.net.relevancy.RelevancyEntry
import dev.wildware.udea.net.relevancy.RelevancyReport
import dev.wildware.udea.net.transport.PeerId

/** Error kinds the two fog tools answer with. */
public object RelevancyErrors {

    /**
     * The session was stood up without fog, so there is no relevancy to report.
     *
     * Its own kind rather than an empty answer, because "nothing is hidden" and "nothing computes
     * what is hidden" look identical in a result body and mean opposite things. An agent that read
     * an empty relevancy set as proof of correct fog would be reading a session that has none.
     */
    public val NO_FOG: AgentErrorKind = AgentErrorKind("no_fog")

    /**
     * `net.assert_not_visible` found the entity **is** visible to that client.
     *
     * A failure and not an `ok` with a false flag: this is an assertion tool, and an assertion
     * that reports its own violation as a success is how a regression rides through CI.
     */
    public val RELEVANCY_LEAK: AgentErrorKind = AgentErrorKind("relevancy_leak")
}

/**
 * The bodies behind `net.relevancy` and `net.assert_not_visible`.
 *
 * Free functions over the live [NetSession] rather than methods on [NetToolset], so the fog
 * tools are one file that adds behaviour and changes none: [NetToolset.current] is already
 * public, and reaching through it costs nothing an agent can observe.
 */
internal object RelevancyTools {

    /** `net.relevancy(client)`: every entity that client may know about, and why. */
    fun relevancy(session: NetSession?, client: Int): AgentResult = withFog(session, client) { fog, peer ->
        val report = RelevancyReport.of(fog, peer)
        AgentResult.ok {
            put("ok", true)
            put("client", client)
            put("team", report.team)
            put("tick", report.tick.value)
            put("visible", report.entries.size)
            arr("entities") {
                for (entry in report.entries.take(MAX_REPORTED_ENTITIES)) element { render(entry) }
            }
            if (report.entries.size > MAX_REPORTED_ENTITIES) put("entitiesTruncated", true)
            arr("left") { for (netId in report.left) element { renderId(netId) } }
            obj("cost") { renderCost(fog) }
        }
    }

    /**
     * `net.assert_not_visible(client, net_id)`: the anti-cheat regression.
     *
     * Answers on the **relevancy set the packer itself consults**, not on a copy, so a pass here
     * is the same statement as "`ReplicationServer` skipped this entity when it packed that
     * client's datagram". The wire-level form of the same claim - that the field is nowhere in
     * the bytes at any bit alignment - is `AntiCheatWireTest` in `:udea-net`, which is where a
     * byte sweep belongs; this is the one an agent can run against a live session.
     */
    fun assertNotVisible(session: NetSession?, client: Int, raw: Int): AgentResult =
        withFog(session, client) { fog, peer ->
            val netId = try {
                NetId.ofRaw(raw)
            } catch (e: IllegalArgumentException) {
                return@withFog AgentResult.failed(
                    AgentErrorKind.BAD_ARGUMENT,
                    e.message ?: "net_id is not a valid NetId word",
                )
            }
            val entry = RelevancyReport.entryOf(fog, peer, netId)
            if (entry.visible) {
                AgentResult.failed(
                    RelevancyErrors.RELEVANCY_LEAK,
                    "$netId IS visible to client $client: ${entry.reason} from ${entry.source} at " +
                        "${entry.distance}. The server would serialise it, so a sniffer would see it.",
                )
            } else {
                AgentResult.ok {
                    put("ok", true)
                    put("client", client)
                    put("notVisible", true)
                    render(entry)
                    obj("cost") { renderCost(fog) }
                }
            }
        }

    private inline fun withFog(
        session: NetSession?,
        client: Int,
        body: (FogOfWar, PeerId) -> AgentResult,
    ): AgentResult {
        val live = session ?: return AgentResult.failed(
            NetErrors.NO_NET_SESSION,
            "no multiplayer session is running in this process; call net.spawn_session first",
        )
        if (client !in 1..live.clients) {
            return AgentResult.failed(
                NetErrors.NO_SUCH_PEER,
                "this session has clients 1..${live.clients}; there is no client $client",
            )
        }
        val fog = live.fog ?: return AgentResult.failed(
            RelevancyErrors.NO_FOG,
            "this session was spawned without fog, so every client is told about every entity; " +
                "call net.spawn_session with vision_radius greater than zero",
        )
        return body(fog, PeerId.client(client))
    }

    private fun Json.render(entry: RelevancyEntry) {
        renderId(entry.netId)
        put("visible", entry.visible)
        put("reason", entry.reason.name)
        put("source", entry.source.toString())
        put("sourceRaw", entry.source.raw)
        put("distance", entry.distance)
        put("since", entry.since.value)
        put("enters", entry.enters)
        put("leaves", entry.leaves)
    }

    private fun Json.renderId(netId: NetId) {
        put("netId", netId.toString())
        put("netIdRaw", netId.raw)
    }

    private fun Json.renderCost(fog: FogOfWar) {
        put("solves", fog.stats.solves)
        put("teamSolves", fog.stats.teamSolves)
        put("clientQueries", fog.stats.clientQueries)
        put("distanceTests", fog.stats.distanceTests)
        put("distanceTestsPerSolve", fog.stats.distanceTestsPerSolve)
        put("cellVisits", fog.stats.cellVisits)
        put("overBudgetSolves", fog.stats.overBudgetSolves)
        put("budget", fog.settings.distanceTestBudget)
    }

    /**
     * How many entities one report prints.
     *
     * The digest is byte-budgeted, and a full lane's relevancy set would fill it with rows an
     * agent cannot act on faster than it can act on the first sixty-four. The count is reported
     * separately and never truncated, so "how many" and "which" stay separable.
     */
    private const val MAX_REPORTED_ENTITIES: Int = 64
}

/** `net.relevancy`. */
public object NetRelevancyTool : AgentToolDef<NetToolset> {

    override val name: String = "net.relevancy"

    override val description: String =
        "List everything one client is allowed to know about, and name the vision source granting " +
            "each one. Reach for it first whenever an entity is appearing and disappearing on a " +
            "client, or is missing when it should not be: the reply says whether the entity is in " +
            "the client's relevancy set, which team member is looking at it, how far away that is, " +
            "and how many times it has entered and left. An entity with a high 'enters' count is " +
            "oscillating on a fog boundary, and 'source' names the unit that keeps dropping it - " +
            "which is the difference between one tool call and a day of inference. Also reports " +
            "what the fog solve cost this session. Requires a session spawned with vision_radius."

    override val args: List<AgentToolArg> = listOf(
        AgentToolArg(
            name = "client",
            type = "integer",
            description = "Which client, one-based: 1 is the first client the session stood up.",
            required = true,
            default = null,
        ),
    )

    override val inputSchema: String = ToolSchema.of(args)

    override val owner: kotlin.reflect.KClass<*> = NetToolset::class

    override fun invoke(receiver: NetToolset, command: AgentCommand): Any? =
        RelevancyTools.relevancy(receiver.current, command.int("client"))
}

/** `net.assert_not_visible`. */
public object NetAssertNotVisibleTool : AgentToolDef<NetToolset> {

    override val name: String = "net.assert_not_visible"

    override val description: String =
        "Assert that one client cannot see one entity, and fail if it can. This is the anti-cheat " +
            "check: the server never serialises a field the client is not allowed to see, so " +
            "passing here means there is nothing in that client's datagrams for a packet sniffer " +
            "to find - not that the client is hiding it in its UI. Reach for it after moving a " +
            "unit into fog, or in a regression script guarding a map-hack fix. On failure the " +
            "reply names the vision source that is granting the entity, so you can see exactly " +
            "why it leaked. Takes the netIdRaw value that net.relevancy prints."

    override val args: List<AgentToolArg> = listOf(
        AgentToolArg(
            name = "client",
            type = "integer",
            description = "Which client must not be able to see it, one-based.",
            required = true,
            default = null,
        ),
        AgentToolArg(
            name = "net_id",
            type = "integer",
            description = "The entity's packed NetId word, as net.relevancy reports it in netIdRaw.",
            required = true,
            default = null,
        ),
    )

    override val inputSchema: String = ToolSchema.of(args)

    override val owner: kotlin.reflect.KClass<*> = NetToolset::class

    override fun invoke(receiver: NetToolset, command: AgentCommand): Any? =
        RelevancyTools.assertNotVisible(receiver.current, command.int("client"), command.int("net_id"))
}
