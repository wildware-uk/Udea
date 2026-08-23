package dev.wildware.udea.agent

/**
 * Wall time, for measuring the agent surface and nothing else.
 *
 * ## Why a wall clock exists at all in a module that runs on the simulation thread
 *
 * The engineering standards forbid `System.nanoTime` inside `Simulation.step()`, and they are
 * right to: a simulation whose *output* depends on how fast it ran is not reproducible. Two
 * things this module owes the agent are nevertheless measurements of elapsed time - a tool
 * that overran its `budgetMs`, and the digest build reporting its own cost so the 0.3ms budget
 * stays honest (spec 6, Phase 1 exit).
 *
 * The reconciliation is that a duration measured here reaches an event string and a timings
 * table and **never a component, a `FieldStore` or an RNG draw**. Nothing a system reads
 * depends on it, so no snapshot, hash or replay changes because a tool took a millisecond
 * longer on one machine.
 *
 * Making it an injected `fun interface` rather than a bare call is what keeps that checkable:
 * there is one named boundary for a determinism scan to look at instead of scattered
 * `nanoTime` calls, and tests drive it with a manual implementation rather than a sleep - the
 * standards forbid those too, and a budget test that slept would be measuring the scheduler.
 */
public fun interface AgentClock {

    /** A monotonic nanosecond reading. Only differences are meaningful. */
    public fun nowNanos(): Long

    public companion object {
        /**
         * `System.nanoTime`.
         *
         * The single call site in the module, so a reader looking for wall clock in
         * simulation-adjacent code finds this one line and its justification above it.
         */
        public val System: AgentClock = AgentClock { java.lang.System.nanoTime() }
    }
}
