import org.gradle.internal.execution.caching.CachingState.enabled
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.2.10"
    id("java-library")
    id("maven-publish")
    kotlin("plugin.serialization") version "2.2.10"
    id("com.google.devtools.ksp") version "2.2.10-2.0.2"
    id("kotlin-kapt")
}

group = "dev.wildware.udea"
version = "1.0-SNAPSHOT"

val gdxVersion = "1.13.5"
val ktxVersion = "1.13.1-rc1"

dependencies {
    api("com.kotcrab.vis:vis-ui:1.5.7")
    api("io.github.quillraven.fleks:Fleks:2.13-SNAPSHOT")
    implementation("org.reflections:reflections:0.10.2")
    testImplementation(kotlin("test"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.8.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    api("com.badlogicgames.gdx:gdx:1.13.5")
    api("com.badlogicgames.gdx:gdx-box2d:1.13.5")
    api("io.github.libktx:ktx-app:$ktxVersion")
    api("io.github.libktx:ktx-assets:$ktxVersion")
    api("io.github.libktx:ktx-async:$ktxVersion")
    api("io.github.libktx:ktx-box2d:$ktxVersion")
    api("io.github.libktx:ktx-freetype-async:$ktxVersion")
    api("io.github.libktx:ktx-freetype:$ktxVersion")
    api("io.github.libktx:ktx-graphics:$ktxVersion")
    api("io.github.libktx:ktx-scene2d:$ktxVersion")
    api("com.github.crykn:kryonet:2.22.9")
    api("com.badlogicgames.box2dlights:box2dlights:1.5")
    api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.0")

    implementation("com.badlogicgames.gdx:gdx-box2d-platform:$gdxVersion:natives-desktop")
    implementation("com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-desktop")
    implementation("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")

    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm")
    implementation("org.jetbrains.kotlin:kotlin-scripting-dependencies")
    implementation("org.jetbrains.kotlin:kotlin-scripting-dependencies-maven")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host")
    implementation("org.jetbrains.kotlin:kotlin-scripting-common")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    ksp(project(":gradle-plugin"))
    implementation(project(":gradle-plugin"))
}


tasks.test {
    useJUnitPlatform()
}

tasks.register<Jar>("sourcesJar") {
    archiveClassifier.set("sources")
    from(sourceSets.main.get().allSource)
}


publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"]) // Main JAR from the Java/Kotlin component
            artifact(tasks.named("sourcesJar")) // Add the sources JAR
            
            pom {
                name.set("UDEA Common")
                description.set("Common utilities for UDEA")
                url.set("https://example.com/udea-common") // Optional: adjust your project's URL
                
                developers {
                    developer {
                        id.set("shaunwild")
                        name.set("Shaun Wild")
                        email.set("shaunwild97@gmail.com")
                    }
                }
                
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://opensource.org/licenses/Apache-2.0")
                    }
                }
            }
        }
    }
    repositories {
        mavenLocal() // This ensures publishing to Maven Local.
    }
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.set(listOf("-Xcontext-parameters", "-Xcontext-sensitive-resolution"))
}