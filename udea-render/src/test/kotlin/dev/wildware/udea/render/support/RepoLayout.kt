package dev.wildware.udea.render.support

import java.io.File

/**
 * Finds the repository, its modules and their compiled classes.
 *
 * Two rules in this module are about *compiled output* rather than about types a test can
 * name: "no headless module references a GL type" and "no `RenderSystem` resolves a `Family`
 * inside `render`". Both are checked by reading `.class` files, and this is how those files
 * are found -- from the working directory up, so the same test passes under Gradle, under
 * an IDE runner and from the repository root.
 */
internal object RepoLayout {

    /** The `udea-render` project directory. */
    val moduleDir: File = locateModuleDir()

    /** The repository root. */
    val repoRoot: File = moduleDir.parentFile

    /** The project directory of [module], e.g. `udea-core`. */
    fun moduleDir(module: String): File {
        val dir = repoRoot.resolve(module)
        check(dir.resolve("build.gradle.kts").isFile) {
            "$module is not a module of this repository (no build.gradle.kts in $dir)"
        }
        return dir
    }

    /**
     * Every `.class` file [module] compiled for [sourceSet], across every language directory
     * (`build/classes/kotlin/main`, `build/classes/java/main`, ...).
     *
     * Deliberately not filtered to Kotlin: a `.java` file added to a headless module would be
     * exactly as able to name a GL type, and a gate that only looked at Kotlin output would
     * pass while it did.
     */
    fun classFiles(module: String, sourceSet: String = "main"): List<File> {
        val classesRoot = moduleDir(module).resolve("build/classes")
        val languageDirs = classesRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        return languageDirs
            .map { it.resolve(sourceSet) }
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "class" } }
            .sortedBy { it.invariantSeparatorsPath }
    }

    /** Path relative to the repository root, `/`-separated, for readable failures and spans. */
    fun relativePath(file: File): String = file.relativeTo(repoRoot).invariantSeparatorsPath

    /**
     * The source file a class was compiled from, if it can be found on disk.
     *
     * The class file records only the simple file name (`Transform.kt`); the package supplies
     * the directory. Returns `null` rather than guessing when the file is not there -- a
     * generated class has no source to point at, and a diagnostic with a made-up location is
     * worse than one with none.
     */
    fun sourceFileOf(module: String, className: String, sourceFileName: String?): File? {
        if (sourceFileName == null) return null
        val packagePath = className.substringBeforeLast('.', "").replace('.', '/')
        // Test and fixture roots are searched too: the gate's own fixtures are compiled from
        // `src/test/kotlin`, and a diagnostic about a fixture should point at the fixture.
        val roots = listOf(
            "src/main/kotlin",
            "src/main/java",
            "src/test/kotlin",
            "src/testFixtures/kotlin",
        )
        return roots.asSequence()
            .map { root -> moduleDir(module).resolve("$root/$packagePath/$sourceFileName") }
            .firstOrNull { it.isFile }
    }

    private fun locateModuleDir(): File {
        var candidate: File? = File(System.getProperty("user.dir")).absoluteFile
        while (candidate != null) {
            if (candidate.name == MODULE && candidate.resolve("build.gradle.kts").isFile) {
                return candidate
            }
            val nested = candidate.resolve(MODULE)
            if (nested.resolve("build.gradle.kts").isFile) return nested
            candidate = candidate.parentFile
        }
        error(
            "Could not locate the $MODULE module directory from working dir " +
                System.getProperty("user.dir"),
        )
    }

    private const val MODULE = "udea-render"
}
