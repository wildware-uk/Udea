package dev.wildware.udea.build

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules behind the two migration gates, executed without a Gradle build.
 *
 * `MigrationVerifyTest` proves the rules are actually attached to a build and that their
 * messages reach a developer. This proves the rules themselves — in particular that
 * [MigrationLedger.SIMILARITY_THRESHOLD] is bracketed from *both* sides, so moving it is a
 * visible change with two failing tests attached rather than a drift nobody notices.
 */
class MigrationLedgerTest {

    // --- fixtures ---------------------------------------------------------------------------

    /** An old-tree file, in the shape the old tree actually writes them. */
    private val original = """
        package dev.wildware.udea.ecs.system

        import com.badlogic.gdx.math.Vector2
        import dev.wildware.udea.ecs.component.base.Transform

        /** Moves everything with a velocity. */
        class TransformSystem(private val world: World) {
            private val scratch = Vector2()

            fun update(deltaTime: Float) {
                for (entity in world.family(Transform::class)) {
                    val transform = entity[Transform::class]
                    scratch.set(transform.velocity).scl(deltaTime)
                    transform.position.add(scratch)
                    if (transform.position.len2() > MAX_DISTANCE_SQUARED) {
                        transform.position.nor().scl(MAX_DISTANCE)
                    }
                    transform.dirty = true
                }
            }

            companion object {
                const val MAX_DISTANCE = 4096f
                const val MAX_DISTANCE_SQUARED = MAX_DISTANCE * MAX_DISTANCE
            }
        }
    """.trimIndent()

    /**
     * The same file copied forward: new package, new imports, reworded comment, reindented,
     * one renamed local. Everything a copy-forward changes for free, and nothing else.
     */
    private val reformattedCopy = """
        package dev.wildware.udea.core.sim

        import dev.wildware.udea.core.Transform
        import dev.wildware.udea.core.math.Vector2

        // Moves everything with a velocity. Copied out of common, tidied slightly.
            class TransformSystem(private val world: World) {
                private val scratch = Vector2()

                fun update(dt: Float) {
                    for (entity in world.family(Transform::class)) {
                        val transform = entity[Transform::class]
                        scratch.set(transform.velocity).scl(dt)
                        transform.position.add(scratch)
                        if (transform.position.len2() > MAX_DISTANCE_SQUARED) {
                            transform.position.nor().scl(MAX_DISTANCE)
                        }
                        transform.dirty = true
                    }
                }

                companion object {
                    const val MAX_DISTANCE = 4096f
                    const val MAX_DISTANCE_SQUARED = MAX_DISTANCE * MAX_DISTANCE
                }
            }
    """.trimIndent()

    /**
     * The same *concept*, written against the new design: `Tick` rather than seconds, no
     * dirty flag (capture-and-diff, spec section 5), no mutable scratch reaching outside.
     *
     * A genuine rewrite must land below the threshold, or the gate would demand a review
     * record for work nobody copied — and a gate that fires on honest work gets switched off.
     */
    private val genuineRewrite = """
        package dev.wildware.udea.core.sim

        import dev.wildware.udea.core.Tick

        internal class MovementSystem(private val store: TransformStore) : SimSystem {

            override fun step(tick: Tick) {
                store.forEachIndexed { index, position, velocity ->
                    val next = position + velocity * SimClock.DT
                    store.setPosition(index, next.clampedTo(ARENA_RADIUS))
                }
            }

            private companion object {
                val ARENA_RADIUS = Metres(4096f)
            }
        }
    """.trimIndent()

    private fun ledger(vararg rows: LedgerRow) = rows.toList()

    private fun row(
        path: String,
        disposition: Disposition = Disposition.REWRITE,
        copiedTo: String? = null,
        sourceHash: String? = null,
        reviewedBy: String? = null,
        reviewedIn: String? = null,
    ) = LedgerRow(
        path = path,
        disposition = disposition,
        destination = "udea-core",
        replacedIn = "0",
        deletedIn = "6",
        copiedTo = copiedTo,
        sourceHash = sourceHash,
        reviewedBy = reviewedBy,
        reviewedIn = reviewedIn,
    )

