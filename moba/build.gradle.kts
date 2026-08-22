plugins {
    id("udea.kotlin-library")
}

dependencies {
    implementation(project(":udea-core"))
    implementation(project(":udea-gas"))
    implementation(project(":udea-net"))
    implementation(project(":udea-assets"))
    implementation(project(":udea-render"))
}
