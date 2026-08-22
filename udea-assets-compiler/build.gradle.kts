plugins {
    id("udea.kotlin-build-tool")
}

dependencies {
    api(project(":udea-assets"))
    implementation(project(":udea-diagnostics"))
}
