package dev.wildware.udea.editors.builder.editors

import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import dev.wildware.udea.editors.builder.UValueBuilder

@UdeaEditor(Float::class)
class UFloatFormEditor : UFormEditor<UValueBuilder> {
    override fun Panel.buildEditor(project: Project, builder: UValueBuilder, onSave: () -> Unit) {
        row {
            textField()
                .bindText(getter = { (builder.value as? Float)?.toString() ?: "" }, setter = {})
                .onChanged { builder.value = it.text.toFloatOrNull() ?: 0F; onSave() }
                .align(AlignX.RIGHT)
                .resizableColumn()
        }
    }
}
