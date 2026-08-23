package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentThreads
import dev.wildware.udea.agent.AgentTimings
import dev.wildware.udea.agent.dispatch.AgentRuntime
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.agent.state.ArchetypeVisitor
import dev.wildware.udea.agent.state.DigestSources
import dev.wildware.udea.agent.state.EntityCensus
import dev.wildware.udea.agent.state.StateDigest
import dev.wildware.udea.agent.tools.EngineToolModules
import dev.wildware.udea.agent.tools.EventsToolset
import dev.wildware.udea.agent.tools.LifecycleToolset
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.module.UdeaGameDef
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/**
 * A whole running instance: a real world, a real frame loop on its own thread, a real HTTP
 * surface, and the `close` tool wired to the teardown of all three.
 *
 * ## Why the loop is a thread here and a hand-pump everywhere else
 *
 * Because the two claims this exists to test are both about a process ending itself.
 * `AgentGameLoop.run` blocks; `close` has to make it return, and it has to do so from a command
 * that arrived on an HTTP thread and was executed on the loop's own. A test that pumped the
 * loop by hand could observe the flag going false and could not observe the thing that matters -
 * that nobody had to kill anything.
 *
 * Everything below the harness is the shipped path: [AgentHost.start], [AgentGameLoop],
 * [AgentRuntime], the generated tool objects and the bridge queue. The harness contributes a
 * census and a `UdeaGameDef` with no modules in it, because neither claim is about a game.
 */
internal class LiveInstance(
    artifacts: AgentArtifacts? = null,
) : AutoCloseable {

    val bridge: AgentBridge = AgentBridge()

    private val host: GameHost = GameHost(RenderMode.Headless, UdeaGameDef(modules = emptyList()))

    val shutdown: HostShutdown = HostShutdown()

    private val digest = StateDigest(
        bridge = bridge,
        sources = DigestSources(entities = EmptyCensus),
        timings = AgentTimings(),
    )

    private val tools: ToolIndex = EngineToolModules
        .wireAll(
            ToolIndex.builder(),
            EventsToolset(bridge, host.ctx.clock, artifacts?.textSpill() ?: NO_SPILL),
            LifecycleToolset(bridge, shutdown),
        )
        .build()

    private val loop = AgentGameLoop(host, AgentRuntime(bridge, tools, host.world, host.ctx, digest))

    private val identity = GameIdentity("udea-live-instance", "0.0.1")

    val agentHost: AgentHost = AgentHost.start(
        bridge,
        AgentHostConfig(
            port = 0,
            identity = identity,
            renderMode = RenderMode.Headless,
            manifest = ToolManifest.of(identity, tools.tools),
            artifacts = artifacts,
            registry = HostHarness.noRegistry(),
        ),
    )

    val port: Int get() = agentHost.port

    /** Set when [loopThread] returns. `false` while it is still spinning. */
    @Volatile
    var loopReturned: Boolean = false
        private set

    private val loopThread: Thread = AgentThreads.daemonFactory("udea-live-instance").newThread {
        loop.run()
        loopReturned = true
    }

    init {
        shutdown
            .onClose("frame-loop") { loop.stop() }
            .onClose("agent-host") { agentHost.stop() }
        digest.publish()
        loopThread.start()
    }

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()

    /** `GET path`, as text. Throws when the port is gone, which is the point in the close test. */
    fun get(path: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://${AgentHost.LOOPBACK}:$port$path"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    /** Waits up to [millis] for [condition]. Returns whether it came true. */
    fun await(millis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + millis * NANOS_PER_MILLI
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(POLL_MILLIS)
        }
        return condition()
    }

    /** Whether the loop thread has finished. */
    fun loopFinished(): Boolean = !loopThread.isAlive

    override fun close() {
        shutdown.shutdown("test teardown")
        loopThread.join(JOIN_MILLIS)
    }

    private object EmptyCensus : EntityCensus {
        override val entityCount: Int = 0

        override fun forEachArchetype(visitor: ArchetypeVisitor) = Unit
    }

    companion object {
        private val NO_SPILL = dev.wildware.udea.agent.tools.TextSpill.NONE

        private const val NANOS_PER_MILLI: Long = 1_000_000L
        private const val POLL_MILLIS: Long = 10L
        private const val JOIN_MILLIS: Long = 2_000L

        /** A scratch artifact store this JVM owns, deleted with the JVM. */
        fun scratchArtifacts(): AgentArtifacts {
            val dir = java.nio.file.Files.createTempDirectory("udea-live-artifacts")
            dir.toFile().deleteOnExit()
            return AgentArtifacts(dir as Path)
        }
    }
}
