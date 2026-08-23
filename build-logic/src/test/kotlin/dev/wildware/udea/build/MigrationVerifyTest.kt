package dev.wildware.udea.build

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertTrue

/**
 * `udeaLegacyReport` and `udeaVerifyMigration` run against a real build.
 *
 * [MigrationLedgerTest] proves the rules. This proves they are *attached*: that the plugin
 * points them at the right trees, that a `udea-*` module added to the fixture is scanned
 * without opting in to anything, and that the failure message reaches the developer.
 */
class MigrationVerifyTest {

    @TempDir
    lateinit var root: File

    private val original = """
        package dev.wildware.udea.ecs.system

        import com.badlogic.gdx.math.Vector2

        /** Moves everything with a velocity. */
        class TransformSystem(private val world: World) {
            private val scratch = Vector2()

            fun update(deltaTime: Float) {
                for (entity in world.family(Transform::class)) {
                    val transform = entity[Transform::class]
                    scratch.set(transform.velocity).scl(deltaTime)
                    transform.position.add(scratch)
                    transform.dirty = true
                }
            }
        }
    """.trimIndent()

    private val legacyPath = "common/src/main/kotlin/dev/wildware/udea/ecs/system/TransformSystem.kt"
    private val copyPath = "udea-x/src/main/kotlin/dev/wildware/udea/x/TransformSystem.kt"

    private fun write(path: String, text: String) {
        File(root, path).apply { parentFile.mkdirs() }.writeText(text)
    }

    /** Writes a ledger holding [rows], plus a row for the fixture's legacy file if absent. */
    private fun ledger(rows: List<LedgerRow>) {
        write(
            "docs/migration/ledger.md",
            "# fixture ledger\n\n${MigrationLedger.FENCE_OPEN}\n${MigrationLedger.render(rows)}\n```\n",
        )
    }

    private fun rewriteRow() = LedgerRow(legacyPath, Disposition.REWRITE, "udea-x", "0", "6")

    private fun portRow(sourceHash: String) = LedgerRow(
        path = legacyPath,
        disposition = Disposition.PORT,
        destination = "udea-x",
        replacedIn = "0",
        deletedIn = "6",
        copiedTo = copyPath,
        sourceHash = sourceHash,
        reviewedBy = "shaunwild",
        reviewedIn = "#146",
    )

    /**
     * A fixture repository with `common`, a `udea-x` module and a ledger.
     *
     * `udea-x` is only ever `include`d — it never applies a plugin or opts in to a check. That
     * is the point: a module added to `settings.gradle.kts` is under the gate by existing.
     */
    private fun fixture(): GradleFixture = GradleFixture(root)
        .project("udea-x", "plugins { `java-library` }")

    private fun rootScript() = """
        plugins { id("udea.migration-check") }
    """.trimIndent()

    @Test
    fun `a green tree passes both gates`() {
        write(legacyPath, original)
        ledger(listOf(rewriteRow()))

        val result = fixture().build("udeaLegacyReport", "udeaVerifyMigration", rootBuildScript = rootScript())

        assertTrue(result.output.contains("common"))
        assertTrue(result.output.contains("remaining"))
    }

    @Test
    fun `a legacy file with no ledger row fails the report, naming the file`() {
        write(legacyPath, original)
        write("common/src/main/kotlin/dev/wildware/udea/Sneaked.kt", "package dev.wildware.udea\nclass Sneaked\n")
        ledger(listOf(rewriteRow()))

        val result = fixture().buildAndFail("udeaLegacyReport", rootBuildScript = rootScript())

        assertTrue(result.output.contains("UDEA-MIG-001"), result.output)
        assertTrue(result.output.contains("common/src/main/kotlin/dev/wildware/udea/Sneaked.kt"), result.output)
    }

    @Test
    fun `the gates run from check, not only when somebody remembers to name them`() {
        write(legacyPath, original)
        write("common/src/main/kotlin/dev/wildware/udea/Sneaked.kt", "package dev.wildware.udea\nclass Sneaked\n")
        ledger(listOf(rewriteRow()))

        val result = fixture().buildAndFail("check", rootBuildScript = rootScript())

        assertTrue(result.output.contains("UDEA-MIG-001"), result.output)
    }

    @Test
    fun `an unreviewed byte-identical copy fails, naming the source and the missing fields`() {
        write(legacyPath, original)
        write(copyPath, original.replace("package dev.wildware.udea.ecs.system", "package dev.wildware.udea.x"))
        ledger(listOf(rewriteRow()))

        val result = fixture().buildAndFail("udeaVerifyMigration", rootBuildScript = rootScript())

        assertTrue(result.output.contains("UDEA-MIG-003"), result.output)
        assertTrue(result.output.contains(legacyPath), result.output)
        assertTrue(result.output.contains("reviewedBy is empty"), result.output)
    }

    @Test
    fun `a reviewed copy passes, and the same copy fails once its source moves on`() {
        write(legacyPath, original)
        write(copyPath, original.replace("package dev.wildware.udea.ecs.system", "package dev.wildware.udea.x"))
        ledger(listOf(portRow(MigrationLedger.contentHash(original))))

        fixture().build("udeaVerifyMigration", rootBuildScript = rootScript())

        // The bug spec section 7 describes as appearing far from its cause: `common` moves on,
        // and a copy reviewed against the old version silently keeps the old behaviour.
        write(legacyPath, original.replace("transform.dirty = true", "transform.dirty = transform.moved"))

        val result = fixture().buildAndFail("udeaVerifyMigration", rootBuildScript = rootScript())

        assertTrue(result.output.contains("UDEA-MIG-004"), result.output)
        assertTrue(result.output.contains("no longer exists"), result.output)
    }

    @Test
    fun `a genuine rewrite of the same concept needs no review record`() {
        write(legacyPath, original)
        write(
            copyPath,
            """
            package dev.wildware.udea.x

            import dev.wildware.udea.core.Tick

            internal class MovementSystem(private val store: TransformStore) : SimSystem {
                override fun step(tick: Tick) {
                    store.forEachIndexed { index, position, velocity ->
                        store.setPosition(index, position + velocity * SimClock.DT)
                    }
                }
            }
            """.trimIndent(),
        )
        ledger(listOf(rewriteRow()))

        fixture().build("udeaVerifyMigration", rootBuildScript = rootScript())
    }
}
