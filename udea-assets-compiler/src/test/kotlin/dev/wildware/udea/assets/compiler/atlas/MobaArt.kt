package dev.wildware.udea.assets.compiler.atlas

import dev.wildware.udea.assets.compiler.TestPaths
import java.nio.file.Path

/**
 * The real art: `moba/src/main/resources/assets/sprites`, 327 sheets across 40 characters.
 *
 * Issue #89 says to pack this rather than a toy fixture, and the difference is not decorative.
 * The corpus is 2269 frames of one size, which is the exact case where a packer's tie-break
 * decides everything - with every frame 100x100, sorting by area leaves 2269 items in whatever
 * order they arrived, and the arrival order is `Files.walk`'s. A three-sheet fixture would have
 * passed a determinism test that this corpus fails.
 *
 * ## The tree is gitignored, and that used to mean the tests did not run
 *
 * `scripts/extract-art.py` is the only thing that produces this tree, and it needs the two paid
 * Tiny RPG archives. So on every clone but the owner's, [available] was false and the nine atlas
 * determinism tests aborted their assumptions and reported green having checked nothing.
 *
 * Issue #168 closed that. [SyntheticArt] draws a corpus of the same shape - 327 one-row sheets,
 * 2269 frames, every frame 100x100 - from nothing, and [AtlasPackerTest] and
 * [dev.wildware.udea.assets.compiler.pack.ReproducibilityTest] run against **that**, everywhere,
 * with nothing to buy. This corpus is now the *additional* run: [RealArtAtlasPackerTest] and
 * [dev.wildware.udea.assets.compiler.pack.RealArtReproducibilityTest] are the same test bodies
 * pointed at it, and they still skip when it is absent - which is the honest report of "the real
 * pixels were not checked here", not a hole in the property, because the property is checked by
 * the synthetic run in the same task.
 *
 * The CI step `Assert the atlas determinism tests ran and none skipped` in the `build` job holds
 * that arrangement in place: it names the two synthetic classes' result files and fails unless
 * each reports more than zero tests and none skipped.
 */
internal object MobaArt : SpriteCorpus {

    override val name: String = "moba-art"

    override val root: Path = TestPaths.repoRoot.resolve("moba/src/main/resources/assets/sprites")

    /** Present in both Tiny RPG packs, and the character the pixel-level tests have always used. */
    override val sampleCharacter: String = "sprites/champions/archer/"

    /** What a skip says, so the message names the one script that can produce this tree. */
    override val unavailable: String =
        "moba sprite art is absent; run python scripts/extract-art.py"
}
