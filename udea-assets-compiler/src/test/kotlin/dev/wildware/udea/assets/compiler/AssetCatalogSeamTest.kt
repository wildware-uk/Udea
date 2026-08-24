package dev.wildware.udea.assets.compiler

import dev.wildware.udea.assets.AssetData
import dev.wildware.udea.assets.Blueprint
import dev.wildware.udea.assets.Character
import dev.wildware.udea.assets.Effect
import dev.wildware.udea.assets.GameConfig
import dev.wildware.udea.assets.GameplayEffect
import dev.wildware.udea.assets.Level
import dev.wildware.udea.assets.SoundCue
import dev.wildware.udea.assets.SpriteAnimation
import dev.wildware.udea.assets.SpriteSheet
import dev.wildware.udea.diagnostics.Severity
import dev.wildware.udea.diagnostics.assets.AssetCatalog
import dev.wildware.udea.diagnostics.assets.AssetCatalogDecode
import dev.wildware.udea.diagnostics.assets.AssetCatalogJson
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Phase 2 seam: producer, format and consumer speak **one** vocabulary (integration wave).
 *
 * Three modules have to agree about what an asset "kind" is, and before [AssetKind] they did
 * not: `udea-assets` has Kotlin types, this module had DSL function names, and
 * `udea-compiler-plugin` resolves an `AssetCatalogEntry.kindFqn` through a `ClassId`. Only the
 * last one can answer the checker's question, so it is the one that wins, and this test is what
 * says the producer really speaks it rather than something that merely reads like it.
 *
 * Encoding and decoding both go through `udea-diagnostics`, the leaf module the K2 checker also
 * uses - so "the two sides agree on the bytes" is structural here and is asserted end to end in
 * `udea-compiler-plugin`'s own suite against a real compilation.
 */
class AssetCatalogSeamTest {

    private fun graph(): AssetGraph {
        val cache = TestPaths.scratch("catalog-seam-cache")
        val result = AssetCompiler(
            repoRoot = TestPaths.repoRoot,
            assetRoot = Fixtures.assetRoot,
            scriptClasspath = TestPaths.compilerClasspath,
            cacheDirectory = cache,
        ).compile(Fixtures.scripts())
        assertEquals(emptyList(), result.diagnostics.filter { it.severity == Severity.Error })
        return result.graph
    }

    @Test
    fun `every publishable kind is the qualified name of a real AssetData`() {
        val graph = graph()
        val export = graph.toCatalog()

        // Not a hand-written expectation of strings: each is read off the class, so a rename in
        // `udea-assets` moves both sides at once and this test cannot rot into a lie.
        val expected = mapOf(
            "character/orc" to Character::class,
            "character/orc_idle" to SpriteAnimation::class,
            "character/orc_idle_sheet" to SpriteSheet::class,
            "character/orc_walk_sheet" to SpriteSheet::class,
            "character/orc_attack_cue" to SoundCue::class,
            "character/goblin_spawn" to Blueprint::class,
            "level/spawner_0" to Blueprint::class,
            "level/test_level" to Level::class,
            "config" to GameConfig::class,
        )
        for ((id, type) in expected) {
            val entry = assertNotNull(
                export.catalog.resolve(id),
                "$id is not in the catalog; the producer published no kind for it",
            )
            assertEquals(type.qualifiedName, entry.kindFqn, "wrong kind published for $id")
        }

        // And every entry names a class that is actually loadable - which is precisely what the
        // consumer does with `ClassId.topLevel(FqName(kindFqn))`. A kind nothing can resolve is
        // a silent case in the checker by contract, so an unloadable name here would not be a
        // red build downstream; it would be validation that quietly stopped happening.
        for (entry in export.catalog.entries) {
            val loaded = Class.forName(entry.kindFqn)
            assertTrue(
                AssetData::class.java.isAssignableFrom(loaded),
                "${entry.kindFqn} (id '${entry.id}') is not an AssetData",
            )
        }
    }

