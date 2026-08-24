package dev.wildware.udea.build.determinism

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One fixture per rule id, each compiled to real bytecode and each asserted down to the line.
 *
 * The line assertions are not decoration. A bytecode gate whose span is wrong sends a reader -
 * or an agent applying a fix - to the wrong place, and the failure mode is silent: the rule
 * still fires, the message still names the class, and the `:0` or the off-by-eight goes
 * unnoticed until somebody tries to use it. So every fixture pins the exact line its violation
 * is written on.
 */
class DeterminismScannerTest {

    @TempDir
    lateinit var tempDir: File

    private fun scan(sources: Map<String, String>): ScanResult {
        val compiled = FixtureCompiler.compile(tempDir, sources + FixtureCompiler.GDX_STUBS)
        return DeterminismScan.run(
            inputs = listOf(FixtureCompiler.scopeInput(compiled, packagePrefixes = listOf("sim"))),
            allowlist = Allowlist.parse(""),
            repoRoot = compiled.sourceDir,
        )
    }

    private fun ScanResult.only(): Finding {
        assertEquals(1, findings.size, "expected exactly one finding, got:\n" + findings.joinToString("\n") { it.render() })
        return findings.single()
    }

    @Test
    fun `DET001 fires on a wall-clock read, naming the class, method and line`() {
        val result = scan(
            mapOf(
                "sim/Clocks.java" to """
                    package sim;
                    public class Clocks {
                        public long elapsed() {
                            return System.nanoTime();
                        }
                    }
                """.trimIndent(),
            ),
        )
        val finding = result.only()
        assertEquals("DET001", finding.ruleId)
        assertEquals("sim.Clocks", finding.className)
        assertEquals("elapsed", finding.method)
        assertEquals("java.lang.System.nanoTime", finding.target)
        assertEquals("sim/Clocks.java:4:1", finding.span)
        assertTrue(finding.didYouMean.contains("SimClock.tick"), finding.didYouMean)
    }

    @Test
    fun `DET002 fires on Math dot random and on the Kotlin default Random object`() {
        val result = scan(
            mapOf(
                "sim/Randoms.java" to """
                    package sim;
                    public class Randoms {
                        public double roll() {
                            return Math.random();
                        }
                        public float kotlinRoll() {
                            return kotlin.random.Random.Default.nextFloat();
                        }
                        public float gdxRoll() {
                            return com.badlogic.gdx.math.MathUtils.random();
                        }
                    }
                """.trimIndent(),
            ),
        )
        val det002 = result.findings.filter { it.ruleId == "DET002" }
        assertEquals(setOf("roll", "kotlinRoll", "gdxRoll"), det002.map { it.method }.toSet())
        assertEquals(
            "sim/Randoms.java:4:1",
            det002.single { it.method == "roll" }.span,
        )
        assertEquals(
            "com.badlogic.gdx.math.MathUtils.random",
            det002.single { it.method == "gdxRoll" }.target,
        )
        // Kotlin's `Random.Default` reaches the scan as the companion field access on
        // `kotlin.random.Random` and as the call on `kotlin.random.Random$Default`. Either one
        // firing is the rule working; the assertion is that the fixture's method is named.
        assertTrue(det002.any { it.method == "kotlinRoll" })
    }

    @Test
    fun `DET002 does not fire on a seeded java util Random`() {
        val result = scan(
            mapOf(
                "sim/Seeded.java" to """
                    package sim;
                    public class Seeded {
                        private final java.util.Random rng = new java.util.Random(1234L);
                        public int roll() { return rng.nextInt(6); }
                    }
                """.trimIndent(),
            ),
        )
        assertEquals(
            emptyList(),
            result.findings.map { it.render() },
            "a seeded Random is how a deterministic stream is BUILT; flagging it would " +
                "make the sanctioned replacement itself a violation",
        )
    }

    @Test
    fun `DET003 fires on calendar time`() {
        val result = scan(
            mapOf(
                "sim/Calendars.java" to """
                    package sim;
                    public class Calendars {
                        public java.time.Instant stamp() {
                            return java.time.Instant.now();
                        }
                    }
                """.trimIndent(),
            ),
        )
        val finding = result.only()
        assertEquals("DET003", finding.ruleId)
        assertEquals("java.time.Instant.now", finding.target)
        assertEquals("sim/Calendars.java:4:1", finding.span)
    }

