plugins {
    id("udea.kotlin-library")
}

dependencies {
    api(project(":udea-core"))

    // The Replicator contract's executable specification: ArrayFieldStore and ArrayBitIo. The
    // attribute replication tests measure a real payload through them rather than asserting on a
    // mock, and udea-core publishes them as a variant for exactly this.
    testImplementation(testFixtures(project(":udea-core")))
}

// --- Phase 3 time gate (spec 5, issue #95) ----------------------------------------------------
//
// Tick is the universal unit. A seconds-denominated duration or a wall-clock read inside GAS is not
// a style problem: it is a value that will not survive a rewind and will not agree across two
// machines, and it will present as a desync a long way from its cause. So it is a build failure,
// not a review note.
//
// Everything the task needs is captured as a plain value before `doLast`, and the scan itself is
// written inside it: a `doLast` that called a script-level function would be a Gradle script object
// reference, which the configuration cache refuses to serialise.

val udeaVerifyGasTime = tasks.register("udeaVerifyGasTime") {
    group = "verification"
    description = "Fails if udea-gas simulation code references seconds, a wall clock or LibGDX."

    /** Forbidden reference, and why it is forbidden — the message a developer actually reads. */
    val forbidden: Map<String, String> = mapOf(
        "kotlin.time.Duration" to "a seconds-denominated duration; use Tick, or an Int tick count",
        "System.nanoTime" to "a wall clock; time comes from SimClock, denominated in Tick",
        "System.currentTimeMillis" to "a wall clock; time comes from SimClock, denominated in Tick",
        "Instant.now" to "a wall clock; time comes from SimClock, denominated in Tick",
        "com.badlogic.gdx" to "LibGDX; udea-gas must never see graphics or audio (spec 3.5)",
        "deltaTime" to "a frame delta; accumulating one is what issue #95 exists to delete",
    )

    val sourceDir = layout.projectDirectory.dir("src/main/kotlin")
    inputs.dir(sourceDir).withPropertyName("simulationSources")
    inputs.property("forbidden", forbidden.keys.sorted().joinToString(","))

    // A verification task with no output is never up to date; the report keeps it incremental and
    // is what CI publishes as the gate's artifact.
    val reportFile = layout.buildDirectory.file("reports/udea/gas-time-gate.txt")
    outputs.file(reportFile)

    val sources = sourceDir.asFile
    val report = reportFile.get().asFile

    doLast {
        // Comments are stripped first, because this module's KDoc deliberately *names* the old
        // seconds-denominated API it replaced — a gate that could not tell a citation from a call
        // would force the documentation to go vague about what it fixed. Newlines inside block
        // comments are kept so reported line numbers stay true. It does not understand a `//`
        // inside a string literal, which is a false-negative risk only: a forbidden reference
        // inside a string is not a call, and the alternative is a Kotlin lexer in a build script.
        fun stripComments(text: String): String {
            val out = StringBuilder(text.length)
            var index = 0
            while (index < text.length) {
                when {
                    text.startsWith("/*", index) -> {
                        val end = text.indexOf("*/", index + 2)
                        val stop = if (end < 0) text.length else end + 2
                        for (character in text.substring(index, stop)) if (character == '\n') out.append('\n')
                        index = stop
                    }

                    text.startsWith("//", index) -> {
                        val end = text.indexOf('\n', index)
                        index = if (end < 0) text.length else end
                    }

                    else -> {
                        out.append(text[index])
                        index++
                    }
                }
            }
            return out.toString()
        }

        val violations = mutableListOf<String>()
        var scanned = 0
        sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .forEach { file ->
                scanned++
                stripComments(file.readText()).lineSequence().forEachIndexed { index, line ->
                    forbidden.forEach { (needle, why) ->
                        if (line.contains(needle)) {
                            violations += "${file.name}:${index + 1} references '$needle' — $why"
                        }
                    }
                }
            }

        require(scanned > 0) { "udeaVerifyGasTime scanned no sources; the gate is misaimed at $sources" }

        report.parentFile.mkdirs()
        report.writeText("scanned $scanned file(s)\n" + violations.joinToString("\n").ifEmpty { "clean" } + "\n")

        if (violations.isNotEmpty()) {
            throw GradleException(
                "udea-gas simulation code is not tick-denominated:\n" +
                    violations.joinToString("\n") { "  $it" },
            )
        }
    }
}

// --- Phase 3 allocation gate (issue #97) ------------------------------------------------------
//
// The recompute runs for every unit in a 5v5 every tick. `AttributeSystem.kt:23` allocated a sorted
// list per entity per tick; at 500 entities and 60Hz that is 30 000 lists a second, and the GC
// pause it buys is a frame the simulation does not get. Same shape as `udea-core`'s snapshot and
// tick-loop budgets: a separate task so the measurement reaches the build log, and excluded from
// `test` so a normal run does not pay for it twice.

val allocationTestClass = "dev.wildware.udea.gas.AttributeAllocationTest"

tasks.named<Test>("test") {
    filter.excludeTestsMatching(allocationTestClass)
}

val udeaGasAllocationBudget = tasks.register<Test>("udeaGasAllocationBudget") {
    group = "verification"
    description = "Gates the attribute recompute at 500 entities x 8 effects x 600 ticks: zero bytes."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching(allocationTestClass)
    testLogging.showStandardStreams = true
}

tasks.named("check") {
    dependsOn(udeaVerifyGasTime, udeaGasAllocationBudget)
}
