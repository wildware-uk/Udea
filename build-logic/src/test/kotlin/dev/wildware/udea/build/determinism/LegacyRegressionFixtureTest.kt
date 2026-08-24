package dev.wildware.udea.build.determinism

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three defects the old tree actually shipped, re-run against the scanner.
 *
 * Issue #150 names them by file and line, and they are the honest test of whether this gate was
 * worth building: a rule table that cannot catch the code that motivated it is a rule table
 * written to pass its own tests.
 *
 * - `common/.../SoundSystem.kt:9,28,31` - `import kotlin.random.Random`, `Random.nextFloat()`
 *   and `List.random()` inside a Fleks `IntervalSystem`. Expected `DET002`.
 * - `common/.../Box2DSystem.kt:20-23` - the solver stepped from an `IteratingSystem`.
 *   Expected `DET005`.
 * - `common/.../UIScreen.kt:16` - `Gdx.graphics.deltaTime`. Expected `DET006`.
 *
 * ## These are transliterations, and here is exactly what that costs
 *
 * `build-logic` has no Kotlin compiler it can invoke as a library and no LibGDX or Fleks on its
 * classpath, so the fixtures are Java with the same *references*: the rules match on owner and
 * member names, and a `javac` fixture naming `com.badlogic.gdx.physics.box2d.World.step`
 * produces byte-for-byte the reference the Kotlin original produced. Two differences are real
 * and are stated rather than glossed:
 *
 * 1. Kotlin's `Random.nextFloat()` compiles to `kotlin/random/Random$Default.nextFloat`; the
 *    Java fixture writes that receiver out longhand. Same owner, same member.
 * 2. `Box2DSystem` was not a *predicted* system - prediction did not exist in the old tree.
 *    `DET005` is scoped to predicted code, so the fixture is placed in a predicted package.
 *    What it proves is the rule, not the old file's location.
 */
class LegacyRegressionFixtureTest {

    @TempDir
    lateinit var tempDir: File

    /** `SoundSystem.kt:28,31` - unseeded randomness inside a Fleks system. */
    @Test
    fun `the old SoundSystem reports DET002`() {
        val findings = scan(
            "dev/wildware/udea/ecs/system/SoundSystem.java" to """
                package dev.wildware.udea.ecs.system;
                import java.util.List;
                public class SoundSystem {
                    public float audioFalloff = 10F;
                    public float playSoundAtPosition(float pitch, float pitchVariance, List<String> assets) {
                        float rolled = pitch + (kotlin.random.Random.Default.nextFloat() * pitchVariance);
                        return rolled;
                    }
                }
            """.trimIndent(),
        )
        assertEquals(setOf("DET002"), findings.map { it.ruleId }.toSet(), findings.render())
        assertTrue(findings.all { it.className == "dev.wildware.udea.ecs.system.SoundSystem" })
        assertTrue(findings.all { it.method == "playSoundAtPosition" })
        assertTrue(
            findings.any { it.span.endsWith("SoundSystem.java:6:1") },
            "the span must name the line the draw is on: ${findings.map { it.span }}",
        )
        assertTrue(findings.first().didYouMean.contains("RngService.stream"))
    }

    /** `Box2DSystem.kt:20-23` - the solver, stepped from a system. */
    @Test
    fun `the old Box2DSystem reports DET005 when it sits in predicted code`() {
        val findings = scan(
            "dev/wildware/udea/net/prediction/Box2DSystem.java" to """
                package dev.wildware.udea.net.prediction;
                import com.badlogic.gdx.physics.box2d.World;
                public class Box2DSystem {
                    private final World box2DWorld = new World();
                    public void onTickEntity() {
                        box2DWorld.step(1F / 60F, 2, 2);
                    }
                }
            """.trimIndent(),
            packagePrefixes = listOf("dev.wildware.udea.net"),
        )
        assertEquals(setOf("DET005"), findings.map { it.ruleId }.toSet(), findings.render())
        assertTrue(
            findings.any { it.target == "com.badlogic.gdx.physics.box2d.World.step" },
            findings.render(),
        )
        assertTrue(findings.first().didYouMean.contains("PhysicsWorld"))
    }

    /** `UIScreen.kt:16` - the frame delta, read where a tick belongs. */
    @Test
    fun `the old UIScreen reports DET006`() {
        val findings = scan(
            "dev/wildware/udea/screen/UIScreen.java" to """
                package dev.wildware.udea.screen;
                import com.badlogic.gdx.Gdx;
                public class UIScreen {
                    public float act() {
                        return Math.min(Gdx.graphics.getDeltaTime(), 1F / 30F);
                    }
                }
            """.trimIndent(),
            packagePrefixes = listOf("dev.wildware.udea.screen"),
        )
        assertEquals(setOf("DET006"), findings.map { it.ruleId }.toSet(), findings.render())
        assertTrue(findings.all { it.span.endsWith("UIScreen.java:5:1") }, findings.render())
        assertTrue(findings.first().didYouMean.contains("SimClock.tick"))
    }

    private fun List<Finding>.render(): String = joinToString("\n") { it.render() }

    private fun scan(
        source: Pair<String, String>,
        packagePrefixes: List<String> = listOf("dev.wildware.udea.ecs"),
    ): List<Finding> {
        val compiled = FixtureCompiler.compile(
            tempDir,
            mapOf(source) + FixtureCompiler.GDX_STUBS,
        )
        return DeterminismScan.run(
            inputs = listOf(FixtureCompiler.scopeInput(compiled, packagePrefixes = packagePrefixes)),
            allowlist = Allowlist.parse(""),
            repoRoot = compiled.sourceDir,
        ).findings
    }
}
