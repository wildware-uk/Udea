package dev.wildware.udea.build

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/**
 * The project's version catalog. Precompiled script plugins get no generated `libs`
 * accessors, so convention plugins reach the catalog through here rather than through
 * hardcoded coordinates.
 */
public val Project.udeaCatalog: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** Looks a library up by catalog alias, failing loudly rather than silently skipping it. */
public fun Project.udeaLibrary(alias: String): Provider<MinimalExternalModuleDependency> =
    udeaCatalog.findLibrary(alias).orElseThrow {
        IllegalStateException(
            "No library '$alias' in gradle/libs.versions.toml. " +
                "New modules must take versions from the catalog, never a literal.",
        )
    }
