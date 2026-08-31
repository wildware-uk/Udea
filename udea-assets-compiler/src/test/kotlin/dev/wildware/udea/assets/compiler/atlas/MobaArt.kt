package dev.wildware.udea.assets.compiler.atlas

import dev.wildware.udea.assets.compiler.TestPaths
import java.nio.file.Path

/**
 * The real art: `moba/src/main/resources/assets/sprites`, 327 sheets across 40 characters.
 *
 * Issue #89 says to pack this rather than a toy fixture, and the difference is not decorative.
 * The corpus is 2269 frames of one size, which is the exact case where a packer's tie-break
 * decides everything: [AtlasPacker] orders by `(height desc, width desc, id asc)`, and with every
 * frame 100x100 the first two keys separate nothing, so without the id tie-break all 2269 land in
 * whatever order they arrived - which is `Files.walk`'s, and therefore the filesystem's.
 *
 * The fixture somebody writes instead is a few sheets of *different* sizes, and that one is
 * totally ordered by height before the tie-break is ever consulted: it passes a determinism test
 * this corpus fails. [SmallFixtureContrastTest] is that fixture, kept as the control, and the
 * pair of them is what makes the assertion worth its seconds.
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

    /**
     * The character the pixel-level tests have always used.
     *
     * Not asserted to exist here, because this file cannot see the tree on a machine without the
     * archives; `AtlasPackerContract.sampleCharacter` fails with "has no sheets under" if the name
     * ever stops matching what `scripts/extract-art.py` writes.
     */
    override val sampleCharacter: String = "sprites/champions/archer/"

    /** What a skip says, so the message names the one script that can produce this tree. */
    override val unavailable: String =
        "moba sprite art is absent; run python scripts/extract-art.py"
}
