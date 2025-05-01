package dev.wildware.udea.editors

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.unit.dp
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import dev.wildware.udea.Json
import dev.wildware.udea.ProjectClassLoaderManager
import dev.wildware.udea.assets.Asset
import dev.wildware.udea.assets.Assets
import io.kanro.compose.jetbrains.expui.control.Label
import io.kanro.compose.jetbrains.expui.theme.DarkTheme
import kotlinx.coroutines.delay
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
        val classLoaderManager = ProjectClassLoaderManager.Companion.getInstance(project)
        val documentText = file.findDocument()!!.text
        val asset = Json
            .withClassLoader(classLoaderManager.classLoader)
            .fromJson<Asset>(documentText)
        return AssetFileEditor(project, file, asset)
    }

    override fun getEditorTypeId() = "AssetEditor"

    override fun getPolicy() = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}

class AssetFileEditor(
    val project: Project,
    private val file: VirtualFile,
    asset: Asset,
) : FileEditor {

    val assetValueClass = asset::class

    var currentState: Asset by mutableStateOf(asset)

    val document = file.findDocument()!!

    var modified = false

    private val component = JPanel(BorderLayout()).apply {
        add(ComposePanel().apply {
            setContent {
                var areAssetsLoaded by remember { mutableStateOf(Assets.ready) }

                if (!areAssetsLoaded) {
                    LaunchedEffect(Unit) {
                        while (!Assets.ready) {
                            delay(100)
                            areAssetsLoaded = Assets.ready
                        }
                    }
                    Label("Loading assets...")
                    return@setContent
                }

                remember {
                    document.addDocumentListener(object : DocumentListener {
                        override fun documentChanged(event: DocumentEvent) {
                            currentState =
                                Json.withClassLoader(ProjectClassLoaderManager.Companion.getInstance(project).classLoader)
                                    .fromJson<Asset>(event.document.text)
                        }
                    })
                }

                DarkTheme {
                    Box(Modifier.padding(8.dp)) {
                        Column {
                            Label("Asset Editor")

                            Spacer(Modifier.padding(8.dp))

                            Editors.getEditor(Any::class)
                                ?.CreateEditor(project, EditorType(assetValueClass), currentState) {
                                    currentState = it as Asset
                                    modified = true

                                    WriteCommandAction.runWriteCommandAction(project) {
                                        document.setText(Json.toJson(it))
                                        modified = false
                                    }
                                }
                        }
                    }
                }
            }
        }, BorderLayout.CENTER)
    }

    override fun getComponent() = component
    override fun getPreferredFocusedComponent() = component

    override fun getName() = "Asset Editor"

    override fun setState(p0: FileEditorState) = Unit
    override fun isModified() = modified
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
