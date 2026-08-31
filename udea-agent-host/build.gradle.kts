plugins {
    id("udea.kotlin-library")
}

dependencies {
    api(project(":udea-agent"))

    // --- the render side, and why it is `implementation` and no longer `testImplementation` ---
    //
    // Spec 4 gives this module "the toolsets that need a render context or live input: render,
    // input, ui". It owns `RenderToolset` and `RenderControl`, and it owns `AgentOverlayView`,
    // which spec 3.7 says is drawn on the human's screen. Both of those need the other half of a
    // pair that lives here: `RenderControl` needs `PresentationControl`, and the overlay needs an
    // `OverlaySystem` over a `Batch`.
    //
    // This line used to be `testImplementation`, because this module was in
    // `ModuleGraphRules.HEADLESS_PROJECTS`. The result was not a headless agent host; it was
    // `OffscreenRenderControl` and the GL overlay adapter sitting in *test* sources with no
    // shipped path able to reach either - so `render.screenshot` answered `no_render_context` on
    // every real run and the overlay was drawn only by tests. A module that owns the render
    // toolset and may not name a render type is a contradiction, and the ruling resolved it here
    // rather than by writing the adapter out a third time in each game.
    //
    // What still holds: the module is debug-only, and `ReleaseRules.CLASSPATH_RULE`
    // (`UDEA-REL-002`) fails any release build whose runtime classpath resolves it. That is the
    // gate the exemption leans on, it is enforced by `udeaVerifyRelease`, and it is asserted by
    // `ModuleGraphRulesTest` alongside the exemption itself. `:udea-core` - the headless
    // guarantee that matters - is untouched and still cannot name a `udea.render` type.
    implementation(project(":udea-render"))

    // gdx types this module's own code names: `Batch`, `BitmapFont`, `Texture` and `Color` in
    // `AgentOverlaySystem`. `udea-render` declares gdx as `implementation` so GL cannot leak onto
    // a consumer's *compile* classpath by default - which is the rule working as intended: a
    // module that writes a renderer opts in, visibly, on this line.
    implementation(libs.gdx)

    /*
     * The wire, and why this line is no longer `testImplementation`.
     *
     * Issue #80 shipped with an in-process double standing in for the transport, because
     * `:udea-net` had no wire when it was written. It has one now, and a seam left un-joined is
     * a seam nobody has ever run: the double would have passed on the day the real transport
     * dropped every datagram, which its own KDoc admitted.
     *
     * This used to be `testImplementation`, on the reasoning that "nothing this module *ships*
     * names a transport". That has stopped being true, and deliberately: `host/net/` ships the
     * `net.*` toolset, which stands a server and n clients up in one process over
     * `NetHarness`/`SimulatedTransport` and reports a `DesyncReport`. `:udea-net` was real,
     * tested and *unreachable from an agent* - twenty test files drove it from Kotlin and no
     * tool called any of it - and a capability nothing can reach is not shipped.
     *
     * What the old reasoning was protecting is untouched. `:udea-net` is headless, so this adds
     * no GL and no native to anything. The session-identity seam is still a `SessionPeers.record`
     * call and a list of JVM arguments, so a *launcher* still joins two processes without either
     * module knowing the other. And this module remains debug-only: `UDEA-REL-001` keeps its
     * classes out of every shipped artifact and `UDEA-REL-002` keeps it off every release runtime
     * classpath, which is the gate that lets it hold a dependency `moba` also holds.
     */
    implementation(project(":udea-net"))

    // `LatencyBudget`, the contention note `Phase2ExitTest` ends its budget failures with
    // (issue #175). Test scope only, and `udea-diagnostics` is a zero-dependency leaf, so this
    // adds the fixture and nothing else to a module `udeaVerifyRelease` already keeps out of
    // every shipped artifact.
    testImplementation(testFixtures(project(":udea-diagnostics")))
}

