plugins {
    id("udea.kotlin-build-tool")
}

dependencies {
    api(project(":udea-annotations"))
    implementation(libs.ksp.api)
    implementation(libs.kotlinpoet)
}
