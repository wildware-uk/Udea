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
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Box
import dev.wildware.composegl.ui.layout.Column
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.layout.VerticalAlignment
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.background
import dev.wildware.composegl.ui.modifier.fillMaxSize
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.onSizeChanged
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Checkbox
import dev.wildware.composegl.ui.widget.MenuBar
import dev.wildware.composegl.ui.widget.PopupHost
import dev.wildware.composegl.ui.widget.SceneView
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.udea.render.view.ViewDimension

/**
 * The editor window's layout: a menu bar, the toolbar, the Scene and Game headings, the showing tab
 * with the Create, Asset and History panels docked over its edges, and a status line.
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
                Menu("&File") {
                    Item("&Save", shortcut = KeyShortcut(Key.S, Modifiers(Modifiers.CONTROL))) { session.assets.save() }
                }
                Menu("&Edit") {
                    Item("&Undo", shortcut = KeyShortcut(Key.Z, Modifiers(Modifiers.CONTROL))) { session.undo() }
                }
            }
            PlaybackToolbar(session.playback)
            ViewTabs(session)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Panels(session)
            }
            Text(session.status, Modifier.fillMaxWidth().padding(horizontal = GAP, vertical = GAP / 2).testTag(EditorTags.STATUS))
        }
    }
}

/** The Scene and Game tabs, and the panels docked over their edges. */
@Composable
private fun Panels(session: EditorSession) {
    val windows = rememberDebugWindowsState(remember { MemoryDebugWindowStore() })
    remember(windows) {
        windows.dockToScreen(CREATE, DockSide.Left)
        windows.dockToScreen(HISTORY, DockSide.Right)
        windows.dockToScreen(ASSET, DockSide.Right)
    }
    DebugWindowHost(Modifier.fillMaxSize(), state = windows) {
        ViewPage(session)
        DebugWindow("Create", id = CREATE) {
            Button(session.spawnLabel, onClick = { session.spawn() }, modifier = Modifier.fillMaxWidth().testTag(EditorTags.SPAWN))
        }
        DebugWindow("Asset", id = ASSET) {
            AssetPanel(session.assets)
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

/**
 * The Scene and Game tabs' headings (issue #234), and the showing tab's own switch: the 2D / 3D camera
 * in the Scene tab, the gizmo overlay in the Game tab.
 *
 * A row of its own under the toolbar, rather than over the page: the docked panels lie over the page's
 * edges, and a heading under one could not be clicked. Not ComposeGL's `Tabs`, whose headings carry no
 * test tag a test or an agent's UI driver could find.
 */
@Composable
private fun ViewTabs(session: EditorSession) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = GAP, vertical = GAP / 2),
        horizontalArrangement = Arrangement.spacedBy(GAP / 2),
        verticalAlignment = VerticalAlignment.Centre,
    ) {
        TabHeading("Scene", EditorTab.Scene, session, EditorTags.SCENE_TAB)
        TabHeading("Game", EditorTab.Game, session, EditorTags.GAME_TAB)
        when (session.tab) {
            EditorTab.Scene -> {
                val next = if (session.dimension == ViewDimension.TwoD) ViewDimension.ThreeD else ViewDimension.TwoD
                Button(
                    if (session.dimension == ViewDimension.TwoD) "2D" else "3D",
                    onClick = { session.switchDimension(next) },
                    modifier = Modifier.testTag(EditorTags.DIMENSION),
                )
            }

            EditorTab.Game -> Checkbox(
                checked = session.gameGizmos,
                onCheckedChange = { session.showGameGizmos(it) },
                label = "Gizmos",
                modifier = Modifier.testTag(EditorTags.GAME_GIZMOS),
            )
        }
    }
}

/**
 * The showing tab's page: the world, in a `SceneView` with a picture of its own held by the session.
 * One is composed at a time.
 *
 * The Game tab's `SceneView` has no pointer handler, and that is the whole of how its input reaches
 * the game: an event no widget takes goes on to the game's own pointer. The Scene tab's takes every
 * event over it ([SceneNavigation]).
 */
@Composable
private fun ViewPage(session: EditorSession) {
    when (session.tab) {
        EditorTab.Scene -> SceneView(
            session.sceneState,
            Modifier.fillMaxSize().testTag(EditorTags.SCENE_VIEW).onSizeChanged { session.sceneBox = it },
            onPointer = session::scenePointer,
            // A click on the world must not take keyboard focus from the game's keys.
            focusable = false,
        ) {
            clear(Background)
            session.drawView(EditorTab.Scene, this)
        }

        EditorTab.Game -> SceneView(session.gameState, Modifier.fillMaxSize().testTag(EditorTags.GAME_VIEW)) {
            clear(Background)
            session.drawView(EditorTab.Game, this)
        }
    }
}

/** One tab's heading: the chosen one drawn as selected. */
@Composable
private fun TabHeading(title: String, tab: EditorTab, session: EditorSession, tag: String) {
    Button(
        title,
        onClick = { session.show(tab) },
        modifier = Modifier.testTag(tag),
        style = if (session.tab == tab) "tab.selected" else "tab",
    )
}

/** The window's backdrop, and the letterbox bars around the world. */
private val Background: Colour = Colour.rgb(0x1B1F27)

/** The space between a panel's parts, in design units. */
private const val GAP: Float = 8f

/** The docked windows' ids, which the dock layout is keyed by. */
private const val CREATE: String = "editor-create"
private const val HISTORY: String = "editor-history"
private const val ASSET: String = "editor-asset"
