package dev.wildware.moba.agent

import com.github.quillraven.fleks.World
import dev.wildware.moba.GruntBlueprint
import dev.wildware.moba.MobaGame
import dev.wildware.moba.Position
import dev.wildware.moba.PositionReplicator
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentTimings
import dev.wildware.udea.agent.dispatch.AgentRuntime
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.agent.host.AgentArtifacts
import dev.wildware.udea.agent.host.AgentGameLoop
import dev.wildware.udea.agent.host.AgentHost
import dev.wildware.udea.agent.host.AgentHostConfig
import dev.wildware.udea.agent.host.AgentHostTools
import dev.wildware.udea.agent.host.ArtifactToolset
import dev.wildware.udea.agent.host.GameIdentity
import dev.wildware.udea.agent.host.RenderControl
import dev.wildware.udea.agent.host.RenderToolset
import dev.wildware.udea.agent.host.ToolManifest
import dev.wildware.udea.agent.query.AgentComponentIndex
import dev.wildware.udea.agent.query.AgentComponentType
import dev.wildware.udea.agent.query.agentComponent
import dev.wildware.udea.agent.state.ArchetypeVisitor
import dev.wildware.udea.agent.state.DigestSources
import dev.wildware.udea.agent.state.EntityCensus
import dev.wildware.udea.agent.state.LoopStatus
import dev.wildware.udea.agent.state.StateDigest
import dev.wildware.udea.agent.tools.BlueprintCatalog
import dev.wildware.udea.agent.tools.DiagToolset
import dev.wildware.udea.agent.tools.EngineToolModules
import dev.wildware.udea.agent.tools.EventsToolset
import dev.wildware.udea.agent.tools.TimeToolset
import dev.wildware.udea.agent.tools.WorldToolset
import dev.wildware.udea.core.blueprint.blueprints
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.loop.barrier
import dev.wildware.udea.core.module.CoreModule
import java.nio.file.Path

/**
 * `moba.agent`: the instance `game-bridge-mcp` launches.
 *
 * `gradlew.bat :moba:run -PdebugPort=7825 --console=plain` - which is exactly the command line
 * the generated `gamebridge.json` names, because `UdeaAgentPlugin` writes both from the same
 * property.
 *
 * ## Why this file is in `src/agent` and not in `src/main`
 *
 * It is the only code in `moba` that names `udea-agent-host`, and `ReleaseRules.CLASSPATH_RULE`
 * refuses a release build that resolves that module on `runtimeClasspath`. A source set of its
 * own keeps it off that classpath **and** out of the jar, which a `compileOnly` dependency would
 * not: that arrangement ships a `main` class whose first statement throws `NoClassDefFoundError`,
 * and calls it absence.
 *
 * ## Two loops, one simulation
 *
 * In [RenderMode.Headless] there is no render backend, so [AgentGameLoop] is the frame loop and
 * it pumps [AgentRuntime] itself. In the GL modes the render thread owns the cadence, so the same
 * [AgentGameLoop.pump] is handed to `Lwjgl3Backend.drive` as the frame callback. Both call the
 * identical three-step - drain the queue, advance the host, publish the digest - because handing
 * the backend `host::frame` instead (which is what a client does) would accept commands onto the
 * bridge and execute none of them.
 *
 * ## What is real here and what is not
 *
 * Real: the game, the loop, the barrier, the snapshot ring, the tool index, the HTTP surface, the
 * registry entry, and - since the Phase 1 demo was driven end to end - the pixels. In either GL
 * mode [MobaRenderControl] joins [RenderToolset] to the live `RenderPipeline`, so `render.screenshot`
 * returns PNG bytes of the actual world and a rewind is visible as a diff between two of them.
 *
 * Still not real: `render.follow_entity` is accepted and does nothing, because `CameraRig` tracks
 * a `PhysicsBody` and a `moba` unit has only a [Position] (see `MobaScene`); `render.toggle_debug_draw`
 * flips a switch no renderer here reads; and in [RenderMode.Headless] there is no context at all,
 * so every render tool correctly answers `no_render_context`.
 */
public object MobaAgent {

    /** Boots, binds if a port was given, and blocks. */
    @JvmStatic
    public fun main(args: Array<String>) {
        val mode = MobaEntry.modeFromProperties(fallback = RenderMode.Offscreen)
        if (mode == RenderMode.Headless) {
            val host = MobaGame.host(RenderMode.Headless)
            // No GL context in Headless, so no capture surface exists and `null` is the
            // honest answer: every `render.*` tool then answers `no_render_context`.
            val session = attach(host, RenderMode.Headless, control = null)
            Runtime.getRuntime().addShutdownHook(Thread { session.close() })
            session.loop.run()
            session.close()
            return
        }
        MobaEntry.runWithGl(mode) { host, rendering ->
            val session = attach(host, mode, MobaRenderControl(rendering.presentation()))
            MobaEntry.Attachment(frame = session.loop::pump, close = session::close)
        }
    }

