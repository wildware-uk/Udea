package dev.wildware.udea.editors

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.badlogic.gdx.math.Vector2
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import dev.wildware.udea.assets.UClass
import dev.wildware.udea.compose.SelectBox
import dev.wildware.udea.findClassesOfType
import dev.wildware.udea.qualifiedNameToTitle
import dev.wildware.udea.toJvmQualifiedName
import io.kanro.compose.jetbrains.expui.control.Label
import io.kanro.compose.jetbrains.expui.control.OutlineButton
import io.kanro.compose.jetbrains.expui.control.TextField

object ListEditor : ComposeEditor<List<Any?>> {
    @Composable
    override fun CreateEditor(
        project: Project,
        type: EditorType<List<Any?>>,
        value: List<Any?>?,
        onValueChange: (List<Any?>) -> Unit
    ) {
        val listType = remember { type.generics.first() }
        var content by remember { mutableStateOf(value ?: emptyList<Any?>()) }
        val editor = remember { Editors.getEditorRaw(listType.type)!! as ComposeEditor<Any> }

        OutlineButton(onClick = {
            content += null
        }) {
            Label("Add Item")
        }

        LazyColumn(
            modifier = Modifier
                .height(300.dp)
        ) {
            items(content) { item ->
                Box(modifier = Modifier.padding(8.dp)) {
                    editor.CreateEditor(project, listType as EditorType<Any>, item) {
                        val index = content.indexOf(item)

                        if (index != -1) {
                            content = content.toMutableList().apply {
                                this[index] = it
                            }

                            onValueChange(content)
                        }
                    }
                }
            }
        }
    }
}

object IntEditor : ComposeEditor<Int> {
    @Composable
    override fun CreateEditor(project: Project, type: EditorType<Int>, value: Int?, onValueChange: (Int) -> Unit) {
        var text by remember { mutableStateOf(value?.toString() ?: "") }

        TextField(
            value = text,
            onValueChange = { newValue ->
                text = newValue
                newValue.toIntOrNull()?.let { onValueChange(it) }
            }
        )
    }
}

object StringEditor : ComposeEditor<String> {
    @Composable
    override fun CreateEditor(
        project: Project,
        type: EditorType<String>,
        value: String?,
        onValueChange: (String) -> Unit
    ) {
        var text by remember { mutableStateOf(value ?: "") }

        TextField(
            value = text,
            onValueChange = {
                text = it
                onValueChange(it)
            }
        )
    }
}

object FloatEditor : ComposeEditor<Float> {
    @Composable
    override fun CreateEditor(
        project: Project,
        type: EditorType<Float>,
        value: Float?,
        onValueChange: (Float) -> Unit
    ) {
        var text by remember { mutableStateOf(value?.toString() ?: "") }

        TextField(
            value = text,
            onValueChange = { newValue ->
                text = newValue
                newValue.toFloatOrNull()?.let { onValueChange(it) }
            }
        )
    }
}

object BooleanEditor : ComposeEditor<Boolean> {
    @Composable
    override fun CreateEditor(
        project: Project,
        type: EditorType<Boolean>,
        value: Boolean?,
        onValueChange: (Boolean) -> Unit
    ) {
        var text by remember { mutableStateOf(value?.toString() ?: "false") }

        TextField(
            value = text,
            onValueChange = { newValue ->
                text = newValue
                newValue.toBooleanStrictOrNull()?.let { onValueChange(it) }
            }
        )
    }
}

object Vector2Editor : ComposeEditor<Vector2> {
    @Composable
    override fun CreateEditor(
        project: Project,
        type: EditorType<Vector2>,
        value: Vector2?,
        onValueChange: (Vector2) -> Unit
    ) {
        var x by remember { mutableStateOf(value?.x.toString()) }
        var y by remember { mutableStateOf(value?.y.toString()) }

        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Label("X:", modifier = Modifier.width(30.dp))
                TextField(
                    value = x,
                    onValueChange = { newValue ->
                        x = newValue
                        val newX = newValue.toFloatOrNull()
                        val newY = y.toFloatOrNull()
                        if (newX != null && newY != null) {
                            onValueChange(Vector2(newX, newY))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Label("Y:", modifier = Modifier.width(30.dp))
                TextField(
                    value = y,
                    onValueChange = { newValue ->
                        y = newValue
                        val newX = x.toFloatOrNull()
                        val newY = newValue.toFloatOrNull()
                        if (newX != null && newY != null) {
                            onValueChange(Vector2(newX, newY))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

object UClassEditor : ComposeEditor<UClass<*>> {
    @Composable
    override fun CreateEditor(
        project: Project,
        type: EditorType<UClass<*>>,
        value: UClass<*>?,
        onValueChange: (UClass<*>) -> Unit
    ) {
        var subclasses by remember { mutableStateOf(emptyList<UClass<*>>()) }

        LaunchedEffect(Unit) {
            readAction {
                subclasses =
                    findClassesOfType(project, type.generics.first().type.qualifiedName!!)
                        .map { UClass<Any>(it.toJvmQualifiedName()) }
            }
        }

        var selectedClass by remember { mutableStateOf(value) }

        var open by remember { mutableStateOf(false) }

        SelectBox(
            subclasses, selectedClass, open,
            onOpenChange = { open = it },
            onSelectChange = {
                selectedClass = it
                onValueChange(it)
            },
            itemContent = {
                Label(it.className.qualifiedNameToTitle())
            }
        )
    }
}
