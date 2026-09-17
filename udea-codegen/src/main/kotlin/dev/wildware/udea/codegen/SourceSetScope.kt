package dev.wildware.udea.codegen

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile

/**
 * Which declarations a run processes: all of them, or only one source set's.
 *
 * See [CodegenOptions.sourceSet]. The match is on the `src/<sourceSet>/` path segment of the
 * declaring file, which is the Gradle layout every multiplatform module in this repository uses.
 * A declaration with no source file is admitted only by an unscoped run: a scoped run is asked
 * about one source set, and a file-less declaration belongs to none.
 */
internal class SourceSetScope(sourceSet: String?) {

    private val segment: String? = sourceSet?.let { "/src/$it/" }

    fun admitsFile(file: KSFile?): Boolean {
        val required = segment ?: return true
        return file != null && file.filePath.replace('\\', '/').contains(required)
    }

    fun admits(node: KSAnnotated): Boolean = admitsFile((node as? KSDeclaration)?.containingFile)
}
