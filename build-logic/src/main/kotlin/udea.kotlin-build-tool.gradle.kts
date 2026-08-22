import dev.wildware.udea.build.UdeaVersions
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

/**
 * Convention for build-time-only modules — `udea-codegen`, `udea-compiler-plugin`,
 * `udea-assets-compiler`. Nothing on this convention ships in a game's runtime classpath.
 *
 * These modules are pinned to the exact project Kotlin version (spec 7): a K2 plugin or a
 * scripting host built against a different compiler than the one loading it fails at
 * class-load time.
 *
 * Two different things have to be pinned, and this convention now owns only the first:
 *
 * 1. the **Kotlin Gradle plugin** actually running the build — `getKotlinPluginVersion()`
 *    below. Close to tautological, since both sides come from the same catalog entry, but
 *    it is the cheap guard against someone applying a different KGP to one module.
 * 2. the **kotlin-stdlib actually resolved**, which the catalog does not control. That pin
 *    used to live here, which meant it protected the three build-time modules and left
 *    every runtime module resolving 2.3.21 in silence. It now lives in
 *    `udea.kotlin-library` for every module at once — see `UdeaStdlibPin`.
 */

plugins {
    id("udea.kotlin-library")
}

private val kotlinInUse = getKotlinPluginVersion()

check(kotlinInUse == UdeaVersions.KOTLIN) {
    "$path is pinned to Kotlin ${UdeaVersions.KOTLIN} but the build is running Kotlin " +
        "$kotlinInUse. Build-time-only modules must match the project Kotlin version exactly; " +
        "update gradle/libs.versions.toml and UdeaVersions.KOTLIN together."
}

extensions.extraProperties["udeaPinnedKotlinVersion"] = UdeaVersions.KOTLIN
