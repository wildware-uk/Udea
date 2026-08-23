package dev.wildware.udea.gas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rules about this module's *source*, checked by reading it.
 *
 * Some of what issues #95, #98 and #100 ask for is a shape a compiler is perfectly happy with: a
 * Fleks `Entity` on a snapshot-captured field compiles, and so does a `kotlin.time.Duration` in a
 * simulation type. The Gradle gate `udeaVerifyGasTime` covers the time half at build time; this
 * covers the rest, and covers the time half again from inside `test` so a developer running tests
 * sees it before CI does.
 */
class GasArchitectureTest {

    private val sources: List<File> = GasSources.mainSources

    @Test
    fun `there are sources to check`() {
        assertTrue(sources.size >= 10, "found only ${sources.size} source files; the scan is misaimed")
    }

    @Test
    fun `no simulation source references a wall clock or a seconds-denominated duration`() {
        val forbidden = listOf(
            "kotlin.time.Duration",
            "System.nanoTime",
            "System.currentTimeMillis",
            "Instant.now",
            "deltaTime",
        )
        assertEquals(emptyList(), GasSources.findReferences(sources, forbidden))
    }

    @Test
    fun `no source references LibGDX, udea-render or audio`() {
        val forbidden = listOf("com.badlogic.gdx", "udea.render", "SoundSystem", "AnimationSet")
        assertEquals(
            emptyList(),
            GasSources.findReferences(sources, forbidden),
            "applying an effect must not be able to reach presentation, in any render mode",
        )
    }

    @Test
    fun `no snapshot-captured type holds a Fleks Entity`() {
        // The components and the values inside them. `GasSystems.kt` legitimately names Entity: it
        // iterates a family, which is exactly where a Fleks entity is the right type.
        val capturedTypes = sources.filter {
            it.name in setOf(
                "Abilities.kt",
                "Attributes.kt",
                "GameplayEffects.kt",
                "CueQueue.kt",
                "HandleAllocator.kt",
                "GameplayEffect.kt",
                "AbilityDef.kt",
            )
        }
        assertTrue(capturedTypes.size == 7, "expected seven captured-state sources, found ${capturedTypes.size}")
        assertEquals(
            emptyList(),
            GasSources.findReferences(capturedTypes, listOf("fleks.Entity", "Entity(")),
            "spec 5: a snapshot or an agent tool never sees a Fleks Entity, only a NetId",
        )
    }

    @Test
    fun `no source reaches for kotlin-reflect`() {
        assertEquals(
            emptyList(),
            GasSources.findReferences(sources, listOf("kotlin.reflect.full", "createInstance()")),
            "the old AbilitySpec built its exec with kotlin.reflect.full.createInstance",
        )
    }

    @Test
    fun `no source declares a top-level mutable global`() {
        val offenders = sources.flatMap { file ->
            GasSources.stripComments(file.readText())
                .lineSequence()
                .withIndex()
                .filter { (_, line) -> line.startsWith("var ") || line.startsWith("public var ") }
                .map { (index, line) -> "${file.name}:${index + 1} $line" }
        }
        assertEquals(emptyList(), offenders, "state reaches code by construction, never through a global")
    }

    @Test
    fun `no source casts a read-only list to a mutable one`() {
        assertEquals(
            emptyList(),
            GasSources.findReferences(sources, listOf("as MutableList", "as ArrayList")),
            "AttributeSystem.kt:55 did exactly this to remove expired effects",
        )
    }
}

/** Locates and reads this module's sources. */
internal object GasSources {

    private val moduleDir: File = locateModuleDir()

    val mainSources: List<File> = moduleDir.resolve("src/main/kotlin")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .sortedBy { it.path }
        .toList()

    /** Every `file:line` in [files] whose code — not its comments — contains one of [needles]. */
    fun findReferences(files: List<File>, needles: List<String>): List<String> =
        files.flatMap { file ->
            stripComments(file.readText())
                .lineSequence()
                .withIndex()
                .flatMap { (index, line) ->
                    needles.filter { line.contains(it) }.map { "${file.name}:${index + 1} '$it'" }
                }
        }

    /**
     * Removes comments so the scan reads code rather than prose.
     *
     * This module's KDoc deliberately names the old seconds-denominated API it replaced; a scan
     * that could not tell a citation from a call would force the documentation to go vague about
     * what it fixed. Newlines inside block comments are kept so line numbers stay true.
     */
    fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            when {
                text.startsWith("/*", index) -> {
                    val end = text.indexOf("*/", index + 2)
                    val stop = if (end < 0) text.length else end + 2
                    for (character in text.substring(index, stop)) if (character == '\n') out.append('\n')
                    index = stop
                }

                text.startsWith("//", index) -> {
                    val end = text.indexOf('\n', index)
                    index = if (end < 0) text.length else end
                }

                else -> {
                    out.append(text[index])
                    index++
                }
            }
        }
        return out.toString()
    }

    private fun locateModuleDir(): File {
        var candidate: File? = File(System.getProperty("user.dir")).absoluteFile
        while (candidate != null) {
            if (candidate.name == "udea-gas" && candidate.resolve("build.gradle.kts").isFile) return candidate
            val nested = candidate.resolve("udea-gas")
            if (nested.resolve("build.gradle.kts").isFile) return nested
            candidate = candidate.parentFile
        }
        error("could not locate udea-gas from ${System.getProperty("user.dir")}")
    }
}
