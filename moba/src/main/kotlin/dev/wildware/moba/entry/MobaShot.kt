package dev.wildware.moba.entry

import com.github.quillraven.fleks.Entity
import com.github.quillraven.fleks.EntityCreateContext
import dev.wildware.moba.CharacterView
import dev.wildware.moba.MobaCharacters
import dev.wildware.moba.Position
import dev.wildware.moba.UnitState
import dev.wildware.udea.core.blueprint.Blueprint
import dev.wildware.udea.core.blueprint.blueprints
import dev.wildware.udea.core.blueprint.BlueprintId
import dev.wildware.udea.core.blueprint.SpawnPosition
import dev.wildware.udea.core.host.RenderMode
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeBytes
import kotlin.system.exitProcess

/**
 * `moba.shot`: boot Offscreen, stand the whole roster on the field, capture one PNG, exit.
 *
 * ## Why this exists as an entry point rather than as a test
 *
 * It is the evidence for a claim that no headless assertion can make: **the characters exist and
 * they animate, and they are six different sprites rather than one placeholder drawn six times.**
 * A test can assert that the atlas holds 195 regions across 33 sheets, and `MobaCharacterTest`
 * does; only a framebuffer can say whether any of them reached it.
 *
 * It is deliberately **not** wired into `check`. It needs a GL driver, and a gate that turns into
 * a skip on a machine without one is the failure mode this repository has already shipped once
 * (see `udeaBenchStartup`'s KDoc for the same argument at greater length). It is a task an
 * operator or an agent runs by name: `./gradlew :moba:runShot`.
 *
 * ## What it draws, and why it does not call [MobaEntry.seed]
 *
 * `seed` loads `level/test_level` - twenty-seven units of a real fight, which is the right thing
 * for every other entry point and the wrong thing for a picture whose whole job is to show that
 * unit A and unit B are different sprites. Twenty-seven units in four clusters overlap, and a
 * fight decides its own states, so two runs frame differently.
 *
 * So this spawns the roster the *bundle* declares, one of each, evenly spaced, each one held in a
 * state chosen by its position in the line - so a single frame shows an idle, a walk, a swing, a
 * flinch and a death at once, and every one of them is a different character's art. Nothing about
 * it is a mock: the entities are real, spawned through the real `BlueprintSpawner`, drawn by the
 * same `CharacterRenderSystem` every other entry point registers, out of the same `.udeapak`.
 */
public object MobaShot {

    /** Where the PNG is written. */
    public const val OUTPUT_PROPERTY: String = "udea.shot.out"

    /** Which tick to capture on, so the picture is reproducible. */
    public const val TICK_PROPERTY: String = "udea.shot.tick"

    /** World y every unit stands on. Inside `MobaScene`'s camera with room above and below. */
    public const val LINE_Y: Float = -20f

    /** World x of the leftmost unit. */
    public const val FIRST_X: Float = -78f

    /** World units between neighbours. Wider than the widest frame, so nobody overlaps. */
    public const val SPACING: Float = 37f

    /**
     * The state the unit at [index] is held in.
     *
     * A pure function of the index rather than of the tick, because the picture has to show
     * several *different* animations at once and a shared clock would show one. Every state is
     * covered as long as the roster has five or more characters, which it does.
     */
    public fun stateFor(index: Int): UnitState =
        UnitState.entries[Math.floorMod(index, UnitState.entries.size)]

    @JvmStatic
    public fun main(args: Array<String>) {
        StartupTrace.enterMain()
        val out = Path.of(System.getProperty(OUTPUT_PROPERTY) ?: "build/reports/udea/roster.png")
        val tick = (System.getProperty(TICK_PROPERTY) ?: "18").toLong()
        val mode = MobaEntry.modeFromProperties(fallback = RenderMode.Offscreen)
        require(mode != RenderMode.Headless) { "moba.shot captures a frame; Headless presents none" }
        var written = false
        MobaEntry.runWithGl(mode) { host, rendering ->
            // This capture has no player to follow - it is a line of roster entries, not the
            // level - so it is the one entry point that asks for the fixed framing. See
            // `MobaScene.frameLevel` for why that is a call rather than something `build` does.
            rendering.scene.frameLevel()
            val roster = MobaCharacters.roster
            roster.entries.forEachIndexed { at, entry ->
                host.ctx.blueprints.spawn(
                    RosterBlueprint(at, entry.name, stateFor(at)),
                    SpawnPosition(FIRST_X + at * SPACING, LINE_Y),
                )
            }
            // The spawns are barrier actions, so they land at the top of the next step; the
            // capture asks for a tick well past that, which is what makes "the entities exist by
            // the time the frame is drawn" a property of the request rather than of a sleep.
            host.run(1)
            val future = rendering.presentation().capture(afterTick = tick)
            MobaEntry.Attachment(
                frame = { delta ->
                    host.frame(delta)
                    if (!written && future.isDone) {
                        val result = future.get()
                        out.createParentDirectories()
                        out.writeBytes(result.bytes)
                        println(
                            "[moba.shot] ${out.toAbsolutePath()} ${result.width}x${result.height} " +
                                "at tick ${result.tick.value}, ${roster.size} characters",
                        )
                        written = true
                        System.out.flush()
                        rendering.requestExit()
                    }
                },
            )
        }
        if (!written) {
            // Non-zero, because a shot that captured nothing must not look green. The most likely
            // cause is a driver that died before the requested tick was reached.
            System.err.println("[moba.shot] no frame was captured before the loop ended")
            exitProcess(1)
        }
        // The capture already landed on disk; nothing here waits on GL teardown.
        TimeUnit.MILLISECONDS.sleep(0)
    }
}

/**
 * One roster entry standing still in a chosen state.
 *
 * A `Blueprint` and not a hand-built entity because the spawner is what attaches a `NetId` and
 * applies the position, and an entity configured outside it would be invisible to
 * `world.query_entities` and to the snapshot ring - which would make this picture a picture of
 * something the rest of the engine cannot see.
 */
private class RosterBlueprint(
    private val index: Int,
    name: String,
    private val state: UnitState,
) : Blueprint {

    override val id: BlueprintId = BlueprintId("roster/$name")

    override fun configure(context: EntityCreateContext, entity: Entity) {
        with(context) {
            entity += Position()
            entity += CharacterView(character = index, state = state, startTick = 0L)
        }
    }
}
