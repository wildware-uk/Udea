package dev.wildware.moba.match

import dev.wildware.moba.CharacterStateSystem
import dev.wildware.moba.ability.CharacterAttributes
import dev.wildware.udea.core.GameContextBuilder
import dev.wildware.udea.core.module.SimPhase
import dev.wildware.udea.core.module.SimRegistry
import dev.wildware.udea.core.module.UdeaModule

/**
 * The game loop, as a module: a match, a result, a restart and a respawn.
 *
 * ## Why this is a module of its own and not three more systems in `MobaModule`
 *
 * `MobaModule` owns *what a unit is* - the roster, the fight between units, the animation the
 * fight produces. This owns *what a session is*, and the two change for different reasons: a
 * balance pass edits the first and never the second, and a change to how long a result stands
 * edits the second and never the first. Keeping them apart is also what makes the loop
 * removable: a definition assembled without this module is the fight simulator this game was
 * before, which is exactly what a combat unit test wants and exactly what a player does not.
 *
 * It is deliberately **not** a second `MobaAbilityModule`-style owner of content. It registers
 * two systems, publishes one service, and holds no tables.
 *
 * ## Wiring
 *
 * `MobaGame.definition()` is what puts this in the list, beside `MobaModule` and
 * `MobaAbilityModule`, and it must be handed the *same* [CharacterAttributes] the units were
 * dressed with: an [dev.wildware.udea.gas.AttributeId] is an index into one `AttributeTable`, so
 * a module built over a table nothing in the world is using would restore a respawning player's
 * *armour* to full and leave its health at zero. That is why the attributes are a constructor
 * parameter rather than a `CharacterAttributes.create()` call in this file.
 */
public class MatchModule(
    /**
     * The one attribute table this game's units are dressed against.
     *
     * `MobaAbilityModule.attributes`, and nothing else. See the class KDoc for what a second
     * table would cost.
     */
    private val attributes: CharacterAttributes,
) : UdeaModule {

    override val name: String get() = "moba-match"

    /**
     * The read mirror, published on the context and handed to both systems.
     *
     * One object, constructed here, so the system that writes it and the renderer that reads it
     * cannot be looking at two different scoreboards. The authoritative state is [MatchState] on
     * a singleton entity; see [MatchService] for why a mirror exists at all.
     */
    public val service: MatchService = MatchService()

    override fun context(builder: GameContextBuilder) {
        builder.service(MatchService.KEY, service)
    }

    /**
     * Both systems in `SimPhase.Cleanup`, in a declared order rather than this file's line order.
     *
     * `Cleanup` because both read the outcome of the tick that has just been simulated:
     * `DeathSystem` runs in `SimPhase.Gameplay`, so by `Cleanup` everything that died this tick
     * has lost its `Combatant` and the alive counts are this tick's rather than last tick's.
     *
     * The two constraints are both load-bearing and neither is cosmetic:
     *
     * - `after(MatchSystem)` so a player cannot stand up into a match that was decided on this
     *   very tick - `RespawnSystem` asks [MatchService] for the phase, and the mirror is written
     *   by `MatchSystem` at the end of its own tick.
     * - `before(CharacterStateSystem)` so a unit revived on this tick has its pose derived from
     *   the health that was just restored. Run the other way round, the frame a player respawns
     *   on still draws the death animation, which reads as the respawn not having worked.
     *
     * Declared, because `SimRegistry` resolves a phase by these edges and falls back to
     * registration order otherwise: somebody reordering two lines here would introduce a
     * one-tick fault that no test names.
     */
    override fun simulation(registry: SimRegistry) {
        registry.add(SimPhase.Cleanup, { MatchSystem(service) })
        registry.add(SimPhase.Cleanup, { RespawnSystem(attributes.health, service) }) {
            after(MatchSystem::class)
            before(CharacterStateSystem::class)
        }
    }

    override fun toString(): String = "MatchModule($service)"
}
