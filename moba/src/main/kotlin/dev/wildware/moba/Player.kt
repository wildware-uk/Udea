package dev.wildware.moba

import com.github.quillraven.fleks.Component
import com.github.quillraven.fleks.ComponentType
import com.github.quillraven.fleks.Family
import dev.wildware.moba.ability.AbilityRpcChannel
import dev.wildware.moba.level.GameUnit
import dev.wildware.moba.level.MobaBlueprints
import dev.wildware.udea.annotations.Net
import dev.wildware.udea.annotations.Replicated
import dev.wildware.udea.annotations.Sim
import dev.wildware.udea.core.SimSystem
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.blueprint.BlueprintSpawner
import dev.wildware.udea.core.blueprint.SpawnPosition
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.gas.AbilityActivation
import dev.wildware.udea.gas.ActivationResult
import dev.wildware.udea.gas.Attributes
import dev.wildware.udea.gas.GameplayEffects
import dev.wildware.udea.net.transport.PeerId
import dev.wildware.udea.render.input.Intent
import dev.wildware.udea.render.input.IntentState

/**
 * The unit a human (or an agent) is driving, and what they asked it to do this tick.
 *
 * ## Why the intent is a component and not a device read at the point of use
 *
 * [moveX]/[moveY] are written at `SimPhase.Intent` by [PlayerControlSystem] and read at
 * `SimPhase.Movement` by [PlayerMovementSystem], and nothing else writes them. That is the same
 * split `udea-core`'s `MoveIntent` makes, for the same reason: a tick's movement is then a pure
 * function of (state, intent), so replaying a tick means restoring the state and re-supplying
 * this - no device, no frame rate and no wall clock anywhere in it.
 *
 * `udea-core`'s own `MoveIntent` is deliberately **not** reused: it is one horizontal axis and a
 * jump, because it drives `CharacterMover`, which is a platformer capsule with gravity and
 * ground normals. A top-down MOBA needs two axes and no gravity, and bending a 1D platformer
 * intent into that shape would be worse than three floats of this game's own.
 *
 * ## It is a marker as much as a value
 *
 * Two systems branch on its *presence*: [dev.wildware.moba.level.UnitBattleSystem] does not walk
 * a unit that has one (a player closes with WASD, not by itself), and the camera follows the
 * entity that has one. That is the same split the old game made with its `Player` and `AIUnit`
 * components, minus the second component - the family that named `AIUnit` was the only reader it
 * ever had.
 */
@Replicated
public class Player(
    /**
     * Horizontal axis this tick, `-1..1`. Written only at `SimPhase.Intent`.
     *
     * `@Sim` rather than `@Net`: a client is the *source* of its own intent and the server is the
     * source of everybody else's position, so sending this back would be echoing an input. It is
     * snapshotted because the component's **presence** is what tells two systems this unit is
     * driven rather than autonomous, and a restore that dropped it would hand the player's
     * soldier to the AI.
     */
    @Sim public var moveX: Float = 0f,
    /** Vertical axis this tick, `-1..1`. Positive is up. `@Sim`, for the reason [moveX] carries. */
    @Sim public var moveY: Float = 0f,
    /**
     * `-1` or `1`: which way the sprite faces.
     *
     * Held rather than derived, because it must **persist** while the player stands still: a
     * character that snapped back to facing right the moment you let go of A would read as a
     * rendering bug rather than as an input one.
     */
    @Sim public var facing: Float = 1f,
    /**
     * Which connection drives this champion: a [PeerId.raw], or [UNOWNED] for nobody's.
     *
     * `@Net` and not `@Sim`, and it is the only field of this component that is on the wire. A
     * two-player game has two `Player` entities in it and a client has to know **which one is
     * mine** - to point a camera at it, to draw its health bar differently, and for a human to
     * be able to tell at all. Nothing else replicated says so: `NetId`s are allocation order,
     * which is not a thing a client can predict, and there is no server-to-client RPC in this
     * build to be told over.
     *
     * It is the server's answer and it is not a permission: the check that decides whether a
     * datagram may fire this champion's abilities is `ChampionOwnership`, read by generated
     * code, and a client editing this field in its own replicated copy changes what its own
     * window draws and nothing else.
     */
    @Net public var owner: Int = UNOWNED,
) : Component<Player> {

    override fun type(): ComponentType<Player> = Player

    override fun toString(): String = "Player(move=($moveX, $moveY) facing=$facing owner=$owner)"

    public companion object : ComponentType<Player>() {

        /**
         * [owner] for a champion no connection drives.
         *
         * [PeerId.SERVER]'s raw value, deliberately: the server owns everything nobody else
         * does, which is the same rule `ChampionOwnership.ownerOf` answers for an entity it has
         * never been told about. Single-player leaves every champion here, and that is correct -
         * there are no connections.
         */
        public const val UNOWNED: Int = 0

        /**
         * Where the player's soldier lands, in the middle of the level's soldier cluster.
         *
         * The old `level/test_level.udea.kts` put a player-controlled soldier at the centre of
         * ten more; this is that position in this game's world units.
         */
        public const val SPAWN_X: Float = 0f

        /** @see SPAWN_X */
        public const val SPAWN_Y: Float = 0f

        /**
         * Spawns the soldier a client drives, and hands back the id the camera follows.
         *
         * A [MobaBlueprints.soldier] with a [Player] laid over it rather than a blueprint of its
         * own, and that is the point: the unit a human steers must be **the same kind of thing**
         * as the ten beside it, or the game a player experiences is not the game the server
         * simulates. The override is one component, and it is the whole difference.
         *
         * `spawn` and not `spawnNow`: this is called from outside a tick, so the entity appears
         * when the barrier drains at the top of the next one - which is also why the caller must
         * run a tick before the returned [NetId] resolves to anything.
         */
        public fun spawn(
            spawner: BlueprintSpawner,
            blueprints: MobaBlueprints,
            x: Float = SPAWN_X,
            y: Float = SPAWN_Y,
            owner: Int = UNOWNED,
        ): NetId = spawner.spawn(
            blueprint = blueprints.soldier,
            position = SpawnPosition(x, y),
        ) { context, entity ->
            with(context) { entity += Player(owner = owner) }
        }
    }
}

