package dev.wildware.udea.assets.compiler.scan

import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile

/**
 * A Kotlin PSI parser with **no classpath and no resolution**.
 *
 * This is the whole of pass 1's dependency on `kotlin-compiler-embeddable`: a
 * [PsiFileFactory] over an environment configured with nothing on it. No JDK roots, no
 * classpath entries, no script definitions, no analysis. That is what lets pass 1 run before
 * the generated accessors exist, which is how the chicken-and-egg between "scripts need
 * accessors" and "accessors need the scan" is broken (spec 3.6).
 *
 * The environment is expensive to build (tens of milliseconds) and cheap to reuse, so one
 * instance serves a whole scan and the daemon keeps one alive across rescans. It is not
 * thread-safe: IntelliJ PSI is not.
 */
public class KtParser @JvmOverloads constructor(
    /**
     * Whether [close] tears the environment down.
     *
     * False for [shared]. Disposing a [KotlinCoreEnvironment] does not only free this
     * parser: the environments are reference-counted against one process-wide IntelliJ
     * application environment, which the *script compiler* in pass 2 also uses. Tearing it
     * down out from under a scripting host in the same JVM makes a later compile fail with
     * "unresolved reference" for classes that are plainly on its classpath - which is
     * exactly what happened when every scanner disposed its own environment and a pass-2
     * test ran after a pass-1 one.
     */
    private val disposeOnClose: Boolean = true,
) : AutoCloseable {

    private val disposable: Disposable = Disposer.newDisposable("udea-assets-compiler-psi")

    private val factory: PsiFileFactory = run {
        val configuration = CompilerConfiguration().apply {
            put(CommonConfigurationKeys.MODULE_NAME, "udea-asset-scan")
            put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        }
        val environment = KotlinCoreEnvironment.createForProduction(
            disposable,
            configuration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
        PsiFileFactory.getInstance(environment.project)
    }

    /**
     * Parses [text] as the script [fileName].
     *
     * [fileName] must keep a `.kts` extension: the Kotlin parser picks script parsing over
     * file parsing from the extension alone, and a name ending in `.kt` would make every
     * top-level `character(...)` call a syntax error instead of a statement.
     */
    public fun parse(fileName: String, text: String): KtFile {
        require(fileName.endsWith(".kts")) {
            "KtParser parses scripts; '$fileName' does not end in .kts, so it would be parsed " +
                "as a declaration file and every top-level call would be a syntax error"
        }
        val file = factory.createFileFromText(fileName, KotlinFileType.INSTANCE, text)
        return file as? KtFile
            ?: error("PsiFileFactory produced ${file.javaClass.name} for '$fileName', not a KtFile")
    }

    override fun close() {
        if (disposeOnClose) Disposer.dispose(disposable)
    }

    public companion object {
        /**
         * The process-wide parser.
         *
         * One environment, built on first use and never disposed. That is the daemon's shape
         * anyway - the environment is built once and reused across every rescan - and it is
         * the only shape that coexists with pass 2 in one JVM; see [disposeOnClose].
         */
        public val shared: KtParser by lazy { KtParser(disposeOnClose = false) }
    }
}

/**
 * A one-shot offset-to-line/column index over a source text.
 *
 * PSI reports offsets; every diagnostic contract in this repo is line- and column-based, and
 * the conversion has to be done once per file rather than once per span or a file with a
 * thousand references becomes quadratic.
 *
 * Lines and columns are 1-based, matching [dev.wildware.udea.diagnostics.SourceSpan]'s
 * `path:line:column` rendering and every editor that consumes it.
 */
public class LineIndex(text: CharSequence) {

    /** Offset of the first character of each line. */
    private val lineStarts: IntArray = buildList {
        add(0)
        for (i in text.indices) if (text[i] == '\n') add(i + 1)
    }.toIntArray()

    /** 1-based line number containing [offset]. */
    public fun lineOf(offset: Int): Int {
        val found = lineStarts.binarySearch(offset)
        return if (found >= 0) found + 1 else -found - 1
    }

    /** 1-based column of [offset] within its line. */
    public fun columnOf(offset: Int): Int = offset - lineStarts[lineOf(offset) - 1] + 1
}
