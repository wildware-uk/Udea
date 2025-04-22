package dev.wildware.udea.assets

import androidx.compose.runtime.Composable
import kotlin.reflect.KClass
import kotlin.reflect.full.isSuperclassOf

interface AssetEditor {
    @Composable
    fun AssetFileEditor.CreateAssetEditor()

    companion object {
        val assetEditors = mutableMapOf<KClass<out Any>, AssetEditor>()

        fun register(assetType: KClass<out Any>, editor: AssetEditor) {
            assetEditors[assetType] = editor
        }

        fun get(assetType: KClass<out Any>): AssetEditor {
            return (assetEditors.entries.firstOrNull { it.key == assetType }
                ?: assetEditors.entries.first { it.key.isSuperclassOf(assetType) }
                    ).value
        }
    }
}
