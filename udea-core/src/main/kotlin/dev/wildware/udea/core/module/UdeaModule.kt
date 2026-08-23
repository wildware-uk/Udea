package dev.wildware.udea.core.module

import dev.wildware.udea.core.GameContextBuilder

/**
 * A unit of engine or game content, registered explicitly.
 *
 * ## What it replaces
 *
 * Discovery. `@UdeaSystem(runIn = [Editor, Game])` (`common/ecs/UdeaSystem.kt`) decided at
 * runtime which systems existed, and `UdeaGameManager.DefaultSystems` decided the order by
 * being a list literal a game could only append to. Neither was checkable: a system that had
 * been renamed out of a package, or annotated wrong, simply did not run, and nothing said so.
 *
 * A module is ordinary code. It is constructed by the caller — so it may take whatever
 * dependencies it wants — and it is listed in a [UdeaGameDef], so "which modules are in this
 * game" is a value you can read, print and test rather than a classpath scan.
 *
 * ## The two hooks
 *
 * [context] runs first, for every module in list order, contributing services to the
 * [GameContextBuilder]. [simulation] runs next, declaring systems into a [SimRegistry]. The
 * split exists because a system's constructor may need a service, and a service must never
 * need a system: contributing them in one pass would make the dependency direction a matter
 * of who registered first.
 *
 * ## Presentation
 *
 * There is deliberately no `presentation(...)` hook here. Render registration names
 * `RenderPhase`, `RenderSystem` and `RenderRegistry`, all of which live in `udea-render`, and
 * `udea-core` must not have GL anywhere on its compile classpath (spec 3.5) — a hook naming
 * those types would drag the renderer into the kernel and invert the module arrows. A module
 * that also draws implements `udea-render`'s presentation-module interface **in addition to**
 * this one; the game definition holds it once and each side asks it for the half it owns.
 */
public interface UdeaModule {

    /** Short name, for the manifest, logs and agent introspection. */
    public val name: String
        get() = this::class.java.simpleName

    /**
     * Contributes services to the context every system will be built against.
     *
     * Runs before [simulation] for **every** module, so a later module may replace a service
     * an earlier one supplied by assigning the builder's field — which is how a game swaps
     * `NoOpPhysicsWorld` for a real Box2D world without the kernel knowing Box2D exists.
     */
    public fun context(builder: GameContextBuilder) {}

    /** Declares this module's simulation systems, their phases and their ordering. */
    public fun simulation(registry: SimRegistry) {}
}
