import dev.wildware.udea.build.UdeaVersions
import dev.wildware.udea.build.udeaLibrary

/**
 * The base convention for every new `udea-*` module and for `moba`.
 *
 * Deliberately contains NO graphics dependency: a module on this convention cannot see
 * GL. Modules that legitimately touch GL apply `udea.kotlin-library-gl` instead, which
 * is the only place LWJGL3/GL enters the build (spec 4, spec 3.5).
 */

plugins {
    kotlin("jvm")
}

group = "dev.wildware.udea"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(UdeaVersions.JVM_TOOLCHAIN)
    // Cheap now, painful to retrofit: Replicator<T> and friends are cross-module contracts.
    explicitApi()
}

dependencies {
    testImplementation(udeaLibrary("kotlin-test"))
    testImplementation(udeaLibrary("junit5-jupiter"))
    testRuntimeOnly(udeaLibrary("junit5-platform-launcher"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
