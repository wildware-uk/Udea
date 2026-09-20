import dev.wildware.udea.build.UdeaMultiplatform

/**
 * The convention for a Udea runtime module: Kotlin Multiplatform on `jvm`, `android`, `wasmJs`,
 * `iosArm64` and `iosSimulatorArm64` (spec section 3, issue #201).
 *
 * Build-time modules - `udea-codegen`, `udea-compiler-plugin`, `udea-assets-compiler`,
 * `udea-gradle` - stay on `dev.wildware.udea.kotlin-library`: they run inside the compiler or Gradle, which
 * are JVM processes, so there is nowhere else for them to run.
 *
 * A JVM consumer of a module on this convention needs no change. Gradle's variant matching picks
 * the `jvm` variant for a JVM classpath on its own, which is how KSP, the K2 plugin and every
 * JVM module keep resolving `udea-annotations` and `udea-diagnostics` after they moved here.
 *
 * iOS test binaries link and run only on macOS. Elsewhere the Kotlin plugin still compiles the
 * iOS klibs but skips `iosSimulatorArm64Test` rather than failing it, so `allTests` off macOS runs
 * every target *but* iOS; the `ios-tests` CI job is where the iOS tests actually run.
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

UdeaMultiplatform.configure(project, ios = true)