// --- the Phase 1 exit demo -------------------------------------------------------------------
//
// `./gradlew :udea-agent-host:udeaPhase1Demo -Pudea.agent.port=7820` boots a real headless game
// with the agent surface bound on loopback and blocks. It is a `JavaExec` over the *test*
// runtime classpath rather than a `main` in `src/main`, deliberately: the demo game - one
// component, one blueprint, a census kept by hand - is a fixture, and a fixture in `src/main`
// would ship inside the module `udeaVerifyRelease` already refuses to let near a release.
//
// It exists because nothing else in the repository stands an instance up: `moba` has no `main`,
// and `UdeaAgentPlugin` has no plugin id and is applied by no project. When those land, this
// task's reason to exist goes with them.
val udeaPhase1Demo = tasks.register<JavaExec>("udeaPhase1Demo") {
    group = "udea"
    description = "Boots a headless game with the agent surface bound, and blocks. -Pudea.agent.port=N"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.wildware.udea.agent.host.demo.Phase1Demo")
    // Read lazily: the port arrives on *this* invocation's command line, so baking it in at
    // configuration time would hand every later run whichever value the cache was stored with.
    // See `udeaPhase1OffscreenDemo` for why the Provider is read into a local first: inside the
    // lambda, `providers` closes over the build script object, which the configuration cache
    // cannot serialize, and the task fails the moment anyone runs it.
    val port = providers.gradleProperty("udea.agent.port")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            val value = port.orNull
            if (value == null) emptyList() else listOf("-Dudea.agent.port=$value")
        },
    )
}

// --- the Phase 1 exit demo, offscreen half -----------------------------------------------------
//
// `./gradlew :udea-agent-host:udeaPhase1OffscreenDemo -Pudea.agent.port=7821` boots the same
// surface behind a real LWJGL3 context with a hidden window, so `render.screenshot` returns PNG
// bytes instead of `no_render_context`. Same reasons as `udeaPhase1Demo` for being a `JavaExec`
// over the test runtime classpath: the game is a fixture, and the adapter that joins the render
// toolset's port to `udea-render` cannot live in the main sources of either module.
val udeaPhase1OffscreenDemo = tasks.register<JavaExec>("udeaPhase1OffscreenDemo") {
    group = "udea"
    description = "Boots an Offscreen game with the agent surface bound, and blocks. " +
        "-Pudea.agent.port=N"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.wildware.udea.agent.host.demo.Phase1OffscreenDemo")
    // The `Provider` is read into a local *before* the lambda, and the lambda closes over the
    // local. Written as `providers.gradleProperty(...)` inside the lambda it closes over the
    // build script object instead, and the configuration cache refuses to serialize one - which
    // is a failure the task only hits when somebody actually runs it, so it sat latent in
    // `udeaPhase1Demo` until this one was run. Still read lazily: the port arrives on *this*
    // invocation's command line, so baking the value in at configuration time would hand every
    // later run whichever value the cache was stored with.
    val port = providers.gradleProperty("udea.agent.port")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            val value = port.orNull
            if (value == null) emptyList() else listOf("-Dudea.agent.port=$value")
        },
    )
}

// --- udeaAgentGlTest ---------------------------------------------------------------------------
//
// The agent-host tests that boot a real LWJGL3 context, in a JVM of their own and out of `test`.
// The same split `udea-render` makes and for the same reason: `Lwjgl3Application` populates
// `Gdx.gl`/`Gdx.graphics`/`Gdx.app` process-wide, so a test that booted one would change what
// every other test in the JVM observes. It also means a machine with no display fails only the
// tests that asked for one.
val agentGlTestPackage = "dev.wildware.udea.agent.host.gl"

val udeaAgentGlTest = tasks.register<Test>("udeaAgentGlTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the agent-host tests that drive a real Offscreen render backend."

    val testSourceSet = sourceSets.test.get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("$agentGlTestPackage.*") }

    // Set on any CI job that has a display, so a render toolset which quietly stops working
    // cannot hide behind a skip forever. Same property `udea-render`'s GL tests read.
    systemProperty(
        "udea.render.requireGl",
        providers.gradleProperty("udea.render.requireGl").getOrElse("false"),
    )
}

tasks.test {
    filter { excludeTestsMatching("$agentGlTestPackage.*") }
}

tasks.check {
    dependsOn(udeaAgentGlTest)
}

// --- the Phase 2 exit demo ---------------------------------------------------------------------
//
// `./gradlew :udea-agent-host:udeaPhase2Demo -Pudea.agent.port=7830` boots the headless game of
// `udeaPhase1Demo` plus a real warm `AssetDaemon` and the `assets.*` toolset, and blocks. It is
// what spec 6's Phase 2 demo is driven against: an agent patches a value over HTTP and a system
// running on the loop reads the new one on the next tick.
//
// The daemon reaches it through a configuration of its own, never through `testRuntimeClasspath`,
// for exactly the reason `udea-agent`'s `assetToolsRuntime` gives: `udea-assets-compiler` carries
// the Kotlin scripting host, `UDEA-MG-005` forbids it on a shipped game's classpath, and it drags
// kotlin-reflect at a version above the stdlib pin - which is a `ClassNotFoundException:
// KotlinGenericDeclaration` out of the first class whose initialiser touches reflection.
// The name is `UdeaStdlibPin.ASSET_DAEMON_RUNTIME`, which is where it is classified as a pinned
// classpath; the two must not drift into two names for one configuration.
val assetDaemonRuntime: Configuration by configurations.creating {
    resolutionStrategy.force("org.jetbrains.kotlin:kotlin-reflect:${libs.versions.kotlin.get()}")
}

