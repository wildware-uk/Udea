package dev.wildware.udea.assets.compiler.scan

import dev.wildware.udea.assets.compiler.TestPaths
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pass 1 runs with no game classpath and no scripting host (issue #85).
 *
 * The claim spec 3.6 makes about pass 1 is not "we try not to need a classpath", it is that
 * pass 1 *cannot* need one — that is what breaks the chicken-and-egg between generated
 * accessors and script compilation, and it is what lets the daemon rescan a file the instant
 * it is saved rather than after a compile.
 *
 * A claim like that is worth nothing asserted in prose. Here the scanner is loaded into a
 * classloader whose parent is the platform loader (so nothing leaks in from the test JVM's
 * own classpath) and whose URLs deliberately exclude every `kotlin-scripting-*` jar. If any
 * line of pass 1 reached for the scripting host or the compiler's resolution half, the scan
 * would die with `NoClassDefFoundError` rather than quietly working.
 */
class IsolatedScanTest {

    /** True for a jar this test removes from the isolated classpath. */
    private fun isExcluded(path: Path): Boolean {
        val name = path.fileName.toString()
        return "kotlin-scripting" in name || "udea-assets-compiler-" in name && name.endsWith(".jar")
    }

    @Test
    fun `the scanner produces the same result with no scripting host on the classpath`() {
        val full = TestPaths.compilerClasspath
        val excluded = full.filter(::isExcluded)
        assertTrue(
            excluded.any { "kotlin-scripting" in it.fileName.toString() },
            "the full test classpath should carry the scripting host; if it does not, this " +
                "test proves nothing. Classpath was: ${full.map { it.fileName }}",
        )

        val isolated = full.filterNot(::isExcluded).map { it.toUri().toURL() }.toTypedArray()
        // Platform loader, not the app loader: an app-loader parent would delegate every
        // excluded jar straight back in and the isolation would be decorative.
        URLClassLoader(isolated, ClassLoader.getPlatformClassLoader()).use { loader ->
            assertScriptingHostAbsent(loader)

            val scannerClass = loader.loadClass(UdeaDeclarationScanner::class.java.name)
            val jsonClass = loader.loadClass(DeclarationsJson::class.java.name)
            val scanner = scannerClass
                .getConstructor(Path::class.java, Path::class.java)
                .newInstance(TestPaths.repoRoot, TestPaths.exampleAssets)
            val isolatedJson = try {
                val report = scannerClass.getMethod("scanTree").invoke(scanner)
                jsonClass.getMethod("write", loader.loadClass(ScanReport::class.java.name))
                    .invoke(jsonClass.getField("INSTANCE").get(null), report) as String
            } finally {
                scannerClass.getMethod("close").invoke(scanner)
            }

            val hostedJson = UdeaDeclarationScanner(TestPaths.repoRoot, TestPaths.exampleAssets)
                .use { DeclarationsJson.write(it.scanTree()) }

            assertEquals(hostedJson, isolatedJson, "an isolated scan must be the same scan")
            assertTrue(isolatedJson.contains("character/orc_elite"), "sanity: the scan found something")
        }
    }

    /** The exclusion actually removed the jars, rather than the loader finding them anyway. */
    private fun assertScriptingHostAbsent(loader: ClassLoader) {
        val scriptingClass = "kotlin.script.experimental.jvmhost.BasicJvmScriptingHost"
        try {
            loader.loadClass(scriptingClass)
            fail("$scriptingClass is still reachable; the isolated classpath is not isolated")
        } catch (expected: ClassNotFoundException) {
            assertTrue(scriptingClass in expected.message.orEmpty())
        }
    }
}
