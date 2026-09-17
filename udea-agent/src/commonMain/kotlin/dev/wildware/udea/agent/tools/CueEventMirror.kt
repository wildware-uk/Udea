package dev.wildware.udea.agent.tools

import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.core.Cue
import dev.wildware.udea.core.CueSink
import dev.wildware.udea.core.CueSinkDecorator

/**
 * Mirrors the simulation's presentation cues into the agent event ring, on the way past.
 *
 * ## The hole this closes
 *
 * A play-agent ran a whole 27-unit battle through the surface and read `totalRecorded: 1` out of
 * `events.recent_events` - and the one entry was the audit line for its **own** rewind. No death,
 * no hit, no heal, no spin was observable. Every event in the ring was written by
 * [WorldToolset], [TimeToolset] or the dispatcher; nothing in any game had ever recorded one,
 * so an agent debugging combat could see the world's *state* and never a thing that happened to
 * it. "Which unit died, and when?" had no answer that did not involve polling `query_entities`
 * fast enough to catch a health reaching zero.
 *
 * ## Why a `CueSink` decorator and not a call from the combat systems
 *
 * Because combat systems cannot make that call. A game's simulation lives in its `main` source
 * set, `udea-agent` is on the **agent** source set only, and `ReleaseRules` bans
 * `dev/wildware/udea/agent/` from a shipped artifact by exact prefix - so `bridge.event(...)`
 * from inside a Fleks system would not compile, and could not be allowed to if it did.
 *
 * A cue is already the sanctioned channel for "something happened that the simulation itself does
 * not read back" (`CueQueue`), and every event a play-agent asked for is already emitted as one:
 * a game's cue table names damage, melee hits, heals, spins, arrows and death, and the simulation
 * fires them through `GameContext.cues` today for audio to pick up. So the event ring does not
 * need a new channel into the simulation - it needs to be *on* the one that exists.
 *
 * Wrapping rather than draining, and that is the whole design: [dev.wildware.udea.core.CueQueue.drain]
 * empties the queue, so an observer that drained would steal every cue from the audio mixer.
 * A decorator sees each cue on `emit`, records it, and passes it to the sink that was going to
 * get it, so installing this changes what an agent can see and nothing about what the game plays.
 *
 * ## What it costs, stated plainly
 *
 * One `String` per mirrored cue, on the simulation thread, inside `emit`. That is an allocation
 * on a per-tick path and it is a real cost, held down three ways and honestly not to zero:
 *
 * - **only the agent source set ever constructs one.** A shipped game emits cues into a bare
 *   `CueQueue` and pays nothing, because this class is not on its classpath at all.
 * - **[include] decides what is worth a line.** A game passes the handful of cue ids that answer
 *   a question - death, hits, heals - and the every-tick chatter is not mirrored. The default
 *   mirrors everything, which is the right default for a surface whose whole complaint was that
 *   it showed nothing.
 * - **the ring is bounded**, so this cannot grow memory without limit however long a fight runs;
 *   `totalRecorded` minus what the ring holds is what tells an agent it wrapped.
 *
 * A cue that is dropped by a full [dev.wildware.udea.core.CueQueue] is still recorded here,
 * because it still happened: the drop is a presentation budget, and an event ring that inherited
 * it would under-report a busy fight - which is exactly the moment an agent is watching.
 */
public class CueEventMirror(
    /**
     * Where the cue was going. Every cue reaches it, whether or not it was mirrored.
     *
     * Public, through [CueSinkDecorator], because a *drainer* has to be able to reach past this
     * object to the `CueQueue` underneath it. That is not a convenience: `MobaAudio.of` refuses to
     * build over a sink that is not a queue - correctly, since there would be nothing to drain -
     * so a private delegate made installing this mirror silently turn the mixer off.
     */
    override val delegate: CueSink,
    /** The ring the mirrored line is written to. */
    private val bridge: AgentBridge,
    /**
     * The cue's name, as the game spells it. `MobaCues::nameOf` is one.
     *
     * A function rather than a map, because a game already has this - a cue id is an `Int`
     * precisely so it can be a `when` - and the engine cannot know any game's vocabulary. The
     * default renders the raw id, which is readable and is what an event for an unnamed cue
     * should say.
     */
    private val nameOf: (Int) -> String = { "cue:$it" },
    /**
     * Which cue ids are worth an event. Everything, by default.
     *
     * Handed the raw id rather than the name so a filter is a comparison against the game's own
     * constants and never a string match.
     */
    private val include: (Int) -> Boolean = { true },
) : CueSinkDecorator {

    /** Cues mirrored into the ring since construction. Read by tests; not agent-visible. */
    public var mirrored: Long = 0L
        private set

    /**
     * Records [cue] if [include] wants it, then passes it on either way.
     *
     * The line is `game:<name>:#<index>@<generation>`, stamped with the cue's own tick and not
     * the clock's. `game:` prefixes it because the ring already carries `agent_mutation:`,
     * `agent_time:` and `slow_tool:` lines, and an agent asking "what did the *game* do" needs
     * `events.recent_events contains=game:` to mean that and nothing else. The source id is the
     * packed `NetId` in the same spelling every other event uses, so it can be pasted straight
     * into `world.describe_entity`.
     *
     * The delegate is called last, so a throwing ring cannot lose a cue the game was going to
     * play - and it is not guarded beyond that: a `bridge.event` that throws is a broken ring,
     * which is a real failure and not something to hide inside an audio path.
     */
    override fun emit(cue: Cue) {
        val id = cue.id.raw
        if (include(id)) {
            val source = cue.source
            bridge.event(
                "game:${nameOf(id)}:#${source.index}@${source.generation}",
                cue.tick.value,
            )
            mirrored++
        }
        delegate.emit(cue)
    }

    override fun toString(): String = "CueEventMirror($mirrored mirrored -> $delegate)"
}
