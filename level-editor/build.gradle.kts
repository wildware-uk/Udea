plugins {
    id("java-library")
    kotlin("jvm") version "2.2.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20"
}

group = "dev.wildware.udea"
version = "1.0-SNAPSHOT"

val gdxVersion = "1.13.5"
val ktxVersion = "1.13.1-rc1"

dependencies {
    implementation(project(":common"))
    testImplementation(kotlin("test"))
    api("io.github.quillraven.fleks:Fleks:2.11")
    api("com.badlogicgames.gdx:gdx:$gdxVersion")
    api("io.github.libktx:ktx-app:$ktxVersion")
    implementation("com.badlogicgames.gdx:gdx-box2d-platform:$gdxVersion:natives-desktop")
    implementation("com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-desktop")
    implementation("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl:$gdxVersion")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.1.20")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.8.0")

    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

tasks.register("setupDependencies") {}
