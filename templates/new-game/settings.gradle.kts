/*
 * A Udea game that lives in its own repository (issue #265).
 *
 * Nothing here names a path to a Udea checkout. The engine is a set of published artifacts -
 * `dev.wildware.udea:udea-core`, the `udea.*` convention plugins, and a version catalog - and
 * this build resolves them the way it resolves any other library. That is what makes a game a
 * repository of its own rather than a folder that has to sit next to the engine's.
 *
 * `udeaVersion` in `gradle.properties` says which release to build against. Override it per
 * machine in `~/.gradle/gradle.properties`, or for one invocation with `-PudeaVersion=...`.
 *
 * Until Udea's first release is on Maven Central, the version is a snapshot and `mavenLocal()` is
 * where it comes from: run `./gradlew publishToMavenLocal` and `./gradlew -p build-logic
 * publishToMavenLocal` in the engine's checkout, and this build finds it. `mavenLocal()` is first
 * in every list below for exactly that reason, and can be deleted the day a release exists.
 */

pluginManagement {
    val udeaVersion: String = providers.gradleProperty("udeaVersion").get()

    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
        // The Android Gradle Plugin, which the multiplatform conventions put on the build
        // classpath, is published only here. A JVM-only game never resolves it, and the
        // repository costs nothing until it does.
        google()
    }

    // The version is stated once, here, so no build script in this repository repeats it. A
    // script applies `id("udea.game-gates")` with no version and gets this one.
    plugins {
        id("udea.kotlin-library") version udeaVersion
        id("udea.kotlin-multiplatform") version udeaVersion
        id("udea.kotlin-multiplatform-render") version udeaVersion
        id("udea.game-gates") version udeaVersion
        id("dev.wildware.udea.agent") version udeaVersion
        id("dev.wildware.udea.assets") version udeaVersion
    }
}

plugins {
    // Provisions the JDK the conventions ask for, rather than requiring it to be installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

dependencyResolutionManagement {
    // The conventions declare `mavenCentral()` on each project they are applied to, and the
    // default `PREFER_PROJECT` would then use *those* and ignore everything below - which is how
    // `mavenLocal()` and the snapshot repository can be declared here and never be searched. The
    // list below is a superset of what the conventions declare, so nothing is lost by winning.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)

    repositories {
        mavenLocal()
        mavenCentral()
        google()

        // ComposeGL, which `udea-render` draws its interface with, publishes snapshots and no
        // release yet, and Sonatype moved snapshot hosting here.
        //
        // It is needed by any game that reaches `udea-render` - which includes a game that only
        // uses the agent surface, since `udea-agent-host` depends on it. A game that drops both
        // can drop this line, and will find out by resolution failing rather than silently.
        maven("https://central.sonatype.com/repository/maven-snapshots/")
    }

    versionCatalogs {
        // The engine's catalog, as `libs`, published as an artifact of its own. The conventions
        // read Kotlin, kotlin-test, JUnit and the Android SDK levels out of it by alias, so a
        // game on those conventions needs the same catalog rather than a copy of its numbers - a
        // copy is a second version of Kotlin waiting to disagree with the compiler the engine was
        // built with.
        //
        // A game with dependencies of its own adds a second catalog here (`create("game") { ... }`)
        // rather than editing the engine's.
        create("libs") {
            val udeaVersion: String = providers.gradleProperty("udeaVersion").get()
            from("dev.wildware.udea:udea-version-catalog:$udeaVersion")
        }
    }
}

rootProject.name = "new-game"

include("game")
