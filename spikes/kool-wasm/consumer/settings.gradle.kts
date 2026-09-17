// Issue #225: a throwaway spike, deliberately its own Gradle build, like spikes/kool-offscreen.
//
// It is not `include`d from the root `settings.gradle.kts`, so it adds no module to Udea and no
// root task can see it. It consumes a Kool built from source by ../reproduce.sh into a scratch
// Maven repository, which `-Pspike.repo=<dir>` names; nothing here is published. Run it through
// ../reproduce.sh, or by hand with the root wrapper:
//
//     sh gradlew -p spikes/kool-wasm/consumer -Pspike.repo=/srv/ssd1/workspace/kool-spike-225/m2 \
//       compileKotlinJvm wasmJsBrowserDistribution
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

val spikeRepo = providers.gradleProperty("spike.repo").orNull
    ?: error("-Pspike.repo=<scratch maven repo that reproduce.sh published Kool into> is required")

dependencyResolutionManagement {
    repositories {
        // Only Kool comes from the scratch repository, so a stray artifact there cannot shadow
        // anything else this build resolves.
        exclusiveContent {
            forRepository { maven { url = uri(spikeRepo) } }
            filter { includeGroup("de.fabmax.kool") }
        }
        mavenCentral()
        google()
    }
}

rootProject.name = "kool-wasm-spike"
