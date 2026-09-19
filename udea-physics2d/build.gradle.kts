plugins {
    // `jvm` and `android` only: `box2d-jni` publishes natives for the desktop JVM and an AAR for
    // Android, and nothing for iOS or Wasm. See the convention's KDoc.
    id("udea.kotlin-multiplatform-jvm-android")
}

/**
 * The desktop platforms `box2d-jni` ships a native library for, each as a classified jar beside
 * the bindings. All of them, so a game that depends on this module runs on any desktop without
 * naming its own natives; each is well under a megabyte.
 */
val desktopNatives: List<String> = listOf(
    "natives-linux",
    "natives-linux-arm64",
    "natives-macos",
    "natives-macos-arm64",
    "natives-windows",
    "natives-windows-arm64",
)

kotlin {
    sourceSets {
        commonMain {
            dependencies {
                api(project(":udea-core"))
            }
        }
        // `box2dMain` is not a Kotlin source set. It is one directory compiled into both `jvmMain`
        // and `androidMain`, because the two bindings are the same API under two Java packages -
        // `box2d.*` on the desktop and `box2dandroid.*` in the AAR - so no shared source set can
        // import either. Each target's `Box2DBindings.kt` maps one set of names onto its package,
        // and the solver code written against those names exists once.
        jvmMain {
            kotlin.srcDir("src/box2dMain/kotlin")
            dependencies {
                implementation(libs.box2d.jni)
            }
        }
        androidMain {
            kotlin.srcDir("src/box2dMain/kotlin")
            dependencies {
                implementation(libs.box2d.jni.android)
            }
        }
    }
}

dependencies {
    for (natives in desktopNatives) {
        "jvmMainRuntimeOnly"(variantOf(libs.box2d.jni) { classifier(natives) })
    }

    // A real snapshot spine and real service doubles for the rewind tests.
    "jvmTestImplementation"(testFixtures(project(":udea-core")))
    // The delta-packet writer and reader, for the test that physics never reaches the wire.
    "jvmTestImplementation"(project(":udea-net"))
    // Kotlin's own visibility, for `NoBox2DInPublicApiTest`: an `internal` class is public bytecode.
    "jvmTestImplementation"(kotlin("reflect"))
}

tasks.withType<Test>().configureEach {
    // Where the source-reading tests find this module's `src`. A relative path would resolve
    // against the daemon's working directory under an IDE, and a scan of nothing passes.
    systemProperty("udea.physics2d.projectDir", layout.projectDirectory.asFile.absolutePath)
}

// The one-line description Maven Central requires of a published artifact (issue #265).
description =
    "Udea's 2D physics: Box2D 3 behind the kernel's physics interface, headless, with no " +
    "Box2D type in any public signature."
