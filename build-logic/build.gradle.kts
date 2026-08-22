plugins {
    `kotlin-dsl`
}

group = "dev.wildware.udea.build"

dependencies {
    implementation(libs.kotlin.gradle.plugin)

    // A third Kotlin version in this build, and a deliberate one.
    //
    // `kotlin-dsl` compiles build logic with the Kotlin the *Gradle distribution* embeds -
    // 2.0.21 for Gradle 8.13 - not with the catalog's 2.2.10, which is why every build prints
    // "Unsupported Kotlin plugin version". A 2.0.21 compiler cannot read kotlin-test 2.2.10's
    // metadata, so `libs.kotlin.test` here fails at compile time with a metadata-version
    // error. `embeddedKotlin("test")` resolves the kotlin-test that matches the compiler
    // actually running, which is the only version that can work.
    //
    // The catalog pin (UdeaVersions.KOTLIN, and UdeaStdlibPin for the resolved stdlib) governs
    // the udea-* tree. It cannot govern this module, because Gradle chooses this compiler. The
    // day build-logic needs the catalog's Kotlin, the fix is a Gradle upgrade, not a version
    // override here.
    testImplementation(embeddedKotlin("test"))
    testImplementation(gradleTestKit())
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
