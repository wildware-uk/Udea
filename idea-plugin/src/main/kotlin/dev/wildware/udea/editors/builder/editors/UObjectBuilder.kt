package dev.wildware.udea.editors.builder.editors

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.layout.selectedValueMatches
import dev.wildware.udea.camelCaseToTitle
import dev.wildware.udea.editors.builder.UAbstractClassBuilder
import dev.wildware.udea.editors.builder.UObjectBuilder
import javax.swing.ListCellRenderer

@UdeaEditor(Any::class)
class UObjectBuilder : UEditor<UObjectBuilder> {
    override fun Panel.buildEditor(project: Project, builder: UObjectBuilder, onSave: () -> Unit) {
        collapsibleGroup(builder.type.type.simpleName?.camelCaseToTitle() ?: "", indent = false) {
            builder.children.map {
                val (name, value) = it

                when (value) {
                    is UAbstractClassBuilder -> {
                        lateinit var combobox: Cell<ComboBox<Map.Entry<String, UObjectBuilder>>>

                        row {
                            label(name.camelCaseToTitle())
                                .widthGroup("name")

                            combobox = comboBox(
                                value.concreteClasses.entries,
                                ListCellRenderer { list, value, index, isSelected, cellHasFocus ->
                                    JBLabel(
                                        value?.key?.substringAfterLast(".")?.camelCaseToTitle()
                                            ?: "Select Class..."
                                    )
                                })
                                .bindItem(
                                    getter = { value.concreteClasses.entries.firstOrNull { it.key == value.concreteClass } },
                                    setter = {}
                                )
                                .onChanged {
                                    value.concreteClass = it.item.key
                                }
                                .columns(16)
                                .onChanged { onSave() }

                        }

                        value.concreteClasses.forEach { concreteClass ->
                            row {
                                with(UEditors.getEditor(concreteClass.value)) {
                                    panel {
                                        buildEditor(project, concreteClass.value, onSave)
                                    }
                                }
                            }.visibleIf(combobox.component.selectedValueMatches { it?.key == concreteClass.key })
                        }
                    }

                    else -> {
                        with(UEditors.getEditor(value)) {
                            row {
                                label(name.camelCaseToTitle())
                                    .widthGroup("name")

                                panel {
                                    buildEditor(project, value, onSave)
                                }
                            }
                        }
                    }
                }
            }
        }.expanded = true
    }
}
