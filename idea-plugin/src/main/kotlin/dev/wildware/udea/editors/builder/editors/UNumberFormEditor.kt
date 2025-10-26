package dev.wildware.udea.editors.builder.editors

import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindIntText
import dev.wildware.udea.editors.builder.UValueBuilder

@UdeaEditor(Int::class)
class UNumberFormEditor : UFormEditor<UValueBuilder> {
    override fun Panel.buildEditor(project: Project, builder: UValueBuilder, onSave: () -> Unit) {
        row {
            intTextField()
                .bindIntText(getter = { (builder.value as? Int) ?: 0}, setter = {})
                .onChanged { builder.value = it.text.toIntOrNull() ?: 0; onSave() }
                .align(AlignX.RIGHT)
                .resizableColumn()
        }
    }
}
