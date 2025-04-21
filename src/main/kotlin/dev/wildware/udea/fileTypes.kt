package dev.wildware.udea

import com.fasterxml.jackson.databind.JsonNode
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
    override fun getName(): String = "UDEA File"
    override fun getDescription(): String = "UDEA custom file type"
    override fun getDefaultExtension(): String = "udea"
    override fun getIcon(): Icon? = null // Provide an icon if needed
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
                if (file != null && file.extension == "udea") {
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
        val contents = Json.fromJson<JsonNode>(psiFile.virtualFile.inputStream)
        val assetType = contents.get("type")
        presentation.locationString = assetType?.asText() ?: ""
        presentation.presentableText = psiFile.name.removeSuffix(".udea")
        presentation.setIcon(UdeaIcons.Blueprint)
    }
}

// blueprint juicy_fish attribute <a href="https://www.flaticon.com/free-icons/blueprint" title="blueprint icons">Blueprint icons created by juicy_fish - Flaticon</a>