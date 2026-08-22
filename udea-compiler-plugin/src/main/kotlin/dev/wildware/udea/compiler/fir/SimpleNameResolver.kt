package dev.wildware.udea.compiler.fir

import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirRegularClass

/**
 * Turns a simple name written in a `[Foo]` KDoc link into the fully qualified name a
 * generated file can resolve.
 *
 * It answers from exactly two places, which are the two a reader of the source file would
 * use: the file's **explicit imports** and the types **declared in the file itself**. A star
 * import is not consulted, and neither is the implicit `kotlin.*` - both would need the
 * resolved classpath, and a wrong answer here is worse than no answer: [KDocLinks] leaves an
 * unresolved name exactly as the author wrote it, whereas a mis-qualified one silently points
 * the generated KDoc at a type that does not exist.
 */
@OptIn(DirectDeclarationsAccess::class)
internal object SimpleNameResolver {

    /** Builds the resolver for one file. The map is built once; each lookup is a map read. */
    fun forFile(file: FirFile): (String) -> String? {
        val byName = HashMap<String, String>()
        for (declaration in file.declarations) {
            if (declaration is FirRegularClass) collect(declaration, byName)
        }
        for (import in file.imports) {
            if (import.isAllUnder) continue
            val imported = import.importedFqName ?: continue
            val alias = import.aliasName?.asString() ?: imported.shortName().asString()
            // `putIfAbsent`: a name declared in this file wins, which is what Kotlin itself
            // does. In valid code the two cannot collide anyway.
            byName.putIfAbsent(alias, imported.asString())
        }
        return byName::get
    }

    private fun collect(declaration: FirRegularClass, out: MutableMap<String, String>) {
        val classId = declaration.symbol.classId
        out.putIfAbsent(classId.shortClassName.asString(), classId.asFqNameString())
        for (nested in declaration.declarations) {
            if (nested is FirRegularClass) collect(nested, out)
        }
    }
}
