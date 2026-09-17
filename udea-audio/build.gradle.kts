plugins {
    // Every target, iOS included: `udea-core` gained its iOS targets when Fleks was vendored
    // (issue #215).
    id("udea.kotlin-multiplatform")
}

// What is common and what is JVM (issue #207). The SPI, `AudioDevice.Silent`, the binding table and
// the drain are all `commonMain`: none of them touches a platform API, so every target drains
// `GameContext.cues` through the same code. The one JVM-only source set is `jvmTest`, which holds
// the allocation budget - it reads HotSpot's per-thread allocation counter, which no other target
// has. A device that actually makes a noise is not here on any target: spec section 3 keeps Kool
// in `udea-render`, and the Kool-backed device is issue #221.
kotlin {
    sourceSets {
        commonMain {
            dependencies {
                // `api` and not `implementation`: `CueAudio.drain` takes a `CueQueue` and
                // `CueSourceLocator` names a `NetId`, so a consumer cannot call into this module
                // without the kernel's vocabulary being on its own compile classpath anyway.
                api(project(":udea-core"))

                // `SoundCue` - the authored volume, pitch variance and file list. `implementation`,
                // because a binding is built here and handed over as an `AudioBindings`; nothing
                // this module exposes has an asset type in its signature, so a consumer that only
                // plays cues does not inherit the asset model.
                implementation(project(":udea-assets"))
            }
        }
    }
}
