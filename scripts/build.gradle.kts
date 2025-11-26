plugins {
    kotlin("jvm")
    id("java-library")
    id("maven-publish")
}

group = "dev.wildware.udea"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("org.jetbrains.kotlin:kotlin-scripting-common")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm")
    implementation("org.jetbrains.kotlin:kotlin-scripting-dependencies")
    implementation("org.jetbrains.kotlin:kotlin-scripting-dependencies-maven")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
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
                name.set("UDEA Script Definitions")
                description.set("Script Definitions for .udea.kts files")
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