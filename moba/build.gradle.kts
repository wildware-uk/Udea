import dev.wildware.udea.build.ReleaseRules
import dev.wildware.udea.build.UdeaNetComponents
import dev.wildware.udea.build.UdeaVerifyReleaseTask
import dev.wildware.udea.build.registerNetProtocolLock
import dev.wildware.udea.gradle.UdeaAgentPlugin

plugins {
    id("udea.kotlin-library")

    // `@Replicated` on `Position` is processed here, exactly as it is for any other game.
    // Before this, `PositionReplicator` was written out by hand - which `ReplicatorApiShapeTest`
    // correctly failed the build for, because a hand-written replicator stores a `FieldMask` in
    // game code and a `Long`-to-`LongArray` widening would then be a breaking change. Generated
    // replicators live under `build/` and are regenerated, so they are out of that rule's scope
    // by construction.
    id("com.google.devtools.ksp") version libs.versions.ksp.get()

    // The plugin that was unreachable until now: it had a class and no id, no project applied it,
    // and there was no `gamebridge.json`, so `launch_instance` had nothing to launch and the only
    // bootable thing in the tree was a task in `udea-agent-host`'s TEST sources. Applying it here
    // creates the `agent` source set, generates the per-variant agent flag, wires
    // `-Dudea.agent.port` into `run`, and writes the launch declaration at the repository root.
    id("dev.wildware.udea.agent")

    // The build-time asset pipeline of spec 3.6, applied to a real game for the first time.
    // Before this, `AssetPackCli` said in its own KDoc that "nothing in `:moba`'s build produces
    // or consumes a bundle yet; `MobaScene` still slices a PNG at runtime" - which was true, and
    // is the sentence this line deletes.
    id("dev.wildware.udea.assets")
}

dependencies {
    // The forked asset pipeline: `udeaScanAssets`, `udeaValidateAssets`, `udeaPackBundle` and
    // `udeaGenerateAccessors` all run out of this configuration, in a JVM of their own. It is
    // NOT `implementation`: it carries `kotlin-compiler-embeddable`, and `UDEA-MG-005` forbids a
    // script compiler on a shipped game's runtime classpath. `StartupClasspathTest` checks that
    // from the other end by naming the artifacts.
    udeaAssetsCompiler(project(":udea-assets-compiler"))

    // The same module again, on the **agent** source set, so `:moba:run` can hold a warm
    // `AssetDaemon` and serve `assets.*` over the live game (see `MobaAssetTools`). Deliberately
    // not `implementation`: this is the jar that carries `kotlin-compiler-embeddable`, and
    // `UDEA-MG-005` plus `ReleaseRules.CLASSPATH_RULE` both forbid it on `runtimeClasspath`.
    // The `agent` source set is the one classpath in this project allowed to resolve it, and
    // `jar` packages `main`, so it cannot reach the artifact either.
    "agentImplementation"(project(":udea-assets-compiler"))

    implementation(project(":udea-core"))

    // `@Replicated`, `@Net` and `@Sim` on `Position`. BINARY-retained, so they are on this
    // module's own bytecode and cannot be `compileOnly`.
    implementation(project(":udea-annotations"))

    // The processor over this module's main source set.
    ksp(project(":udea-codegen"))
    implementation(project(":udea-gas"))
    implementation(project(":udea-net"))
    implementation(project(":udea-assets"))
    implementation(project(":udea-render"))

    // The cue drain and the mixer. Presentation, like `udea-render`, but with no GL in it: the
    // module that names `Gdx.audio` is this one (`dev.wildware.moba.audio.GdxAudioDevice`),
    // because `udea-audio` is a designated headless module and `UDEA-MG-002-BYTECODE` bans
    // `com/badlogic/gdx/Gdx` there by exact name.
    implementation(project(":udea-audio"))

    // `udea-render` declares gdx as `implementation`, so GL types do not leak onto a consumer's
    // compile classpath by default - a game that draws has to opt in, visibly, on this line.
    // `moba` draws (see `MobaScene`), so it opts in. Runtime already had gdx transitively; what
    // this adds is the ability to *name* `Texture` and `Batch`, which writing a `RenderSystem`
    // requires.
    implementation(libs.gdx)
}

