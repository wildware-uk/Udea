package dev.wildware.udea.build

import dev.wildware.udea.build.determinism.ClassScanner
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * `udeaVerifyEditorAbsent`: fails when a `udea-editor` class or a `Gizmo` is on this project's
 * release runtime classpath or in its jar (`UDEA-MG-012`, issue #233).
 *
 * The rule is [EditorReleaseRules]; this reads the classes. It reads the **files** the classpath
 * resolves to, not the dependency graph `UDEA-MG-010` reads, because the case it exists for has no
 * dependency edge: a directory of editor classes added with `files(...)`, or a gizmo packed into the
 * jar. Every jar and directory is read, external ones included, and only each class's header.
 */
public abstract class UdeaVerifyEditorAbsentTask : DefaultTask() {

    /** The release runtime classpath and the project's own jar. */
    @get:Classpath
    public abstract val classpath: ConfigurableFileCollection

    /** Gradle path of the project being verified, for the failure message. */
    @get:Input
    public abstract val projectPath: Property<String>

    /** What was scanned, written on success too so a green run still shows its working. */
    @get:OutputFile
    public abstract val report: RegularFileProperty

    /** Reads every class header on [classpath] and fails on the first rule broken. */
    @TaskAction
    public fun verify() {
        val path = projectPath.get()
        val roots = classpath.files.filter(File::exists).sortedBy { it.absolutePath }
        val scanned = roots.flatMap(::classesIn)

        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("${EditorReleaseRules.RULE_ID.value} $path: ${scanned.size} classes read")
                    for (root in roots) appendLine("scanned: ${root.absolutePath}")
                },
            )
        }

        EditorReleaseRules.brokenCheck(path, scanned.size)?.let { throw GradleException(it) }
        EditorReleaseRules.report(path, EditorReleaseRules.violations(scanned))?.let { throw GradleException(it) }
    }

    /** Every class under a directory, or in a jar; anything else on a classpath holds none. */
    private fun classesIn(root: File): List<EditorReleaseRules.ScannedClass> = when {
        root.isDirectory -> root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(CLASS) }
            .map { EditorReleaseRules.ScannedClass(root.absolutePath, header(it.readBytes(), it.path)) }
            .toList()
        root.name.endsWith(".jar", ignoreCase = true) -> jarClasses(root)
        else -> emptyList()
    }

    private fun jarClasses(jar: File): List<EditorReleaseRules.ScannedClass> =
        try {
            ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(CLASS) }
                    .map { entry ->
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        EditorReleaseRules.ScannedClass(jar.absolutePath, header(bytes, "${jar.absolutePath}!/${entry.name}"))
                    }
                    .toList()
            }
        } catch (e: ZipException) {
            throw GradleException("udeaVerifyEditorAbsent could not read ${jar.absolutePath} as a jar", e)
        }

    /** A class header, or a failure naming the class: a class this gate cannot read is unverified. */
    private fun header(bytes: ByteArray, where: String) =
        try {
            ClassScanner.header(bytes)
        } catch (e: IllegalArgumentException) {
            throw GradleException("udeaVerifyEditorAbsent could not read the class header of $where", e)
        }

    private companion object {
        const val CLASS = ".class"
    }
}

/**
 * Registers `udeaVerifyEditorAbsent` on this project and puts it on `check`.
 *
 * Only where there is a JVM release classpath to read, and never on `:udea-editor`, whose own
 * classes are the editor. The classpath and the jar are attached as they appear, because a
 * multiplatform project's JVM target is created by its convention plugin, after this runs.
 */
public fun Project.registerEditorReleaseCheck() {
    if (path == EditorReleaseRules.EDITOR_PROJECT) return
    val modulePath = path
    configurations.matching { it.name in EditorReleaseRules.RELEASE_CLASSPATHS }.all {
        val configuration = this
        val task = if (TASK_NAME in tasks.names) {
            tasks.named(TASK_NAME, UdeaVerifyEditorAbsentTask::class.java)
        } else {
            tasks.register(TASK_NAME, UdeaVerifyEditorAbsentTask::class.java) {
                group = "verification"
                description = "Fails if a udea-editor class or a Gizmo is on this project's release classpath (UDEA-MG-012)."
                projectPath.set(modulePath)
                report.set(layout.buildDirectory.file("reports/udea/editor-absent.txt"))
                classpath.from(project.tasks.matching { it.name in EditorReleaseRules.RELEASE_JARS })
            }.also { registered -> tasks.named("check") { dependsOn(registered) } }
        }
        task.configure { classpath.from(configuration) }
    }
}

/** The task's name, one per project. */
private const val TASK_NAME: String = "udeaVerifyEditorAbsent"
