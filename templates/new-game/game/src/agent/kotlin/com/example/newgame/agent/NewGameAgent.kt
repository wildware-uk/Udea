package com.example.newgame.agent

import com.example.newgame.NewGame
import com.example.newgame.sim.RoverSystem
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentTimings
import dev.wildware.udea.agent.dispatch.AgentRuntime
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.agent.state.DigestSources
import dev.wildware.udea.agent.state.StateDigest
import dev.wildware.udea.agent.tools.EngineToolModules
import dev.wildware.udea.agent.tools.EventsToolset
import dev.wildware.udea.agent.tools.LifecycleToolset
import dev.wildware.udea.agent.tools.TimeToolset
import dev.wildware.udea.agent.host.AgentHost
import dev.wildware.udea.agent.host.AgentHostConfig
import dev.wildware.udea.agent.host.AgentGameLoop
import dev.wildware.udea.agent.host.GameIdentity
import dev.wildware.udea.agent.host.HostShutdown
import dev.wildware.udea.agent.host.ToolManifest
import dev.wildware.udea.core.host.RenderMode
import com.example.newgame.agent.UdeaAgentBuildFlags

/**
 * The launcher: the game, and the agent's HTTP surface when a port was asked for.
 *
 * ## Why this lives in `src/agent` and not in `src/main`
 *
 * Because `udea-agent-host` binds an HTTP port onto the live simulation and writes any field of
 * any entity, and that is a thing a developer wants and a player must never get. The `agent`
 * source set is the only classpath in this project that resolves it, `jar` packages `main`, and
 * `udeaVerifyRelease` fails a `-Pudea.release=true` build that carries either - so the guarantee
 * is the module graph's rather than a promise in a comment.
 *
 * `UdeaAgentBuildFlags` is generated into this source set per build: `false` on a release build,
 * which is the second half of the same guard.
 */
public object NewGameAgent {

    /** How many ticks a run with no agent port simulates before it prints and exits. */
    private const val TICKS: Int = 600

    @JvmStatic
    public fun main(args: Array<String>) {
        val host = NewGame.host(RenderMode.Headless)
        val bridge = AgentBridge()
        val shutdown = HostShutdown()
        // The `/state` document. `DigestSources()` is the empty default, so `/state` reports the
        // tick, the pause, the time scale and the result of every command - and `entityCount: 0`,
        // because counting entities means an `EntityCensus` over this game's own components.
        // `moba`'s is `MobaCensus`; write one when your game has components worth counting.
        val digest = StateDigest(bridge = bridge, sources = DigestSources(), timings = AgentTimings())
        // Which of the engine's toolsets this game's agent surface offers.
        //
        // `TimeToolset` needs only the host, so every game can have it: pause, step a known
        // number of ticks, set the time scale, snapshot and rewind. That is what makes an agent
        // session reproducible - step, look, step again - rather than a race against a running
        // clock.
        //
        // `WorldToolset` is deliberately absent here, and it is the one that needs something
        // this template does not have: an `AgentComponentIndex` over `@Replicated` components,
        // and this game declares none (`docs/new-game.md`, "What the template does not cover
        // yet"). `moba/desktop/src/agent/.../MobaAgent.kt` is the worked example of wiring it.
        val tools: ToolIndex = EngineToolModules
            .wireAll(
                ToolIndex.builder(),
                TimeToolset(host.time, host.ctx.clock, bridge),
                EventsToolset(bridge, host.ctx.clock),
                LifecycleToolset(bridge, shutdown),
            )
            .build()
        val identity = GameIdentity("new-game", "0.1.0")

        val agentHost = AgentHost.startIfRequested(
            bridge = bridge,
            config = { port ->
                AgentHostConfig(
                    port = port,
                    identity = identity,
                    renderMode = RenderMode.Headless,
                    manifest = ToolManifest.of(identity, tools.tools),
                    // What `/health` reports as `paused`. Without it the field is the default
                    // `false` for ever, and an agent that called `time.pause` and then read
                    // `/health` would be told the game is running while its tick stands still -
                    // a true-looking answer to the question it actually asked. `host.time` is the
                    // same `TimeControl` the `time.*` tools drive, so the two cannot disagree.
                    paused = { host.time.paused },
                )
            },
            // The generated flag, not a constant: `-Pudea.release=true` regenerates it as `false`
            // and no port is ever bound.
            agentAllowed = UdeaAgentBuildFlags.AGENT_ALLOWED,
        )

        if (agentHost == null) {
            // No port was asked for, so this is a developer's own run: simulate a fixed number of
            // ticks and print what happened. Deterministic, so two runs print the same thing.
            host.run(TICKS)
            val rovers = host.world.system<RoverSystem>()
            println("new-game ran ${host.totalTicks} ticks; rovers at ${rovers.positions()}")
            return
        }

        val loop = AgentGameLoop(host, AgentRuntime(bridge, tools, host.world, host.ctx, digest))
        shutdown
            .onClose("frame-loop") { loop.stop() }
            .onClose("agent-host") { agentHost.stop() }
        Runtime.getRuntime().addShutdownHook(Thread { shutdown.shutdown("jvm shutdown hook") })
        digest.publish()
        println("new-game serving the agent surface on ${agentHost.port}")
        loop.run()
        shutdown.shutdown("the frame loop ended")
    }
}
