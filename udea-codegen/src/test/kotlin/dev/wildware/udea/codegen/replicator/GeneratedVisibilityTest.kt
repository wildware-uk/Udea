package dev.wildware.udea.codegen.replicator

import dev.wildware.udea.codegen.ProcessorHarness
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Issue #167, generator half: `@Net(visibility = OwnerOnly)` reaches the emitted `Replicator`.
 *
 * ## What was wrong
 *
 * `udea-annotations` has declared `visibility = All | OwnerOnly` since Phase 0 and
 * `ComponentModelBuilder` never read the argument, so the declaration was **decorative** — and
 * decorative in the leaking direction, which is the difference between this and issue #114. An
 * author who wrote `OwnerOnly` on `moba`'s `Inventory` believed the field was private to one
 * connection, and every client the champion was relevant to was sent it.
 *
 * These tests run the real processor over throwaway sources, like `GeneratedLifetimeTest`,
 * rather than adding to the fixture source set — the thing under test is what the processor
 * *emits* for a declaration, and the fixture set can only show the one configuration it is built
 * with. The behavioural end of it — the owner's replica carries the field and a non-owner's does
 * not — is proven over a real `ReplicationServer` in `udea-net`'s `OwnerOnlyVisibilityTest` and
 * against a real generated replicator in `moba`'s `InventoryVisibilityTest`.
 */
class GeneratedVisibilityTest {

    private fun run(workDir: File, source: String): String {
        val result = ProcessorHarness.run(workDir, mapOf("Fixtures.kt" to source))
        assertEquals(emptyList(), result.errors)
        return result.generatedSource("SpawnedReplicator.kt")
    }

    @Test
    fun `a component with an OwnerOnly field declares an owner-only mask over exactly that field`(
        @TempDir workDir: File,
    ) {
        val generated = run(
            workDir,
            """
            package fixtures

            import dev.wildware.udea.annotations.Net
            import dev.wildware.udea.annotations.Replicated
            import dev.wildware.udea.annotations.Visibility

            @Replicated
            class Spawned(
                @Net var x: Float = 0f,
                @Net(visibility = Visibility.OwnerOnly) var gold: Int = 0,
            )
            """.trimIndent(),
        )

        // The interface is what `VisibilityPolicy` casts to; without it the mask is unreachable
        // however correctly it was computed.
        assertTrue(
            "dev.wildware.udea.net.wire.OwnerOnlyFields" in generated,
            "the generated replicator does not implement OwnerOnlyFields:\n$generated",
        )
        assertTrue(
            "override val ownerOnlyMask: FieldMask = MaskOps.of(FIELD_GOLD)" in generated,
            "the owner-only mask is not exactly gold:\n$generated",
        )
        // An owner-only field is still a `@Net` field. If it fell out of netMask the stripping
        // would subtract a bit that was never there and the field would reach nobody, owner
        // included.
        assertTrue(
            "override val netMask: FieldMask = MaskOps.of(FIELD_GOLD, FIELD_X)" in generated,
            "the owner-only field left netMask:\n$generated",
        )
    }

    @Test
    fun `a component with no OwnerOnly field names no udea-net type at all`(@TempDir workDir: File) {
        val generated = run(
            workDir,
            """
            package fixtures

            import dev.wildware.udea.annotations.Net
            import dev.wildware.udea.annotations.Replicated

            @Replicated
            class Spawned(@Net var x: Float = 0f, @Net var gold: Int = 0)
            """.trimIndent(),
        )

        // Not merely "the mask is empty": implementing the interface would put `udea-net` on the
        // compile classpath of every module that has a replicated component, for a mask that says
        // nothing. `VisibilityPolicy` reads the absence as "nothing is owner-only", which for this
        // component is the true answer and not a fallback.
        assertFalse(
            "OwnerOnlyFields" in generated,
            "a component with no OwnerOnly field acquired a udea-net dependency:\n$generated",
        )
        assertFalse("ownerOnlyMask" in generated, generated)
    }

