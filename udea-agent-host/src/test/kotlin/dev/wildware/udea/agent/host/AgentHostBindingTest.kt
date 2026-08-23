package dev.wildware.udea.agent.host

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The surface is reachable on loopback and nowhere else, and there is no API through which that
 * could be changed.
 *
 * This is the module's security boundary. A configurable bind host is one hurried edit away from
 * exposing a remote-control channel into a live game to a LAN, so the test asserts both halves:
 * the socket behaviour, and the *absence of a knob*.
 */
class AgentHostBindingTest {

    @Test
    fun `reachable on the loopback address`() {
        HostHarness().use { harness ->
            assertEquals(200, harness.get(AgentHost.LOOPBACK, "/health").statusCode())
        }
    }

    /**
     * Not reachable on this machine's routable address.
     *
     * Skipped rather than failed on a host with no non-loopback IPv4 interface - a container with
     * only `lo` is a legitimate CI shape, and there is nothing to test there. It is announced, so
     * a run where it silently stopped testing anything is visible in the log.
     */
    @Test
    fun `not reachable on a routable local address`() {
        val routable = routableAddress()
        if (routable == null) {
            println("[AgentHostBindingTest] no non-loopback IPv4 interface; skipping")
            return
        }
        HostHarness().use { harness ->
            val refused = assertFailsWith<java.io.IOException> {
                Socket().use { it.connect(InetSocketAddress(routable, harness.port), CONNECT_TIMEOUT_MS) }
            }
            assertTrue(
                refused is java.net.ConnectException || refused is java.net.SocketTimeoutException,
                "expected the connection to be refused, got ${refused.javaClass.name}: ${refused.message}",
            )
        }
    }

    /**
     * No public entry point takes a bind host.
     *
     * A reflective check rather than a code-reading convention, because "nobody added a host
     * parameter" is precisely the claim that decays: the next person to want one adds it to
     * `AgentHostConfig` and every prose assertion about loopback stays green.
     */
    @Test
    fun `no public API accepts a bind host`() {
        val suspicious = listOf("host", "address", "bindaddress", "hostname", "inetaddress", "iface")
        val surfaces = listOf(AgentHost::class.java, AgentHostConfig::class.java)

        surfaces.forEach { type ->
            type.methods.forEach { method ->
                method.parameters.forEach { parameter ->
                    assertTrue(
                        suspicious.none { parameter.name.lowercase().contains(it) },
                        "${type.simpleName}.${method.name} takes ${parameter.name}: the agent host " +
                            "binds ${AgentHost.LOOPBACK} and must not be given an address",
                    )
                }
                assertTrue(
                    method.parameterTypes.none { InetAddress::class.java.isAssignableFrom(it) },
                    "${type.simpleName}.${method.name} takes an InetAddress",
                )
            }
            type.declaredFields.forEach { field ->
                assertTrue(
                    field.type != InetAddress::class.java && field.type != InetSocketAddress::class.java,
                    "${type.simpleName}.${field.name} holds an address that is not the constant",
                )
            }
        }
    }

    /**
     * The gate refuses without the port property, and refuses with it when the build forbids the
     * agent surface at all. Both, because either alone has failed in prior art.
     */
    @Test
    fun `startIfRequested binds only when both conditions hold`() {
        assertNull(
            AgentHost.startIfRequested(dev.wildware.udea.agent.AgentBridge(), { config(it) }, properties = { null }),
            "no -Dudea.agent.port means no server",
        )
        assertNull(
            AgentHost.startIfRequested(
                dev.wildware.udea.agent.AgentBridge(),
                { config(it) },
                agentAllowed = false,
                properties = { if (it == BuildFlags.PORT_PROPERTY) "0" else null },
            ),
            "AGENT_ALLOWED=false means no server even with a port",
        )

        val started = AgentHost.startIfRequested(
            dev.wildware.udea.agent.AgentBridge(),
            { config(it) },
            agentAllowed = true,
            properties = { if (it == BuildFlags.PORT_PROPERTY) "0" else null },
        )
        try {
            assertTrue(started != null && started.port > 0, "an ephemeral port was requested and bound")
        } finally {
            started?.stop()
        }
    }

    private fun config(port: Int) = AgentHostConfig(port = port, registry = HostHarness.noRegistry())

    private fun routableAddress(): InetAddress? =
        NetworkInterface.networkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses().toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 2_000
    }
}
