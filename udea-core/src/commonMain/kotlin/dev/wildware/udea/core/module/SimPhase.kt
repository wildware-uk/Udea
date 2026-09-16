package dev.wildware.udea.core.module

/**
 * The fixed running order of a simulation tick.
 *
 * ## What it replaces
 *
 * The old engine had no phases. `UdeaGameManager.DefaultSystems` (`:391`) was a list, engine
 * systems ran in whatever order that list happened to spell, and a game could only ever
 * *append* after all of them — so a game system that had to run before physics could not.
 * Which systems ran at all was decided at runtime by `@UdeaSystem(runIn = [...])` and
 * `kClass.createInstance()` (`:281`), so a system with a real constructor parameter did not
 * fail to compile, it failed to start.
 *
 * Here the order is a **declared** property of every system: its phase is chosen at
 * registration and the compiler checks the constructor. A game module registering into
 * [Ability] lands between the engine's [PreSimulation] and [Movement] work without either
 * module knowing about the other.
 *
 * ## Phases are absolute
 *
 * Every system in an earlier phase runs before every system in a later one, on every machine
 * and every run. `before`/`after` constraints order systems *within* a phase; a constraint
 * that crosses a phase boundary is checked for agreement with these ordinals and fails the
 * build when it disagrees, rather than silently losing (see `SimRegistry`).
 *
 * The ordinal is therefore load-bearing. Inserting a phase in the middle changes when every
 * later phase's systems run relative to the earlier ones, which is a behaviour change for
 * every module — do it deliberately, not to make one system fit.
 */
public enum class SimPhase {

    /** Player and AI input becomes intent components. Nothing else may write intent. */
    Intent,

    /**
     * Commands queued for this tick are consumed: teleports, spawns, despawns.
     *
     * A command consumed here has its effect visible to every later phase of the same tick,
     * which is what makes `Teleport` a legitimate way to move a body and a mid-tick
     * `setTransform` an illegitimate one (spec 3.4).
     */
    PreSimulation,

    /** Abilities activate, tick and expire. Tick-denominated, never seconds (spec 5). */
    Ability,

    /** Attribute aggregation: base values plus the modifiers [Ability] just changed. */
    Attribute,

    /** `CharacterMover` — the authoritative movement model for anything predicted (spec 3.4). */
    Movement,

    /**
     * The non-authoritative solver advances exactly one tick.
     *
     * After [Movement] deliberately: Box2D serves sensor queries, debris and server-only
     * projectiles, so it reacts to authoritative movement rather than deciding it.
     */
    Physics,

    /** Reads what the solver produced: contacts, sensor overlaps, projectile hits. */
    PostPhysics,

    /** Game rules: damage, scoring, objectives. The phase a game module usually wants. */
    Gameplay,

    /** Snapshot capture and outbound replication, after every writer for this tick has run. */
    Replication,

    /** Destruction, one-shot component removal, per-tick scratch reset. */
    Cleanup,
}
