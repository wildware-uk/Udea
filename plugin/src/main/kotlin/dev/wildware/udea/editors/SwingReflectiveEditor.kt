package dev.wildware.udea.editors

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiType
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.AbstractTableCellEditor
import dev.wildware.udea.*
import java.awt.Component
import javax.swing.JTable
import javax.swing.event.TableModelEvent
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableModel
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

object SwingReflectiveEditor : UEditor<Any> {
    override fun Panel.CreateEditor(
        project: Project,
        type: EditorType<*>,
        value: Any?,
        onValueChange: (Any) -> Unit
    ) {
        if (type.type.isSealed || type.type.isAbstract) {
            var concreteClass: KClass<out Any>? = null
            val subclasses =
                findClassesOfType(project, type.type.qualifiedName!!)
                    .map { project.service<ProjectClassLoaderManager>().classLoader.loadClass(it.toJvmQualifiedName()).kotlin }

            row {
                comboBox(subclasses).onChanged {
                    concreteClass = it.item
                }
            }

            concreteClass?.let {
                concreteEditor(EditorType(it), project, value, onValueChange)
            }
        } else {
            concreteEditor(type, project, value, onValueChange)
        }
    }

    private fun Panel.concreteEditor(
        type: EditorType<*>,
        project: Project,
        value: Any?,
        onValueChange: (Any) -> Unit
    ) {
        val constructor = type.type.primaryConstructor ?: error("No primary constructor found for ${type.type}")

        val constructorPsiMap = runReadAction { calculateConstructParams(project, type.type) }

        val currentValues =
            constructor.parameters.associateWith { param ->
                value?.let {
                    type.type.memberProperties.first { prop ->
                        prop.name == param.name
                    }.call(value)
                }
            }.toMutableMap()


        val tableModel = DefaultTableModel().apply {
            addColumn("Name")
            addColumn("Value")
        }

        constructorPsiMap.forEach { (parameter, psiClass) ->
            tableModel.addRow(arrayOf(parameter.name!!.camelCaseToTitle(), currentValues[parameter]))
        }

        val table = ObjectEditorTable(
            project,
            tableModel,
            type,
            constructorPsiMap.values.map { it.toEditorType<Any>() },
            onValueChange = {
                onValueChange(it)
            })

        collapsibleGroup(type.type.simpleName!!.camelCaseToTitle()) {
            row {
                cell(table)
            }
        }.apply { expanded = true }
    }

    private fun calculateConstructParams(
        project: Project,
        kClass: KClass<*>
    ): Map<KParameter, PsiType> {
        val psiClass = findClassByName(project, kClass.qualifiedName!!)
            ?: error("Class not found: ${kClass.qualifiedName}")

        val parameters = kClass.primaryConstructor?.parameters
            ?: error("No primary constructor found for $kClass")

        val psiParams = psiClass.constructors.firstOrNull()?.parameterList?.parameters
            ?.map { it.type }!!

        return parameters.zip(psiParams).toMap()
    }
}

class ObjectEditorTable(
    val project: Project,
    model: TableModel,
    val type: EditorType<*>,
    val rowTypes: List<EditorType<*>>,
    val onValueChange: (Any) -> Unit
) : JBTable(model) {

    init {
        columnModel.getColumn(NameColumn).preferredWidth = 150
        columnModel.getColumn(ValueColumn).preferredWidth = 300
        rowHeight = 35

        columnModel.getColumn(ValueColumn).cellEditor = ValueCellRenderer(project)
    }

    override fun onTableChanged(e: TableModelEvent) {
        if (e.type == TableModelEvent.UPDATE) {
            val newValue = rowTypes.mapIndexed { index, kClass ->
                model.getValueAt(index, 1)
            }

            val constructor = type.type
                .primaryConstructor ?: error("No primary constructor found for ${rowTypes.first()}")
            val constructorParams = constructor.parameters.mapIndexed { index, param ->
                param to newValue[index]
            }.toMap()
            try {
                val newInstance = constructor.callBy(
                    constructorParams
                )

                onValueChange(newInstance)
            } catch (e: NullPointerException) {
            } catch (e: Exception) {
                println("Error creating instance of $type: ${e.message}")
            }
        }
    }

    override fun isCellEditable(row: Int, column: Int): Boolean {
        return column == ValueColumn
    }

    companion object {
        const val NameColumn = 0
        const val ValueColumn = 1
    }
}

class ValueCellRenderer(
    val project: Project
) : AbstractTableCellEditor() {

    var value: Any? = null

    override fun getTableCellEditorComponent(
        table: JTable?, value: Any?,
        isSelected: Boolean,
        row: Int, column: Int
    ): Component? {
        table as ObjectEditorTable

        val editor = UEditors.getEditorRaw(table.rowTypes[row].type) as UEditor<Any?>?

        if (editor != null) {
            return panel {
                with(editor) {
                    CreateEditor(project, table.rowTypes[row], value) { newValue ->
                        this@ValueCellRenderer.value = newValue
                    }
                }
            }
        }

        return panel {
            row {
                label("No editor found for type: ${table.rowTypes[row]}")
            }
        }
    }

    override fun getCellEditorValue(): Any? {
        return value
    }
}
