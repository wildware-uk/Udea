/**
 * Convention for `udea-gradle`, the Gradle plugin.
 *
 * `gradleApi()` is `compileOnly` on purpose. The old `gradle-plugin` module declared it as
 * `implementation` and games depended on that module, which put the whole Gradle API on the
 * game's runtime classpath (spec 4). `compileOnly` cannot reach any runtime classpath, so
 * the failure mode is impossible rather than merely discouraged.
 */

plugins {
    id("udea.kotlin-library")
}

dependencies {
    compileOnly(gradleApi())
    testImplementation(gradleTestKit())
}
