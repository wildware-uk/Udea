package dev.wildware.udea.assets.compiler.atlas

import dev.wildware.udea.assets.compiler.TestPaths
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText

/**
 * The real corpus's shape with none of its pixels: 327 one-row sheets, 2269 frames, every frame
 * 100x100, drawn here rather than bought.
 *
 * ## Why this exists (issue #168)
 *
 * [MobaArt] needs two paid archives, so the atlas determinism tests ran on one machine and
 * skipped everywhere else while the build stayed green. This corpus is redistributable, costs
 * nothing but a few seconds of the first run, and exercises the property *exactly*, because the
 * property is about cardinality and ties rather than about what the pixels depict:
 *
 * - **2269 frames, every one 100x100.** [AtlasPacker] sorts by `(height desc, width desc,
 *   id asc)`, so with a single frame size the first two keys separate nothing and the id
 *   tie-break decides all 2269 placements on its own. That is the case a small fixture cannot
 *   reproduce - a handful of differently sized frames is totally ordered before the tie-break is
 *   consulted, and passes with the tie-break deleted.
 * - **327 sheets across 40 characters plus a projectile set**, mirroring what
 *   `scripts/extract-art.py` writes, so `sheetRanges`, frame contiguity and the six-page
 *   spillover are all exercised at full size.
 *
 * ## The pixels
 *
 * Each frame is a flat body colour with a border and a length-coded bar, all three colours
 * derived from an FNV-1a hash of `(sheet id, frame index)`. Three consequences, all deliberate:
 * no two frames are alike, so `a packed frame holds the pixels of the source frame it names` can
 * actually fail; the border makes an off-by-one blit visible to a person looking at the page; and
 * flat runs deflate to almost nothing, so 2269 frames land on disk as about a megabyte.
 *
 * Nothing here draws on a random number generator, a clock or a locale - the digits in a sheet
 * name are padded by hand rather than by `String.format`, whose `%d` is locale-sensitive. A
 * corpus for a determinism test that was itself non-deterministic would be worse than none.
 *
 * ## Caching
 *
 * The tree is written once under `udea-assets-compiler/build/tmp/synthetic-art/<fingerprint>/`
 * and reused by every later run, so the cost is paid on a fresh checkout and by `clean`, not by
 * every gate. The fingerprint names the shape *and* [PIXELS_VERSION]: **bump that whenever the
 * drawing changes**, or a stale tree from before the change is silently reused. Generation writes
 * to a staging directory and renames it into place after the marker file is written, so an
 * interrupted run cannot leave a half-corpus that looks complete.
 */
internal object SyntheticArt : SpriteCorpus {

    override val name: String = "synthetic-art"

    override val sampleCharacter: String = "sprites/champions/champion_00/"

    override val root: Path get() = tree

    /** Always: this corpus draws itself. That is the point of it. */
    override val available: Boolean get() = true

    /**
     * Bump when [drawFrame] or [colourOf] changes, or a cached tree from before the change is
     * reused and the new drawing is never exercised.
     */
    private const val PIXELS_VERSION: Int = 1

    /** The animation names `scripts/extract-art.py` produces, in the order it writes them. */
    private val ANIMATIONS = listOf(
        "attack01", "attack02", "attack03", "death", "hurt", "idle", "walk", "walk_alt",
    )

    /** Characters carrying the full [ANIMATIONS] list; the rest drop the last one. */
    private const val CHARACTERS_WITH_EVERY_ANIMATION: Int = 20

    /** The real corpus has a `sprites/projectiles/` set beside the champions. */
    private const val PROJECTILES: Int = 27

    /**
     * Frames per sheet, cycled so the corpus is not uniform, then corrected to hit
     * [CorpusShape.FRAMES] exactly. The variety is what makes `sheetRanges` and frame contiguity
     * worth checking; the exact total is what makes the tie-break the size issue #89 measured.
     */
    private val FRAME_COUNT_CYCLE = intArrayOf(4, 6, 8, 6, 10, 4, 8, 6, 10, 8)

    private const val MARKER = "corpus.complete"

    /** A 6px border: wide enough to see, narrow enough to leave the body colour dominant. */
    private const val BORDER = 6

    private const val BAR_TOP = 20
    private const val BAR_HEIGHT = 10

    private val tree: Path by lazy { materialise() }

