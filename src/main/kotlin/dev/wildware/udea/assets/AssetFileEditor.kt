package dev.wildware.udea.assets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposePanel
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
import dev.wildware.udea.*
import io.kanro.compose.jetbrains.expui.theme.DarkTheme
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JPanel
import kotlin.reflect.KClass
import kotlin.reflect.full.companionObjectInstance

class AssetFileEditorProvider : FileEditorProvider, DumbAware {

    init {
        AssetEditor.register(Any::class, GenericAssetEditor)
        AssetEditor.register(Level::class, LevelEditor())
    }

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
            .fromJson<Asset<*>>(documentText)
        return AssetFileEditor(project, file, asset)
    }

    override fun getEditorTypeId() = "AssetEditor"

    override fun getPolicy() = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}

class AssetFileEditor(
    val project: Project,
    private val file: VirtualFile,
    asset: Asset<*>,
) : FileEditor {

    val assetId = asset.id
    val assetValueClass = asset.value!!::class
    val assetType = asset.value!!::class.companionObjectInstance as AssetType<out Any>

    var currentState by mutableStateOf(objectToParameters(asset.value::class, asset.value!!))
    val document = file.findDocument()!!

    var modified = false

    private val component = JPanel(BorderLayout()).apply {
        add(ComposePanel().apply {
            setContent {
                remember {
                    document.addDocumentListener(object : DocumentListener {
                        override fun documentChanged(event: DocumentEvent) {
                            val asset =
                                Json.withClassLoader(ProjectClassLoaderManager.Companion.getInstance(project).classLoader)
                                    .fromJson<Asset<out Any>>(event.document.text)
                            currentState = objectToParameters(asset.value::class, asset.value)
                        }
                    })
                }

                with(AssetEditor.get(assetValueClass)) {
                    DarkTheme {
                        CreateAssetEditor()
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

    fun objectToParameters(kClass: KClass<*>, instance: Any): Array<Any?> {
        val constructor = kClass.constructors.first()
        val parameters = constructor.parameters
        return parameters.map { parameter ->
            kClass.members.find { it.name == parameter.name }?.call(instance)
        }.toTypedArray()
    }
}
