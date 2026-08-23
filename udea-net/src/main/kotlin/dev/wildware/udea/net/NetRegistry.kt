package dev.wildware.udea.net

import dev.wildware.udea.core.replication.Replicator
import java.util.ServiceLoader

/**
 * Discovers every [NetModule] on the classpath and flattens them into one protocol.
 *
 * This is the *consumer* half of the generated index. Without it the generated
 * `META-INF/services` line is a file nothing reads, and cross-module discovery is emitted
 * but never exercised — which is exactly how the mechanism it replaces rotted.
 *
 * ## Why the id collision check lives here
 *
 * A component type id is assigned from the whole-project sorted FQN list (spec 5), threaded
 * into each module's KSP run as `udea.projectComponents`. If that option is missing or stale
 * for one module, that module numbers its components from 0 in its own private id space and
 * two modules mint the same [dev.wildware.udea.core.replication.ComponentTypeId] — after
 * which two peers decode each other's packets as the wrong component type, silently. That is
 * a build-configuration mistake with a runtime symptom far from its cause, so [replicators]
 * refuses to produce a protocol at all rather than hand one out with two components on one
 * id.
 */
public object NetRegistry {

    /**
     * Every [NetModule] on [classLoader], in ascending [NetModule.moduleName] order.
     *
     * Sorted rather than in discovery order: `ServiceLoader` walks the classpath, and the
     * classpath order of two jars is a property of how Gradle assembled the run, not of the
     * protocol. A server and a client that disagreed about it would build different worlds
     * from the same modules.
     */
    public fun load(classLoader: ClassLoader = NetRegistry::class.java.classLoader): List<NetModule> =
        ServiceLoader.load(NetModule::class.java, classLoader)
            .toList()
            .sortedBy(NetModule::moduleName)

    /**
     * Every module's replicators in one list, in ascending component type id order.
     *
     * @throws IllegalStateException if two modules claim one component type id, naming both
     *   modules and both replicators — a bare "id 3 collides" sends the reader to grep two
     *   jars, and this does not.
     */
    public fun replicators(modules: List<NetModule> = load()): List<Replicator<*>> {
        val owners = HashMap<Int, String>()
        val all = ArrayList<Replicator<*>>()
        for (module in modules) {
            for (replicator in module.replicators) {
                val owner = "${module.moduleName}'s ${replicator::class.java.simpleName}"
                val existing = owners.put(replicator.typeId.raw, owner)
                check(existing == null) {
                    "${replicator.typeId} is claimed by both $existing and $owner. Component " +
                        "type ids come from one whole-project sorted-FQN assignment, so two " +
                        "modules can only collide when one of them was generated without the " +
                        "project-wide component list; check that every module's KSP run gets " +
                        "`udea.projectComponents`."
                }
                all += replicator
            }
        }
        all.sortBy { it.typeId.raw }
        return all
    }
}