/**
 * Which [Intent] drives which champion, for a process with more than one human in it.
 *
 * ## Why this exists rather than a second `IntentState`
 *
 * [IntentState] is one `Intent` per world, because a single-player process has one pair of
 * hands. A server with two connections has two, and they are not interchangeable: the axis that
 * arrived from `client2` must reach `client2`'s champion and no other, or the second player
 * drives the first one's soldier - which is what "client 2 is a spectator" was hiding.
 *
 * It is a **lookup by [NetId]** and not a per-entity component, because the mapping is a
 * property of the *connection* rather than of the world: it must not be snapshotted, must not be
 * replicated, and must survive a rewind that recreates the champion's components. A `Player`
 * entity nobody is driving answers `null`, and [PlayerControlSystem] leaves it standing still -
 * a champion whose owner disconnected does not inherit the last axis they were holding.
 *
 * `null` on the system itself means "there is one pair of hands", which is single-player and
 * every test that has never heard of a peer. That is the default and it is the path
 * `MobaModule` wires.
 */
public fun interface PlayerIntents {

    /** This tick's input for the champion [self], or `null` when nobody is driving it. */
    public fun intentFor(self: NetId): Intent?
}

/**
 * Turns this tick's [dev.wildware.udea.render.input.Intent] into every [Player]'s move axis.
 *
 * ## It reads a value, not a device
 *
 * This is what `PlayerControlSystem` was in the old tree, and the difference is the whole of
 * issue #124. That one held `world.system<ControllerSystem>()` and asked it for an axis that
 * `ControllerSystem` had polled off `Gdx.input` **inside the tick**, at frame rate. This one
 * reads [IntentState.intent], which `IntentSampleSystem` filled at the top of this same tick out
 * of whatever source is wired: a keyboard, an agent's `input.*` tools, a replayed buffer. There
 * is no branch here for which, which is exactly why an agent driving this game is not driving a
 * different code path from a human - `MobaInputTest` runs it from an injected source with no
 * window in the process at all.
 *
 * Registered at `SimPhase.Intent` **after** `IntentSampleSystem` (declared in [MobaModule], not
 * left to registration order), so the axis it reads was sampled on this tick rather than the
 * previous one.
 *
 * ## Two keys, two slots
 *
 * Both attacks activate the player's own ability slot through [AbilityActivation] - the same call
 * `dev.wildware.moba.ability.AbilityAutopilotSystem` makes for every AI unit on the field, with
 * the same cost check, the same cooldown effect and the same exec. It used to write
 * `unit.attackReadyTick = 0L`, which reached into a second combat implementation that no longer
 * exists; a player whose swing is a different code path from the soldier beside them is a player
 * playing a different game from the one the server simulates.
 *
 * [MobaControls.ATTACK] fires [SLOT_PRIMARY] and [MobaControls.ATTACK_2] fires [SLOT_SECONDARY],
 * which are the two slots `UnitBlueprint.dress` grants in that order - slot 0 the basic attack,
 * slot 1 the special. That mapping is the fix for a control that was bound, packed, and read by
 * nothing: `attack_2` is declared in `moba/assets/control/controls.udea.kts` on `Q`, it resolves
 * into [MobaControls.ATTACK_2_ACTION], and no system in the tree ever asked an intent about it.
 *
 * What it replaced was **one** key running "highest granted slot that will fire wins". That is a
 * defensible rule for an autopilot and a bad one for hands: Space silently spent the soldier's
 * five-second fire arrow whenever it happened to be up, so the player's only key did two
 * different things depending on state they could not see - and the second key could not have been
 * given anything to do while the first was eating both slots. Space is now always the sword and Q
 * is always the arrow.
 *
 * A slot the unit was never granted - Q on an orc, which has only a melee - is a refusal and not
 * a crash. Refusals are counted rather than swallowed, because "the input never arrived" and "it
 * arrived and the ability was cooling down" look identical from outside the process, and
 * [MobaHudModel] is what puts that difference on the screen.
 */
