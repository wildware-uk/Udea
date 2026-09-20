/*
 * A Udea game that lives in its own repository (issue #265).
 *
 * Nothing here names a path to a Udea checkout. The engine is a set of published artifacts -
 * `dev.wildware.udea:udea-core`, the `udea.*` convention plugins, and a version catalog - and
 * this build resolves them the way it resolves any other library. That is what makes a game a
 * repository of its own rather than a folder that has to sit next to the engine's.
 *
 * `udeaVersion` in `gradle.properties` says which engine to build against, and it is the only
 * place any version of it appears. Until Udea's first release, that is a snapshot, and the
 * repository lists below are ordered so it can come from either place: `mavenLocal()` first, so
 * an engine you published yourself wins, then Central's snapshot repository, which is where the
 * engine's own Release workflow puts one.
 *
 * **A game does not follow the engine's tip.** The snapshot version is pinned in
 * `gradle.properties` and changes when the owner says so, not when the engine moves. See
 * `docs/new-game.md` in the Udea repository, under "Getting a newer engine".
 */

pluginManagement {
    val udeaVersion: String = providers.gradleProperty("udeaVersion").get()

    repositories {
        // First, so an engine published with `publishToMavenLocal` wins over whatever snapshot
        // is on the network. That is how you try an engine change without publishing it.
        mavenLocal()
        mavenCentral()
        // Udea's snapshots. Sonatype moved its snapshot hosting to the first of these; the two
        // `oss.sonatype.org` hosts below are the fallbacks it has not retired. The engine's own
        // build declares the same list for the same reason, for ComposeGL.
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
        gradlePluginPortal()
        // The Android Gradle Plugin, which the multiplatform conventions put on the build
        // classpath, is published only here. A JVM-only game never resolves it, and the
        // repository costs nothing until it does.
        google()
    }

    // The version is stated once, here, so no build script in this repository repeats it. A
    // script applies `id("dev.wildware.udea.game-gates")` with no version and gets this one.
    plugins {
        id("dev.wildware.udea.kotlin-library") version udeaVersion
        id("dev.wildware.udea.kotlin-multiplatform") version udeaVersion
        id("dev.wildware.udea.kotlin-multiplatform-render") version udeaVersion
        id("dev.wildware.udea.game-gates") version udeaVersion
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
        // Same order and the same list as `pluginManagement` above: a locally published engine
        // first, then the release repository, then the snapshot hosts.
        mavenLocal()
        mavenCentral()
        google()

        // Udea's own snapshots, and ComposeGL's.
        //
        // Udea publishes snapshots until its first release, so this is where `udeaVersion`
        // resolves from on a machine that has not published the engine itself. ComposeGL, which
        // `udea-render` draws its interface with, publishes only snapshots too - and it is needed
        // by any game that reaches `udea-render`, which includes a game that only uses the agent
        // surface, since `udea-agent-host` depends on it.
        //
        // Sonatype moved its snapshot hosting to the first of these; the two `oss.sonatype.org`
        // hosts are the fallbacks it has not retired.
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
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