    @Test
    fun `the two independent stripping declarations compose on one field`(@TempDir workDir: File) {
        // `lifetime` and `visibility` answer different questions - *when* a field is sent and *to
        // whom* - so a field may carry both, and the generated object has to implement both
        // markers rather than the later declaration replacing the earlier.
        val generated = run(
            workDir,
            """
            package fixtures

            import dev.wildware.udea.annotations.Lifetime
            import dev.wildware.udea.annotations.Net
            import dev.wildware.udea.annotations.Replicated
            import dev.wildware.udea.annotations.Visibility

            @Replicated
            class Spawned(
                @Net var x: Float = 0f,
                @Net(lifetime = Lifetime.OnCreate, visibility = Visibility.OwnerOnly)
                var gold: Int = 0,
            )
            """.trimIndent(),
        )

        assertTrue("CreateOnlyFields" in generated, generated)
        assertTrue("OwnerOnlyFields" in generated, generated)
        assertTrue("override val createOnlyMask: FieldMask = MaskOps.of(FIELD_GOLD)" in generated, generated)
        assertTrue("override val ownerOnlyMask: FieldMask = MaskOps.of(FIELD_GOLD)" in generated, generated)
    }

    @Test
    fun `an OwnerOnly declaration changes the locked wire description`(@TempDir workDir: File) {
        // The lock is what `protoHash` is computed from, and a visibility change moves no bit in
        // any single packet: a peer that thinks `gold` is `All` expects it from the server for
        // every champion, a peer that thinks it is `OwnerOnly` expects it for one, and both
        // decode every packet the other sends without complaint. Invisible to the lock, two
        // builds disagreeing about which fields a client is owed is a permanent silent
        // difference in what a screen can show.
        val always = ProcessorHarness.run(
            File(workDir, "all"),
            mapOf(
                "Fixtures.kt" to """
                package fixtures

                import dev.wildware.udea.annotations.Net
                import dev.wildware.udea.annotations.Replicated

                @Replicated
                class Spawned(@Net var gold: Int = 0)
                """.trimIndent(),
            ),
            mapOf(
                "udea.moduleName" to "Fixtures",
                "udea.projectComponents" to "fixtures.Spawned",
            ),
        )
        val ownerOnly = ProcessorHarness.run(
            File(workDir, "owneronly"),
            mapOf(
                "Fixtures.kt" to """
                package fixtures

                import dev.wildware.udea.annotations.Net
                import dev.wildware.udea.annotations.Replicated
                import dev.wildware.udea.annotations.Visibility

                @Replicated
                class Spawned(@Net(visibility = Visibility.OwnerOnly) var gold: Int = 0)
                """.trimIndent(),
            ),
            mapOf(
                "udea.moduleName" to "Fixtures",
                "udea.projectComponents" to "fixtures.Spawned",
            ),
        )

        val allLock = always.generatedResources.entries.single { it.key.endsWith(".lock") }.value
        val ownerOnlyLock = ownerOnly.generatedResources.entries.single { it.key.endsWith(".lock") }.value
        assertTrue("i32:32" in allLock, allLock)
        assertFalse("owneronly" in allLock, allLock)
        assertTrue("i32:32:owneronly" in ownerOnlyLock, ownerOnlyLock)
    }

    @Test
    fun `both tokens appear in one field description in a fixed order`(@TempDir workDir: File) {
        // Order matters because the token is hashed: two builds that agreed about a field and
        // spelled its description differently would refuse each other's handshake.
        val both = ProcessorHarness.run(
            File(workDir, "both"),
            mapOf(
                "Fixtures.kt" to """
                package fixtures

                import dev.wildware.udea.annotations.Lifetime
                import dev.wildware.udea.annotations.Net
                import dev.wildware.udea.annotations.Replicated
                import dev.wildware.udea.annotations.Visibility

                @Replicated
                class Spawned(
                    @Net(lifetime = Lifetime.OnCreate, visibility = Visibility.OwnerOnly)
                    var gold: Int = 0,
                )
                """.trimIndent(),
            ),
            mapOf(
                "udea.moduleName" to "Fixtures",
                "udea.projectComponents" to "fixtures.Spawned",
            ),
        )
        val lock = both.generatedResources.entries.single { it.key.endsWith(".lock") }.value
        assertTrue("i32:32:oncreate:owneronly" in lock, lock)
    }
}
