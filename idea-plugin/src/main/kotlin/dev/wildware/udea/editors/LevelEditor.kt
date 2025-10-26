package dev.wildware.udea.editors

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color.Companion.Black
import androidx.compose.ui.graphics.Color.Companion.Gray
import androidx.compose.ui.unit.dp
import com.badlogic.gdx.assets.AssetManager
import com.badlogic.gdx.files.FileHandle
import com.badlogic.gdx.graphics.Texture
import com.github.quillraven.fleks.Component
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBList
import dev.wildware.GameEditorCanvas
import dev.wildware.systems.EditorSystem
import dev.wildware.udea.*
import dev.wildware.udea.assets.EntityDefinition
import dev.wildware.udea.assets.GameAssetManager
import dev.wildware.udea.assets.Level
import io.kanro.compose.jetbrains.expui.control.Label
import io.kanro.compose.jetbrains.expui.control.PrimaryButton
import org.jetbrains.compose.splitpane.ExperimentalSplitPaneApi
import org.jetbrains.compose.splitpane.HorizontalSplitPane
import org.jetbrains.compose.splitpane.rememberSplitPaneState
import javax.swing.DefaultListCellRenderer
import javax.swing.ListSelectionModel
import kotlin.reflect.KClass

class EditorAssetLoader(
    val project: Project
) : AssetLoader {
    override fun load(manager: AssetManager) {
        project.service<GameAssetManager>().assetFiles.forEach {
            if (it.extension == "png") {
                val assetPath = it.path.substringAfter("assets/")
                println("Adding texture $assetPath")
//                manager.load(assetPath)
            }
        }
    }

    override fun resolve(fileName: String): FileHandle? {
        val assetsDir = project.baseDir.findChild("assets") ?: return null
        val assetFile = assetsDir.findFileByRelativePath("/$fileName") ?: return null
        return FileHandle(assetFile.path)
    }
}

object LevelEditor : ComposeEditor<Level> {
    @OptIn(ExperimentalSplitPaneApi::class)
    @Composable
    override fun CreateEditor(
        project: Project,
        type: EditorType<Level>,
        value: Level?,
        onValueChange: (Level) -> Unit
    ) {
        requireNotNull(value) { "Level must not be null" }

        val editorAssetLoader = remember { EditorAssetLoader(project) }
        val gameEditorCanvas = remember { GameEditorCanvas(editorAssetLoader) }
        val splitPaneState = rememberSplitPaneState(0.8f)
        var selectedEntity by remember { mutableStateOf<EntityDefinition?>(null) }
        var entities by remember { mutableStateOf(value.entities) }

        LaunchedEffect(value) {
            gameEditorCanvas.gameManager.setLevel(value, listOf(EditorSystem::class.java))
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
                    Box(
                        modifier = Modifier.height(300.dp)
                    ) {
                        EntityList(
                            project, entities,
                            selectedEntity,
                            onEntitySelected = { println("selected ${it.id}"); selectedEntity = it },
                            onEntityAdded = {
                                val newEntity = EntityDefinition(value.nextEntityId())
                                selectedEntity = newEntity
                                val newLevel = value.copy(entities = entities + newEntity)
//                                    gameEditorCanvas.gameManager.setLevel(newLevel, listOf(EditorSystem::class.java), true)
                                onValueChange(newLevel)
                            })
                    }

                    Box {
                        if (selectedEntity != null) {
                            println("SELECTED ${selectedEntity?.id}")
                            EntityDefinitionEditor.CreateEditor(
                                project,
                                EditorType(selectedEntity!!::class),
                                selectedEntity,
                                onValueChange = {
                                    val newValue =
                                        value.copy(entities = entities.filter { it.id != selectedEntity!!.id } + it)
                                    onValueChange(newValue)
                                    selectedEntity = it
                                })
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EntityList(
        project: Project,
        entities: List<EntityDefinition>,
        selected: EntityDefinition?,
        onEntitySelected: (EntityDefinition) -> Unit,
        onEntityAdded: () -> Unit
    ) {
        Column {
            Row {
                PrimaryButton(onClick = {
                    onEntityAdded()
                }) {
                    Label("Add Entity")
                }
            }

            LazyColumn {
                items(entities) { entity ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (entity === selected) Black else Gray)
                            .padding(8.dp)
                            .clickable {
                                onEntitySelected(entity)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Label(entity.name)
                    }
                }
            }
        }
    }

    fun showComponentTypeMenu(project: Project, onComponentSelected: (KClass<Component<*>>) -> Unit) {
        // Retrieve the list of component types
        val componentTypes = pooledResult {
            findClassesOfType(project, Component::class.java.name)
                .map { project.service<ProjectClassLoaderManager>().classLoader.loadClass(it.toJvmQualifiedName()).kotlin }
        }

        // Create a JBList to display the component types
        val list = JBList(componentTypes).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = DefaultListCellRenderer().apply {
                text = "Loading..."
            }
        }

        // Create a popup and show it
        val popup = JBPopupFactory.getInstance()
            .createListPopupBuilder(list)
            .setTitle("Select Component Type")
            .setItemChosenCallback { selectedValue ->
                @Suppress("UNCHECKED_CAST")
                onComponentSelected(selectedValue as KClass<Component<*>>)
            }
            .createPopup()

        // Show the popup
        popup.showInFocusCenter()
    }
}
