package dev.wildware.udea.editors

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import io.kanro.compose.jetbrains.expui.control.Label
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

object ReflectionEditor : ComposeEditor<Any> {
    @Composable
    override fun CreateEditor(
        project: Project,
        type: KClass<out Any>,
        value: Any?,
        onValueChange: (Any) -> Unit
    ) {
        if (type.isSealed) {
            var concreteClass by remember { mutableStateOf<KClass<out Any>?>(null) }
            val subclasses = type.sealedSubclasses

            Column {
                subclasses.forEach { subclass ->
                    Row(
                        modifier = Modifier.padding(8.dp)
                            .clickable {
                                concreteClass = subclass
                            }
                    ) {
                        Label(subclass.simpleName ?: "Unknown")
                    }
                }

                concreteClass?.let { ConcreteEditor(project, it, value, onValueChange) }
            }
        } else {
            ConcreteEditor(project, type, value, onValueChange)
        }
    }

    @Composable
    private fun ConcreteEditor(
        project: Project,
        type: KClass<out Any>,
        value: Any?,
        onValueChange: (Any) -> Unit
    ) {
        val constructor = remember {
            type.primaryConstructor ?: error("No primary constructor found for $type")
        }

        val currentValues = remember {
            constructor.parameters.associateWith { param ->
                value?.let {
                    type.memberProperties.first { prop ->
                        prop.name == param.name
                    }.call(value)
                }
            }.toMutableMap()
        }

        Column {
            constructor.parameters.forEach { parameter ->
                val parameterClass = parameter.type.classifier as? KClass<*>
                    ?: error("Unsupported parameter type: ${parameter.type}")

                val editor = Editors.getEditorRaw(parameterClass) as? ComposeEditor<Any>
                    ?: error("No editor found for type: $parameterClass")

                Row {
                    Label(parameter.name ?: "Field")
                    Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                    editor.CreateEditor(project, parameterClass, currentValues[parameter]) { newValue ->
                        currentValues[parameter] = newValue

                        try {
                            val newInstance = constructor.callBy(
                                currentValues
                            )

                            onValueChange(newInstance)
                        } catch (e: Exception) {
                            println("Error creating instance of $type: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }
}