    // --- parsing ----------------------------------------------------------------------------

    @Test
    fun `a rendered ledger parses back to the rows it was rendered from`() {
        val rows = ledger(
            row("common/a.kt"),
            row("common/b.kt", Disposition.PORT, "udea-core/b.kt", "abc", "shaunwild", "#146"),
            row("common/c.kt", Disposition.DELETE),
        )
        val markdown = "prose\n\n${MigrationLedger.FENCE_OPEN}\n${MigrationLedger.render(rows)}\n```\nmore prose\n"

        assertEquals(rows.sortedBy { it.path }, MigrationLedger.parse(markdown))
    }

    @Test
    fun `a ledger with no fenced block is a hard failure, not an empty list`() {
        val failure = assertFailsWith<IllegalArgumentException> { MigrationLedger.parse("just prose") }
        assertTrue(failure.message!!.contains(MigrationLedger.FENCE_OPEN))
    }

    @Test
    fun `a column added to the header without updating the build is rejected`() {
        val markdown = "${MigrationLedger.FENCE_OPEN}\n${MigrationLedger.HEADER}\textra\n```"
        assertFailsWith<IllegalArgumentException> { MigrationLedger.parse(markdown) }
    }

    @Test
    fun `a disposition outside the three-word vocabulary is rejected`() {
        val row = MigrationLedger.render(ledger(row("common/a.kt"))).replace("\trewrite\t", "\tmaybe\t")
        val failure = assertFailsWith<IllegalArgumentException> {
            MigrationLedger.parse("${MigrationLedger.FENCE_OPEN}\n$row\n```")
        }
        assertTrue(failure.message!!.contains("maybe"))
        assertTrue(failure.message!!.contains("rewrite"))
    }

    // --- coverage ---------------------------------------------------------------------------

    @Test
    fun `a legacy file with no row is reported against that file`() {
        val findings = MigrationLedger.coverageFindings(
            rows = ledger(row("common/a.kt")),
            presentPaths = setOf("common/a.kt", "common/sneaked-in.kt"),
        )

        assertEquals(1, findings.size)
        assertEquals(MigrationLedger.UNLEDGERED_FILE, findings.single().rule)
        assertEquals("common/sneaked-in.kt", findings.single().path)
    }

    @Test
    fun `a row naming a file that is gone is reported against the ledger`() {
        val findings = MigrationLedger.coverageFindings(
            rows = ledger(row("common/a.kt"), row("common/deleted.kt")),
            presentPaths = setOf("common/a.kt"),
        )

        assertEquals(listOf(MigrationLedger.STALE_ROW), findings.map { it.rule })
        assertEquals("docs/migration/ledger.md", findings.single().path)
        assertTrue(findings.single().message.contains("common/deleted.kt"))
    }

    @Test
    fun `two rows for one file are rejected, because its disposition would be ambiguous`() {
        val findings = MigrationLedger.coverageFindings(
            rows = ledger(row("common/a.kt"), row("common/a.kt", Disposition.DELETE)),
            presentPaths = setOf("common/a.kt"),
        )

        assertEquals(listOf(MigrationLedger.STALE_ROW), findings.map { it.rule })
        assertTrue(findings.single().message.contains("more than one row"))
    }

    @Test
    fun `a correct ledger produces no findings`() {
        assertEquals(
            emptyList(),
            MigrationLedger.coverageFindings(ledger(row("common/a.kt")), setOf("common/a.kt")),
        )
    }

