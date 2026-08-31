package dev.wildware.udea.assets.compiler.atlas

import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

/**
 * The shape issue #89 chose for the atlas determinism tests, in one place.
 *
 * These are the real Tiny RPG corpus's numbers, and [SyntheticArt] is built to them, so the two
 * corpora cannot drift apart by someone editing one of them. The tests assert them against
 * whichever corpus they are handed, which is what stops a substitute quietly shrinking into a
 * fixture that a broken tie-break would survive.
 */
internal object CorpusShape {

    const val SHEETS: Int = 327

    const val FRAMES: Int = 2269

    /** Every frame is this square. The single size is the whole reason the corpus has to be big. */
    const val FRAME_SIZE: Int = 100

    const val CHARACTERS: Int = 40

    /** The id prefix every character's sheets sit under, in both corpora. */
    const val CHAMPIONS: String = "sprites/champions/"
}

/** One sheet on disk, with its frame size read out of the image rather than declared. */
internal data class ScannedSheet(
    val input: SheetInput,
    val frameWidth: Int,
    val frameHeight: Int,
)

/**
 * A corpus of sprite sheets for the atlas determinism tests to pack.
 *
 * Two implementations, and the pair is the whole of issue #168. [MobaArt] is the real Tiny RPG
 * corpus, which only a machine holding the two paid archives has. [SyntheticArt] has the same
 * *shape* - 327 one-row strips, 2269 frames, every frame 100x100 - and is drawn by this test
 * suite out of nothing, so the property is checked on every clone rather than on one laptop.
 *
 * ## Why the shape is the thing that has to match
 *
 * [AtlasPacker] orders frames by `(height desc, width desc, id asc)`. When every frame is the
 * same size the first two keys separate nothing at all, so the id tie-break alone decides where
 * all 2269 frames land, and a packer that lost that tie-break would place them in whatever order
 * they arrived - which is `Files.walk`'s, and therefore the filesystem's. A fixture of a handful
 * of differently sized frames is totally ordered by height before the tie-break is ever
 * consulted, so it would pass with the tie-break deleted. That is what issue #89 meant by
 * choosing the real corpus over a toy one, and it is why the substitute is the same size rather
 * than a smaller one.
 */
internal interface SpriteCorpus {

    /** Named in assertion messages and in report filenames, so a failure says which corpus. */
    val name: String

    /** The directory the sheets live under. */
    val root: Path

    /** Whether the corpus can be read at all. [SyntheticArt] draws itself, so it always can. */
    val available: Boolean get() = root.isDirectory()

    /** What a skip says when [available] is false. */
    val unavailable: String get() = "$name is absent"

    /**
     * The id prefix of one character, whose sheets are small enough for a pixel-for-pixel check.
     *
     * A prefix rather than a hardcoded `sprites/champions/archer/` because the two corpora do not
     * share character names, and a shared test body must not know which one it is running against.
     */
    val sampleCharacter: String

    /** Every PNG under [root]. Two calls are two directory walks, which several tests rely on. */
    fun scan(): List<ScannedSheet> = SpriteSheetScan.of(root)

    fun sheets(): List<SheetInput> = scan().map { it.input }

    /** The sample character's sheets, in id order. */
    fun sampleCharacterSheets(): List<SheetInput> = sheets().filter { it.id.startsWith(sampleCharacter) }
}

/**
 * Reads a sprite tree into [ScannedSheet]s.
 *
 * The column count is read from the image: this art is horizontal strips of square frames, so
 * `width / height` is the frame count. Reading it rather than declaring it means a sheet whose
 * dimensions change is packed correctly instead of being packed wrong and silently blitting the
 * neighbouring frame.
 */
internal object SpriteSheetScan {

    @OptIn(ExperimentalPathApi::class)
    fun of(root: Path): List<ScannedSheet> = root.walk()
        .filter { it.extension.equals("png", ignoreCase = true) }
        .map { file ->
            val id = "sprites/" + file.relativeTo(root).toString().replace('\\', '/').removeSuffix(".png")
            val (width, height) = dimensionsOf(file)
            check(height > 0 && width >= height && width % height == 0) {
                "'$id' is ${width}x$height, which is not a horizontal strip of square frames"
            }
            val columns = width / height
            ScannedSheet(
                input = SheetInput(id = id, file = file, columns = columns, rows = 1),
                frameWidth = width / columns,
                frameHeight = height,
            )
        }
        // Sorted here so a caller that does *not* shuffle still gets a defined order; the
        // packer sorts again, which is what the reversed-order tests prove.
        .sortedBy { it.input.id }
        .toList()

    /**
     * Width and height from the PNG header, without decoding a single pixel.
     *
     * Every test in the gate scans its corpus afresh - deliberately, since two separate walks are
     * what `two packs of the whole art corpus produce identical atlas pages` is about - and
     * `ImageIO.read` would decode every frame of it each time, tens of seconds of work to learn
     * two numbers per file. The reader's `getWidth`/`getHeight` read `IHDR` and stop, and a file
     * that is not a decodable image still fails here, because no reader claims it.
     */
    private fun dimensionsOf(file: Path): Pair<Int, Int> {
        ImageIO.createImageInputStream(file.toFile()).use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            check(readers.hasNext()) { "$file is not a readable image" }
            val reader = readers.next()
            try {
                reader.input = stream
                return reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    }
}
