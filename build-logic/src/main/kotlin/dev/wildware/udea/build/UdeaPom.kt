package dev.wildware.udea.build

/**
 * What Maven Central insists on before it will take a release: a name, a description, a home, a
 * licence, a human, and where the source is (issue #265).
 *
 * The values live here rather than in the build script that writes them so that the engine's
 * modules and `build-logic`'s own plugins cannot describe themselves as two different projects.
 * The root build script reads these; `build-logic/build.gradle.kts` cannot - it is the build that
 * compiles this class - so it carries them as literals and `UdeaPomTest` reads that file and
 * fails when the two stop agreeing.
 *
 * Signing and the upload itself are configured by properties rather than in code, so the key and
 * the token live in CI's secrets and never in the repository. Without them everything here still
 * builds: `publishToMavenLocal` works on any machine, it just cannot publish.
 */
public object UdeaPom {
    /** The group every Udea artifact is published under. `dev.wildware` is the verified namespace. */
    public const val GROUP: String = "dev.wildware.udea"

    /** The project's home, and the `scm` url. */
    public const val URL: String = "https://github.com/wildware-uk/Udea"

    public const val INCEPTION_YEAR: String = "2026"

    /**
     * MIT, because that is what `LICENSE` in the root of this repository is.
     *
     * ComposeGL, whose publishing arrangement this copies, publishes under Apache-2.0 and is
     * Apache-2.0 licensed. Udea is not: a POM saying Apache-2.0 over an MIT tree would be a false
     * statement in metadata that outlives the branch that wrote it. It also happens to be what
     * `udea-fleks` needs - vendored Fleks is MIT (`udea-fleks/NOTICE.md`) - so every published
     * artifact declares one licence and it is the right one for all of them.
     */
    public const val LICENCE_NAME: String = "MIT License"
    public const val LICENCE_URL: String = "https://opensource.org/licenses/MIT"

    public const val DEVELOPER_ID: String = "shaun-wild"
    public const val DEVELOPER_NAME: String = "Shaun Wild"
    public const val DEVELOPER_URL: String = "https://github.com/shaun-wild"

    public const val SCM_CONNECTION: String = "scm:git:git://github.com/wildware-uk/Udea.git"
    public const val SCM_DEVELOPER_CONNECTION: String = "scm:git:ssh://git@github.com/wildware-uk/Udea.git"

    /**
     * Every value above, as the literal text a build script writing it would contain.
     *
     * `UdeaPomTest` checks `build-logic/build.gradle.kts` against this list. A list rather than a
     * count, because a count is a claim that goes stale the next time somebody adds a field.
     */
    public val VALUES: List<String> = listOf(
        GROUP,
        URL,
        INCEPTION_YEAR,
        LICENCE_NAME,
        LICENCE_URL,
        DEVELOPER_ID,
        DEVELOPER_NAME,
        DEVELOPER_URL,
        SCM_CONNECTION,
        SCM_DEVELOPER_CONNECTION,
    )
}
