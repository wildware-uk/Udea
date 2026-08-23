package dev.wildware.udea.assets.compiler.atlas

import dev.wildware.udea.assets.compiler.TestPaths
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

/**
 * The real art: `moba/src/main/resources/assets/sprites`, 327 sheets across 40 characters.
 *
 * Issue #89 says to pack this rather than a toy fixture, and the difference is not decorative.
 * The corpus is 2269 frames of one size, which is the exact case where a packer's tie-break
 * decides everything - with every frame 100x100, sorting by area leaves 2269 items in whatever
 * order they arrived, and the arrival order is `Files.walk`'s. A three-sheet fixture would have
 * passed a determinism test that this corpus fails.
 *
 * The tree is gitignored (`scripts/extract-art.py` reproduces it), so tests that need it check
 * [available] and are skipped when it is absent. That is a real hole and it is named here
 * rather than hidden: on a checkout without the art, the atlas determinism tests do not run.
 * [ArtPresenceTest] fails loudly when the directory exists but holds nothing, which is the
 * failure mode that would otherwise look like a pass.
 */
internal object MobaArt {

    val root: Path = TestPaths.repoRoot.resolve("moba/src/main/resources/assets/sprites")

    val available: Boolean get() = root.isDirectory()

    /**
     * Every PNG under [root] as a one-row sheet, id'd by its path relative to the asset root.
     *
     * The column count is read from the image: this art is horizontal strips of square frames,
     * so `width / height` is the frame count. Reading it rather than declaring it means a sheet
     * whose dimensions change is packed correctly instead of being packed wrong and silently
     * blitting the neighbouring frame.
     */
    @OptIn(ExperimentalPathApi::class)
    fun sheets(): List<SheetInput> = root.walk()
        .filter { it.extension.equals("png", ignoreCase = true) }
        .map { file ->
            val id = "sprites/" + file.relativeTo(root).toString().replace('\\', '/').removeSuffix(".png")
            val (width, height) = dimensionsOf(file)
            SheetInput(id = id, file = file, columns = width / height, rows = 1)
        }
        // Sorted here so a caller that does *not* shuffle still gets a defined order; the
        // packer sorts again, which is what the reversed-order tests prove.
        .sortedBy { it.id }
        .toList()

    private fun dimensionsOf(file: Path): Pair<Int, Int> {
        val image = ImageIO.read(file.toFile()) ?: error("$file is not a readable image")
        return image.width to image.height
    }
}
