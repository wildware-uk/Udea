import dev.wildware.udea.build.UdeaVersions
import org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion

/**
 * Convention for build-time-only modules — `udea-codegen`, `udea-compiler-plugin`,
 * `udea-assets-compiler`. Nothing on this convention ships in a game's runtime classpath.
 *
 * These modules are pinned to the exact project Kotlin version (spec 7): a K2 plugin or a
 * scripting host built against a different compiler than the one loading it fails at
 * class-load time. The check below turns that drift into a configuration-time failure
 * instead of a mystery at runtime.
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
