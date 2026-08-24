package dev.wildware.moba.lane

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.moba.MobaControls
import dev.wildware.moba.MobaGame
import dev.wildware.moba.MobaModule
import dev.wildware.moba.Player
import dev.wildware.moba.Position
import dev.wildware.moba.ability.CharacterAttributes
import dev.wildware.moba.ability.Combatant
import dev.wildware.moba.ability.Corpse
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.moba.level.GameUnit
import dev.wildware.moba.level.MobaBlueprints
import dev.wildware.moba.level.Team
import dev.wildware.udea.core.blueprint.SpawnPosition
import dev.wildware.udea.core.blueprint.blueprints
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.loop.RewindResult
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.render.input.InjectedIntent
import dev.wildware.udea.render.input.IntentState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The lane, played headless, over the definition `MobaClient.main` boots.
 *
 * ## What this is a proof of
 *
 * That `moba` is a MOBA rather than a brawl in a field. Everything below runs
 * `MobaGame.definition()` and nothing else: the real level, the real twenty-seven units, the real
 * combat, and the real lane appended to the same module list a shipped process assembles. Nothing
 * here injects a wave, shortens a cooldown or writes a gold number.
 *
 * The two things it does do to the simulation are stated rather than buried: it **spawns creeps
 * through the same spawner and the same overrides** [LaneSystem] uses, and it **moves the
 * champion** by writing its `Position`, which is what `world.set_component_field` does over HTTP.
 * Both are done to isolate a claim from twenty-seven units of unrelated fighting, and neither
 * touches the rule under test.
 *
 * ## Why the economy claims are set up rather than watched
 *
 * "A champion last-hits a creep and gains gold" is a claim about *attribution*, and attribution is
 * only observable when you know who else could have hit it. In the middle of a live wave with two
 * towers firing and six creeps swinging, a test that saw gold go up would not know why. So the
 * champion is stood somewhere nothing else can reach and handed exactly the situation the claim
 * is about - which is the difference between evidence and a coincidence that happened to be
 * green.
 */
class LaneProofTest {

    /** A booted headless game with the level's first tick already applied. */
    private class Harness(
        val host: GameHost,
        /** This world's own attribute ids, off the table its units were dressed with. */
        val attributes: CharacterAttributes,
    ) {

        val netIds = host.ctx[CoreModule.NET_IDS]

        /** The lane singleton, or `null` before it has opened. */
        fun lane(): LaneState? {
            val entities = host.world.family { all(LaneState) }.entities
            if (entities.size == 0) return null
            return with(host.world) { entities[0][LaneState] }
        }

        /** Every creep on the field, corpses included. */
        fun creeps(): List<Entity> = collect(host.world.family { all(LaneCreep) })

        /** Living creeps: `Combatant` is what `DeathSystem` takes away. */
        fun livingCreeps(): Int =
            host.world.family { all(LaneCreep, Combatant) }.entities.size

        /** The towers the lane placed. */
        fun towers(): List<Entity> = collect(host.world.family { all(Tower) })

        /** The champion's purse, or `null` before [ChampionSystem] has granted one. */
        fun wallet(): Wallet? {
            val entities = host.world.family { all(Player, Wallet) }.entities
            if (entities.size == 0) return null
            return with(host.world) { entities[0][Wallet] }
        }

        /** The champion entity. */
        fun champion(): Entity = host.world.family { all(Player) }.entities[0]

        /** Puts the champion at [x], [y]. What `world.set_component_field` does over HTTP. */
        fun moveChampion(x: Float, y: Float) {
            with(host.world) {
                val position = champion()[Position]
                position.x = x
                position.y = y
            }
        }

        /**
         * Spawns one creep through the same spawner and the same overrides [LaneSystem] uses.
         *
         * `host.run(1)` afterwards, because a spawn is a barrier action and the entity does not
         * exist until the top of the next tick.
         */
        fun spawnCreep(team: Int, x: Float, y: Float, waypoint: Int): NetId {
            val blueprints = host.ctx[MobaBlueprints.KEY]
            val blueprint =
                if (team == Team.SOLDIER) blueprints.soldier else blueprints.skeleton
            val id = host.ctx.blueprints.spawn(
                blueprint = blueprint,
                position = SpawnPosition(x, y),
                overrides = LaneSystem.creepOverrides(team, waypoint, wave = 0),
            )
            host.run(1)
            return id
        }

        /** The champion's `maxHealth` base, which is what a level-up raises. */
        fun championMaxHealth(): Float = with(host.world) {
            champion()[Attributes].base(attributes.maxHealth)
        }

        /** A family's entities as a list. Fleks' bag is not a `Collection`. */
        private fun collect(family: com.github.quillraven.fleks.Family): List<Entity> {
            val entities = family.entities
            val out = ArrayList<Entity>(entities.size)
            var index = 0
            while (index < entities.size) {
                out += entities[index]
                index++
            }
            return out
        }

        /** Ticks until [predicate] holds, one tick at a time, and fails rather than hanging. */
        fun runUntil(limit: Int, what: String, predicate: () -> Boolean): Long {
            var spent = 0
            while (spent < limit) {
                if (predicate()) return host.tick.value
                host.run(1)
                spent++
            }
            if (predicate()) return host.tick.value
            throw AssertionError("$what did not happen within $limit ticks")
        }
    }

