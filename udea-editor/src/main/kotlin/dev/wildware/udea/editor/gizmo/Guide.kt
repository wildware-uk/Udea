package dev.wildware.udea.editor.gizmo

import com.github.quillraven.fleks.Component

/**
 * Something a gizmo draws that nobody grabs: the outline of the box a resize handle changes, the
 * circle a range reaches (issue #236).
 *
 * A [HandleShape] keeps its size on screen, because it is something to grab. A guide is the opposite:
 * it is drawn **in world space**, at the world size it describes, because what it shows is how big
 * something is. A tower's attack range drawn at a constant size on screen would say nothing about
 * the range. Declared with [GizmoScope.guide]; the editor draws each one thin, under the handles,
 * and a press never lands on one.
 */
public sealed interface Guide {

    /** A straight line from [from] to [to]. */
    public data class Line(val from: WorldPoint, val to: WorldPoint) : Guide

    /**
     * A circle of world radius [radius] about [centre], lying in the XY plane of [axes]: the ground
     * plane with the world's axes, which is where a 2D game's circles are.
     */
    public data class Circle(
        val centre: WorldPoint,
        val radius: Float,
        val axes: AxisFrame = AxisFrame.WORLD,
    ) : Guide
}

/**
 * The guides [this] gizmo declares for [target], in the order it declared them.
 *
 * Beside [handles], for a game's test of its own gizmo: [handles] answers what can be dragged, and
 * this what is drawn round it.
 */
public fun <C : Component<C>> Gizmo<C>.guides(target: GizmoTarget<C>): List<Guide> =
    built(target).guides
