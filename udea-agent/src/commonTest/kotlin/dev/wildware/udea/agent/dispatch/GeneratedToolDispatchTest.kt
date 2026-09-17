package dev.wildware.udea.agent.dispatch

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentErrorKind
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.harness.SimHarness
import dev.wildware.udea.agent.state.StateDigest
import dev.wildware.udea.agent.tools.EngineToolModules
import dev.wildware.udea.agent.tools.TimeToolset
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import dev.wildware.udea.generated.UdeaAgentUdeaRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A KSP-generated tool, dispatched on every target this module builds for (issue #208).
 *
 * Common code, so the same assertions run as `jvmTest` and as `wasmJsNodeTest`. The tool is
 * `time.step`, and nothing about it is hand-written for the test: `TimeToolsetStepTool` is what
 * the processor generated from `TimeToolset.step`, its argument parsing is generated with it,
 * `EngineToolModules` indexes it, and `SimHarness` submits the command through the same
 * `AgentBridge` and `AgentRuntime` drain the JVM host uses. A module that compiled for Wasm but
 * could not generate, index or dispatch a tool there fails here rather than in a browser.
 */
class GeneratedToolDispatchTest {

    private class Game {
        val bridge = AgentBridge()
        val host = GameHost(
            RenderMode.Headless,
            UdeaGameDef(registry = UdeaAgentUdeaRegistry, modules = emptyList()),
        )
        val index: ToolIndex = EngineToolModules
            .wireAll(ToolIndex.builder(), TimeToolset(host.time, host.ctx.clock, bridge))
            .build()
        val sim = SimHarness(host, bridge, index, StateDigest(bridge))
    }

    @Test
    fun `a generated tool is dispatched and moves the simulation by exactly what it was asked`() {
        val game = Game()
        val before = game.host.tick.value

        val result = game.sim.call("time.step", "ticks" to "7")

        val json = assertIs<AgentResult.Ok>(result, "time.step failed: $result").json
        assertEquals(before + 7, game.host.tick.value)
        assertTrue(json.contains("\"ticksStepped\":7"), json)
        assertTrue(json.contains("\"tickAfter\":${before + 7}"), json)
    }

    @Test
    fun `the generated argument parser refuses text that is not a number and steps nothing`() {
        val game = Game()
        val before = game.host.tick.value

        val result = game.sim.call("time.step", "ticks" to "seven")

        val failed = assertIs<AgentResult.Failed>(result, "time.step accepted 'seven': $result")
        assertEquals(AgentErrorKind.BAD_ARGUMENT, failed.error.kind)
        assertEquals(before, game.host.tick.value)
    }

    @Test
    fun `the index carries the generated description, not an empty one`() {
        val step = Game().index.tools.single { it.name == "time.step" }

        assertTrue(step.description.isNotBlank(), "time.step has no description")
        assertEquals(TimeToolset::class, step.owner)
    }
}
