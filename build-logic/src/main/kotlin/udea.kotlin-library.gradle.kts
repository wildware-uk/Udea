import dev.wildware.udea.build.ModuleGraphRules
import dev.wildware.udea.build.udeaLibrary

/**
 * The base convention for every JVM `udea-*` module and for `moba`.
 *
 * Deliberately contains NO graphics dependency: a module on this convention cannot see
 * GL. Modules that legitimately touch GL apply `udea.kotlin-library-gl` instead, which
 * is the only place LWJGL3/GL enters the build (spec 4, spec 3.5).
 *
 * The policy every Kotlin module shares - the explicit-API rule, the resolved `kotlin-stdlib`
 * pinned to the catalog's Kotlin version (see `UdeaStdlibPin`), and the K2 compiler plugin with
 * its gates - is `udea.kotlin-base`, which the multiplatform conventions apply too.
 */

plugins {
    kotlin("jvm")
    id("udea.kotlin-base")
}

dependencies {
    testImplementation(udeaLibrary("kotlin-test"))
    testImplementation(udeaLibrary("junit5-jupiter"))
    testRuntimeOnly(udeaLibrary("junit5-platform-launcher"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.register(ModuleGraphRules.MAIN_BYTECODE_TASK) {
    description = "Compiles the bytecode this module's main code ships as."
    dependsOn("classes")
}
