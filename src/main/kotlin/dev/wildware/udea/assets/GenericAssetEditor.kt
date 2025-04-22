package dev.wildware.udea.assets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.openapi.command.WriteCommandAction
import dev.wildware.udea.Json
import io.kanro.compose.jetbrains.expui.control.Checkbox
import io.kanro.compose.jetbrains.expui.control.Label
import io.kanro.compose.jetbrains.expui.control.TextField
import kotlin.reflect.KClass

object GenericAssetEditor : AssetEditor {

    @Composable
    override fun AssetFileEditor.CreateAssetEditor() {
        NewObjectBuilder(assetValueClass, currentState, onValuesChange = {
            modified = true
            val constructor = assetValueClass.constructors.first()
            val newInstance = constructor.call(*it)
            val newAssetInstance = Asset(assetId, newInstance, assetType)

            WriteCommandAction.runWriteCommandAction(project) {
                document.setText(Json.toJson(newAssetInstance))
                modified = false
            }
        })
    }

    @Composable
    fun NewObjectBuilder(
        kClass: KClass<*>,
        currentValues: Array<Any?>,
        onValuesChange: (Array<Any?>) -> Unit = {}
    ) {
        val constructor = remember { kClass.constructors.first() }
        val parameters = remember { constructor.parameters }

        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            parameters.forEachIndexed { index, parameter ->
                when (parameter.type.classifier as KClass<*>) {
                    String::class -> StringBuilder.buildAsset(parameter.name!!, currentValues[index], index) {
                        currentValues[index] = it
                        onValuesChange(currentValues)
                    }

                    Boolean::class -> BooleanBuilder.buildAsset(parameter.name!!, currentValues[index], index) {
                        currentValues[index] = it
                        onValuesChange(currentValues)
                    }

                    Float::class -> FloatBuilder.buildAsset(parameter.name!!, currentValues[index], index) {
                        currentValues[index] = it
                        onValuesChange(currentValues)
                    }

                    Int::class -> IntBuilder.buildAsset(parameter.name!!, currentValues[index], index) {
                        currentValues[index] = it
                        onValuesChange(currentValues)
                    }
                    //                Asset::class -> AssetBuilder.buildAsset(parameter.name!!, values[index], index) { onChange(index, it) }
                    else -> {
                        Label("OTHER ${parameter.type}")
                    }
                }
            }
        }
    }

    interface ObjectBuilderUI<T> {
        @Composable
        fun buildAsset(name: String, value: Any?, index: Int, onChange: (Any?) -> Unit)
    }

    object StringBuilder : ObjectBuilderUI<String> {
        @Composable
        override fun buildAsset(name: String, value: Any?, index: Int, onChange: (Any?) -> Unit) {
            var currentValue by remember { mutableStateOf((value as? String) ?: "") }

            Row {
                Label(name)

                TextField(
                    currentValue,
                    onValueChange = {
                        currentValue = it
                        onChange(it)
                    })
            }
        }
    }

    object BooleanBuilder : ObjectBuilderUI<Boolean> {
        @Composable
        override fun buildAsset(name: String, value: Any?, index: Int, onChange: (Any?) -> Unit) {
            var currentValue by remember { mutableStateOf((value as? Boolean) ?: false) }

            Row {
                Label(name)

                Checkbox(currentValue, onCheckedChange = {
                    currentValue = it
                    onChange(it)
                })
            }
        }
    }

    object FloatBuilder : ObjectBuilderUI<Float> {
        @Composable
        override fun buildAsset(name: String, value: Any?, index: Int, onChange: (Any?) -> Unit) {
            var currentValue by remember { mutableStateOf((value as? Float)?.toString() ?: "0.0") }

            Row {
                Label(name)

                TextField(
                    currentValue,
                    onValueChange = {
                        currentValue = it
                        try {
                            onChange(it.toFloat())
                        } catch (e: NumberFormatException) {
                            // Handle invalid input
                        }
                    })
            }
        }
    }

    object IntBuilder : ObjectBuilderUI<Int> {
        @Composable
        override fun buildAsset(name: String, value: Any?, index: Int, onChange: (Any?) -> Unit) {
            var currentValue by remember { mutableStateOf((value as? Int)?.toString() ?: "0") }

            Row {
                Label(name)

                TextField(
                    currentValue,
                    onValueChange = {
                        currentValue = it
                        try {
                            onChange(it.toInt())
                        } catch (e: NumberFormatException) {
                            // Handle invalid input
                        }
                    })
            }
        }
    }

}