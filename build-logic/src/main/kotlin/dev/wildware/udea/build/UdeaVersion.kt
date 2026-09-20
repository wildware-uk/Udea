package dev.wildware.udea.build

/**
 * What version the engine publishes under: what the release button asked for, and failing that,
 * the git tag (issue #265).
 *
 * `-PudeaVersion=0.2.0` is how the Release workflow names a build. It needs to say, for two
 * reasons: a snapshot is published off no tag at all, and a release is compiled and signed
 * *before* it is tagged, so a commit that cannot be built leaves no tag behind. The workflow
 * makes the tag out of the same number it passed in and then checks the two agree, so the
 * promise still holds - a published artifact and its tag cannot disagree.
 *
 * With no property the tag decides, and releasing by hand is still `git tag v0.2.0 && git push
 * --tags`.
 *
 * Off a tag you get a snapshot of the *next* minor rather than the last one. `0.1.0-SNAPSHOT`
 * after 0.1.0 has been released is a mutable version wearing the name of an immutable one that
 * already exists on Central, and whoever depends on it gets whichever they happened to fetch.
 *
 * The rule is a function of two strings rather than a block inside a build script because three
 * callers need the same answer - the root build script, which versions every `udea-*` module;
 * `build-logic`, which publishes the convention plugins a game applies; and `UdeaVersionTest`,
 * which is the only place any of its branches can be executed.
 *
 * This is ComposeGL's rule, deliberately: `/srv/ssd1/workspace/composegl/build.gradle.kts` is
 * where the publishing arrangement this repository copies comes from, and a developer who has
 * read one should not have to learn the other.
 */
public object UdeaVersion {
    /**
     * What a repository with no release tag publishes.
     *
     * This repository is in exactly that state today - `git describe --tags --always` answers a
     * bare commit id - so this is the version `publishToMavenLocal` produces on this branch, and
     * the one `scripts/outside-game-proof.sh` resolves the engine at unless it is told otherwise.
     */
    public const val FIRST_SNAPSHOT: String = "0.1.0-SNAPSHOT"

    /** The Gradle property the release workflow names the version with. */
    public const val PROPERTY: String = "udeaVersion"

    /** `v1.4.2` and nothing after it: the commit that is exactly a release. */
    private val EXACT_TAG = Regex("^v(\\d+\\.\\d+\\.\\d+)$")

    /** `v1.4.2-7-gdeadbee` or `v1.4.2-dirty`: a release tag with commits or edits on top. */
    private val AFTER_TAG = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)-.+$")

    /**
     * The version to publish under.
     *
     * @param asked the `-PudeaVersion` property, or null when it was not passed. Blank counts as
     *   not passed: `-PudeaVersion=` is a property that is set and empty, and taking it literally
     *   would publish an artifact with no version at all.
     * @param described the output of `git describe --tags --always --dirty`, or the empty string
     *   when git could not answer - an exported tree, or a machine with no git on it. Neither is
     *   an error here: a build that cannot see its own history publishes [FIRST_SNAPSHOT] and the
     *   release workflow passes [PROPERTY] anyway.
     */
    public fun resolve(asked: String?, described: String): String {
        val named = asked?.trim()
        if (!named.isNullOrEmpty()) return named

        val tag = described.trim()
        EXACT_TAG.matchEntire(tag)?.let { return it.groupValues[1] }
        AFTER_TAG.matchEntire(tag)?.let {
            val major = it.groupValues[1]
            val minor = it.groupValues[2].toIntOrNull() ?: return FIRST_SNAPSHOT
            return "$major.${minor + 1}.0-SNAPSHOT"
        }
        return FIRST_SNAPSHOT
    }
}
