package dev.wildware.udea.assets.compiler.validate

import dev.wildware.udea.assets.compiler.TestPaths
import dev.wildware.udea.diagnostics.Severity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sprite-sheet grid against the actual image (issue #88).
 *
 * The fixture art is the committed orc pack, not a generated image: `Orc-Idle.png` is 600x100
 * and `docs/art-assets.md` says the orc idle is six frames, so `columns = 6` is the manifest's
 * own number and `columns = 7` is a defect the file itself proves. That is the difference
 * between testing a validator and testing a constant a test wrote down.
 */
class SheetGeometryTest {

    private fun corpus() = ValidationFixture.withArt(
        "geometry",
        "character/sheets.udea.kts" to """
            spriteSheet(name = "good", spritePath = "/sprites/orc/Orc-Idle.png", columns = 6)
            spriteSheet(name = "indivisible", spritePath = "/sprites/orc/Orc-Walk.png", columns = 7)
            spriteSheet(name = "oblong", spritePath = "/sprites/orc/Orc-Idle.png", columns = 5)
            spriteSheet(name = "absent", spritePath = "/sprites/orc/Orc-Nothing.png", columns = 6)
        """,
    )

    /** The manifest's own frame count passes against the real PNG. */
    @Test
    fun `the art manifest's frame count validates against the committed art`() {
        assertNull(
            SpriteSheetGeometryValidator.validate(corpus()).firstOrNull { it.assetId == "character/good" },
            "orc idle really is 6 frames of 100x100",
        )
    }

    /**
     * A grid that does not divide the image is an error carrying both numbers.
     *
     * 800 pixels wide, 7 columns: `TextureRegion.split` would hand back 114-pixel frames sliced
     * across two drawings, and nothing at runtime would ever say so.
     */
    @Test
    fun `a grid that does not divide the image fails naming declared and actual dimensions`() {
        val diagnostic = assertNotNull(
            SpriteSheetGeometryValidator.validate(corpus())
                .firstOrNull { it.assetId == "character/indivisible" },
        )

        assertEquals(Severity.Error, diagnostic.severity)
        assertEquals(AssetValidationRules.SHEET_GEOMETRY.id, diagnostic.ruleId)
        assertTrue("7 columns" in diagnostic.message, diagnostic.message)
        assertTrue("800x100" in diagnostic.message, diagnostic.message)
        assertTrue("8 columns" in diagnostic.message, "it should name the count that would work")
        assertNotNull(diagnostic.span, "the declaration's span")
    }

    /**
     * A grid that divides but yields oblong frames is a warning.
     *
     * 600 / 5 = 120x100. Divisibility alone passes it; the art manifest says every frame is
     * square. This is the case the issue's `width % columns == 0` check misses, and it is a
     * warning rather than an error because non-square frames are wrong for *this* art pack, not
     * wrong in principle.
     */
    @Test
    fun `a divisible but non-square grid is a warning, not an error`() {
        val diagnostic = assertNotNull(
            SpriteSheetGeometryValidator.validate(corpus()).firstOrNull { it.assetId == "character/oblong" },
        )
        assertEquals(Severity.Warning, diagnostic.severity)
        assertTrue("120x100" in diagnostic.message, diagnostic.message)
    }

    /** A sheet whose file is absent is `MissingFileValidator`'s defect, reported once. */
    @Test
    fun `an absent texture is not also a geometry error`() {
        assertNull(
            SpriteSheetGeometryValidator.validate(corpus()).firstOrNull { it.assetId == "character/absent" },
        )
        assertTrue(
            MissingFileValidator.validate(corpus()).any { it.assetId == "character/absent" },
            "it is still reported, just under UDEA0032",
        )
    }
}

/**
 * Only the header is read (issue #88).
 *
 * A PNG truncated immediately after its IHDR chunk still validates, which is the observable
 * consequence of reading twenty-four bytes instead of decoding the image. An `ImageIO.read`
 * implementation fails this test, which is the point of having it.
 */
class TruncatedPngTest {

    /** The committed 600x100 orc idle strip; a real PNG, not a generated one. */
    private fun orcIdle(): Path = TestPaths.exampleAssets
        .resolve("sprites").resolve("orc").resolve("Orc-Idle.png")

    @Test
    fun `a PNG truncated after the header still yields its dimensions`(@TempDir dir: Path) {
        val whole = orcIdle()
        val bytes = whole.readBytes()
        assertTrue(bytes.size > PngHeader.HEADER_BYTES * 4, "the source PNG must have a body to remove")

        val truncated = dir.resolve("Orc-Idle.png")
        truncated.writeBytes(bytes.copyOf(PngHeader.HEADER_BYTES))

        assertEquals(PngHeader.read(whole), PngHeader.read(truncated))
        assertEquals(ImageSize(600, 100), PngHeader.read(truncated))
    }

    /** One byte short of the header is not a guess; it is a `null`. */
    @Test
    fun `a file shorter than the header is not measured`(@TempDir dir: Path) {
        val short = dir.resolve("short.png")
        short.writeBytes(orcIdle().readBytes().copyOf(PngHeader.HEADER_BYTES - 1))
        assertNull(PngHeader.read(short))
    }

    /** A file that is not a PNG at all is not measured either, rather than measured wrongly. */
    @Test
    fun `a non-PNG is not measured`(@TempDir dir: Path) {
        val notPng = dir.resolve("thing.png")
        notPng.writeBytes(ByteArray(64) { 0x41 })
        assertNull(PngHeader.read(notPng))
    }
}
