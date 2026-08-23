plugins {
    id("udea.kotlin-library")
}

dependencies {
    api(project(":udea-agent"))

    // --- test only, and it has to stay that way -------------------------------------------
    //
    // The render toolset is declared against the `RenderControl` port; `udea-render` implements
    // the other half of that pair as `PresentationControl`, and something has to join the two.
    // Neither module may name the other: this one is headless (`UDEA-MG-002`, and
    // `RenderModuleGraphTest` fails if a headless module's bytecode names a `udea.render` type),
    // and an arrow from `udea-render` to here would put the agent surface on `moba`'s runtime
    // classpath, which `ReleaseRules.CLASSPATH_RULE` (`UDEA-REL-002`) fails a release build for.
    //
    // So the adapter belongs to whoever assembles a host out of both, and until `UdeaAgentPlugin`
    // has a plugin id that is the offscreen demo below. `testImplementation` keeps GL off this
    // module's own compile and runtime classpaths, which is what those two rules check.
    testImplementation(project(":udea-render"))

    // gdx types for the demo's own two render systems. `udea-render` declares gdx as
    // `implementation` so that GL cannot leak onto a consumer's *compile* classpath - which is
    // the rule working exactly as intended: a composition root that writes a renderer is opting
    // in, visibly, one line at a time. Test-scoped for the same reason the line above is.
    testImplementation(libs.gdx)
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
