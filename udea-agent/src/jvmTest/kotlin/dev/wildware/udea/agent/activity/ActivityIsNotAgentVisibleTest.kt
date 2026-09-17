package dev.wildware.udea.agent.activity

import com.github.quillraven.fleks.configureWorld
import dev.wildware.udea.agent.AgentClock
import dev.wildware.udea.agent.AgentCommand
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.dispatch.AgentDispatcher
import dev.wildware.udea.agent.dispatch.DeferredQueue
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.agent.tools.EngineToolModules
import dev.wildware.udea.agent.tools.SayToolset
import dev.wildware.udea.core.fixtures.testGameContext
import dev.wildware.udea.core.gameContext
import dev.wildware.udea.agent.state.DigestFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec 3.7's correctness requirement, stated on the data side: **the agent cannot read its own
 * overlay inputs**.
 *
 * ## Why this is a test and not a comment
 *
 * `udea-render`'s type split stops overlay *pixels* reaching a capture. It says nothing about
 * `/state`, and `/state` is the other half of what an agent observes. An agent doing
 * capture/act/capture/diff also polls the digest between the two captures; a caption or a tool
 * history rendered into it would change between the two polls for the same reason a caption in
 * the framebuffer would, and the agent would conclude the *game* had changed.
 *
 * "We did not add it to the digest" is not a property, it is a state of affairs, and it decays
 * the first time somebody adds a field to `StateDigest` and reaches for the nearest available
 * member of `AgentBridge` - which is where both of these now live. This fails if they do.
 */
class ActivityIsNotAgentVisibleTest {

    @Test
    fun `neither the caption nor the tool history reaches the state document`() {
        val fixture = DigestFixture(entityCount = 12)
        val bridge = fixture.bridge

        bridge.narration.say(CAPTION, ttlSeconds = 60f, AgentSessionId.LOCAL)
        val slot = bridge.activity.begin(
            AgentCommand(TOOL_NAME, mapOf("id" to "266")),
            tick = 4L,
            session = AgentSessionId.LOCAL,
            anchor = AnchorRule.NONE,
        )
        bridge.activity.complete(slot, 1L, AgentOutcome.OK, durationNanos = 1_000L)

        fixture.digest.publish()
        val document = bridge.snapshot()

        assertFalse(
            document.contains(CAPTION),
            "the agent's own caption is in the document the agent polls; capture/act/capture " +
                "would read it changing as the game changing:\n$document",
        )
        assertFalse(
            document.contains(TOOL_NAME),
            "the agent's own tool history is in the document the agent polls, so it pays " +
                "tokens to be told what it just did:\n$document",
        )
        assertFalse(
            document.contains("activity") || document.contains("narration"),
            "a section named for the overlay's state has appeared in the digest:\n$document",
        )
        // The positive control: the digest is a real document with real content, so the three
        // assertions above are statements about what is missing from something, not about an
        // empty string.
        assertTrue(document.contains("tick"), "the digest rendered nothing at all: $document")
    }

    @Test
    fun `agent say does not echo the caption back, so it cannot return through state`() {
        // The subtler leak, and the reason this drives the *real* tool rather than hand-writing
        // a plausible answer: `/state` renders recent command answers verbatim, so an
        // `agent.say` that returned its own text would put the caption inside the document the
        // agent polls by the back door - and the tool would look perfectly innocent doing it.
        val fixture = DigestFixture()
        val bridge = fixture.bridge
        val ctx = testGameContext()
        val world = configureWorld { injectables { gameContext(ctx) } }
        val tools = ToolIndex.builder()
            .module(EngineToolModules.Say)
            .toolset(SayToolset(bridge))
            .build()
        val dispatcher = AgentDispatcher(bridge, tools, DeferredQueue(), AgentClock.System)
        val command = AgentCommand("agent.say", mapOf("text" to CAPTION, "ttlSeconds" to "60"))

        dispatcher.run(command, world, ctx)

        val answer = bridge.commandResults().single { it.id == command.id }.result
        assertTrue(answer is AgentResult.Ok, "agent.say failed: $answer")
        assertFalse(
            answer.json.contains(CAPTION),
            "agent.say echoed the caption back, so it reaches /state through the command " +
                "result ring: ${answer.json}",
        )

        fixture.digest.publish()
        assertFalse(bridge.snapshot().contains(CAPTION), bridge.snapshot())
    }

    @Test
    fun `a rewind does not rewrite what the human saw the agent do`() {
        // The ring lives on the bridge, which is in no FieldStore and no snapshot. That is not
        // an oversight: the agent really did make those calls, and a panel that un-made them as
        // the world rewound would be lying about the only history the human has.
        val bridge = DigestFixture().bridge
        bridge.activity.begin(
            AgentCommand("world.spawn_blueprint"),
            tick = 400L,
            session = AgentSessionId.LOCAL,
            anchor = AnchorRule.NONE,
        )

        // A rewind, as the agent surface performs one: the tick goes backwards.
        bridge.publishTick(40L)

        assertEquals(
            1,
            bridge.activity.size,
            "the activity ring lost entries when the simulation was rewound",
        )
    }

    private companion object {
        const val CAPTION: String = "zz-caption-marker-zz"
        const val TOOL_NAME: String = "zz.tool_marker"
    }
}
