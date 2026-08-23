package dev.wildware.udea.agent.assets

import dev.wildware.udea.agent.AgentError
import dev.wildware.udea.agent.AgentResult
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.agent.tools.ToolsetHarness
import dev.wildware.udea.assets.AssetData
import dev.wildware.udea.assets.AssetRegistry
import dev.wildware.udea.assets.compiler.daemon.AssetDaemon
import dev.wildware.udea.core.loop.barrier
import java.io.File
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A real daemon, a real headless game, and the `assets` toolset dispatched through a real
 * [ToolIndex].
 *
 * Nothing here is a double. The daemon compiles real `.udea.kts` with the real Kotlin scripting
 * host; the registry is built from what that daemon packed; the barrier is the running host's, so
 * a delta pushed by `assets.write` genuinely waits for a tick. A fake daemon would leave the one
 * thing issue #92 is about - a warm compiler answering fast enough to be an editor - untested.
 *
 * The one hop that is **not** exercised is HTTP. `game-bridge-mcp` calls
 * `AgentDispatcher`/`ToolIndex` over `/command`, and issue #92 puts that host explicitly out of
 * scope; [call] enters at the same `ToolIndex` the HTTP handler enters at, and no further.
 */
internal class AssetToolHarness(name: String) {

    private val repoRoot: Path = Path(
        checkNotNull(System.getProperty("udea.repoRoot")) {
            "system property 'udea.repoRoot' is not set; udea-agent's build script sets it"
        },
    )

    /** The writable asset tree. Under `build` because the scripting host holds jar handles open. */
    val assetRoot: Path = scratch("$name/assets")

    val daemon: AssetDaemon = AssetDaemon(
        repoRoot = repoRoot,
        assetRoot = assetRoot,
        scriptClasspath = checkNotNull(System.getProperty("udea.assetsCompiler.classpath"))
            .split(File.pathSeparatorChar).filter { it.isNotBlank() }.map { Path(it) },
        cacheDirectory = scratch("$name/cache"),
    )

    private val game = ToolsetHarness(withSnapshotRing = false)

    lateinit var registry: AssetRegistry
        private set

    lateinit var hotReload: AssetHotReload
        private set

    private lateinit var tools: ToolIndex

    /** Writes `<assetRoot>/<relative>` and returns the absolute path. */
    fun write(relative: String, text: String): Path {
        val file = assetRoot.resolve(relative)
        file.parent.createDirectories()
        file.writeText(text.trimIndent() + "\n")
        return file.toAbsolutePath().normalize()
    }

    /**
     * Starts the daemon over what has been written and wires the toolset over a registry built
     * from it.
     *
     * The registry is built *after* the daemon has packed, from the daemon's own values, because
     * that is the relationship a running game has with the daemon that fed it: the game holds what
     * the last build produced, and the daemon holds the same thing.
     */
    fun start(): AssetToolHarness {
        val report = daemon.start()
        assertTrue(report.ok, "the harness corpus must be valid: ${report.diagnostics}")
        val values: Array<AssetData> = daemon.ids.mapNotNull { daemon.value(it) }.toTypedArray()
        registry = AssetRegistry(values, contentHash = ByteArray(32))
        hotReload = AssetHotReload(registry, game.host.ctx.barrier, game.host.ctx.clock)
        tools = AssetToolModule.wire(ToolIndex.builder(), AssetsToolset(daemon, hotReload)).build()
        return this
    }

    /** Every tool name the index advertises, sorted. */
    fun toolNames(): List<String> = tools.tools.map { it.name }.sorted()

    /** Calls [name] through the real index, exactly as an HTTP handler would. */
    fun call(name: String, vararg args: Pair<String, String>): AgentResult =
        tools.invoke(dev.wildware.udea.agent.AgentCommand(name, args.toMap()))

    /** [call], asserting success, returning the rendered document. */
    fun ok(name: String, vararg args: Pair<String, String>): String {
        val result = call(name, *args)
        return assertIs<AgentResult.Ok>(result, "$name failed: $result").json
    }

    /** [call], asserting refusal. */
    fun failure(name: String, vararg args: Pair<String, String>): AgentError {
        val result = call(name, *args)
        return assertIs<AgentResult.Failed>(result, "$name unexpectedly succeeded: $result").error
    }

    /** Runs one tick, so a pushed delta reaches the registry through the barrier drain. */
    fun tick() {
        game.sim.step(1)
    }

    private fun scratch(relative: String): Path {
        val dir = repoRoot.resolve("udea-agent/build/tmp/scratch/$relative")
        dir.toFile().deleteRecursively()
        dir.toFile().mkdirs()
        return dir
    }
}
