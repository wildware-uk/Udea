plugins {
    id("udea.kotlin-library")
}

dependencies {
    api(project(":udea-agent"))
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
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            val port = providers.gradleProperty("udea.agent.port").orNull
            if (port == null) emptyList() else listOf("-Dudea.agent.port=$port")
        },
    )
}
