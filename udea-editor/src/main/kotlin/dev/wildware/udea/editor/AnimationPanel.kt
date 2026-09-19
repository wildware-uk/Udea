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
import dev.wildware.composegl.ui.widget.Checkbox
import dev.wildware.composegl.ui.widget.Slider
import dev.wildware.composegl.ui.widget.Text

/**
 * The Animation panel (issue #243): the selected entity's clips, a scrub preview for the Scene tab,
 * and the game's animated models to preview on their own. Everything it does is [AnimationPanelState]'s.
 */
@Composable
internal fun AnimationPanel(state: AnimationPanelState) {
    Column(Modifier.fillMaxWidth()) {
        val target = state.target
        val model = state.targetModel
        if (target == null || model == null) {
            Text("Select an animated entity", Modifier.fillMaxWidth().testTag(AnimationTags.TARGET))
        } else {
            Text("#${target.raw}  ${model.model.asset.id.value}", Modifier.fillMaxWidth().testTag(AnimationTags.TARGET))
            for (clip in model.clips) {
                Row(Modifier.fillMaxWidth()) {
                    val playing = clip.index == state.playingClip
                    Button(
                        if (playing) "${clip.name}  (playing)" else clip.name,
                        onClick = { state.choose(clip) },
                        modifier = Modifier.weight(1f).testTag(AnimationTags.clip(clip.name)),
                        style = if (playing) "tab.selected" else "tab",
                    )
                    Button("Preview", onClick = { state.previewOf(clip) }, modifier = Modifier.testTag(AnimationTags.previewClip(clip.name)))
                }
            }
            Checkbox(
                checked = state.previewing,
                onCheckedChange = { state.preview(it) },
                label = "Preview in the Scene tab",
                modifier = Modifier.padding(top = GAP).testTag(AnimationTags.PREVIEW),
            )
        }
        val clip = state.previewClip
        if ((state.previewing || state.assetPreview != null) && clip != null) {
            Row(Modifier.fillMaxWidth().padding(top = GAP)) {
                Button(
                    if (state.playing) "Pause" else "Play",
                    onClick = { state.play(!state.playing) },
                    modifier = Modifier.testTag(AnimationTags.PLAY),
                )
                Text("${clip.name}  ${state.previewAt} / ${clip.length.count}", Modifier.weight(1f).padding(horizontal = GAP).testTag(AnimationTags.TIME))
            }
            Slider(
                value = state.previewAt.toFloat(),
                onValueChange = { state.scrub(it.toLong()) },
                modifier = Modifier.fillMaxWidth().testTag(AnimationTags.SCRUBBER),
                range = 0f..clip.length.count.toFloat(),
                step = 1f,
            )
        }
        Text("Models", Modifier.fillMaxWidth().padding(top = GAP))
        for (animated in state.models) {
            val shown = state.assetPreview === animated
            Button(
                if (shown) "${animated.model.asset.id.value}  (showing)" else animated.model.asset.id.value,
                onClick = { state.showModel(animated) },
                modifier = Modifier.fillMaxWidth().testTag(AnimationTags.model(animated.model.asset.id.value)),
            )
            if (shown) {
                for (modelClip in animated.clips) {
                    Button(modelClip.name, onClick = { state.previewOf(modelClip) }, modifier = Modifier.fillMaxWidth().testTag(AnimationTags.previewClip(modelClip.name)))
                }
            }
        }
        state.problem?.let { Text(it, Modifier.fillMaxWidth().padding(top = GAP).testTag(AnimationTags.PROBLEM)) }
    }
}

/** The space between the panel's parts, in design units. */
private const val GAP: Float = 8f

/** The test tags on the Animation panel's parts. */
public object AnimationTags {

    /** The selected entity and its model, or a prompt to select one. */
    public const val TARGET: String = "editor:animation-target"

    /** The scrub preview's switch. */
    public const val PREVIEW: String = "editor:animation-preview"

    /** Play and Pause. */
    internal const val PLAY: String = "editor:animation-play"

    /** The scrubber. */
    public const val SCRUBBER: String = "editor:animation-scrubber"

    /** Which clip the preview shows, and where in it. */
    internal const val TIME: String = "editor:animation-time"

    /** Why the last control was refused. */
    internal const val PROBLEM: String = "editor:animation-problem"

    /** The button that chooses clip [name] for the entity: an edit. */
    public fun clip(name: String): String = "editor:animation-clip:$name"

    /** The button that previews clip [name], without an edit. */
    internal fun previewClip(name: String): String = "editor:animation-preview-clip:$name"

    /** The button that shows model [id] on its own. */
    internal fun model(id: String): String = "editor:animation-model:$id"
}
