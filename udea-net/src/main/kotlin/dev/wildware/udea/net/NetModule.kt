package dev.wildware.udea.net

import dev.wildware.udea.core.replication.Replicator

/**
 * One Gradle module's contribution to the wire protocol, discovered through `ServiceLoader`.
 *
 * `udea-codegen` generates exactly one implementation per module that contributes
 * `@Replicated` components — `public class MobaNetModule : NetModule` — and the matching
 * `META-INF/services/dev.wildware.udea.net.NetModule` line beside it. This interface is the
 * shape that generated class is written against, which is why it lives in `udea-net` rather
 * than in the generator: generated code may only implement an interface that exists on the
 * module's own compile classpath.
 *
 * **A class and not a Kotlin `object`, and that is not a style choice.** `ServiceLoader`
 * instantiates a provider it finds on the classpath through a public no-arg constructor,
 * which an `object` does not have — its constructor is private and the instance lives in
 * `INSTANCE`. An index emitted as an `object` compiles, links, and then fails on the first
 * packet with `ServiceConfigurationError: … NoSuchMethodException: <init>()`. Anyone editing
 * `ServiceIndexEmitter` should read that before switching `classBuilder` back;
 * `GeneratedNetModuleServiceTest` loads a real generated index through a real `ServiceLoader`
 * and is what turns the mistake into a failing build instead of a runtime crash.
 *
 * **This replaces the magic package.** The generator being retired listed a module's
 * serializers in an annotation on a class in `dev.wildware._serializer_`, under a name
 * containing `System.currentTimeMillis()`, and fell back to an `org.reflections` classpath
 * scan at run time. None of that survives R8 and none of it is deterministic. Every member
 * named by an implementation of this interface is a static reference, so resolution costs a
 * class-load, R8 keeps the replicators because they are genuinely referenced, and two builds
 * of the same sources discover the same modules in the same order.
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