/**
 * How this game names itself to `list_instances` and how a launcher starts it.
 *
 * Everything except `flagsPackage` is the plugin's default; it is written out because a launch
 * declaration is a contract with a process outside this build, and a reader looking for "what
 * command does the bridge run" should find it here rather than have to infer it from a convention.
 */
udeaAgent {
    name.set("moba")
    flagsPackage.set("dev.wildware.moba.agent")
}

/**
 * Where this game's assets live, and what they are compiled by.
 *
 * `assets/` and not `src/main/assets` or `src/main/resources`. Two roots exist in this module
 * today, and the reason has narrowed twice since this comment was first written. The old reason - that
 * `src/main/assets`, the mechanically migrated 19-script corpus of issue #93, could not compile
 * until #84's generated DSL landed - is **no longer true**: `AssetScope` grew the eight missing
 * kinds, the 19 scripts carry no imports at all, and `MigratedCorpusCompilesTest` compiles and
 * validates every one of them with zero errors. Breaking a reference in that tree turns it red
 * without a `--rerun-tasks`, so it is a live check and not a stale one.
 *
 * It is also **no longer a capability gap**, which is the correction this line needed most.
 * `control`, `axis2D`, `binding` and `axis2DBinding` are published `AssetKind`s and `AssetCodecs`
 * has always round-tripped all four; nothing had simply ever put one in a *packed* root, and
 * `assets/control/controls.udea.kts` now does - `MobaControls.BINDINGS` is loaded from the bundle
 * and `MobaFieldTest` fails if the packed key codes stop being the ones the game runs on. So this
 * split costs the corpus, not the controls.
 *
 * What still keeps the roots apart is narrower and is a *packing* limit, not a compiling one:
 * `character`, `gameplayEffect` and `effect` are `AssetKind.Unpublishable`, so
 * `level/test_level`'s 27 entities pack without their blueprints - 27 `UDEA0013`s, pinned by
 * `MigratedCorpusBundleTest`. A game cannot load a bundle whose level has no entities to spawn,
 * so switching this line today would trade a working game for a corpus. Closing that is #84's,
 * and when it closes the two roots become one and this block goes away.
 *
 * The Kotlin version is declared even though the compiler classpath is already `@Classpath` on
 * every task - see `UdeaAssetsExtension.kotlinVersion` for why a redundant input is worth its
 * line here.
 */
udea {
    assetRoots.from("assets")
    kotlinVersion.set(libs.versions.kotlin.get())
}

/**
 * The release gate bans **this game's** agent package too, not only the engine's.
 *
 * `ReleaseRules.DEFAULT_BANNED_PREFIXES` names `dev/wildware/udea/agent/` and
 * `dev/wildware/udea/agenthost/`, which is the engine's half. `MobaAgent` is in neither: it is
 * in `dev.wildware.moba.agent`, in a source set of its own, and it stays out of the jar today
 * only because `agentClasses` is not wired into `jar`. That is a true statement about the
 * current packaging and not a guarantee - the day somebody builds a fat jar, or adds
 * `from(sourceSets["agent"].output)`, the class that binds an HTTP surface onto the live
 * simulation ships and every rule in the default list is still satisfied.
 *
 * So the gate is told the name that is already written down two lines above. Proven load-bearing
 * rather than asserted: adding `from(sourceSets["agent"].output)` to `jar` turns
 * `./gradlew :moba:assemble -Pudea.release=true` red on `UDEA-REL-001`.
 */
tasks.named<UdeaVerifyReleaseTask>("udeaVerifyRelease") {
    bannedPrefixes.set(ReleaseRules.DEFAULT_BANNED_PREFIXES + "dev/wildware/moba/agent/")
}

