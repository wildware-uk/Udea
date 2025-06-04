package dev.wildware.udea.ecs.component

@Target(AnnotationTarget.CLASS)
@Retention
annotation class UdeaClass(
    vararg val value: UClassAttribute = []
) {
    enum class UClassAttribute {
        /**
         * This class can't be inline edited in the editor.
         * */
        NoInline,
    }
}

/**
 * Annotate a property with this field to specify extra information.
 * @property name Display name for this property.
 * */
@Target(AnnotationTarget.PROPERTY)
@Retention
annotation class UdeaProperty(
    vararg val value: UAttribute = [],
    val name: String = Undefined
) {
    enum class UAttribute {
        /**
         * This property will be ignored by the editor.
         * */
        Ignore,

        /**
         * This property will be edited inline.
         * */
        Inline
    }

    companion object {
        private const val Undefined = ""
    }
}