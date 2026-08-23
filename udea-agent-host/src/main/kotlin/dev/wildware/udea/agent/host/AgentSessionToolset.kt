package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.AgentToolArg
import dev.wildware.udea.agent.AgentToolDef
import kotlin.reflect.KClass

/**
 * `agent_session`: one call that tells an agent which end of a multiplayer test it is talking to.
 *
 * ## The problem it solves
 *
 * Phase 3 and 4 (spec 6) are about a server and its clients disagreeing, and the only way to
 * debug that is to send input on a client port and assert authoritative state on the server port.
 * An agent that attached to a port mid-session has no way to know which it has: `/health` says the
 * game is up, `/tools` says what it can do, and neither says *what this instance is for*. So the
 * agent has to try something and infer - which is the shape of every debugging session that ends
 * with the wrong conclusion.
 *
 * One call answers all four questions at once: what am I, which group am I in, which port am I on,
 * and what did I start.
 *
 * ## It reports; it does not group
 *
 * This answers about **this instance**. Grouping the whole session is the bridge's job, over the
 * registry entries, using the same `sessionId` - and deliberately so: an instance can only tell
 * the truth about itself and about what it launched, and one that tried to enumerate its peers
 * would be re-implementing `list_instances` from inside a process that cannot see the others.
 *
 * ## Nothing here can change anything
 *
 * There is no argument and no writer. A tool that could *set* a session id could move an instance
 * between groups behind a bridge's cached view, and one that could read a caller-supplied id
 * could branch on it - which is precisely the "never make an endpoint's behaviour depend on the
 * session id" rule that keeps a bridge ignoring the field semantically identical to one that
 * reads it.
 */
public class AgentSessionToolset(
    /** What this instance is and which group it is in. */
    private val session: SessionIdentity,
    /** The port this instance's [AgentHost] actually bound. */
    private val port: Int,
    /** What this instance launched. Empty for an instance that launched nothing. */
    private val peers: SessionPeers,
) {

    /** Reports this instance's role, session, port and launched peers. */
    public fun describe(): AgentResult = AgentResult.ok {
        put("role", session.role.id)
        put("sessionId", session.sessionId.value)
        put("port", port)
        arr("peers") {
            for (peer in peers.peers) {
                element {
                    put("role", peer.role.id)
                    put("port", peer.port)
                    put("pid", peer.pid)
                }
            }
        }
    }

    override fun toString(): String = "AgentSessionToolset($session on $port, $peers)"
}

/**
 * The hand-written [AgentToolDef] for `agent_session`.
 *
 * Hand-written for the same reason [CompareArtifactsTool] is: this module has no KSP round, and
 * adding one to generate a single declaration would put a build-time dependency on the debug host
 * for no behaviour. The shape is what the processor emits - `docs/contracts/agent-tools.md` is the
 * written form of it - so replacing this with a generated one later is a deletion.
 */
public object AgentSessionTool : AgentToolDef<AgentSessionToolset> {

    override val name: String = "agent_session"

    override val description: String =
        "Report what this instance is in a multiplayer session: its role (server, client or " +
            "standalone), its session id, the port it is bound to, and the peers it launched. " +
            "Call it first when you have attached to a port mid-session and need to know whether " +
            "to send input here or assert authoritative state here. Instances sharing a session " +
            "id are one match; the same id appears in list_instances and in each instance's " +
            "/health. Takes no arguments and changes nothing."

    override val args: List<AgentToolArg> = emptyList()

    /** Derived from [args], never a second literal beside them. See [ToolSchema]. */
    override val inputSchema: String = ToolSchema.of(args)

    override val owner: KClass<*> = AgentSessionToolset::class

    override fun invoke(receiver: AgentSessionToolset, command: AgentCommand): Any? =
        receiver.describe()
}
