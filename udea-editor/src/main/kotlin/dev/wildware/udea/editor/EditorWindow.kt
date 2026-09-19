package dev.wildware.udea.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.wildware.composegl.debug.DebugWindow
import dev.wildware.composegl.debug.DebugWindowHost
import dev.wildware.composegl.debug.DockSide
import dev.wildware.composegl.debug.MemoryDebugWindowStore
import dev.wildware.composegl.debug.rememberDebugWindowsState
import dev.wildware.composegl.ui.graphics.Colour
import dev.wildware.composegl.ui.input.Key
import dev.wildware.composegl.ui.input.KeyShortcut
import dev.wildware.composegl.ui.input.Modifiers
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.MenuBar
import dev.wildware.composegl.ui.widget.PopupHost
import dev.wildware.composegl.ui.widget.SceneView
import dev.wildware.composegl.ui.widget.Text

/**
 * The editor window's layout: a menu bar, the viewport with the Create and History panels docked
 * either side of it, and a status line.
 *
 * The panels are ComposeGL's own docked windows (`DebugWindowHost`, from `composegl-debug`): they can
 * be dragged off, tabbed together and re-docked, and the dividers between them resized. Their layout
 * is kept in memory for the run - a file beside the game, the toolkit's default, would dirty the
 * working tree every time the editor was opened.
 */
@Composable
internal fun EditorWindow(session: EditorSession) {
    PopupHost {
        Column(Modifier.fillMaxSize().background(Background)) {
            MenuBar(Modifier.fillMaxWidth()) {
                Menu("&Edit") {
                    Item("&Undo", shortcut = KeyShortcut(Key.Z, Modifiers(Modifiers.CONTROL))) { session.undo() }
                }
            }
            PlaybackToolbar(session.playback)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Panels(session)
            }
            Text(session.status, Modifier.fillMaxWidth().padding(horizontal = GAP, vertical = GAP / 2).testTag(EditorTags.STATUS))
        }
    }
}

/** The viewport, and the two panels docked over its edges. */
@Composable
private fun Panels(session: EditorSession) {
    val windows = rememberDebugWindowsState(remember { MemoryDebugWindowStore() })
    remember(windows) {
        windows.dockToScreen(CREATE, DockSide.Left)
        windows.dockToScreen(HISTORY, DockSide.Right)
    }
    DebugWindowHost(Modifier.fillMaxSize(), state = windows) {
        SceneView(session.viewportState, Modifier.fillMaxSize().testTag(EditorTags.VIEWPORT)) {
            clear(Background)
            session.drawViewport(this)
        }
        DebugWindow("Create", id = CREATE) {
            Button(session.spawnLabel, onClick = { session.spawn() }, modifier = Modifier.fillMaxWidth().testTag(EditorTags.SPAWN))
        }
        DebugWindow("History", id = HISTORY) {
            Column(Modifier.fillMaxWidth()) {
                Button("Undo", onClick = { session.undo() }, modifier = Modifier.fillMaxWidth().testTag(EditorTags.UNDO))
                Column(Modifier.fillMaxWidth().padding(top = GAP).testTag(EditorTags.HISTORY)) {
                    val entries = session.history
                    if (entries.isEmpty()) {
                        Text("Nothing to undo")
                    } else {
                        for (entry in entries) Text("#${entry.sequence}  ${entry.tool}  #${entry.netId}")
                    }
                }
            }
        }
    }
}

/** The window's backdrop, and the letterbox bars around the world. */
private val Background: Colour = Colour.rgb(0x1B1F27)

/** The space between a panel's parts, in design units. */
private const val GAP: Float = 8f

/** The docked windows' ids, which the dock layout is keyed by. */
private const val CREATE: String = "editor-create"
private const val HISTORY: String = "editor-history"
