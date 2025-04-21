import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.20"
    id("java")
    id("org.jetbrains.intellij.platform") version "2.5.0"
    id("org.jetbrains.compose") version "1.7.0-alpha03"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

group = "dev.wildware.udea"
version = "1.0-SNAPSHOT"

/**
 * Exclude all kotlinx coroutine modules from the runtime classpath to avoid conflicts with the IDE.
 * */
configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
}

repositories {
    intellijPlatform {
        defaultRepositories()
    }
}

allprojects {
    repositories {
        mavenCentral()
        mavenLocal()
        gradlePluginPortal()
        google()
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
        maven("https://s01.oss.sonatype.org")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaUltimate("2025.1")
        bundledPlugins(
            "org.jetbrains.kotlin",
            "com.intellij.java",
            "com.intellij.modules.json"
        )
    }

    implementation(compose.desktop.currentOs) {
        exclude("org.jetbrains.compose.material")
    }
    implementation("com.bybutter.compose:compose-jetbrains-expui-theme:2.0.0")
    implementation("org.jetbrains.skiko:skiko-awt:0.8.11")
    implementation(project(":level-editor"))
    implementation(project(":common"))
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }

    patchPluginXml {
        sinceBuild.set("232")
        untilBuild.set("251.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
