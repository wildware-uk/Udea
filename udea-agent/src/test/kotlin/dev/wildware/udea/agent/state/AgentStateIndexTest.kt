package dev.wildware.udea.agent.state

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.AgentStateSource
import dev.wildware.udea.agent.StateModule
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [AgentStateIndex]: the join between a generated `StateModule` and the digest's `game` block.
 *
 * The two things asserted hardest are the two a KSP round structurally cannot do - a key
 * published by two different modules, and the pairing of a source with the instance it reads -
 * because those are the failures that would otherwise reach an agent as a malformed document or
 * as somebody else's numbers.
 */
class AgentStateIndexTest {

    @Test
    fun `a bound source publishes its scalars into the digest's game block`() {
        val match = Match(phase = "Laning", minute = 7)
        val index = AgentStateIndex.builder().module(MATCH_MODULE).source(match).build()
        val bridge = AgentBridge()
        val digest = StateDigest(bridge, DigestSources(game = index))

        digest.publish()

        val document = bridge.snapshot()
        assertTrue(""""game":{"phase":"Laning","minute":7}""" in document, document)
    }

    @Test
    fun `the digest follows the live instance rather than a value read at build time`() {
        val match = Match(phase = "Laning", minute = 7)
        val index = AgentStateIndex.builder().module(MATCH_MODULE).source(match).build()
        val bridge = AgentBridge()
        val digest = StateDigest(bridge, DigestSources(game = index))

        digest.publish()
        match.phase = "Sieging"
        match.minute = 31
        digest.publish()

        val document = bridge.snapshot()
        assertTrue(""""phase":"Sieging"""" in document, document)
        assertTrue(""""minute":31""" in document, document)
    }

    @Test
    fun `two sources on different instances each read their own`() {
        val match = Match(phase = "Laning", minute = 1)
        val economy = Economy(gold = 500)
        val index = AgentStateIndex.builder()
            .module(Module("Moba", listOf<AgentStateSource<*>>(MATCH_STATE, ECONOMY_STATE)))
            // Registered in the opposite order to the module's source order.
            .source(economy)
            .source(match)
            .build()

        assertEquals(listOf("gold", "minute", "phase"), index.names)
        assertEquals(listOf("phase=Laning", "minute=1", "gold=500"), collect(index))
    }

    @Test
    fun `two modules publishing one digest key are refused, naming both`() {
        val failure = assertFailsWith<IllegalStateException> {
            AgentStateIndex.builder()
                .module(MATCH_MODULE)
                .module(Module("Shared", listOf<AgentStateSource<*>>(RIVAL_PHASE_STATE)))
                .source(Match(phase = "Laning", minute = 1))
                .source(Rival())
                .build()
        }

        val message = failure.message.orEmpty()
        assertTrue("phase" in message, message)
        assertTrue("Moba" in message && "Shared" in message, "both modules must be named: $message")
    }

    @Test
    fun `a source with no registered instance is refused when the index is built`() {
        val failure = assertFailsWith<IllegalStateException> {
            AgentStateIndex.builder().module(MATCH_MODULE).build()
        }

        assertTrue(Match::class.qualifiedName!! in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `two instances that both fit one source are refused rather than silently picked`() {
        val failure = assertFailsWith<IllegalStateException> {
            AgentStateIndex.builder()
                .module(MATCH_MODULE)
                .source(Match(phase = "a", minute = 1))
                .source(Match(phase = "b", minute = 2))
                .build()
        }

        assertTrue("2 registered instances" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `an index over no modules publishes nothing and is still a usable source`() {
        val index = AgentStateIndex.builder().build()

        assertEquals(emptyList(), index.names)
        assertEquals(emptyList(), collect(index))
    }

    /**
     * What this index wrote, in write order, and nothing else.
     *
     * A recording sink rather than the digest's own, so the order the values are appended in is
     * observable: it is the order the `game` block will carry, and "registration order does not
     * decide which instance a source reads" is only a claim if the order is visible.
     */
    private fun collect(index: AgentStateIndex): List<String> {
        val recorded = RecordingSink()
        index.publish(recorded)
        return recorded.written
    }

    private class RecordingSink : GameStateSink {
        val written: MutableList<String> = ArrayList()

        override fun put(name: String, value: Int) {
            written += "$name=$value"
        }

        override fun put(name: String, value: Long) {
            written += "$name=$value"
        }

        override fun put(name: String, value: Float) {
            written += "$name=$value"
        }

        override fun put(name: String, value: Boolean) {
            written += "$name=$value"
        }

        override fun put(name: String, value: String?) {
            written += "$name=$value"
        }
    }

    // --- fixtures ------------------------------------------------------------------------------

    private class Match(var phase: String, var minute: Int)

    private class Economy(var gold: Int)

    private class Rival

    /** The hand-written equivalent of what `AgentStateEmitter` produces for one class. */
    private class Source<T : Any>(
        override val names: List<String>,
        override val owner: KClass<*>,
        private val writer: (T, GameStateSink) -> Unit,
    ) : AgentStateSource<T> {
        override fun write(source: T, out: GameStateSink) = writer(source, out)
    }

    private class Module(
        override val moduleName: String,
        override val states: List<AgentStateSource<*>>,
    ) : StateModule

    private companion object {
        val MATCH_STATE = Source<Match>(listOf("minute", "phase"), Match::class) { match, out ->
            out.put("phase", match.phase)
            out.put("minute", match.minute)
        }

        val ECONOMY_STATE = Source<Economy>(listOf("gold"), Economy::class) { economy, out ->
            out.put("gold", economy.gold)
        }

        val RIVAL_PHASE_STATE = Source<Rival>(listOf("phase"), Rival::class) { _, out ->
            out.put("phase", "collides")
        }

        val MATCH_MODULE = Module("Moba", listOf<AgentStateSource<*>>(MATCH_STATE))
    }
}
