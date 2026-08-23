package dev.wildware.udea.assets.compiler

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * The three absolute paths tests here need, handed in by the build script.
 *
 * None of them is discoverable from inside a test JVM. `user.dir` is the module directory
 * under Gradle and the daemon's directory under an IDE, and this module's whole point is that
 * it takes a repo root, an asset root and a classpath as *arguments* rather than reading its
 * own environment — the same property that lets one implementation serve both a Gradle task
 * and the dev daemon. So the test harness has to be told too.
 */
internal object TestPaths {

    /** Absolute path of the repository root; every span in a test golden is relative to it. */
    val repoRoot: Path = required("udea.repoRoot")

    /** The real 19-file example asset tree, used as the pass-1 corpus (issue #85). */
    val exampleAssets: Path = required("udea.exampleAssets")

    /**
     * This module's test runtime classpath, as the script compile classpath.
     *
     * It is what makes `AssetScope` visible to a fixture script, and it is what a forked
     * worker is launched with.
     */
    val compilerClasspath: List<Path> =
        checkNotNull(System.getProperty("udea.assetsCompiler.classpath")) {
            "system property 'udea.assetsCompiler.classpath' is not set; udea-assets-compiler's " +
                "build script sets it on every Test task"
        }.split(java.io.File.pathSeparatorChar).filter { it.isNotBlank() }.map { Path(it) }


    /**
     * A fresh scratch directory under `build/tmp`, emptied on the way in.
     *
     * Not `@TempDir`, and the reason is a real finding rather than a test convenience: the
     * Kotlin scripting host keeps the classloader for each compiled-script jar open, so on
     * Windows every jar in a script cache stays locked for the life of the JVM. JUnit's
     * `@TempDir` cleanup then fails the test after every assertion in it has passed. A
     * directory under `build` is deleted by `clean` instead, by a process that is not holding
     * the lock. (The lock itself matters beyond tests - see the report's note on the daemon
     * evicting cache entries.)
     */
    fun scratch(name: String): Path {
        val dir = repoRoot.resolve("udea-assets-compiler/build/tmp/scratch/$name")
        dir.toFile().deleteRecursively()
        dir.toFile().mkdirs()
        return dir
    }

    private fun required(property: String): Path {
        val value = checkNotNull(System.getProperty(property)) {
            "system property '$property' is not set; udea-assets-compiler's build script sets " +
                "it on every Test task"
        }
        return Path(value)
    }
}
