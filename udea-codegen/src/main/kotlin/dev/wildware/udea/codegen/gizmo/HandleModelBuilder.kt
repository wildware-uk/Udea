package dev.wildware.udea.codegen.gizmo

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import dev.wildware.udea.codegen.AnnotationNames
import dev.wildware.udea.codegen.LevelNames
import dev.wildware.udea.codegen.replicator.toClassName
import dev.wildware.udea.diagnostics.DidYouMean
import dev.wildware.udea.diagnostics.UdeaRules

/**
 * Reads a component's handle annotations into [HandleModel]s, checking every field they name.
 *
 * `@PositionHandle(x = "worldX")` names its field as a string, which the Kotlin compiler cannot
 * check, so this is where a typo is caught: `UDEA0017` at the annotation, with a did-you-mean, and no
 * gizmo for the component at all. Unchecked, the generated gizmo would fail to compile in the game's
 * `editor` source set - another module, a long way from the typo.
 *
 * It reads a component compiled from source in this run and one read off the classpath alike, so the
 * editor source set's run checks a component from another module by the same rules its own module's
 * run already did.
 */
internal class HandleModelBuilder(private val logger: KSPLogger) {

    /**
     * Every handle on [declaration], in a fixed order - the class annotations, then the field ones by
     * field name - or `null`, having reported each problem, when any of them is wrong.
     */
    fun build(declaration: KSClassDeclaration): List<HandleModel>? {
        val name = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
        if (!isComponent(declaration)) {
            logger.error(
                "${UdeaRules.GIZMO_HANDLE_FIELD.id}: $name carries a gizmo handle but is not a Fleks " +
                    "component with a ComponentType companion. A gizmo edits one component, through " +
                    "Component<${declaration.simpleName.asString()}>, and names it by its ComponentType: " +
                    "make it implement Component and declare `companion object : " +
                    "ComponentType<${declaration.simpleName.asString()}>()`.",
                declaration,
            )
            return null
        }
        val properties = declaration.getAllProperties().associateBy { it.simpleName.asString() }
        val check = FieldCheck(name, properties)
        val component = declaration.toClassName()

        val handles = ArrayList<HandleModel>()
        annotation(declaration, AnnotationNames.POSITION_HANDLE)?.let { position ->
            val x = check.required(position, "x")
            val y = check.required(position, "y")
            val z = check.optional(position, "z")
            if (check.distinct(position, listOfNotNull(x, y, z)) && x != null && y != null) {
                handles += HandleModel.Position(component, x, y, z)
            }
        }
        annotation(declaration, AnnotationNames.SIZE_HANDLE)?.let { size ->
            val width = check.required(size, "width")
            val height = check.required(size, "height")
            val depth = check.optional(size, "depth")
            if (check.distinct(size, listOfNotNull(width, height, depth)) && width != null && height != null) {
                handles += HandleModel.Size(component, width, height, depth)
            }
        }
        annotation(declaration, AnnotationNames.ROTATION_HANDLE)?.let { rotation ->
            check.required(rotation, "rotation")?.let { handles += HandleModel.Rotation(component, it) }
        }
        for (property in properties.values.sortedBy { it.simpleName.asString() }) {
            for (kind in ReachKind.entries) {
                if (!carries(declaration, property, kind.annotationFqn)) continue
                if (check.floatVar(property, "@${kind.annotation}")) {
                    handles += HandleModel.Reach(component, property.simpleName.asString(), kind)
                }
            }
        }
        return if (check.failed) null else handles
    }

    /**
     * Whether [property] carries the one-field handle [fqn].
     *
     * A constructor property's annotation is looked for on the parameter too: which of the two a
     * property-targeted annotation is reported on is the front end's business, and a handle missed
     * here is a gizmo silently not generated.
     */
    private fun carries(declaration: KSClassDeclaration, property: KSPropertyDeclaration, fqn: String): Boolean =
        isNamed(property, fqn) ||
            declaration.primaryConstructor?.parameters
                ?.firstOrNull { it.name?.asString() == property.simpleName.asString() }
                ?.let { isNamed(it, fqn) } == true

    private fun isComponent(declaration: KSClassDeclaration): Boolean {
        if (declaration.classKind != ClassKind.CLASS) return false
        val implementsComponent = declaration.getAllSuperTypes()
            .any { it.declaration.qualifiedName?.asString() == LevelNames.FLEKS_COMPONENT }
        val companionIsType = declaration.declarations
            .filterIsInstance<KSClassDeclaration>()
            .firstOrNull { it.isCompanionObject }
            ?.getAllSuperTypes()
            ?.any { it.declaration.qualifiedName?.asString() == EditorNames.COMPONENT_TYPE_FQN } == true
        return implementsComponent && companionIsType
    }

