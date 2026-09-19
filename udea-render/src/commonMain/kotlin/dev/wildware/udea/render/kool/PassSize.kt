package dev.wildware.udea.render.kool

import de.fabmax.kool.pipeline.OffscreenPass2d
import de.fabmax.kool.util.Viewport

/**
 * Makes this pass [width] x [height] pixels, and every view of it draw to the whole of the new size
 * (issue #234: an editor's views, and the frame a Game tab shows, follow the rectangle they are shown
 * in).
 *
 * Both halves, because Kool 0.19.0's `setSize` does only the first: it reallocates the attachments and
 * leaves each view's viewport at the size it was made with. A view that kept the old viewport would
 * draw to a corner of a larger texture, or past the edge of a smaller one - and a camera clipped to
 * that viewport would frame the old shape. Render thread only.
 */
internal fun OffscreenPass2d.resize(width: Int, height: Int) {
    setSize(width, height)
    val whole = Viewport(0, 0, width, height)
    for (view in views) view.viewport = whole
}
