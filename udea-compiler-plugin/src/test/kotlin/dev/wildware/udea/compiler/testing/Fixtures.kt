package dev.wildware.udea.compiler.testing

/**
 * The shared, well-formed `@Replicated` component every checker test compiles against.
 *
 * It mirrors spec 3.1's `Transform` as closely as a self-contained fixture can: the composite
 * `@Net val position`, the quantised `@Net var rotation`, the `@Sim` field that rewinds but
 * never reaches a client. `Vector2`, `Tick` and Fleks' `Component` are not on this module's
 * test classpath, so the fixture declares stand-ins of the same *shape* - which is all the FIR
 * checkers can see anyway, since they match annotations by name and types by structure.
 *
 * Issue #37 requires it to be reused by every checker issue: a false positive on a normal
 * component is the failure mode that would make the checkers unusable, so every checker's test
 * class asserts this fixture stays clean.
 */
object Fixtures {

    /** A vector mutated in place - the reason replication is capture-and-diff (spec 3.2). */
    val VECTOR: TestSource = source(
        "Vector2.kt",
        """
        package udea.fixtures

        class Vector2(var x: Float = 0f, var y: Float = 0f)
        """,
    )

    /** A component that must produce no diagnostic at all. */
    val WELL_FORMED: TestSource = source(
        "Transform.kt",
        """
        package udea.fixtures

        import dev.wildware.udea.annotations.Net
        import dev.wildware.udea.annotations.Q
        import dev.wildware.udea.annotations.Replicated
        import dev.wildware.udea.annotations.Sim

        @Replicated
        class Transform {
            // A composite val is legal: `apply` restores it by writing x and y in place.
            @Net
            val position: Vector2 = Vector2()

            @Net
            @Q(bits = 12, min = -3.1416f, max = 3.1416f)
            var rotation: Float = 0f

            @Net
            var health: Float = 100f

            @Sim
            var lastGroundedTick: Long = 0L
        }
        """,
    )

    /** [WELL_FORMED] and everything it needs. */
    val WELL_FORMED_COMPILATION: Array<TestSource> = arrayOf(VECTOR, WELL_FORMED)

    /**
     * A `@Replicated` component with [fieldCount] `@Net var` fields, for the mask-ceiling test.
     *
     * Generated rather than written out because the interesting inputs are 64 and 65 and the
     * difference between the two files is one line.
     */
    fun componentWithNetFields(fieldCount: Int): TestSource = source(
        "Wide.kt",
        buildString {
            append("package udea.fixtures\n\n")
            append("import dev.wildware.udea.annotations.Net\n")
            append("import dev.wildware.udea.annotations.Replicated\n\n")
            append("@Replicated\n")
            append("class Wide {\n")
            repeat(fieldCount) { index ->
                append("    @Net var f").append(index).append(": Int = 0\n")
            }
            append("}\n")
        },
    )
}
