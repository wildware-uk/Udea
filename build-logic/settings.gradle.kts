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
