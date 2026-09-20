plugins {
    // Multiplatform (issue #205): jvm, android, wasmJs, iosArm64, iosSimulatorArm64. This module
    // depends on no Fleks, so unlike `udea-core` it takes the full runtime set. Every JVM
    // consumer - the asset compiler, the engine, moba - resolves the `jvm` variant unchanged.
    id("dev.wildware.udea.kotlin-multiplatform")
}

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":udea-annotations"))
                implementation(project(":udea-diagnostics"))

                // `api`: `BundleReader.open` and `BundleSource.of` take a kotlinx-io `Path`, which
                // is how a `.udeapak` on disk is named on every target (spec section 6, "Files").
                api(libs.kotlinx.io.core)
            }
        }
    }
}

// The one-line description Maven Central requires of a published artifact (issue #265).
description =
    "Udea's runtime asset model and the reader for the .udeapak archive the asset pipeline " +
    "produces."
