package dev.wildware.udea.agent.host

import dev.wildware.udea.core.host.RenderMode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Self-registration: the entry, its precedence rules, and above all its harmlessness.
 *
 * The rule the contract states most emphatically is that registry failure must never break the
 * game, so the read-only case is tested by *serving a request through a host whose advertising
 * failed*, not by asserting that a method returned null.
 */
class AgentRegistryTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `the entry carries every field the contract names`() {
        val entries = temp.resolve("instances")
        val registry = registry(entries)
        val cwd = temp.resolve("checkout").also { it.createDirectories() }

        val file = registry.advertise(
            7820,
            GameIdentity("Orbital Freight", "0.9.2"),
            RenderMode.Offscreen,
            SessionIdentity(InstanceRole.Client, SessionId("s-7f3a")),
            cwd,
        )

        assertTrue(file != null && file.exists())
        val json = file.readText()
        listOf(
            """"name":"Orbital Freight"""",
            """"version":"0.9.2"""",
            """"protocol":1""",
            """"port":7820""",
            """"pid":4242""",
            """"host":"127.0.0.1"""",
            """"started":""",
            """"cwd":""",
            """"renderMode":"Offscreen"""",
            """"role":"client"""",
            """"sessionId":"s-7f3a"""",
        ).forEach { assertContains(json, it) }
        assertContains(json, cwd.toAbsolutePath().normalize().toString().replace("\\", "\\\\"))
        assertEquals("4242.json", file.fileName.toString(), "the entry is named for the pid")
    }

    /**
     * The order the writer must follow: bound first, advertised second. A host whose bind throws
     * must leave no entry, because an entry naming a port nobody claimed is worse than no entry -
     * a reader that trusts it reports a running game where there is none.
     */
    @Test
    fun `a failed bind writes no entry`() {
        val entries = temp.resolve("instances")
        val occupied = java.net.ServerSocket()
        occupied.reuseAddress = false
        occupied.bind(java.net.InetSocketAddress(AgentHost.LOOPBACK, 0))
        try {
            val failed = runCatching {
                AgentHost.start(
                    dev.wildware.udea.agent.AgentBridge(),
                    AgentHostConfig(port = occupied.localPort, registry = registry(entries)),
                )
            }
            assertTrue(failed.isFailure, "binding an occupied port must fail")
            assertFalse(entries.exists(), "nothing may be advertised for a port that was never bound")
        } finally {
            occupied.close()
        }
    }

    @Test
    fun `stop deletes the entry and a second stop is a no-op`() {
        val entries = temp.resolve("instances")
        val registry = registry(entries)
        val harness = HostHarness(registry = registry)
        val file = registry.entry

        assertTrue(file != null && file.exists(), "the entry is written once the port is bound")
        harness.host.stop()
        assertFalse(file!!.exists())
        harness.host.stop()
        assertNull(registry.entry)
    }

    /**
     * The rule that matters most. An unwritable entries directory must not stop the game starting
     * or serving, so the assertion is on `/health`, not on a return value.
     */
    @Test
    fun `an unwritable registry does not stop the host serving`() {
        // A *file* where the directory should be: `createDirectories` fails on it on every OS,
        // unlike a read-only directory, which an administrator process can still write to.
        val blocked = temp.resolve("blocked")
        Files.writeString(blocked, "not a directory")

        HostHarness(registry = registry(blocked.resolve("instances"))).use { harness ->
            assertEquals(200, harness.get("/health").statusCode())
            assertContains(harness.get("/health").body(), """"ok":true""")
        }
    }

    @Test
    fun `directory precedence is property then instances env then home env then user home`() {
        val property = temp.resolve("by-property")
        val instances = temp.resolve("by-instances")
        val home = temp.resolve("by-home")
        val userHome = temp.resolve("by-user-home")

        assertEquals(
            property,
            AgentRegistry.resolveDirectory(
                properties = { if (it == AgentRegistry.DIRECTORY_PROPERTY) property.toString() else userHome.toString() },
                environment = {
                    when (it) {
                        AgentRegistry.INSTANCES_ENV -> instances.toString()
                        AgentRegistry.HOME_ENV -> home.toString()
                        else -> null
                    }
                },
            ),
        )
        assertEquals(
            instances,
            AgentRegistry.resolveDirectory(
                properties = { if (it == "user.home") userHome.toString() else null },
                environment = {
                    when (it) {
                        AgentRegistry.INSTANCES_ENV -> instances.toString()
                        AgentRegistry.HOME_ENV -> home.toString()
                        else -> null
                    }
                },
            ),
        )
        assertEquals(
            home.resolve(AgentRegistry.INSTANCES_DIRECTORY),
            AgentRegistry.resolveDirectory(
                properties = { if (it == "user.home") userHome.toString() else null },
                environment = { if (it == AgentRegistry.HOME_ENV) home.toString() else null },
            ),
        )
        assertEquals(
            userHome.resolve(AgentRegistry.DEFAULT_HOME_DIRECTORY).resolve(AgentRegistry.INSTANCES_DIRECTORY),
            AgentRegistry.resolveDirectory(
                properties = { if (it == "user.home") userHome.toString() else null },
                environment = { null },
            ),
        )
    }

    /** A sandbox with no `$HOME` is a legitimate outcome, not a failure. */
    @Test
    fun `no home at all resolves to nowhere rather than throwing`() {
        assertNull(AgentRegistry.resolveDirectory(properties = { null }, environment = { null }))
    }

    private fun registry(entries: Path) = AgentRegistry(
        properties = { if (it == AgentRegistry.DIRECTORY_PROPERTY) entries.toString() else null },
        environment = { null },
        pid = 4242,
    )
}
