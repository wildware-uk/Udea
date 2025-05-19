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
import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.Snapshot
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBList
import dev.wildware.LevelEditorCanvas
import dev.wildware.udea.*
import dev.wildware.udea.assets.Level
import dev.wildware.udea.ecs.component.UdeaComponentType
import io.kanro.compose.jetbrains.expui.control.Label
import io.kanro.compose.jetbrains.expui.control.PrimaryButton
import kotlinx.serialization.Contextual
import org.jetbrains.compose.splitpane.ExperimentalSplitPaneApi
import org.jetbrains.compose.splitpane.HorizontalSplitPane
import org.jetbrains.compose.splitpane.rememberSplitPaneState
import javax.swing.DefaultListCellRenderer
import javax.swing.ListSelectionModel
import kotlin.reflect.KClass
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.createInstance

object LevelEditor : ComposeEditor<Level> {
    @OptIn(ExperimentalSplitPaneApi::class)
    @Composable
    override fun CreateEditor(
        project: Project,
        type: EditorType<Level>,
        value: Level?,
        onValueChange: (Level) -> Unit
    ) {
        val levelEditorCanvas = remember { LevelEditorCanvas(value!!) }
        val splitPaneState = rememberSplitPaneState(0.8f)
        var systems by remember { mutableStateOf(value?.systems ?: emptyList()) }
        var entities by remember { mutableStateOf(value?.entities ?: emptyList()) }
        var selectedEntity by remember { mutableStateOf<Snapshot?>(null) }

        HorizontalSplitPane(
            splitPaneState = splitPaneState
        ) {
            first {
                SwingPanel(
                    modifier = Modifier.fillMaxSize(),
                    factory = { levelEditorCanvas.getCanvas() }
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
                            onEntitySelected = { selectedEntity = it },
                            onEntityAdded = {
                                val newEntity = Snapshot(emptyList(), emptyList())
                                entities = entities + newEntity
                                selectedEntity = newEntity
                                value?.let { onValueChange(it.copy(entities = entities)) }
                            })
                    }

                    Box {
                        selectedEntity?.let { selected ->
                            EntityEditor(
                                project,
                                selected,
                                onEntityUpdated = {
                                    onValueChange(value!!.copy(entities = (entities - selected) + it))
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
        entities: List<Snapshot>,
        selected: Snapshot?,
        onEntitySelected: (Snapshot) -> Unit,
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
                        Label("Entity")
                    }
                }
            }
        }
    }

    @Composable
    private fun EntityEditor(
        project: Project,
        entity: Snapshot,
        onEntityUpdated: (Snapshot) -> Unit
    ) {
        Column {
            PrimaryButton(onClick = {
                showComponentTypeMenu(project) { componentType ->
                    val newComponent = componentType.createInstance()

                    val dependencies = if (componentType.companionObjectInstance is UdeaComponentType<*>) {
                        (componentType.companionObjectInstance as UdeaComponentType<*>).dependsOn.dependencies.map {
                            it::class.java.enclosingClass.kotlin.createInstance()
                        }.toTypedArray()
                    } else emptyArray()

                    onEntityUpdated(entity.copy(components = (entity.components + dependencies + newComponent) as List<Component<out @Contextual Any>>))
                }
            }) {
                Label("Add Component")
            }

            Label("Components")

            LazyColumn {
                items(entity.components) { component ->
                    Column(
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Label(component::class.simpleName!!.qualifiedNameToTitle())

                        Editors.getEditor(Any::class)!!
                            .CreateEditor(
                                project,
                                EditorType(component::class), component,
                                onValueChange = {
                                    val updatedEntity = (entity.components - component) + (it as Component<out Any>)
                                    onEntityUpdated(entity.copy(components = updatedEntity))
                                })
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
