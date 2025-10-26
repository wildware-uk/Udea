package dev.wildware.udea.editors.builder.editors.assets

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import dev.wildware.GameEditorCanvas
import dev.wildware.systems.EditorSystem
import dev.wildware.udea.assets.EntityDefinition
import dev.wildware.udea.assets.Level
import dev.wildware.udea.editors.EditorAssetLoader
import dev.wildware.udea.editors.builder.UListBuilder
import dev.wildware.udea.editors.builder.UObjectBuilder
import dev.wildware.udea.editors.builder.UValueBuilder
import dev.wildware.udea.editors.builder.editors.UObjectEditor
import dev.wildware.udea.editors.builder.editors.UdeaEditor
import dev.wildware.udea.editors.builder.toUBuilder
import net.miginfocom.swing.MigLayout
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel.SINGLE_SELECTION
import javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
import javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED

@UdeaEditor(Level::class)
class ULevelEditor(
    project: Project,
    builder: UObjectBuilder,
    onSave: () -> Unit = {}
) : UObjectEditor(project, builder, onSave) {

    var nextEntityId = 0L

    val entityList = JBList<UObjectBuilder>().apply {
        cellRenderer = ListCellRenderer { list, value, index, isSelected, cellHasFocus ->
            val entityDefinition = value as UObjectBuilder

            JPanel(MigLayout("ins 0, fill")).apply {
                isOpaque = true

                if (isSelected) {
                    background = list.selectionBackground
                    foreground = list.selectionForeground
                } else {
                    background = list.background
                    foreground = list.foreground
                }

                add(JBLabel(entityDefinition.get<UValueBuilder>("name").value as String), "push")
                add(JButton("", AllIcons.Actions.GC).apply {
                    addActionListener {
                        val result = JBPopupFactory.getInstance()
                            .createConfirmation(
                                "Delete Entity",
                                "Are you sure you want to delete this entity?",
                                "Other",
                                {
                                    TODO("DELETE ENTITY DEFINITION")
                                },
                                { },
                                0
                            )
                        result.showInFocusCenter()
                    }
                }, "al right")
            }
        }

        selectionMode = SINGLE_SELECTION

        addListSelectionListener {
            if (selectedValue != null) {
                entityDefinitionEditor =
                    UEntityDefinitionEditor(project, selectedValue as UObjectBuilder, this@ULevelEditor.onSave)
                entityDefinitionPanel.removeAll()
                entityDefinitionPanel.add(entityDefinitionEditor.createEditor())
                entityDefinitionPanel.revalidate()
                entityDefinitionPanel.repaint()
            }
        }
    }

    val editorAssetLoader = EditorAssetLoader(project)
    val gameEditorCanvas = GameEditorCanvas(editorAssetLoader)

    override val onSave = {
        if (reloadAll()) {
            onSave()
        }
    }

    val entityDefinitionPanel = JPanel(BorderLayout())
    lateinit var entityDefinitionEditor: UEntityDefinitionEditor

    override fun createEditor(): JComponent {
        reloadAll()

        return Splitter(false).apply {
            dividerWidth = 20
            proportion = 0.7f

            firstComponent = JPanel(BorderLayout()).apply {
                add(gameEditorCanvas.getCanvas(), BorderLayout.CENTER)
            }

            secondComponent = ScrollPaneFactory.createScrollPane(
                JPanel(MigLayout()).apply {
                    add(JBLabel("Entities"), "wrap, width 100%, gapbottom 5")
                    add(JButton("Add Entity").apply {
                        addActionListener {
                            val newEntity = EntityDefinition(nextEntityId++)
                                .toUBuilder(project)
                            builder.get<UListBuilder>("entities").children.add(newEntity)

                            reloadAll()
                            onSave()
                        }
                    }, "wrap")
                    add(ScrollPaneFactory.createScrollPane(entityList), "wrap, width 100%, gapbottom 5")
                    add(entityDefinitionPanel, "wrap, width 100%, gapbottom 5")
                },
                VERTICAL_SCROLLBAR_AS_NEEDED,
                HORIZONTAL_SCROLLBAR_NEVER, true
            )
        }
    }

    fun reloadAll(): Boolean {
        reloadEntityList(builder.get<UListBuilder>("entities").children as List<UObjectBuilder>)

        val level = (builder.build() as? Level)

        if (level != null) {
            gameEditorCanvas.gameManager.setLevel(
                level,
                extraSystems = listOf(EditorSystem::class.java)
            )
            return true
        }

        return false
    }

    private fun reloadEntityList(entities: List<UObjectBuilder>) {
        entityList.setListData(entities.toTypedArray())
    }
}
