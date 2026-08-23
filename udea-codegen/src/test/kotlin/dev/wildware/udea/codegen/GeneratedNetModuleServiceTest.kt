package dev.wildware.udea.codegen

import dev.wildware.udea.codegen.fixtures.AiBlackboardReplicator
import dev.wildware.udea.codegen.fixtures.CombatReplicator
import dev.wildware.udea.codegen.fixtures.HealthReplicator
import dev.wildware.udea.codegen.fixtures.MovementReplicator
import dev.wildware.udea.codegen.fixtures.PlacementReplicator
import dev.wildware.udea.codegen.fixtures.QuantisedProbeReplicator
import dev.wildware.udea.core.replication.Replicator
import dev.wildware.udea.net.NetModule
import dev.wildware.udea.net.NetRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Cross-module discovery, *executed*: the generated index compiles against the real service
 * interface, `ServiceLoader` finds it through the generated resource, and the replicators come
 * back.
 *
 * **Why this test and not another string assertion.** `ModuleIndexTest` runs the processor over
 * throwaway sources and matches the emitted text, which is the right test for "what does the
 * processor write". It cannot notice that the emitted text does not *compile*: the index is
 * emitted as `object <Module>NetModule : <service>` with two `override`s, and the service it
 * was pointed at was an `internal object` declaring neither member. Every generated index in
 * the project would have failed to compile three ways over, and nothing said so, because no
 * module set `udea.netModuleService` and the harness never compiles what it generates.
 *
 * So this module now sets the option for its own fixture source set. The index below is a real
 * generated file, compiled by `compileTestKotlin` under `-Werror`, loaded through a real
 * `META-INF/services` resource on a real classpath. If any link in that chain breaks, this test
 * cannot run at all — which is the point.
 */
class GeneratedNetModuleServiceTest {

    /** Every fixture replicator, in the ascending-id order the index claims to be in. */
    private val expected: List<Replicator<*>> = listOf(
        AiBlackboardReplicator,
        CombatReplicator,
        HealthReplicator,
        MovementReplicator,
        PlacementReplicator,
        QuantisedProbeReplicator,
    )

    @Test
    fun `ServiceLoader finds this module's generated index and hands back its replicators`() {
        val modules = NetRegistry.load()

        val index = modules.singleOrNull { it.moduleName == "CodegenFixtures" }
        assertTrue(
            index != null,
            "ServiceLoader found ${modules.map(NetModule::moduleName)}; the generated index is " +
                "reachable only if META-INF/services/${NetModule::class.java.name} is on the " +
                "runtime classpath and the class named in it implements the service through a " +
                "public no-arg constructor",
        )
        assertEquals(
            expected.map { it.typeId.raw },
            index.replicators.map { it.typeId.raw },
        )
        for ((position, replicator) in expected.withIndex()) {
            assertSame(
                replicator,
                index.replicators[position],
                "the index must name its members statically, so each entry is the one object, " +
                    "not a copy the loader constructed",
            )
        }
    }

    @Test
    fun `the flattened protocol is every discovered replicator in ascending id order`() {
        assertEquals(
            expected.map { it.typeId.raw to it.fieldNames },
            NetRegistry.replicators().map { it.typeId.raw to it.fieldNames },
        )
    }

    @Test
    fun `the registry refuses a protocol in which two modules claim one component type id`() {
        // The runtime symptom of a per-module id space (see `CodegenOptions.PROJECT_COMPONENTS`).
        // Two modules that each numbered from zero produce exactly this, and without the check
        // the protocol is built anyway and a peer decodes one component as the other.
        val first = module("First", HealthReplicator)
        val second = module("Second", HealthReplicator)

        val failure = runCatching { NetRegistry.replicators(listOf(first, second)) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException, "expected a refusal, got $failure")
        val message = failure.message.orEmpty()
        assertTrue("First" in message && "Second" in message, message)
        assertTrue(HealthReplicator.typeId.toString() in message, message)
    }

    @Test
    fun `two modules are flattened into one ascending-id protocol whatever order they load in`() {
        // ServiceLoader walks the classpath, and classpath order is a property of how the run
        // was assembled rather than of the protocol. A server and a client that disagreed about
        // it would lay out the same components differently.
        val low = module("Low", AiBlackboardReplicator, CombatReplicator)
        val high = module("High", PlacementReplicator, QuantisedProbeReplicator)

        val forwards = NetRegistry.replicators(listOf(low, high)).map { it.typeId.raw }
        val backwards = NetRegistry.replicators(listOf(high, low)).map { it.typeId.raw }

        assertEquals(forwards, backwards)
        assertEquals(forwards.sorted(), forwards)
    }

    private fun module(name: String, vararg members: Replicator<*>): NetModule = object : NetModule {
        override val moduleName: String = name
        override val replicators: List<Replicator<*>> = members.toList()
    }
}
