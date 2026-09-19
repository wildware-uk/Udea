package dev.wildware.udea.editor

import androidx.compose.runtime.Composable
import dev.wildware.composegl.ui.layout.Arrangement
import dev.wildware.composegl.ui.layout.Row
import dev.wildware.composegl.ui.modifier.Modifier
import dev.wildware.composegl.ui.modifier.fillMaxWidth
import dev.wildware.composegl.ui.modifier.padding
import dev.wildware.composegl.ui.modifier.testTag
import dev.wildware.composegl.ui.widget.Button
import dev.wildware.composegl.ui.widget.Text

/** The test tags on the Play/Stop/Step toolbar (issue #196): how `MobaEditorPlayTest` finds a button. */
public object PlaybackTags {

    /** `editor.play`. */
    public const val PLAY: String = "editor:play"

    /** `editor.stop`. */
    public const val STOP: String = "editor:stop"

    /** `time.step`, one tick. */
    public const val STEP: String = "editor:step"

    /** Saves the level and starts a separate game on it. */
    public const val PLAY_STANDALONE: String = "editor:play-standalone"

    /** The toolbar's line about what it last did. */
    public const val STATUS: String = "editor:playback-status"
}

/**
 * Play, Stop, Step and Play standalone, in a row under the menu bar.
 *
 * Every button is always pressable, and a press that does not apply is answered by its tool: Stop
 * with nothing playing is refused with `not_playing`, and the refusal is what the status says. A
 * button disabled from here would be the window keeping its own copy of whether a play is under
 * way, and an agent can start or stop one without the window knowing.
 */
@Composable
internal fun PlaybackToolbar(controls: PlayControls) {
    Row(Modifier.fillMaxWidth().padding(horizontal = GAP, vertical = GAP / 2), Arrangement.spacedBy(GAP)) {
        Button("Play", onClick = { controls.play() }, modifier = Modifier.testTag(PlaybackTags.PLAY))
        Button("Stop", onClick = { controls.stop() }, modifier = Modifier.testTag(PlaybackTags.STOP))
        Button("Step", onClick = { controls.step() }, modifier = Modifier.testTag(PlaybackTags.STEP))
        if (controls.canPlayStandalone) {
            Button("Play standalone", onClick = { controls.playStandalone() }, modifier = Modifier.testTag(PlaybackTags.PLAY_STANDALONE))
        }
        Text(controls.status, Modifier.testTag(PlaybackTags.STATUS))
    }
}

/** The space between the toolbar's parts, in design units. */
private const val GAP: Float = 8f
