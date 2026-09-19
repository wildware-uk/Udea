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
 * The Inspector panel (issue #235): what the selected entities have in common, and a box per field
 * to set it on all of them at once.
 *
 * A field they agree on shows its value; one they disagree on shows an empty box that says
 * **Mixed**, and typing a value there and pressing Enter or Set gives every one of them that value,
 * as one undoable edit.
 */
@Composable
internal fun InspectorPanel(inspector: EditorInspector) {
    Column(Modifier.fillMaxWidth()) {
        Text(heading(inspector.count), Modifier.fillMaxWidth().testTag(InspectorTags.HEADING))
        // A plain column, not a `ScrollArea`: in ComposeGL 0.7.0 a press on a text field inside one
        // focuses the window around it rather than the field, so no value could be typed.
        Column(Modifier.fillMaxWidth().padding(vertical = GAP).testTag(InspectorTags.FIELDS)) {
            for (field in inspector.fields) FieldRow(inspector, field)
        }
        Text(inspector.message, Modifier.fillMaxWidth().testTag(InspectorTags.MESSAGE))
    }
}

@Composable
private fun FieldRow(inspector: EditorInspector, field: InspectorField) {
    // The name on a line of its own: a docked panel is narrow, and `GameUnit.targetRaw` beside a box
    // wraps mid-word.
    Text(field.key, Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth().padding(bottom = GAP / 2f)) {
        if (field.readOnly) {
            Text(field.value.orEmpty(), Modifier.weight(1f).testTag(InspectorTags.field(field.key)))
        } else {
            TextField(
                inspector.textOf(field),
                { inspector.edit(field, it) },
                Modifier.weight(1f).testTag(InspectorTags.field(field.key)),
                placeholder = if (field.mixed) MIXED else "",
                onSubmit = { inspector.set(field) },
            )
            Button("Set", onClick = { inspector.set(field) }, modifier = Modifier.testTag(InspectorTags.set(field.key)))
        }
    }
}

private fun heading(count: Int): String = when (count) {
    0 -> "Nothing selected. Click an entity in the Scene tab."
    1 -> "1 entity selected"
    else -> "$count entities selected: fields they share"
}

/** What a field the selected entities disagree on shows in place of a value. */
public const val MIXED: String = "Mixed"

/** The space between the panel's parts, in design units. */
private const val GAP: Float = 8f

/**
 * The test tags on the Inspector panel's parts: how a test - and `MobaInspectorTest`, in the game that
 * launches the window - finds a field to read and the button that sets it.
 */
public object InspectorTags {

    /** The panel's docked window id. */
    internal const val PANEL: String = "editor-inspector"

    /** How many entities are selected. */
    internal const val HEADING: String = "editor:inspector-heading"

    /** The shared fields, one row each. */
    internal const val FIELDS: String = "editor:inspector-fields"

    /** What the last set did. */
    internal const val MESSAGE: String = "editor:inspector-message"

    /** The box for one shared field, by its `Component.field`. */
    public fun field(key: String): String = "editor:inspector-field:$key"

    /** The Set button beside it. */
    public fun set(key: String): String = "editor:inspector-set:$key"
}
