package dev.wildware.udea.agent.host.net

import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `net.relevancy` and `net.assert_not_visible`, driven the way an agent drives them.
 *
 * Both go through the real [AgentToolDef.invoke][dev.wildware.udea.agent.AgentToolDef.invoke]
 * with a real [AgentCommand], because the argument coercion and the refusal kinds *are* the tool
 * — a test that called the Kotlin behind them would leave the half an agent actually touches
 * untested, which is how a tool ships answering `bad_argument` to every call.
 *
 * The session is the shipped [NetSession]: a real `ReplicationServer` holding a real
 * [dev.wildware.udea.net.relevancy.FogOfWar] as its `RelevancySet`, over the real wire. So
 * "client 1 cannot see client 2" here is the same sentence as "the packer skipped it".
 */
class RelevancyToolsTest {

    @Test
    fun `a session without fog says so rather than answering an empty set`() {
        val toolset = spawn(vision = 0f)

        val refused = assertIs<AgentResult.Failed>(
            relevancy(toolset, client = 1),
            "a fogless session answered a relevancy query as though it had computed one",
        )
        assertEquals("no_fog", refused.error.kind.id)
        assertContains(refused.error.message, "vision_radius")
    }

    @Test
    fun `a client sees its own avatar and nothing of the other team`() {
        val toolset = spawn(vision = VISION)
        step(toolset, WARMUP_TICKS)

        val mine = ok(relevancy(toolset, client = 1))
        val theirs = ok(relevancy(toolset, client = 2))

        assertContains(mine, """"visible":1""", message = "client 1 should see exactly its own avatar")
        assertContains(mine, """"reason":"OwnTeam"""")
        assertTrue(rawIdIn(theirs) != rawIdIn(mine), "the two clients hold different avatars")
    }

    @Test
    fun `assert_not_visible passes for an entity on the other side of the fog`() {
        val toolset = spawn(vision = VISION)
        step(toolset, WARMUP_TICKS)
        val enemy = rawIdIn(ok(relevancy(toolset, client = 2)))

        val result = ok(assertNotVisible(toolset, client = 1, netId = enemy))

        assertContains(result, """"notVisible":true""")
        assertContains(result, """"reason":"Hidden"""")
        // The instrumentation spec 7 asks for, on the same reply, so a cost regression is visible
        // from the tool that is already being called rather than from one nobody thinks to call.
        assertContains(result, """"teamSolves"""")
        assertContains(result, """"overBudgetSolves":0""")
    }

    @Test
    fun `assert_not_visible fails and names the granting source once the unit walks into sight`() {
        val toolset = spawn(vision = VISION)
        step(toolset, WARMUP_TICKS)
        val enemy = rawIdIn(ok(relevancy(toolset, client = 2)))
        assertIs<AgentResult.Ok>(assertNotVisible(toolset, client = 1, netId = enemy))

        walkTogether(toolset)

        val leak = assertIs<AgentResult.Failed>(
            assertNotVisible(toolset, client = 1, netId = enemy),
            "the two avatars are on top of each other and the assertion still passed",
        )
        assertEquals("relevancy_leak", leak.error.kind.id)
        assertContains(leak.error.message, "IS visible to client 1")
        assertContains(leak.error.message, "Sighted")
    }

    @Test
    fun `the report names the granting source and counts the transitions`() {
        val toolset = spawn(vision = VISION)
        walkTogether(toolset)

        val report = ok(relevancy(toolset, client = 1))

        assertContains(report, """"visible":2""", message = "client 1 now sees its own avatar and the enemy")
        assertContains(report, """"reason":"Sighted"""")
        assertContains(report, """"enters":1""", message = "one entry and no oscillation on a straight approach")
        assertContains(report, """"leaves":0""")
    }

    @Test
    fun `a client the session does not have is refused rather than defaulted`() {
        val toolset = spawn(vision = VISION)

        val refused = assertIs<AgentResult.Failed>(relevancy(toolset, client = 7))

        assertEquals("no_such_peer", refused.error.kind.id)
    }

    @Test
    fun `both tools are on the net module and describe themselves`() {
        val names = NetToolModule.tools.map { it.name }

        assertContains(names, "net.relevancy")
        assertContains(names, "net.assert_not_visible")
        for (tool in listOf(NetRelevancyTool, NetAssertNotVisibleTool)) {
            assertTrue(
                tool.description.length > MIN_DESCRIPTION,
                "${tool.name} needs a description that reads correctly in /tools as a model sees it",
            )
            assertContains(tool.inputSchema, "client")
        }
        assertContains(NetAssertNotVisibleTool.inputSchema, "net_id")
    }

    // --- driving ------------------------------------------------------------------------------

    private fun spawn(vision: Float): NetToolset {
        val toolset = NetToolset()
        val spawned = assertIs<AgentResult.Ok>(
            toolset.spawnSession(
                clients = CLIENTS,
                seed = SEED,
                latencyMs = 0,
                jitterMs = 0,
                loss = 0f,
                visionRadius = vision,
            ),
        )
        assertContains(spawned.json, """"clients":2""")
        return toolset
    }

    private fun step(toolset: NetToolset, ticks: Int) {
        assertIs<AgentResult.Ok>(toolset.step(ticks))
    }

    /**
     * Drives the two avatars into each other until they are unambiguously in sight.
     *
     * A bounded loop in **small** steps rather than a fixed tick count. Two things force that:
     * the arena moves an avatar a tenth of a unit per *applied* command and the jitter buffer
     * decides when a command applies, so the rate is not this test's to hard-code; and the two are
     * walking straight through each other, so a coarse step can start them apart, end them apart,
     * and step right over the sighting in between. When they meet the input is released, so the
     * assertions that follow read a settled session rather than one still accelerating apart.
     */
    private fun walkTogether(toolset: NetToolset) {
        toolset.input(client = 1, moveX = 1f, moveY = 0f, aim = 0f, buttons = 0)
        toolset.input(client = 2, moveX = -1f, moveY = 0f, aim = 0f, buttons = 0)
        repeat(APPROACH_ROUNDS) {
            step(toolset, APPROACH_TICKS)
            if (""""reason":"Sighted"""" in ok(relevancy(toolset, client = 1))) {
                toolset.input(client = 1, moveX = 0f, moveY = 0f, aim = 0f, buttons = 0)
                toolset.input(client = 2, moveX = 0f, moveY = 0f, aim = 0f, buttons = 0)
                step(toolset, SETTLE_TICKS)
                return
            }
        }
        error("the avatars never met in ${APPROACH_ROUNDS * APPROACH_TICKS} ticks")
    }

    private fun relevancy(toolset: NetToolset, client: Int): AgentResult =
        NetRelevancyTool.invoke(
            toolset,
            AgentCommand("net.relevancy", mapOf("client" to client.toString())),
        ) as AgentResult

    private fun assertNotVisible(toolset: NetToolset, client: Int, netId: Int): AgentResult =
        NetAssertNotVisibleTool.invoke(
            toolset,
            AgentCommand(
                "net.assert_not_visible",
                mapOf("client" to client.toString(), "net_id" to netId.toString()),
            ),
        ) as AgentResult

    private fun ok(result: AgentResult): String = when (result) {
        is AgentResult.Ok -> result.json
        is AgentResult.Failed -> error("expected a successful result, got $result")
    }

    /** The first `netIdRaw` in a rendered report. */
    private fun rawIdIn(json: String): Int =
        RAW_ID.find(json)?.groupValues?.get(1)?.toInt()
            ?: error("no netIdRaw in $json")

    private companion object {

        const val CLIENTS: Int = 2
        const val SEED: Long = 909_090L

        /**
         * One world unit of sight.
         *
         * Small on purpose: `NetSession` spawns the avatars three sight radii apart, so a small
         * radius is a small gap, and the arena's tenth-of-a-unit-per-tick movement can close it
         * inside a test rather than inside a minute.
         */
        const val VISION: Float = 1f

        /** Enough ticks for the first packets to land and the fog to have solved several times. */
        const val WARMUP_TICKS: Int = 30

        /** Small enough that the two cannot pass through each other inside one round. */
        const val APPROACH_TICKS: Int = 8

        const val APPROACH_ROUNDS: Int = 120

        /** Long enough for the sighting to reach the client and the packet to be acked. */
        const val SETTLE_TICKS: Int = 20

        /** A tool description that fits in one line is not a description (spec, Phase 5 exit). */
        const val MIN_DESCRIPTION: Int = 200

        val RAW_ID = Regex("\"netIdRaw\":(-?\\d+)")
    }
}
