package dev.wildware.moba.match

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.Family
import com.github.quillraven.fleks.World
import dev.wildware.moba.ability.Combatant
import dev.wildware.moba.level.GameUnit
import dev.wildware.moba.level.Team
import dev.wildware.moba.level.TestLevelScene
import dev.wildware.udea.core.GameContext
import dev.wildware.udea.core.RngStream
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.loop.BarrierAction
import dev.wildware.udea.core.loop.barrier
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.core.rng.DefaultRngService

/**
 * The game loop: a match starts, a side wins, the result stands, and the next match begins.
 *
 * ## What this replaces
 *
 * Nothing, which is the point. Before it, `moba` spawned twenty-seven units, they fought for
 * about forty seconds, and the process then sat static for ever: no objective, no score, no win,
 * no lose, no restart. Two independent play agents graded it the same way and gave the same
 * reason - it is a fight simulator, not a game. This is the loop that makes it one.
 *
 * ## The three transitions, and where each of them lives
 *
 * | transition | trigger | what happens |
 * |---|---|---|
 * | nothing to [MatchPhase.Fighting] | a populated world with no [MatchState] in it | [begin] mints the singleton |
 * | [MatchPhase.Fighting] to [MatchPhase.Ended] | [MatchRules.decide] returns a team | the result is stamped and frozen |
 * | [MatchPhase.Ended] to the next match | [MatchRules.RESULT_TICKS] have passed | [restart] reseeds, swaps the scene and queues [BeginMatch] |
 *
 * ## Why the restart goes through `SceneManager` and not through a reset of its own
 *
 * `BarrierSceneManager` already applies a swap as **one** barrier action at a tick boundary:
 * teardown, physics bodies destroyed, `world.removeAll(clearRecycled = true)`, `netIds.reset()`,
 * populate - so a tick sees the whole old match or the whole new one and never half of either. A
 * bespoke "put everything back" path would be a second answer to a question the engine has
 * already answered, and it would be the one that has to be kept in step with every future
 * teardown step. It also buys a property worth naming: because ids are recycled and the level
 * spawns in a fixed order, the player's `NetId` is the *same* in every match, so the camera that
 * was told to follow it at boot is still following it three matches later.
 *
 * ## Where the next match's layout comes from
 *
 * `TestLevelScene` scatters each unit with two draws from `RngStream.Spawn`. Left alone, a
 * second populate would simply continue that stream - a different layout, but one no number in
 * the world describes. So [restart] seeds the spawn stream from a draw on `RngStream.Wave` and
 * records the value in [MatchState.seed]. Two consequences, both wanted: a match is reproducible
 * from its recorded seed, and the choice of layout cannot perturb combat rolls, because
 * `RngStream.Wave` is a stream nothing else in this game draws from.
 *
 * The seed is applied by writing it into the stream directly, which needs the concrete
 * [DefaultRngService] rather than the `RngService` interface - the interface has draws on it and
 * no way to reseed. That is a real cost and it is a cast: if a game ever runs on another
 * implementation this fails loudly at construction rather than silently laying every match out
 * the same way.
 *
 * ## Phase and allocation
 *
 * `SimPhase.Cleanup`, after `DeathSystem` in `SimPhase.Gameplay` has retired everything that
 * died this tick, so the counts this system writes are this tick's and not last tick's. The
 * per-tick path walks one family and writes primitives; the only allocation in the file is the
 * one [BeginMatch] a restart queues, which happens once per match.
 */
