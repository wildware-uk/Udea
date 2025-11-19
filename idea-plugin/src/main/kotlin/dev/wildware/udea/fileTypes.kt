package dev.wildware.udea

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.TreeStructureProvider
import com.intellij.ide.projectView.ViewSettings
import com.intellij.ide.projectView.impl.nodes.PsiFileNode
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import org.jetbrains.annotations.Unmodifiable
import javax.swing.Icon

class UdeaFileType : FileType {
    override fun getName(): String = "UDEA Asset File"
    override fun getDescription(): String = "UDEA Asset file type"
    override fun getDefaultExtension(): String = ".udea.kts"
    override fun getIcon(): Icon = UdeaIcons.Blueprint
    override fun isBinary(): Boolean = false
}

class UdeaFileStructureProvider : TreeStructureProvider {
    override fun modify(
        parent: AbstractTreeNode<*>,
        children: Collection<AbstractTreeNode<*>>,
        settings: ViewSettings?
    ): @Unmodifiable Collection<AbstractTreeNode<*>> {
        return children.map { child ->
            if (child is PsiFileNode) {
                val file = child.virtualFile

                if (file != null && file.name.endsWith(".udea.kts")) {
                    UdeaFileNode(child.project, child.value, settings)
                } else {
                    child
                }
            } else {
                child
            }
        }
    }
}

class UdeaFileNode(
    project: Project,
    val psiFile: PsiFile,
    viewSettings: ViewSettings?,
) : PsiFileNode(project, psiFile, viewSettings) {
    override fun update(presentation: PresentationData) {
        super.update(presentation)

        try {
            presentation.presentableText = psiFile.name.removeSuffix(".udea.kts")
            presentation.locationString = "UDEA Asset"
            presentation.setIcon(UdeaIcons.Blueprint)
        } catch (e: Exception) {
        }
    }
}

// blueprint juicy_fish attribute <a href="https://www.flaticon.com/free-icons/blueprint" title="blueprint icons">Blueprint icons created by juicy_fish - Flaticon</a>