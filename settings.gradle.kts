pluginManagement {
    // Convention plugins for every new udea-* module and for moba.
    includeBuild("build-logic")

    repositories {
        mavenCentral()
        gradlePluginPortal()
        // The Android Gradle Plugin, which `build-logic`'s multiplatform conventions put on the
        // build classpath (issue #201), is published only here.
        google()
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
include("udea-fleks")
include("udea-core")
include("udea-assets")
include("udea-assets-compiler")
include("udea-gas")
include("udea-net")
include("udea-render")

// Presentation, like `udea-render`, and headless like every other module here: it names no GL
// type and no gdx backend. See `docs/module-graph.md` for the arrow set and for why the device
// that actually opens a `Sound` lives in the game rather than in this module.
include("udea-audio")
include("udea-agent")
include("udea-agent-host")

// Phase 7's retrofit (issues #147-#149): the `.udearep` recording, the deterministic headless
// replay, and the bisect tools. Headless like every other module here - it names `udea-core` and
// `udea-agent` and nothing that has ever seen a device.
include("udea-replay")
include("udea-gradle")

// --- the example game, as four nested projects (spec D12, issue #212) ------------------------
//
// One module per thing that can be built on its own, rather than flat `moba-*` modules: the game
// is a library with no `main` in it, and each launcher is a project whose targets are decided by
// the platform it launches on rather than by the game's.
//
// `:moba:web` is deliberately absent rather than declared and empty. Kool publishes no `wasmJs`
// artifact (issue #223), so `udea-render` has no `wasmJs` target and a browser client cannot be
// built today; issue #226 adds this line with the module it names. A project declared now would
// be one that fails resolution on every build, which is worse than one that does not exist.
include("moba:game")
include("moba:desktop")
include("moba:android")
