plugins {
    kotlin("jvm")
    id("maven-publish")
    `kotlin-dsl`
}

group = "dev.wildware.udea"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    implementation("com.google.devtools.ksp:symbol-processing-api:2.2.10-2.0.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}

buildscript {
    dependencies {
        classpath(kotlin("gradle-plugin", version = "2.2.10"))
    }
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

            artifactId = "gradle-plugin"
            pom {
                name.set("UDEA Gradle Plugin")
                description.set("Gradle Plugin required for UDEA")
                url.set("https://example.com/udea-gradle-plugin") // Optional: adjust your project's URL

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

gradlePlugin {
    plugins {
        create("udeaPlugin") {
            id = "dev.wildware.udea-plugin"
            implementationClass = "dev.wildware.udea.UdeaPlugin"
        }
    }
}
