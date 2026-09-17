plugins {
    // Multiplatform (issue #205): jvm, android, wasmJs, iosArm64, iosSimulatorArm64. This module
    // depends on no Fleks, so unlike `udea-core` it takes the full runtime set. Every JVM
    // consumer - the asset compiler, the engine, moba - resolves the `jvm` variant unchanged.
    id("udea.kotlin-multiplatform")
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
