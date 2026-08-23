package dev.wildware.udea.core.module

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * An order that cannot be realised fails world construction, loudly and by name.
 *
 * The alternative — resolve what can be resolved and run the rest in some arbitrary order — is
 * what makes a desync that only reproduces on one machine, because "some arbitrary order" is
 * whatever that JVM's hash iteration produced. So every unsatisfiable constraint throws, before
 * a single tick runs, with enough in the message to fix it.
 */
class SystemCycleTest {

    private fun defWith(configure: SimRegistry.() -> Unit): UdeaGameDef =
        UdeaGameDef(
            listOf(
                object : UdeaModule {
                    override fun simulation(registry: SimRegistry) = registry.configure()
                },
            ),
        )

    @Test
    fun `a two-system cycle fails world construction naming both systems`() {
        val failure = assertFailsWith<SystemOrderException> {
            defWith {
                add(SimPhase.Gameplay, { CycleA() }) { after<CycleB>() }
                add(SimPhase.Gameplay, { CycleB() }) { after<CycleA>() }
            }.build()
        }

        val message = failure.message.orEmpty()
        assertTrue(CycleA::class.java.name in message, "message names CycleA: $message")
        assertTrue(CycleB::class.java.name in message, "message names CycleB: $message")
        assertTrue("cycle" in message, "message says what is wrong: $message")
    }

    @Test
    fun `a longer cycle is reported as the cycle, not as a list of stuck systems`() {
        val failure = assertFailsWith<SystemOrderException> {
            defWith {
                add(SimPhase.Gameplay, { CycleA() }) { after<CycleC>() }
                add(SimPhase.Gameplay, { CycleB() }) { after<CycleA>() }
                add(SimPhase.Gameplay, { CycleC() }) { after<CycleB>() }
            }.build()
        }

        val message = failure.message.orEmpty()
        val hops = message.split(" -> ")
        assertTrue(hops.size >= 4, "the cycle is printed as a path, was: $message")
        assertEquals(
            hops.first().substringAfterLast(' '),
            hops.last(),
            "a printed cycle returns to where it started: $message",
        )
    }

    @Test
    fun `a constraint naming an unregistered system fails and names it`() {
        val failure = assertFailsWith<SystemOrderException> {
            defWith {
                add(SimPhase.Gameplay, { CycleA() }) { after<UnregisteredSystem>() }
            }.build()
        }

        assertTrue(
            UnregisteredSystem::class.java.name in failure.message.orEmpty(),
            "message names the missing system: ${failure.message}",
        )
    }

    @Test
    fun `a cross-phase constraint that contradicts the phase order fails`() {
        val failure = assertFailsWith<SystemOrderException> {
            defWith {
                add(SimPhase.Cleanup, { CycleA() }) { before<CycleB>() }
                add(SimPhase.Intent, { CycleB() })
            }.build()
        }

        val message = failure.message.orEmpty()
        assertTrue("Cleanup" in message && "Intent" in message, "both phases are named: $message")
        assertTrue("phases are absolute" in message, "and why it cannot be honoured: $message")
    }

    @Test
    fun `a cross-phase constraint that agrees with the phase order is accepted`() {
        val manifest = defWith {
            add(SimPhase.Intent, { CycleA() }) { before<CycleB>() }
            add(SimPhase.Cleanup, { CycleB() })
        }.build().manifest

        assertEquals(
            listOf(CycleA::class.java.name, CycleB::class.java.name),
            manifest.entries.map { it.name }.filter { it.contains("Cycle") },
        )
        assertTrue(
            manifest.entries.single { it.name == CycleA::class.java.name }.before.isNotEmpty(),
            "the constraint is recorded in the manifest, so a later phase move fails here",
        )
    }

    @Test
    fun `registering the same system twice fails`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            defWith {
                add(SimPhase.Gameplay, { CycleA() })
                add(SimPhase.Cleanup, { CycleA() })
            }.build()
        }

        assertTrue(
            CycleA::class.java.name in failure.message.orEmpty(),
            "message names the doubly-registered system: ${failure.message}",
        )
    }

    @Test
    fun `CoreModule may not be listed twice`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            UdeaGameDef(listOf(CoreModule()))
        }
        assertTrue("CoreModule" in failure.message.orEmpty(), "${failure.message}")
    }
}
