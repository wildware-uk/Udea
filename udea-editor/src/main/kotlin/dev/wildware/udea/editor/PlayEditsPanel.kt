package dev.wildware.udea.editor

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.widget.Checkbox
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.udea.core.identity.NetId

/**
 * The "Changes during Play" panel (issue #238): every edit made since Play, with a Keep toggle on
 * each one Stop can make again, and why not on each one it cannot.
 *
 * A kept edit survives Stop with the values it left; the rest are thrown away with the play.
 */
@Composable
internal fun PlayEditsPanel(playEdits: EditorPlayEdits) {
    Column(Modifier.fillMaxWidth().testTag(PlayEditTags.LIST)) {
        val edits = playEdits.edits
        when {
            !playEdits.playing -> Text("Not playing. Edits made after Play are listed here, to keep past Stop.")
            edits.isEmpty() -> Text("No changes yet. Anything edited while the game plays is listed here.")
            else -> for (row in edits) PlayEditRowView(playEdits, row)
        }
    }
}

/**
 * One edit: its Keep toggle first, labelled with the edit, so a short panel still shows every toggle's
 * box; then the values it left, or why it cannot be kept.
 */
@Composable
private fun PlayEditRowView(playEdits: EditorPlayEdits, row: PlayEditRow) {
    val title = "#${row.editId}  ${row.tool.removePrefix(TOOL_PREFIX)}  #${row.netId}  by ${row.author}"
    Column(Modifier.fillMaxWidth().padding(bottom = GAP)) {
        if (row.keepable) {
            Checkbox(
                checked = row.kept,
                onCheckedChange = { playEdits.setKept(row, it) },
                label = "Keep $title",
                modifier = Modifier.testTag(PlayEditTags.keep(row.editId)),
            )
        } else {
            Text(title, Modifier.fillMaxWidth())
        }
        for (value in row.values) Text("  ${value.key} = ${value.value}", Modifier.fillMaxWidth())
        if (!row.keepable) Text("  Can't keep: ${row.reason.orEmpty()}", Modifier.fillMaxWidth())
    }
}

/** What every editor tool's name starts with, left off a row's title to save the panel's width. */
private const val TOOL_PREFIX: String = "editor."

/**
 * The Inspector's Keep pin on field [key] of the [selected] entities: shown only when a play edit
 * wrote that field on one of them, checked while one of those edits is kept
 * ([EditorPlayEdits.togglePin]).
 */
@Composable
internal fun KeepPin(playEdits: EditorPlayEdits, selected: List<NetId>, key: String) {
    val rows = playEdits.touching(selected, key)
    if (rows.none { it.keepable }) return
    Checkbox(
        checked = rows.any { it.kept },
        onCheckedChange = { playEdits.togglePin(selected, key) },
        label = "Keep after Stop",
        modifier = Modifier.testTag(PlayEditTags.pin(key)),
    )
}

/** The space between the panel's rows, in design units. */
private const val GAP: Float = 8f

/**
 * The test tags on the "Changes during Play" panel and the Inspector's Keep pins (issue #238): how a
 * test - and `MobaPlayKeepPanelTest`, in the game that launches the window - finds the toggle to press.
 */
public object PlayEditTags {

    /** The panel's docked window id. */
    internal const val PANEL: String = "editor-play-edits"

    /** The panel's list of play edits. */
    public const val LIST: String = "editor:play-edits"

    /** The Keep toggle of the play edit [editId], as `editor.play_edits` numbers it. */
    public fun keep(editId: Long): String = "editor:play-edit-keep:$editId"

    /** The Inspector's Keep pin on the field `Component.field` [key]. */
    public fun pin(key: String): String = "editor:inspector-keep:$key"
}