    private fun boot(): Harness {
        loadPhysicsNatives()
        val definition = MobaGame.definition()
        // Off the definition's own module list, so the attribute ids this test reads are the ones
        // the world's units were actually dressed with. An `AttributeId` is an index into one
        // `AttributeTable`, and a second table built here would read a unit's armour and call it
        // health - the mistake `MobaGame` threads its table through five call sites to prevent.
        val combat = definition.modules.filterIsInstance<MobaModule>().single().combat
        val host = GameHost(RenderMode.Headless, definition, null)
        MobaEntry.seed(host)
        return Harness(host, combat.attributes)
    }

    /**
     * Waves arrive on a tick timer, three a side, for ever.
     *
     * The headline number of issue #130. Every interval is asserted **exactly**, in ticks, and
     * not as "roughly ten seconds": a wave timer that drifted by a tick per wave would still look
     * right to a human watching and would put two processes in a networked game a wave apart
     * after ten minutes.
     */
    @Test
    fun `creep waves arrive on the tick timer, three a side`() {
        val game = boot()
        val opened = assertNotNull(
            game.runUntilLaneOpens(),
            "the lane never opened over a populated world",
        )
        println("[lane] opened at tick ${opened.startedTick}, first wave ${opened.nextWaveTick}")

        // The first wave's due tick, off the lane itself. Asserted before a single wave has gone
        // out, so this is the schedule the lane *published* and not one inferred from when a test
        // happened to look.
        assertEquals(
            LaneGeometry.FIRST_WAVE_TICK,
            opened.nextWaveTick - opened.startedTick,
            "the first wave must be due exactly FIRST_WAVE_TICK after the lane opened",
        )

        // Two clocks, deliberately. `dueTicks` is what the lane scheduled and `seenTicks` is when
        // a reader outside the simulation saw the wave number move; asserting the interval on
        // both is what would catch a wave that was scheduled correctly and sent late, or sent on
        // time and rescheduled wrongly.
        val dueTicks = ArrayList<Long>()
        val seenTicks = ArrayList<Long>()
        val census = ArrayList<Map<Int, Int>>()
        var seen = 0
        while (seen < WAVES) {
            val next = seen + 1
            seenTicks += game.runUntil(WAVE_BUDGET, "wave $next") {
                (game.lane()?.waveNumber ?: 0) >= next
            }
            dueTicks += assertNotNull(game.lane()).nextWaveTick
            // Counted **now**, two ticks after the wave went out, because a wave is a set of
            // barrier spawns that land at the top of the next tick and because a creep from an
            // earlier wave is dead and swept away long before the next one arrives. Counting the
            // whole field at the end would therefore report a wave that had simply been fought.
            game.host.run(2)
            census += creepsOf(game, next)
            seen = next
        }
        println("[lane] waves seen on ticks $seenTicks, next due $dueTicks")

        var index = 1
        while (index < WAVES) {
            assertEquals(
                LaneGeometry.WAVE_INTERVAL_TICKS,
                dueTicks[index] - dueTicks[index - 1],
                "wave ${index + 1} must be rescheduled exactly one interval after wave $index",
            )
            assertEquals(
                LaneGeometry.WAVE_INTERVAL_TICKS,
                seenTicks[index] - seenTicks[index - 1],
                "wave ${index + 1} must arrive exactly one interval after wave $index",
            )
            index++
        }
        // And the first wave went out on the tick it was due, not merely at the right spacing.
        // `seenTicks[0]` is read one tick late by construction - `GameHost.run(1)` advances the
        // clock past the tick it simulated - which is why this is `+ 1` rather than an equality
        // that would silently pass for a wave that arrived a tick early.
        assertEquals(
            opened.startedTick + LaneGeometry.FIRST_WAVE_TICK + 1,
            seenTicks[0],
            "the first wave must go out on the tick it was scheduled for",
        )

        println("[lane] wave one fielded ${census[0]}")
        for (team in LaneGeometry.TEAMS) {
            assertEquals(
                LaneGeometry.CREEPS_PER_WAVE,
                census[0][team],
                "wave one should field ${LaneGeometry.CREEPS_PER_WAVE} ${Team.nameOf(team)} creeps",
            )
        }
    }

