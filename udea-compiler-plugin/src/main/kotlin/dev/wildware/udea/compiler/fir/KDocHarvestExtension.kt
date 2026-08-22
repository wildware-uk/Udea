package dev.wildware.udea.compiler.fir

import dev.wildware.udea.compiler.kdoc.KDocEntry
import dev.wildware.udea.compiler.kdoc.KDocIndexSink
import dev.wildware.udea.compiler.kdoc.KDocLinks
import dev.wildware.udea.compiler.kdoc.KDocParser
import dev.wildware.udea.compiler.kdoc.KDocScanner
import dev.wildware.udea.diagnostics.SourceSpan
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.text

/**
 * Harvests the KDoc of every source declaration into `kdoc-index.json`.
 *
 * Spec 3.2 assigns this to K2 because "KSP cannot read or re-emit KDoc" - a KSP processor sees
 * a resolved symbol, and a doc comment is not part of one. This is one of the three things D8
 * buys, and Trello #12 ("Copy KDoc to DSL") is the card it closes: the old `UdeaDslProcessor`
 * emitted builders with every word of documentation stripped.
 *
 * It is a *checkers* extension that reports nothing. That is deliberate rather than a hack:
 * a checkers extension is the one K2 extension point that is handed a fully resolved
 * [FirFile] with its source offsets and its imports intact, which is exactly what the
 * harvester needs, and registering it as a checker keeps issue #38's "zero
 * `FirDeclarationGenerationExtension`s" property true by construction.
 *
 * It runs only when the `kdocIndex` option names an output path, so a normal build - and every
 * build with the plugin disabled - never pays for it, and never depends on it.
 */
internal class KDocHarvestExtension(
    session: FirSession,
    sink: KDocIndexSink,
) : FirAdditionalCheckersExtension(session) {

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val fileCheckers: Set<FirDeclarationChecker<FirFile>> = setOf(KDocFileChecker(sink))
    }

    companion object {
        /** Builds the session factory the [UdeaFirExtensionRegistrar] registers. */
        fun factory(indexPath: String, repoRoot: String): (FirSession) -> FirAdditionalCheckersExtension {
            val sink = KDocIndexSink(indexPath, repoRoot)
            return { session -> KDocHarvestExtension(session, sink) }
        }
    }
}

/**
 * Walks one file and hands every documented declaration to [sink].
 *
 * A file at a time rather than a declaration at a time, because the two things a harvested
 * entry needs beyond the declaration itself - the file's text, to find the comment, and the
 * file's imports, to qualify `[Foo]` links - are both properties of the file.
 */
@OptIn(DirectDeclarationsAccess::class)
private class KDocFileChecker(
    private val sink: KDocIndexSink,
) : FirDeclarationChecker<FirFile>(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        // A file with no source text is a compiled dependency, not something being authored;
        // there is nothing to harvest and nothing to warn about.
        val fileText = declaration.source?.text?.toString() ?: return
        val path = declaration.sourceFile?.path ?: return
        val lines = declaration.sourceFileLinesMapping
        val resolve = SimpleNameResolver.forFile(declaration)

        for (member in declaration.declarations) {
            harvest(member, fileText, path, lines, resolve)
        }
    }

    private fun harvest(
        declaration: FirDeclaration,
        fileText: String,
        path: String,
        lines: org.jetbrains.kotlin.KtSourceFileLinesMapping?,
        resolve: (String) -> String?,
    ) {
        val fqn = fullyQualifiedNameOf(declaration)
        val start = declaration.source?.startOffset
        if (fqn != null && start != null) {
            val raw = KDocScanner.docCommentAt(fileText, start)
            val parsed = raw?.let(KDocParser::parse)
            // A declaration with no KDoc produces no entry and no warning: issue #42 makes a
            // missing entry mean "no documentation", never a build failure.
            if (parsed != null && !parsed.isEmpty) {
                val (line, column) = lines?.getLineAndColumnByOffset(start) ?: (0 to 0)
                sink.add(
                    KDocEntry(
                        fqn = fqn,
                        span = SourceSpan.of(sink.repoRoot, path, line + 1, column + 1),
                        doc = KDocLinks.qualify(parsed, resolve),
                    ),
                )
            }
        }
        if (declaration is FirRegularClass) {
            for (member in declaration.declarations) {
                harvest(member, fileText, path, lines, resolve)
            }
        }
    }

    private fun fullyQualifiedNameOf(declaration: FirDeclaration): String? = when (declaration) {
        is FirRegularClass -> declaration.symbol.classId.asFqNameString()
        is FirCallableDeclaration -> declaration.symbol.callableId?.asSingleFqName()?.asString()
        else -> null
    }
}
