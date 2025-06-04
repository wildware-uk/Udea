package dev.wildware.udea.editors.builder.editors

import com.intellij.openapi.project.Project
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.columns
import dev.wildware.udea.assets.Sprite
import dev.wildware.udea.editors.builder.UValueBuilder

@UdeaEditor(Sprite::class)
class USpriteEditor : UEditor<UValueBuilder> {
    override fun Panel.buildEditor(
        project: Project,
        builder: UValueBuilder,
        onSave: () -> Unit
    ) {
        val files = getAllPngFiles(project)
        row {
            comboBox(files).apply {
                component.isEditable = true
            }
                .bindItem(getter = { (builder.value as? Sprite)?.spritePath }, setter = {})
                .onChanged {
                    builder.value = Sprite(it.item)
                    onSave()
                }
                .columns(16)
        }
    }

    fun getAllPngFiles(project: Project): List<String> {
        val pngFiles = FilenameIndex.getAllFilesByExt(project, "png", GlobalSearchScope.projectScope(project))
        return pngFiles.map { (it.canonicalPath ?: it.path).substringAfter("assets/") }
    }
}