    /**
     * The shape Kotlin actually emits, reproduced in Java: the concrete type appears only at the
     * `NEW`, and the walk is an interface call on `java.util.Map`. Nothing correlates the two
     * except that they are in the same class - which is exactly what `DET004` does.
     */
    @Test
    fun `DET004 fires when a class builds a hash map and walks a map`() {
        val result = scan(
            mapOf(
                "sim/Hashes.java" to """
                    package sim;
                    import java.util.HashMap;
                    import java.util.Map;
                    public class Hashes {
                        private final Map<String, Integer> byName = new HashMap<>();
                        public int sum() {
                            int total = 0;
                            for (Map.Entry<String, Integer> e : byName.entrySet()) {
                                total += e.getValue();
                            }
                            return total;
                        }
                    }
                """.trimIndent(),
            ),
        )
        val det004 = result.findings.filter { it.ruleId == "DET004" }
        assertEquals(listOf("java.util.HashMap"), det004.map { it.target })
        assertEquals(
            "sim/Hashes.java:5:1",
            det004.single().span,
            "the finding is reported at the CONSTRUCTION, which is where the fix goes",
        )
    }

    @Test
    fun `DET004 does not fire on a hash map that is only ever looked up`() {
        val result = scan(
            mapOf(
                "sim/Index.java" to """
                    package sim;
                    import java.util.HashMap;
                    public class Index {
                        private final HashMap<String, Integer> byName = new HashMap<>();
                        private final String[] sorted = new String[0];
                        public int indexOf(String name) { return byName.get(name); }
                        public String at(int i) { return sorted[i]; }
                    }
                """.trimIndent(),
            ),
        )
        assertEquals(
            emptyList(),
            result.findings.map { it.render() },
            "a lookup index whose ordering comes from a sorted array beside it is exactly what " +
                "SimRegistry, AbilityTable, GameplayTagTable and three others do; the first " +
                "version of this rule was wrong on all seven",
        )
    }

    @Test
    fun `DET004 does not fire on a class that walks only a list`() {
        val result = scan(
            mapOf(
                "sim/Walker.java" to """
                    package sim;
                    import java.util.HashMap;
                    import java.util.List;
                    public class Walker {
                        private final HashMap<String, Integer> byName = new HashMap<>();
                        public int sum(List<Integer> values) {
                            int total = 0;
                            for (Integer v : values) total += v;
                            return total + byName.size();
                        }
                    }
                """.trimIndent(),
            ),
        )
        assertEquals(
            emptyList(),
            result.findings.map { it.render() },
            "a list walk says nothing about hash order; counting it would put every class that " +
                "iterates anything back into the false-positive set",
        )
    }

    @Test
    fun `DET004 does not fire on a LinkedHashMap that is walked`() {
        val result = scan(
            mapOf(
                "sim/Ordered.java" to """
                    package sim;
                    import java.util.LinkedHashMap;
                    import java.util.Map;
                    public class Ordered {
                        private final Map<String, Integer> byName = new LinkedHashMap<>();
                        public int sum() {
                            int total = 0;
                            for (Integer v : byName.values()) total += v;
                            return total;
                        }
                    }
                """.trimIndent(),
            ),
        )
        assertEquals(emptyList(), result.findings.map { it.render() })
    }

    @Test
    fun `DET005 fires on Box2D only inside a predicted package`() {
        val compiled = FixtureCompiler.compile(
            tempDir,
            mapOf(
                "dev/wildware/udea/net/prediction/Predicted.java" to """
                    package dev.wildware.udea.net.prediction;
                    public class Predicted {
                        public void step(com.badlogic.gdx.physics.box2d.World world) {
                            world.step(1F / 60F, 2, 2);
                        }
                    }
                """.trimIndent(),
                "dev/wildware/udea/net/transport/Authoritative.java" to """
                    package dev.wildware.udea.net.transport;
                    public class Authoritative {
                        public void step(com.badlogic.gdx.physics.box2d.World world) {
                            world.step(1F / 60F, 2, 2);
                        }
                    }
                """.trimIndent(),
            ) + FixtureCompiler.GDX_STUBS,
        )
        val result = DeterminismScan.run(
            inputs = listOf(
                FixtureCompiler.scopeInput(compiled, packagePrefixes = listOf("dev.wildware.udea.net")),
            ),
            allowlist = Allowlist.parse(""),
            repoRoot = compiled.sourceDir,
        )
        val finding = result.only()
        assertEquals("DET005", finding.ruleId)
        assertEquals("dev.wildware.udea.net.prediction.Predicted", finding.className)
        assertEquals("dev/wildware/udea/net/prediction/Predicted.java:4:1", finding.span)
        assertTrue(
            result.findings.none { it.className.contains("Authoritative") },
            "the server owns the solver; DET005 is about PREDICTED code re-running it",
        )
    }

