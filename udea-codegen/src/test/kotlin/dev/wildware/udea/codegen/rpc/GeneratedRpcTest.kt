package dev.wildware.udea.codegen.rpc

import dev.wildware.udea.codegen.ProcessorHarness
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Issue #109, generator half: the authority guard is **emitted**, and a declaration that has
 * nothing to guard fails the build.
 *
 * The behavioural proof - a client firing an ability on an entity it does not own, refused with
 * a typed error naming the rule - is `dev.wildware.moba.ability.AbilityRpcTest`, over the real
 * generated descriptor. What is proven here is the property a behavioural test cannot reach:
 * that there is **no way to declare an unguarded ownership RPC**. `PacketUtil.kt:148` was not a
 * failing test, it was a comment; the equivalent mistake has to be a red build.
 */
class GeneratedRpcTest {

    private fun generate(workDir: File, source: String): ProcessorHarness.Run =
        ProcessorHarness.run(workDir, mapOf("Rpcs.kt" to source))

    @Test
    fun `an OwnerPredicted rpc emits the ownership check before the call`(@TempDir workDir: File) {
        val run = generate(
            workDir,
            """
            package fixtures

            import dev.wildware.udea.annotations.Authority
            import dev.wildware.udea.core.identity.NetId
            import dev.wildware.udea.net.rpc.Rpc
            import dev.wildware.udea.net.rpc.RpcDirection

            @Rpc(
                direction = RpcDirection.ClientToServer,
                authority = Authority.OwnerPredicted,
                ratePerSecond = 20,
                burst = 8,
            )
            fun fireAbility(self: NetId, slot: Int) {
                println(self.toString() + slot)
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), run.errors)
        val generated = run.generatedSource("FireAbilityRpc.kt")

        assertTrue("val owner = ownership.ownerOf(self)" in generated, generated)
        assertTrue("if (owner != sender)" in generated, generated)
        assertTrue(
            "RpcRefusal.NotOwner(name, Authority.OwnerPredicted, sender, self, owner)" in generated,
            generated,
        )
        // The guard has to be *before* the call, or it is a log line and not a guard.
        assertTrue(
            generated.indexOf("if (owner != sender)") < generated.indexOf("fireAbility(self, slot)"),
            "the guard is emitted after the invocation:\n$generated",
        )
        assertTrue("RpcRate(perSecond = 20, burst = 8)" in generated, generated)
    }

    @Test
    fun `an ownership rule with no entity to check is a build failure`(@TempDir workDir: File) {
        val run = generate(
            workDir,
            """
            package fixtures

            import dev.wildware.udea.annotations.Authority
            import dev.wildware.udea.net.rpc.Rpc
            import dev.wildware.udea.net.rpc.RpcDirection

            @Rpc(direction = RpcDirection.ClientToServer, authority = Authority.OwnerPredicted)
            fun fireAbility(slot: Int) {
                println(slot)
            }
            """.trimIndent(),
        )
        // A permissive default here is the old engine exactly: a declaration that reads as
        // protected, a generated body that checks nothing, and a green build.
        assertEquals(1, run.errors.size, run.errors.toString())
        assertTrue("nothing for the generated guard to check ownership of" in run.errors.single())
        assertTrue("PacketUtil.kt:148" in run.errors.single())
        assertTrue(run.generatedFiles.none { it.name == "FireAbilityRpc.kt" })
        // Located, not merely loud: the message has to point at the declaration.
        assertEquals("Rpcs.kt", run.errorDiagnostics.single().file)
    }

    @Test
    fun `a client-to-server rpc that no client may invoke is a build failure`(@TempDir workDir: File) {
        val run = generate(
            workDir,
            """
            package fixtures

            import dev.wildware.udea.annotations.Authority
            import dev.wildware.udea.core.identity.NetId
            import dev.wildware.udea.net.rpc.Rpc
            import dev.wildware.udea.net.rpc.RpcDirection

            @Rpc(direction = RpcDirection.ClientToServer, authority = Authority.Server)
            fun fireAbility(self: NetId) {
                println(self)
            }
            """.trimIndent(),
        )
        assertEquals(1, run.errors.size, run.errors.toString())
        assertTrue("can never be invoked" in run.errors.single(), run.errors.single())
    }

    @Test
    fun `an argument with no wire encoding is a build failure naming the type`(@TempDir workDir: File) {
        val run = generate(
            workDir,
            """
            package fixtures

            import dev.wildware.udea.annotations.Authority
            import dev.wildware.udea.core.identity.NetId
            import dev.wildware.udea.net.rpc.Rpc
            import dev.wildware.udea.net.rpc.RpcDirection

            class Payload(val text: String)

            @Rpc(direction = RpcDirection.ClientToServer, authority = Authority.OwnerPredicted)
            fun fireAbility(self: NetId, payload: Payload) {
                println(self.toString() + payload.text)
            }
            """.trimIndent(),
        )
        // The old generator's answer to a type it did not recognise was a blind serializer
        // fallback. Here it is a located error naming the parameter and the type.
        assertTrue(run.errors.any { "fixtures.Payload" in it && "no wire encoding" in it }, run.errors.toString())
    }

    @Test
    fun `a server-originated rpc guards against a client replaying it`(@TempDir workDir: File) {
        val run = generate(
            workDir,
            """
            package fixtures

            import dev.wildware.udea.annotations.Authority
            import dev.wildware.udea.core.identity.NetId
            import dev.wildware.udea.net.rpc.Rpc
            import dev.wildware.udea.net.rpc.RpcDirection

            @Rpc(direction = RpcDirection.Multicast, authority = Authority.Server)
            fun announceKill(victim: NetId) {
                println(victim)
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), run.errors)
        val generated = run.generatedSource("AnnounceKillRpc.kt")
        // `RpcServer` refuses it on direction before this is reached, but the descriptor must
        // still be safe on its own: a second dispatcher, or a future one, must not be able to
        // route a client datagram into a server-only body.
        assertTrue("RpcRefusal.ServerOnly(name, Authority.Server, sender)" in generated, generated)
        assertTrue("announceKill(" !in generated.substringAfter("return RpcRefusal.ServerOnly"), generated)
    }
}
