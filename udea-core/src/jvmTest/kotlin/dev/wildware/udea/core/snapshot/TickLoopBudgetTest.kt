package dev.wildware.udea.core.snapshot

import dev.wildware.udea.core.EngineConfig
import dev.wildware.udea.core.ModuleFiles
import dev.wildware.udea.core.Tick
import dev.wildware.udea.core.alloc.AllocationProbe
import dev.wildware.udea.diagnostics.bench.LatencyBudget
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Phase 0 demo, as an executable gate.
 *
 * Spec 6 states it as a hard number — 200 entities, 600 fixed ticks in under 50ms, snapshot,
 * restore, re-run, identical hash stream, zero steady-state allocation — and nothing else in
 * the plan measures the *assembled* loop. The snapshot epic gates capture alone; the agent
 * epic gates the digest alone; each subsystem has its own allocation test. Every one of them
 * can pass while `Simulation.step()` misses the number Phase 0 actually exits on.
 *
 * Every run here is headless: no GL, no window, no `LibGDX` application, no global. That is
 * asserted rather than assumed, at the bottom of this file.
 */
class TickLoopBudgetTest {

    @Test
    fun `six hundred ticks at two hundred entities run inside the fifty millisecond budget`() {
        LatencyBudget.measuredBy(":udea-core:udeaBenchTickLoop")

        // Warm first, then measure, then take the median of at least five runs: a cold 600-tick
        // run is dominated by class loading and would make the gate flaky rather than strict.
        repeat(WARMUP_RUNS) { runOnce() }

        val samples = LongArray(MEASURED_RUNS) { runOnce() }
        samples.sort()
        val median = samples[samples.size / 2]
        val p95 = samples[(samples.size * 95) / 100]

        writeReport(median, p95, samples)
        println(
            "udeaBenchTickLoop: ${SnapshotBudgets.LOOP_TICKS} ticks at " +
                "${SnapshotBudgets.LOOP_ENTITIES} entities, median ${median / 1_000_000.0}ms, " +
                "p95 ${p95 / 1_000_000.0}ms, budget ${SnapshotBudgets.LOOP_NANOS / 1_000_000.0}ms",
        )

        assertTrue(
            median <= SnapshotBudgets.LOOP_NANOS,
            "the assembled loop took a median of ${median / 1_000_000.0}ms against a " +
                "${SnapshotBudgets.LOOP_NANOS / 1_000_000.0}ms budget (p95 ${p95 / 1_000_000.0}ms). " +
                "This is the Phase 0 exit number; do not widen it. " +
                LatencyBudget.contentionNote(":udea-core:udeaBenchTickLoop"),
        )
    }

    @Test
    fun `the final three hundred ticks allocate zero bytes, capture included`() {
        assertTrue(AllocationProbe.isSupported, "this JVM has no thread allocation counters")

        val sim = SnapshotWorld(driveCapture = true)
        sim.spawn(SnapshotBudgets.LOOP_ENTITIES)

        // Warm the *ring*, not only the JIT. A ring whose sparse window is not yet full is
        // still building slots, and a slot is a large allocation — so measuring before then
        // would measure the ring filling up rather than the loop's steady state, and a run
        // that stopped at 600 ticks would never reach the state a real session spends its
        // life in.
        repeat(RING_WARMUP_TICKS) { sim.step() }
        assertEquals(
            (RING_WARMUP_TICKS / CAPTURE_INTERVAL_TICKS).toLong(),
            sim.travel.capturedTicks,
            "the loop must have captured on its own cadence; a zero here would mean this gate " +
                "is measuring a loop with no capture in it at all",
        )
        val slotsBefore = sim.ring.slotCount
        val heldBefore = sim.ring.size
        assertTrue(heldBefore > 0, "the ring should be holding the warm-up's snapshots")

        val allocated = AllocationProbe.bytesAllocated {
            repeat(STEADY_STATE_TICKS) { sim.step() }
        }

        println(
            "udeaBenchTickLoop: $STEADY_STATE_TICKS steady-state ticks allocated $allocated " +
                "bytes; ring held $heldBefore slots over ${sim.ring.totalBytes} bytes",
        )
        assertEquals(
            slotsBefore,
            sim.ring.slotCount,
            "the ring built ${sim.ring.slotCount - slotsBefore} new slots during the measured " +
                "window, so it was not at its steady population and this gate measured a ring " +
                "filling up rather than a loop running",
        )
        assertEquals(
            SnapshotBudgets.LOOP_ALLOCATED_BYTES,
            allocated,
            "the assembled loop allocated $allocated bytes over $STEADY_STATE_TICKS ticks — " +
                "barrier drain, system iteration, capture and the ring commit together",
        )
    }

