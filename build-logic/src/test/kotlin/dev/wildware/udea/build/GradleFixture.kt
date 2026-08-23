package dev.wildware.udea.build

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A throwaway Gradle build for TestKit to run the Phase 0 gates against.
 *
 * These gates read a *resolved* dependency graph, which means the only honest way to test
 * them is to make Gradle resolve one. A hand-written [ResolvedGraph] proves the rule; this
 * proves the rule is actually attached to a build, on the classpaths it claims, and that its
 * message reaches the developer.
 *
 * @param root a per-test temporary directory.
 */
class GradleFixture(private val root: File) {

    private val repository = File(root, "test-repo").also { it.mkdirs() }
    private val projects = mutableListOf<String>()

    /**
     * Declares a subproject with the given build script.
     *
     * Subprojects rather than a single root project because most of these rules key on a
     * Gradle path — `:udea-core`, `:moba` — and a rule matched against `:` would be a rule
     * matched against nothing real.
     */
    fun project(name: String, buildScript: String): GradleFixture = apply {
        projects += name
        File(root, name).mkdirs()
        File(root, "$name/build.gradle.kts").writeText(buildScript.trimIndent() + "\n")
    }

    /**
     * Publishes a module into the fixture's local repository.
     *
     * A local repository rather than Maven Central: a build gate's test suite that needs the
     * network is a test suite that fails for reasons unrelated to the gate.
     *
     * @param coordinate `group:artifact:version`.
     * @param dependencies coordinates this module drags in, for transitive cases.
     */
    fun publish(coordinate: String, dependencies: List<String> = emptyList()): GradleFixture = apply {
        val (group, artifact, version) = coordinate.split(':')
        val dir = File(repository, "${group.replace('.', '/')}/$artifact/$version").also { it.mkdirs() }
        val dependencyBlock = dependencies.joinToString(separator = "\n") {
            val (g, a, v) = it.split(':')
            "    <dependency><groupId>$g</groupId><artifactId>$a</artifactId><version>$v</version></dependency>"
        }
        File(dir, "$artifact-$version.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$group</groupId>
              <artifactId>$artifact</artifactId>
              <version>$version</version>
              <packaging>jar</packaging>
              <dependencies>
            $dependencyBlock
              </dependencies>
            </project>
            """.trimIndent(),
        )
        ZipOutputStream(File(dir, "$artifact-$version.jar").outputStream()).use { }
    }

    /** A `repositories { }` block pointing at the fixture repository, for a build script. */
    fun repositoryBlock(): String =
        "repositories { maven { url = uri(\"${repository.toURI()}\") } }"

    /**
     * Writes a file into a subproject's `packaged` directory, which [jarFrom] folds into that
     * project's jar.
     */
    fun packagedFile(project: String, entryPath: String, content: String = "not really a class"): GradleFixture =
        apply {
            val file = File(root, "$project/packaged/$entryPath")
            file.parentFile.mkdirs()
            file.writeText(content)
        }

    /**
     * A `jar` configuration that packages the [packagedFile] tree.
     *
     * This is how a fixture gets an entry into an artifact **without** anything on a
     * classpath referencing it — the case that separates reading the packaged zip from
     * reading the configuration model.
     */
    fun jarFrom(): String = "tasks.named<Jar>(\"jar\") { from(layout.projectDirectory.dir(\"packaged\")) }"

    /**
     * Makes the project's real version catalog available to the fixture as `libs`.
     *
     * `udea.kotlin-library` reads the catalog through `udeaLibrary(...)`, so a fixture that
     * applies the convention has to have one. Pointing at the repository's own
     * `gradle/libs.versions.toml` rather than a hand-written stub is deliberate: the
     * convention is being tested as it will actually run, versions included.
     */
    fun withVersionCatalog(): GradleFixture = apply { versionCatalog = true }

    private var versionCatalog = false

    /** Materialises `settings.gradle.kts` and the root build script, then runs Gradle. */
    private fun write(rootBuildScript: String) {
        val catalog = File("../gradle/libs.versions.toml").canonicalFile
        File(root, "settings.gradle.kts").writeText(
            buildString {
                if (versionCatalog) {
                    check(catalog.isFile) { "version catalog not found at $catalog" }
                    appendLine("dependencyResolutionManagement {")
                    appendLine("    versionCatalogs {")
                    appendLine("        create(\"libs\") { from(files(\"${catalog.invariantSeparatorsPath}\")) }")
                    appendLine("    }")
                    appendLine("}")
                }
                appendLine("rootProject.name = \"fixture\"")
                projects.forEach { appendLine("include(\"$it\")") }
            },
        )
        File(root, "build.gradle.kts").writeText(rootBuildScript.trimIndent() + "\n")
        File(root, "gradle.properties").writeText("org.gradle.configuration-cache=true\n")
    }

    private fun runner(rootBuildScript: String, arguments: List<String>): GradleRunner {
        write(rootBuildScript)
        return GradleRunner.create()
            .withProjectDir(root)
            .withPluginClasspath()
            .withArguments(arguments + "--stacktrace")
            .forwardOutput()
    }

    /** Runs Gradle and requires the build to succeed. */
    fun build(vararg arguments: String, rootBuildScript: String = ""): BuildResult =
        runner(rootBuildScript, arguments.toList()).build()

    /** Runs Gradle and requires the build to fail, returning the result so it can be asserted on. */
    fun buildAndFail(vararg arguments: String, rootBuildScript: String = ""): BuildResult =
        runner(rootBuildScript, arguments.toList()).buildAndFail()
}

/** Convenience for a build script that applies `java-library` plus a Phase 0 gate. */
fun gatedProject(plugin: String, body: String): String =
    """
    plugins {
        `java-library`
        id("$plugin")
    }
    $body
    """.trimIndent()

/** Writes a zip with the given entry names, for tests that need an archive on disk. */
fun writeArchive(target: File, entries: List<String>) {
    target.parentFile.mkdirs()
    ZipOutputStream(target.outputStream()).use { zip ->
        entries.forEach {
            zip.putNextEntry(ZipEntry(it))
            zip.write(byteArrayOf(0))
            zip.closeEntry()
        }
    }
}
