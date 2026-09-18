package dev.wildware.udea.render.draw

import dev.wildware.udea.render.RenderResource

/**
 * A fixed-width 5x7 pixel font for developer text: debug labels and the agent activity overlay.
 *
 * ## Why a font compiled into the engine
 *
 * LibGDX's no-argument `BitmapFont()` was what those two drew with, and it needed no asset. Kool's
 * text is MSDF glyphs from a font atlas that loads asynchronously and draws with its own shader,
 * which would put a second mesh kind into [SpriteBatch2D] and leave the first frames of a capture
 * without labels. Developer text needs neither typography nor a loading step: it needs to be
 * legible, present on frame one, and the same pixels twice. So the glyphs are a table here, the
 * sheet is built from it in code, and text is ordinary sprites - one run per label, in the order it
 * was drawn.
 *
 * ## The glyphs
 *
 * Printable ASCII, `' '` to `'~'`, each a 5x7 cell on a 6x8 advance - the dot-matrix character
 * set a HD44780 display shows. Anything outside that range draws as [FALLBACK]. The UI proper is
 * ComposeGL's, with real glyphs (issue #224); this font is not for players.
 */
public class BitmapFont2D private constructor(
    /** The glyph sheet, [SHEET_COLUMNS] cells across. */
    private val sheet: SpriteTexture,
    /** Texels per glyph texel: `2` draws a 5x7 glyph ten pixels wide. */
    public val scale: Int,
) : RenderResource {

    private val glyphs: Array<SpriteRegion> = Array(GLYPH_COUNT) { index ->
        SpriteRegion(
            sheet,
            (index % SHEET_COLUMNS) * CELL_WIDTH,
            (index / SHEET_COLUMNS) * CELL_HEIGHT,
            CELL_WIDTH,
            CELL_HEIGHT,
        )
    }

    /** Baseline to baseline, in pixels. */
    public val lineHeight: Float = (CELL_HEIGHT + LINE_GAP) * scale.toFloat()

    /** How wide [text] draws, in pixels. Every glyph advances the same distance. */
    public fun measure(text: CharSequence): Float = text.length * CELL_WIDTH * scale.toFloat()

    /**
     * Draws [text] with its left edge at [x] and its baseline at [baselineY], in the pixel space of
     * a batch begun with [SpriteBatch2D.beginPixels].
     */
    public fun draw(batch: SpriteBatch2D, text: CharSequence, x: Float, baselineY: Float, tint: Rgba) {
        val cellWidth = CELL_WIDTH * scale.toFloat()
        val cellHeight = CELL_HEIGHT * scale.toFloat()
        // The glyph rows sit at the top seven texels of the cell; the eighth is the gap below the
        // baseline, so the cell's bottom edge is one scaled texel under it.
        val bottom = baselineY - scale
        var penX = x
        for (index in text.indices) {
            val char = text[index]
            if (char != ' ') {
                batch.draw(glyphs[glyphIndex(char)], penX, bottom, cellWidth, cellHeight, tint)
            }
            penX += cellWidth
        }
    }

    override fun release() {
        sheet.release()
    }

    override fun toString(): String = "BitmapFont2D(5x7, scale $scale)"

    public companion object {

        /** Drawn for any character outside printable ASCII. */
        public const val FALLBACK: Char = '?'

        /**
         * The built-in font at [scale] texels per glyph texel.
         *
         * Each call builds a sheet of its own, which its owner releases: hand it to
         * `RenderResources.own` so the pipeline does.
         */
        public fun builtIn(scale: Int = 2): BitmapFont2D {
            require(scale > 0) { "font scale must be positive, was $scale" }
            return BitmapFont2D(SpriteTexture.fromRgba(SHEET_WIDTH, SHEET_HEIGHT, sheetPixels(), "udea-5x7-font"), scale)
        }

        /** The glyph for [char]: its offset from `' '`, or [FALLBACK]'s. */
        internal fun glyphIndex(char: Char): Int {
            val code = char.code - FIRST_CHAR.code
            return if (code in 0 until GLYPH_COUNT) code else FALLBACK.code - FIRST_CHAR.code
        }

        /** Whether glyph [char] has its texel at column [column] (0 is left), row [row] (0 is top) set. */
        internal fun isSet(char: Char, column: Int, row: Int): Boolean {
            require(column in 0 until GLYPH_WIDTH && row in 0 until GLYPH_HEIGHT) {
                "($column, $row) is outside a ${GLYPH_WIDTH}x$GLYPH_HEIGHT glyph"
            }
            val at = glyphIndex(char) * GLYPH_HEIGHT * HEX_DIGITS_PER_ROW + row * HEX_DIGITS_PER_ROW
            val bits = GLYPH_ROWS.substring(at, at + HEX_DIGITS_PER_ROW).toInt(HEX_RADIX)
            return (bits shr (GLYPH_WIDTH - 1 - column)) and 1 == 1
        }

        private fun sheetPixels(): ByteArray {
            val pixels = ByteArray(SHEET_WIDTH * SHEET_HEIGHT * BYTES_PER_PIXEL)
            for (glyph in 0 until GLYPH_COUNT) {
                val char = FIRST_CHAR + glyph
                val left = (glyph % SHEET_COLUMNS) * CELL_WIDTH
                val top = (glyph / SHEET_COLUMNS) * CELL_HEIGHT
                for (row in 0 until GLYPH_HEIGHT) {
                    for (column in 0 until GLYPH_WIDTH) {
                        if (!isSet(char, column, row)) continue
                        val at = ((top + row) * SHEET_WIDTH + left + column) * BYTES_PER_PIXEL
                        // White and opaque; the draw's tint colours it.
                        pixels[at] = -1
                        pixels[at + 1] = -1
                        pixels[at + 2] = -1
                        pixels[at + 3] = -1
                    }
                }
            }
            return pixels
        }

        private const val FIRST_CHAR: Char = ' '
        private const val GLYPH_COUNT: Int = '~'.code - ' '.code + 1
        private const val GLYPH_WIDTH: Int = 5
        private const val GLYPH_HEIGHT: Int = 7
        private const val CELL_WIDTH: Int = 6
        private const val CELL_HEIGHT: Int = 8
        private const val LINE_GAP: Int = 2
        private const val SHEET_COLUMNS: Int = 16
        private const val SHEET_WIDTH: Int = SHEET_COLUMNS * CELL_WIDTH
        private const val SHEET_HEIGHT: Int = ((GLYPH_COUNT + SHEET_COLUMNS - 1) / SHEET_COLUMNS) * CELL_HEIGHT
        private const val BYTES_PER_PIXEL: Int = 4
        private const val HEX_DIGITS_PER_ROW: Int = 2
        private const val HEX_RADIX: Int = 16

        /**
         * Seven rows per glyph, top first, two hex digits a row; bit 4 is the leftmost column.
         * One line per glyph, in ASCII order from `' '`.
         */
        private val GLYPH_ROWS: String = listOf(
            "00000000000000", // ' '
            "04040404000004", // !
            "0A0A0A00000000", // "
            "0A0A1F0A1F0A0A", // #
            "040F140E051E04", // $
            "18190204081303", // %
            "0C12140815120D", // &
            "0C040800000000", // '
            "02040808080402", // (
            "08040202020408", // )
            "0004150E150400", // *
            "0004041F040400", // +
            "000000000C0408", // ,
            "0000001F000000", // -
            "00000000000C0C", // .
            "00010204081000", // /
            "0E11131519110E", // 0
            "040C040404040E", // 1
            "0E11010204081F", // 2
            "1F02040201110E", // 3
            "02060A121F0202", // 4
            "1F101E0101110E", // 5
            "0608101E11110E", // 6
            "1F010204080808", // 7
            "0E11110E11110E", // 8
            "0E11110F01020C", // 9
            "000C0C000C0C00", // :
            "000C0C000C0408", // ;
            "02040810080402", // <
            "00001F001F0000", // =
            "08040201020408", // >
            "0E110102040004", // ?
            "0E11010D15150E", // @
            "0E1111111F1111", // A
            "1E11111E11111E", // B
            "0E11101010110E", // C
            "1C12111111121C", // D
            "1F10101E10101F", // E
            "1F10101E101010", // F
            "0E11101711110F", // G
            "1111111F111111", // H
            "0E04040404040E", // I
            "0702020202120C", // J
            "11121418141211", // K
            "1010101010101F", // L
            "111B1515111111", // M
            "11111915131111", // N
            "0E11111111110E", // O
            "1E11111E101010", // P
            "0E11111115120D", // Q
            "1E11111E141211", // R
            "0F10100E01011E", // S
            "1F040404040404", // T
            "1111111111110E", // U
            "11111111110A04", // V
            "1111111515150A", // W
            "11110A040A1111", // X
            "1111110A040404", // Y
            "1F01020408101F", // Z
            "0E08080808080E", // [
            "00100804020100", // \
            "0E02020202020E", // ]
            "040A1100000000", // ^
            "0000000000001F", // _
            "08040200000000", // `
            "00000E010F110F", // a
            "1010161911111E", // b
            "00000E1010110E", // c
            "01010D1311110F", // d
            "00000E111F100E", // e
            "0609081C080808", // f
            "000F11110F010E", // g
            "10101619111111", // h
            "04000C0404040E", // i
            "0200060202120C", // j
            "10101214181412", // k
            "0C04040404040E", // l
            "00001A15151111", // m
            "00001619111111", // n
            "00000E1111110E", // o
            "00001E111E1010", // p
            "00000D130F0101", // q
            "00001619101010", // r
            "00000E100E011E", // s
            "08081C08080906", // t
            "0000111111130D", // u
            "00001111110A04", // v
            "0000111115150A", // w
            "0000110A040A11", // x
            "000011110F010E", // y
            "00001F0204081F", // z
            "02040408040402", // {
            "04040404040404", // |
            "08040402040408", // }
            "00000815020000", // ~
        ).joinToString(separator = "").also { rows ->
            check(rows.length == GLYPH_COUNT * GLYPH_HEIGHT * HEX_DIGITS_PER_ROW) {
                "the glyph table has ${rows.length} digits, not ${GLYPH_COUNT * GLYPH_HEIGHT * HEX_DIGITS_PER_ROW}"
            }
        }
    }
}
