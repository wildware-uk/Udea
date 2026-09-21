package dev.wildware.udea.render.sky

import dev.wildware.udea.render.draw.Rgba

/**
 * What the frame shows wherever nothing is drawn: past the edge of a 3D world, above its horizon,
 * behind a 2D map that does not fill the screen (issue #267).
 *
 * Without one, that is the capturable frame's clear colour, which is black - so a game that is not
 * indoors ships a black horizon, or hides it by pointing the camera at the ground. Each game used to
 * draw its own sky as a render system of its own; this is the engine's.
 *
 * A value, not a setting: a game holds [Sky] and puts one of these in it, and swaps it for another
 * when a level changes, so a day map and a night map are two values rather than two builds.
 */
public sealed interface SkyBackground {

    /**
     * No sky: nothing is drawn, and the frame's black clear colour shows. The default, and exactly
     * what every frame was before a sky existed - not a black sky drawn over black, but nothing drawn
     * at all.
     */
    public data object None : SkyBackground

    /**
     * One colour over the whole frame.
     *
     * [colour]'s alpha is drawn as it is, over the black clear colour, so a translucent sky is a
     * darker one. An opaque colour is what a sky is almost always meant to be.
     *
     * @property colour the whole frame's colour wherever nothing is drawn.
     */
    public data class Solid(val colour: Rgba) : SkyBackground

    /**
     * A vertical blend from [top], along the top edge of the frame, to [bottom], along its bottom
     * edge.
     *
     * In the frame's own space, not the world's: the blend stays where it is however the camera
     * turns. That is the right sky for a camera whose pitch does not change - an isometric view, a
     * fixed third-person one - and a camera that tilts far up and down would want a sky tied to its
     * view instead, which this is not.
     *
     * @property top the colour along the frame's top edge.
     * @property bottom the colour along the frame's bottom edge.
     */
    public data class Gradient(val top: Rgba, val bottom: Rgba) : SkyBackground
}
