package dev.wildware.udea.render.model

import dev.wildware.udea.core.Ticks
import dev.wildware.udea.core.spatial.AnimationClip

/**
 * The Khronos Fox's three clips, as the asset build generates them for a game (`Fox.Clips` in
 * `moba:game`, issue #241): their index in the file's `animations` array and their length in ticks.
 * This module has no asset build of its own, so its tests and its shot name them here.
 */
internal object FoxClips {
    val Survey: AnimationClip = AnimationClip(index = 0, name = "Survey", length = Ticks(205L))
    val Walk: AnimationClip = AnimationClip(index = 1, name = "Walk", length = Ticks(43L))
    val Run: AnimationClip = AnimationClip(index = 2, name = "Run", length = Ticks(70L))
}
