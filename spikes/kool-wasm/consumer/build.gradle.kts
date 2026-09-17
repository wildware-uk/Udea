@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

// Udea's own Kotlin (gradle/libs.versions.toml `kotlin`), on Udea's own Gradle wrapper, so this
// answers "can Udea read a Kool built this way" rather than "can Kool read itself".
plugins {
    kotlin("multiplatform") version "2.4.20"
}

// ../reproduce.sh publishes Kool main as this version into the scratch repository.
val koolVersion = "0.20.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    jvm()
    wasmJs {
        outputModuleName = "kool-wasm-spike"
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation("de.fabmax.kool:kool-core:$koolVersion")
        }
    }
}
