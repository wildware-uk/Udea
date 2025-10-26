package dev.wildware.udea.editors.builder.editors

import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.layout.selected
import dev.wildware.udea.editors.builder.UValueBuilder

@UdeaEditor(Boolean::class)
class UBooleanFormEditor : UFormEditor<UValueBuilder> {
    override fun Panel.buildEditor(project: Project, builder: UValueBuilder, onSave: () -> Unit) {
        row {
            checkBox("")
                .bindSelected(
                    getter = { (builder.value as? Boolean) ?: false },
                    setter = { builder.value = it }
                )
                .align(AlignX.RIGHT)
                .onChanged { builder.value = it.isSelected; onSave() }
        }
    }
}
