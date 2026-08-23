plugins {
    id("udea.kotlin-library")
}

dependencies {
    // `api` and not `implementation`: `CueAudio.drain` takes a `CueQueue` and `CueSourceLocator`
    // names a `NetId`, so a consumer cannot call into this module without the kernel's vocabulary
    // being on its own compile classpath anyway.
    api(project(":udea-core"))

    // `SoundCue` - the authored volume, pitch variance and file list. `implementation`, because a
    // binding is built here and handed over as an `AudioBindings`; nothing this module exposes has
    // an asset type in its signature, so a consumer that only plays cues does not inherit the
    // asset model.
    implementation(project(":udea-assets"))
}
