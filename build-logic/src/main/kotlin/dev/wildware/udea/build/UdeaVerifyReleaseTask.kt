package dev.wildware.udea.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Fails a release build if an agent class is inside the packaged artifact, or if the agent
 * modules are on the release runtime classpath.
 *
 * See [ReleaseRules] for why this reads the zip rather than the configuration model. The
 * decisions all live there, where unit tests execute them; this class is the I/O around
 * them — open the archives, list the entries, throw.
 */
public abstract class UdeaVerifyReleaseTask : DefaultTask() {

    /** The packaged artifacts to scan: every archive this project produces. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract val archives: ConfigurableFileCollection

    /** Entry prefixes that may not appear. Defaults to [ReleaseRules.DEFAULT_BANNED_PREFIXES]. */
    @get:Input
    public abstract val bannedPrefixes: ListProperty<String>

    /** Gradle path of the project being verified, for the failure message. */
    @get:Input
    public abstract val projectPath: Property<String>

    /** `configuration name -> resolved graph` for the release classpaths to model-check. */
    @get:Input
    public abstract val classpaths: MapProperty<String, ResolvedGraph>

    /** What was scanned, written on success so a green run still shows its working. */
    @get:OutputFile
    public abstract val report: RegularFileProperty

    /** Scans every archive and every release classpath, and fails on the first rule broken. */
    @TaskAction
    public fun verify() {
        val prefixes = bannedPrefixes.get()
        val path = projectPath.get()
        val scanned = archives.files.filter { it.isFile }.sortedBy { it.name }

        ReleaseRules.brokenCheck(path, scanned.map { it.absolutePath })?.let { throw GradleException(it) }

        val entries = scanned.flatMap { archive -> entriesOf(archive).map { ReleaseRules.ArchiveEntry(archive.absolutePath, it) } }
        val classpathViolations = classpaths.get().entries.sortedBy { it.key }
            .flatMap { (configuration, graph) ->
                DependencyRules.violations(path, configuration, graph, listOf(ReleaseRules.CLASSPATH_RULE))
            }

        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("banned prefixes: ${prefixes.joinToString()}")
                    scanned.forEach { appendLine("scanned archive: ${it.absolutePath} (${entriesOf(it).size} entries)") }
                    classpaths.get().keys.sorted().forEach { appendLine("scanned classpath: $it") }
                },
            )
        }

        DependencyRules.report("udeaVerifyRelease", classpathViolations)?.let { throw GradleException(it) }
        ReleaseRules.report(path, ReleaseRules.artifactViolations(entries, prefixes), prefixes)
            ?.let { throw GradleException(it) }
    }

    /**
     * Every entry name in [archive], **including the entries of jars nested inside it**.
     *
     * A file that is not a zip is a hard failure rather than a skip: silently ignoring an
     * artifact this task was pointed at is how a gate stops gating.
     *
     * ## Why it descends
     *
     * A plain jar is what `:moba` packages today, and for that the top level is the whole answer.
     * It will not stay that way: a distribution zip carries the runtime classpath as jars under `lib`,
     * and a fat or shaded jar carries dependencies whole. In both shapes every agent class sits
     * one level down, so a scanner that read only the top level would report a clean release for
     * an artifact that contains the entire remote-control surface - and would do it *silently*,
     * on the day the packaging changed, which is the worst possible moment for this gate to
     * quietly stop working. Nested names are reported as `outer.jar!/inner.jar!/entry` so the
     * failure message says which jar to look in.
     *
     * Depth is capped by [MAX_NESTING]: a self-referential archive is a real thing, and a release
     * gate that hangs is a release gate somebody switches off.
     */
    private fun entriesOf(archive: File): List<String> =
        try {
            ZipFile(archive).use { zip ->
                zip.entries().asSequence().flatMap { entry ->
                    if (isNestedArchive(entry.name)) {
                        val nested = zip.getInputStream(entry).use { nestedEntries(it, entry.name, 1) }
                        sequenceOf(entry.name) + nested
                    } else {
                        sequenceOf(entry.name)
                    }
                }.toList()
            }
        } catch (e: ZipException) {
            throw GradleException(
                "udeaVerifyRelease could not read ${archive.absolutePath} as an archive. A " +
                    "release artifact this gate cannot open is an unverified release artifact.",
                e,
            )
        }

    /** Entry names inside a nested archive, prefixed with the path that reached it. */
    private fun nestedEntries(stream: java.io.InputStream, prefix: String, depth: Int): Sequence<String> {
        if (depth > MAX_NESTING) return emptySequence()
        val collected = ArrayList<String>()
        java.util.zip.ZipInputStream(stream).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = "$prefix!/${entry.name}"
                collected.add(name)
                if (isNestedArchive(entry.name)) {
                    // The stream must not be closed by the recursive read - it is still positioned
                    // inside the outer archive - so the inner reader is handed a non-closing view.
                    collected.addAll(nestedEntries(NonClosing(zip), name, depth + 1))
                }
            }
        }
        return collected.asSequence()
    }

    private fun isNestedArchive(name: String): Boolean =
        name.endsWith(".jar", ignoreCase = true) || name.endsWith(".war", ignoreCase = true)

    /** A view that refuses `close`, so a nested reader cannot close the archive it is inside. */
    private class NonClosing(private val delegate: java.io.InputStream) : java.io.InputStream() {
        override fun read(): Int = delegate.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
        override fun available(): Int = delegate.available()
        override fun close() = Unit
    }

    private companion object {
        /** How far down the gate will follow nested archives before giving up. */
        const val MAX_NESTING = 4
    }
}
