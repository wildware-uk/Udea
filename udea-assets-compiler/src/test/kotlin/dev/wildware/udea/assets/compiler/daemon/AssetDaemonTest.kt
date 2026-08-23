package dev.wildware.udea.assets.compiler.daemon

import dev.wildware.udea.assets.AssetId
import dev.wildware.udea.assets.DeltaClassification
import dev.wildware.udea.assets.RestartReason
import dev.wildware.udea.assets.SoundCue
import dev.wildware.udea.assets.SpriteSheet
import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.UdeaRules
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #91's behaviour: what the daemon decides, and what it refuses to decide.
 *
 * Every test here asserts against the daemon's *state after the call*, not only against the value
 * it returned. "The reload was rejected" and "the running graph is unchanged" are two different
 * claims and only the second one is the promise an agent's broken edit rests on.
 */
class AssetDaemonTest {

    @Test
    fun `a value edit yields a delta naming only the asset that changed`() {
        val fixture = DaemonFixture("value-edit").writeBaseline()
        assertTrue(fixture.daemon.start().ok)
        val before = assertIs<SpriteSheet>(fixture.daemon.value("character/orc_idle"))
        assertEquals(0.02f, before.scale)

        val edited = fixture.write(
            "character/orc.udea.kts",
            """
            spriteSheet(name = "orc_idle", spritePath = "/sprites/orc/idle.png", rows = 1, columns = 6, scale = 0.05f)
            spriteSheet(name = "orc_walk", spritePath = "/sprites/orc/walk.png", rows = 1, columns = 8, scale = 0.02f)
            spriteAnimation(name = "orc_idle_anim", sheet = reference("character/orc_idle"))
            soundCue(name = "orc_hit", pitchVariance = 0.3f, volume = 1.0f, sounds = listOf("/sounds/orc/hit.ogg"))
            """,
        )

        val outcome = assertIs<ReloadOutcome.Applied>(fixture.daemon.reload(listOf(edited)))
        // Only the sheet: the other three assets in the same recompiled file are byte-for-byte the
        // values already in the graph, and a delta that named them would make "what changed" mean
        // "what was recompiled", which is a different and much less useful question.
        assertEquals(listOf(AssetId("character/orc_idle")), outcome.changedIds)
        assertEquals(0.05f, assertIs<SpriteSheet>(outcome.delta.changed.single().data).scale)

        // Until the caller has pushed the delta the daemon still answers with the old value.
        assertEquals(0.02f, assertIs<SpriteSheet>(fixture.daemon.value("character/orc_idle")).scale)
        fixture.daemon.commit()
        assertEquals(0.05f, assertIs<SpriteSheet>(fixture.daemon.value("character/orc_idle")).scale)
    }

    @Test
    fun `a syntax error is rejected and the last-good graph is untouched`() {
        val fixture = DaemonFixture("syntax-error").writeBaseline()
        assertTrue(fixture.daemon.start().ok)
        val generation = fixture.daemon.generation
        val idsBefore = fixture.daemon.ids

        val broken = fixture.write("character/orc.udea.kts", """soundCue(name = "orc_hit", volume = ???)""")
        val outcome = assertIs<ReloadOutcome.Rejected>(fixture.daemon.reload(listOf(broken)))

        assertTrue(outcome.diagnostics.any { it.severity == Severity.Error })
        assertEquals(idsBefore, fixture.daemon.ids)
        assertEquals(generation, fixture.daemon.generation)
        assertEquals(1.0f, assertIs<SoundCue>(fixture.daemon.value("character/orc_hit")).volume)
    }

