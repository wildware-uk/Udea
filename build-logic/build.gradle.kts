plugins {
    `kotlin-dsl`
}

group = "dev.wildware.udea.build"

dependencies {
    implementation(libs.kotlin.gradle.plugin)

    // Gradle 8.13 compiles build logic with its own embedded Kotlin, which cannot read
    // kotlin-test 2.2.10 metadata. The embedded kotlin-test is the matching one.
    testImplementation(embeddedKotlin("test"))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
