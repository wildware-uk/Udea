// Issue #200: a throwaway spike, deliberately its own Gradle build.
//
// It is not `include`d from the root `settings.gradle.kts`, so it adds no module to Udea, leaves
// AGENTS.md's module table alone, and no root task can see it. Its Kool version is declared here
// rather than in `gradle/libs.versions.toml`, because choosing Udea's real Kool version is #211's
// job, not a spike's. Run it with the root wrapper:
//
//     xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
//       sh gradlew -p spikes/kool-offscreen run
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "kool-offscreen-spike"
