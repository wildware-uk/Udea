package dev.wildware.moba

import com.github.quillraven.fleks.World.Companion.family
import dev.wildware.moba.ability.Combatant
import dev.wildware.moba.entry.MobaEntry
import dev.wildware.moba.match.MatchPhase
import dev.wildware.moba.match.MatchService
import dev.wildware.udea.core.host.GameHost
import dev.wildware.udea.core.host.RenderMode
import dev.wildware.udea.core.identity.NetId
import dev.wildware.udea.core.identity.NetIdIndex
import dev.wildware.udea.core.module.CoreModule
import dev.wildware.udea.gas.Abilities
import dev.wildware.udea.render.capture.CaptureResult
import dev.wildware.udea.render.input.InjectedIntent
import dev.wildware.udea.render.input.IntentState
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeBytes
import kotlin.system.exitProcess

/**
 * Four captures of the shipping game, each taken on the tick its subject is actually on screen.
 *
 * ## Why this is a harness and not a test
 *
 * The same reason [VfxShot] is one, and stated there at length: it creates a real GL context, so
 * on a machine with no driver it is a failure rather than a skip, and a gate that turns into a
 * skip is the failure mode this repository has already shipped once. It lives in the **test**
 * source set so `check` compiles it and the shipped jar does not carry it, and it has no `@Test`,
 * so JUnit never runs it. It is launched by hand off the test runtime classpath.
 *
 * ## What it photographs, and why these four
 *
 * These are the four claims a play agent graded "partly" and could not verify from a screenshot:
 *
 * | file | the claim it is evidence for |
 * |---|---|
 * | `melee.png` | overlapping units can be told apart, and one of them is visibly *you* |
 * | `hud.png` | a human can read their own health, their cooldowns and the score |
 * | `spin.png` | the elite orc special has art of its own and it is on screen when it fires |
 * | `result.png` | a match ends and the game says who won |
 *
 * `melee.png` and `hud.png` are two moments of one claim rather than the same file twice: the HUD
 * is drawn at `RenderPhase.UI`, which is *before* the capture point on purpose, so every frame
 * here carries it and the second one is taken once a cooldown is visibly running.
 *
 * ## The input is injected, not simulated
 *
 * The spin is fired by pressing the key a human presses. [InjectedIntent] is an ordinary
 * `IntentSource`, so `PlayerControlSystem` cannot tell it from a keyboard - which is what makes
 * `spin.png` evidence that **the control works** rather than evidence that an ability can be
 * activated from outside the input path.
 */
public object MatchShot {

    /** Where the PNGs are written. One per subject. */
    public const val OUTPUT_PROPERTY: String = "udea.matchshot.dir"

    /** The tick the melee is photographed on. Far enough in that the lines have met. */
    public const val MELEE_TICK: Long = 420L

    /** The tick the HUD is photographed on. Later, so a cooldown is running in a slot. */
    public const val HUD_TICK: Long = 560L

    /** Give up after this many ticks. Long enough for a match to resolve on the default layout. */
    public const val DEADLINE_TICKS: Long = 2_400L

    private class Shot(
        val name: String,
        val future: CompletableFuture<CaptureResult>,
        val note: String,
    )

