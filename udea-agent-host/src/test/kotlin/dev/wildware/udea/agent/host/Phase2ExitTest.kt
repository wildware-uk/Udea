package dev.wildware.udea.agent.host

import dev.wildware.udea.agent.host.demo.Phase2Demo
import dev.wildware.udea.agent.host.demo.Phase2Instance
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec 6's **Phase 2 exit demo**, as a gate rather than as a transcript somebody once ran.
 *
 * Verbatim, the demo is: *an agent patches an asset value and the running game reflects it in
 * under a second without restarting; a typo'd reference is rejected in under 300ms with a file, a
 * line, a column and a did-you-mean, and the running game keeps its last-good graph.*
 *
 * ## Why this is not `AssetsToolsetTest`
 *
 * That test drives the same toolset through the same real daemon, and it is the better test of
 * the toolset. It is not this claim. It enters at `ToolIndex`, and it ticks the world by hand -
 * so the two things spec 6 actually asks about, **HTTP** and **a game that is running**, are both
 * absent. Here the loop is a thread nobody pumps, every call is a `GET` on a loopback socket, and
 * the number that has to change is read back out of the world with `world.get_component`.
 *
 * ## What makes "the running game reflects it" checkable
 *
 * `Phase2Demo`'s one system reads `SpriteSheet("character/orc_idle").scale` from the live
 * `AssetRegistry` every tick and writes `scale * 100` into `Position.hp`. `hp` is deliberately
 * outside the component's `agentWritableFields`, so no tool call can put a number there: the only
 * way it moves is that a system read a new asset value. Mutating the system to cache the sheet at
 * construction leaves this test red and every other test in the module green, which is the check
 * that this test measures the game and not the daemon.
 *
 * ## The two budgets, and how they are measured
 *
 * The clock starts when the HTTP request is *sent* and stops when a later HTTP request reports
 * the new number, so JSON, the socket, the bridge queue, the barrier and the tick boundary are
 * all inside the measurement. The daemon is warmed first with one `assets.validate`, because the
 * first script compile in a JVM pays for classloading the whole scripting host - measured on this
 * machine at roughly 2.3 seconds - and spec 6's claim is about the warm editing loop, not about
 * process start-up. That warm-up is stated here rather than hidden: a cold first edit does not
 * meet this budget and nothing in this repository claims it does.
 */
class Phase2ExitTest {

    @Test
    fun `an agent patches an asset value and the running game reflects it in under a second`() {
        instance { game ->
            warm(game)

            val began = System.nanoTime()
            val patch = command(
                game,
                "assets.patch",
                "path" to "character/orc.udea.kts",
                "find" to "scale = 0.02f",
                "replace" to "scale = 0.08f",
            )
            assertTrue("\"applied\":true" in patch, patch)
            assertTrue("\"pushedToGame\":true" in patch, patch)
            assertTrue("\"character/orc_idle\"" in patch, patch)

            val observed = await(BUDGET_APPLY_MS) { hp(game) == 8.0 }
            val elapsedMs = (System.nanoTime() - began) / 1_000_000
            println("phase 2 exit: agent request -> running world observed changed in ${elapsedMs}ms")
            assertTrue(observed, "the running world never reported the new value; hp is ${hp(game)}")
            assertTrue(
                elapsedMs <= BUDGET_APPLY_MS,
                "spec 6 gates this at ${BUDGET_APPLY_MS}ms; it took ${elapsedMs}ms",
            )
            // Without a restart: the same instance, the same loop thread, still ticking.
            assertTrue(!game.loopFinished(), "the loop thread ended, so this was a restart")
            assertTrue("\"character/orc_idle\"" in command(game, "assets.changed_since", "tick" to "0"))
        }
    }