/**
 * Names this module's generated manifest fragment, the way `:udea-agent` names its own.
 *
 * Without it two modules emit `udea/-agent-tools.json` and the second overwrites the first.
 */
/**
 * The project-wide `@Replicated` id space, read from the reviewed `net-components.lock`.
 *
 * Not optional for a module that emits protocol identity. A processor numbering only the symbols
 * in front of it hands out 0, 1, 2 per module, so two modules both mint `ComponentTypeId(0)` and
 * two peers decode each other's packets as the wrong component type - silently, because each
 * module's lock is internally consistent and `protoHash` therefore reports agreement.
 */
val projectComponents: Provider<String> =
    providers.fileContents(rootProject.layout.projectDirectory.file(UdeaNetComponents.FILE_NAME))
        .asText
        .map { text ->
            when (val parsed = UdeaNetComponents.parse(text)) {
                is UdeaNetComponents.Parse.Success -> UdeaNetComponents.optionValue(parsed.components)
                is UdeaNetComponents.Parse.Failure -> throw GradleException(parsed.problem)
            }
        }

ksp {
    arg("udea.moduleName", "Moba")
    arg(UdeaNetComponents.KSP_OPTION, projectComponents.get())
}

/**
 * `moba` emits protocol identity now, so it gets the same reviewed lock gate `:udea-codegen` has.
 *
 * Without this the module would generate a wire contract that nothing compares against a reviewed
 * file, which is the one arrangement the lock exists to prevent: a component id and a field width
 * would be free to change in a diff nobody read.
 */
registerNetProtocolLock(
    generatedLock = layout.buildDirectory.file("generated/ksp/main/resources/udea/Moba-net-protocol.lock"),
    producingTask = "kspKotlin",
)

/** The three entry points of spec 4, over the one `MobaGame.definition()`. */
val agentSources: SourceSet = sourceSets.named(UdeaAgentPlugin.DEFAULT_SOURCE_SET).get()

/**
 * `moba.agent` - and the task the launch declaration names.
 *
 * It runs off the **agent** source set's runtime classpath, which is the only classpath in this
 * project that resolves `:udea-agent-host`. `runtimeClasspath` does not, which is what makes
 * `ReleaseRules.CLASSPATH_RULE` pass for a reason rather than by omission, and `jar` packages
 * `main` alone, so no agent entry point is inside the artifact either.
 *
 * `run` rather than `runAgent` because `UdeaAgentPlugin` wires `-Dudea.agent.port` into the task
 * literally named `run`, and because that is the task the generated `launch.command` invokes.
 */
tasks.register<JavaExec>("run") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.agent: Offscreen by default, with the agent HTTP surface on -PdebugPort=N."
    mainClass.set("dev.wildware.moba.agent.MobaAgent")
    classpath = agentSources.runtimeClasspath

    // --- the dev asset daemon -----------------------------------------------------------------
    //
    // Three properties, and the game registers `assets.*` only when all three are usable. A
    // packaged game has none of them and correctly serves no asset tools at all; `MobaAssetTools`
    // says so on stderr rather than registering tools that would fail on first call.
    //
    // `udea.assets.root` is the same directory `udea { assetRoots }` names above and the same one
    // `udeaPackBundle` packed, which is what makes the daemon's graph and the loaded `.udeapak`
    // the same graph - see `MobaAssetTools` for why that matters.
    systemProperty("udea.assets.root", layout.projectDirectory.dir("assets").asFile.absolutePath)
    systemProperty("udea.repoRoot", rootProject.layout.projectDirectory.asFile.absolutePath)

    // A `CommandLineArgumentProvider` and not a `systemProperty`, because resolving a
    // configuration during configuration is a configuration-cache failure and because the
    // resolved classpath must be this invocation's rather than whichever one stored the cache.
    // `files(provider)` and not `configurations.named(...).get()`: a `Configuration` is a
    // `FileCollection` and is also one of the types the configuration cache refuses to
    // serialize, so capturing one in the provider below fails the build at store time.
    val scriptClasspath: FileCollection = files(configurations.named("udeaAssetScript"))
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Dudea.assetsCompiler.classpath=" + scriptClasspath.asPath)
        },
    )
}