    @Test
    fun `an unresolved reference is one diagnostic with a did-you-mean, however many referrers`() {
        val fixture = DaemonFixture("unresolved").writeBaseline()
        assertTrue(fixture.daemon.start().ok)

        // Five animations, all pointing at one misspelling of `character/orc_idle`.
        val edited = fixture.write(
            "character/orc.udea.kts",
            """
            spriteSheet(name = "orc_idle", spritePath = "/sprites/orc/idle.png", rows = 1, columns = 6, scale = 0.02f)
            spriteSheet(name = "orc_walk", spritePath = "/sprites/orc/walk.png", rows = 1, columns = 8, scale = 0.02f)
            spriteAnimation(name = "orc_idle_anim", sheet = reference("character/orc_idle"))
            soundCue(name = "orc_hit", pitchVariance = 0.3f, volume = 1.0f, sounds = listOf("/sounds/orc/hit.ogg"))
            repeat(5) { i -> spriteAnimation(name = "broken_${'$'}i", sheet = reference("character/orc_idel")) }
            """,
        )
        val outcome = assertIs<ReloadOutcome.Rejected>(fixture.daemon.reload(listOf(edited)))

        val errors = outcome.diagnostics.filter { it.severity == Severity.Error }
        assertEquals(1, errors.size, "five referrers of one bad id is one defect, not five: $errors")
        assertEquals(UdeaRules.UNRESOLVED_REFERENCE.id, errors.single().ruleId)
        assertTrue(
            "did you mean `character/orc_idle`?" in errors.single().message.lowercase(),
            "an agent must be able to self-correct in one turn: ${errors.single().message}",
        )
        // The surviving diagnostic names a referrer and the field it was written in, which is
        // where the author edits. It does *not* say "referenced by 5 assets" any more: that
        // phrasing belonged to `daemon/AssetGraphValidator`, which aggregated the referrers
        // itself. `UnresolvedReferenceValidator` reports every site honestly and `DiagnosticSink`
        // collapses them, so the count is lost and the location is kept - the deliberate cost of
        // having one implementation of this check instead of two.
        assertTrue(
            "references `character/orc_idel` from its `sheet`" in errors.single().message,
            "the diagnostic must name a referrer and its field: ${errors.single().message}",
        )
    }

    @Test
    fun `a new declaration requires a restart rather than half-applying`() {
        val fixture = DaemonFixture("added").writeBaseline()
        assertTrue(fixture.daemon.start().ok)

        val edited = fixture.write(
            "character/orc.udea.kts",
            """
            spriteSheet(name = "orc_idle", spritePath = "/sprites/orc/idle.png", rows = 1, columns = 6, scale = 0.05f)
            spriteSheet(name = "orc_walk", spritePath = "/sprites/orc/walk.png", rows = 1, columns = 8, scale = 0.02f)
            spriteSheet(name = "orc_die", spritePath = "/sprites/orc/die.png", rows = 1, columns = 4, scale = 0.02f)
            spriteAnimation(name = "orc_idle_anim", sheet = reference("character/orc_idle"))
            soundCue(name = "orc_hit", pitchVariance = 0.3f, volume = 1.0f, sounds = listOf("/sounds/orc/hit.ogg"))
            """,
        )
        val outcome = assertIs<ReloadOutcome.RequiresRestart>(fixture.daemon.reload(listOf(edited)))

        assertEquals(listOf(AssetId("character/orc_die")), outcome.changes.map { it.id })
        assertEquals(RestartReason.AssetAdded.code, outcome.changes.single().code)
        assertEquals(DeltaClassification.RequiresRestart.CODE, outcome.code)
        // The `scale = 0.05f` in the same edit is a perfectly good value change and is still not
        // applied: a mixed delta half-applied leaves the running graph in a state no build produced.
        assertEquals(0.02f, assertIs<SpriteSheet>(fixture.daemon.value("character/orc_idle")).scale)
    }

    @Test
    fun `a deleted declaration requires a restart`() {
        val fixture = DaemonFixture("removed").writeBaseline()
        assertTrue(fixture.daemon.start().ok)
        val deleted = fixture.delete("level/arena.udea.kts")

        val outcome = assertIs<ReloadOutcome.RequiresRestart>(fixture.daemon.reload(listOf(deleted)))
        assertEquals(listOf(AssetId("level/arena")), outcome.changes.map { it.id })
        assertEquals(RestartReason.AssetRemoved.code, outcome.changes.single().code)
        assertNotNull(fixture.daemon.value("level/arena"), "the removal must not be applied")
    }

