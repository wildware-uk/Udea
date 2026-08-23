package dev.wildware.udea.codegen.replicator

import dev.wildware.udea.codegen.ProcessorHarness
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Issue #114, generator half: `@Net(lifetime = OnCreate)` reaches the emitted `Replicator`.
 *
 * ## What was wrong
 *
 * `udea-annotations` has declared `lifetime = OnCreate | Always` since Phase 0 and
 * `ComponentModelBuilder` never read the argument, so the declaration was **decorative**.
 * `udea-net`'s `LifetimePolicy` was already refusing to put a create-only field in an `Update`
 * — and it asked every replicator for a mask that every generated replicator answered as
 * empty, so a spawn-only team id rode a delta on every tick capture-and-diff saw it move.
 * `LifetimeMaskTest` proved the enforcement against a *hand-written* fixture and said so in its
 * own KDoc: "until the generator implements that interface … this enforcement, although real,
 * applies to nothing in a shipped build."
 *
 * These tests run the real processor over throwaway sources, like `ModuleIndexTest`, rather
 * than adding to the fixture source set — the thing under test is what the processor *emits*
 * for a declaration, and the fixture set can only show the one configuration it is built with.
 * The behavioural end of it — present in the create packet, absent from every later update —
 * is proven against a real generated replicator in `moba`'s `CombatantLifetimeTest`.
 */
class GeneratedLifetimeTest {

    private fun run(workDir: File, source: String): String {
        val result = ProcessorHarness.run(workDir, mapOf("Fixtures.kt" to source))
        assertEquals(emptyList(), result.errors)
        return result.generatedSource("SpawnedReplicator.kt")
    }

    @Test
    fun `a component with an OnCreate field declares a create-only mask over exactly that field`(
        @TempDir workDir: File,
    ) {
        val generated = run(
            workDir,
            """
            package fixtures

            import dev.wildware.udea.annotations.Lifetime
            import dev.wildware.udea.annotations.Net
            import dev.wildware.udea.annotations.Replicated

            @Replicated
            class Spawned(
                @Net var x: Float = 0f,
                @Net(lifetime = Lifetime.OnCreate) var teamId: Int = 0,
            )
            """.trimIndent(),
        )

        // The interface is what `LifetimePolicy` casts to; without it the mask is unreachable
        // however correctly it was computed.
        assertTrue(
            "dev.wildware.udea.net.wire.CreateOnlyFields" in generated,
            "the generated replicator does not implement CreateOnlyFields:\n$generated",
        )
        assertTrue(
            "override val createOnlyMask: FieldMask = MaskOps.of(FIELD_TEAM_ID)" in generated,
            "the create-only mask is not exactly teamId:\n$generated",
        )
        // A create-only field is still a `@Net` field. If it fell out of netMask the stripping
        // would subtract a bit that was never there and the field would never be sent at all.
        assertTrue(
            "override val netMask: FieldMask = MaskOps.of(FIELD_TEAM_ID, FIELD_X)" in generated,
            "the create-only field left netMask:\n$generated",
        )
    }

    @Test
    fun `a component with no OnCreate field names no udea-net type at all`(@TempDir workDir: File) {
        val generated = run(
            workDir,
            """
            package fixtures

            import dev.wildware.udea.annotations.Net
            import dev.wildware.udea.annotations.Replicated

            @Replicated
            class Spawned(@Net var x: Float = 0f, @Net var teamId: Int = 0)
            """.trimIndent(),
        )

        // Not merely "the mask is empty": implementing the interface would put `udea-net` on
        // the compile classpath of every module that has a replicated component, for a mask
        // that says nothing. `LifetimePolicy` reads the absence as "nothing is create-only",
        // which for this component is the true answer and not a fallback.
        assertFalse(
            "CreateOnlyFields" in generated,
            "a component with no OnCreate field acquired a udea-net dependency:\n$generated",
        )
        assertFalse("createOnlyMask" in generated, generated)
    }

    @Test
    fun `an OnCreate declaration changes the locked wire description`(@TempDir workDir: File) {
        // The lock is what `protoHash` is computed from, and a lifetime change moves no bit in
        // any single packet: a peer that thinks `teamId` is `Always` expects it in deltas, a
        // peer that thinks it is `OnCreate` never sends it there, and both decode every packet
        // the other sends without complaint. Invisible to the lock, that is a silent
        // disagreement about a value for the rest of the match.
        val always = ProcessorHarness.run(
            File(workDir, "always"),
            mapOf(
                "Fixtures.kt" to """
                package fixtures

                import dev.wildware.udea.annotations.Net
                import dev.wildware.udea.annotations.Replicated

                @Replicated
                class Spawned(@Net var teamId: Int = 0)
                """.trimIndent(),
            ),
            mapOf(
                "udea.moduleName" to "Fixtures",
                "udea.projectComponents" to "fixtures.Spawned",
            ),
        )
        val onCreate = ProcessorHarness.run(
            File(workDir, "oncreate"),
            mapOf(
                "Fixtures.kt" to """
                package fixtures

                import dev.wildware.udea.annotations.Lifetime
                import dev.wildware.udea.annotations.Net
                import dev.wildware.udea.annotations.Replicated

                @Replicated
                class Spawned(@Net(lifetime = Lifetime.OnCreate) var teamId: Int = 0)
                """.trimIndent(),
            ),
            mapOf(
                "udea.moduleName" to "Fixtures",
                "udea.projectComponents" to "fixtures.Spawned",
            ),
        )

        val alwaysLock = always.generatedResources.entries.single { it.key.endsWith(".lock") }.value
        val onCreateLock = onCreate.generatedResources.entries.single { it.key.endsWith(".lock") }.value
        assertTrue("i32:32" in alwaysLock, alwaysLock)
        assertTrue("i32:32:oncreate" in onCreateLock, onCreateLock)
    }
}
