package dev.wildware.udea.editor

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField

/**
 * The Asset panel (issue #195): open an asset by id, change its plain values, and Save them into its
 * `.udea.kts` - or save them as a new asset beside it.
 *
 * A value the file computes is shown read-only, with the reason and the line to change it on, rather
 * than as a box that would refuse on Save: the person sees why before they try.
 */
@Composable
internal fun AssetPanel(assets: EditorAssets) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            TextField(
                assets.id,
                { assets.id = it },
                Modifier.weight(1f).testTag(EditorAssetTags.ID),
                placeholder = "character/soldier_idle_sheet",
                onSubmit = { assets.open() },
            )
            Button("Open", onClick = { assets.open() }, modifier = Modifier.testTag(EditorAssetTags.OPEN))
        }
        Column(Modifier.fillMaxWidth().padding(vertical = GAP).testTag(EditorAssetTags.FIELDS)) {
            for (field in assets.fields) {
                if (field.value == null) {
                    Text("${field.name}  read-only: ${field.reason}", Modifier.fillMaxWidth())
                } else {
                    Row(Modifier.fillMaxWidth()) {
                        Text(field.name, Modifier.weight(1f))
                        TextField(
                            assets.valueOf(field),
                            { assets.edit(field, it) },
                            Modifier.weight(1f).testTag(EditorAssetTags.field(field.name)),
                        )
                    }
                }
            }
        }
        Button("Save  (Ctrl+S)", onClick = { assets.save() }, modifier = Modifier.fillMaxWidth().testTag(EditorAssetTags.SAVE))
        Row(Modifier.fillMaxWidth().padding(top = GAP)) {
            TextField(
                assets.newName,
                { assets.newName = it },
                Modifier.weight(1f).testTag(EditorAssetTags.NEW_NAME),
                placeholder = "new asset name",
            )
            Button("Save as new asset", onClick = { assets.saveAsNew() }, modifier = Modifier.testTag(EditorAssetTags.SAVE_AS_NEW))
        }
        Text(assets.message, Modifier.fillMaxWidth().padding(top = GAP).testTag(EditorAssetTags.MESSAGE))
    }
}

/** The space between the panel's parts, in design units. */
private const val GAP: Float = 8f

/** The test tags on the Asset panel's parts. */
public object EditorAssetTags {

    /** The asset id box. */
    public const val ID: String = "editor:asset-id"

    /** The Open button beside it. */
    public const val OPEN: String = "editor:asset-open"

    /** The open asset's values: a box per plain literal, a line per read-only value. */
    public const val FIELDS: String = "editor:asset-fields"

    /** The Save button, which File > Save and Ctrl+S also press. */
    internal const val SAVE: String = "editor:asset-save"

    /** The new asset's name box. */
    public const val NEW_NAME: String = "editor:asset-new-name"

    /** The Save as new asset button. */
    public const val SAVE_AS_NEW: String = "editor:asset-save-as-new"

    /** What the last action did, and whether the running game has it. */
    public const val MESSAGE: String = "editor:asset-message"

    /** The box for one plain literal value, by its field name. */
    public fun field(name: String): String = "editor:asset-field:$name"
}
