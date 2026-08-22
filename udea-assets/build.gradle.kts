plugins {
    id("udea.kotlin-library")
}

dependencies {
    api(project(":udea-annotations"))
    implementation(project(":udea-diagnostics"))
}
