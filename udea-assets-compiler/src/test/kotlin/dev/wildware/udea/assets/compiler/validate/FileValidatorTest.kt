package dev.wildware.udea.assets.compiler.validate

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Files on disk, and notify frames inside a sheet.
 */
class FileValidatorTest {

    /**
     * The leading-slash spelling and the stripped one are one path, and the missing one is named.
     *
     * `orc_elite.udea.kts` wrote `"/sprites/..."` while the loader keyed on `"sprites/..."`, so
     * the same file lived under two names and the lookup missed. Both spellings normalise here,
     * and then either the file exists or the build says which one does not — with a suggestion
     * measured against the files that are actually there.
     */
    @Test
    fun `a missing sprite path is reported with a did-you-mean over real files`() {
        val context = ValidationFixture.withArt(
            "missing-file",
            "character/orc.udea.kts" to """
                spriteSheet(name = "ok", spritePath = "/sprites/orc/Orc-Idle.png", columns = 6)
                spriteSheet(name = "typo", spritePath = "sprites/orc/Orc-Idl.png", columns = 6)
            """,
        )

        val diagnostics = MissingFileValidator.validate(context)
        val diagnostic = assertNotNull(diagnostics.singleOrNull(), "only `typo` is missing: $diagnostics")
        assertEquals(AssetValidationRules.MISSING_FILE.id, diagnostic.ruleId)
        assertEquals("character/typo", diagnostic.assetId)
        assertTrue("did you mean `sprites/orc/Orc-Idle.png`?" in diagnostic.message, diagnostic.message)
        assertNotNull(diagnostic.span)
    }

    /**
     * Three missing sounds on one cue are one diagnostic naming all three.
     *
     * Not a style choice: pass 2 gives one span per *declaration*, so three diagnostics would
     * share a span and `DiagnosticSink` would dedupe two of them away — an author would be told
     * about one missing file, fix it, and be told about the next one on the next build.
     */
    @Test
    fun `several missing files on one declaration are one diagnostic naming each`() {
        val context = ValidationFixture.context(
            "missing-sounds",
            "sounds/cues.udea.kts" to """
                soundCue(name = "hit", sounds = listOf("/sounds/a.ogg", "/sounds/b.ogg", "/sounds/c.ogg"))
            """,
        )

        val diagnostic = assertNotNull(MissingFileValidator.validate(context).singleOrNull())
        assertTrue("3 file(s)" in diagnostic.message, diagnostic.message)
        for (name in listOf("sounds/a.ogg", "sounds/b.ogg", "sounds/c.ogg")) {
            assertTrue(name in diagnostic.message, "$name is missing from: ${diagnostic.message}")
        }
    }

    /**
     * A path that escapes the asset root is reported rather than resolved.
     *
     * `ResFile` deliberately does not throw on one — a script that threw during pass 2 would cost
     * the author every other asset in the file — so the diagnostic is the only thing standing
     * between a `..` and a loader reading outside the pack.
     */
    @Test
    fun `a path that escapes the asset root is reported, not resolved`() {
        val context = ValidationFixture.context(
            "escaping-path",
            "character/escape.udea.kts" to """
                spriteSheet(name = "outside", spritePath = "../../../secrets.png", columns = 1)
            """,
        )

        val diagnostic = assertNotNull(MissingFileValidator.validate(context).singleOrNull())
        assertTrue("not a path inside the asset root" in diagnostic.message, diagnostic.message)
    }

    /**
     * `animNotify(9, ...)` against a seven-frame sheet fails naming the sheet and its count.
     *
     * The failure it replaces is silent: the lookup matches a notify by name and simply finds
     * nothing, so the sword swing never connects and no error is raised anywhere.
     */
    private fun notifyCorpus() = ValidationFixture.context(
        "notify",
        "character/anim.udea.kts" to """
            spriteSheet(name = "attack_sheet", spritePath = "sprites/attack.png", columns = 7)
            spriteAnimation(
                name = "attack",
                sheet = reference("character/attack_sheet"),
                notifies = mapOf("hit" to 9, "swoosh" to 2, "recover" to -1),
            )
            spriteAnimation(
                name = "fine",
                sheet = reference("character/attack_sheet"),
                notifies = mapOf("hit" to 6),
            )
        """,
    )

