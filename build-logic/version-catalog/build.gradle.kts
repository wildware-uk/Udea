plugins {
    `version-catalog`

    // The POM, the repositories and the signing are configured once for both projects of this
    // build, in `build-logic/build.gradle.kts`. Declared here as well so this script can create
    // the publication below with the generated accessor.
    `maven-publish`
    signing
}

description =
    "The version catalog Udea itself builds with: the Kotlin, KSP, Fleks, Ktor, Kool and JUnit " +
        "versions every engine module compiles against, so a game can compile against the same " +
        "ones without guessing."

/**
 * Published as a copy of `gradle/libs.versions.toml`, rather than as a hand-written subset.
 *
 * A game has to agree with the engine about two versions in particular or it does not build at
 * all: the Kotlin version (the engine's modules are compiled by it, and `dev.wildware.udea.kotlin-base` pins
 * `kotlin-stdlib` to it) and the KSP version (`udea-codegen` is a KSP2 processor, and KSP names
 * no compiler version of its own since 2.3.0, so there is nothing to derive it from). Publishing
 * the whole file means those two cannot be copied out of date, and the rest costs nothing.
 *
 * What it deliberately does *not* contain is an alias per `udea-*` module. A game names the
 * engine's coordinates with its own `udeaVersion` property, because the version of the engine a
 * game wants is a decision that game makes, and an alias inside the engine's own catalog would
 * pin it to whatever the catalog was published from. `docs/new-game.md` writes that out.
 */
catalog {
    versionCatalog {
        from(files(rootDir.resolve("../gradle/libs.versions.toml")))
    }
}

publishing {
    publications {
        // `version-catalog` builds the component; nothing publishes it unless something says so.
        create<MavenPublication>("versionCatalog") {
            from(components["versionCatalog"])
        }
    }
}
