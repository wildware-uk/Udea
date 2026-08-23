package dev.wildware.udea.agent.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * How an instance learns what it is: `-Dudea.agent.session`, `-Dudea.agent.role`, and what it does
 * when it was told neither.
 *
 * The properties are read through an injected lookup rather than `System.getProperty`, exactly as
 * [AgentRegistry]'s are, so these run without mutating the JVM's real properties - which a test
 * running beside every other test in this module must not do.
 */
class SessionIdentityTest {

    private fun properties(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { key -> map[key] }
    }

    @Test
    fun `an instance told a session and a role reports both`() {
        val identity = SessionIdentity.resolve(
            properties(
                SessionIdentity.SESSION_PROPERTY to "s-7f3a",
                SessionIdentity.ROLE_PROPERTY to "client",
            ),
            pid = 4242L,
        )

        assertEquals(InstanceRole.Client, identity.role)
        assertEquals("s-7f3a", identity.sessionId.value)
    }

    @Test
    fun `an instance told nothing is standalone with an id of its own`() {
        // The acceptance criterion: the fields are never missing, so no reader needs a case for
        // "this instance did not say".
        val identity = SessionIdentity.resolve(properties(), pid = 4242L)

        assertEquals(InstanceRole.Standalone, identity.role)
        assertTrue(
            identity.sessionId.value.startsWith(SessionId.GENERATED_PREFIX),
            "a generated id is marked as generated; got ${identity.sessionId}",
        )
        assertTrue(identity.sessionId.value.length <= SessionId.MAX_LENGTH)
    }

    @Test
    fun `a blank property is treated as absent, not as a session called empty`() {
        // `-Dudea.agent.session=` is what a launcher script produces when its variable was unset,
        // and a SessionId("") would throw out of a game's start-up path over a debug flag.
        val identity = SessionIdentity.resolve(
            properties(
                SessionIdentity.SESSION_PROPERTY to "  ",
                SessionIdentity.ROLE_PROPERTY to "",
            ),
            pid = 4242L,
        )
        assertEquals(InstanceRole.Standalone, identity.role)
        assertEquals(SessionId.generate(4242L).value, identity.sessionId.value)
    }

    @Test
    fun `two concurrent processes generate different ids`() {
        // The property the pid derivation buys and a random id would not: two instances that are
        // running at the same time cannot collide, because their pids cannot be equal.
        assertNotEquals(SessionId.generate(4242L).value, SessionId.generate(4243L).value)
    }

    @Test
    fun `an unknown role fails loudly and names what was accepted`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            SessionIdentity.resolve(
                properties(SessionIdentity.ROLE_PROPERTY to "hostt"),
                pid = 1L,
            )
        }
        assertTrue(failure.message!!.contains("hostt"), "the message must name the bad value")
        assertTrue(failure.message!!.contains("server"), "the message must name the accepted ones")
    }

    @Test
    fun `a role is parsed case-insensitively and trimmed`() {
        // What a launcher script actually produces when a variable had a trailing newline.
        assertEquals(InstanceRole.Server, InstanceRole.parse(" Server\n"))
        assertEquals(InstanceRole.Client, InstanceRole.parse("CLIENT"))
    }

    @Test
    fun `a session id that could not be one is refused`() {
        assertFailsWith<IllegalArgumentException> { SessionId("") }
        assertFailsWith<IllegalArgumentException> { SessionId("a".repeat(SessionId.MAX_LENGTH + 1)) }
        // The characters that would break the value out of a filename, a query string or a JSON
        // string, which is every place this id is written.
        assertFailsWith<IllegalArgumentException> { SessionId("s-7f3a/../etc") }
        assertFailsWith<IllegalArgumentException> { SessionId("""s-"a""") }
        assertFailsWith<IllegalArgumentException> { SessionId("s 7f3a") }
    }

    @Test
    fun `the arguments a launcher passes are the arguments a peer resolves`() {
        // This is the seam with `:udea-net`. `start_host`/`start_client` add these to the JVM they
        // spawn; the peer reads them back through `resolve`. Written as a round trip so the two
        // halves cannot drift: a renamed property breaks this test, not a silent grouping failure
        // in a multiplayer session weeks later.
        val session = SessionId("s-7f3a")
        val arguments = SessionIdentity.jvmArguments(session, InstanceRole.Server)

        assertEquals(
            listOf("-Dudea.agent.session=s-7f3a", "-Dudea.agent.role=server"),
            arguments,
        )

        val parsed = arguments.associate { argument ->
            val body = argument.removePrefix("-D")
            body.substringBefore('=') to body.substringAfter('=')
        }
        val resolved = SessionIdentity.resolve({ key -> parsed[key] }, pid = 9L)
        assertEquals(InstanceRole.Server, resolved.role)
        assertEquals(session.value, resolved.sessionId.value)
    }

    @Test
    fun `peers are recorded in launch order and the list is bounded`() {
        val peers = SessionPeers(capacity = 2)
        peers.record(InstanceRole.Client, 7821, pid = 11L)
        peers.record(InstanceRole.Client, 7822, pid = 12L)

        assertEquals(listOf(7821, 7822), peers.peers.map { it.port })
        assertFailsWith<IllegalStateException> { peers.record(InstanceRole.Client, 7823) }
    }

    @Test
    fun `a peer on a port that could not exist is refused`() {
        // The row exists so an agent can reach the peer. A row naming port 0 - which is what a
        // launcher records if it asks before the OS has chosen - would send it nowhere.
        assertFailsWith<IllegalArgumentException> { SessionPeers().record(InstanceRole.Client, 0) }
    }
}
