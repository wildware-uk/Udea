package dev.wildware.udea.assets.compiler.validate

import dev.wildware.udea.assets.compiler.Ref
import dev.wildware.udea.assets.compiler.ResFile
import dev.wildware.udea.diagnostics.DidYouMean
import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.UdeaDiagnostic
import dev.wildware.udea.diagnostics.UdeaRule
import kotlin.io.path.isRegularFile

/**
 * A declaration names a resource file that is not in the asset root.
 *
 * Sprite textures, sound files, and — through `AssetScope.resource` — anything a generic
 * `asset(...)` declaration marks as a path. The validator does not know which kinds have path
 * fields and deliberately cannot: it walks every value looking for a
 * [dev.wildware.udea.assets.compiler.ResFile], which the DSL signature stamped. A kind added
 * later is covered without editing this file, which is the only kind of coverage that survives.
 *
 * ### The bug it closes
 *
 * `orc_elite.udea.kts` wrote `spritePath = "/sprites/orc_elite/orc_elite_idle.png"` while the
 * loader registered the same file under the stripped key `sprites/...`, so the lookup missed
 * and the texture was loaded twice under two names. `ResFile` makes both spellings one value;
 * this rule is what then says out loud that the one value is not on disk.
 *
 * ### The did-you-mean
 *
 * Suggested from every file actually under the asset root, not from the missing path's parent
 * directory: the expensive typo is the one in the *directory*, where a parent-directory listing
 * has nothing to offer. Matching is case-insensitive (that is [DidYouMean]'s policy), which is
 * exactly the class of bug that passes on Windows and fails in CI.
 */
public object MissingFileValidator : AssetValidator {

    override val rules: List<UdeaRule> = listOf(AssetValidationRules.MISSING_FILE)

    override fun validate(context: ValidationContext): List<UdeaDiagnostic> = context.fileSites
        .filter { it.path.isMalformed || !context.fileOf(it.path).isRegularFile() }
        // One diagnostic per declaration, not per path. Pass 2 gives a span per *declaration*
        // and not per argument, so a sound cue with three missing files would be three
        // diagnostics at one span - which `DiagnosticSink` would then dedupe down to one and
        // throw the other two names away. Saying all three in one message keeps them.
        .groupBy { it.owner.id }
        .toSortedMap()
        .map { (ownerId, sites) ->
            val owner = sites.first().owner
            val details = sites.joinToString("; ") { site ->
                val suggestion = if (site.path.isMalformed) {
                    null
                } else {
                    DidYouMean.suggest(site.path.value, context.resourceFiles)
                }
                buildString {
                    append("`${site.field}` names `${site.path}`")
                    when {
                        site.path.isMalformed ->
                            append(", which is not a path inside the asset root")
                        suggestion != null -> append(" - did you mean `$suggestion`?")
                        else -> Unit
                    }
                }
            }
            AssetValidationRules.MISSING_FILE.diagnostic(
                message = "`$ownerId` names ${sites.size} file(s) that are not under the asset " +
                    "root ${context.assetRoot}: $details",
                span = context.spanFor(owner),
                assetId = ownerId,
            )
        }
}

/**
 * A sprite sheet's declared grid does not fit the image it names.
 *
 * `TextureRegion.split` divides a texture by an author-supplied column count that nothing has
 * ever checked (`common/.../animationSets.kt`), so a wrong count does not fail — it hands back
 * frames sliced across two drawings, and the character animates through half-frames for the
 * life of the build. This reads twenty-four bytes of the PNG and compares.
 *
 * ### Two findings, one rule id
 *
 * - **Error**: the grid does not divide the image (`width % columns != 0`). There is no reading
 *   of the sheet under which that is correct.
 * - **Warning**: the grid divides but the frames are not square. Every sheet in
 *   `docs/art-assets.md` is "a horizontal strip of 100x100 frames", so a 600x100 sheet declared
 *   with 5 columns divides cleanly into 120x100 frames and is still wrong — but only wrong
 *   *for this art pack*, so it is a warning rather than a rule the engine claims in general.
 *   This is the case plain divisibility misses, and saying so is the point.
 *
 * They share an id because they share a fix — correct `rows`/`columns`, or replace the image.
 *
 * ### What it does not do
 *
 * A sheet whose file is absent is skipped: that is [MissingFileValidator]'s `UDEA0032` and
 * reporting geometry against a file that is not there would be two diagnostics for one defect.
 * A non-PNG texture is skipped entirely — this validator reads the PNG header and nothing else,
 * so for a `.jpg` or an atlas it has no opinion, and it says so instead of guessing.
 */
public object SpriteSheetGeometryValidator : AssetValidator {

    /** The DSL word whose declarations carry a grid. */
    public const val KIND: String = "spriteSheet"

    override val rules: List<UdeaRule> = listOf(AssetValidationRules.SHEET_GEOMETRY)

