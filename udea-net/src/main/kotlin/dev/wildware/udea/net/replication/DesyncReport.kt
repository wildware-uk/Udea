package dev.wildware.udea.net.replication

import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.replication.MaskOps
import dev.wildware.udea.core.snapshot.ComponentRegistry
import dev.wildware.udea.core.snapshot.FieldComparison
import dev.wildware.udea.core.snapshot.WorldFieldStore
import dev.wildware.udea.net.wire.ReplicaStore

/** One field on which the server and a client disagree. */
public data class Desync(
    public val netId: NetId,
    public val componentName: String,
    public val fieldName: String,
    public val serverValue: Any?,
    public val clientValue: Any?,
) {
    override fun toString(): String = "$netId.$componentName.$fieldName: server=$serverValue client=$clientValue"
}

/**
 * `net.desync_report(tick)`: a field-by-field comparison of server state against a client's.
 *
 * A **field** comparison and not a byte or hash comparison, and issue #107 is explicit about
 * why: both sides run the same `Replicator` over the same `FieldStore` layout (spec 3.1), so the
 * comparison can name the entity, the component and the field that differ. A hash compare can
 * only say "somewhere". "Somewhere" is what makes a desync a week of work.
 *
 * Only `@Net` fields are compared. A `@Sim` field is snapshotted and never replicated, so the
 * client having no value for it is correct, and reporting it would fill the report with noise
 * that can never be fixed.
 */
public object DesyncReport {

    /**
     * Every replicated field on which [client] disagrees with [server].
     *
     * Empty means the client has converged. An entity present on one side only is reported once
     * per component it carries, naming what is missing rather than only that something is.
     */
    public fun compare(
        registry: ComponentRegistry,
        server: WorldFieldStore,
        client: ReplicaStore,
    ): List<Desync> {
        require(client.registry === registry) { "the client store was built against another registry" }
        val report = ArrayList<Desync>()

        for (row in 0 until server.rowCount) {
            val netId = server.netIdAt(row)
            val clientRow = client.rowOf(netId)
            for (component in 0 until registry.size) {
                if (!server.isPresent(row, component)) continue
                val schema = registry.schemaAt(component)
                val replicator = registry.typeAt(component).replicator
                val serverStore = server.storeAt(component)
                val serverSlot = server.componentSlotAt(row, component)
                val clientSlot = if (clientRow == ReplicaStore.ABSENT) {
                    ReplicaStore.ABSENT
                } else {
                    client.slotOf(clientRow, component)
                }
                if (clientSlot == ReplicaStore.ABSENT) {
                    report += Desync(netId, schema.typeName, "<component>", "present", "absent")
                    continue
                }
                val clientStore = client.storeAt(component)
                for (field in 0 until schema.fieldCount) {
                    if (!MaskOps.test(replicator.netMask, field)) continue
                    if (serverStore.fieldEquals(serverSlot, clientStore, clientSlot, field, FieldComparison.Bitwise)) {
                        continue
                    }
                    report += Desync(
                        netId,
                        schema.typeName,
                        schema.nameOf(field),
                        serverStore.valueAt(serverSlot, field),
                        clientStore.valueAt(clientSlot, field),
                    )
                }
            }
        }

        for (row in 0 until client.rowHighWater) {
            if (!client.isLive(row)) continue
            val netId = client.netIdAt(row)
            if (server.rowOf(netId) != WorldFieldStore.NO_ROW) continue
            report += Desync(netId, "<entity>", "<presence>", "absent", "present")
        }

        return report
    }
}
