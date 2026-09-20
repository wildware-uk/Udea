import dev.wildware.udea.build.UdeaMultiplatform

/**
 * Kotlin Multiplatform on `jvm` and `android` only, for an engine module whose native library is
 * published for those two and for nothing else.
 *
 * `udea-physics2d` is that module: `box2d-jni` publishes desktop natives for the JVM and an AAR for
 * Android, and no iOS or Wasm build of the bindings exists. Declaring those targets anyway would
 * need an `actual` for each with nothing real behind it, which the charter forbids, so they are
 * simply absent. When `box2d-jni` publishes another platform, the switch is here.
 *
 * Not `dev.wildware.udea.kotlin-multiplatform-render`, which configures the same two targets: that one is
 * `udea-render`'s and says so, and `RenderModuleGraphTest` asserts `udea-render` is the only engine
 * module on it. The target set is the same by coincidence of two upstream libraries, not by design,
 * so the two conventions move independently.
 */

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("dev.wildware.udea.kotlin-base")
}

repositories {
    // AGP resolves Android's own tooling artefacts from Google's repository.
    google()
}

UdeaMultiplatform.configure(project, ios = false, wasm = false)
