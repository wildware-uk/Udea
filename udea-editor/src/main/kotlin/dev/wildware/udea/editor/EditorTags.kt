package dev.wildware.udea.editor

/**
 * The test tags on the editor window's parts: how a test - and `MobaEditorTest`, in the game that
 * launches the window - finds the button to press and the panel to read.
 */
public object EditorTags {

    /** The Scene tab's heading. */
    internal const val SCENE_TAB: String = "editor:tab-scene"

    /** The Game tab's heading. */
    internal const val GAME_TAB: String = "editor:tab-game"

    /** The world through the editor's camera, in its `SceneView`: the Scene tab's page. */
    public const val SCENE_VIEW: String = "editor:scene-view"

    /** The world through the game's camera, in its `SceneView`: the Game tab's page. */
    internal const val GAME_VIEW: String = "editor:game-view"

    /** The Scene tab's 2D / 3D switch: which camera a drag moves. */
    internal const val DIMENSION: String = "editor:dimension"

    /** The Game tab's toggle that draws gizmos over the game, read-only. */
    internal const val GAME_GIZMOS: String = "editor:game-gizmos"

    /** The Scene tab's grid snapping toggle and its step (issue #236). */
    internal const val GRID_SNAP: String = "editor:grid-snap"
    internal const val GRID_STEP: String = "editor:grid-step"

    /** The Scene tab's angle snapping toggle and its step, in degrees. */
    internal const val ANGLE_SNAP: String = "editor:angle-snap"
    internal const val ANGLE_STEP: String = "editor:angle-step"

    /** The Scene tab's world/local axes switch. */
    internal const val AXES: String = "editor:axes"

    /** The Create panel: its docked window's id, which the dock layout is keyed by. */
    internal const val CREATE_PANEL: String = "editor-create"

    /** The Asset panel's docked window id. */
    internal const val ASSET_PANEL: String = "editor-asset"

    /** The History panel's docked window id. */
    internal const val HISTORY_PANEL: String = "editor-history"

    /** The Create panel's spawn button. */
    public const val SPAWN: String = "editor:spawn"

    /** The History panel's undo button. */
    public const val UNDO: String = "editor:undo"

    /** The History panel's list of edits. */
    public const val HISTORY: String = "editor:history"

    /** The status line: paused or running, the tick, and the last refusal. */
    public const val STATUS: String = "editor:status"
}
