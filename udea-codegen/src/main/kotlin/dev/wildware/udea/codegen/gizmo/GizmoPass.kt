package dev.wildware.udea.codegen.gizmo

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.isLocal
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import dev.wildware.udea.codegen.AnnotationNames
import dev.wildware.udea.codegen.CodegenOptions
import dev.wildware.udea.codegen.GeneratedNames
import dev.wildware.udea.codegen.SourceSetScope
import dev.wildware.udea.codegen.replicator.toClassName
import dev.wildware.udea.diagnostics.UdeaRules

/**
 * What the processor's gizmo pass reads (issue #233). It finds; [GizmoEmitter] writes.
 *
 * A game's gizmos are split across two KSP runs, because its components and its gizmos may not live
 * in one module: a `Gizmo` implements a `udea-editor` type, and only an `editor` source set may see
 * one (`UDEA-MG-010`, `UDEA-MG-012`).
 *
 * - **The module's run** ([own]) finds the handle annotations in its sources and checks them. It
 *   generates no gizmo; it lists the components on its module registry's `@HandleIndex`.
 * - **The editor source set's run** ([indexed], [handWritten]) reads that index off each module
 *   registry the build names - by exact class name, the lookup the launcher registry already makes
 *   - and generates a gizmo per annotation, plus the `<Game>GizmoRegistry` naming them and every
 *   hand-written gizmo in its own sources.
 *
 * A run may be both, as `udea-codegen`'s own fixtures are, and then it generates from its own
 * sources directly.
 */
internal class GizmoPass(private val logger: KSPLogger, private val scope: SourceSetScope) {

    private val builder = HandleModelBuilder(logger)

    /** A component with handles, and the handles, checked. [file] is `null` for one off the classpath. */
    class Handled(val component: ClassName, val handles: List<HandleModel>, val file: KSFile?)

    /** A hand-written gizmo the registry can name: an object as itself, a class by its no-arg constructor. */
    class HandWritten(val className: ClassName, val expression: CodeBlock, val file: KSFile)

    /**
     * The components in this round's sources that carry a handle, sorted by name - or `null`, having
     * reported every problem, when any handle is wrong.
     */
    fun own(resolver: Resolver): List<Handled>? {
        val owners = LinkedHashMap<String, KSClassDeclaration>()
        var orphaned = false
        for (fqn in HANDLE_ANNOTATIONS) {
            for (symbol in resolver.getSymbolsWithAnnotation(fqn)) {
                val owner = ownerOf(symbol)
                if (owner == null) {
                    orphaned = true
                    logger.error(
                        "${UdeaRules.GIZMO_HANDLE_FIELD.id}: @${fqn.substringAfterLast('.')} marks something " +
                            "that is not a property of a class. A one-field handle marks a Float var of a " +
                            "Fleks component.",
                        symbol,
                    )
                    continue
                }
                if (!scope.admitsFile(owner.containingFile)) continue
                owners[owner.qualifiedName?.asString() ?: owner.simpleName.asString()] = owner
            }
        }
        val checked = owners.toSortedMap().values.map { it to builder.build(it) }
        if (orphaned || checked.any { it.second == null }) return null
        return checked.map { (declaration, handles) ->
            Handled(declaration.toClassName(), checkNotNull(handles), declaration.containingFile)
        }
    }

    /**
     * The components each module in [modules] indexed on its registry, checked, sorted by name - or
     * `null`, having reported it, when a module's registry is missing or one of its handles is wrong.
     */
    fun indexed(resolver: Resolver, modules: List<String>): List<Handled>? {
        val found = sortedMapOf<String, KSClassDeclaration>()
        var failed = false
        for (module in modules.distinct().sorted()) {
            val registry = GeneratedNames.moduleRegistry(module)
            val declaration = if (CodegenOptions.MODULE_NAME_FORMAT.matches(module)) {
                resolver.getClassDeclarationByName(resolver.getKSNameFromString(registry.canonicalName))
            } else {
                null
            }
            if (declaration == null) {
                failed = true
                logger.error(
                    "${CodegenOptions.REGISTRY_MODULES} lists module '$module', but " +
                        "${registry.canonicalName} is not on this source set's classpath, so its gizmo " +
                        "handles cannot be read. The build lists every module on the classpath that " +
                        "declared itself with udeaModule; check that '$module' runs udea-codegen.",
                )
                continue
            }
            for (type in indexOf(declaration)) {
                val component = type.declaration as? KSClassDeclaration ?: continue
                found[component.qualifiedName?.asString() ?: component.simpleName.asString()] = component
            }
        }
        val checked = found.values.map { it to builder.build(it) }
        if (failed || checked.any { it.second == null }) return null
        return checked.map { (declaration, handles) -> Handled(declaration.toClassName(), checkNotNull(handles), null) }
    }

