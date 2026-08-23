package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.Json
import dev.wildware.udea.core.host.RenderMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The documented invariant: `role` and `sessionId` are **additive and ignorable**.
 *
 * Two halves, and both have to hold or a bridge that ignores the fields quietly gets different
 * semantics from one that reads them - which surfaces as a test that passes here and fails
 * through the real bridge.
 *
 * 1. A client that has never heard of the fields still reads `/health`. That is what [ContractEra]
 *    below is: a reader written against the four keys `game-bridge-mcp`'s README §1 names, and
 *    nothing else. It is a **stand-in** for the bridge's own client - this repository cannot
 *    execute the bridge's TypeScript - and it is deliberately strict about the thing that would
 *    actually break one: the document has to be well-formed JSON whose contract keys carry
 *    exactly the contract values.
 * 2. No endpoint behaves differently because of them. Asserted by driving two instances that
 *    differ *only* in their session identity through the same calls and comparing the bodies.
 */
class SessionAdditiveTest {

    @Test
    fun `a reader that knows only the contract keys parses health with both fields present`() {
        HostHarness(mode = RenderMode.Offscreen).use { harness ->
            harness.bridge.advanceFrame()
            harness.bridge.publishTick(412)

            val parsed = ContractEra.readHealth(harness.get("/health").body())

            assertEquals("true", parsed["ok"])
            assertEquals("1", parsed["frame"])
            assertEquals("412", parsed["tick"])
            assertEquals("false", parsed["paused"])
        }
    }

    @Test
    fun `the contract keys are unchanged in value and in order by the two additions`() {
        // The additions are appended, so a reader that scans for a key in document order - which a
        // hand-rolled parser in a shell script does - finds every contract key exactly where it
        // was. This asserts the prefix, character for character.
        HostHarness(mode = RenderMode.Headless).use { harness ->
            harness.bridge.advanceFrame()
            harness.bridge.publishTick(7)

            val body = harness.get("/health").body()
            val contractOnly = Json.render {
                put("ok", true)
                put("frame", 1L)
                put("tick", 7L)
                put("paused", false)
                put("renderMode", "Headless")
            }
            val prefix = contractOnly.dropLast(1)

            assertTrue(
                body.startsWith(prefix),
                "the additions changed the contract prefix.\n  was: $body\n  expected prefix: $prefix",
            )
            assertEquals(
                """$prefix,"role":"standalone","sessionId":"s-test"}""",
                body,
            )
        }
    }

    @Test
    fun `no endpoint answers differently because of a session id`() {
        // Grouping is a reader-side convenience. Two instances that differ only in their identity
        // must be indistinguishable through every endpoint but `/health`'s two extra keys.
        val plain = HostHarness(
            bridge = AgentBridge(),
            session = SessionIdentity.resolve({ null }, pid = 4242L),
        )
        val grouped = HostHarness(
            bridge = AgentBridge(),
            session = SessionIdentity(InstanceRole.Client, SessionId("s-7f3a")),
        )

        plain.use {
            grouped.use {
                for (harness in listOf(plain, grouped)) {
                    harness.bridge.publish("""{"tick":9,"entities":2}""")
                }

                assertEquals(plain.get("/state").body(), grouped.get("/state").body())
                // The command id is a counter this JVM shares across every bridge, so it differs
                // between two instances for a reason that has nothing to do with sessions.
                // Everything else about the two answers must be identical.
                assertEquals(
                    withoutCommandId(plain.get("/command?cmd=spawn&type=cherry").body()),
                    withoutCommandId(grouped.get("/command?cmd=spawn&type=cherry").body()),
                )
                assertEquals(
                    plain.get("/command").statusCode(),
                    grouped.get("/command").statusCode(),
                )
                assertEquals(plain.get("/tools").statusCode(), grouped.get("/tools").statusCode())
                assertEquals(
                    plain.get("/artifact?id=cap_0001").body(),
                    grouped.get("/artifact?id=cap_0001").body(),
                )

                // And the commands themselves arrive identically: a tool must not be able to read
                // the grouping, or it could branch on which end of a session it is running on.
                val plainQueued = ArrayList<dev.wildware.udea.agent.AgentCommand>()
                val groupedQueued = ArrayList<dev.wildware.udea.agent.AgentCommand>()
                plain.bridge.drain(plainQueued)
                grouped.bridge.drain(groupedQueued)
                assertEquals(plainQueued.size, groupedQueued.size)
                for (index in plainQueued.indices) {
                    assertEquals(plainQueued[index].name, groupedQueued[index].name)
                    assertEquals(plainQueued[index].args, groupedQueued[index].args)
                    assertTrue(
                        groupedQueued[index].args.none { it.value == "s-7f3a" },
                        "the session id reached a tool's arguments",
                    )
                }
            }
        }
    }

    /** The body with its `commandId` blanked, so two instances' answers can be compared. */
    private fun withoutCommandId(body: String): String =
        body.replace(Regex(""""commandId":\d+"""), """"commandId":N""")

    /**
     * A `/health` reader written against `game-bridge-mcp` README §1 and nothing later.
     *
     * Hand-written, and small on purpose: the point is that it knows *only* the original keys, so
     * a version of it that used a general JSON library and returned a map of everything would not
     * be modelling the reader whose behaviour is in question. It still has to be a real parser -
     * it walks the document, rejects malformed structure, and would fail if the additions
     * corrupted the syntax around them.
     */
    private object ContractEra {

        private val known = listOf("ok", "frame", "tick", "paused")

        /** The four contract fields, as raw JSON text. Throws on a document it cannot read. */
        fun readHealth(body: String): Map<String, String> {
            require(body.startsWith("{") && body.endsWith("}")) {
                "a /health body is a JSON object; got $body"
            }
            val members = splitMembers(body.substring(1, body.length - 1))
            val out = LinkedHashMap<String, String>()
            for ((key, value) in members) {
                if (key in known) out[key] = value
            }
            require(out.keys.toList() == known) {
                "the contract keys are missing or reordered: found ${out.keys} in $body"
            }
            return out
        }

        /** `"a":1,"b":"x"` into pairs, respecting quoted strings. */
        private fun splitMembers(body: String): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            var index = 0
            while (index < body.length) {
                require(body[index] == '"') { "expected a member name at $index in $body" }
                val nameEnd = body.indexOf('"', index + 1)
                require(nameEnd > 0) { "unterminated member name at $index in $body" }
                val name = body.substring(index + 1, nameEnd)
                require(body.getOrNull(nameEnd + 1) == ':') { "expected ':' after $name in $body" }

                var cursor = nameEnd + 2
                val valueStart = cursor
                if (body[cursor] == '"') {
                    cursor = body.indexOf('"', cursor + 1) + 1
                    require(cursor > 0) { "unterminated string value for $name in $body" }
                } else {
                    while (cursor < body.length && body[cursor] != ',') cursor++
                }
                out += name to body.substring(valueStart, cursor).trim('"')
                index = if (cursor < body.length) cursor + 1 else cursor
            }
            return out
        }
    }
}
