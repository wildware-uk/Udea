package dev.wildware.udea.editors

import com.badlogic.gdx.assets.AssetManager
import com.badlogic.gdx.files.FileHandle
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.fileEditor.impl.text.PsiAwareTextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBSplitter
import dev.wildware.GameEditorCanvas
import dev.wildware.udea.AssetLoader
import dev.wildware.udea.ProjectClassLoaderManager
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.loadAssets
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

class AssetFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file.name.endsWith(".udea.kts")
    }

    override fun createEditor(
        project: Project,
        file: VirtualFile
    ): FileEditor {
        // Ensure Kotlin script gets standard editor features
        ProjectClassLoaderManager.getInstance(project)
        return AssetFileEditor(project, file)
    }

    override fun getEditorTypeId() = "UdeaKtsSplitEditor"

    // Replace the default text editor with our split editor
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class AssetFileEditor(
    val project: Project,
    private val file: VirtualFile
) : FileEditor {
    private val textEditorProvider = PsiAwareTextEditorProvider()
    private val textEditor: TextEditor = textEditorProvider.createEditor(project, file) as TextEditor

    init {
        loadAssets(file.toNioPath().toFile())
            .forEach { asset -> Assets[asset.path] = asset }
    }

    val editorPanel = GameEditorCanvas(object : AssetLoader {
        override fun load(manager: AssetManager) {
        }

        override fun resolve(fileName: String): FileHandle {
            return FileHandle(fileName)
        }
    })

    // Expose a secondary panel so more components can be added later by the plugin
    val rightPanel: JPanel = JPanel(BorderLayout()).apply {
        name = "UdeaRightPanel"
        add(editorPanel.getCanvas(), BorderLayout.CENTER)
    }

    private val splitter: JBSplitter = JBSplitter(false, 0.7f).apply {
        firstComponent = textEditor.component
        secondComponent = rightPanel
    }

    override fun getComponent(): JComponent = splitter

    override fun getPreferredFocusedComponent(): JComponent? = textEditor.preferredFocusedComponent

    override fun getName() = "Udea Kotlin Script"

    override fun setState(state: FileEditorState) {
        textEditor.setState(state)
    }

    override fun isModified(): Boolean = textEditor.isModified

    override fun isValid(): Boolean = textEditor.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        textEditor.addPropertyChangeListener(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        textEditor.removePropertyChangeListener(listener)
    }

    override fun <T : Any?> getUserData(key: Key<T?>): T? = textEditor.getUserData(key)

    override fun <T : Any?> putUserData(key: Key<T?>, value: T?) {
        textEditor.putUserData(key, value)
    }

    override fun dispose() {
        textEditorProvider.disposeEditor(textEditor)
    }

    override fun getFile(): VirtualFile = file
}
