package dev.wildware.udea.build

import org.gradle.api.Project
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.ResolvedVariantResult
import org.gradle.api.attributes.Attribute
import org.gradle.api.provider.Provider

/**
 * How a launcher's generated `UdeaRegistry` learns which modules it contains (issue #202).
 *
 * `udea-codegen` emits one `<Module>ModuleRegistry` per module that runs it, and one
 * `<Module>UdeaRegistry` that names every module registry on that module's runtime classpath as
 * a static reference. That second list is what replaced `ServiceLoader`, and where it comes from
 * is the whole design: the processor sees one Gradle module, so the names reach it as data the
 * build computed, in [REGISTRY_MODULES_OPTION], the same way `udea.projectComponents` does.
 *
 * ## Where the list comes from
 *
 * A module states its name once, through [udeaModule]. That sets nothing but a string: the name
 * goes onto every variant the module publishes, as [MODULE_ATTRIBUTE]. A launcher's list is then
 * read off its own **resolved** runtime classpath - every component on it whose variant carries
 * the attribute, plus the launcher itself.
 *
 * So the list is not a second copy of anything. Which modules a launcher contains is exactly
 * what its dependency declarations already say, and a module it depends on cannot be left out of
 * its registry by forgetting to write it down somewhere else. What *can* go wrong is a listed
 * module whose registry was never generated, and that is the case the processor turns into a
 * compile error.
 *
 * It is deliberately not a checked-in file like `net-components.lock`. A component id is a wire
 * promise, so inserting one has to be a reviewed diff; membership of a launcher is not a promise
 * to anybody outside the build, and a second list of it could only drift from the first.
 */
public object UdeaModuleRegistry {

    /** Mirrors `CodegenOptions.MODULE_NAME`. */
    public const val MODULE_NAME_OPTION: String = "udea.moduleName"

    /** Mirrors `CodegenOptions.REGISTRY_MODULES`. */
    public const val REGISTRY_MODULES_OPTION: String = "udea.registryModules"

    /**
     * Mirrors `CodegenOptions.GIZMO_REGISTRY` (issue #233): set on a game's `editor` source set, it
     * names the game whose `<Game>GizmoRegistry` that run generates.
     */
    public const val GIZMO_REGISTRY_OPTION: String = "udea.gizmoRegistry"

    /** How [REGISTRY_MODULES_OPTION] separates names; a module name is letters and digits only. */
    internal const val SEPARATOR: Char = ','

    /**
     * The module name, stamped on every variant a Udea codegen module publishes.
     *
     * A `String` attribute rather than a capability or a published file: an attribute travels
     * with the variant into the consumer's resolution result without the consumer requesting
     * it, and without a task having run, so reading it costs nothing but the resolution the
     * compile needed anyway.
     */
    internal val MODULE_ATTRIBUTE: Attribute<String> =
        Attribute.of("dev.wildware.udea.moduleName", String::class.java)

    /**
     * The module names on a resolved graph, not counting the root, in ascending order.
     *
     * Every component reachable from [root] is visited once, and every variant of it is read,
     * because one project can be selected through more than one variant.
     */
    internal fun moduleNames(root: ResolvedComponentResult): List<String> {
        val names = sortedSetOf<String>()
        val visited = HashSet<ResolvedComponentResult>()
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            val component = pending.removeFirst()
            if (!visited.add(component)) continue
            if (component !== root) component.variants.mapNotNullTo(names, ::moduleName)
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().mapTo(pending) { it.selected }
        }
        return names.toList()
    }

    /**
     * The option value: [self] and every module in [dependencies], deduplicated and sorted.
     *
     * Sorted here only so the option string is stable; the processor sorts the registries it
     * emits by fully-qualified name, which is the order the spec fixes.
     */
    internal fun optionValue(self: String, dependencies: List<String>): String =
        (dependencies + self).toSortedSet().joinToString(SEPARATOR.toString())

    private fun moduleName(variant: ResolvedVariantResult): String? {
        val attributes = variant.attributes
        // Looked up by name, not by the typed key: a resolution result may hand an attribute
        // back desugared, and a typed lookup would then quietly answer null.
        val key = attributes.keySet().firstOrNull { it.name == MODULE_ATTRIBUTE.name } ?: return null
        return attributes.getAttribute(key)?.toString()
    }
}

/**
 * What a module's `ksp { }` block passes to `udea-codegen`: its name, and the launcher list.
 *
 * @property name the value for [UdeaModuleRegistry.MODULE_NAME_OPTION].
 * @property registryModules the value for [UdeaModuleRegistry.REGISTRY_MODULES_OPTION], computed
 *   lazily from the resolved classpath.
 */
public class UdeaModuleOptions(
    public val name: String,
    public val registryModules: Provider<String>,
)

/**
 * Declares this project a Udea codegen module called [name].
 *
 * Stamps [name] on every variant this project publishes, and returns the two KSP option values.
 * The build script hands them to `ksp { }` itself, because `build-logic` does not put the KSP
 * Gradle plugin on its classpath - every module applies that plugin by id and version, and a
 * second copy here would be a version the catalog does not govern.
 *
 * The launcher list is read from `runtimeClasspath`, which is the classpath a program started
 * from this module's main sources actually has. A multiplatform module has one per target and
 * generates its registry once, into common code, so it reads the JVM target's,
 * `jvmRuntimeClasspath`, because the JVM is the authoritative target (spec D3, issue #203).
 */
public fun Project.udeaModule(name: String): UdeaModuleOptions {
    configurations.configureEach {
        if (isCanBeConsumed && !isCanBeResolved) {
            attributes.attribute(UdeaModuleRegistry.MODULE_ATTRIBUTE, name)
        }
    }
    val classpath = if (plugins.hasPlugin(MULTIPLATFORM_PLUGIN_ID)) "jvmRuntimeClasspath" else "runtimeClasspath"
    return UdeaModuleOptions(name, udeaRegistryModules(name, classpath))
}

/** The Kotlin multiplatform plugin's id, which [udeaModule] reads a module's layout from. */
private const val MULTIPLATFORM_PLUGIN_ID: String = "org.jetbrains.kotlin.multiplatform"

/**
 * The launcher list for a KSP run over [classpath], without declaring this project a module.
 *
 * For `udea-codegen`, whose processor runs over its own **test** fixtures: the fixture module's
 * registry lives in test output that nothing else can depend on, so stamping its name on the
 * variants this project publishes - the processor jar itself - would put a module in other
 * launchers' lists whose registry they can never see.
 */
public fun Project.udeaRegistryModules(self: String, classpath: String): Provider<String> =
    configurations.getByName(classpath).incoming.resolutionResult.rootComponent
        .map { root -> UdeaModuleRegistry.optionValue(self, UdeaModuleRegistry.moduleNames(root)) }
