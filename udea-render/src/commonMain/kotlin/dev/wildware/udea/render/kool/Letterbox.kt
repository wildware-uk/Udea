package dev.wildware.udea.render.kool

/**
 * Where a picture of one shape goes inside a target of another, scaled to fit and centred: the
 * letterbox both the window and a `SceneView` show the capturable frame with.
 *
 * One rule for both, because they show the same pixels and must frame them the same way - a world
 * that fills the window edge to edge and is cropped in the editor's viewport would be two answers to
 * "what does the agent see".
 */
internal class Letterbox(
    /** Left edge in the target, in pixels. */
    val x: Float,
    /** Bottom edge in the target, in pixels. */
    val y: Float,
    /** Width drawn, in pixels. */
    val width: Float,
    /** Height drawn, in pixels. */
    val height: Float,
) {

    override fun toString(): String = "Letterbox($x, $y, ${width}x$height)"

    companion object {

        /** A [sourceWidth] x [sourceHeight] picture, as large as fits in the target and centred. */
        fun fit(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Letterbox {
            require(sourceWidth > 0 && sourceHeight > 0) { "the picture must have extent, was ${sourceWidth}x$sourceHeight" }
            val scale = minOf(targetWidth.toFloat() / sourceWidth, targetHeight.toFloat() / sourceHeight)
            val width = sourceWidth * scale
            val height = sourceHeight * scale
            return Letterbox((targetWidth - width) / 2f, (targetHeight - height) / 2f, width, height)
        }
    }
}
