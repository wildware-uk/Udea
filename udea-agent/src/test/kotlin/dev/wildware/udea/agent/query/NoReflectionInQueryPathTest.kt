package dev.wildware.udea.agent.query

import dev.wildware.udea.agent.KotlinSourceText
import dev.wildware.udea.agent.ModuleSources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The claim spec 3.1 makes about this surface: **no reflection, and it survives R8.**
 *
 * `describe_entity` and `set_component_field` are consequences of `Replicator.getField/setField`,
 * so the query path never needs to ask a class what its fields are. The old engine did the
 * opposite - a classpath-scanning `Reflections` singleton in `common/.../reflection.kt` - and
 * "we deleted the call" is not a durable answer, because the next convenient shortcut has the
 * same shape and reintroduces it in one line.
 *
 * Checked at source level rather than by bytecode: an import is where reflection arrives, it is
 * greppable, and the failure message can point at the line. Comments and strings are blanked
 * first, so this file's own prose about `kotlin.reflect` does not trip it.
 */
class NoReflectionInQueryPathTest {

    @Test
    fun `no source in this module imports a reflection API`() {
        val offenders = scanMainSources { path, line, text ->
            if (REFLECTION_IMPORT.containsMatchIn(text) && !CLASS_TOKEN_IMPORT.matches(text.trim())) {
                "$path:$line  ${text.trim()}"
            } else {
                null
            }
        }

        assertEquals(
            emptyList(),
            offenders,
            "the agent surface reads fields through the generated Replicator; reflection here " +
                "would be stripped by R8 and would make describe_entity a runtime surprise",
        )
    }

    @Test
    fun `the KClass exemption covers the owner pairing and nothing else`() {
        // `KClass` is exempt from the import ban above because a class *literal* is a constant
        // reference and not a lookup - the same distinction the REFLECTIVE_ACCESS pattern
        // already draws for `javaClass.simpleName`. R8 follows a class literal and keeps the
        // class, which is the ban's whole rationale. It is used for exactly one thing: pairing
        // a generated `AgentToolDef`/`AgentStateSource` with the instance a host registered,
        // resolved once when the index is built.
        //
        // The engine's own toolsets are generated now, so their `owner` class literals live in
        // `build/generated` rather than in this list - which is why the list got *shorter* when
        // `EngineToolDef` was deleted rather than growing an entry per toolset.
        //
        // So the exemption's *size* is asserted rather than trusted. If a fifth file starts
        // importing KClass, this list is where that shows up in a diff.
        val importers = ModuleSources.mainSources
            .filter { CLASS_TOKEN_IMPORT.containsMatchIn(it.readText()) }
            .map { ModuleSources.relativePath(it) }

        assertEquals(
            listOf(
                "udea-agent/src/main/kotlin/dev/wildware/udea/agent/AgentStateSource.kt",
                "udea-agent/src/main/kotlin/dev/wildware/udea/agent/AgentToolDef.kt",
                "udea-agent/src/main/kotlin/dev/wildware/udea/agent/OwnerBinding.kt",
            ),
            importers.sorted(),
        )
        assertTrue(
            importers.none { it.contains("/agent/query/") },
            "the query path must reach a field through Replicator and never through a class: $importers",
        )
    }

    @Test
    fun `no source reaches for a class object to inspect it`() {
        val offenders = scanMainSources { path, line, text ->
            if (REFLECTIVE_ACCESS.containsMatchIn(text)) "$path:$line  ${text.trim()}" else null
        }

        assertEquals(emptyList(), offenders, "field and member lookup must go through Replicator")
    }

    @Test
    fun `the query package exists and is what is being scanned`() {
        val queryFiles = ModuleSources.mainSources.filter {
            ModuleSources.relativePath(it).contains("/agent/query/")
        }

        // Guards the two tests above from passing because they scanned nothing.
        assertTrue(queryFiles.size >= 5, "expected the query package, found $queryFiles")
    }

    private fun scanMainSources(inspect: (String, Int, String) -> String?): List<String> {
        val offenders = ArrayList<String>()
        for (file in ModuleSources.mainSources) {
            val source = KotlinSourceText.stripCommentsAndStrings(file.readText())
            source.lineSequence().forEachIndexed { index, text ->
                inspect(ModuleSources.relativePath(file), index + 1, text)?.let { offenders += it }
            }
        }
        return offenders
    }

    private companion object {
        /**
         * `javaClass.simpleName` is deliberately not here: it is a name, not a lookup, it is on
         * an error path rather than the query path, and R8 keeps it working. `::class.java` used
         * to *find* something is what this forbids.
         */
        val REFLECTION_IMPORT =
            Regex("""^\s*import\s+(kotlin\.reflect|java\.lang\.reflect|org\.reflections)""")

        /**
         * The one narrowing of [REFLECTION_IMPORT]: `KClass` as a class **token**.
         *
         * Anchored to the whole line so `kotlin.reflect.full.*` and every other member of that
         * package stays banned, and pinned to an exact file list by
         * `the KClass exemption covers the owner pairing and nothing else`.
         */
        val CLASS_TOKEN_IMPORT = Regex("""import\s+kotlin\.reflect\.KClass""")

        val REFLECTIVE_ACCESS =
            Regex("""(::class\.(java\.)?(declaredFields|declaredMethods|fields|methods|memberProperties))|(getDeclaredField|getDeclaredMethod|\.isAccessible)""")
    }
}
