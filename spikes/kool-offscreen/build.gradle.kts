plugins {
    kotlin("jvm") version "2.4.20"
    application
}

// The latest Kool on Maven Central when #200 ran (maven-metadata.xml: latest/release 0.19.0).
val koolVersion = "0.19.0"

dependencies {
    implementation("de.fabmax.kool:kool-core:$koolVersion")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.wildware.udea.spike.kooloffscreen.KoolOffscreenSpikeKt")
}

// `-Pspike.backend=vk` selects Kool's Vulkan backend instead of OpenGL; `-Pspike.out=<path>` moves
// the PNG. Both are forwarded as system properties so the one `run` task serves every attempt.
tasks.named<JavaExec>("run") {
    workingDir = layout.buildDirectory.dir("run").get().asFile.apply { mkdirs() }
    systemProperty("spike.backend", providers.gradleProperty("spike.backend").getOrElse("gl"))
    systemProperty(
        "spike.out",
        providers.gradleProperty("spike.out")
            .getOrElse(layout.buildDirectory.file("spike/kool-offscreen-quad.png").get().asFile.absolutePath),
    )
}

tasks.test {
    useJUnitPlatform()
}