public class PlayerControlSystem(
    private val input: IntentState,
    /** The one activation path in this game. The AI uses the same object. */
    private val activation: AbilityActivation,
    /**
     * Where a press is sent when this process is a **connected client** (issue #109).
     *
     * `null` - the default, and what single-player and every test that does not care runs -
     * means the press is simply activated locally, which is the behaviour this system has
     * always had. Nullable rather than a no-op default channel, because a channel that
     * silently swallowed calls would make a wiring mistake invisible.
     */
    private val channel: AbilityRpcChannel? = null,
) : SimSystem() {

    /**
     * Per-champion input, or `null` for the one-pair-of-hands case.
     *
     * A `var` set after the world is built rather than a constructor parameter, and that is
     * forced rather than chosen: `MobaModule` is what constructs this system, out of a factory
     * that is handed only the `GameContext`, and the router belongs to a
     * [dev.wildware.moba.net.MobaHostSession] that does not exist until after
     * `definition.build()`. It is scoped to **this world's** system instance - not a process
     * global - so two sessions in one JVM route their own peers' hands to their own champions.
     *
     * While it is null every [Player] reads [input], which is exactly what single-player, the
     * agent's instance and every existing test do.
     */
    public var intents: PlayerIntents? = null

    /** Resolved once at construction; `world.family { }` per tick is a lookup on a hot path. */
    private val players: Family = world.family { all(Player) }

    private val netIds: NetIdIndex = ctx[CoreModule.NET_IDS]

    /** Abilities actually started by a key press, either key. A signal for a test and a log line. */
    public var attacksRequested: Long = 0L
        private set

    /** Presses that reached a unit and fired nothing: not granted, on cooldown, out of mana. */
    public var attacksRefused: Long = 0L
        private set

    /** [SLOT_SECONDARY] activations only. What separates "Q is wired" from "a key was pressed". */
    public var specialsRequested: Long = 0L
        private set

    /** [SLOT_SECONDARY] presses that fired nothing. Mostly "the special is still cooling down". */
    public var specialsRefused: Long = 0L
        private set

    override fun onTick() {
        val router = intents
        // Read once when there is one pair of hands, so the single-player path allocates and
        // looks up nothing per entity - which is the path every test and the agent still run.
        val shared = if (router == null) input.intent else null
        val now = tick
        players.forEach { entity ->
            val player = entity[Player]
            val self = netIds.netIdOf(entity)
            val intent = shared ?: router?.intentFor(self)
            if (intent == null) {
                // Nobody is driving this champion this tick. Zeroed rather than left alone: a
                // disconnected player's soldier that kept walking would look like the server
                // still taking their input, and `PlayerMovementSystem` reads exactly these two.
                player.moveX = 0f
                player.moveY = 0f
                return@forEach
            }
            val x = intent.axisX(MobaControls.MOVE_AXIS)
            val y = intent.axisY(MobaControls.MOVE_AXIS)
            val primary = intent.isJustPressed(MobaControls.ATTACK_ACTION)
            val secondary = intent.isJustPressed(MobaControls.ATTACK_2_ACTION)
            player.moveX = x
            player.moveY = y
            // Only on a real deflection: see `Player.facing`.
            if (x > 0f) player.facing = 1f else if (x < 0f) player.facing = -1f
            // The sprite follows the hands, overwriting the facing `UnitBattleSystem` derived
            // from whoever this unit is targeting. A character that turns to face an enemy while
            // you walk the other way reads as the controls being ignored.
            entity.getOrNull(CharacterView)?.flipX = player.facing < 0f
            if (!primary && !secondary) return@forEach
            val abilities = entity.getOrNull(Abilities) ?: return@forEach
            val attributes = entity.getOrNull(Attributes) ?: return@forEach
            val effects = entity.getOrNull(GameplayEffects) ?: return@forEach
            // Both keys are read on the same tick when both went down on it. A player who mashes
            // Space and Q together means both, and a rule that dropped one would be a rule they
            // have to learn by losing a fight.
            if (primary) {
                if (fire(self, abilities, attributes, effects, SLOT_PRIMARY, now)) {
                    attacksRequested++
                } else {
                    attacksRefused++
                }
            }
            if (secondary) {
                if (fire(self, abilities, attributes, effects, SLOT_SECONDARY, now)) {
                    attacksRequested++
                    specialsRequested++
                } else {
                    attacksRefused++
                    specialsRefused++
                }
            }
        }
    }

    /**
     * Starts [slot] if the unit has it and it will go, and says whether it went.
     *
     * A slot past [Abilities.slotCount], or one the unit was never granted, is `false` rather than
     * a throw: `UnitBlueprint.dress` grants as many slots as the kind declares abilities, so an
     * orc has nothing in [SLOT_SECONDARY], and a player who takes one over must get a refusal for
     * Q rather than an exception out of the middle of `SimPhase.Intent`.
     *
     * `=== ActivationResult.Activated` rather than `.isActivated`: every refusal case carries a
     * reason and is a `data class`, so this is an identity comparison against the one `data
     * object` and the path that fires allocates nothing.
     */
    @Suppress("LongParameterList")
    private fun fire(
        self: NetId,
        abilities: Abilities,
        attributes: Attributes,
        effects: GameplayEffects,
        slot: Int,
        now: Tick,
    ): Boolean {
        if (slot >= abilities.slotCount) return false
        if (!abilities.instanceAt(slot).isGranted) return false
        // `@Rpc(authority = OwnerPredicted)` means both halves of this line, not one of them:
        // the press goes to the server, which re-checks that this connection owns `self` in
        // generated code before it fires anything, **and** it is run locally so the swing
        // appears on the tick the key went down instead of a round trip later. What the client
        // sends is the *press*; what it never sends is the resulting state. That is the whole
        // difference from `NetworkClientSystem.kt:57`, which uploaded component state for
        // every owned entity at render rate and thereby had nothing to reconcile against.
        channel?.activateAbility(self, slot)
        return activation.activate(self, abilities, attributes, effects, slot, now) ===
            ActivationResult.Activated
    }

    public companion object {

        /**
         * The slot [MobaControls.ATTACK] fires. The basic attack.
         *
         * `UnitBlueprint.dress` walks `UnitKind.abilities` into slots in declaration order and
         * every kind in `MobaUnits.kinds` declares its melee first, so slot 0 is the sword on
         * every unit in this game - which is what makes one constant here correct rather than a
         * soldier-shaped assumption about the one unit a human happens to drive today.
         */
        public const val SLOT_PRIMARY: Int = 0

        /**
         * The slot [MobaControls.ATTACK_2] fires. The special, where the kind has one.
         *
         * The soldier's fire arrow, the priest's heal, the elite orc's spin. An orc, a skeleton
         * and a wizard have no slot 1 at all, and pressing Q as one of them is a counted refusal.
         */
        public const val SLOT_SECONDARY: Int = 1
    }
}

