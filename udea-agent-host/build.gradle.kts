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
