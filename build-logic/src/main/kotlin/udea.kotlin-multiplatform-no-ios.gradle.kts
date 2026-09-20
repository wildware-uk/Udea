import dev.wildware.udea.build.UdeaMultiplatform

/**
 * `dev.wildware.udea.kotlin-multiplatform` without the iOS targets: `jvm`, `android` and `wasmJs`.
 *
 * For a runtime module that cannot have an iOS target yet because something it is built on has
 * none. Spec D2 is that every Udea module builds and tests for iOS, so applying this is a named
 * exception with a reason, and the build script that applies it states that reason and the issue
 * that lifts it (`udea-render` reaches it through `dev.wildware.udea.kotlin-multiplatform-render`).
 *
 * An iOS target cannot simply be declared and left unbuilt. Kotlin compiles iOS klibs on every
 * host, not only on macOS, so a dependency with no iOS variant fails resolution on this Linux
 * box as well as on the macOS runner. Re-enabling iOS is switching the module back to
 * `dev.wildware.udea.kotlin-multiplatform`.
 */

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("udea.kotlin-base")
}

repositories {
    // AGP resolves Android's own tooling artefacts from Google's repository.
    google()
}

UdeaMultiplatform.configure(project, ios = false)
