package dev.wildware.udea.render.capture

/**
 * Reads pixels back out of whatever surface is bound right now, already PNG-encoded.
 *
 * The seam that keeps [FrameCaptureSlot] -- the request queue, the `afterTick` rule, the
 * ordering guarantee -- testable with no GL context. Everything about *when* a capture happens
 * is in the slot and is asserted in a plain JVM; everything about *how* pixels leave the
 * driver is in `GlPixelSource` and needs a real context.
 *
 * Encoding lives behind this interface rather than after it because the two are one decision:
 * the alpha stomp has to happen between `glReadPixels` and the encoder, on the raw pixmap, and
 * an interface that handed back a `Pixmap` would put a GL type in the slot's signature and
 * hand every implementer the chance to skip the stomp.
 */
internal interface PixelSource {

    /**
     * Reads `width` x `height` pixels from `(x, y)`, origin bottom-left, and encodes them.
     *
     * The extent is passed in rather than discovered here because the surface bound at the
     * capture point is the offscreen framebuffer, not the backbuffer: `Gdx.graphics` would
     * report the window's size, and a capture asked for a rectangle bigger than the bound
     * framebuffer comes back with whatever the driver felt like putting in the margin.
     *
     * @return PNG bytes, colour type 6 (RGBA), rows top-down, every alpha byte 255.
     */
    fun readPng(x: Int, y: Int, width: Int, height: Int): ByteArray
}
