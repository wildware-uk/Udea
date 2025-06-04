plugins {
    kotlin("jvm") version "2.1.20"
    id("java-library")
    id("maven-publish")
    kotlin("plugin.serialization") version "2.1.20"
}

group = "dev.wildware.udea"
version = "1.0-SNAPSHOT"

val gdxVersion = "1.13.5"
val ktxVersion = "1.13.1-rc1"

dependencies {
    api("io.github.quillraven.fleks:Fleks:2.11")
    implementation(kotlin("stdlib"))
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

    implementation("com.badlogicgames.gdx:gdx-box2d-platform:$gdxVersion:natives-desktop")
    implementation("com.badlogicgames.gdx:gdx-freetype-platform:$gdxVersion:natives-desktop")
    implementation("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
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