dependencies {
    // Compile-time only: `Phase2Demo` names `AssetDaemon`, and nothing on this module's ordinary
    // test runtime classpath may.
    testCompileOnly(project(":udea-assets-compiler"))
    assetDaemonRuntime(project(":udea-assets-compiler"))
}

val udeaPhase2Demo = tasks.register<JavaExec>("udeaPhase2Demo") {
    group = "udea"
    description = "Boots a headless game with a warm asset daemon and the agent surface bound, " +
        "and blocks. -Pudea.agent.port=N"
    val demoClasspath = sourceSets.test.get().runtimeClasspath + assetDaemonRuntime
    classpath = demoClasspath
    mainClass.set("dev.wildware.udea.agent.host.demo.Phase2Demo")
    // The daemon takes its repo root, asset root and script compile classpath as arguments rather
    // than reading its environment, so the caller has to supply all three. The script classpath is
    // *this task's* classpath: a script compiled against anything else fails with "Unresolved
    // reference 'spriteSheet'".
    val repoRoot = rootProject.layout.projectDirectory.asFile.absolutePath
    val port = providers.gradleProperty("udea.agent.port")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            buildList {
                add("-Dudea.repoRoot=$repoRoot")
                add("-Dudea.assetsCompiler.classpath=${demoClasspath.asPath}")
                port.orNull?.let { add("-Dudea.agent.port=$it") }
            }
        },
    )
}

/**
 * The Phase 2 exit criterion, as a gate on the root's `udeaLatencyBudgets`.
 *
 * Its own `Test` task and not part of `test`, for the same reason `udea-agent`'s `udeaAssetTools`
 * is: the daemon it needs carries the Kotlin scripting host, and that host reaches this JVM
 * through `assetDaemonRuntime` alone. Excluded from `test` so it runs once, on the classpath that
 * can actually load `AssetDaemon`.
 *
 * ## Why it is no longer on `check` (issue #175)
 *
 * Its budget is one second from an agent's HTTP request to the running world reporting the new
 * value, and the whole path - JSON, socket, bridge queue, barrier, tick boundary - is inside the
 * measurement. Every part of that is a wall-clock duration on a shared machine. Measured inside
 * `./gradlew build` on a GitHub runner it took 1485ms against the 1000ms budget, on a branch that
 * had not touched the agent host; it was the `build` job's first red on both `ubuntu-latest` and
 * `windows-latest`. It is now measured by the `latency-budgets` job, serially, with nothing else
 * on the runner.
 *
 * The second half of this test - a typo'd reference is refused with a file, a line, a column and
 * a did-you-mean - is a correctness claim rather than a latency one, and moving the task moves it
 * too. It is not weakened by that: the `latency-budgets` job runs on every push, on both runner
 * images, which is more often than the criterion has ever actually been checked before, because
 * before #170 unblocked `:moba` the `build` job never reached this task at all.
 */
val phase2ExitTests = "dev.wildware.udea.agent.host.Phase2ExitTest"

val udeaPhase2Exit = tasks.register<Test>("udeaPhase2Exit") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Spec 6 Phase 2 exit: an agent's patch reaches the running game in under a " +
        "second over HTTP, and a typo'd reference is refused in under 300ms."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath + assetDaemonRuntime
    filter { includeTestsMatching(phase2ExitTests) }
    // The measured numbers belong in the log of whatever machine is slow, exactly as the other
    // budget tasks in this repository do it.
    testLogging.showStandardStreams = true
}

tasks.test {
    filter { excludeTestsMatching(phase2ExitTests) }
}

// `AssetDaemon` takes its repo root and its script compile classpath as arguments rather than
// reading its environment, so a test JVM has to be told both. The classpath is **this task's
// own** - the two differ for `udeaPhase2Exit`, which is the only task here that has a daemon at
// all, and a script compiled against the wrong one fails with "Unresolved reference 'spriteSheet'".
tasks.withType<Test>().configureEach {
    systemProperty("udea.repoRoot", rootProject.layout.projectDirectory.asFile.absolutePath)
    doFirst {
        systemProperty("udea.assetsCompiler.classpath", classpath.asPath)
    }
}
