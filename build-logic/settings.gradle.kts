dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        // The Android Gradle Plugin is published only to Google's repository.
        google()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

// The version catalog, published so a game outside this repository can compile against the same
// Kotlin, KSP and JUnit the engine does (issue #265). It lives in this build rather than in the
// outer one for two reasons: the outer build's `settings.gradle.kts` is what `udeaVerifyAgentsMd`
// and the module-graph gate enumerate, and a project there would have to be a module of the
// engine with an arrow in `AGENTS.md`; and a catalog is build logic, which is what this build is.
include("version-catalog")
