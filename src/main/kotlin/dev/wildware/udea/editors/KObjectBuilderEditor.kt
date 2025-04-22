package dev.wildware.udea.editors

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import dev.wildware.udea.*
import io.kanro.compose.jetbrains.expui.control.Label
import io.kanro.compose.jetbrains.expui.control.PrimaryButton
import io.kanro.compose.jetbrains.expui.control.TextField

@Composable
fun KBuilder.CreateEditor(project: Project) {
    when (this) {
        is KObjectBuilder -> CreateEditor(project)
        is KValueBuilder -> CreateEditor(project)
        is KListBuilder -> CreateEditor(project)
    }
}

@Composable
fun KObjectBuilder.CreateEditor(project: Project) {
    Box(modifier = Modifier.padding(8.dp)) {
        Column {
            Label("$type Editor")

            fields.forEach { field ->
                Label("${field.name} (${field.value.type})")
                field.value.CreateEditor(project)
            }
        }
    }
}

@Composable
fun KValueBuilder.CreateEditor(project: Project) {
    TextField(type, onValueChange = {})
}

@Composable
fun KListBuilder.CreateEditor(project: Project) {
    Column {
        Row {
            PrimaryButton(onClick = {
                val clasz = findClassByName(project, type)!!
                    .toType(project)
                elements.add(fromPsiType(project, clasz))
            }) {
                Label("Add Element")
            }
        }

        elements.forEach { element ->
            element.CreateEditor(project)
        }
    }
}