/**
 * Moves every [Player] by its axis. `SimPhase.Movement`.
 *
 * ## Units per **tick**, matching the units beside it
 *
 * `UnitKind.moveSpeed` is world units per tick - that is the convention the ported level uses,
 * and `UnitBattleSystem` closes on a target with `position.x += dx / distance * kind.moveSpeed`.
 * A player scaled by `ctx.clock.dt` instead would be sixty times slower than the soldier next to
 * it, which is a difference a player notices immediately and a reviewer notices never. When the
 * fight moves to seconds, both move together.
 *
 * Either way it is **not** `IntervalSystem.deltaTime`: a frame duration would make how far you
 * walked depend on how long the last frame took, and two processes fed the same intent stream
 * would end at different coordinates.
 *
 * A player with no [GameUnit] - which nothing spawns today, but a test may - falls back to the
 * soldier's speed rather than standing still, because a controllable thing that does not move is
 * the hardest bug in this file to attribute.
 */
public class PlayerMovementSystem : SimSystem() {

    private val players: Family = world.family { all(Player, Position) }

    override fun onTick() {
        players.forEach { entity ->
            val player = entity[Player]
            if (player.moveX == 0f && player.moveY == 0f) return@forEach
            val position = entity[Position]
            val speed = entity.getOrNull(GameUnit)?.unitKind?.moveSpeed ?: FALLBACK_SPEED
            position.x += player.moveX * speed
            position.y += player.moveY * speed
        }
    }

    private companion object {
        /** What a [Player] with no [GameUnit] walks at. Nothing the level spawns is one. */
        const val FALLBACK_SPEED: Float = 0.75f
    }
}