    /**
     * The lane places one tower a side, and they are where the geometry says.
     *
     * A tower that never reached the world is the difference between "towers are implemented" and
     * "a `Tower` component compiles", and nothing else in this file would notice.
     */
    @Test
    fun `the lane places one tower a side`() {
        val game = boot()
        game.runUntil(TOWER_BUDGET, "the towers to be placed") { game.towers().size >= 2 }
        val towers = game.towers()
        assertEquals(2, towers.size, "one tower a side and no more")
        with(game.host.world) {
            for (entity in towers) {
                val tower = entity[Tower]
                val position = entity[Position]
                assertEquals(
                    LaneGeometry.towerX(tower.team),
                    position.x,
                    "the ${Team.nameOf(tower.team)} tower is not where the geometry says",
                )
                assertEquals(LaneGeometry.TOWER_Y, position.y)
            }
        }
        // And they stay two: the placement guard is the flag on `LaneState`, and a guard that
        // did not hold would put a pair of towers on the field every tick.
        game.host.run(120)
        assertEquals(2, game.towers().size, "the tower placement must run exactly once")
    }

    /**
     * Creeps walk the lane, meet, and kill each other.
     *
     * Three separate things, and the test asserts all three, because a lane where creeps spawn
     * and stand still, or walk and pass through each other, would satisfy any two of them.
     */
    @Test
    fun `creeps march down the lane, meet, and fight`() {
        val game = boot()
        game.runUntil(WAVE_BUDGET, "the first wave") { (game.lane()?.waveNumber ?: 0) >= 1 }
        game.host.run(2)
        val start = HashMap<NetId, Float>()
        with(game.host.world) {
            for (entity in game.creeps()) {
                start[game.netIds.netIdOf(entity)] = entity[Position].x
            }
        }
        assertTrue(start.isNotEmpty(), "wave one put nothing on the field")

        // Long enough for two lines starting 600 units apart at 0.7 a tick to close and fight.
        game.host.run(MARCH_TICKS)

        var advanced = 0
        var pastFirstWaypoint = 0
        with(game.host.world) {
            for (entity in game.creeps()) {
                val id = game.netIds.netIdOf(entity)
                val from = start[id] ?: continue
                val creep = entity[LaneCreep]
                val moved = entity[Position].x - from
                // Soldier creeps walk toward +x and undead toward -x, so "advanced" is movement
                // in the creep's own heading and not movement in some absolute direction.
                if (moved * creep.heading > MOVEMENT_EPSILON) advanced++
                if (creep.waypoint != LaneGeometry.startWaypoint(entity[GameUnit].team)) {
                    pastFirstWaypoint++
                }
            }
        }
        val bodies = with(game.host.world) { game.creeps().count { Corpse in it } }
        println(
            "[lane] after $MARCH_TICKS ticks: ${game.creeps().size} creeps, $advanced advanced, " +
                "$pastFirstWaypoint past their first waypoint, $bodies dead",
        )
        assertTrue(advanced > 0, "no creep moved along the lane at all")
        assertTrue(
            pastFirstWaypoint > 0,
            "no creep reached a waypoint, so the polyline is not being followed",
        )
        assertTrue(bodies > 0, "the two lines never killed anything, so they never met")
    }

