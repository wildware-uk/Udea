package dev.wildware.udea.codegen

import dev.wildware.udea.codegen.fixtures.AiBlackboardReplicator
import dev.wildware.udea.codegen.fixtures.CombatReplicator
import dev.wildware.udea.codegen.fixtures.HealthReplicator
import dev.wildware.udea.codegen.fixtures.MovementReplicator
import dev.wildware.udea.codegen.fixtures.PlacementReplicator
import dev.wildware.udea.codegen.fixtures.QuantisedProbeReplicator
import dev.wildware.udea.core.registry.ModuleRegistry
import dev.wildware.udea.core.replication.Replicator
import dev.wildware.udea.generated.CodegenFixturesModuleRegistry
import dev.wildware.udea.generated.CodegenFixturesUdeaRegistry
import dev.wildware.udea.generated.CoreModuleRegistry
import dev.wildware.udea.net.NetModule
import dev.wildware.udea.net.NetRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The generated registries, *compiled and executed*: the module registry implements the real
 * facet interface, the launcher registry names it, and the replicators come back (issue #202).
 *
 * **Why this test and not another string assertion.** `ModuleRegistryTest` runs the processor
 * over throwaway sources and matches the emitted text, which is the right test for "what does the
 * processor write". It cannot notice that the emitted text does not *compile*: the index this
 * replaced was once emitted against a service that was an `internal object` declaring neither
 * member, and every generated index in the project would have failed to compile three ways over
 * with nothing saying so, because the harness never compiles what it generates.
 *
 * So this module's own fixture source set runs with the options a real module has. The registries
 * below are real generated files, compiled by `compileTestKotlin` under `-Werror`, and the launcher
 * registry names `udea-core`'s registry from a jar on the test classpath. If any link in that
 * chain breaks, this test cannot run at all - which is the point.
 */
class GeneratedModuleRegistryTest {

    /** Every fixture replicator, in the ascending-id order the facet claims to be in. */
    private val expected: List<Replicator<*>> = listOf(
        AiBlackboardReplicator,
        CombatReplicator,
        HealthReplicator,
        MovementReplicator,
        PlacementReplicator,
        QuantisedProbeReplicator,
    )

    @Test
    fun `the launcher registry names this module's registry and the modules on its classpath, in FQN order`() {
        val modules: List<ModuleRegistry> = CodegenFixturesUdeaRegistry.modules

        assertTrue(CodegenFixturesModuleRegistry in modules, "listed ${modules.map { it.moduleName }}")
        // udea-core is on this module's test runtime classpath and declares itself a module, so
        // the build lists it without this module's build script naming it anywhere.
        assertTrue(CoreModuleRegistry in modules, "listed ${modules.map { it.moduleName }}")
        val names = modules.map { it::class.qualifiedName.orEmpty() }
        assertEquals(names.sorted(), names, "a launcher registry lists its modules in ascending FQN order")
    }

    @Test
    fun `the net facet of this module's registry hands back its replicators, statically`() {
        val index = NetRegistry.modules(CodegenFixturesUdeaRegistry).single { it.moduleName == "CodegenFixtures" }

        assertSame(CodegenFixturesModuleRegistry, index)
        assertEquals(expected.map { it.typeId.raw }, index.replicators.map { it.typeId.raw })
        for ((position, replicator) in expected.withIndex()) {
            assertSame(
                replicator,
                index.replicators[position],
                "the registry must name its members statically, so each entry is the one object",
            )
        }
    }

    @Test
    fun `the flattened protocol is every listed replicator in ascending id order`() {
        assertEquals(
            expected.map { it.typeId.raw to it.fieldNames },
            NetRegistry.replicators(NetRegistry.modules(CodegenFixturesUdeaRegistry))
                .map { it.typeId.raw to it.fieldNames },
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
    fun `two modules are flattened into one ascending-id protocol whatever order they are listed in`() {
        // A server and a client whose launchers listed the same modules in a different order
        // must still lay out the same components identically.
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