    /** What every sheet is called and how many frames it holds, in the order they are written. */
    private fun plan(): List<SheetPlan> {
        val ids = ArrayList<String>(CorpusShape.SHEETS)
        for (character in 0 until CorpusShape.CHARACTERS) {
            val animations =
                if (character < CHARACTERS_WITH_EVERY_ANIMATION) ANIMATIONS else ANIMATIONS.dropLast(1)
            for (animation in animations) ids += "champions/champion_${pad(character)}/$animation"
        }
        for (projectile in 0 until PROJECTILES) ids += "projectiles/projectile_${pad(projectile)}"
        check(ids.size == CorpusShape.SHEETS) {
            "the plan names ${ids.size} sheets, not ${CorpusShape.SHEETS}"
        }

        val counts = frameCounts(ids.size, CorpusShape.FRAMES)
        return ids.mapIndexed { at, id -> SheetPlan(id, counts[at]) }
    }

    /**
     * [sheets] counts summing to exactly [frames].
     *
     * The cycle is walked, then the shortfall or excess is spread one frame at a time from the
     * front. Deterministic, and it terminates because every pass of the loop moves `remaining`
     * exactly one step closer to zero; the `check` below is what catches a shape that would need
     * to take a sheet's last frame away to balance.
     */
    private fun frameCounts(sheets: Int, frames: Int): IntArray {
        val counts = IntArray(sheets) { FRAME_COUNT_CYCLE[it % FRAME_COUNT_CYCLE.size] }
        var remaining = frames - counts.sum()
        var at = 0
        while (remaining != 0) {
            val step = if (remaining > 0) 1 else -1
            counts[at] += step
            remaining -= step
            at = (at + 1) % sheets
        }
        check(counts.all { it >= 1 }) { "a sheet ended up with no frames" }
        check(counts.sum() == frames) { "the counts sum to ${counts.sum()}, not $frames" }
        return counts
    }

    private fun materialise(): Path {
        val home = TestPaths.repoRoot.resolve("udea-assets-compiler/build/tmp/synthetic-art")
        val fingerprint = "v$PIXELS_VERSION-${CorpusShape.SHEETS}sheets-" +
            "${CorpusShape.FRAMES}frames-${CorpusShape.FRAME_SIZE}px"
        val corpus = home.resolve(fingerprint)
        if (corpus.resolve(MARKER).isRegularFile()) return corpus

        home.createDirectories()
        val staging = Files.createTempDirectory(home, "staging-")
        for (sheet in plan()) write(staging, sheet)
        staging.resolve(MARKER).writeText(fingerprint)
        return try {
            Files.move(staging, corpus, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: FileAlreadyExistsException) {
            // Another test JVM drew the same corpus first. Its bytes are ours by construction.
            staging.toFile().deleteRecursively()
            corpus
        }
    }

    private fun write(into: Path, sheet: SheetPlan) {
        val image = RgbaImage.blank(sheet.frames * CorpusShape.FRAME_SIZE, CorpusShape.FRAME_SIZE)
        for (frame in 0 until sheet.frames) drawFrame(image, sheet.id, frame)
        val file = into.resolve("${sheet.id}.png")
        file.parent.createDirectories()
        Files.write(file, Png.encode(image))
    }

    private fun drawFrame(sheet: RgbaImage, id: String, frame: Int) {
        val body = colourOf(id, frame, slot = 0)
        val border = colourOf(id, frame, slot = 1)
        val bar = colourOf(id, frame, slot = 2)
        // The bar's length codes the frame index, so two frames of one sheet differ in shape as
        // well as in colour and a swapped pair is visible rather than merely unequal.
        val barWidth = BORDER + 1 + frame * 2
        val size = CorpusShape.FRAME_SIZE
        val left = frame * size
        for (y in 0 until size) {
            for (x in 0 until size) {
                val onBorder = x < BORDER || y < BORDER || x >= size - BORDER || y >= size - BORDER
                val onBar = y in BAR_TOP until BAR_TOP + BAR_HEIGHT && x in BORDER until barWidth
                sheet.argb[y * sheet.width + left + x] = when {
                    onBorder -> border
                    onBar -> bar
                    else -> body
                }
            }
        }
    }

    /**
     * An opaque colour from FNV-1a over the sheet id, the frame and the slot.
     *
     * Every channel is lifted into `40..255` so that no frame comes out near-black, which would
     * make a page unreadable to the person the atlas PNGs are published for.
     */
    private fun colourOf(id: String, frame: Int, slot: Int): Int {
        var hash = FNV_OFFSET
        for (character in id) hash = (hash xor character.code) * FNV_PRIME
        hash = (hash xor frame) * FNV_PRIME
        hash = (hash xor slot) * FNV_PRIME
        val red = channel(hash ushr 16)
        val green = channel(hash ushr 8)
        val blue = channel(hash)
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun channel(bits: Int): Int = 40 + (bits and 0xFF) * (255 - 40) / 255

    private fun pad(value: Int): String = value.toString().padStart(2, '0')

    private class SheetPlan(val id: String, val frames: Int)

    private const val FNV_OFFSET: Int = -2128831035

    private const val FNV_PRIME: Int = 16777619
}