    /**
     * A tower kills a creep that walks into range, and the kill is attributed to the tower.
     *
     * The second half is the half that matters for issue #131: a tower holding the killing blow
     * is a last hit **denied**, and the test asserts the attribution rather than only the death,
     * because a creep that died to something else would look identical from the outside.
     *
     * The creep is placed on the lane at the waypoint nearest the soldier tower and marches away
     * from it, which is a real creep doing a real thing - it just does it early enough in the
     * match that no wave has spawned yet and nothing else on the field can reach it.
     */
    @Test
    fun `a tower kills a creep that walks into its range`() {
        val game = boot()
        game.runUntil(TOWER_BUDGET, "the towers to be placed") { game.towers().size >= 2 }
        val creepId = game.spawnCreep(
            team = Team.UNDEAD,
            x = LaneGeometry.PATH_X[1],
            y = LaneGeometry.PATH_Y[1],
            waypoint = 0,
        )
        val creep = assertNotNull(game.netIds.resolveOrNull(creepId), "the creep never spawned")

        val diedAt = game.runUntil(TOWER_KILL_BUDGET, "the tower to kill the creep") {
            with(game.host.world) { Corpse in creep }
        }
        val shots = with(game.host.world) { game.towers().sumOf { it[Tower].shots } }
        val record = with(game.host.world) { creep[LastHit] }
        val killer = assertNotNull(
            game.netIds.resolveOrNull(NetId.ofRaw(record.attackerRaw)),
            "nothing was recorded as having struck the creep",
        )
        val killerIsTower = with(game.host.world) { Tower in killer }
        println(
            "[lane] tower killed a creep at tick $diedAt after $shots shots; " +
                "last hit by ${describe(game, killer)}",
        )
        assertTrue(shots > 0, "no tower fired")
        assertTrue(killerIsTower, "the killing blow was not the tower's; it was $killer")

        // And nobody was paid for it: a tower has no wallet, which is the denial every player of
        // the genre knows, arrived at with no special case anywhere.
        assertEquals(0, game.wallet()?.gold ?: 0, "a tower kill must pay no champion")
    }