    /**
     * Wires every toolset, binds the HTTP surface if `-Dudea.agent.port` was passed, and seeds.
     *
     * The surface is bound **before** the first frame and the digest is published once here, so a
     * `/state` that beats the loop reads a document rather than an empty string. The registry
     * entry is written by [AgentHost] itself, after the port is bound - never before, because an
     * entry naming a port nobody claimed is worse than no entry.
     */
    private fun attach(host: GameHost, mode: RenderMode, control: RenderControl?): Session {
        val bridge = AgentBridge()
        val timings = AgentTimings()
        val census = MobaCensus(host.world)
        val digest = StateDigest(
            bridge = bridge,
            sources = DigestSources(entities = census, loop = LoopView(host)),
            timings = timings,
        )
        val artifacts = AgentArtifacts(
            Path.of("build", "udea-agent-artifacts").toAbsolutePath(),
        )

        val worldTools = WorldToolset(
            world = host.world,
            components = AgentComponentIndex(listOf(positionAccess())),
            netIds = host.ctx[CoreModule.NET_IDS],
            bridge = bridge,
            clock = host.ctx.clock,
            catalog = BlueprintCatalog.of(listOf(GruntBlueprint)),
            spawner = host.ctx.blueprints,
        )
        val tools = EngineToolModules
            .wireAll(
                ToolIndex.builder(),
                worldTools,
                TimeToolset(host.time, host.ctx.clock, bridge),
                EventsToolset(bridge, host.ctx.clock),
                DiagToolset(
                    bridge = bridge,
                    clock = host.ctx.clock,
                    timings = timings,
                    census = census,
                    digest = digest,
                    barrier = host.ctx.barrier,
                ),
            )
            .module(AgentHostTools)
            .toolset(RenderToolset(mode, control, artifacts))
            .toolset(ArtifactToolset(artifacts))
            .build()

        val identity = GameIdentity(MobaGame.NAME, MobaGame.VERSION)
        val agentHost = AgentHost.startIfRequested(
            bridge = bridge,
            config = { port ->
                AgentHostConfig(
                    port = port,
                    identity = identity,
                    renderMode = mode,
                    manifest = ToolManifest.of(identity, tools.tools),
                    artifacts = artifacts,
                    paused = { host.time.paused },
                )
            },
            // The generated per-variant flag, not `udea-agent-host`'s hand-written `true`. A
            // `-Pudea.release=true` build regenerates this as false, and the gate then refuses to
            // bind even if somebody put the module back on the classpath.
            agentAllowed = UdeaAgentBuildFlags.AGENT_ALLOWED,
        )

        MobaEntry.seed(host)
        digest.publish()
        if (agentHost == null) {
            System.err.println(
                "[moba.agent] no agent surface; pass -PdebugPort=<port> (or -Dudea.agent.port). " +
                    "Running as a plain $mode instance.",
            )
        } else {
            println(
                "[moba.agent] listening on http://127.0.0.1:${agentHost.port} in $mode with " +
                    "${tools.tools.size} tools",
            )
        }
        return Session(
            loop = AgentGameLoop(host, AgentRuntime(bridge, tools, host.world, host.ctx, digest)),
            agentHost = agentHost,
        )
    }

    /** [Position], with x and y writable and `hp` not - so `field_not_writable` is reachable. */
    private fun positionAccess(): AgentComponentType = agentComponent(
        name = "Position",
        replicator = PositionReplicator,
        componentType = Position,
        agentWritableFields = setOf(PositionReplicator.FIELD_X, PositionReplicator.FIELD_Y),
    )

    /** The loop and the surface, so one `close` tears both down in the right order. */
    private class Session(val loop: AgentGameLoop, private val agentHost: AgentHost?) {
        fun close() {
            // Loop first: stopping the surface while a tool call is mid-drain would leave the
            // caller holding a closed connection to a command that did run.
            loop.stop()
            agentHost?.stop()
        }
    }
}

/**
 * Counts what Fleks already counts.
 *
 * [EntityCensus]'s contract is that counts are maintained incrementally and never by walking the
 * world, and `World.numEntities` honours that - Fleks keeps it as a field. The archetype
 * breakdown is **not** honest bookkeeping: `moba` has one blueprint, so every entity is reported
 * under `grunt`. A game with several must count them at its own spawn and despawn sites.
 */
private class MobaCensus(private val world: World) : EntityCensus {

    override val entityCount: Int get() = world.numEntities

    override fun forEachArchetype(visitor: ArchetypeVisitor) {
        visitor.visit(GruntBlueprint.id.value, entityCount)
    }
}

/** The digest's window onto the real loop, so `/state.paused` is the loop's own answer. */
private class LoopView(private val host: GameHost) : LoopStatus {
    override val paused: Boolean get() = host.loop.paused
    override val timeScale: Float get() = host.loop.timeScale
    override val fps: Float get() = 0f
}
