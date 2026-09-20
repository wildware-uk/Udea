import dev.wildware.udea.build.ModuleGraphRules
import dev.wildware.udea.build.udeaLibrary

/**
 * The base convention for every JVM `udea-*` module and for `moba`.
 *
 * Deliberately contains NO graphics dependency: a module on this convention cannot see
 * GL. The module that legitimately touches GL, `udea-render`, is on
 * `dev.wildware.udea.kotlin-multiplatform-render` and takes Kool itself (spec 4, spec 3.5).
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
