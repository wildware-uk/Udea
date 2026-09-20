import dev.wildware.udea.build.UdeaMultiplatform

/**
 * The convention for `udea-render`: Kotlin Multiplatform on `jvm` and `android` (spec section 3,
 * issues #201 and #211).
 *
 * Two targets fewer than `dev.wildware.udea.kotlin-multiplatform`, and each for a reason that lives in Kool,
 * which `udea-render` draws with:
 *
 * - **no iOS**: Kool has no iOS backend (spec D2). Every other module builds and tests for iOS;
 *   the renderer waits for Kool.
 * - **no wasmJs**: Kool 0.19.0 publishes no wasmJs artifact, so the target fails dependency
 *   resolution before it compiles a line. Issue #223 is the Wasm build of Kool, and passing
 *   `wasm = true` below is the change that follows it.
 *
 * It applies the same plugins as the other multiplatform conventions and calls the same
 * `UdeaMultiplatform.configure`, so the rest - Kotlin, Android, the stdlib pin and the
 * compiler-plugin gates - is identical by construction.
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

UdeaMultiplatform.configure(project, ios = false, wasm = false)
