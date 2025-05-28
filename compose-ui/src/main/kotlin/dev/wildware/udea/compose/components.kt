package dev.wildware.udea.compose

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color.Companion.Black
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.unit.dp
import io.kanro.compose.jetbrains.expui.control.Label

@Composable
fun <T> SelectBox(
    values: List<T>,
    selected: T? = null,
    open: Boolean = false,
    onOpenChange: (Boolean) -> Unit = {},
    onSelectChange: (T) -> Unit = {},
    itemContent: @Composable (T) -> Unit = { Label(it.toString()) }
) {
    Column {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp)) // Apply rounded corners
                .border(1.dp, White, RoundedCornerShape(8.dp))
                .padding(8.dp, 4.dp)
                .clickable { onOpenChange(!open) }  // Add clickable to toggle
        ) {
            Label("v  ")
            selected?.let {
                itemContent(it)
            } ?: run {
                Label("Select an option")
            }
        }

        if (open) {
            Box(
                Modifier
                    .absoluteOffset(0.dp, (-2).dp)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .border(1.dp, Black)
                        .padding(4.dp)           // Add padding
                ) {
                    values.forEach { item ->
                        Row(
                            modifier = Modifier
                                .clickable {
                                    onSelectChange(item)
                                    onOpenChange(false)
                                }
                                .padding(8.dp)
                        ) {
                            itemContent(item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
@Preview
fun PreviewSelectBox() {
    var isOpen by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<String?>("Hello") }

    SelectBox(
        values = listOf("Hello", "World", "123"),
        selected = selected,
        open = isOpen,
        onOpenChange = { isOpen = it },
        onSelectChange = { selected = it }
    )
}
