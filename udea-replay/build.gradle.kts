plugins {
    id("udea.kotlin-library")
    // The replay toolset goes through the same `@AgentTool` KSP pass every other toolset does.
    // `EngineToolModules` deliberately does not name these tools - see `ReplayToolModules` for
    // why a replay session is a thing only a host that has one can register.
    id("com.google.devtools.ksp") version libs.versions.ksp.get()
}

dependencies {
    // `Tick`, `WorldSnapshot`, `WorldHasher`, `DivergenceReport`, `RngService`. The whole point
    // of Phase 7 is that these already exist and a recording only has to name them.
    api(project(":udea-core"))

    // `AgentResult`, `AgentToolDef`, `ToolModule`: the bisect tools of issue #149.
    //
    // **compileOnly, and that is a release gate rather than a preference.**
    // `ReleaseRules.CLASSPATH_RULE` (UDEA-REL-002) forbids `:udea-agent` on `:moba`'s
    // `runtimeClasspath`, because the agent surface mutates the live simulation and debug-only
    // means absent from the shipped classpath rather than disabled in it. `moba` needs the
    // *recording* half of this module in `src/main` - a shipped game records matches - so an
    // `api` or `implementation` edge here would drag the agent surface into every release and
    // fail that gate.
    //
    // The consequence, stated plainly: `ReplayToolset` and `ReplayToolModules` are simply
    // unloadable in a process with no `udea-agent` on its classpath. That is the same bargain
    // `udea-agent` itself strikes with `udea-assets-compiler` for `AssetsToolset`, and it is
    // correct rather than a compromise - only a debug host serves `replay.*`, and `moba`'s
    // `agent` source set is the one classpath in that project which resolves the agent surface.
    compileOnly(project(":udea-agent"))

    // `@AgentTool` and `@Arg`, on `ReplayToolset`.
    implementation(project(":udea-annotations"))

    ksp(project(":udea-codegen"))

    // Real Fleks components on real entities and a wired `GameContext`, so a replay test drives
    // a real world through a real snapshot service rather than a mock of one.
    testImplementation(testFixtures(project(":udea-core")))

    // The agent surface is `compileOnly` above, so this module's own tests have to put it back
    // on the classpath they run against - otherwise `ReplayToolset` could not be exercised here
    // at all and the bisect tools would be proven by nothing.
    testImplementation(project(":udea-agent"))
}

// The manifest fragment is named per module, or two modules emit `udea/-agent-tools.json` and
// the second overwrites the first. No `udea.toolModuleService`: `ReplayToolset` needs a
// `ReplaySession` that only a host can build, and a `ServiceLoader` entry would make every
// process with this module on its classpath fail `ToolIndex.Builder.build`.
ksp {
    arg("udea.moduleName", "UdeaReplay")
}