    @JvmStatic
    @Suppress("LongMethod", "NestedBlockDepth", "CyclomaticComplexMethod")
    public fun main(args: Array<String>) {
        val dir = Path.of(System.getProperty(OUTPUT_PROPERTY) ?: "build/reports/udea/match")
        val mode = MobaEntry.modeFromProperties(fallback = RenderMode.Offscreen)
        val pending = ArrayList<Shot>()
        val written = HashSet<String>()
        val log = StringBuilder()
        MobaEntry.runWithGl(mode) { host, rendering ->
            val player = MobaEntry.seed(host)
            val injected = InjectedIntent(MobaControls.BINDINGS.catalog)
            host.ctx[IntentState.KEY].source = injected
            MobaEntry.follow(rendering, player)
            val match = host.ctx[MatchService.KEY]
            val netIds = host.ctx[CoreModule.NET_IDS]
            var spinAskedAt = -1L
            var resultAsked = false
            MobaEntry.Attachment(
                frame = { delta ->
                    host.frame(delta)
                    val now = host.ctx.clock.tick.value
                    if (now < MELEE_TICK && pending.none { it.name == "melee" } && "melee" !in written) {
                        pending += Shot(
                            "melee",
                            rendering.presentation().capture(afterTick = MELEE_TICK),
                            "the melee, camera on the player",
                        )
                    }
                    if (now < HUD_TICK && pending.none { it.name == "hud" } && "hud" !in written) {
                        pending += Shot(
                            "hud",
                            rendering.presentation().capture(afterTick = HUD_TICK),
                            "the HUD with a cooldown running",
                        )
                    }
                    // The spin: press Q, then photograph the tick after the activation lands. The
                    // press is repeated until the slot reports itself active, because the ability
                    // refuses while the unit is mid-cast or the slot is cooling, and a single tap
                    // that happened to land in that window would photograph nothing.
                    if (spinAskedAt < 0 && now > MELEE_TICK) {
                        if (spinIsActive(host, netIds, player)) {
                            spinAskedAt = now
                            pending += Shot(
                                "spin",
                                rendering.presentation().capture(afterTick = now + 2),
                                "the spin, fired from the bound key, activated at tick " + now,
                            )
                        } else {
                            injected.tap(MobaControls.ATTACK_2_ACTION)
                        }
                    }
                    // The result banner, thirty ticks after the match is decided, so the HUD has
                    // certainly sampled it.
                    if (!resultAsked && match.hasMatch && match.phase != MatchPhase.Fighting) {
                        resultAsked = true
                        pending += Shot(
                            "result",
                            rendering.presentation().capture(afterTick = now + 30),
                            "match " + match.matchNumber + " won by team " + match.winner +
                                " decided on tick " + match.endedTick,
                        )
                    }
                    val done = pending.filter { it.future.isDone }
                    for (shot in done) {
                        val result = shot.future.get()
                        val out = dir.resolve(shot.name + ".png")
                        out.createParentDirectories()
                        out.writeBytes(result.bytes)
                        written += shot.name
                        pending.remove(shot)
                        log.append("[match.shot] wrote ").append(out.toAbsolutePath())
                            .append(" ").append(result.width).append("x").append(result.height)
                            .append(" at tick ").append(result.tick.value)
                            .append(" - ").append(shot.note)
                            .append(" | alive=").append(alive(host))
                            .append(" score orc=").append(match.orcAlive)
                            .append(" soldier=").append(match.soldierAlive)
                            .append(" undead=").append(match.undeadAlive)
                            .append("\n")
                    }
                    if (written.size == SUBJECTS.size || now > DEADLINE_TICKS) {
                        rendering.requestExit()
                    }
                },
            )
        }
        print(log)
        val missing = SUBJECTS - written
        if (missing.isNotEmpty()) {
            System.err.println("[match.shot] never captured: " + missing)
            exitProcess(1)
        }
    }

    /** The four subjects. */
    private val SUBJECTS = listOf("melee", "hud", "spin", "result")

    /** Living units, by the definition the match counts them with. */
    private fun alive(host: GameHost): Int =
        host.world.family { all(Combatant) }.entities.size

    /** Whether the player secondary slot is mid-cast right now. */
    private fun spinIsActive(host: GameHost, netIds: NetIdIndex, player: NetId): Boolean {
        val entity = netIds.resolveOrNull(player) ?: return false
        return with(host.world) {
            val abilities = entity.getOrNull(Abilities) ?: return@with false
            if (abilities.slotCount <= PlayerControlSystem.SLOT_SECONDARY) return@with false
            abilities.instanceAt(PlayerControlSystem.SLOT_SECONDARY).isActive
        }
    }
}
