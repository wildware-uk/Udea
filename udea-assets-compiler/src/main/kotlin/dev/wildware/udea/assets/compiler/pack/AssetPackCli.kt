package dev.wildware.udea.assets.compiler.pack

import dev.wildware.udea.assets.compiler.AssetCompiler
import dev.wildware.udea.assets.compiler.atlas.AtlasPacker
import dev.wildware.udea.assets.compiler.atlas.SheetInput
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeBytes
import kotlin.system.exitProcess

/**
 * `udeaPackBundle`, as a process: a `.udea.kts` tree and a sprite tree in, one `.udeapak` out.
 *
 * ## Why this exists at all, and what it is not
 *
 * "Two clean builds produce a byte-identical `.udeapak`" is a Phase 2 exit criterion, and until
 * this class landed the only thing that produced a bundle was `ReproducibilityTest`, which packs
 * twice **inside one JVM**. That is the stronger test of the two in one respect - it packs from
 * two differently named checkout roots, which is where a leaked absolute path shows up - and
 * strictly weaker in another: it never writes a file, so it cannot catch anything that varies
 * between *processes* rather than between directories. Two JVMs differ in hash seed, in
 * `HashMap` iteration order for any map keyed by an identity-hashed object, in class-loading
 * order and in default charset. A single-JVM comparison is blind to all of it.
 *
 * So this is the artifact half of the criterion, and the two are complementary rather than
 * redundant. Neither alone proves the claim.
 *
 * This is **not** the shipped asset pipeline. `UdeaAssetsPlugin` - `udeaScanAssets`,
 * `udeaGenerateAccessors`, `udeaPackBundle` as a `@CacheableTask` with `InputChanges` and an
 * `assetRoots` extension - lives in `:udea-gradle` and is not written. Nothing in `:moba`'s
 * build produces or consumes a bundle yet; `MobaScene` still slices a PNG at runtime. What
 * follows is a `JavaExec` an operator or a CI job runs by name, which is exactly enough to
 * settle the exit criterion and no more.
 *
 * ```
 * udeaPackBundle <repoRoot> <assetRoot> <spriteRoot|-> <outFile>
 * ```
 *
 * Exits non-zero when compiling or packing reported an error, so a CI job that runs it twice and
 * diffs cannot mistake two identical failures for two identical bundles.
 */
public object AssetPackCli {

    @JvmStatic
    public fun main(args: Array<String>) {
        if (args.size != 4) {
            System.err.println("usage: udeaPackBundle <repoRoot> <assetRoot> <spriteRoot|-> <outFile>")
            exitProcess(2)
        }
        val repoRoot = Path.of(args[0]).toAbsolutePath().normalize()
        val assetRoot = Path.of(args[1]).toAbsolutePath().normalize()
        val spriteRoot = if (args[2] == "-") null else Path.of(args[2]).toAbsolutePath().normalize()
        val out = Path.of(args[3]).toAbsolutePath().normalize()

        val classpath = System.getProperty("udea.assetsCompiler.classpath").orEmpty()
            .split(java.io.File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { Path.of(it) }
            .filter { it.exists() }
        check(classpath.isNotEmpty()) {
            "system property 'udea.assetsCompiler.classpath' is empty; the udeaPackBundle task " +
                "sets it to the classpath the .udea.kts are compiled against"
        }

        val compiler = AssetCompiler(
            repoRoot = repoRoot,
            assetRoot = assetRoot,
            scriptClasspath = classpath,
            // Under the output, not under the source tree: the cache is a build artifact, and a
            // pack that wrote into the checkout would make the second of two clean builds start
            // from a state the first one left.
            cacheDirectory = out.parent.resolve("pack-cache"),
        )
        val result = compiler.compile(AssetCompiler.scriptsUnder(assetRoot))
        if (result.hasErrors) {
            result.diagnostics.forEach { System.err.println("[udeaPackBundle] ${it.ruleId} ${it.message}") }
            exitProcess(1)
        }
        val packed = GraphPacker.pack(result.graph)
        if (packed.hasErrors) {
            packed.diagnostics.forEach { System.err.println("[udeaPackBundle] ${it.ruleId} ${it.message}") }
            exitProcess(1)
        }

        val sheets = spriteRoot?.let(::sheetsUnder).orEmpty()
        val atlas = if (sheets.isEmpty()) PackedAtlas.EMPTY else AtlasPacker().pack(sheets)
        val bytes = BundleWriter.write(BundleContent.reachable(assets = packed.assets, atlas = atlas))

        out.parent?.createDirectories()
        out.writeBytes(bytes)
        println("[udeaPackBundle] $out")
        println(
            "[udeaPackBundle] ${packed.assets.size} assets, ${sheets.size} sheets, " +
                "${atlas.pages.size} atlas pages, ${bytes.size} bytes",
        )
        println("[udeaPackBundle] sha256=${sha256(bytes)}")
    }

    /**
     * Every PNG under [root] as a one-row sheet, id'd by its path relative to [root].
     *
     * The frame count is read from the image - this art is horizontal strips of square frames, so
     * `width / height` is the count - rather than declared, so a sheet whose dimensions changed is
     * packed correctly rather than packed wrong and silently blitting its neighbour. Sorted by id
     * here as well as inside [AtlasPacker], so that a caller comparing two runs is comparing the
     * packer's determinism rather than `Files.walk`'s.
     */
    @OptIn(ExperimentalPathApi::class)
    private fun sheetsUnder(root: Path): List<SheetInput> {
        if (!root.isDirectory()) return emptyList()
        return root.walk()
            .filter { it.extension.equals("png", ignoreCase = true) }
            .mapNotNull { file ->
                val image = ImageIO.read(file.toFile()) ?: return@mapNotNull null
                if (image.height <= 0 || image.width < image.height) return@mapNotNull null
                SheetInput(
                    id = "sprites/" + file.relativeTo(root).toString().replace('\\', '/').removeSuffix(".png"),
                    file = file,
                    columns = image.width / image.height,
                    rows = 1,
                )
            }
            .sortedBy { it.id }
            .toList()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
