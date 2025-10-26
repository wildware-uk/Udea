package dev.wildware.udea.editors

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import dev.wildware.udea.Json
import dev.wildware.udea.ProjectClassLoaderManager
import dev.wildware.udea.assets.AssetFile
import dev.wildware.udea.editors.builder.UObjectBuilder
import dev.wildware.udea.editors.builder.editors.UObjectEditors
import dev.wildware.udea.editors.builder.toUBuilder
import dev.wildware.udea.findClassByName
import dev.wildware.udea.toType
import java.beans.PropertyChangeListener

class AssetFileEditorProvider : FileEditorProvider {
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
            .fromJson<AssetFile>(documentText)
        return AssetFileEditor(project, file, asset)
    }

    override fun getEditorTypeId() = "AssetEditor"

    override fun getPolicy() = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}

class AssetFileEditor(
    val project: Project,
    private val file: VirtualFile,
    val assetFile: AssetFile,
) : FileEditor {

    val assetValueClass = ProjectClassLoaderManager
        .getInstance(project).classLoader.loadClass(assetFile.type).kotlin

    val document = file.findDocument()!!

    var modified = false

    val builder = findClassByName(project, assetFile.type)!!
        .toType(project)
        .toUBuilder(project, assetFile.asset) as UObjectBuilder

    private val component = UObjectEditors.getEditor(project, builder, onSave = {
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(Json.toJson(AssetBuilder(assetValueClass.qualifiedName!!, builder)))
            modified = false
        }
    }).createEditor()

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

data class AssetBuilder(
    val type: String,

    val asset: UObjectBuilder
)