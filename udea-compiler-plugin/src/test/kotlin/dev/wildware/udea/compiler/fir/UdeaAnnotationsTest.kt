package dev.wildware.udea.compiler.fir

import org.jetbrains.kotlin.name.ClassId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The name binding between the FIR checkers and `udea-annotations`.
 *
 * [UdeaAnnotations] holds [ClassId]s built from string literals, because `udea-annotations` is
 * on the *compiled module's* classpath rather than the plugin's, so there is no `Net::class`
 * to reference. That makes the binding silent when it breaks: rename the package or an
 * annotation and every checker simply stops matching, on a build that is still green.
 * `AnnotationVocabularyTest` in `udea-annotations` freezes the vocabulary from that side;
 * this is the assertion that the plugin still points at it.
 *
 * The load check is the half that matters. Comparing the constants to a second copy of the
 * same strings would only prove a literal equals itself; `Class.forName` fails when the
 * declaration those strings name has moved.
 */
class UdeaAnnotationsTest {

    /**
     * The frozen fully qualified names, in the form `AnnotationVocabularyTest` freezes them.
     * Written out rather than derived, so a rename has to be typed twice, in two modules,
     * deliberately.
     */
    private val frozen: Map<String, ClassId> = mapOf(
        "dev.wildware.udea.annotations.Replicated" to UdeaAnnotations.REPLICATED,
        "dev.wildware.udea.annotations.Net" to UdeaAnnotations.NET,
        "dev.wildware.udea.annotations.Sim" to UdeaAnnotations.SIM,
        "dev.wildware.udea.annotations.Q" to UdeaAnnotations.Q,
    )

    @Test
    fun `every ClassId the checkers match on is the frozen fully qualified name`() {
        frozen.forEach { (fqName, classId) ->
            assertEquals(fqName, classId.asFqNameString())
        }
    }

    @Test
    fun `every name the checkers bind to resolves to a real annotation class`() {
        // The guard the KDoc on UdeaAnnotations promises. `udea-annotations` is on this
        // module's runtime classpath, so a package rename, a moved annotation or a deleted one
        // makes this red - where today the only thing that would notice is an `import` in a
        // compile-testing fixture, and only for the annotations those fixtures happen to use.
        frozen.keys.forEach { fqName ->
            val type = Class.forName(fqName)
            assertTrue(
                type.isAnnotation,
                "$fqName is on the classpath but is not an annotation type; the FIR checkers " +
                    "match declarations against it",
            )
        }
    }

    @Test
    fun `the package constant is the package the annotations are actually in`() {
        assertEquals("dev.wildware.udea.annotations", UdeaAnnotations.PACKAGE.asString())
        assertEquals(
            UdeaAnnotations.PACKAGE.asString(),
            Class.forName("dev.wildware.udea.annotations.Net").packageName,
        )
    }
}
