package dev.wildware.udea.editors

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClassType
import dev.wildware.udea.findClassByName
import dev.wildware.udea.pooled
import dev.wildware.udea.toEditorType
import io.kanro.compose.jetbrains.expui.control.Label
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

object ReflectionEditor : ComposeEditor<Any> {
    @Composable
    override fun CreateEditor(
        project: Project,
        type: EditorType<Any>,
        value: Any?,
        onValueChange: (Any) -> Unit
    ) {
        if (type.type.isSealed) {
            var concreteClass by remember { mutableStateOf(value?.let { it::class }) }
            val subclasses = type.type.sealedSubclasses

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

                concreteClass?.let { ConcreteEditor(project, EditorType(it), value, onValueChange) }
            }
        } else {
            ConcreteEditor(project, type, value, onValueChange)
        }
    }

    @Composable
    private fun ConcreteEditor(
        project: Project,
        type: EditorType<Any>,
        value: Any?,
        onValueChange: (Any) -> Unit
    ) {
        val constructor = remember {
            type.type.primaryConstructor ?: error("No primary constructor found for ${type.type}")
        }

        var constructorPsiMap by remember {
            mutableStateOf<Map<KParameter, PsiClassType>>(emptyMap())
        }

        remember {
            pooled {
                constructorPsiMap = calculateConstructParams(project, type.type)
            }
        }

        if (constructor.parameters.isEmpty()) {
            Label("Nothing to edit :)")
            return
        }

        val currentValues = remember {
            constructor.parameters.associateWith { param ->
                value?.let {
                    type.type.memberProperties.first { prop ->
                        prop.name == param.name
                    }.call(value)
                }
            }.toMutableMap()
        }

        Column {
            constructorPsiMap.forEach { (parameter, psiClass) ->
                val parameterClass = parameter.type.classifier as? KClass<*>
                    ?: error("Unsupported parameter type: ${parameter.type}")

                val editor = Editors.getEditorRaw(parameterClass) as? ComposeEditor<Any>
                    ?: error("No editor found for type: $parameterClass")

                Row {
                    Label(parameter.name ?: "Field")
                    Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                    editor.CreateEditor(project, psiClass.toEditorType(), currentValues[parameter]) { newValue ->
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

    private fun calculateConstructParams(
        project: Project,
        kClass: KClass<*>
    ): Map<KParameter, PsiClassType> {
        return ApplicationManager.getApplication().runReadAction<Map<KParameter, PsiClassType>> {
            val psiClass = findClassByName(project, kClass.qualifiedName!!)
                ?: error("Class not found: ${kClass.qualifiedName}")

            val parameters = kClass.primaryConstructor?.parameters
                ?: error("No primary constructor found for $kClass")

            val psiParams = psiClass.constructors.firstOrNull()?.parameterList?.parameters
                ?.map { it.type as PsiClassType }!!

            parameters.zip(psiParams).toMap()
        }
    }
}
