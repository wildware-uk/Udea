package dev.wildware.udea.editors

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.findDocument
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import dev.wildware.udea.Json
import dev.wildware.udea.ProjectClassLoaderManager
import dev.wildware.udea.assets.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.beans.PropertyChangeListener
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer
import kotlin.reflect.KClass

class AssetFileEditorProvider : FileEditorProvider {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file.extension == "udea"
    }

    override fun createEditor(
        project: Project,
        file: VirtualFile
    ): FileEditor {
        val classLoaderManager = ProjectClassLoaderManager.Companion.getInstance(project)
        val documentText = file.findDocument()!!.text
        val asset = Json
            .withClassLoader(classLoaderManager.classLoader)
            .fromJson<AssetFile>(documentText)
        return AssetFileEditor(project, file, asset)
    }

    override fun getEditorTypeId() = "AssetEditor"

    override fun getPolicy() = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}

class AssetFileEditor(
    val project: Project,
    private val file: VirtualFile,
    val assetFile: AssetFile,
) : FileEditor {

    val assetValueClass = ProjectClassLoaderManager
        .getInstance(project).classLoader.loadClass(assetFile.type).kotlin

    val currentState = MutableStateFlow(assetFile.asset)

    val document = file.findDocument()!!

    var modified = false

    // Custom table model for our data
    private val tableModel = object : DefaultTableModel() {
        // Map to store the type of each cell in the second column
        val cellTypes = mutableMapOf<Int, KClass<*>>()

        init {
            addColumn("Property")
            addColumn("Value")

            // Add some test rows with different types
            addRow(arrayOf("TestString", "Hello"))
            addRow(arrayOf("TestInt", 42))
            addRow(arrayOf("TestFloat", 3.14f))
            addRow(arrayOf("TestBoolean", true))
            addRow(arrayOf("TestBlueprint", null))

            // Set the types for the test rows
            cellTypes[0] = String::class
            cellTypes[1] = Int::class
            cellTypes[2] = Float::class
            cellTypes[3] = Boolean::class
            cellTypes[4] = AssetReference::class
        }

        override fun getColumnClass(columnIndex: Int): Class<*> {
            return when (columnIndex) {
                0 -> String::class.java
                1 -> Any::class.java // Use Any as the base class for all values
                else -> Any::class.java
            }
        }

        override fun isCellEditable(row: Int, column: Int): Boolean {
            return column == 1 // Only the value column is editable
        }

        // Get the type of a cell in the second column
        fun getCellType(row: Int): KClass<*> {
            return cellTypes[row] ?: Any::class
        }

        // Set the type of a cell in the second column
        fun setCellType(row: Int, type: KClass<*>) {
            cellTypes[row] = type
        }
    }

    // Generic cell renderer that uses the appropriate renderer based on the type
    private inner class GenericCellRenderer : TableCellRenderer {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            // Get the type of the cell
            val type = if (column == 1 && row >= 0 && row < tableModel.rowCount) {
                tableModel.getCellType(row)
            } else {
                value?.let { v -> v::class } ?: Any::class
            }

            // Create a simple text representation for the renderer
            val text = when {
                value is AssetReference<*> && type == AssetReference::class -> {
                    (value.value as? Blueprint)?.name ?: "None"
                }

                else -> value?.toString() ?: "null"
            }

            // Return a simple label for rendering
            return JButton(text).apply {
                isEnabled = false
                horizontalAlignment = javax.swing.SwingConstants.LEFT
            }
        }
    }

    // Custom cell editor for Blueprint dropdown
    private inner class BlueprintCellEditor : DefaultCellEditor(JComboBox<Blueprint>().apply {
        val blueprints = Assets.filterIsInstance<Blueprint>()
        blueprints.forEach { addItem(it) }
    }) {
        override fun getTableCellEditorComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            row: Int,
            column: Int
        ): Component {
            val comboBox = super.getTableCellEditorComponent(table, value, isSelected, row, column) as JComboBox<*>

            // Set the selected item if value is an AssetReference
            if (value is AssetReference<*>) {
                val blueprint = value.value as? Blueprint
                if (blueprint != null) {
                    comboBox.selectedItem = blueprint
                }
            }

            return comboBox
        }

        override fun getCellEditorValue(): Any {
            val blueprint = (component as JComboBox<*>).selectedItem as? Blueprint
            return if (blueprint != null) {
                AssetReference<Blueprint>(blueprint.path)
            } else {
                // Return an empty string instead of null
                ""
            }
        }
    }

    // Custom table class that overrides getCellEditor to choose the appropriate editor based on the type
    private inner class TypedTable(model: DefaultTableModel) : JBTable(model) {
        // Cache for cell editors to avoid creating new ones for each cell
        private val editorCache = mutableMapOf<KClass<*>, TableCellEditor>()

        override fun getCellEditor(row: Int, column: Int): TableCellEditor {
            // Only apply custom editors to the value column
            if (column == 1) {
                val type = tableModel.getCellType(row)

                // Return cached editor if available
                editorCache[type]?.let { return it }

                // Create and cache a new editor based on the type
                val editor = when (type) {
                    AssetReference::class -> BlueprintCellEditor()
                    else -> super.getCellEditor(row, column)
                }

                editorCache[type] = editor
                return editor
            }
            return super.getCellEditor(row, column)
        }
    }

    // Create the table
    private val table = TypedTable(tableModel).apply {
        setDefaultRenderer(Any::class.java, GenericCellRenderer())

        // Set column widths
        columnModel.getColumn(0).preferredWidth = 150
        columnModel.getColumn(1).preferredWidth = 200

        // Set row height
        rowHeight = 30

        // Add a listener to mark the editor as modified when the table is edited
        model.addTableModelListener {
            modified = true
        }
    }

    // Create a panel with the table and add button
    private val tablePanel = JPanel(BorderLayout()).apply {
        // Add the table in a scroll pane
        val scrollPane = JBScrollPane(table).apply {
            preferredSize = Dimension(400, 200)
        }
        add(scrollPane, BorderLayout.CENTER)

        // Add a button panel at the bottom
        val buttonPanel = JPanel().apply {
            val addButton = JButton("Add Row").apply {
                addActionListener {
                    tableModel.addRow(arrayOf("New Blueprint", ""))
                    modified = true
                }
            }
            add(addButton)

            val removeButton = JButton("Remove Row").apply {
                addActionListener {
                    val selectedRow = table.selectedRow
                    if (selectedRow != -1) {
                        tableModel.removeRow(selectedRow)
                        modified = true
                    }
                }
            }
            add(removeButton)
        }
        add(buttonPanel, BorderLayout.SOUTH)
    }

    private val component = panel {
        with(SwingReflectiveEditor) {
            CreateEditor(project, EditorType(assetValueClass), currentState.value) { newState ->
                runWriteAction {
                    document.setText(Json.toJson(assetFile.copy(asset = newState as Asset)))
                    modified = false
                }
            }
        }
    }

    override fun getComponent() = component
    override fun getPreferredFocusedComponent() = component

    override fun getName() = "Asset Editor"

    override fun setState(p0: FileEditorState) = Unit
    override fun isModified() = modified
    override fun isValid() = true
    override fun addPropertyChangeListener(p0: PropertyChangeListener) {
    }

    override fun removePropertyChangeListener(p0: PropertyChangeListener) {
    }

    override fun <T : Any?> getUserData(p0: Key<T?>): T? = null

    override fun <T : Any?> putUserData(p0: Key<T?>, p1: T?) {
    }

    override fun dispose() {
    }

    override fun getFile() = file
}