    @Test
    fun `capturing at tick three hundred, restoring and re-running reproduces the hash stream`() {
        val sim = SnapshotWorld()
        sim.spawn(SnapshotBudgets.LOOP_ENTITIES)
        val scratch = sim.service.newSnapshot()

        repeat(HALFWAY) { sim.step() }
        val keyframe = sim.service.capture()
        assertEquals(Tick(HALFWAY.toLong()), keyframe.tick)

        val baseline = LongArray(SnapshotBudgets.LOOP_TICKS - HALFWAY) {
            sim.step()
            sim.hashNow(scratch)
        }

        sim.service.applyNow(keyframe)

        val replay = LongArray(SnapshotBudgets.LOOP_TICKS - HALFWAY) {
            sim.step()
            sim.hashNow(scratch)
        }

        val diverged = DivergenceReport.firstDivergingTick(baseline, replay, Tick(HALFWAY + 1L))
        assertNull(
            diverged,
            "the re-run diverged at $diverged; the first differing tick is where to look",
        )
    }

    @Test
    fun `nothing in the snapshot package can reach a GL type`() {
        // `udea-core` has no graphics dependency at all, so this is a real check rather than a
        // grep: if a GL type were ever reachable the class would simply not be on the classpath.
        for (name in GL_TYPES) {
            val loadable = runCatching { Class.forName(name, false, javaClass.classLoader) }
            assertTrue(
                loadable.isFailure,
                "$name is on udea-core's test classpath; the kernel must stay headless (spec 4)",
            )
        }

        val offenders = ModuleFiles.mainSources
            .filter { it.invariantPath().contains("/core/snapshot/") }
            .filter { it.readText().contains("com.badlogic.gdx") }
            .map { ModuleFiles.relativePath(it) }
        assertEquals(emptyList(), offenders, "a snapshot source named a LibGDX type")
    }

    @Test
    fun `nothing in the snapshot package holds mutable static state`() {
        val offenders = snapshotClasses().flatMap { className ->
            Class.forName(className, false, javaClass.classLoader).declaredFields
                .filterNot { it.isSynthetic }
                .filter { Modifier.isStatic(it.modifiers) && !Modifier.isFinal(it.modifiers) }
                .map { "$className.${it.name}" }
        }.sorted()

        assertEquals(
            emptyList(),
            offenders,
            "a non-final static field is a global however it is spelled, and a global is what " +
                "makes two simulations in one JVM impossible",
        )
    }

    @Test
    fun `kotlin-reflect is not on udea-core's runtime classpath`() {
        // Spec 3.1: the MCP surface needs no reflection and survives R8, precisely because
        // capture and field access go through generated code. Reflection reaching the kernel's
        // runtime would quietly reintroduce `common/reflection.kt`.
        val runtimeJars = System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .filter { it.contains("kotlin-reflect") }
            .filterNot { it.contains("test") }

        assertTrue(
            runtimeJars.isEmpty() || isTestOnlyDependency(),
            "kotlin-reflect reached a non-test classpath: $runtimeJars",
        )
    }

