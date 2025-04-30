package dev.wildware.udea.editors

import androidx.compose.runtime.Composable

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import dev.wildware.udea.math.Vector2
import io.kanro.compose.jetbrains.expui.control.Label
import io.kanro.compose.jetbrains.expui.control.TextField
import kotlin.reflect.KClass

object IntEditor : ComposeEditor<Int> {
    @Composable
    override fun CreateEditor(project: Project, type: KClass<out Int>, value: Int?, onValueChange: (Int) -> Unit) {
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
        type: KClass<out String>,
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
    override fun CreateEditor(project: Project, type: KClass<out Float>, value: Float?, onValueChange: (Float) -> Unit) {
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
        type: KClass<out Boolean>,
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
        type: KClass<out Vector2>,
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
