pluginManagement {
    // Convention plugins for every new udea-* module and for moba.
    includeBuild("build-logic")

    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "udea"

// --- old tree: replaced module by module, deleted at the Phase 6 exit (spec 6) ---
//
// `level-editor`, `idea-plugin` and `compose-ui` are gone: D6 drops all three outright, since
// the MCP tool surface *is* the editor (spec 1). They had no replacement to wait for, so they
// went in Phase 0. The three below do have replacements and stay until those land - see
// `docs/migration/ledger.md` for the retirement order and the gate that settles each one.
include("common")
include("gradle-plugin")
include("example")
include("example:assets")

// --- the rewrite (spec 4). No project here may have `common` on its compile classpath. ---
include("udea-annotations")
include("udea-diagnostics")
include("udea-codegen")
include("udea-compiler-plugin")
include("udea-core")
include("udea-assets")
include("udea-assets-compiler")
include("udea-gas")
include("udea-net")
include("udea-render")
include("udea-agent")
include("udea-agent-host")
include("udea-gradle")
include("moba")