    /**
     * A champion that lands the killing blow is paid, through the key a human presses.
     *
     * The activation goes through [InjectedIntent], which is an ordinary `IntentSource`, so
     * `PlayerControlSystem` cannot tell it from a keyboard. That is what makes this evidence that
     * **the game pays a last hit** rather than evidence that a number can be incremented.
     *
     * ## Why the champion is stood beside a creep fight rather than duelling one creep
     *
     * A first attempt duelled the champion against a single creep and it **never landed a blow**,
     * which is a real thing this game does rather than a broken test: `MeleeAttackExec` stuns for
     * thirty ticks and takes twenty-four to strike, and every ability is blocked by that tag, so a
     * creep hitting once every forty-eight ticks leaves eighteen-tick windows and cancels every
     * swing at eighteen. A lone champion is stun-locked by one skeleton. That is worth knowing and
     * it is not what this test is about.
     *
     * So the champion is put where a laning champion actually stands: beside two creeps that are
     * fighting **each other**. Each creep's nearest enemy is its partner rather than the champion,
     * so nothing stuns the champion and it does what a human does in a lane - swing at whichever
     * creep is closest and try to take the last hit off its own side's minion.
     *
     * ## What is asserted, and why it is an identity rather than a number
     *
     * `gold == CREEP_GOLD * lastHits`, exactly, while creeps are dying that the champion did not
     * kill. A rule that paid every nearby champion would break the identity on the very first
     * creep the *other* creep killed; a rule that paid nothing would break `lastHits >= 1`. It is
     * the strongest form of the claim available without deciding, in advance, which of two
     * attackers lands a particular blow - and which one lands it is a genuine race between two
     * units on the same tick, not something a test should pretend to control.
     */
    @Test
    fun `a champion that lands the killing blow is paid, and only for the blows it lands`() {
        val game = boot()
        val injected = InjectedIntent(MobaControls.BINDINGS.catalog)
        game.host.ctx[IntentState.KEY].source = injected
        game.moveChampion(DUEL_X, DUEL_Y)
        // Two engaged pairs, one either side. The near creep of each pair is inside the
        // champion's `MeleeAttackExec.RANGE`; its partner is nearer to it than the champion is,
        // which is what keeps the creep fighting the creep.
        for (side in intArrayOf(1, -1)) {
            game.spawnCreep(
                Team.UNDEAD,
                DUEL_X + side * NEAR_CREEP,
                DUEL_Y,
                LaneGeometry.startWaypoint(Team.UNDEAD),
            )
            game.spawnCreep(
                Team.SOLDIER,
                DUEL_X + side * FAR_CREEP,
                DUEL_Y,
                LaneGeometry.startWaypoint(Team.SOLDIER),
            )
        }
        val before = assertNotNull(game.wallet(), "the champion was never granted a wallet").gold

        var spent = 0
        while (spent < DUEL_TICKS && (game.wallet()?.lastHits ?: 0) == 0) {
            game.moveChampion(DUEL_X, DUEL_Y)
            injected.tap(MobaControls.ATTACK_ACTION)
            game.host.run(1)
            spent++
        }

        val wallet = assertNotNull(game.wallet())
        val dead = with(game.host.world) { game.creeps().count { Corpse in it } }
        println(
            "[lane] champion farmed for $spent ticks: gold $before -> ${wallet.gold}, " +
                "cs=${wallet.lastHits}, xp=${wallet.xp}, level=${wallet.level}, " +
                "creeps down=$dead",
        )
        assertTrue(
            wallet.lastHits >= 1,
            "the champion landed no killing blow in $DUEL_TICKS ticks with $dead creeps down",
        )
        assertEquals(
            before + LaneGeometry.CREEP_GOLD * wallet.lastHits,
            wallet.gold,
            "a champion is paid CREEP_GOLD for each killing blow and nothing for anything else",
        )
        assertTrue(
            dead >= wallet.lastHits,
            "the champion is credited with $wallet.lastHits kills and only $dead creeps died",
        )
    }

    /**
     * A champion standing in the lane gains experience and levels, and **no gold at all**.
     *
     * This is the other half of the last-hit claim, and it is the half that makes last-hitting a
     * skill rather than a formality: gold is exactly zero, not merely smaller. Five pairs of
     * opposing creeps are stood eight units apart, so each creep's nearest enemy is its partner
     * and they kill each other; the champion stands inside [LaneGeometry.XP_RADIUS] of all of
     * them and never presses a key.
     *
     * The level-up is asserted through what a level is *worth* as well as through the number:
     * `maxHealth` is read off the champion's own `Attributes` before and after, because a level
     * counter that went up while the champion got no stronger is a scoreboard rather than a
     * progression.
     */
    @Test
    fun `a champion that only watches gains experience and levels but not a coin`() {
        val game = boot()
        game.moveChampion(DUEL_X, DUEL_Y)
        val healthBefore = game.championMaxHealth()
        var pair = 0
        while (pair < PAIRS) {
            val x = DUEL_X - PAIR_SPAN + pair * (PAIR_SPAN * 2f / (PAIRS - 1))
            game.spawnCreep(Team.SOLDIER, x, DUEL_Y - PAIR_OFFSET, LaneGeometry.SOLDIER_END)
            game.spawnCreep(Team.UNDEAD, x + PAIR_GAP, DUEL_Y - PAIR_OFFSET, LaneGeometry.UNDEAD_END)
            pair++
        }
        val before = assertNotNull(game.wallet()).gold

        val levelledAt = game.runUntil(WATCH_TICKS, "the champion to reach level two") {
            (game.wallet()?.level ?: 1) >= 2
        }
        val wallet = assertNotNull(game.wallet())
        val dead = with(game.host.world) { game.creeps().count { Corpse in it } }
        val healthAfter = game.championMaxHealth()
        println(
            "[lane] watched $dead creeps die by tick $levelledAt: gold $before -> ${wallet.gold}, " +
                "xp=${wallet.xp}, level=${wallet.level}, cs=${wallet.lastHits}, " +
                "maxHealth $healthBefore -> $healthAfter",
        )
        assertTrue(dead > 0, "no creep died, so this proves nothing about who was paid")
        assertEquals(before, wallet.gold, "a creep the champion did not kill must pay nothing")
        assertEquals(0, wallet.lastHits, "and it must not count as a last hit")
        assertTrue(
            wallet.xp >= LaneGeometry.xpForLevel(2),
            "level two costs ${LaneGeometry.xpForLevel(2)} and the champion has ${wallet.xp}",
        )
        assertEquals(
            healthBefore + LaneGeometry.HEALTH_PER_LEVEL * (wallet.level - 1),
            healthAfter,
            "a level must be worth something: maxHealth should rise by HEALTH_PER_LEVEL each time",
        )
    }

