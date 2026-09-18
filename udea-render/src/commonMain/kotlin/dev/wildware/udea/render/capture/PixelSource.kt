package dev.wildware.udea.render.capture

/**
 * Reads pixels back out of whatever surface is bound right now, already PNG-encoded.
 *
 * The seam that keeps [FrameCaptureSlot] -- the request queue, the `afterTick` rule, the
 * ordering guarantee -- testable with no render context. Everything about *when* a capture
 * happens is in the slot and is asserted in a plain JVM; everything about *how* pixels leave the
 * driver is in `dev.wildware.udea.render.kool.KoolPixelSource` and needs a real Kool context.
 *
 * Encoding lives behind this interface rather than after it because the two are one decision:
 * the alpha stomp has to happen between the texture read-back and the encoder, on the raw
 * buffer, and an interface that handed back a driver-owned image type would put that type in
 * the slot's signature and hand every implementer the chance to skip the stomp.
 */
internal interface PixelSource {

    /**
     * Reads `width` x `height` pixels from `(x, y)`, origin bottom-left, and encodes them.
     *
     * The extent is passed in rather than discovered here because the surface bound at the
     * capture point is the offscreen pass, not the window: asking the context for "the" size
     * would report the window's, and a capture asked for a rectangle bigger than the bound pass
     * comes back with whatever the driver felt like putting in the margin.
     *
     * @return PNG bytes, colour type 6 (RGBA), rows top-down, every alpha byte 255.
     */
    fun readPng(x: Int, y: Int, width: Int, height: Int): ByteArray
}
