plugins {
    id("udea.kotlin-library")
    // The engine's own toolsets go through the same `@AgentTool` KSP pass every game's do.
    // There is one mechanism on the agent surface, not an engine one and a game one - see
    // `EngineToolModules` for what that took and for the one thing it deliberately does not do.
    id("com.google.devtools.ksp") version libs.versions.ksp.get()
}

dependencies {
    api(project(":udea-core"))

    // `@AgentTool` and `@Arg`, on the toolsets in `tools/`. `compileOnly` is not an option:
    // the annotations are BINARY-retained, so they are on this module's own bytecode, and a
    // consumer compiling against `WorldToolset` needs them resolvable.
    implementation(project(":udea-annotations"))

    // The processor over *this module's* main source set. The reverse edge exists too -
    // `udea-codegen`'s tests compile against `udea-agent` so the generated code they exercise
    // is dispatched through the real `ToolIndex` - and the two do not form a task cycle:
    // `:udea-codegen:compileKotlin` needs nothing from here, and it is that compilation, not
    // `:udea-codegen:test`, that `:udea-agent:kspKotlin` waits on.
    ksp(project(":udea-codegen"))

    // The stable rule ids, so the runtime description gate in `ToolIndex.Builder.build` reports
    // under exactly the id the KSP checker and the K2 checker report under (spec 5: one defect,
    // one name, wherever it surfaces). `implementation` and not `api`: nothing in this module's
    // public surface names a `UdeaRule`, only its refusal messages quote one.
    implementation(project(":udea-diagnostics"))

    // Real Fleks components on real entities, a wired GameContext, and the ArrayFieldStore /
    // ArrayBitWriter pair, so the hand-written test replicators can implement the whole frozen
    // contract rather than only the two methods the agent surface calls.
    testImplementation(testFixtures(project(":udea-core")))
}

// --- the agent surface's own codegen ---------------------------------------------------------
//
// `udea.moduleName` is the only option set, and the omissions are the design:
//
// - **no `udea.toolModuleService`**, so no `META-INF/services` entry is emitted for the engine's
//   toolsets. `ToolIndex.Builder.discover()` would then find them in *every* process with
//   `udea-agent` on the classpath, and `build()` refuses a tool whose toolset instance was never
//   registered - so a host that wires two toolsets, and `udea-codegen`'s own fixture tests which
//   wire a `Playground` and nothing else, would fail at start-up. `EngineToolModules` assembles
//   the modules from the generated objects by hand instead, which is what lets a host take four
//   of the five.
// - **no `udea.stateModuleService`**, because this module declares no `@AgentState`.
// - **no `udea.projectComponents`**, because it declares no `@Replicated` component either, so
//   it mints no component type id and is not a participant in the wire contract. The processor
//   only demands the id space of a module that emits protocol identity.
//
// The tool manifest fragment IS emitted - `udea/UdeaAgent-agent-tools.json` - and
// `EngineToolSurfaceTest` reads it, so a reworded engine tool description is a reviewable diff
// exactly as it is for a game's own tools.
ksp {
    arg("udea.moduleName", "UdeaAgent")
}

// --- Phase 1 budget gate (spec 6, Phase 1 exit) ----------------------------------------------
//
// "digest <0.3ms at 500 entities" is an exit criterion, so it is a CI gate and not an advisory
// print, wired exactly like the Phase 0 budgets in `udea-core`: its own task on `check`, and
// excluded from `test` so a normal run does not pay for it twice. The numbers live in
// `DigestBudgets`, where moving one is a diff a reviewer sees.
//
// If it fails on slower hardware the remedy is `DigestBudgets.REBUILD_INTERVAL_TICKS` - build
// the digest less often - never a wider budget. A budget that moves when it is missed measures
// nothing.

val budgetTestClasses = listOf(
    "dev.wildware.udea.agent.state.DigestBudgetTest",
    "dev.wildware.udea.agent.query.EntityQueryBudgetTest",
)

tasks.named<Test>("test") {
    budgetTestClasses.forEach { filter.excludeTestsMatching(it) }
}

/** The digest under 0.3ms at 500 entities, and allocating nothing but the document. */
val udeaDigestBudget = tasks.register<Test>("udeaDigestBudget") {
    group = "verification"
    description = "Gates the Tier-0 digest at 500 entities: <0.3ms median, zero render allocation."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("dev.wildware.udea.agent.state.DigestBudgetTest")
    // The measured numbers are the point of the task, so they go to the build log rather than
    // into a report nobody opens.
    testLogging.showStandardStreams = true
}

/** A query over 500 entities returning 20, under 1ms and with bounded allocation. */
val udeaQueryBudget = tasks.register<Test>("udeaQueryBudget") {
    group = "verification"
    description = "Gates entity query at 500 entities returning 20: <1ms median, bounded allocation."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("dev.wildware.udea.agent.query.EntityQueryBudgetTest")
    testLogging.showStandardStreams = true
}

tasks.named("check") {
    dependsOn(udeaDigestBudget, udeaQueryBudget)
}
