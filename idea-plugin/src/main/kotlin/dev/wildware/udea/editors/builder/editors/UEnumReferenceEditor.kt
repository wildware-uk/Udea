package dev.wildware.udea.editors.builder.editors

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.columns
import dev.wildware.udea.camelCaseToTitle
import dev.wildware.udea.ecs.component.UdeaClass
import dev.wildware.udea.ecs.component.UdeaClass.UClassAttribute.NoInline
import dev.wildware.udea.editors.builder.USelectBuilder

@UdeaEditor(Enum::class)
class UEnumReferenceEditor : UEditor<USelectBuilder> {
    override fun Panel.buildEditor(project: Project, builder: USelectBuilder, onSave: () -> Unit) {
        row {
            comboBox(
                builder.values,
                { list, value, index, isSelected, cellHasFocus ->
                    JBLabel((value as? Enum<*>)?.name?.camelCaseToTitle() ?: "Select Value...")
                })
                .columns(16)
                .bindItem(
                    getter = { builder.value },
                    setter = { }
                )
                .onChanged {
                    builder.value = it.item
                    onSave()
                }
        }
    }
}
