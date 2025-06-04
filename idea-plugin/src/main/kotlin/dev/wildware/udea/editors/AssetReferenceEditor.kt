package dev.wildware.udea.editors

import androidx.compose.runtime.*
import com.intellij.openapi.project.Project
import dev.wildware.udea.assets.Asset
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.camelCaseToTitle
import dev.wildware.udea.compose.SelectBox
import io.kanro.compose.jetbrains.expui.control.Label
import kotlin.reflect.KClass

object AssetReferenceEditor : ComposeEditor<AssetReference<out Asset>> {
    @Composable
    override fun CreateEditor(
        project: Project,
        type: EditorType<AssetReference<out Asset>>,
        value: AssetReference<out Asset>?,
        onValueChange: (AssetReference<out Asset>) -> Unit
    ) {
        var selectedAsset by remember { mutableStateOf(value?.value) }
        val assets = remember { Assets.filterIsInstance(type.generics.first().type as KClass<Asset>) }
        var open by remember { mutableStateOf(false) }

        SelectBox(
            assets,
            selectedAsset,
            open,
            onOpenChange = { open = it },
            onSelectChange = { selected ->
                selectedAsset = selected
                onValueChange(AssetReference(selected.path))
            },
            itemContent = { asset ->
                Label("${asset.name} ${asset::class.simpleName?.let { "(${it.camelCaseToTitle()})" }}")
            }
        )
    }
}
