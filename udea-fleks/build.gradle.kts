import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

/**
 * Fleks 2.14, vendored (issue #215). `NOTICE.md` beside this file says where the source came
 * from, under which licence, and what this module changes about how it is built.
 *
 * Vendored because Fleks publishes no iOS variant at any version, and `udea-core` - which exposes
 * Fleks through its `api` - could not have an iOS target while it resolved Fleks from Maven.
 * Fleks is pure common Kotlin, so building its own source here is what gives it one.
 *
 * The source is upstream's, byte for byte. Everything that differs from upstream's build is
 * configuration in this file, and is kept that way: a change to the vendored source moves the
 * `@version fleks` pin in `determinism-allowlist.txt` and fails `udeaVerifyDeterminism` until
 * `determinism-audit.md` is re-read against it.
 */
plugins {
    id("udea.kotlin-multiplatform")
    // Fleks' `Entity`, `Snapshot` and `ComponentType` are `@Serializable`: a level file is Fleks' own
    // `world.snapshot()` encoded as CBOR (issue #191).
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    // Udea's convention requires explicit visibility on every declaration. Fleks is third-party
    // source and relies on Kotlin's default `public`, so the rule is switched off for this module
    // rather than applied by editing upstream's files.
    explicitApi = ExplicitApiMode.Disabled

    sourceSets {
        all {
            // Upstream's own build sets this for every source set: `bitArray.kt` calls
            // `Long.countLeadingZeroBits`, which is experimental on some targets.
            languageSettings.optIn("kotlin.ExperimentalStdlibApi")
        }
        commonMain {
            dependencies {
                // `api`: `Entity` and `Snapshot` are `@Serializable`, so a consumer that encodes a
                // world compiles against their generated serializers.
                api(libs.kotlinx.serialization.core)
            }
        }
        commonTest {
            dependencies {
                // Upstream's tests encode a snapshot to JSON. Upstream declares the JSON library on
                // its main classpath; its main source does not use it, so here it is test-only.
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}

// The one-line description Maven Central requires of a published artifact (issue #265).
description =
    "Fleks 2.14, the Kotlin entity component system by Simon Klausner (MIT), vendored as " +
    "source so that Udea's kernel has iOS targets. Unmodified upstream source; see " +
    "udea-fleks/NOTICE.md."