    @Test
    fun `module counts separate what is left from what has gone`() {
        val counts = MigrationLedger.moduleCounts(
            rows = ledger(row("common/a.kt"), row("common/b.kt"), row("example/c.kt")),
            presentPaths = setOf("common/a.kt"),
        )

        assertEquals(MigrationLedger.ModuleCount(remaining = 1, deleted = 1), counts["common"])
        assertEquals(MigrationLedger.ModuleCount(remaining = 0, deleted = 1), counts["example"])
        assertEquals(2, counts.getValue("common").total)
    }

    // --- normalisation and similarity -------------------------------------------------------

    @Test
    fun `normalisation drops exactly what a copy-forward changes for free`() {
        val normalised = MigrationLedger.normalise(
            """
            package a.b.c

            import x.y.Z

            // a comment
                val indented = 1
            """.trimIndent(),
        )

        assertEquals(listOf("val indented = 1"), normalised)
    }

    @Test
    fun `a reformatted copy hashes identically to its source`() {
        val moved = original.replace("package dev.wildware.udea.ecs.system", "package dev.wildware.udea.core")
            .replace("import com.badlogic.gdx.math.Vector2", "import dev.wildware.udea.core.math.Vector2")
            .replace("/** Moves everything with a velocity. */", "// Moves everything with a velocity.")
            .prependIndent("    ")

        assertEquals(MigrationLedger.contentHash(original), MigrationLedger.contentHash(moved))
    }

    @Test
    fun `a changed line changes the hash, which is what makes a stale review detectable`() {
        val edited = original.replace("const val MAX_DISTANCE = 4096f", "const val MAX_DISTANCE = 8192f")
        assertTrue(MigrationLedger.contentHash(original) != MigrationLedger.contentHash(edited))
    }

    @Test
    fun `a copy that was edited on the way over is still above the threshold`() {
        val score = MigrationLedger.similarity(original, reformattedCopy)
        assertTrue(
            score >= MigrationLedger.SIMILARITY_THRESHOLD,
            "an edited copy scored $score, below the ${MigrationLedger.SIMILARITY_THRESHOLD} threshold",
        )
    }

    @Test
    fun `a genuine rewrite is below the threshold`() {
        val score = MigrationLedger.similarity(original, genuineRewrite)
        assertTrue(
            score < MigrationLedger.SIMILARITY_THRESHOLD,
            "a rewrite scored $score, at or above the ${MigrationLedger.SIMILARITY_THRESHOLD} threshold",
        )
    }

    @Test
    fun `two short files that share their boilerplate are not called duplicates`() {
        val a = "package p\n\ndata class A(val x: Int)\n"
        val b = "package q\n\ndata class B(val x: Int)\n"
        assertEquals(0.0, MigrationLedger.similarity(a, b))
    }

    // --- copy compliance --------------------------------------------------------------------

    private val source = SourceFile("common/src/main/kotlin/TransformSystem.kt", original)

    @Test
    fun `an unreviewed copy is reported against the copy, naming the source and the gaps`() {
        val copy = SourceFile("udea-core/src/main/kotlin/TransformSystem.kt", reformattedCopy)

        val findings = MigrationLedger.copyFindings(listOf(source), listOf(copy), ledger(row(source.path)))

        val finding = findings.single()
        assertEquals(MigrationLedger.UNREVIEWED_COPY, finding.rule)
        assertEquals(copy.path, finding.path)
        assertTrue(finding.message.contains(source.path))
        assertTrue(finding.message.contains("disposition is 'rewrite'"), finding.message)
        assertTrue(finding.message.contains("reviewedBy is empty"), finding.message)
    }

    @Test
    fun `a copy with no ledger row at all says so`() {
        val copy = SourceFile("moba/src/main/kotlin/TransformSystem.kt", original)

        val finding = MigrationLedger.copyFindings(listOf(source), listOf(copy), emptyList()).single()

        assertEquals(MigrationLedger.UNREVIEWED_COPY, finding.rule)
        assertTrue(finding.message.contains("no row for that source file at all"), finding.message)
    }

