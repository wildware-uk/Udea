package dev.wildware.udea.editors.builder.editors

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import dev.wildware.udea.assets.Asset
import dev.wildware.udea.assets.UClass
import dev.wildware.udea.camelCaseToTitle
import dev.wildware.udea.editors.builder.UValueBuilder
import dev.wildware.udea.findClassesOfType
import dev.wildware.udea.toJvmQualifiedName
import javax.swing.ListCellRenderer

@UdeaEditor(UClass::class)
class UClassEditor : UFormEditor<UValueBuilder> {

    override fun Panel.buildEditor(project: Project, builder: UValueBuilder, onSave: () -> Unit) {
        val subclasses = ApplicationManager.getApplication().runReadAction<List<UClass<*>>> {
            findClassesOfType(project, builder.type.generics.first().type.qualifiedName!!)
                .map { UClass<Any>(it.toJvmQualifiedName()) }
        }

        row {
            comboBox(
                subclasses,
                ListCellRenderer<UClass<*>?> { list, value, index, isSelected, cellHasFocus ->
                    JBLabel(value?.className?.substringAfterLast(".")?.camelCaseToTitle() ?: "Select Asset...")
                })
                .align(AlignX.RIGHT)
                .resizableColumn()
                .bindItem(
                    getter = { (builder.value as? UClass<Asset>) },
                    setter = { }
                )
                .onChanged {
                    builder.value = it.item
                    onSave()
                }
        }
    }
}
