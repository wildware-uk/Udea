package dev.wildware.moba.entry

import java.lang.management.ManagementFactory
import java.time.Instant

/**
 * When each phase of this process's startup happened, so a blown budget names its own cause.
 *
 * ## Why a process-wide object
 *
 * It is a mutable global, which is normally the wrong shape and is argued for here rather than
 * assumed. The thing being measured is *the process*, from before `main` to the first swapped
 * buffer, across three threads (the JVM's own start, the main thread's composition, the render
 * thread's first frame) and through call sites that are five frames deep in engine code and
 * cannot be handed a recorder without every one of them growing a parameter for it. A trace of a
 * process is one of the few things whose scope genuinely is the process.
 *
 * It is confined to `moba`'s entry package and read by exactly one entry point ([MobaBench]).
 * Nothing in the simulation reads it, so it cannot affect a tick, and `PureSimulationTest`'s
 * property is untouched.
 *
 * ## The zero point is the JVM's, not `main`'s
 *
 * [jvmStart] is `ProcessHandle.current().info().startInstant()` — the OS's idea of when the
 * process began. Issue #94 is explicit that class loading and JVM startup are *inside* the
 * budget, because they are inside the player's wait. Timing from `main` would exclude the JVM's
 * own boot and the whole of the classpath scan, which for a runtime script host is precisely
 * where the cost used to hide.
 *
 * `System.nanoTime()` measures every *interval* after that, because `currentTimeMillis` is wall
 * time and steps when the clock is adjusted. The two absolute readings that have to be on the
 * same clock as [jvmStart] - [enterMain] and [firstFramePresented] - use `currentTimeMillis`,
 * because `nanoTime`'s origin is arbitrary and cannot be compared with an `Instant` at all.
 */
public object StartupTrace {

    /** OS process start, or JVM RuntimeMXBean start when `ProcessHandle` cannot say. */
    public val jvmStart: Instant = ProcessHandle.current().info().startInstant()
        .orElseGet { Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().startTime) }

    /** Millis from [jvmStart] to [enterMain]: JVM boot plus class loading of the entry point. */
    public var jvmStartToMainMillis: Double = 0.0
        private set

    /** Millis spent opening the asset pack and turning it into drawable resources. */
    public var assetMillis: Double = 0.0
        private set

    /**
     * Millis spent creating the GL context and building the render pipeline, **excluding** the
     * asset work nested inside it.
     *
     * Its own phase because spec 3.5 keeps a real LWJGL3 context in `Offscreen`, so driver init
     * is inside the budget on purpose - and a CI machine with a slow software GL stack should
     * show up here by name rather than inflating "world construction" and looking like a
     * regression in code that did not change. The subtraction is what makes the four phases sum
     * to something a reader can trust: the renderer's sheet load happens *during* pipeline
     * construction, so without it that time would be counted twice.
     */
    public var glMillis: Double = 0.0
        private set

    /** Millis spent building the world: definition, scene, host, seed. */
    public var worldMillis: Double = 0.0
        private set

    /** Millis from [jvmStart] to the moment the first frame had been presented. */
    public var firstFrameMillis: Double = 0.0
        private set

    /** True once [firstFramePresented] has run. */
    public var complete: Boolean = false
        private set

    /** Called as the first statement of a benchmarked `main`. */
    public fun enterMain() {
        jvmStartToMainMillis = (System.currentTimeMillis() - jvmStart.toEpochMilli()).toDouble()
    }

    /**
     * Times [block] as asset work and returns its result.
     *
     * Additive, because a process legitimately opens more than one thing: today `moba` loads one
     * champion sheet, and when the `.udeapak` reader lands (issue #89) the pack open and the
     * graph deserialisation both land in this phase, which is what the 150ms sub-budget is
     * denominated in.
     */
    public fun <T> asset(block: () -> T): T {
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            assetMillis += (System.nanoTime() - started) / NANOS_PER_MILLI
        }
    }

    /**
     * Times [block] as GL context and pipeline construction, net of any [asset] work inside it.
     *
     * The netting is why this cannot be written as two independent stopwatches: the renderer
     * loads its sheets from inside `RenderRegistry.build`, which runs on the render thread inside
     * the backend's `start`, so the two intervals genuinely nest.
     */
    public fun <T> gl(block: () -> T): T = nested(block) { glMillis += it }

    /**
     * Times [block] as world construction, net of any [asset] work inside it.
     *
     * Netted for the same reason [gl] is, and discovered the same way: the champion sheet is
     * loaded from `RenderRegistry.build`, which `GameHost` drives while constructing its
     * presentation - so a planted 400ms delay in the asset load showed up **twice**, once under
     * `assetMillis` and once under `worldMillis`, and the four phases summed to 400ms more than
     * the boot actually took. A breakdown that over-counts is a breakdown that sends the next
     * reader after the wrong phase.
     */
    public fun <T> world(block: () -> T): T = nested(block) { worldMillis += it }

    /** Times [block] and reports the elapsed millis *excluding* nested [asset] work. */
    private inline fun <T> nested(block: () -> T, record: (Double) -> Unit): T {
        val started = System.nanoTime()
        val assetsBefore = assetMillis
        try {
            return block()
        } finally {
            val elapsed = (System.nanoTime() - started) / NANOS_PER_MILLI
            record((elapsed - (assetMillis - assetsBefore)).coerceAtLeast(0.0))
        }
    }

    /**
     * Records that frame 1 has been swapped to the surface.
     *
     * "Presented", not "drawn": the render callback returns *before* the buffer swap, so a
     * timestamp taken at the end of the first callback would report a frame the player has not
     * seen. [MobaBench] calls this at the top of the **second** callback, which is the first
     * instant at which frame 1 is provably on the surface. That is a ~16ms pessimism at 60Hz and
     * it is the honest direction to be wrong in.
     */
    public fun firstFramePresented() {
        if (complete) return
        firstFrameMillis = (System.currentTimeMillis() - jvmStart.toEpochMilli()).toDouble()
        complete = true
    }

    /**
     * The phase breakdown as the JSON object issue #94 requires, with fixed key order.
     *
     * Hand-rolled rather than serialized: `moba` has no JSON dependency, and this document has
     * four numbers in it. Keys are in phase order so a human reading the report reads a timeline.
     */
    public fun toJson(): String = buildString {
        append("{\n")
        append("""  "jvmStartToMainMillis": """).append(round(jvmStartToMainMillis)).append(",\n")
        append("""  "assetMillis": """).append(round(assetMillis)).append(",\n")
        append("""  "glMillis": """).append(round(glMillis)).append(",\n")
        append("""  "worldMillis": """).append(round(worldMillis)).append(",\n")
        append("""  "firstFrameMillis": """).append(round(firstFrameMillis)).append("\n")
        append("}\n")
    }

    /** Millis, to one decimal. `String.format` would follow the default locale and emit `1,5`. */
    private fun round(value: Double): String {
        val tenths = Math.round(value * 10.0)
        return "${tenths / 10}.${tenths % 10}"
    }

    private const val NANOS_PER_MILLI = 1_000_000.0
}
