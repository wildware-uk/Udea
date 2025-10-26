package dev.wildware.udea.editors.builder.editors

import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import dev.wildware.udea.editors.builder.UValueBuilder

@UdeaEditor(String::class)
class UStringFormEditor : UFormEditor<UValueBuilder> {
    override fun Panel.buildEditor(project: Project, builder: UValueBuilder, onSave: () -> Unit) {
        row {
            textField()
                .resizableColumn()
                .bindText(
                    getter = { (builder.value as? String) ?: "" },
                    setter = { builder.value = it }
                )
                .align(AlignX.RIGHT)
                .onChanged { onSave() }
        }
    }
}
