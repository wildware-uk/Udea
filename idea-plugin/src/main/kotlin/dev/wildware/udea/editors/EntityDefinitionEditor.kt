package dev.wildware.udea.editors

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.quillraven.fleks.Component
import com.intellij.openapi.project.Project
import dev.wildware.udea.assets.EntityDefinition
import dev.wildware.udea.ecs.component.UdeaComponentType
import dev.wildware.udea.editors.LevelEditor.showComponentTypeMenu
import dev.wildware.udea.qualifiedNameToTitle
import io.kanro.compose.jetbrains.expui.control.Label
import io.kanro.compose.jetbrains.expui.control.PrimaryButton
import kotlinx.serialization.Contextual
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.createInstance

object EntityDefinitionEditor : ComposeEditor<EntityDefinition> {
    @Composable
    override fun CreateEditor(
        project: Project,
        type: EditorType<EntityDefinition>,
        value: EntityDefinition?,
        onValueChange: (EntityDefinition) -> Unit
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

                    onValueChange(value!!.copy(components = (value.components + dependencies + newComponent) as List<Component<out @Contextual Any>>))
                }
            }) {
                Label("Add Component")
            }

            Label("Components")

            println("Creating editors for ${value!!.components}")

            Label("${value?.id}")

            value!!.components.forEachIndexed { index, component ->
                Column(
                    modifier = Modifier.padding(4.dp)
                ) {
                    Label(component::class.simpleName!!.qualifiedNameToTitle())

                    Editors.getEditor(Any::class)!!
                        .CreateEditor(
                            project,
                            EditorType(component::class),
                            component,
                            onValueChange = { newValue ->
                                val updatedComponents = value.components.toMutableList()
                                updatedComponents[index] = newValue as Component<out Any>
                                onValueChange(value.copy(components = updatedComponents))
                            })
                }
            }

        }
    }
}