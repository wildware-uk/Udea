package dev.wildware.udea.build

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stdlib pin is the one thing standing between a build-time-only jar and a
 * `NoSuchMethodError` at `-Xplugin` / processor load. These drive the rule, so its failure
 * paths run in CI rather than being taken on trust.
 */
class UdeaKotlinPinTest {

    private val pinned = UdeaVersions.KOTLIN

    @Test
    fun `a classpath at the pinned version passes`() {
        assertNull(
            UdeaKotlinPin.violation(
                ":udea-compiler-plugin",
                pinned,
                listOf("compileClasspath kotlin-stdlib:$pinned", "runtimeClasspath kotlin-stdlib:$pinned"),
            ),
        )
    }

    @Test
    fun `a stdlib dragged forward by a transitive dependency fails and is named`() {
        // Exactly the live case: KotlinPoet 2.3.0 requests kotlin-stdlib 2.3.20 and Fleks
        // 2.14 requests 2.3.21, and Gradle's highest-wins resolution would put them on a
        // jar the 2.2.10 compiler loads.
        val message = assertNotNull(
            UdeaKotlinPin.violation(
                ":udea-codegen",
                pinned,
                listOf("compileClasspath kotlin-stdlib:2.3.20", "runtimeClasspath kotlin-stdlib:$pinned"),
            ),
        )
        assertTrue("2.3.20" in message, message)
        assertTrue(":udea-codegen" in message, message)
        assertTrue("runtimeClasspath" !in message, "only the offending configuration is named: $message")
    }

    @Test
    fun `a stdlib variant is checked too, not just the base artifact`() {
        val message = assertNotNull(
            UdeaKotlinPin.violation(
                ":udea-assets-compiler",
                pinned,
                listOf("runtimeClasspath kotlin-stdlib-jdk8:2.3.21"),
            ),
        )
        assertTrue("kotlin-stdlib-jdk8:2.3.21" in message, message)
    }

    @Test
    fun `an empty classpath is a broken check, not a compliant module`() {
        val message = assertNotNull(UdeaKotlinPin.violation(":udea-codegen", pinned, emptyList()))
        assertTrue("broken" in message, message)
    }
}
