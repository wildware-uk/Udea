package dev.wildware.udea.editor

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.onFocusWithin
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField

/**
 * The Inspector panel (issue #235): what the selected entities have in common, and a box per field
 * that writes to all of them at once.
 *
 * A field they agree on shows its value; one they disagree on shows an empty box that says
 * **Mixed**. Typing into a box writes the value to every one of them as it is typed, and Enter or
 * leaving the box keeps it as one undoable edit ([EditorInspector.edit]).
 */
@Composable
internal fun InspectorPanel(
    inspector: EditorInspector,
    /** Drawn under a field's name, keyed by `Component.field`: its Keep pin during Play (issue #238). */
    pin: @Composable (key: String) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth()) {
        Text(heading(inspector.count), Modifier.fillMaxWidth().testTag(InspectorTags.HEADING))
        // A plain column, not a `ScrollArea`: in ComposeGL 0.7.0 a press on a text field inside one
        // focuses the window around it rather than the field, so no value could be typed.
        Column(Modifier.fillMaxWidth().padding(vertical = GAP).testTag(InspectorTags.FIELDS)) {
            for (field in inspector.fields) FieldRow(inspector, field, pin)
        }
        Text(inspector.message, Modifier.fillMaxWidth().testTag(InspectorTags.MESSAGE))
    }
}

@Composable
private fun FieldRow(inspector: EditorInspector, field: InspectorField, pin: @Composable (key: String) -> Unit) {
    // The name on a line of its own: a docked panel is narrow, and `GameUnit.targetRaw` beside a box
    // wraps mid-word.
    Text(field.key, Modifier.fillMaxWidth())
    pin(field.key)
    val box = Modifier.fillMaxWidth().padding(bottom = GAP / 2f).testTag(InspectorTags.field(field.key))
    if (field.readOnly) {
        Text(field.value.orEmpty(), box)
    } else {
        // No Set button (the owner's ruling on #235): typing writes, Enter or leaving the box keeps it.
        // The focus handler is on a box around the field because ComposeGL tells a node's ancestors
        // that focus left it, never the node itself.
        Box(Modifier.fillMaxWidth().onFocusWithin { focused -> if (!focused) inspector.commit(field) }) {
            TextField(
                inspector.textOf(field),
                { inspector.edit(field, it) },
                box,
                placeholder = if (field.mixed) MIXED else "",
                onSubmit = { inspector.commit(field) },
            )
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
 * launches the window - finds a field to read and type into.
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
}
