package dev.wildware.udea.editors

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.intellij.openapi.project.Project
import dev.wildware.udea.assets.Asset
import dev.wildware.udea.assets.AssetRefence
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.camelCaseToTitle
import io.kanro.compose.jetbrains.expui.control.Label
import kotlin.reflect.KClass

object AssetReferenceEditor : ComposeEditor<AssetRefence<out Asset>> {
    @Composable
    override fun CreateEditor(
        project: Project,
        type: KClass<out AssetRefence<out Asset>>,
        value: AssetRefence<out Asset>?,
        onValueChange: (AssetRefence<out Asset>) -> Unit
    ) {
        var selectedAsset by remember { mutableStateOf<Asset?>(null) }
        val assets = remember { Assets.toList() }

        assets.forEach { asset ->
            Row(
                modifier = Modifier.padding(8.dp)
                    .clickable {
                        selectedAsset = asset
                        onValueChange(AssetRefence(asset.path))
                    }
                    .background(
                        if (selectedAsset == asset) {
                            androidx.compose.ui.graphics.Color.LightGray
                        } else {
                            androidx.compose.ui.graphics.Color.Transparent
                        }
                    )
            ) {
                Label(asset::class.simpleName?.camelCaseToTitle() ?: "Unknown")
            }
        }
    }
}
