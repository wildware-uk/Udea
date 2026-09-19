package dev.wildware.udea.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.wildware.composegl.ui.modifier.height
import dev.wildware.composegl.ui.modifier.offset
import dev.wildware.composegl.ui.modifier.onPlaced
import dev.wildware.composegl.ui.modifier.onShortcutKey
import dev.wildware.composegl.ui.modifier.onSizeChanged
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.size
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.modifier.weight
import dev.wildware.composegl.ui.modifier.width
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Checkbox
import dev.wildware.composegl.ui.widget.MenuBar
import dev.wildware.composegl.ui.widget.PopupHost
import dev.wildware.composegl.ui.widget.SceneView
import dev.wildware.composegl.ui.widget.Text
import dev.wildware.composegl.ui.widget.TextField
import dev.wildware.udea.render.view.ViewDimension

/**
 * The editor window's layout: a menu bar, the toolbar, the Scene and Game headings, the Create, Asset
 * and History panels docked round the showing tab, and a status line.
 *
 * The panels are ComposeGL's own docked windows (`DebugWindowHost`, from `composegl-debug`): they can
 * be dragged off, tabbed together and re-docked, and the dividers between them resized. Their layout
 * is kept in memory for the run - a file beside the game, the toolkit's default, would dirty the
 * working tree every time the editor was opened.
 */
@Composable
internal fun EditorWindow(session: EditorSession) {
    PopupHost {
        Column(Modifier.fillMaxSize().background(Background).onShortcutKey(session.keys)) {
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

/**
 * The docked panels, and the showing tab in the gap between them ([ViewArea]): the world is drawn only
 * there, so no panel lies over it and it shows behind none of them.
 */
@Composable
private fun Panels(session: EditorSession) {
    val windows = rememberDebugWindowsState(remember { MemoryDebugWindowStore() })
    remember(windows) {
        windows.dockToScreen(EditorTags.CREATE_PANEL, DockSide.Left)
        windows.dockToScreen(EditorTags.HISTORY_PANEL, DockSide.Right)
        windows.dockToScreen(EditorTags.ASSET_PANEL, DockSide.Right)
        windows.dockWith(InspectorTags.PANEL, EditorTags.HISTORY_PANEL, DockSide.Bottom)
        windows.dockWith(PlayEditTags.PANEL, EditorTags.CREATE_PANEL, DockSide.Bottom)
        if (session.animation != null) windows.dockToScreen(EditorTags.ANIMATION_PANEL, DockSide.Left)
    }
    val area = remember { ViewArea() }
    DebugWindowHost(Modifier.fillMaxSize().onPlaced(area.host), state = windows) {
        // The view in the gap the docked panels leave, never under them: it draws only there, and a
        // pointer over a panel or a divider is the panel's, never the view's.
        val free = area.free(windows::isDocked)
        Box(Modifier.offset(free.left, free.top).size(free.width, free.height)) {
            ViewPage(session)
        }
        DebugWindow("Create", id = EditorTags.CREATE_PANEL, modifier = Modifier.onPlaced(area.pane(EditorTags.CREATE_PANEL))) {
            Button(session.spawnLabel, onClick = { session.spawn() }, modifier = Modifier.fillMaxWidth().testTag(EditorTags.SPAWN))
        }
        DebugWindow("Inspector", id = InspectorTags.PANEL, modifier = Modifier.onPlaced(area.pane(InspectorTags.PANEL))) {
            InspectorPanel(session.inspector) { key -> KeepPin(session.playEdits, session.selection.ids, key) }
        }
        DebugWindow("Changes during Play", id = PlayEditTags.PANEL, modifier = Modifier.onPlaced(area.pane(PlayEditTags.PANEL))) {
            PlayEditsPanel(session.playEdits)
        }
        DebugWindow("Asset", id = EditorTags.ASSET_PANEL, modifier = Modifier.onPlaced(area.pane(EditorTags.ASSET_PANEL))) {
            AssetPanel(session.assets)
        }
        session.animation?.let { animation ->
            DebugWindow("Animation", id = EditorTags.ANIMATION_PANEL, modifier = Modifier.onPlaced(area.pane(EditorTags.ANIMATION_PANEL))) {
                AnimationPanel(animation)
            }
        }
        DebugWindow("History", id = EditorTags.HISTORY_PANEL, modifier = Modifier.onPlaced(area.pane(EditorTags.HISTORY_PANEL))) {
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
 * A row of its own under the toolbar, above the docked panels, so a panel docked along the top can
 * never cover a heading. Not ComposeGL's `Tabs`, whose headings carry no
 * test tag a test or an agent's UI driver could find.
 */
@Composable
private fun ViewTabs(session: EditorSession) {
    Row(
        // One height for both tabs' switches, so changing tab does not move the page under the pointer.
        Modifier.fillMaxWidth().height(TAB_ROW_HEIGHT).padding(horizontal = GAP, vertical = GAP / 2),
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
                session.preferences?.let { preferences -> GizmoToolbar(session, preferences) }
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

/**
 * The Scene tab's gizmo settings (issue #236): grid snapping and its step in world units, angle
 * snapping and its step in degrees, and the world/local axes switch. What is set here is kept in the
 * project's editor preferences ([GizmoPreferences]).
 */
@Composable
private fun GizmoToolbar(session: EditorSession, preferences: GizmoPreferences) {
    Checkbox(
        checked = preferences.gridSnap,
        onCheckedChange = { preferences.gridSnap = it },
        label = "Grid",
        modifier = Modifier.testTag(EditorTags.GRID_SNAP),
    )
    StepField(preferences.gridStep, EditorTags.GRID_STEP) { preferences.gridStep = it }
    Checkbox(
        checked = preferences.angleSnap,
        onCheckedChange = { preferences.angleSnap = it },
        label = "Angle",
        modifier = Modifier.testTag(EditorTags.ANGLE_SNAP),
    )
    StepField(preferences.angleStepDegrees, EditorTags.ANGLE_STEP) { preferences.angleStepDegrees = it }
    val local = preferences.axes == GizmoAxes.Local
    Button(
        if (local) "Local" else "World",
        onClick = { session.switchAxes(if (local) GizmoAxes.World else GizmoAxes.Local) },
        modifier = Modifier.testTag(EditorTags.AXES),
    )
}

/**
 * A box for a snapping step. What is typed is kept as typed, and set as the step once it reads as a
 * number above zero; anything else leaves the step as it was, so half-typed text never snaps to it.
 */
@Composable
private fun StepField(step: Float, tag: String, set: (Float) -> Unit) {
    var text by remember { mutableStateOf(step.toString()) }
    TextField(
        text,
        { typed ->
            text = typed
            typed.trim().toFloatOrNull()?.takeIf { it > 0f && it.isFinite() }?.let(set)
        },
        Modifier.width(STEP_WIDTH).testTag(tag),
    )
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

/** The window's backdrop, and what a tab shows around the world for the one frame before its view takes the tab's size. */
private val Background: Colour = Colour.rgb(0x1B1F27)

/** The Scene and Game headings' row, in design units: a button and its padding. */
private const val TAB_ROW_HEIGHT: Float = 44f

/** A snapping step's box, in design units: wide enough for a number like `22.5`. */
private const val STEP_WIDTH: Float = 64f

/** The space between a panel's parts, in design units. */
private const val GAP: Float = 8f