    @Test
    fun `a notify past the last frame names the sheet and its frame count`() {
        val context = notifyCorpus()

        val diagnostic = assertNotNull(
            AnimationNotifyValidator.validate(context).singleOrNull(),
            "`fine` is in range and must not be reported",
        )
        assertEquals(AssetValidationRules.NOTIFY_RANGE.id, diagnostic.ruleId)
        assertEquals("character/attack", diagnostic.assetId)
        assertTrue("`character/attack_sheet` has 7 frames" in diagnostic.message, diagnostic.message)
        assertTrue("the last frame is 6" in diagnostic.message, diagnostic.message)
        assertTrue("`hit` on frame 9" in diagnostic.message, diagnostic.message)
        assertTrue("`recover` on frame -1" in diagnostic.message, "a negative frame is out of range too")
        assertTrue("swoosh" !in diagnostic.message, "frame 2 is in range: ${diagnostic.message}")
    }

    /**
     * The frame count comes from the declared grid, not from the image.
     *
     * A sheet whose `columns` disagrees with its PNG is `UDEA0033`; counting frames from the
     * image here would turn one wrong `columns` into a second diagnostic about a notify the
     * author wrote correctly. The fixture's texture does not exist at all, and the notify check
     * still works.
     */
    @Test
    fun `the frame count is the declared grid, so a missing texture does not silence the check`() {
        val context = notifyCorpus()
        assertEquals(1, AnimationNotifyValidator.validate(context).size)
        assertTrue(MissingFileValidator.validate(context).isNotEmpty(), "the texture really is absent")
    }
}

/**
 * A `.udea.kts` may not read a clock or an unseeded random (issue #88).
 */
class DeterminismTest {

    private fun randomCorpus() = ValidationFixture.context(
        "determinism",
        "level/test_level.udea.kts" to """
            import kotlin.random.Random

            // "Random" in a comment and in a string is not a name reference: "Random"
            val jitter = Random.nextFloat()

            blueprint(name = "spawn", components = listOf("ai"))
        """,
    )

    @Test
    fun `an unseeded random in a level script is flagged with its line`() {
        val context = randomCorpus()

        val diagnostic = assertNotNull(
            DeterminismValidator.validate(context).singleOrNull(),
            "one banned symbol, one diagnostic",
        )
        assertEquals(AssetValidationRules.NONDETERMINISTIC_ASSET.id, diagnostic.ruleId)
        assertTrue("kotlin.random.Random" in diagnostic.message, diagnostic.message)

        val span = assertNotNull(diagnostic.span)
        assertEquals(4, span.startLine, "the use, not the import, and not the comment")
        assertTrue(span.path.endsWith("level/test_level.udea.kts"), span.path)
    }

    /**
     * There is no `Fix`, and that is deliberate.
     *
     * `Fix` is "a machine-applicable repair, when one is unambiguous". This engine has no seeded
     * random for a script to use, so a fix naming one would be a fix an agent applies to get an
     * unresolved reference. When a seeded source exists, this assertion is what has to change.
     */
    @Test
    fun `no fix is offered, because no seeded replacement exists yet`() {
        assertEquals(null, DeterminismValidator.validate(randomCorpus()).single().fix)
    }

    /** A clock is the same defect as a random: the pack differs between two builds. */
    @Test
    fun `a clock read is flagged as its qualified name, not as a bare member`() {
        val context = ValidationFixture.context(
            "determinism-clock",
            "level/clock.udea.kts" to """
                class Stopwatch { fun nanoTime(): Long = 0L }

                val innocent = Stopwatch().nanoTime()
                val stamp = System.currentTimeMillis()

                blueprint(name = "clock", components = listOf(innocent.toString(), stamp.toString()))
            """,
        )

        val diagnostic = assertNotNull(DeterminismValidator.validate(context).singleOrNull())
        assertTrue("System.currentTimeMillis" in diagnostic.message, diagnostic.message)
    }
}