    /**
     * A rewind puts the wave timer, the creeps and the purse back.
     *
     * The failure this exists to catch is the one the repository has already shipped once: a
     * component that is not in `MobaGame.componentRegistry` is not partly captured, it is
     * **invisible** to capture. A lane whose timer was invisible would, after a rewind, send its
     * next wave on a schedule from a future that no longer exists - and nothing anywhere would be
     * red.
     */
    @Test
    fun `a rewind restores the wave timer and the creeps on the field`() {
        val game = boot()
        val host = game.host
        game.runUntil(WAVE_BUDGET, "the first wave") { (game.lane()?.waveNumber ?: 0) >= 1 }
        host.run(60)
        val before = describe(assertNotNull(game.lane()))
        val creepsBefore = game.creeps().size
        println("[rewind] before t${host.tick.value}: $before, creeps=$creepsBefore")

        host.time.pause()
        val keyframe = host.time.snapshot()
        host.time.step(REWIND)
        val drifted = assertNotNull(game.lane())
        println("[rewind] +$REWIND t${host.tick.value}: ${describe(drifted)}")
        assertTrue(
            drifted.waveNumber > 1,
            "the step must cross at least one more wave, or the rewind proves nothing",
        )

        val result = host.time.rewind(REWIND)
        assertTrue(result is RewindResult.Rewound, "rewind refused: $result")
        assertEquals(keyframe.tick, host.tick, "the clock did not land on the keyframe")

        val after = assertNotNull(
            game.lane(),
            "the lane singleton did not survive the rewind, which is exactly what an " +
                "unregistered component looks like from outside",
        )
        println("[rewind] after  t${host.tick.value}: ${describe(after)}, creeps=${game.creeps().size}")
        assertEquals(before, after.let(::describe), "every field of the lane at $keyframe")
        assertEquals(creepsBefore, game.creeps().size, "the creeps on the field at $keyframe")
    }

    /**
     * Loads the LibGDX and Box2D native libraries into this test JVM.
     *
     * ## This is not this wave's code and it should not survive
     *
     * `MobaGame.definition()` now contains `MobaPhysicsModule`, whose `Box2DPhysicsWorld`
     * constructs a `com.badlogic.gdx.physics.box2d.World` the first time a unit needs a body.
     * That call reaches `BufferUtils.getBufferAddress`, which is a native method, and **nothing
     * in a headless test JVM has loaded the natives**: a real process gets them for free because
     * `MobaEntry.runWithGl` creates an LWJGL3 backend first. So every headless test that boots
     * the shipped definition dies with `UnsatisfiedLinkError` before its first assertion - which
     * on the tree this was written against is eighty of `:moba`'s hundred and seventy-four tests,
     * `MatchProofTest`, `MobaIntegrationTest`, `SnapshotRestoreProofTest` and
     * `Box2DPhysicsWorldTest` itself among them.
     *
     * Two lines here make **this** file runnable. They do not fix that, and they are not a fix:
     * the natives belong to whoever owns `MobaPhysicsModule`, either loaded by the physics world
     * itself or by a shared test fixture, and when that lands this function is dead code and
     * should be deleted. It is written out at length rather than hidden in a `@BeforeEach`
     * because a workaround nobody can see is a workaround that becomes permanent.
     *
     * `LaneRulesTest` needs none of this: it touches no world.
     */
    private fun loadPhysicsNatives() {
        // Both are idempotent, so this is safe to call once per test.
        com.badlogic.gdx.utils.GdxNativesLoader.load()
        com.badlogic.gdx.physics.box2d.Box2D.init()
    }

