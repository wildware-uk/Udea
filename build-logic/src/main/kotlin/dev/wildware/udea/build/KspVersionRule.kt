package dev.wildware.udea.build

/**
 * What can still be checked about the catalog's `ksp` entry now that KSP no longer names a
 * Kotlin compiler in its version.
 *
 * ## What changed
 *
 * KSP used to publish one artifact per Kotlin release, versioned `<kotlin>-<ksp>`:
 * `2.2.10-2.0.2`, `2.2.20-2.0.4`, `2.2.21-2.0.5`. Under that scheme "is this KSP built against
 * our compiler" was a string comparison, and `UdeaVersionsTest` made it one. From `2.3.0`
 * onwards KSP publishes a single version of its own — `2.3.12` at the time of issue #186 — and
 * names no compiler at all, because KSP2 reads the Kotlin Analysis API rather than the compiler
 * frontend.
 *
 * So the old assertion had to go: kept as it was it would reject every KSP that exists, and
 * loosened to "starts with a digit" it would have become a check that cannot fail.
 *
 * ## What this rule is, and what it deliberately is not
 *
 * It is the *narrow* half, and it is the half that catches a real mistake: a KSP version that
 * names a compiler **other than this project's**. That is exactly the wrong turn the 2.4.20 move
 * invites — reaching for `2.4.20-2.0.2` by analogy with the version that was there before, which
 * does not exist, or leaving `2.2.10-2.0.2` in place, which does exist, resolves, and embeds a
 * 2.2 analysis API that cannot read a 2.4 metadata jar.
 *
 * It is **not** a check that a decoupled KSP supports the project's Kotlin. Nothing in the
 * version string can say that, and pretending otherwise is how a green build comes to mean
 * nothing. That claim is proved by execution instead, on `check`, in three places that all fail
 * if `kspKotlin` cannot read this compiler's output:
 *
 * - `:udea-codegen:test`, which runs the KSP2 standalone runner over real sources
 *   (`ProcessorLoggingTest`, `ProcessorFailureTest`) and diffs every generated file against
 *   `expected-generated-hashes.txt`;
 * - `udeaCheckProtocolLock`, which regenerates the wire contract and compares it to
 *   `net-protocol.lock`;
 * - every module's own `kspKotlin`, which is a compile step and cannot pass without running.
 *
 * `internal`: nothing outside `build-logic` reads it, and no precompiled script plugin does
 * either - the catalog check that uses it is `UdeaVersionsTest`, in this module's own test
 * compilation. `docs/engineering-standards.md` section 8 rejects a `public` declaration nobody
 * outside the module uses, and `UdeaVersions` is `public` only because the outer build's own
 * scripts import it.
 */
internal object KspVersionRule {

    /**
     * The old `<kotlin>-<ksp>` shape: a Kotlin version — which may itself carry a `-RC2` or
     * `-Beta1` qualifier — followed by a dotted KSP triple.
     *
     * Anchored at both ends so that a decoupled version like `2.3.12` does not match: it has one
     * dotted triple where this shape needs two.
     */
    private val KOTLIN_PREFIXED = Regex("""^(\d+\.\d+\.\d+(?:-[A-Za-z0-9]+)?)-(\d+\.\d+\.\d+)$""")

    /**
     * The Kotlin version [kspVersion] names, or `null` when it names none.
     *
     * `null` is the decoupled scheme, which is every KSP from `2.3.0` onwards.
     */
    fun kotlinVersionNamedBy(kspVersion: String): String? =
        KOTLIN_PREFIXED.matchEntire(kspVersion)?.groupValues?.get(1)

    /**
     * A message describing why [kspVersion] cannot be used with [kotlinVersion], or `null` if
     * there is nothing in the string that says it cannot.
     */
    fun mismatch(kspVersion: String, kotlinVersion: String): String? {
        val named = kotlinVersionNamedBy(kspVersion) ?: return null
        if (named == kotlinVersion) return null
        return "gradle/libs.versions.toml has ksp='$kspVersion', which is on KSP's old " +
            "<kotlin>-<ksp> scheme and therefore names Kotlin $named, but this project compiles " +
            "with Kotlin $kotlinVersion. A KSP built against a different compiler cannot read " +
            "this one's metadata. Either use the Kotlin $kotlinVersion release of that scheme, " +
            "or move to a decoupled KSP (2.3.0 and later publish one version and name no " +
            "compiler)."
    }
}
