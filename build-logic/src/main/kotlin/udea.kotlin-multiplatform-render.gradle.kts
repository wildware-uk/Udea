import dev.wildware.udea.build.UdeaMultiplatform

/**
 * `udea.kotlin-multiplatform` without iOS, for `udea-render` (spec section 3, issue #201).
 *
 * Kool, which `udea-render` draws with, has no iOS backend (spec D2): every other module builds
 * and tests for iOS, and the renderer waits for Kool. The rest - Kotlin, Android, Wasm, the
 * stdlib pin and the compiler-plugin gates - is identical, because both conventions call the same
 * [UdeaMultiplatform.configure].
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