    @Test
    fun `a typo'd reference is rejected with a file a line a column and a did-you-mean`() {
        instance { game ->
            warm(game)
            val file = game.assetRoot.resolve("character/orc.udea.kts")
            val before = file.readText()

            // Three rejections, and the median is the gate. Same statistic and same reason as
            // `DaemonLatencyBudgetTest`: this task runs inside `check`, beside every other
            // compilation in the build, and the maximum of a small sample there is the worst
            // scheduling hiccup rather than anything about the compiler. Every sample is printed.
            val samples = mutableListOf<Long>()
            var json = ""
            repeat(REJECT_SAMPLES) {
                json = resolve(
                    game,
                    command(
                        game,
                        "assets.write",
                        "path" to "character/orc.udea.kts",
                        "content" to BROKEN,
                    ),
                )
                samples += checkNotNull(FIELD.find(json)) { "no durationMs in $json" }
                    .groupValues[1].toLong()
            }

            assertTrue("\"rolledBack\":true" in json, json)
            assertEquals(before, file.readText(), "a rejected write must leave the file byte-identical")
            assertTrue("UDEA0004" in json, json)
            // A file, a line, a column and a did-you-mean - each asserted separately, because a
            // diagnostic missing any one of them is a diagnostic an agent cannot act on.
            assertTrue("character/orc.udea.kts" in json, "no file in $json")
            assertTrue("\"startLine\":2" in json, "no line in $json")
            assertTrue("\"startColumn\":" in json, "no column in $json")
            assertTrue("did you mean" in json.lowercase(), "no did-you-mean in $json")
            assertTrue("character/orc_idle" in json, "the suggestion is not the right id: $json")

            val median = samples.sorted()[samples.size / 2]
            println("phase 2 exit: typo'd reference rejected in ${median}ms (median of $samples)")
            assertTrue(
                median <= BUDGET_REJECT_MS,
                "spec 6 gates the rejection at ${BUDGET_REJECT_MS}ms; median was ${median}ms $samples",
            )
            // The last-good graph: the world still carries the value the daemon had before.
            assertEquals(2.0, hp(game), "a rejected edit must leave the running game as it was")
        }
    }

    // --- the harness -------------------------------------------------------------------------

    private fun instance(body: (Phase2Instance) -> Unit) {
        // Each test gets its own scratch tree and its own daemon; they edit real files.
        val name = "phase2-test-" + body.hashCode().toUInt().toString(16)
        val game = checkNotNull(Phase2Demo.start(port = 0, scratchName = name)) {
            "port 0 must bind"
        }
        game.use(body)
    }

    /**
     * One `assets.validate`, so the measured call is not the JVM's first script compile.
     *
     * Also the assertion that the corpus is green before anything is timed: a budget measured on
     * the error path measures the error path.
     */
    private fun warm(game: Phase2Instance) {
        // Twice, and `DaemonLatencyBudgetTest` discards its first sample for the same reason: the
        // first compile in a JVM pays for the scripting host's classloading and for a cold jar
        // cache, and the second is the first one that measures the daemon. Measured here, the
        // first validate is around 2.3 seconds and the second around 30ms.
        repeat(2) { assertTrue("\"ok\":true" in command(game, "assets.validate")) }
        // And waits for the probe to be live. `hp` is 0 until the loop has taken a tick with the
        // spawned entity in the world, and reading it before then would time the loop's start-up
        // rather than the reload - which showed up as a flake before this line existed.
        assertTrue(
            await(PROBE_TIMEOUT_MS) { hp(game) == 2.0 },
            "the probe never reached scale 0.02 * 100; hp is ${hp(game)}",
        )
    }

    /**
     * The answer itself, following `resultRef` through `GET /artifact` when there is one.
     *
     * An `assets.write` rejection is thousands of characters against a 1280-character digest
     * ceiling, so it never arrives inline and it never can - `AgentBridge.complete` spills it and
     * `/state` carries the handle instead. Following the handle here is not a workaround: it is
     * the same door `render.screenshot` has always used, and an agent that could not follow it
     * would not have the diagnostic either. What this test still asserts is that the diagnostic
     * **arrives**, by whichever of the two doors, in one round trip the caller can find from the
     * result it was given.
     */
    private fun resolve(game: Phase2Instance, entry: String): String {
        val handle = RESULT_REF.find(entry)?.groupValues?.get(1)
            ?: return entry.also {
                assertTrue("\"resultTooLarge\":true" !in it, "spilled with no handle: $it")
            }
        return get(game, "/artifact?id=$handle")
    }

