package dev.wildware.udea.compiler.fir

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * The `udea-annotations` vocabulary, as the [ClassId]s FIR matches declarations against.
 *
 * The plugin binds by name rather than by class: `udea-annotations` is on the *compiled
 * module's* classpath, not on the plugin's, so there is no `Net::class` to reference here.
 * `AnnotationVocabularyTest` in `udea-annotations` freezes these fully qualified names, and
 * `UdeaAnnotationsTest` asserts the strings below are still the
 * ones it freezes *and* that each one still loads — a name-based binding fails silently when
 * the declaration moves, so an assertion that only compared two copies of the same literal
 * would be checking nothing.
 */
internal object UdeaAnnotations {

    /** The package every Udea annotation lives in. */
    val PACKAGE: FqName = FqName("dev.wildware.udea.annotations")

    /** `@Replicated`, the class-level marker. */
    val REPLICATED: ClassId = classId("Replicated")

    /** `@Net`, replicated and snapshotted. */
    val NET: ClassId = classId("Net")

    /** `@Sim`, snapshotted only. */
    val SIM: ClassId = classId("Sim")

    /** `@Q`, wire quantisation. */
    val Q: ClassId = classId("Q")

    private fun classId(simpleName: String): ClassId =
        ClassId(PACKAGE, Name.identifier(simpleName))
}