tasks.register<JavaExec>("runServer") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.server: headless, no GL context, no agent surface."
    mainClass.set("dev.wildware.moba.entry.MobaServer")
    classpath = sourceSets.main.get().runtimeClasspath
}

/**
 * `moba.shot`: one Offscreen frame of the whole character roster, written as a PNG.
 *
 * Deliberately NOT wired into `check`. It creates a real GL context, so on a machine with no
 * driver it is a failure rather than a skip - which is the right behaviour for a task that is
 * *asked for* and the wrong behaviour for `./gradlew build` on a headless container. See
 * `MobaShot` for what it draws and why it does not load `level/test_level`.
 */
tasks.register<JavaExec>("runShot") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.shot: captures one frame of the character roster to -Pudea.shot.out=<png>."
    mainClass.set("dev.wildware.moba.entry.MobaShot")
    classpath = sourceSets.main.get().runtimeClasspath
    systemProperty("udea.render.mode", "Offscreen")
    systemProperty(
        "udea.shot.out",
        providers.gradleProperty("udea.shot.out").orNull
            ?: layout.buildDirectory.file("reports/udea/roster.png").get().asFile.absolutePath,
    )
    systemProperty("udea.shot.tick", providers.gradleProperty("udea.shot.tick").orNull ?: "18")
}

// The evidence task. `MatchShot` lives in the test source set - it needs a GL driver, so wiring it
// into `check` would turn a missing driver into a skip, which is the failure mode this repository
// has already shipped once (see `MobaShot`). It is run by name, and it is a task rather than a
// hand-assembled `java -cp` so that the classpath it runs on is the one Gradle resolved.
tasks.register<JavaExec>("runMatchShot") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.matchshot: captures the melee, the HUD, the spin and the match result."
    mainClass.set("dev.wildware.moba.MatchShot")
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("udea.render.mode", "Offscreen")
    systemProperty(
        "udea.matchshot.dir",
        providers.gradleProperty("udea.matchshot.dir").orNull
            ?: layout.buildDirectory.dir("reports/udea/match").get().asFile.absolutePath,
    )
}

/**
 * `moba.netproof`: one server, two clients, the real 27-unit level, and three hashes that must
 * agree. Run by name rather than wired into `check`, because it prints a transcript that is the
 * point of running it; the assertion it embodies is covered by `MobaNetAgreementTest`.
 */
tasks.register<JavaExec>("runNetProof") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.netproof: server + 2 clients, perfect / 150ms+5% loss / TRELLO_8."
    mainClass.set("dev.wildware.moba.net.MobaNetProof")
    classpath = sourceSets.main.get().runtimeClasspath
}

tasks.register<JavaExec>("runClient") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.client: a visible LWJGL3 window. Modes: local | listen | host [port] | join <host[:port]>."
    mainClass.set("dev.wildware.moba.entry.MobaClient")
    classpath = sourceSets.main.get().runtimeClasspath
    // `JavaExec` forks, so a `-D` on the Gradle command line reaches the daemon and stops there.
    // `MobaClient`'s three knobs are how a two-window run is *checked* rather than watched - a
    // bounded frame count and a scripted axis are what turn two windows into two transcripts - so
    // a task that silently dropped them made the documented command a no-op. Read through
    // `providers` and not `System.getProperties()`: a configuration-time property read is exactly
    // what the configuration cache refuses to serialise. Same shape as `runAudio` above.
    listOf(
        "udea.net.frames",
        "udea.net.walk",
        "udea.moba.fog",
        "udea.render.mode",
    ).forEach { name ->
        val value = providers.systemProperty(name)
        if (value.isPresent) systemProperty(name, value.get())
    }
}

