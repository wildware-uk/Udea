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

// --- the engine (spec 4) ---
//
// The previous engine - `common`, `gradle-plugin` and the `example` game - was deleted in issue
// #213, once `moba` and the `udea-*` modules had replaced it. The retired game's asset tree is
// kept at `example-assets/`, which is not a project: see `docs/art-assets.md`.
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
// 2D physics: Box2D 3 through `box2d-jni`, behind `udea-core`'s `PhysicsWorld`. Headless - no Kool,
// no GL - so a dedicated server, CI and a replay can all simulate it.
include("udea-physics2d")
include("udea-render")

// Presentation, like `udea-render`, and headless like every other module here: it names no GL
// type and no gdx backend. See `docs/module-graph.md` for the arrow set and for why the device
// that actually opens a `Sound` lives in the game rather than in this module.
include("udea-audio")
include("udea-agent")
include("udea-agent-host")

// The editor window (issue #194): docked ComposeGL panels over the `editor.*` tools, with the world in
// a `SceneView`. Debug-only - `UDEA-MG-010` keeps it off every shipped classpath, and only a game's
// `editor` source set may depend on it.
include("udea-editor")

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
