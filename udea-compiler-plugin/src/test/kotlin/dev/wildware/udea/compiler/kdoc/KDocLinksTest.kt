package dev.wildware.udea.compiler.kdoc

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `[Foo]` has to survive the move into a generated file in a different package.
 */
class KDocLinksTest {

    private val resolve: (String) -> String? = mapOf(
        "Ability" to "dev.wildware.udea.assets.Ability",
        "Tick" to "dev.wildware.udea.core.Tick",
    )::get

    @Test
    fun `a known simple name is qualified`() {
        assertEquals(
            "See [dev.wildware.udea.assets.Ability] for the shape.",
            KDocLinks.qualify("See [Ability] for the shape.", resolve),
        )
    }

    @Test
    fun `an unknown name is left exactly as the author wrote it`() {
        // Guessing would point the generated KDoc at a type that does not exist, which is
        // worse than a link that only resolves in the source file.
        assertEquals("See [Unknown].", KDocLinks.qualify("See [Unknown].", resolve))
    }

    @Test
    fun `an already-qualified link is untouched`() {
        assertEquals(
            "See [dev.wildware.udea.assets.Ability].",
            KDocLinks.qualify("See [dev.wildware.udea.assets.Ability].", resolve),
        )
    }

    @Test
    fun `a markdown link is not a KDoc reference`() {
        assertEquals(
            "See [Ability](https://example.invalid/ability).",
            KDocLinks.qualify("See [Ability](https://example.invalid/ability).", resolve),
        )
    }

    @Test
    fun `every occurrence is rewritten, not only the first`() {
        assertEquals(
            "[dev.wildware.udea.assets.Ability] and [dev.wildware.udea.core.Tick] and " +
                "[dev.wildware.udea.assets.Ability]",
            KDocLinks.qualify("[Ability] and [Tick] and [Ability]", resolve),
        )
    }

    @Test
    fun `a whole block is qualified, tags included`() {
        val qualified = KDocLinks.qualify(
            KDocBlock(
                summary = "Uses [Ability].",
                params = listOf(KDocParam("at", "the [Tick] it fires on")),
                tags = listOf(KDocTag("see", "[Ability]")),
            ),
            resolve,
        )

        assertEquals("Uses [dev.wildware.udea.assets.Ability].", qualified.summary)
        assertEquals("the [dev.wildware.udea.core.Tick] it fires on", qualified.params.single().text)
        assertEquals("[dev.wildware.udea.assets.Ability]", qualified.tags.single().text)
    }
}
