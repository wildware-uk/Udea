package dev.wildware.udea.editors.builder.editors.assets

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import dev.wildware.GameEditorCanvas
import dev.wildware.systems.EditorSystem
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.assets.EntityDefinition
import dev.wildware.udea.assets.Level
import dev.wildware.udea.editors.EditorAssetLoader
import dev.wildware.udea.editors.builder.UObjectBuilder
import dev.wildware.udea.editors.builder.editors.UObjectEditor
import dev.wildware.udea.editors.builder.editors.UdeaEditor
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

@UdeaEditor(Blueprint::class)
class UBlueprintEditor(
    project: Project,
    builder: UObjectBuilder,
    onSave: () -> Unit = {}
) : UObjectEditor(project, builder, onSave) {

    val editorAssetLoader = EditorAssetLoader(project)
    val gameEditorCanvas = GameEditorCanvas(editorAssetLoader)

    override val onSave = {
        if (reloadAll()) {
            onSave()
        }
    }

    val entityDefinitionEditor = UEntityDefinitionEditor(project, builder, this.onSave)

    override fun createEditor(): JComponent {
        reloadAll()

        return Splitter(false).apply {
            dividerWidth = 20
            proportion = 0.7f
            firstComponent = JPanel(BorderLayout()).apply {
                add(gameEditorCanvas.getCanvas(), BorderLayout.CENTER)
            }
            secondComponent = entityDefinitionEditor.createEditor()
        }
    }

    fun reloadAll(): Boolean {
//        reloadComponentList((builder.children["components"] as UListBuilder).children as List<UObjectBuilder>)

        val blueprint = (builder.build() as? Blueprint)

        if (blueprint != null) {
            gameEditorCanvas.gameManager.setLevel(
                Level(
                    entities = listOf(
                        EntityDefinition(
                            id = 0,
                            components = blueprint.components,
                            tags = blueprint.tags,
                        )
                    )
                ),

                extraSystems = listOf(EditorSystem::class.java)
            )
            return true
        }

        return false
    }
}