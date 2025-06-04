package dev.wildware.udea.editors.builder.editors

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Splitter
import com.intellij.ui.dsl.builder.Panel
import dev.wildware.GameEditorCanvas
import dev.wildware.systems.EditorSystem
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.assets.EntityDefinition
import dev.wildware.udea.assets.Level
import dev.wildware.udea.editors.EditorAssetLoader
import dev.wildware.udea.editors.builder.UListBuilder
import dev.wildware.udea.editors.builder.UObjectBuilder
import javax.swing.JPanel

@UdeaEditor(Blueprint::class)
class UBlueprintEditor : UEditor<UObjectBuilder> {
    override fun Panel.buildEditor(project: Project, builder: UObjectBuilder, onSave: () -> Unit) {

        val editorAssetLoader = EditorAssetLoader(project)
        val gameEditorCanvas = GameEditorCanvas(editorAssetLoader)

        fun loadLevel(): Boolean {
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

        val onSave = {
            if(loadLevel()) {
                onSave()
            }
        }

        loadLevel()

        row {
            cell(Splitter(false).apply {
                dividerWidth = 20
                proportion = 0.8f
                firstComponent = JPanel().apply { add(gameEditorCanvas.getCanvas()) }
                secondComponent = com.intellij.ui.dsl.builder.panel {
                    // Create a panel for the blueprint editor
                    row {
                        label("Blueprint Editor")
                    }

                    row {
                        scrollCell(com.intellij.ui.dsl.builder.panel {
                            group("Components") {
                                (builder.children["components"] as UListBuilder).children.forEach {
                                    with(UEditors.getEditor(it)) {
                                        panel {
                                            buildEditor(project, it, {
                                                onSave()
                                            })
                                        }
                                    }
                                }
                            }
                        })
                    }

                    // Tags section
                    group("Tags") {

                    }

                    // Parent blueprint reference
                    group("Parent Blueprint") {

                    }
                }
            })
        }
    }
}