    @Test
    fun `a complete and current review record passes`() {
        val copy = SourceFile("udea-core/src/main/kotlin/TransformSystem.kt", reformattedCopy)
        val rows = ledger(
            row(
                path = source.path,
                disposition = Disposition.PORT,
                copiedTo = copy.path,
                sourceHash = MigrationLedger.contentHash(original),
                reviewedBy = "shaunwild",
                reviewedIn = "#146",
            ),
        )

        assertEquals(emptyList(), MigrationLedger.copyFindings(listOf(source), listOf(copy), rows))
    }

    @Test
    fun `a review whose source has since changed is reported as stale, naming both hashes`() {
        val copy = SourceFile("udea-core/src/main/kotlin/TransformSystem.kt", reformattedCopy)
        val staleHash = MigrationLedger.contentHash(original.replace("4096f", "1024f"))
        val rows = ledger(
            row(
                path = source.path,
                disposition = Disposition.PORT,
                copiedTo = copy.path,
                sourceHash = staleHash,
                reviewedBy = "shaunwild",
                reviewedIn = "#146",
            ),
        )

        val finding = MigrationLedger.copyFindings(listOf(source), listOf(copy), rows).single()

        assertEquals(MigrationLedger.STALE_COPY, finding.rule)
        assertTrue(finding.message.contains(staleHash), finding.message)
        assertTrue(finding.message.contains(MigrationLedger.contentHash(original)), finding.message)
    }

    @Test
    fun `a review record pointing at a different file does not excuse this copy`() {
        val copy = SourceFile("udea-core/src/main/kotlin/TransformSystem.kt", reformattedCopy)
        val rows = ledger(
            row(
                path = source.path,
                disposition = Disposition.PORT,
                copiedTo = "udea-core/src/main/kotlin/SomethingElse.kt",
                sourceHash = MigrationLedger.contentHash(original),
                reviewedBy = "shaunwild",
                reviewedIn = "#146",
            ),
        )

        val finding = MigrationLedger.copyFindings(listOf(source), listOf(copy), rows).single()

        assertEquals(MigrationLedger.UNREVIEWED_COPY, finding.rule)
        assertTrue(finding.message.contains("copiedTo names"), finding.message)
    }

    @Test
    fun `a genuine rewrite needs no review record`() {
        val rewrite = SourceFile("udea-core/src/main/kotlin/MovementSystem.kt", genuineRewrite)

        assertEquals(emptyList(), MigrationLedger.copyFindings(listOf(source), listOf(rewrite), emptyList()))
    }

    @Test
    fun `a port row is incomplete until all four review fields are filled in`() {
        val complete = row(
            "common/a.kt",
            Disposition.PORT,
            copiedTo = "udea-core/a.kt",
            sourceHash = "abc",
            reviewedBy = "shaunwild",
            reviewedIn = "#146",
        )

        assertTrue(complete.hasCompleteReview)
        assertFalse(complete.copy(reviewedIn = null).hasCompleteReview)
        assertFalse(complete.copy(reviewedBy = "").hasCompleteReview)
        assertFalse(complete.copy(sourceHash = null).hasCompleteReview)
        assertFalse(complete.copy(copiedTo = null).hasCompleteReview)
        assertFalse(complete.copy(disposition = Disposition.REWRITE).hasCompleteReview)
    }

    @Test
    fun `the failure report names the task and every finding`() {
        val message = MigrationLedger.report(
            taskName = "udeaVerifyMigration",
            findings = listOf(
                MigrationFinding(MigrationLedger.UNREVIEWED_COPY, "udea-core/a.kt", 1, "copied"),
            ),
        )

        assertTrue(message!!.contains("udeaVerifyMigration"))
        assertTrue(message.contains("udea-core/a.kt:1:1: error: [UDEA-MIG-003] copied"))
    }

    @Test
    fun `no findings means no report`() {
        assertEquals(null, MigrationLedger.report("udeaLegacyReport", emptyList()))
    }
}