    /**
     * The checks every field name goes through, reporting as it goes, and remembering whether any
     * failed so the component generates nothing rather than a gizmo missing a handle.
     */
    private inner class FieldCheck(
        private val owner: String,
        private val properties: Map<String, KSPropertyDeclaration>,
    ) {
        var failed: Boolean = false
            private set

        /** The mutable `Float` properties, which are the only names a handle may use. */
        private val floats: List<String> =
            properties.values.filter(::isFloatVar).map { it.simpleName.asString() }.sorted()

        /** [parameter]'s field, which must be named and must be a float var. */
        fun required(annotation: KSAnnotation, parameter: String): String? {
            val field = argument(annotation, parameter)
            if (field.isEmpty()) {
                report(annotation, "@${annotation.shortName.asString()}($parameter = \"\") on $owner names no field for $parameter, which it needs")
                return null
            }
            return field.takeIf { named(annotation, parameter, it) }
        }

        /** [parameter]'s field, absent when it is the empty string: a 2D handle has no third axis. */
        fun optional(annotation: KSAnnotation, parameter: String): String? {
            val field = argument(annotation, parameter)
            if (field.isEmpty()) return null
            return field.takeIf { named(annotation, parameter, it) }
        }

        /** True unless [fields] names one field twice, which would write it twice per drag. */
        fun distinct(annotation: KSAnnotation, fields: List<String>): Boolean {
            val twice = fields.groupBy { it }.filterValues { it.size > 1 }.keys.sorted()
            for (field in twice) {
                report(annotation, "@${annotation.shortName.asString()} on $owner names '$field' twice; each axis drives a field of its own")
            }
            return twice.isEmpty()
        }

        /** True when [property], marked with [annotation], is a float var. */
        fun floatVar(property: KSPropertyDeclaration, annotation: String): Boolean {
            val problem = problemWith(property) ?: return true
            report(property, "$annotation marks $owner.${property.simpleName.asString()} - '${property.simpleName.asString()}' $problem")
            return false
        }

        private fun named(annotation: KSAnnotation, parameter: String, field: String): Boolean {
            val property = properties[field]
            val prefix = "@${annotation.shortName.asString()}($parameter = \"$field\") on $owner names '$field'"
            if (property == null) {
                val suggestion = DidYouMean.suggest(field, floats)
                val hint = if (suggestion != null) {
                    "did you mean '$suggestion'?"
                } else if (floats.isEmpty()) {
                    "it has no mutable Float property at all"
                } else {
                    "the mutable Float properties it has are ${floats.joinToString()}"
                }
                report(annotation, "$prefix, which it does not declare", hint)
                return false
            }
            val problem = problemWith(property) ?: return true
            report(annotation, "$prefix, which $problem")
            return false
        }

        /** Reports [message] at [node], ending with [hint] - the did-you-mean - when there is one. */
        private fun report(node: KSNode, message: String, hint: String? = null) {
            failed = true
            logger.error(
                "${UdeaRules.GIZMO_HANDLE_FIELD.id}: $message. A handle's drag writes a Float into the " +
                    "field it names" + (hint?.let { "; $it" } ?: "."),
                node,
            )
        }
    }

    /** Why [property] cannot be dragged, or `null` when it can. */
    private fun problemWith(property: KSPropertyDeclaration): String? {
        val type = property.type.resolve()
        val typeName = type.declaration.qualifiedName?.asString() ?: type.declaration.simpleName.asString()
        return when {
            typeName != FLOAT || type.isMarkedNullable ->
                "is ${typeName}${if (type.isMarkedNullable) "?" else ""}, not Float"
            !property.isMutable -> "is a val, and a drag has to write it"
            else -> null
        }
    }

    private fun isFloatVar(property: KSPropertyDeclaration): Boolean = problemWith(property) == null

    private fun annotation(declaration: KSClassDeclaration, fqn: String): KSAnnotation? =
        declaration.annotations.firstOrNull { it.fqn() == fqn }

    private fun isNamed(node: KSAnnotated, fqn: String): Boolean = node.annotations.any { it.fqn() == fqn }

    private fun KSAnnotation.fqn(): String? = annotationType.resolve().declaration.qualifiedName?.asString()

    /**
     * A string argument by [name], or the annotation's own default when the source left it out -
     * read from the annotation declaration, so the defaults live in `udea-annotations` alone.
     */
    private fun argument(annotation: KSAnnotation, name: String): String =
        (annotation.arguments.firstOrNull { it.name?.asString() == name }?.value as? String)
            ?: (annotation.defaultArguments.firstOrNull { it.name?.asString() == name }?.value as? String)
            ?: error("KSP reported neither a value nor a default for $name on @${annotation.shortName.asString()}")

    private companion object {
        const val FLOAT = "kotlin.Float"
    }
}