    /** `Position.hp` on the probe entity, read back over HTTP. */
    private fun hp(game: Phase2Instance): Double {
        val json = command(
            game,
            "world.get_component",
            "id" to game.probeNetId.toString(),
            "component" to "Position",
        )
        return checkNotNull(HP.find(json)) { "no hp in $json" }.groupValues[1].toDouble()
    }

    /**
     * Sends one command and returns the rendered result, waiting for it to appear in `/state`.
     *
     * The wait is the shipped confirmation path - `completedCommandId` and the result ring - and
     * not a sleep, so a command that never runs fails here rather than being read as a fast one.
     */
    private fun command(game: Phase2Instance, name: String, vararg args: Pair<String, String>): String {
        val query = buildString {
            append("/command?cmd=").append(name)
            args.forEach { (key, value) ->
                append('&').append(key).append('=').append(encode(value))
            }
        }
        val ack = get(game, query)
        val id = checkNotNull(COMMAND_ID.find(ack)) { "refused: $ack" }.groupValues[1]
        val marker = "\"id\":$id,"
        val deadline = System.nanoTime() + RESULT_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            val state = get(game, "/state")
            val at = state.indexOf(marker)
            if (at >= 0) return state.substring(at, endOfEntry(state, at))
            Thread.sleep(2)
        }
        error(
            "$name (id $id) never completed within ${RESULT_TIMEOUT_MS}ms; last /state was " +
                get(game, "/state"),
        )
    }

    /** The end of the result object that starts at [from], by brace depth. */
    private fun endOfEntry(document: String, from: Int): Int {
        var depth = 1
        var index = document.indexOf('{', from)
        if (index < 0) return document.length
        // `from` points just past the opening brace of the entry, so start at that brace instead.
        index = document.lastIndexOf('{', from)
        var cursor = index + 1
        var inString = false
        var escaped = false
        while (cursor < document.length && depth > 0) {
            val character = document[cursor]
            when {
                escaped -> escaped = false
                character == '\\' && inString -> escaped = true
                character == '"' -> inString = !inString
                inString -> Unit
                character == '{' -> depth++
                character == '}' -> depth--
            }
            cursor++
        }
        return cursor
    }

    private fun get(game: Phase2Instance, path: String): String = CLIENT.send(
        HttpRequest.newBuilder()
            .uri(URI.create("http://${AgentHost.LOOPBACK}:${game.port}$path"))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    ).body()

    private fun await(millis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
        }
        return condition()
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8)

    private companion object {
        /** Spec 6, Phase 2: "reflects it in under a second". */
        const val BUDGET_APPLY_MS = 1_000L

        /** Spec 6, Phase 2: "rejected in under 300ms". */
        const val BUDGET_REJECT_MS = 300L

        /** Enough for a median that is not one sample, short enough not to dominate `check`. */
        const val REJECT_SAMPLES = 3

        /** How long the loop may take to run one tick with the probe entity in the world. */
        const val PROBE_TIMEOUT_MS = 10_000L

        /** Generous: a command that has not answered in this long has not answered. */
        const val RESULT_TIMEOUT_MS = 60_000L

        val CLIENT: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        val COMMAND_ID = Regex("\"commandId\":(\\d+)")
        val HP = Regex("\"hp\":([0-9.eE+-]+)")
        val FIELD = Regex("\"durationMs\":(\\d+)")

        /** The handle `AgentBridge.complete` leaves in place of an answer too large for `/state`. */
        val RESULT_REF = Regex("\"resultRef\":\"([A-Za-z0-9_]+)\"")

        val BROKEN = """
            spriteSheet(name = "orc_idle", spritePath = "/sprites/orc/idle.png", rows = 1, columns = 6, scale = 0.02f)
            spriteAnimation(name = "orc_idle_anim", sheet = reference("character/orc_idel"))
            soundCue(name = "orc_hit", pitchVariance = 0.3f, volume = 1.0f, sounds = listOf("/sounds/orc/hit.ogg"))
        """.trimIndent()
    }
}
