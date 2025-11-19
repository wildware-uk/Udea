package dev.wildware.udea

import com.intellij.ide.IconProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import javax.swing.Icon

/**
 * Psi-based icon provider to ensure our icon is applied even when Kotlin or other providers
 * short-circuit FileIconProvider for script files. This runs for PSI elements in views like
 * Project View, tabs, navigation bar, etc.
 */
class UdeaIconProvider : IconProvider(), DumbAware {
    override fun getIcon(element: PsiElement, flags: Int): Icon? {
        // Resolve to the containing file if element is not a file itself
        val file: PsiFile = (element as? PsiFile) ?: element.containingFile ?: return null
        val name = file.name
        return if (name.endsWith(".udea.kts", ignoreCase = true)) {
            UdeaIcons.Blueprint
        } else null
    }
}