    @Test
    fun `changing a blueprint's component list requires a restart, not a value swap`() {
        val fixture = DaemonFixture("blueprint-shape").writeBaseline()
        assertTrue(fixture.daemon.start().ok)

        val edited = fixture.write(
            "blueprint/player.udea.kts",
            """
            blueprint(name = "player", components = listOf("dev.wildware.moba.Health", "dev.wildware.moba.Mana"))
            """,
        )
        val outcome = assertIs<ReloadOutcome.RequiresRestart>(fixture.daemon.reload(listOf(edited)))
        assertEquals(StructuralChange.BLUEPRINT_COMPONENTS, outcome.changes.single().code)
        assertTrue("Mana" in outcome.changes.single().detail)
    }

    @Test
    fun `a kind the DSL has no runtime type for still gets a value, through the bundle codecs`() {
        val fixture = DaemonFixture("unpackable").writeBaseline()
        fixture.write("config.udea.kts", """gameConfig(defaultCharacter = reference("blueprint/player"))""")
        fixture.write("bestiary/orc.udea.kts", """asset("beast", "orc", "bite" to 7)""")
        assertTrue(fixture.daemon.start().ok)

        // Both of these used to be `null`. `daemon/AssetPacker` was a hand-written `when` over
        // six DSL words: it had no case for `gameConfig` at all, and nothing at all for a kind
        // declared through `asset(...)`. The daemon now packs through `GraphPacker` and reads
        // back through `BundleReader`, so it holds exactly the values a shipped `.udeapak`
        // holds - `GameConfig` for the first, an opaque record for the second - and the two
        // cannot disagree about an id, because there is only one writer and one reader.
        assertNotNull(fixture.daemon.value("config"))
        assertNotNull(fixture.daemon.value("bestiary/orc"))
        assertTrue("config" in fixture.daemon.ids)

        val edited = fixture.write(
            "config.udea.kts",
            """
            gameConfig(defaultCharacter = reference("blueprint/player"))
            // a comment, so the file's content hash moves and the declaration is recompiled
            """,
        )
        // Recompiling it produces an identical declaration, so there is nothing to say.
        assertIs<ReloadOutcome.NoChange>(fixture.daemon.reload(listOf(edited)))
    }

    @Test
    fun `a reload naming a file the daemon does not hold is not a change`() {
        val fixture = DaemonFixture("unknown-file").writeBaseline()
        assertTrue(fixture.daemon.start().ok)
        assertIs<ReloadOutcome.NoChange>(
            fixture.daemon.reload(listOf(fixture.assetRoot.resolve("nothing/here.udea.kts"))),
        )
    }

    @Test
    fun `rollback discards a decided reload so a failed push cannot be committed later`() {
        val fixture = DaemonFixture("rollback").writeBaseline()
        assertTrue(fixture.daemon.start().ok)
        val edited = fixture.write(
            "character/orc.udea.kts",
            """
            spriteSheet(name = "orc_idle", spritePath = "/sprites/orc/idle.png", rows = 1, columns = 6, scale = 0.9f)
            spriteSheet(name = "orc_walk", spritePath = "/sprites/orc/walk.png", rows = 1, columns = 8, scale = 0.02f)
            spriteAnimation(name = "orc_idle_anim", sheet = reference("character/orc_idle"))
            soundCue(name = "orc_hit", pitchVariance = 0.3f, volume = 1.0f, sounds = listOf("/sounds/orc/hit.ogg"))
            """,
        )
        assertIs<ReloadOutcome.Applied>(fixture.daemon.reload(listOf(edited)))

        fixture.daemon.rollback()
        fixture.daemon.commit()
        assertEquals(
            0.02f,
            assertIs<SpriteSheet>(fixture.daemon.value("character/orc_idle")).scale,
            "commit() after rollback() must not install the discarded graph",
        )
        assertEquals(0, fixture.daemon.generation)
    }
}
