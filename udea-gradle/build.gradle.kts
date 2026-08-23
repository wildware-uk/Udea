plugins {
    id("udea.gradle-plugin")
}

dependencies {
    implementation(project(":udea-assets-compiler"))
    implementation(project(":udea-diagnostics"))
}

/**
 * How the TestKit tests get the plugin under test onto a generated build's classpath.
 *
 * `java-gradle-plugin` would supply `pluginUnderTestMetadata` and `withPluginClasspath()` for
 * free, and it is deliberately not applied: it adds `gradleApi()` to the `api` configuration, and
 * the `udea.gradle-plugin` convention keeps `gradleApi()` `compileOnly` precisely so the whole
 * Gradle API cannot reach a consumer's runtime classpath - the defect the old `gradle-plugin`
 * module shipped. So the classpath is handed over as a property and the tests build a
 * `buildscript { }` block from it.
 *
 * `runtimeClasspath` and not just the output directories: `LaunchDeclaration` and
 * `AgentBuildFlagsSource` are in the output, but the Kotlin stdlib the plugin is compiled against
 * is not, and a plugin loaded without it fails inside `apply` with a `NoClassDefFoundError` that
 * names `kotlin/jvm/internal/Intrinsics` rather than anything to do with this build.
 */
val pluginClasspath: FileCollection = files(
    sourceSets.main.get().output,
    configurations.runtimeClasspath,
)

/**
 * The classpath, written out at **execution** time.
 *
 * Not passed as a system property computed during configuration: joining the collection resolves
 * `runtimeClasspath` and queries `jar` outputs, which is a configuration-time query of a task
 * result, and a closure over the script-level `val` cannot be stored in the configuration cache
 * at all. A file written by a task with declared inputs and outputs has neither problem and is
 * up-to-date-checked into the bargain.
 */
val pluginClasspathFile = layout.buildDirectory.file("udea/plugin-under-test-classpath.txt")

val udeaWritePluginClasspath by tasks.registering {
    description = "Writes the plugin-under-test classpath the TestKit cases load the plugin from."
    val entries = pluginClasspath
    val output = pluginClasspathFile
    inputs.files(entries)
        .withPropertyName("pluginUnderTestClasspath")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(output)
    doLast {
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(entries.joinToString(separator = System.lineSeparator()) { it.absolutePath })
        }
    }
}

tasks.test {
    val handOff = pluginClasspathFile
    dependsOn(udeaWritePluginClasspath)
    inputs.file(handOff).withPropertyName("pluginUnderTestClasspathFile")
    systemProperty("udea.gradle.pluginClasspathFile", handOff.get().asFile.absolutePath)
}
