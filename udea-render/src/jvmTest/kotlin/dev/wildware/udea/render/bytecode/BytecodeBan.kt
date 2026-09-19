package dev.wildware.udea.render.bytecode

import dev.wildware.udea.diagnostics.DiagnosticReport
import dev.wildware.udea.diagnostics.DiagnosticSink
import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.SourceSpan
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import dev.wildware.udea.render.support.RepoLayout
import java.io.File

/**
 * A bytecode gate: a [BannedOwner] table read against the main bytecode of a list of modules, with
 * each hit reported under one rule id.
 *
 * `HeadlessScan` (GL in a headless module) and `LibGdxScan` (LibGDX in any module) are both one of
 * these. What differs between gates is the table, the modules and the wording of the failure; the
 * walk, the refusal to pass on an empty module and the source span are the same, and live here once.
 */
internal class BytecodeBan(
    /** The id every violation is reported under. */
    val ruleId: String,
    /** The Gradle task that runs the gate, named in the failures that mean the gate is broken. */
    private val gateTask: String,
    private val banned: List<BannedOwner>,
    /** The failure message for one reference: the module, the use and the entry it matched. */
    private val describe: (module: String, use: TypeUse, entry: BannedOwner) -> String,
) {

    /**
     * Runs the scan over [modules].
     *
     * @throws IllegalStateException if a module contributed no class files at all. An empty scan
     *   is a broken gate, not a clean module: it would pass forever while the module quietly grew
     *   what the table bans. (The same reasoning as `DependencyRules.vacuity`.)
     */
    fun run(
        modules: List<String>,
        classFilesOf: (String) -> List<File>,
        excused: Map<String, List<String>> = emptyMap(),
    ): DiagnosticReport {
        val sink = DiagnosticSink()
        for (module in modules) {
            val classFiles = classFilesOf(module)
            check(classFiles.isNotEmpty()) {
                "$module contributed no compiled classes to $gateTask. The gate is broken, not " +
                    "the module: a scan of nothing passes forever. Build the module before " +
                    "running the gate."
            }
            sink.reportAll(violations(module, classFiles, excused[module].orEmpty()))
        }
        return sink.build()
    }

    /**
     * Every banned reference in [classFiles], as diagnostics attributed to [module], except a
     * reference into one of the [excused] namespaces (the FBX converter's, issue #244).
     */
    fun violations(module: String, classFiles: List<File>, excused: List<String> = emptyList()): List<UdeaDiagnostic> = classFiles
        .flatMap { file -> ClassRefScanner.scan(file) }
        .filterNot { use -> excused.any { use.owner.startsWith(it) } }
        .mapNotNull { use -> banned.firstOrNull { it.matches(use.owner) }?.let { use to it } }
        .map { (use, entry) ->
            UdeaDiagnostic(
                severity = Severity.Error,
                ruleId = ruleId,
                message = describe(module, use, entry),
                span = span(module, use),
            )
        }

    /**
     * Where the offending class was compiled from, or `null` when the source cannot be found
     * -- a generated class, or one compiled from a language directory this does not know
     * about. A span invented for such a class would point at a file that does not exist.
     */
    private fun span(module: String, use: TypeUse): SourceSpan? {
        val source = RepoLayout.sourceFileOf(module, use.className, use.sourceFile) ?: return null
        val line = use.line
        return SourceSpan(RepoLayout.relativePath(source), line, 0, line, 0)
    }

    companion object {

        /**
         * The module list a build script handed a gate in the system property [property].
         *
         * @throws IllegalStateException when the property is missing or names no modules. A scan
         *   with no modules passes forever, so a broken hand-off has to be louder than a green tick.
         */
        fun modulesFrom(property: String, gateTask: String): List<String> {
            val raw = System.getProperty(property)
            checkNotNull(raw) {
                "-D$property was not set. The designated modules come from udea-render's build " +
                    "script; run this through Gradle (`:udea-render:$gateTask`) or set the " +
                    "property. A scan that defaulted to a list written down here is the drift " +
                    "the hand-off replaced."
            }
            val modules = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            check(modules.isNotEmpty()) {
                "-D$property was set to '$raw', which names no modules. The gate is broken, " +
                    "not the tree: a scan of nothing passes forever."
            }
            return modules
        }

        /** "`owner.member`" when the use names a member of the banned owner, else the owner. */
        fun referenced(use: TypeUse): String = use.ownerMember?.let { "${use.owner}.$it" } ?: use.owner
    }
}