// The audible client. Identical to `runClient` except that its frame drains `GameContext.cues`
// through `MobaAudio` - see `MobaAudioProbe` for why that is a separate main today and what one
// line moves it into `MobaClient`. The default working directory is this project, which is what
// lets `GdxAudioDevice` find `assets/sounds/**` on disk: the `.ogg` files are not packed into
// `assets.udeapak`, because `AssetPackCli` writes no blob sections.
tasks.register<JavaExec>("runAudio") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba.audio: a windowed client that drains the cue queue and plays sound."
    mainClass.set("dev.wildware.moba.audio.MobaAudioProbe")
    classpath = sourceSets.main.get().runtimeClasspath
    // Forwarded through `providers` rather than read off `System.getProperties()`: a configuration
    // -time system property read is exactly what the configuration cache refuses to serialise, and
    // these two are the only knobs the probe has.
    listOf("udea.audio.probe.frames", "udea.render.mode").forEach { name ->
        val value = providers.systemProperty(name)
        if (value.isPresent) systemProperty(name, value.get())
    }
}

/**
 * The debug source set compiles as part of `check`, and not otherwise.
 *
 * Without this the only thing that ever compiles `src/agent` is somebody running `:moba:run`, so
 * a change to `udea-agent-host`'s API would leave the build green and the launch path broken -
 * discovered by the next person to try to launch an instance, which is the worst possible moment.
 * It is deliberately not on `assemble`: `assemble` is what a release build runs, and compiling the
 * agent entry point there would put its output where `udeaVerifyRelease` has to reason about it.
 */
tasks.named("check") {
    dependsOn(agentSources.classesTaskName)
}

// --- udeaBenchStartup (issue #94) ---------------------------------------------------------
//
// The Phase 2 exit criterion "process start to first frame < 800ms", measured on a real
// `:moba` process rather than on a harness that resembles one.
//
// It is the criterion that proves the runtime script host is gone. The old path constructed a
// `BasicJvmScriptingHost` and compiled every `.udea.kts` during startup
// (`common/.../assets/dsl/script/scriptHost.kt:41,53-58`), mitigated only by an on-disk jar
// cache that a first run, a CI run or any changed script missed. There is no assertion that
// can prove a compiler is absent; a wall-clock gate on the whole boot is what notices if one
// creeps back, and `StartupClasspathTest` closes the same question from the other end by
// naming the artifacts.

/** JVM start to first presented frame, in millis. Spec 6's Phase 2 exit criterion. */
val startupBudgetMillis: Long =
    (providers.gradleProperty("udea.bench.budgetMillis").orNull ?: "800").toLong()

/** How many processes to launch. The median of these is what the gate compares. */
val startupRuns: Int = (providers.gradleProperty("udea.bench.runs").orNull ?: "5").toInt()

/**
 * Launches `moba.bench` N times and gates the median.
 *
 * A task class rather than a `doLast` because it has to fork a process per run and read the
 * files those processes write, and both of those need injected services to survive the
 * configuration cache.
 */
abstract class UdeaBenchStartupTask : DefaultTask() {

    @get:InputFiles
    abstract val runtimeClasspath: ConfigurableFileCollection

    @get:Input
    abstract val mainClass: Property<String>

    @get:Input
    abstract val runs: Property<Int>

    @get:Input
    abstract val budgetMillis: Property<Long>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val layout: ProjectLayout

