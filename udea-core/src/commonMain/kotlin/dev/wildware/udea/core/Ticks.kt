package dev.wildware.udea.core

import kotlin.jvm.JvmInline

/**
 * A length of simulation time: *how many* ticks, where a [Tick] is *which* tick.
 *
 * The two are different types for the reason [Tick.ticksSince] gives for not being an operator:
 * a duration spelled as a [Tick] reads as a moment, and `start + fade` where both are ticks is a
 * sum that means nothing. An animation clip's length and a crossfade's length are the first
 * durations a game passes to engine API rather than keeping to itself, so they get the type.
 *
 * Never seconds. The seconds a renderer needs are derived at the edge, in `udea-render`.
 */
@JvmInline
public value class Ticks(public val count: Long) : Comparable<Ticks> {

    override fun compareTo(other: Ticks): Int = count.compareTo(other.count)

    override fun toString(): String = "${count}ticks"
}

/** `6.ticks`: a [Ticks] of this many ticks. */
public val Int.ticks: Ticks get() = Ticks(toLong())
