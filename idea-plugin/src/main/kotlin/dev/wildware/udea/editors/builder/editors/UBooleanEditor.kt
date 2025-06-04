package dev.wildware.udea.editors.builder.editors

import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.layout.selected
import dev.wildware.udea.editors.builder.UValueBuilder

@UdeaEditor(Boolean::class)
class UBooleanEditor : UEditor<UValueBuilder> {
    override fun Panel.buildEditor(project: Project, builder: UValueBuilder, onSave: () -> Unit) {
        row {
            checkBox("")
                .bindSelected(
                    getter = { (builder.value as? Boolean) ?: false },
                    setter = { builder.value = it }
                )
                .onChanged { builder.value = it.selected; onSave() }
        }
    }
}
