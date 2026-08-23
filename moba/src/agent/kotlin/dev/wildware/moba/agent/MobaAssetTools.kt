package dev.wildware.moba.agent

import dev.wildware.moba.MobaAssets
import dev.wildware.udea.agent.assets.AssetHotReload
import dev.wildware.udea.agent.assets.AssetToolModule
import dev.wildware.udea.agent.assets.AssetsToolset
import dev.wildware.udea.agent.dispatch.ToolIndex
import dev.wildware.udea.assets.compiler.daemon.AssetDaemon
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.loop.barrier
import java.io.File
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * The dev asset daemon, wired into `moba`'s own agent host.
 *
 * ## What this closes
 *
 * The `assets.*` toolset worked, was gated at 300ms, and was reachable from exactly one place:
 * `Phase2Demo`, a class in `udea-agent-host`'s **test** source set that seeds an asset tree under
 * `build/` and boots a headless host over it. That demo proved the mechanism and proved nothing
 * about this game - an agent that launched `moba` through `game-bridge-mcp` got the world, time
 * and render toolsets and no way to edit an asset at all. This is the same three objects over the
 * real corpus and the real running simulation.
 *
 * ## Why it is in `src/agent` and nowhere else
 *
 * [AssetDaemon] carries `kotlin-compiler-embeddable`. `UDEA-MG-005` forbids that on the shipped
 * game's runtime classpath and `ReleaseRules.CLASSPATH_RULE` fails a release build that resolves
 * it, so the dependency is declared on `agentImplementation` and every line that names the
 * daemon lives in this source set. `jar` packages `main`, so no part of it is in the artifact
 * either - the same argument [MobaAgent] makes about `udea-agent-host`.
 *
 * ## The daemon and the bundle agree by construction
 *
 * The running graph is [MobaAssets.registry], decoded from the `.udeapak` that
 * `:moba:udeaPackBundle` wrote. The daemon compiles the *same* asset root and turns declarations
 * into values through `PackedValues`, which is the bundle writer and the bundle reader rather
 * than a second interpretation of the DSL. So a hot-reloaded `SpriteSheet` is the same object a
 * rebuild would have produced, and an agent cannot get the game into a state a build could not
 * reproduce. That property is the reason `daemon/AssetPacker` was deleted rather than kept.
 *
 * ## When there is no source tree
 *
 * A packaged game has assets and no `.udea.kts`. [wire] answers that by registering nothing and
 * saying so on stderr: the tools are absent from `/tools` rather than present and failing, which
 * is the difference between an agent planning around a missing capability and an agent
 * discovering it mid-turn.
 */
internal object MobaAssetTools {

    /** Where the asset tree is, absolute. Set by `:moba:run`; absent in a packaged game. */
    const val ASSET_ROOT_PROPERTY: String = "udea.assets.root"

    /** The repository root every diagnostic span is relative to. Set by `:moba:run`. */
    const val REPO_ROOT_PROPERTY: String = "udea.repoRoot"

    /** The classpath `.udea.kts` compile against. The spelling every other host uses. */
    const val SCRIPT_CLASSPATH_PROPERTY: String = "udea.assetsCompiler.classpath"

    /**
     * Registers `assets.*` on [builder], or returns it untouched.
     *
     * @return the builder, and the daemon when one was started - the caller needs it to close.
     */
    fun wire(builder: ToolIndex.Builder, host: GameHost): Wired {
        val assetRoot = pathProperty(ASSET_ROOT_PROPERTY)
        if (assetRoot == null || !assetRoot.isDirectory()) {
            System.err.println(
                "[moba.agent] no asset source tree, so assets.* is not registered. " +
                    "`-D$ASSET_ROOT_PROPERTY=<dir>` names one; `:moba:run` sets it. A packaged " +
                    "game legitimately has none - it ships the .udeapak and not the scripts.",
            )
            return Wired(builder, null)
        }
        val scriptClasspath = System.getProperty(SCRIPT_CLASSPATH_PROPERTY).orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { Path.of(it) }
            .filter { it.exists() }
        if (scriptClasspath.isEmpty()) {
            System.err.println(
                "[moba.agent] $ASSET_ROOT_PROPERTY names $assetRoot but " +
                    "$SCRIPT_CLASSPATH_PROPERTY is empty, so no script could be compiled and " +
                    "assets.* is not registered.",
            )
            return Wired(builder, null)
        }

        val repoRoot = pathProperty(REPO_ROOT_PROPERTY) ?: Path.of("").toAbsolutePath()
        val daemon = AssetDaemon(
            repoRoot = repoRoot,
            assetRoot = assetRoot,
            scriptClasspath = scriptClasspath,
            // Under `build/`, never the process working directory: the old runtime script host
            // wrote `./scripts/cache` wherever the JVM happened to start, which meant a cache
            // per launch directory and none of them ever cleaned.
            cacheDirectory = repoRoot.resolve("moba/build/udea/agent-script-cache"),
        )
        val started = daemon.start()
        println(
            "[moba.agent] asset daemon: ok=${started.ok} ${started.durationMs}ms " +
                "${daemon.ids.size} assets over $assetRoot",
        )
        if (!started.ok) {
            // Registered anyway, and deliberately. A daemon whose corpus has a typo in it is the
            // exact situation `assets.validate` exists for; refusing to serve the tools that
            // report the typo would turn a dev loop into a restart loop.
            started.diagnostics.forEach { System.err.println("[moba.agent] ${it.ruleId} ${it.message}") }
        }
        val hotReload = AssetHotReload(MobaAssets.registry, host.ctx.barrier, host.ctx.clock)
        return Wired(AssetToolModule.wire(builder, AssetsToolset(daemon, hotReload)), daemon)
    }

    private fun pathProperty(name: String): Path? =
        System.getProperty(name)?.takeIf { it.isNotBlank() }?.let { Path.of(it).toAbsolutePath().normalize() }

    /** The builder, and the daemon when one was started. */
    internal data class Wired(val builder: ToolIndex.Builder, val daemon: AssetDaemon?)
}
