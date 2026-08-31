package dev.wildware.udea.assets.compiler.atlas

import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The packer against a full-size corpus, on every machine.
 *
 * This is the run issue #168 added. It used to be the *only* class here and it read the real
 * Tiny RPG corpus, which meant it aborted its assumptions on every clone but one and reported
 * green having packed nothing at all. [SyntheticArt] has the same shape and no licence, so this
 * class no longer knows how to skip, and the `Assert the atlas determinism tests ran and none
 * skipped` step in CI's `build` job fails the run if it ever learns.
 */
internal class AtlasPackerTest : AtlasPackerContract() {
    override val corpus: SpriteCorpus = SyntheticArt
}

/**
 * The same body against the real art, which only the owner's machine can supply.
 *
 * Kept deliberately: [SyntheticArt] proves the packer is deterministic over the *shape*, and this
 * proves it over the actual pixels - a decode path that the synthetic corpus, drawn by this
 * repository's own encoder, cannot honestly stand in for. It skips when the art is absent, and
 * that skip is now a statement that the real pixels were not checked here rather than a hole in
 * the property, because [AtlasPackerTest] checked the property in the same task.
 */
internal class RealArtAtlasPackerTest : AtlasPackerContract() {
    override val corpus: SpriteCorpus = MobaArt

    override fun requireCorpus(): Unit = assumeTrue(corpus.available, corpus.unavailable)
}
