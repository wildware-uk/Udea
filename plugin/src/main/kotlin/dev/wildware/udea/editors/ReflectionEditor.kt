package dev.wildware.udea.editors

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiType
import dev.wildware.udea.*
import dev.wildware.udea.compose.SelectBox
import io.kanro.compose.jetbrains.expui.control.Label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        if (type.type.isSealed || type.type.isAbstract) {
            var concreteClass by remember { mutableStateOf(value?.let { it::class }) }
            var subclasses by remember { mutableStateOf(emptyList<KClass<*>>()) }
            var open by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                readAction {
                    subclasses =
                        findClassesOfType(project, type.type.qualifiedName!!)
                            .map { project.service<ProjectClassLoaderManager>().classLoader.loadClass(it.toJvmQualifiedName()).kotlin }
                }
            }

            SelectBox(
                subclasses,
                concreteClass,
                open,
                onOpenChange = { open = it },
                onSelectChange = { selected ->
                    concreteClass = selected
                    onValueChange(selected)
                },
                itemContent = { Label(it.simpleName ?: "Unknown") }
            )

            concreteClass?.let { ConcreteEditor(project, EditorType(it), value, onValueChange) }
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
            mutableStateOf<Map<KParameter, PsiType>>(emptyMap())
        }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                constructorPsiMap = runReadAction { calculateConstructParams(project, type.type) }
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
    ): Map<KParameter, PsiType> {
        val psiClass = findClassByName(project, kClass.qualifiedName!!)
            ?: error("Class not found: ${kClass.qualifiedName}")

        val parameters = kClass.primaryConstructor?.parameters
            ?: error("No primary constructor found for $kClass")

        val psiParams = psiClass.constructors.firstOrNull()?.parameterList?.parameters
            ?.map { it.type }!!

        return parameters.zip(psiParams).toMap()
    }
}