    /**
     * The corpus happens not to declare a `spriteAnimation`, and a mapping only the corpus
     * covers is a mapping that is wrong the day the corpus changes. Every declaration function
     * is therefore driven directly, so the seam is asserted for the DSL rather than for five
     * files that use part of it.
     */
    @Test
    fun `every declaration function publishes the kind it says it does`() {
        val scope = AssetScope(idPrefix = "fixture", defaultName = "fixture")
        scope.spriteSheet(name = "sheet", spritePath = "sprites/a.png")
        scope.spriteAnimation(name = "anim", sheet = scope.reference("fixture/sheet"))
        scope.spriteAnimationSet(name = "set", animations = listOf(scope.reference("fixture/anim")))
        scope.soundCue(name = "cue")
        scope.blueprint(name = "bp")
        scope.level(name = "lvl")
        scope.gameConfig(name = "cfg", defaultCharacter = scope.reference("fixture/bp"))
        scope.character(name = "ch")
        scope.gameplayEffect(name = "ge", effectDuration = scope.instant())
        scope.effect(
            name = "fx",
            animationSet = scope.reference("fixture/set"),
            animation = "heal",
            duration = 1f,
        )
        scope.asset("somethingAGameInvented", "custom")

        val byId = scope.assets.associateBy { it.id }
        assertEquals(SpriteSheet::class.qualifiedName, byId.getValue("fixture/sheet").kindFqn)
        assertEquals(SpriteAnimation::class.qualifiedName, byId.getValue("fixture/anim").kindFqn)
        assertEquals(SoundCue::class.qualifiedName, byId.getValue("fixture/cue").kindFqn)
        assertEquals(Blueprint::class.qualifiedName, byId.getValue("fixture/bp").kindFqn)
        assertEquals(Level::class.qualifiedName, byId.getValue("fixture/lvl").kindFqn)
        assertEquals(GameConfig::class.qualifiedName, byId.getValue("fixture/cfg").kindFqn)
        assertEquals(Character::class.qualifiedName, byId.getValue("fixture/ch").kindFqn)
        assertEquals(GameplayEffect::class.qualifiedName, byId.getValue("fixture/ge").kindFqn)
        assertEquals(Effect::class.qualifiedName, byId.getValue("fixture/fx").kindFqn)

        // The one that has no runtime type, and must not acquire one by guesswork: the generic
        // escape a game declares its own kinds through.
        assertNull(byId.getValue("fixture/custom").kindFqn)
    }

    @Test
    fun `a DSL word with no runtime type is reported, never invented`() {
        val export = graph().toCatalog()

        // `asset(kind, ...)` is the live example now that `character` is published: a game
        // declares its own kinds and this module cannot have a type for them. The wrong fix is to
        // publish `dev.wildware.udea.assets.Particle` because the word is `particle` - the checker
        // would then fail to resolve the kind and go silent, so the id would be indexed *and*
        // unvalidated.
        assertEquals(listOf("character/goblin_dust"), export.unpublishable.map { it.id })
        assertTrue(export.unpublishable.all { it.dslName == "particle" })
        assertNull(export.catalog.resolve("character/goblin_dust"))

        // It is still absent rather than wrong: nothing named `Particle` was invented.
        assertTrue(export.catalog.entries.none { it.kindFqn.endsWith(".Particle") })
    }

    @Test
    fun `the published document round-trips through the consumer's decoder`() {
        val export = graph().toCatalog()
        val json = AssetCatalogJson.encode(export.catalog)

        val decoded = AssetCatalogJson.decode(json)
        val ok = decoded as? AssetCatalogDecode.Ok
            ?: error("the consumer's decoder rejected the producer's document: $decoded")
        assertEquals(export.catalog.entries, ok.catalog.entries)

        // The format's own promises, asserted against a document this module produced rather
        // than against one the format's own tests built.
        assertTrue(json.contains("\"version\": ${AssetCatalog.FORMAT_VERSION}"))
        assertTrue(json.endsWith("\n"))
        assertTrue(json.none { it.code > 0x7E }, "the document must be pure ASCII")
        assertEquals(json, AssetCatalogJson.encode(graph().toCatalog().catalog))
    }
}
