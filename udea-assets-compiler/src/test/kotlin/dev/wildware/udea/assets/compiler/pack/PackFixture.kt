package dev.wildware.udea.assets.compiler.pack

import dev.wildware.udea.assets.compiler.AssetCompiler
import dev.wildware.udea.assets.compiler.AssetGraph
import dev.wildware.udea.assets.compiler.TestPaths
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Evaluates a `.udea.kts` tree and packs it, the way the pipeline will.
 *
 * Everything a determinism test needs is a *parameter* here - the repo root and the asset root
 * both - so a test can run the same pack from two different directories. That is not a
 * convenience: the acceptance criterion for issue #89 is byte-identical output "including when
 * run from two different checkout directories", and a helper that read `user.dir` would make
 * that criterion untestable.
 */
internal object PackFixture {

    /** Repo-relative path of the pack corpus, from either checkout root. */
    const val ASSETS: String = "udea-assets-compiler/src/test/resources/packassets"

    /**
     * The kind-correct corpus this issue's tests pack.
     *
     * Not `resources/assets`: that tree exercises the *front ends* and is shaped for their
     * tests. This one is kind-correct end to end, so a round-trip failure here means the writer
     * or the reader is wrong rather than the corpus being deliberately odd.
     */
    val assetRoot: Path = TestPaths.repoRoot.resolve(ASSETS)

    /** Every id the pack corpus declares, written out so a silent drop is a failing test. */
    val EXPECTED_IDS: Set<String> = setOf(
        "config",
        "blueprint/player",
        "blueprint/minion",
        "character/orc",
        "character/orc_idle",
        "character/orc_walk",
        "character/orc_idle_anim",
        "character/orc_walk_anim",
        "character/orc_attack_cue",
        "character/orc_dust",
        "level/arena",
    )

    /** Compiles every script under [assetRoot] and returns the evaluated graph. */
    fun compile(repoRoot: Path, assetRoot: Path, cacheName: String): AssetGraph {
        val compiler = AssetCompiler(
            repoRoot = repoRoot,
            assetRoot = assetRoot,
            // Only entries that exist. `build/classes/java/test` is on the test runtime
            // classpath but is never created (this module has no Java sources), and the Kotlin
            // script compiler reports a missing classpath entry as a compilation diagnostic -
            // which would make every pack test fail for a reason that has nothing to do with
            // packing.
            scriptClasspath = TestPaths.compilerClasspath.filter { it.exists() },
            cacheDirectory = TestPaths.scratch(cacheName),
        )
        val result = compiler.compile(AssetCompiler.scriptsUnder(assetRoot))
        check(!result.hasErrors) {
            "the fixture corpus must compile cleanly; it reported " +
                result.diagnostics.joinToString("\n") { "${it.ruleId} ${it.message}" }
        }
        return result.graph
    }

    /** Compiles and packs, returning the bundle bytes. */
    fun bundle(
        repoRoot: Path,
        assetRoot: Path,
        cacheName: String,
        atlas: PackedAtlas = PackedAtlas.EMPTY,
    ): ByteArray {
        val packed = GraphPacker.pack(compile(repoRoot, assetRoot, cacheName))
        check(!packed.hasErrors) {
            "packing reported " + packed.diagnostics.joinToString("\n") { "${it.ruleId} ${it.message}" }
        }
        return BundleWriter.write(BundleContent(assets = packed.assets, atlas = atlas))
    }
}
