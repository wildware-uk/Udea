package dev.wildware.udea

import androidx.compose.ui.awt.ComposePanel
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import dev.wildware.udea.editors.NewObjectBuilder
import io.kanro.compose.jetbrains.expui.theme.DarkTheme
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JPanel

class AssetFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file.extension == "udea"
    }

    override fun createEditor(
        project: Project,
        file: VirtualFile
    ): FileEditor {
        val classLoaderManager = ProjectClassLoaderManager.getInstance(project)
        Json.configure {
            typeFactory = typeFactory.withClassLoader(classLoaderManager.classLoader)
        }
        val asset = Json.fromJson<Asset<*>>(file.inputStream)
        return AssetFileEditor(project, file, asset)
    }

    override fun getEditorTypeId() = "SceneEditor"

    override fun getPolicy() = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}

class AssetFileEditor(
    private val project: Project,
    private val file: VirtualFile,
    private val asset: Asset<*>,
) : FileEditor {

    private val component = JPanel(BorderLayout()).apply {
        add(ComposePanel().apply {
            val psiManager = PsiManager.getInstance(project)
            val psiFile = psiManager.findFile(file)
            val assetClass = PsiTreeUtil.findChildOfType(psiFile, PsiClass::class.java)
            val cc = ProjectClassLoaderManager.getInstance(project).classLoader
                .loadClass(assetClass!!.qualifiedName)
                .kotlin

            setContent {
                DarkTheme {
                    NewObjectBuilder(cc)
                }
            }
        }, BorderLayout.CENTER)
    }

    override fun getComponent() = component
    override fun getPreferredFocusedComponent() = component

    override fun getName() = "Asset Editor"

    override fun setState(p0: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(p0: PropertyChangeListener) {
    }

    override fun removePropertyChangeListener(p0: PropertyChangeListener) {
    }

    override fun <T : Any?> getUserData(p0: Key<T?>): T? = null

    override fun <T : Any?> putUserData(p0: Key<T?>, p1: T?) {
    }

    override fun dispose() {
    }

    override fun getFile() = file
}
