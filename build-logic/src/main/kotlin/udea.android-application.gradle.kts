import com.android.build.api.dsl.ApplicationExtension
import dev.wildware.udea.build.ModuleGraphRules
import dev.wildware.udea.build.UdeaMultiplatform
import dev.wildware.udea.build.udeaCatalog

/**
 * The convention for an Android **application**: `:moba:android` (spec D12, issue #212).
 *
 * The three library conventions beside this one all produce something another module depends on.
 * This one produces an APK, which is the end of a graph rather than a node in it, and that is the
 * whole difference: it applies AGP's application plugin rather than its multiplatform library
 * plugin, and it has one target by construction.
 *
 * It still goes through `udea.kotlin-base`, so the explicit-API rule, the JDK toolchain, the
 * `kotlin-stdlib` pin and the K2 compiler plugin reach an app exactly as they reach a library. A
 * launcher that quietly compiled without the FIR checkers would be the one module in the tree the
 * checkers could not see.
 *
 * `compileSdk` and `minSdk` are read from the same two catalog entries `UdeaMultiplatform` reads,
 * so an app and the libraries it packages cannot disagree about the platform they were built for.
 */

plugins {
    id("com.android.application")
    kotlin("android")
    id("udea.kotlin-base")
}

repositories {
    // AGP resolves Android's own tooling artefacts from Google's repository.
    google()
}

extensions.configure<ApplicationExtension> {
    namespace = UdeaMultiplatform.androidNamespace(project.path)
    compileSdk = project.udeaAndroidSdk("androidCompileSdk")

    defaultConfig {
        applicationId = UdeaMultiplatform.androidNamespace(project.path)
        minSdk = project.udeaAndroidSdk("androidMinSdk")
        targetSdk = project.udeaAndroidSdk("androidCompileSdk")
        versionCode = 1
        versionName = "1.0"
    }

    // `assembleDebug` is the acceptance criterion and `assemble` is what `udeaAssemble` runs, so
    // the release variant would have to be signed for a plain `build` to pass. It is not built
    // rather than signed with a debug key: a release APK nobody can install is not evidence of
    // anything, and a checked-in signing key is worse.
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

// The bytecode an app ships, for the gates that read every module's (`udeaVerifyNoLibGdx`, issue
// #189): the release variant's, since that is what the APK carries. AGP creates the task after
// this script runs, so it is named rather than looked up.
tasks.register(ModuleGraphRules.MAIN_BYTECODE_TASK) {
    description = "Compiles the bytecode this app's release variant ships."
    dependsOn("compileReleaseKotlin")
}

/** A `[versions]` entry of the catalog as an `Int`, failing loudly when it is absent. */
fun Project.udeaAndroidSdk(alias: String): Int =
    udeaCatalog.findVersion(alias).orElseThrow {
        IllegalStateException("No version '$alias' in gradle/libs.versions.toml.")
    }.requiredVersion.toInt()
