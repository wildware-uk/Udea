package dev.wildware.udea.net

import dev.wildware.udea.core.replication.Replicator

/**
 * One Gradle module's contribution to the wire protocol: the facet a generated module registry
 * implements to list its replicators (issue #202).
 *
 * `udea-codegen` makes `<Module>ModuleRegistry` implement this for every module that contributes
 * `@Replicated` components and whose build names this interface in `udea.netModuleService`. The
 * interface lives in `udea-net` rather than in the generator because generated code may only
 * implement an interface that exists on the module's own compile classpath. [NetRegistry.modules]
 * picks the facets out of a game's `UdeaRegistry`.
 *
 * **This replaces the magic package.** The generator being retired listed a module's
 * serializers in an annotation on a class in `dev.wildware._serializer_`, under a name
 * containing `System.currentTimeMillis()`, and fell back to an `org.reflections` classpath
 * scan at run time. None of that survives R8 and none of it is deterministic. Every member
 * named by an implementation of this interface is a static reference, so resolution costs a
 * class-load, R8 keeps the replicators because they are genuinely referenced, and two builds
 * of the same sources list the same modules in the same order.
 */
public interface NetModule {

    /** The Gradle module this index was generated for, in `UpperCamelCase` — e.g. `Moba`. */
    public val moduleName: String

    /**
     * Every `Replicator` generated for this module, in ascending
     * [dev.wildware.udea.core.replication.ComponentTypeId] order.
     *
     * Ascending id is the canonical order everything else in the engine walks — the snapshot
     * registry, the world hash, the packet layout — so an index that returned them in
     * discovery order would push a sort into every consumer.
     */
    public val replicators: List<Replicator<*>>
}
