package dev.wildware.udea.build

/**
 * Versions that build logic itself needs as compile-time constants, and that therefore
 * cannot be read from the version catalog at the point of use.
 *
 * The single authoritative source is `gradle/libs.versions.toml`. Anything here is a
 * mirror of it and is asserted equal to it by `UdeaVersionsTest`, so the two cannot drift.
 */
public object UdeaVersions {
    /**
     * The exact Kotlin version the whole project compiles with.
     *
     * `udea-compiler-plugin` (K2 FIR/IR) and `udea-assets-compiler` are pinned to this
     * version exactly — a K2 plugin built against a different compiler than the one
     * loading it fails at class-load time, not at compile time.
     */
    public const val KOTLIN: String = "2.2.10"

    /** JDK release every module targets. */
    public const val JVM_TOOLCHAIN: Int = 17
}
