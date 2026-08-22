package dev.wildware.udea.codegen

import java.io.File

/**
 * Where `udea-codegen` is on disk, found from whatever directory the test JVM started in.
 *
 * Tests read real files — the sources `kspTest` produced, the checked-in hash list, this
 * module's own `src/main` — because that is the point of choosing KSP over an IR plugin
 * (spec 3.2): the output is a file, so it can be diffed, stepped and scanned. Gradle and an
 * IDE disagree about the working directory, so the location is searched for rather than
 * assumed.
 */
internal object ModuleRoot {

    val directory: File by lazy {
        var candidate: File? = File("").absoluteFile
        while (candidate != null) {
            if (candidate.name == NAME && File(candidate, MARKER).isFile) return@lazy candidate
            val child = File(candidate, NAME)
            if (File(child, MARKER).isFile) return@lazy child
            candidate = candidate.parentFile
        }
        error("could not locate the $NAME module from ${File("").absolutePath}")
    }

    fun file(relativePath: String): File = File(directory, relativePath)

    private const val NAME = "udea-codegen"
    private const val MARKER = "build.gradle.kts"
}
