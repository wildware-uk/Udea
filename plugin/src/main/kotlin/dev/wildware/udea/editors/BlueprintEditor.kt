package dev.wildware.udea.editors

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import com.intellij.openapi.project.Project
import dev.wildware.GameEditorCanvas
import dev.wildware.systems.EditorSystem
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.assets.EntityDefinition
import dev.wildware.udea.assets.Level
import org.jetbrains.compose.splitpane.ExperimentalSplitPaneApi
import org.jetbrains.compose.splitpane.HorizontalSplitPane
import org.jetbrains.compose.splitpane.rememberSplitPaneState

object BlueprintEditor : ComposeEditor<Blueprint> {
    @OptIn(ExperimentalSplitPaneApi::class)
    @Composable
    override fun CreateEditor(
        project: Project,
        type: EditorType<Blueprint>,
        value: Blueprint?,
        onValueChange: (Blueprint) -> Unit
    ) {
        val editorAssetLoader = remember { EditorAssetLoader(project) }
        val gameEditorCanvas = remember { GameEditorCanvas(editorAssetLoader) }
        val splitPaneState = rememberSplitPaneState(0.8f)

        var entityDefinition by remember {
            mutableStateOf(
                EntityDefinition(
                    components = value!!.components,
                    tags = value.tags
                )
            )
        }

        LaunchedEffect(entityDefinition) {
            if (value != null) {
                gameEditorCanvas.gameManager.setLevel(
                    Level(
                        entities = listOf(
                            entityDefinition
                        )
                    ),

                    extraSystems = listOf(EditorSystem::class.java)
                )
            }
        }

        HorizontalSplitPane(
            splitPaneState = splitPaneState
        ) {
            first {
                SwingPanel(
                    modifier = Modifier.fillMaxSize(),
                    factory = { gameEditorCanvas.getCanvas() }
                )
            }
            second {
                Column {
                    Box {
                        EntityDefinitionEditor.CreateEditor(
                            project,
                            EditorType(EntityDefinition::class),
                            entityDefinition,
                            onValueChange = {
                                entityDefinition = it
                                onValueChange(
                                    value!!.copy(
                                        components = entityDefinition.components,
                                        tags = entityDefinition.tags,
                                        parent = entityDefinition.blueprint
                                    )
                                )
                            })
                    }
                }
            }
        }
    }
}
