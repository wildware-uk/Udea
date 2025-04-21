package dev.wildware.udea.editors

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.wildware.udea.Asset
import io.kanro.compose.jetbrains.expui.control.*
import kotlin.reflect.KClass

typealias ObjectBuilder = MutableMap<String, Any>

@Composable
fun NewObjectBuilder(kClass: KClass<*>) {
    val constructor = remember { kClass.constructors.first() }
    val parameters = remember { constructor.parameters }

    Column {
        parameters.forEach { parameter ->
            when (val param = parameter.type.classifier as KClass<*>) {
                String::class -> StringBuilder.buildAsset(parameter.name!!) { /* handle string */ }
                Boolean::class -> BooleanBuilder.buildAsset(parameter.name!!) { /* handle boolean */ }
                Float::class -> FloatBuilder.buildAsset(parameter.name!!) { /* handle float */ }
                Int::class -> IntBuilder.buildAsset(parameter.name!!) { /* handle int */ }
//                Asset::class -> AssetBuilder.buildAsset { /* handle asset */ }
                else -> {
                    Label("OTHER ${parameter.type}")
                }
            }
        }
    }
}

interface ObjectBuilderUI<T> {
    @Composable
    fun buildAsset(name: String, outObject: (T) -> Unit)
}

object AssetBuilder : ObjectBuilderUI<Asset<*>> {
    @Composable
    override fun buildAsset(name: String, outObject: (Asset<*>) -> Unit) {
//        DropdownMenu(true, onDismissRequest = {}, content = {
//            DropdownMenuItem(onClick = {}, content = {
//                Label("test")
//            })
//        })
    }
}

object StringBuilder : ObjectBuilderUI<String> {
    @Composable
    override fun buildAsset(name: String, outObject: (String) -> Unit) {
        TextField(
            "test",
            onValueChange = {
                outObject(it)
            })
    }
}

object BooleanBuilder : ObjectBuilderUI<Boolean> {
    @Composable
    override fun buildAsset(name: String, outObject: (Boolean) -> Unit) {
        Checkbox(true, onCheckedChange = {
            outObject(it)
        })
    }
}

object FloatBuilder : ObjectBuilderUI<Float> {
    @Composable
    override fun buildAsset(name: String, outObject: (Float) -> Unit) {
        TextField(
            "25.00",
            onValueChange = {
                outObject(it.toFloat())
            })
    }
}

object IntBuilder : ObjectBuilderUI<Int> {
    @Composable
    override fun buildAsset(name: String, outObject: (Int) -> Unit) {
        TextField(
            "25",
            onValueChange = {
                outObject(it.toInt())
            })
    }
}
