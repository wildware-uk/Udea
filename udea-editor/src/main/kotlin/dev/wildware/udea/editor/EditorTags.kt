package dev.wildware.udea.editor

/**
 * The test tags on the editor window's parts: how a test - and `MobaEditorTest`, in the game that
 * launches the window - finds the button to press and the panel to read.
 */
public object EditorTags {

    /** The world, in its `SceneView`. */
    internal const val VIEWPORT: String = "editor:viewport"

    /** The Create panel's spawn button. */
    public const val SPAWN: String = "editor:spawn"

    /** The History panel's undo button. */
    public const val UNDO: String = "editor:undo"

    /** The History panel's list of edits. */
    public const val HISTORY: String = "editor:history"

    /** The status line: paused or running, the tick, and the last refusal. */
    public const val STATUS: String = "editor:status"
}
