package dev.wildware.udea.net.transport

import dev.wildware.udea.core.Tick

/**
 * The only clock anything in this package reads.
 *
 * Latency, jitter and the bandwidth cap are all expressed in [Tick]s and resolved against
 * this, so an entire session — server, four clients, 150ms of simulated latency, 5% loss —
 * runs as fast as the CPU can execute it and produces the same bytes every time.
 *
 * There is deliberately no wall-clock implementation of this type. `NoWallClockInTransportTest`
 * fails the build if `System.nanoTime`, `System.currentTimeMillis` or `Math.random` appears
 * anywhere in this package, because a single one of them turns every downstream test from
 * deterministic into "usually passes".
 */
public class ManualClock(start: Tick = Tick.ZERO) {

    /** The current tick. Only [advance] moves it. */
    public var tick: Tick = start
        private set

    /** Advances by one tick and returns the new value. */
    public fun advance(): Tick {
        tick = tick.inc()
        return tick
    }
}