public class MatchSystem(
    /** The read mirror this publishes into. The truth stays in [MatchState]. */
    private val service: MatchService,
) : SimSystem() {

    /**
     * Living units. `Combatant` and not `Position.hp > 0`, because `DeathSystem` removes the
     * `Combatant` from a unit the moment it dies and that removal is what takes it out of every
     * targeting family in the game. Reusing it here means "alive" has one definition rather than
     * two that can disagree - a corpse is never counted, and a unit nothing can hit is never
     * counted as holding a side up.
     */
    private val units: Family = world.family { all(GameUnit, Combatant) }

    /** The singleton. A family and not a stored `Entity`, because a scene swap destroys it. */
    private val matches: Family = world.family { all(MatchState) }

    private val netIds: NetIdIndex = ctx[CoreModule.NET_IDS]

    /**
     * The spawn stream, reachable for writing.
     *
     * Resolved at construction and not per restart, so a game wired with an `RngService` that
     * cannot be reseeded fails when the world is built rather than three minutes into the first
     * match, at the one moment nobody is watching a log.
     */
    private val rng: DefaultRngService = ctx.rng as? DefaultRngService
        ?: error(
            "MatchSystem needs to seed RngStream.Spawn between matches and RngService has no " +
                "reseed on it, so it requires a DefaultRngService; this world has " +
                ctx.rng::class.java.name,
        )

    /** Matches this system has decided. A health signal for a test and a log line, not state. */
    public var decided: Long = 0L
        private set

    /** Restarts this system has queued. Equal to [decided] once the last result has expired. */
    public var restarts: Long = 0L
        private set

    override fun onTick() {
        val state = current() ?: begin() ?: return
        when (state.phase) {
            MatchPhase.Fighting -> fight(state)
            MatchPhase.Ended -> expire(state)
            // Queued and not yet drained. The swap lands at the top of the next tick and takes
            // this entity with it, so there is nothing to do and nothing to guard against.
            MatchPhase.Restarting -> Unit
        }
        service.publish(state)
    }

    /** The match in this world, or `null` when a swap has just cleared it away. */
    private fun current(): MatchState? {
        val entities = matches.entities
        if (entities.size == 0) return null
        return with(world) { entities[0][MatchState] }
    }

    /**
     * Mints match one over a world that has units and no match, or `null` over an empty one.
     *
     * The `null` is the boot ordering made explicit rather than absorbed: `MobaEntry.seed`
     * requests the scene and the swap lands at the top of the *next* tick, so there is a tick in
     * every process where this system runs over nothing. Minting a match there would produce a
     * match with three zero counts, which [MatchRules.decide] would immediately call a draw.
     *
     * Only match one comes through here. Every later match is created by [BeginMatch], which
     * carries the number and the seed forward across a teardown that destroys the entity holding
     * them.
     */
    private fun begin(): MatchState? {
        if (units.entities.size == 0) return null
        // The seed the spawn stream was *actually* built with, out of the same derivation
        // `DefaultRngService`'s constructor uses. Reseeding with it and reloading the scene
        // reproduces this match's layout exactly, which is the property `MatchState.seed`
        // promises and would not hold if this line wrote the root seed instead.
        val seed = DefaultRngService.streamSeed(rng.rootSeed, RngStream.Spawn.ordinal)
        return install(matchNumber = 1, seed = seed, world = world, ctx = ctx)
    }

    /** Counts the sides, writes the scoreboard, and stamps a result when there is one. */
    private fun fight(state: MatchState) {
        var orc = 0
        var soldier = 0
        var undead = 0
        val entities = units.entities
        var index = 0
        with(world) {
            while (index < entities.size) {
                val entity: Entity = entities[index]
                when (entity[GameUnit].team) {
                    Team.ORC -> orc++
                    Team.SOLDIER -> soldier++
                    Team.UNDEAD -> undead++
                    else -> Unit
                }
                index++
            }
        }
        state.orcAlive = orc
        state.soldierAlive = soldier
        state.undeadAlive = undead
        val now = tick.value
        val outcome = MatchRules.decide(orc, soldier, undead, now - state.startedTick)
        if (outcome == MatchRules.UNDECIDED) return
        state.winner = outcome
        state.phase = MatchPhase.Ended
        state.endedTick = now
        decided++
        service.countDecision()
    }

    /** Queues the next match once the result has stood for [MatchRules.RESULT_TICKS]. */
    private fun expire(state: MatchState) {
        if (tick.value - state.endedTick < MatchRules.RESULT_TICKS) return
        restart(state)
    }

    /**
     * Lays out the next match and queues it.
     *
     * Three steps, and the order is the whole of it:
     *
     * 1. the spawn stream is seeded **now**, inside this tick, so it is already carrying the new
     *    seed when the swap action populates the level at the top of the next one;
     * 2. `requestScene` submits the swap;
     * 3. [BeginMatch] is submitted after it, so it runs after the swap has emptied the world and
     *    finds a populated level to mint a match over.
     *
     * The number and the seed travel *in the queued action* rather than in a field on this
     * system, and that is deliberate: a field would be state no snapshot knows about, and a
     * `time.rewind` across a restart would restore a world whose match number came from the
     * component and whose next match number came from a counter that had run on ahead.
     */
    private fun restart(state: MatchState) {
        val seed = rng.nextLong(RngStream.Wave)
        rng.stream(RngStream.Spawn).seed(seed)
        state.phase = MatchPhase.Restarting
        ctx.scenes.requestScene(TestLevelScene.ID)
        ctx.barrier.submit(BeginMatch(matchNumber = state.matchNumber + 1, seed = seed))
        restarts++
    }

    override fun toString(): String = "MatchSystem(decided=$decided, restarts=$restarts)"

    internal companion object {

        /**
         * Creates the singleton match entity and hands back its state.
         *
         * Shared by [begin] and [BeginMatch] so the two paths cannot drift into meaning
         * different things about what a new match is. A `NetId` is allocated for it because the
         * agent surface addresses entities by net id, and a scoreboard nothing can name is a
         * scoreboard nothing can read.
         */
        fun install(matchNumber: Int, seed: Long, world: World, ctx: GameContext): MatchState {
            val state = MatchState(
                matchNumber = matchNumber,
                seed = seed,
                startedTick = ctx.clock.tick.value,
            )
            val entity = world.entity { it += state }
            ctx[CoreModule.NET_IDS].allocate(entity)
            return state
        }
    }
}

/**
 * Mints the match the scene swap ahead of it just populated the world for.
 *
 * A [BarrierAction] and not a line in a system, because the entity it creates has to exist
 * *after* `BarrierSceneManager`'s swap has run `world.removeAll` and repopulated - and both are
 * barrier actions, drained in submission order at the top of one tick. A system could not be
 * ordered between them: it does not run until every action has drained.
 *
 * It carries the two numbers that cannot survive the teardown any other way. Everything else
 * about a match is derived from the world the swap has just built.
 */
internal class BeginMatch(
    private val matchNumber: Int,
    private val seed: Long,
) : BarrierAction {

    override val label: String get() = "begin match #$matchNumber"

    override fun apply(world: World, ctx: GameContext) {
        MatchSystem.install(matchNumber = matchNumber, seed = seed, world = world, ctx = ctx)
    }
}
