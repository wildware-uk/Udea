package dev.wildware.udea.editors.builder.editors

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.columns
import dev.wildware.udea.assets.Asset
import dev.wildware.udea.assets.AssetReference
import dev.wildware.udea.assets.Assets
import dev.wildware.udea.camelCaseToTitle
import dev.wildware.udea.editors.builder.UObjectBuilder
import dev.wildware.udea.editors.builder.UValueBuilder
import javax.swing.ListCellRenderer
import kotlin.reflect.KClass

@UdeaEditor(AssetReference::class)
class UAssetReferenceFormEditor : UFormEditor<UValueBuilder> {

    override fun Panel.buildEditor(project: Project, builder: UValueBuilder, onSave: () -> Unit) {
        val assets: Collection<Asset> = Assets.filterIsInstance(builder.type.generics.first().type as KClass<Asset>)

        row {
            comboBox(
                assets,
                ListCellRenderer<Asset?> { list, value, index, isSelected, cellHasFocus ->
                    JBLabel(value?.name?.camelCaseToTitle() ?: "Select Asset...")
                })
                .align(AlignX.RIGHT)
                .resizableColumn()
                .bindItem(
                    getter = { (builder.value as? AssetReference<Asset>)?.value },
                    setter = { }
                )
                .onChanged {
                    builder.value = it.item.reference
                    onSave()
                }
        }
    }
}