    /**
     * Builds a fresh world and runs the whole scenario, returning nanoseconds elapsed.
     *
     * `driveCapture = true`, so what is timed is `WorldSimulation.step` with the engine's own
     * capture inside it — barrier drain, systems, clock, `TimeTravel.captureIfDue`, and through
     * it `acquire -> captureInto -> commit` with the ring's retention, eviction, pool and byte
     * budget. This benchmark used to stand a copy of that cadence up beside the loop because no
     * production code drove one; gating the loop on a path production does not take is what
     * makes a budget decorative.
     */
    private fun runOnce(): Long {
        val sim = SnapshotWorld(driveCapture = true)
        sim.spawn(SnapshotBudgets.LOOP_ENTITIES)

        val before = System.nanoTime()
        repeat(SnapshotBudgets.LOOP_TICKS) { sim.step() }
        return System.nanoTime() - before
    }

    /** `build/reports/udea/tick-loop.json`, published by CI as the gate's artifact. */
    private fun writeReport(median: Long, p95: Long, samples: LongArray) {
        val report = ModuleFiles.moduleDir.resolve("build/reports/udea").also { it.mkdirs() }
            .resolve("tick-loop.json")
        report.writeText(
            """
            {
              "entities": ${SnapshotBudgets.LOOP_ENTITIES},
              "ticks": ${SnapshotBudgets.LOOP_TICKS},
              "budgetNanos": ${SnapshotBudgets.LOOP_NANOS},
              "medianNanos": $median,
              "p95Nanos": $p95,
              "samplesNanos": [${samples.joinToString()}],
              "allocatedBytesBudget": ${SnapshotBudgets.LOOP_ALLOCATED_BYTES}
            }
            """.trimIndent(),
        )
    }

    private fun snapshotClasses(): List<String> {
        val source = File(WorldHasher::class.java.protectionDomain.codeSource.location.toURI())
        val entries = when {
            source.isDirectory -> source.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .map { it.relativeTo(source).invariantSeparatorsPath }
                .toList()

            source.isFile -> java.util.jar.JarFile(source).use { jar ->
                jar.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.toList()
            }

            else -> error("expected a classes directory or jar for udea-core, got $source")
        }
        val classes = entries
            .map { it.removeSuffix(".class").replace('/', '.') }
            .filter { it.startsWith("dev.wildware.udea.core.snapshot.") }
        assertTrue(classes.size >= 8, "expected the snapshot package's classes, found $classes")
        return classes.sorted()
    }

    /**
     * `udea-core` declares `kotlin-reflect` as `testImplementation` only, for
     * `ReplicatorApiShapeTest`. Anything else is a regression.
     */
    private fun isTestOnlyDependency(): Boolean =
        ModuleFiles.moduleDir.resolve("build.gradle.kts").readText()
            .lines()
            .filter { it.contains("kotlin(\"reflect\")") }
            .all { it.trimStart().startsWith("testImplementation") }

    private fun File.invariantPath(): String = invariantSeparatorsPath

    private companion object {
        /**
         * The cadence the engine actually runs at: `EngineConfig.snapshotIntervalTicks`.
         *
         * Read from the production default rather than restated, so that changing the engine's
         * cadence moves this gate's expectation with it instead of leaving the gate asserting
         * a number nothing uses.
         */
        const val CAPTURE_INTERVAL_TICKS: Int = EngineConfig.DEFAULT_SNAPSHOT_INTERVAL_TICKS

        const val WARMUP_RUNS: Int = 3
        const val MEASURED_RUNS: Int = 5
        const val STEADY_STATE_TICKS: Int = 300

        /**
         * Enough ticks for the ring's sparse window to be full and its slot pool to be at its
         * high water: the sixty-second window plus the dense window plus slack.
         */
        const val RING_WARMUP_TICKS: Int = RingConfig.DEFAULT_SPARSE_WINDOW_TICKS +
            RingConfig.DEFAULT_DENSE_TICKS + 300
        const val HALFWAY: Int = SnapshotBudgets.LOOP_TICKS / 2

        val GL_TYPES: List<String> = listOf(
            "com.badlogic.gdx.Gdx",
            "com.badlogic.gdx.graphics.GL20",
            "com.badlogic.gdx.graphics.Texture",
        )
    }
}
