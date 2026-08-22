package dev.wildware.udea.build

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `udeaVerifyAnnotationsLeaf` is the only purity rule the build actually enforces, and a
 * `doLast` block is unreachable from a test. These drive the rule it applies, so its
 * failure paths are executed rather than assumed.
 */
class UdeaLeafCheckTest {

    private val allowed = setOf(
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.jetbrains:annotations",
    )

    @Test
    fun `the allowed classpath passes`() {
        assertNull(UdeaLeafCheck.violation(":udea-annotations", allowed, allowed))
    }

    @Test
    fun `a disallowed direct dependency fails and is named`() {
        val message = assertNotNull(
            UdeaLeafCheck.violation(
                ":udea-annotations",
                allowed + "com.squareup:kotlinpoet-jvm",
                allowed,
            ),
        )
        assertTrue("com.squareup:kotlinpoet-jvm" in message, message)
        assertTrue(":udea-annotations" in message, message)
    }

    @Test
    fun `a transitive dependency fails too - the gate reads the resolved graph`() {
        val message = assertNotNull(
            UdeaLeafCheck.violation(
                ":udea-annotations",
                allowed + setOf("com.squareup:kotlinpoet-jvm", "org.jetbrains.kotlin:kotlin-reflect"),
                allowed,
            ),
        )
        assertTrue("2 disallowed" in message, message)
        assertTrue("org.jetbrains.kotlin:kotlin-reflect" in message, message)
    }

    @Test
    fun `an empty classpath is a broken check, not a clean module`() {
        // The concrete path: gradle.properties' `kotlin.stdlib.default.dependency = true`
        // is documented as opt-out-able. Flip it and runtimeClasspath resolves nothing,
        // at which point `resolved - allowed` is empty and the gate would pass green
        // forever while the leaf accumulated dependencies.
        val message = assertNotNull(UdeaLeafCheck.violation(":udea-annotations", emptySet(), allowed))
        assertTrue("broken" in message, message)
    }
}