    /**
     * Every hand-written `Gizmo` in this run's sources the registry can name, sorted by name - or
     * `null`, having reported it, when one cannot be constructed.
     *
     * A private or local gizmo is its file's own business, and an abstract one is not a gizmo yet, so
     * those are not listed. One the registry would have to call with arguments is an error: it is a
     * gizmo nobody would ever see, which is worse than a build that says so.
     */
    fun handWritten(resolver: Resolver): List<HandWritten>? {
        val found = ArrayList<HandWritten>()
        var failed = false
        for (file in resolver.getAllFiles()) {
            if (!scope.admitsFile(file)) continue
            for (declaration in classesIn(file.declarations)) {
                if (!isGizmo(declaration) || !isVisible(declaration) || isAbstract(declaration)) continue
                val className = declaration.toClassName()
                val expression = when {
                    declaration.classKind == ClassKind.OBJECT -> CodeBlock.of("%T", className)
                    constructible(declaration) -> CodeBlock.of("%T()", className)
                    else -> {
                        failed = true
                        logger.error(
                            "${className.canonicalName} is a Gizmo, but the generated gizmo registry cannot " +
                                "construct it: it needs a no-argument constructor, or to be an object, and " +
                                "must be neither generic nor inner. Give its parameters defaults, or make it " +
                                "an object.",
                            declaration,
                        )
                        continue
                    }
                }
                found += HandWritten(className, expression, file)
            }
        }
        return if (failed) null else found.sortedBy { it.className.canonicalName }
    }

    /** The class a handle annotation on [symbol] belongs to, or `null` when it belongs to none. */
    private fun ownerOf(symbol: KSAnnotated): KSClassDeclaration? = when (symbol) {
        is KSClassDeclaration -> symbol
        is KSPropertyDeclaration -> symbol.parentDeclaration as? KSClassDeclaration
        is KSValueParameter -> ((symbol.parent as? KSFunctionDeclaration)?.parentDeclaration as? KSClassDeclaration)
        else -> null
    }

    /** The component types listed on [registry]'s `@HandleIndex`, or none when it has no index. */
    private fun indexOf(registry: KSClassDeclaration): List<KSType> {
        val index = registry.annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == AnnotationNames.HANDLE_INDEX
        } ?: return emptyList()
        val components = index.arguments.firstOrNull { it.name?.asString() == "components" }?.value
        return (components as? List<*>).orEmpty().filterIsInstance<KSType>()
    }

    private fun classesIn(declarations: Sequence<KSDeclaration>): Sequence<KSClassDeclaration> =
        declarations.filterIsInstance<KSClassDeclaration>().flatMap { sequenceOf(it) + classesIn(it.declarations) }

    private fun isGizmo(declaration: KSClassDeclaration): Boolean =
        (declaration.classKind == ClassKind.CLASS || declaration.classKind == ClassKind.OBJECT) &&
            declaration.getAllSuperTypes().any { it.declaration.qualifiedName?.asString() == EditorNames.GIZMO_FQN }

    /** Not local, and neither it nor any class around it is private. */
    private fun isVisible(declaration: KSClassDeclaration): Boolean {
        var current: KSDeclaration? = declaration
        while (current != null) {
            if (current.isLocal() || current.isPrivate()) return false
            current = current.parentDeclaration
        }
        return true
    }

    private fun isAbstract(declaration: KSClassDeclaration): Boolean =
        Modifier.ABSTRACT in declaration.modifiers || Modifier.SEALED in declaration.modifiers

    private fun constructible(declaration: KSClassDeclaration): Boolean =
        declaration.typeParameters.isEmpty() &&
            Modifier.INNER !in declaration.modifiers &&
            declaration.getConstructors().any { constructor ->
                !constructor.isPrivate() && constructor.parameters.all { it.hasDefault }
            }

    private companion object {
        val HANDLE_ANNOTATIONS: List<String> = listOf(
            AnnotationNames.POSITION_HANDLE,
            AnnotationNames.SIZE_HANDLE,
            AnnotationNames.ROTATION_HANDLE,
            AnnotationNames.SCALE_HANDLE,
            AnnotationNames.RADIUS_HANDLE,
            AnnotationNames.RANGE_HANDLE,
        )
    }
}
