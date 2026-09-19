package dev.wildware.moba.agent

import dev.wildware.moba.ability.MobaCues
import dev.wildware.udea.agent.AgentBridge
import dev.wildware.udea.agent.tools.CueEventMirror
import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.module.UdeaModule

/**
 * Puts the agent's event ring on the game's cue stream, so `events.recent_events` sees the fight.
 *
 * ## The hole this closes
 *
 * A play agent ran a whole twenty-seven-unit battle through the surface and read
 * `totalRecorded: 1` - and the one entry was the audit line for its *own* rewind. No death, no
 * hit, no heal, no spin was observable from outside the process. Every event in the ring had been
 * written by a toolset or by the dispatcher; nothing in any game had ever recorded one.
 *
 * ## Why the wiring is a module and lives in this source set
 *
 * A combat system cannot make the call. `moba`'s simulation is in `src/main`, `udea-agent` is on
 * the **agent** source set only, and `ReleaseRules` bans `dev/wildware/udea/agent/` from a
 * shipped artifact by exact prefix - so `bridge.event(...)` from inside a Fleks system would not
 * compile and could not be allowed to if it did. The sanctioned channel for "something happened
 * that the simulation does not read back" is already a cue, and `MobaCues` already names damage,
 * melee hits, knockback, heals, spins, arrows and death.
 *
 * `CueEventMirror` is a `CueSink` **decorator**, and the only place a decorator can see the value
 * it decorates is a module `context` hook: `CoreModule` sets `GameContextBuilder.cues` to its
 * `CueQueue`, and a module later in the list overwrites it. So this module is appended after
 * every module the game is made of - `MobaGame.definition(extraModules)` is what appends it - and
 * it wraps whatever the game left there.
 *
 * ## What it does not change
 *
 * It **wraps** and never drains. `CueQueue.drain` empties the queue, so an observer that drained
 * would steal every cue from the audio mixer and from `EffectSpawnSystem`. Every cue still
 * reaches the sink that was going to get it. And the drainers in this tree read
 * `UdeaGameDef.core.cues` - the concrete queue - rather than `GameContext.cues`, so replacing the
 * context's sink with a decorator is invisible to them.
 *
 * ## The cost, stated
 *
 * One `String` per cue, on the simulation thread. Only the agent source set constructs this, so a
 * shipped client emits into a bare `CueQueue` and pays nothing - this class is not on its
 * classpath at all.
 */
internal class MobaCueMirrorModule(
    /** The ring the mirrored lines are written to. The agent's one bridge. */
    private val bridge: AgentBridge,
) : UdeaModule {

    override val name: String get() = "moba-agent-cue-mirror"

    override fun context(builder: GameContextBuilder) {
        val existing = requireNotNull(builder.cues) {
            "no CueSink was on the builder when the cue mirror ran, so there is nothing to " +
                "decorate; CoreModule sets it, which means this module ran before the core's " +
                "context hook and the module order is wrong"
        }
        builder.cues = CueEventMirror(existing, bridge, MobaCues::nameOf)
    }

    override fun toString(): String = "MobaCueMirrorModule"
}