    @TaskAction
    fun bench() {
        val scratch = layout.buildDirectory.dir("tmp/udeaBenchStartup").get().asFile
        scratch.deleteRecursively()
        scratch.mkdirs()
        val samples = mutableListOf<Map<String, Double>>()
        for (run in 0 until runs.get()) {
            val out = scratch.resolve("run-$run.json")
            val result = execOperations.javaexec {
                mainClass.set(this@UdeaBenchStartupTask.mainClass)
                classpath = runtimeClasspath
                args("--exit-after-first-frame")
                systemProperty("udea.bench.out", out.absolutePath)
                systemProperty("udea.render.mode", "Offscreen")
                isIgnoreExitValue = true
            }
            if (result.exitValue != 0 || !out.isFile) {
                throw GradleException(
                    "moba.bench run $run exited ${result.exitValue} and wrote " +
                        (if (out.isFile) "a report" else "no report") +
                        ". A machine with no GL driver cannot run this gate; that is a red " +
                        "build and not a skip, because a silently skipped startup gate is how a " +
                        "regression ships.",
                )
            }
            samples += parse(out.readText())
        }
        val medians = summarise(samples)
        val median = medians.getValue("firstFrameMillis")
        val p95 = percentile(samples.map { it.getValue("firstFrameMillis") }, 0.95)
        val document = render(medians, median, p95, samples.size)
        val file = report.get().asFile
        file.parentFile.mkdirs()
        file.writeText(document)
        logger.lifecycle("[udeaBenchStartup] median ${fmt(median)}ms, p95 ${fmt(p95)}ms over ${samples.size} runs")
        logger.lifecycle(document)
        if (median > budgetMillis.get()) {
            throw GradleException(
                "process start to first frame is ${fmt(median)}ms over ${samples.size} runs, " +
                    "budget ${budgetMillis.get()}ms. The phase breakdown in ${file.absolutePath} " +
                    "names the phase that grew.",
            )
        }
    }

    private fun parse(json: String): Map<String, Double> =
        Regex("""["](\w+)["]\s*:\s*([0-9.]+)""").findAll(json)
            .associate { it.groupValues[1] to it.groupValues[2].toDouble() }

    /** The per-phase median. A median per phase, not the phases of the median run. */
    private fun summarise(samples: List<Map<String, Double>>): Map<String, Double> =
        samples.flatMap { it.keys }.distinct().sorted().associateWith { key ->
            percentile(samples.mapNotNull { it[key] }, 0.5)
        }

    /** Nearest-rank, so a five-run p95 is the slowest run rather than an interpolation of it. */
    private fun percentile(values: List<Double>, fraction: Double): Double {
        require(values.isNotEmpty()) { "no samples" }
        val sorted = values.sorted()
        val rank = Math.ceil(fraction * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun render(medians: Map<String, Double>, median: Double, p95: Double, runs: Int): String =
        buildString {
            append("{\n")
            append("  \"runs\": ").append(runs).append(",\n")
            append("  \"budgetMillis\": ").append(budgetMillis.get()).append(",\n")
            append("  \"medianFirstFrameMillis\": ").append(fmt(median)).append(",\n")
            append("  \"p95FirstFrameMillis\": ").append(fmt(p95)).append(",\n")
            append("  \"phaseMedians\": {\n")
            val phases = listOf("jvmStartToMainMillis", "assetMillis", "glMillis", "worldMillis")
            phases.forEachIndexed { i, phase ->
                append("    \"").append(phase).append("\": ")
                append(fmt(medians[phase] ?: 0.0))
                append(if (i == phases.lastIndex) "\n" else ",\n")
            }
            append("  }\n")
            append("}\n")
        }

    private fun fmt(value: Double): String {
        val tenths = Math.round(value * 10.0)
        return "${tenths / 10}.${tenths % 10}"
    }
}

val udeaBenchStartup = tasks.register<UdeaBenchStartupTask>("udeaBenchStartup") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails when median moba process-start-to-first-frame exceeds the Phase 2 budget."
    runtimeClasspath.from(sourceSets.main.map { it.runtimeClasspath })
    mainClass.set("dev.wildware.moba.entry.MobaBench")
    runs.set(startupRuns)
    budgetMillis.set(startupBudgetMillis)
    report.set(layout.buildDirectory.file("reports/udea/startup.json"))
}

/**
 * The classpath half of the same gate, run on every `check`.
 *
 * Deliberately NOT folded into `udeaBenchStartup`'s action even though issue #94 asks for one
 * gate: `udeaBenchStartup` needs a GL driver and this does not, so a machine that cannot run
 * the wall-clock half would otherwise stop asserting the artifact half too - which is the
 * exact "silently skipped gate" failure mode this whole issue exists to notice. They are one
 * gate in the sense that both are wired into `check`, and two tasks in the sense that one of
 * them can run anywhere.
 */
/**
 * The three-process UDP proof is asked for by name, and is not a gate on `check`.
 *
 * Same reasoning as `runShot` and `runMatchShot` above, for a different scarce resource. It forks
 * three JVMs that each seed the whole 27-unit level and then tick against a **wall clock** at
 * 60Hz over real sockets. Under `./gradlew build` those three compete with every other Gradle
 * worker on the machine, miss their tick deadlines, and the reading drifts - so wiring it into
 * `check` would buy a proof that is red on a loaded laptop and green on an idle one, which is
 * worth less than no proof at all.
 *
 * The claim itself is not left unguarded. `MobaNetProof` and the loopback session run the same
 * replication path deterministically, on one thread with a manual clock, inside `check`. What
 * this adds is the part that only separate processes can show, and it is run by name:
 *
 * ```
 * ./gradlew :moba:runUdpProof
 * ```
 */
tasks.register<Test>("runUdpProof") {
    group = ApplicationPlugin.APPLICATION_GROUP
    description = "moba: the 27-unit battle over real UDP, three OS processes, perfect and lossy."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("dev.wildware.moba.net.MobaUdpTwoProcessTest") }
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}

