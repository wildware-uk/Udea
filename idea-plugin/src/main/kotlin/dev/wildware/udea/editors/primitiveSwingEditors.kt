package dev.wildware.udea.editors

import com.badlogic.gdx.math.Vector2
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.selected
import dev.wildware.udea.assets.Asset
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.assets.UClass
import dev.wildware.udea.findClassesOfType
import dev.wildware.udea.toJvmQualifiedName
import javax.swing.DefaultComboBoxModel
import javax.swing.JTextField
import javax.swing.ListCellRenderer
import kotlin.concurrent.thread
import kotlin.reflect.KClass

object ListEditorSwing : UEditor<List<Any?>> {
    override fun Panel.CreateEditor(
        project: Project,
        type: EditorType<*>,
        value: List<Any?>?,
        onValueChange: (List<Any?>) -> Unit
    ) {
        val listType = type.generics.first()
        val content = (value as? List<out Any?>)?.toMutableList() ?: mutableListOf()

        row {
            button("Add Item") {
                content.add(null)
                onValueChange(content.toList())
            }
        }

        panel {
            row {
                // Simple list display for now
                // In a real implementation, we would need to create a Swing editor for each item
                val listItems = content.mapIndexed { index, item -> "Item $index: ${item.toString()}" }
                label(listItems.joinToString("\n"))
            }
        }
    }
}

object IntEditorSwing : UEditor<Int> {
    override fun Panel.CreateEditor(
        project: Project,
        type: EditorType<*>,
        value: Int?,
        onValueChange: (Int) -> Unit
    ) {
        row {
            val initialValue = (value as? Int)?.toString() ?: ""
            val field = JTextField(initialValue)
            cell(field)
                .onChanged {
                    try {
                        val intValue = Integer.parseInt(field.text)
                        onValueChange(intValue)
                    } catch (e: NumberFormatException) {
                        // Invalid input, do nothing
                    }
                }
        }
    }
}

object StringEditorSwing : UEditor<String> {
    override fun Panel.CreateEditor(
        project: Project,
        type: EditorType<*>,
        value: String?,
        onValueChange: (String) -> Unit
    ) {
        row {
            val initialValue = value as? String ?: ""
            val field = JTextField(initialValue)
            cell(field)
                .onChanged {
                    onValueChange(field.text)
                }
        }
    }
}

object FloatEditorSwing : UEditor<Float> {
    override fun Panel.CreateEditor(
        project: Project,
        type: EditorType<*>,
        value: Float?,
        onValueChange: (Float) -> Unit
    ) {
        row {
            val initialValue = (value as? Float)?.toString() ?: ""
            val field = JTextField(initialValue)
            cell(field)
                .onChanged {
                    try {
                        val floatValue = java.lang.Float.parseFloat(field.text)
                        onValueChange(floatValue)
                    } catch (e: NumberFormatException) {
                        // Invalid input, do nothing
                    }
                }
        }
    }
}

object BooleanEditorSwing : UEditor<Boolean> {
    override fun Panel.CreateEditor(
        project: Project,
        type: EditorType<*>,
        value: Boolean?,
        onValueChange: (Boolean) -> Unit
    ) {
        row {
            checkBox("")
                .selected(value as? Boolean ?: false)
                .onChanged { selected ->
                    onValueChange(selected.isSelected)
                }
        }
    }
}

object Vector2EditorSwing : UEditor<Vector2> {
    override fun Panel.CreateEditor(
        project: Project,
        type: EditorType<*>,
        value: Vector2?,
        onValueChange: (Vector2) -> Unit
    ) {
        val vector = value as? Vector2 ?: Vector2(0f, 0f)
        val initialX = vector.x.toString()
        val initialY = vector.y.toString()

        // We need to store the current values to use them when updating
        val xField = JTextField(initialX)
        val yField = JTextField(initialY)

        row {
            label("X:")
            cell(xField)
                .onChanged {
                    try {
                        val xValue = java.lang.Float.parseFloat(xField.text)
                        val yValue = java.lang.Float.parseFloat(yField.text)
                        onValueChange(Vector2(xValue, yValue))
                    } catch (e: NumberFormatException) {
                        // Invalid input, do nothing
                    }
                }
        }

        row {
            label("Y:")
            cell(yField)
                .onChanged {
                    try {
                        val xValue = java.lang.Float.parseFloat(xField.text)
                        val yValue = java.lang.Float.parseFloat(yField.text)
                        onValueChange(Vector2(xValue, yValue))
                    } catch (e: NumberFormatException) {
                        // Invalid input, do nothing
                    }
                }
        }
    }
}

object UClassEditorSwing : UEditor<UClass<Any>> {
    override fun Panel.CreateEditor(
        project: Project,
        type: EditorType<*>,
        value: UClass<Any>?,
        onValueChange: (UClass<Any>) -> Unit
    ) {
        val comboBoxModel = DefaultComboBoxModel<UClass<*>>()

        row {
            comboBox(comboBoxModel)
                .onChanged { selectedItem ->
                    onValueChange(selectedItem.item)
                }
        }

        // Load subclasses in a background thread
        thread {
            val subclasses = ApplicationManager.getApplication().runReadAction<List<UClass<*>>> {
                findClassesOfType(project, type.generics.first().type.qualifiedName!!)
                    .map { UClass<Any>(it.toJvmQualifiedName()) }
            }

            // Update the combo box model on the EDT
            javax.swing.SwingUtilities.invokeLater {
                comboBoxModel.removeAllElements()
                subclasses.forEach { comboBoxModel.addElement(it) }

                // Select the current value if it exists
                if (value != null) {
                    comboBoxModel.selectedItem = value
                }
            }
        }
    }
}

object EnumEditorSwing : UEditor<Enum<*>> {
    override fun Panel.CreateEditor(
        project: Project,
        type: EditorType<*>,
        value: Enum<*>?,
        onValueChange: (Enum<*>) -> Unit
    ) {
        row {
            comboBox(type.type.java.enumConstants.toList()).onChanged { box ->
                onValueChange(box.item as Enum<*>)
            }
        }
    }
}

object AssetReferenceSwingEditor : UEditor<AssetReference<out Asset>> {

    override fun Panel.CreateEditor(
        project: Project,
        type: EditorType<*>,
        value: AssetReference<out Asset>?,
        onValueChange: (AssetReference<out Asset>) -> Unit
    ) {
        val assets: Collection<Asset> = Assets.filterIsInstance(type.generics.first().type as KClass<Asset>)

        row {
            comboBox(assets, ListCellRenderer<Asset?> { list, value, index, isSelected, cellHasFocus ->
                JBLabel(value?.name ?: "Asset")
            }).onChanged { selectedItem ->
                onValueChange(AssetReference(selectedItem.item.path))
            }
        }
    }
}