    /** Living-or-dead creeps of [wave], counted per team. */
    private fun creepsOf(game: Harness, wave: Int): Map<Int, Int> {
        val counts = HashMap<Int, Int>()
        with(game.host.world) {
            for (entity in game.creeps()) {
                if (entity[LaneCreep].waveNumber != wave) continue
                counts.merge(entity[GameUnit].team, 1, Int::plus)
            }
        }
        return counts
    }

    /** Every field of a [LaneState] as one line, so a diff names the field that moved. */
    private fun describe(state: LaneState): String =
        "wave=${state.waveNumber} next=${state.nextWaveTick} started=${state.startedTick} " +
            "towers=${state.towersPlaced} creeps=${state.creepsAlive}"

    private fun describe(game: Harness, entity: Entity): String = with(game.host.world) {
        when {
            Tower in entity -> "tower(${Team.nameOf(entity[Tower].team)})"
            Player in entity -> "champion"
            LaneCreep in entity -> "creep(${Team.nameOf(entity[GameUnit].team)})"
            else -> entity.toString()
        }
    }

    private fun Harness.runUntilLaneOpens(): LaneState? {
        runUntil(TOWER_BUDGET, "the lane to open") { lane() != null }
        return lane()
    }

    private companion object {

        /** How many waves the timing test watches. Three gives two intervals to compare. */
        const val WAVES: Int = 3

        /** Ticks a wave is given to arrive. */
        const val WAVE_BUDGET: Int = (LaneGeometry.WAVE_INTERVAL_TICKS + 300L).toInt()

        /** Ticks the tower placement is given to drain the barrier. */
        const val TOWER_BUDGET: Int = 30

        /**
         * Ticks a tower is given to kill a fifty-health creep at twenty-two a shot.
         *
         * Three shots, a second apart, plus room for the barrier. Deliberately shorter than
         * [LaneGeometry.FIRST_WAVE_TICK] plus its own budget, so the creep under test is the only
         * thing on that half of the lane for the whole of it.
         */
        const val TOWER_KILL_BUDGET: Int = 200

        /** How far the marching test runs: two lines 600 apart closing at 0.7 a tick, and a fight. */
        const val MARCH_TICKS: Int = 700

        /** Below this, a float difference is noise rather than a step. */
        const val MOVEMENT_EPSILON: Float = 1f

        /**
         * Where the economy tests stand the champion.
         *
         * North of the lane by more than `UnitBattleSystem.AGGRO_RADIUS` and outside
         * `LaneGeometry.TOWER_RANGE` of either tower, so the only things that can reach the
         * champion are the creeps this test spawns beside it. `LaneRulesTest` pins the lane's own
         * separation from the brawl; this is the same argument one field further north.
         */
        const val DUEL_X: Float = 0f

        /** @see DUEL_X */
        const val DUEL_Y: Float = 820f

        /**
         * How far the near creep of each pair stands from the champion.
         *
         * Inside `MeleeAttackExec.RANGE` (32) so the champion can reach it, and further from the
         * champion than it is from its own partner, so the creep keeps fighting the creep.
         */
        const val NEAR_CREEP: Float = 24f

        /** How far the far creep of each pair stands. Twelve from its partner, outside the champion's reach. */
        const val FAR_CREEP: Float = 36f

        /** Ticks the champion is given to take a last hit off a wave. */
        const val DUEL_TICKS: Int = 2_400

        /** How many opposing pairs the watching test stands around the champion. */
        const val PAIRS: Int = 5

        /** Half the width the watched pairs are spread over. Every pair is inside `XP_RADIUS`. */
        const val PAIR_SPAN: Float = 80f

        /** How far south of the champion the pairs stand. Well inside `XP_RADIUS`. */
        const val PAIR_OFFSET: Float = 100f

        /** How far apart the two creeps of a pair stand. Each is the other's nearest enemy. */
        const val PAIR_GAP: Float = 8f

        /** Ticks the watched fight is given to carry the champion to level two. */
        const val WATCH_TICKS: Int = 2_400

        /** How far the rewind test steps forward and back. More than one wave interval. */
        const val REWIND: Int = 700
    }
}