tasks.test {
    // See `runUdpProof`: wall-clock timing across three forked JVMs, which `check` cannot give it.
    filter { excludeTestsMatching("dev.wildware.moba.net.MobaUdpTwoProcessTest") }
}

tasks.test {
    // Where `MobaAssetsTest` finds this module's sources. A relative path would resolve against
    // the project directory under Gradle and against the daemon's working directory under an
    // IDE, and a source scan that silently read nothing would pass.
    systemProperty("udea.moba.projectDir", layout.projectDirectory.asFile.absolutePath)
}

tasks.test {
    // A *local* val, captured by the `doFirst` below. A script-level property here would make
    // the lambda hold a reference to the build script object, which the configuration cache
    // refuses to serialize - the build fails at store time rather than at run time, which is
    // how this was found.
    val names: Provider<String> = configurations.named("runtimeClasspath")
        .map { it.files.joinToString(File.pathSeparator) { file -> file.name } }
    inputs.property("runtimeClasspathNames", names)
    doFirst {
        systemProperty("udea.moba.runtimeClasspathNames", names.get())
    }
}

// Deliberately NOT wired into `check`. The gate forks a process that creates a real LWJGL3
// context, so on a machine with no GL driver it is a red build rather than a skip (see the
// GradleException above) - which is the right behaviour for a gate that is *asked for*, and the
// wrong behaviour for `./gradlew build` on a developer's headless container. CI runs it
// explicitly, under xvfb, alongside `udeaGlTest`. `udeaBenchStartup` is referenced here so a
// configuration error in it is still a configuration error for everyone.
require(udeaBenchStartup.name == "udeaBenchStartup")

// --- the agent entry point is launchable, not merely compilable --------------------------------
//
// `check` compiles `src/agent` above, which catches an API break. It does not catch an entry point
// the JVM will not start: an `object`'s `@JvmStatic` detached from its `main` compiles cleanly and
// fails only at launch, with `gamebridge.json` pointing `game-bridge-mcp` straight at it. See
// `AgentEntryPointTest`, which loads the class from this directory and looks at nothing else.
tasks.test {
    dependsOn(agentSources.classesTaskName)
    // The whole agent runtime classpath, not just this module's output: reflecting on
    // `MobaAgent` resolves the signatures of every method it declares, and those name
    // `udea-agent-host` types. A loader given only the class output throws
    // `NoClassDefFoundError` before it can look at `main`.
    val agentClasses = agentSources.runtimeClasspath
    inputs.files(agentClasses).withPropertyName("agentClasses")
    doFirst {
        systemProperty("udea.moba.agentClasses", agentClasses.asPath)
    }
}
