plugins {
    kotlin("jvm")
    id("maven-publish")

    // `java-gradle-plugin`, not `kotlin-dsl`, since the Kotlin 2.4.20 move (issue #186).
    //
    // `kotlin-dsl` pins `languageVersion`/`apiVersion` to the Gradle distribution's embedded
    // Kotlin - 1.8 for Gradle 8.13 - and Kotlin 2.4 refuses to compile below 2.0 outright:
    // "Language version 1.8 is no longer supported; use version 2.0 or greater instead." The
    // "Unsupported Kotlin plugin version" warning this module has printed for several releases
    // was that same clash while it was still survivable.
    //
    // Nothing is lost. `kotlin-dsl` was here for `gradlePlugin { }`, which belongs to
    // `java-gradle-plugin`; this module's four source files import only `org.gradle.api.Plugin`,
    // `org.gradle.api.Project` and the KSP API, and name no Gradle Kotlin DSL type at all. The
    // alternative - forcing `languageVersion = 2.0` back over what `kotlin-dsl` set - would
    // leave the two plugins disagreeing and only stop them saying so.
    `java-gradle-plugin`
}

group = "dev.wildware.udea"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.12")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

buildscript {
    dependencies {
        classpath(kotlin("gradle-plugin", version = "2.4.20"))
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