    @Test
    fun `DET006 fires on a frame-delta read`() {
        val result = scan(
            mapOf(
                "sim/Devices.java" to """
                    package sim;
                    import com.badlogic.gdx.Gdx;
                    public class Devices {
                        public float delta() {
                            return Gdx.graphics.getDeltaTime();
                        }
                    }
                """.trimIndent(),
            ),
        )
        val det006 = result.findings.filter { it.ruleId == "DET006" }
        assertEquals(
            setOf("com.badlogic.gdx.Gdx.graphics", "com.badlogic.gdx.Graphics.getDeltaTime"),
            det006.map { it.target }.toSet(),
        )
        det006.forEach { assertEquals("sim/Devices.java:5:1", it.span) }
    }

    @Test
    fun `a clean fixture reports nothing`() {
        val result = scan(
            mapOf(
                "sim/Clean.java" to """
                    package sim;
                    import java.util.LinkedHashMap;
                    public class Clean {
                        private final LinkedHashMap<String, Integer> byName = new LinkedHashMap<>();
                        private long tick;
                        public void onTick() {
                            tick++;
                            byName.put("t", (int) tick);
                        }
                        public int at(String name) { return byName.getOrDefault(name, 0); }
                    }
                """.trimIndent(),
            ),
        )
        assertEquals(emptyList(), result.findings.map { it.render() })
        assertTrue(result.problems.isEmpty())
        assertTrue(!result.failed)
    }

    @Test
    fun `a class outside the declared package prefixes is not simulation`() {
        val result = scan(
            mapOf(
                "presentation/Hud.java" to """
                    package presentation;
                    public class Hud {
                        public long stamp() { return System.currentTimeMillis(); }
                    }
                """.trimIndent(),
            ),
        )
        assertEquals(
            emptyList(),
            result.findings.map { it.render() },
            "presentation is outside world.update by construction (spec 3.3); a wall-clock " +
                "read there is correct, and a gate that failed on it would be one people switch off",
        )
    }

    @Test
    fun `the report says on every run that it is not the determinism gate`() {
        val result = scan(
            mapOf("sim/Clean.java" to "package sim;\npublic class Clean { }\n"),
        )
        val report = DeterminismScan.report(result)
        assertTrue(report.contains("cheap first filter, not the determinism gate"), report)
        assertTrue(report.contains("WorldHasher"), report)
        assertTrue(report.contains("replay-equality"), report)
    }

    @Test
    fun `findings are capped and the report says how many were hidden`() {
        val methods = (1..40).joinToString("\n") {
            "    public long m$it() { return System.nanoTime(); }"
        }
        val result = scan(mapOf("sim/Many.java" to "package sim;\npublic class Many {\n$methods\n}\n"))
        assertEquals(DeterminismRules.MAX_FINDINGS, result.findings.size)
        assertEquals(40, result.totalFindings)
        assertTrue(DeterminismScan.report(result).contains("showing the first 25 of 40"))
    }

    @Test
    fun `an allowlist entry suppresses its finding and is recorded as used`() {
        val compiled = FixtureCompiler.compile(
            tempDir,
            mapOf(
                "sim/Clocks.java" to """
                    package sim;
                    public class Clocks {
                        public long elapsed() { return System.nanoTime(); }
                    }
                """.trimIndent(),
            ),
        )
        val result = DeterminismScan.run(
            inputs = listOf(FixtureCompiler.scopeInput(compiled, packagePrefixes = listOf("sim"))),
            allowlist = Allowlist.parse(
                "DET001  java.lang.System#nanoTime  # fixture: proves suppression works\n",
            ),
            repoRoot = compiled.sourceDir,
        )
        assertEquals(emptyList(), result.findings.map { it.render() })
        assertEquals(setOf("DET001 java.lang.System#nanoTime"), result.usedEntries)
        assertTrue(result.problems.isEmpty())
    }

    @Test
    fun `a scope that contributed no classes is a broken gate, not a clean module`() {
        val compiled = FixtureCompiler.Compiled(
            tempDir.resolve("nowhere/src"),
            tempDir.resolve("nowhere/classes"),
        )
        val error = kotlin.runCatching {
            DeterminismScan.run(
                inputs = listOf(FixtureCompiler.scopeInput(compiled)),
                allowlist = Allowlist.parse(""),
                repoRoot = tempDir,
            )
        }.exceptionOrNull()
        assertTrue(
            error?.message?.contains("a scan of nothing passes forever") == true,
            "expected the empty-scan guard, got $error",
        )
    }
}