    override fun validate(context: ValidationContext): List<UdeaDiagnostic> =
        context.graph.assets.values
            .filter { it.kind == KIND }
            .sortedBy { it.id }
            .flatMap { sheet ->
                val texture = sheet.fields["spritePath"] as? ResFile ?: return@flatMap emptyList()
                val columns = sheet.fields["columns"] as? Int ?: return@flatMap emptyList()
                val rows = sheet.fields["rows"] as? Int ?: return@flatMap emptyList()

                if (columns <= 0 || rows <= 0) {
                    return@flatMap listOf(
                        AssetValidationRules.SHEET_GEOMETRY.diagnostic(
                            message = "sprite sheet `${sheet.id}` declares a ${columns}x$rows grid, " +
                                "which holds no frames",
                            span = context.spanFor(sheet),
                            assetId = sheet.id,
                        ),
                    )
                }

                val file = context.fileOf(texture)
                if (!file.isRegularFile()) return@flatMap emptyList()
                val size = PngHeader.read(file) ?: return@flatMap emptyList()

                val fits = size.width % columns == 0 && size.height % rows == 0
                val frameWidth = size.width / columns
                val frameHeight = size.height / rows
                when {
                    !fits -> listOf(
                        AssetValidationRules.SHEET_GEOMETRY.diagnostic(
                            message = buildString {
                                append("sprite sheet `${sheet.id}` declares $columns columns x $rows ")
                                append("rows, but `$texture` is ${size.width}x${size.height}, which ")
                                append("that grid does not divide (")
                                append("${size.width} % $columns = ${size.width % columns}, ")
                                append("${size.height} % $rows = ${size.height % rows})")
                                squareColumns(size, rows)?.let {
                                    append(". Square frames would be $it columns x $rows rows")
                                }
                            },
                            span = context.spanFor(sheet),
                            assetId = sheet.id,
                        ),
                    )
                    frameWidth != frameHeight -> listOf(
                        AssetValidationRules.SHEET_GEOMETRY.diagnostic(
                            message = buildString {
                                append("sprite sheet `${sheet.id}` divides `$texture` ")
                                append("(${size.width}x${size.height}) into ${frameWidth}x$frameHeight ")
                                append("frames, which are not square. Every sheet in ")
                                append("docs/art-assets.md is a horizontal strip of square frames")
                                squareColumns(size, rows)?.let {
                                    append("; $it columns would give square frames")
                                }
                            },
                            span = context.spanFor(sheet),
                            assetId = sheet.id,
                            severity = Severity.Warning,
                        ),
                    )
                    else -> emptyList()
                }
            }

    /** The column count that would make the frames square, or null when none does. */
    private fun squareColumns(size: ImageSize, rows: Int): Int? {
        if (rows <= 0 || size.height % rows != 0) return null
        val frameHeight = size.height / rows
        if (frameHeight <= 0 || size.width % frameHeight != 0) return null
        return size.width / frameHeight
    }
}

/**
 * An animation notify names a frame the animation does not have.
 *
 * The defect this replaces is silent, which is what makes it worth a rule: the lookup at
 * `animationSets.kt:60` matches a notify by name and simply finds nothing, so `animNotify(9)`
 * against a seven-frame sheet is a sword swing that never connects, with no error anywhere and
 * nothing to grep for.
 *
 * ### It counts declared frames, not pixels
 *
 * The frame count is the sheet's `rows * columns` as declared, not what the PNG measures. If
 * those disagree that is [SpriteSheetGeometryValidator]'s `UDEA0033`, and counting from the
 * image here would make one wrong `columns` produce a second, confusing diagnostic about a
 * notify the author wrote correctly.
 *
 * A notify whose sheet reference does not resolve, or resolves to something that is not a
 * sprite sheet, is left to `UDEA0004` and `UDEA0013`.
 */
public object AnimationNotifyValidator : AssetValidator {

    /** The DSL word whose declarations carry notifies. */
    public const val KIND: String = "spriteAnimation"

    override val rules: List<UdeaRule> = listOf(AssetValidationRules.NOTIFY_RANGE)

    override fun validate(context: ValidationContext): List<UdeaDiagnostic> =
        context.graph.assets.values
            .filter { it.kind == KIND }
            .sortedBy { it.id }
            .flatMap { animation ->
                val notifies = animation.fields["notifies"] as? Map<*, *> ?: return@flatMap emptyList()
                if (notifies.isEmpty()) return@flatMap emptyList()
                val sheetRef = animation.fields["sheet"] as? Ref ?: return@flatMap emptyList()
                val sheet = context.resolve(sheetRef.id) ?: return@flatMap emptyList()
                if (sheet.kind != SpriteSheetGeometryValidator.KIND) return@flatMap emptyList()

                val columns = sheet.fields["columns"] as? Int ?: return@flatMap emptyList()
                val rows = sheet.fields["rows"] as? Int ?: return@flatMap emptyList()
                val frameCount = columns * rows

                // One diagnostic per animation listing every out-of-range notify, for the same
                // reason `MissingFileValidator` groups: there is one span per declaration, and
                // two diagnostics at one span under one rule id are deduped to one by the sink.
                val offenders = notifies.entries
                    .sortedBy { it.key.toString() }
                    .mapNotNull { (name, value) ->
                        val frame = value as? Int ?: return@mapNotNull null
                        if (frame in 0 until frameCount) null else "`$name` on frame $frame"
                    }
                if (offenders.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        AssetValidationRules.NOTIFY_RANGE.diagnostic(
                            message = "animation `${animation.id}` has ${offenders.size} notify " +
                                "(${offenders.joinToString(", ")}) outside its frame range: its " +
                                "sheet `${sheet.id}` has $frameCount frames ($columns columns x " +
                                "$rows rows), so the last frame is ${frameCount - 1}",
                            span = context.spanFor(animation),
                            assetId = animation.id,
                        ),
                    )
                }
            }
}
