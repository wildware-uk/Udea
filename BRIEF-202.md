cabf8e7

# BRIEF-202: a generated registry replaces ServiceLoader discovery

Branch `issue-202-generated-registry`, off `origin/kmp` at `0223ca6`. The change is one commit,
`cabf8e7`. This brief is committed after it.

Every artefact quoted below is a log in the session scratchpad,
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/6e1a2afd-7171-4ceb-8fca-e1da5562a013/scratchpad/`,
called `$S` from here on. Each quote is a contiguous run of lines from the file named beside it.

## 1. Evidence command

    ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
      sh gradlew :udea-codegen:test --tests dev.wildware.udea.codegen.ModuleRegistryTest --rerun

**Green on `cabf8e7`.** `$S/evidence-green.log` ends `BUILD SUCCESSFUL in 7s`, and the report
reads `tests="9" skipped="0" failures="0" errors="0"`.

**Red with the feature reverted.** I wrote this test before the processor change, against the old
processor, which still emitted `ServiceLoader` indexes. The only thing added at that point was the
option-name constant, so the test could compile. All nine tests failed (`$S/red1.log`,
`$S/red1-ModuleRegistryTest.xml`):

    ModuleRegistryTest > nothing is written where ServiceLoader would look(File) FAILED
    ModuleRegistryTest > a module with nothing to list still emits its registry, so a launcher naming it compiles(File) FAILED
    ModuleRegistryTest > the launcher registry names every module registry statically, in sorted FQN order(File) FAILED
    ModuleRegistryTest > a listed module whose registry does not exist fails the build, naming the module(File) FAILED
    ModuleRegistryTest > a launcher list that leaves out its own module is refused(File) FAILED
    ModuleRegistryTest > a module emits one registry object naming its replicators statically(File) FAILED
    ModuleRegistryTest > a registry implements the facet for every kind its module contributes(File) FAILED
    ModuleRegistryTest > a facet whose interface the build did not name is left off, not emitted against nothing(File) FAILED
    ModuleRegistryTest > a module name with no launcher list is a build error rather than a module no launcher lists(File) FAILED
    9 tests completed, 9 failed

The first failure message shows why they failed: the old processor still wrote
`META-INF/services/dev.wildware.udea.agent.StateModule`, `…ToolModule` and
`…LevelComponentModule`.

## 2. Summary

### What changed

- **`udea-core`** gains `ModuleRegistry` (`moduleName`) and `UdeaRegistry` (`modules`), in
  `dev.wildware.udea.core.registry`. `UdeaGameDef` now takes a required `registry: UdeaRegistry`.
  Level files read their components from it (`LevelComponentModule.of(registry)`), so they no
  longer ask the classpath.
- **`udea-codegen`** emits two things for each module that sets `udea.moduleName`:
  - `object <Module>ModuleRegistry : ModuleRegistry`. It also implements one facet interface per
    kind the module contributes: `NetModule`, `ToolModule`, `StateModule`, `LevelComponentModule`.
  - `object <Module>UdeaRegistry : UdeaRegistry`, which names every module registry the build
    listed, sorted by FQN.

  `ServiceIndexEmitter` became `RegistryEmitter`. No `META-INF/services` resource is written. The
  processor checks each listed module's registry by exact name
  (`Resolver.getClassDeclarationByName`) and reports a missing one as a located KSP error.
- **`build-logic`** gains `udeaModule("Gas")`, in `UdeaModuleRegistry.kt`. It stamps the module
  name on the project's consumable variants as a Gradle attribute. It also works out
  `udea.registryModules` from the project's resolved `runtimeClasspath`: this module, plus every
  component on the classpath that carries the attribute, followed transitively. `udea-core`,
  `udea-gas`, `udea-agent`, `udea-replay` and `moba` all use it. `udea-codegen`'s fixtures use
  `udeaRegistryModules(...)` against `testRuntimeClasspath`, which does not stamp the processor
  jar.
- **Consumers.** `NetRegistry.load()` became `NetRegistry.modules(registry)`.
  `ToolIndex.Builder.discover()` became `.registry(registry)`, and the same for
  `AgentStateIndex.Builder`. `MobaGame` passes `MobaUdeaRegistry`. `MobaAgent` adds
  `.registry(MobaUdeaRegistry)` to its tool index. None of moba's modules sets
  `udea.toolModuleService`, so that call adds no tool today; `/tools` is byte-identical (section 4).
- **Contract, approved by the owner (spec D7).** Changed `docs/contracts/agent-tools.md`, the "Id
  assignment" row of `AGENTS.md`, and `docs/contracts.lock`, which I rewrote with
  `udeaWriteContractLock`. The diff is one line: the `agent-tools.md` digest. Also updated, to say
  the same thing: `docs/engineering-standards.md` (section 1 "reflection" row, section 3 pattern
  row), `docs/module-graph.md`, `docs/budgets.md`, and every KDoc or comment that said
  `ServiceLoader`.

The generated `MobaUdeaRegistry` as built:

    listOf(CoreModuleRegistry, GasModuleRegistry, MobaModuleRegistry, UdeaReplayModuleRegistry)

`UdeaAgent` is not in it. `moba` takes the agent surface only on its `agent` source set, so it is
not on `runtimeClasspath`. That is the property `UdeaModuleRegistryTest` pins with a
`compileOnly` edge.

### Decisions, each commented on #202

- **Where the launcher list comes from.** The resolved runtime classpath, through a variant
  attribute.
  - Rejected: a list written by hand in the launcher. Forgetting a module would be silent again.
  - Rejected: `getDeclarationsFromPackage`, the magic package this codebase retired.
  - Rejected: a checked-in lock. It would be a second copy of the dependency declarations.
- **Facets, not one fat interface.** `udea-core` cannot name `udea-agent` types, because module
  arrows point down. A consumer picks its facet with an `is` check, which is not reflection and
  works on every KMP target.
- **`UdeaGameDef.registry` is required, not defaulted.** A default would be the one remaining way
  to build a game whose levels silently lack a module. Worlds built from the kernel alone (the
  engine tests, `NetArena`) pass `CoreUdeaRegistry`. That touched many test call sites
  mechanically.
- **Option names kept.** `udea.netModuleService`, `udea.toolModuleService` and
  `udea.stateModuleService` now gate facets rather than services. The names are unchanged so no
  build script moves. `CodegenOptions` says so.
- **ServiceLoader left in build-time code only.**
  - `udea-assets-compiler`'s `TranspiledAssetLoader`: the asset compiler and daemon load compiled
    `.udea.kts` sources in a JVM of their own, and never on a shipped runtime classpath
    (`UDEA-MG-005`).
  - `udea-codegen`'s `SymbolProcessorProvider` file: KSP's own discovery.
  - `udea-compiler-plugin`'s two Kotlin compiler service files: the compiler's own discovery.

  Checked with this command, which printed nothing:

      git grep -n "ServiceLoader\|META-INF/services" -- udea-core udea-gas udea-net udea-replay udea-assets udea-agent udea-agent-host udea-audio udea-render moba ':!*.md'

### Proven red: a module missing from the registry is a compile error

Each mutation was applied, run with `:moba:compileKotlin` or the named test, and then reverted.
The diffs are copied from `$S/mutationA.diff`, `$S/mutationB.diff` and `$S/mutationC.diff`.

**A. A module the game depends on does not generate its registry.**

    -    ksp(project(":udea-codegen"))
    +    // MUTATION A: ksp(project(":udea-codegen"))

(hunk `@@ -19,7 +19,7 @@` in `udea-gas/build.gradle.kts`.) From `$S/mutationA.log`:

    > Task :moba:kspKotlin FAILED
    e: [ksp] udea.registryModules lists module 'Gas', but dev.wildware.udea.generated.GasModuleRegistry is not on this module's classpath. The build lists every module on the runtime classpath that declared itself with udeaModule, so that module did not generate its registry: check that it applies the KSP plugin with udea-codegen and passes both of udeaModule's options.

**B. As A, with the processor's own check also removed.** This shows the static reference
catches it on its own.

    -        val missing = others.filter { name ->
    +        val missing = emptyList<String>() // MUTATION B
    +        val unused = others.filter { name ->

(hunk `@@ -423,7 +423,8 @@` in `UdeaSymbolProcessor.kt`.) From `$S/mutationB.log`:

    e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-af8703c835b0fca84/moba/build/generated/ksp/main/kotlin/dev/wildware/udea/generated/MobaUdeaRegistry.kt:19:34 Unresolved reference 'GasModuleRegistry'.

**C. The list walk stops following dependencies past the first hop.**

    -            component.dependencies.filterIsInstance<ResolvedDependencyResult>().mapTo(pending) { it.selected }
    +            if (component === root) component.dependencies.filterIsInstance<ResolvedDependencyResult>().mapTo(pending) { it.selected } // MUTATION C

From `$S/mutationC.log`:

    UdeaModuleRegistryTest > a launcher lists itself and every module on its runtime classpath, transitively, and nothing else(File) FAILED
        org.opentest4j.AssertionFailedError at UdeaModuleRegistryTest.kt:55

**Also watched fail: the contract freeze.** My first full build ran before I rewrote the lock.
`udeaVerifyContracts` then failed on the edited contract (`$S/contracts-red.log`):

    docs/contracts/agent-tools.md:1:1: error: [UDEA-FRZ-001] this frozen contract no longer matches the digest in docs/contracts.lock, so its content has changed since it was frozen.

## 3. Build output

**Baseline.** `sh gradlew build --continue` on `origin/kmp` at `0223ca6`. From
`$S/baseline-build.log`:

    BUILD SUCCESSFUL in 1m 21s
    324 actionable tasks: 215 executed, 109 from cache

The baseline had **no** failing tasks.

**This branch.** Same command on the committed tree (`$S/build2.log`):

    BUILD SUCCESSFUL in 1m 51s
    322 actionable tasks: 250 executed, 1 from cache, 71 up-to-date
    Configuration cache entry stored.
    EXIT=0

The sets of executed task names are identical: `diff` of the sorted `> Task` names from the two
logs is empty, 626 lines each. The actionable counts differ, 322 against 324. I have not traced
that difference.

- **Tasks this ticket turned green:** none. The baseline was already green. This ticket adds
  tests: `ModuleRegistryTest`, `UdeaModuleRegistryTest`, and the registry-driven test in
  `GasLevelTest`.
- **Baseline failures, unchanged:** none. There were none.

**GL tests under xvfb.** This ticket edits `udea-render` and `udea-agent-host` test call sites and
`NetArena`, so I ran them for real, forced with `--rerun` because the first attempt was
up-to-date:

    xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
      sh gradlew udeaGlTest --rerun udeaAgentGlTest --rerun -Pudea.render.requireGl=true

From `$S/gl.log`:

    BUILD SUCCESSFUL in 10s
    EXIT=0

The result XMLs, timestamped 21:59 during that run:

    udea-render/build/test-results/udeaGlTest tests 21 skipped 0 failed 0
    udea-agent-host/build/test-results/udeaAgentGlTest tests 8 skipped 0 failed 0

**Gates outside `check`.**
`udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd udeaVerifyContracts`
ran green (`$S/gates.log`, `BUILD SUCCESSFUL in 4s`). I did not run `runUdpProof` or
`runLaneShot`: the wire, the lane and the ids are unchanged.

## 4. The live game: `/tools` before and after

I ran `:moba:run -PdebugPort=N` under xvfb twice: once with the worktree checked out detached at
`origin/kmp` (port 7851), once on `cabf8e7` (port 7852). For each run I read `/health` and
`/tools`, then closed the game with `close`.

- `$S/health-before.json`: `"renderMode":"Offscreen"`.
- `$S/health-after.json`: `"renderMode":"Offscreen"`.
- Before: `9 toolsets 51 tools`.
- After: `9 toolsets 51 tools`.
- `diff toolnames-before.txt toolnames-after.txt` is empty.
- `cmp tools-before.json tools-after.json` reports the two `/tools` documents byte-identical.
- The game log line (`$S/game-after.log`): `[moba.agent] listening on http://127.0.0.1:7852 in Offscreen with 51 tools`.

## 5. Images

- `/srv/ssd1/workspace/Udea/build/debug-screenshots/issue202-live-moba-on-generated-registry.png`:
  a `render.screenshot` taken through the bridge from the running game on `cabf8e7`. It shows the
  match playing: HUD, units, health bars, ability bar. The game boots and draws on the generated
  registry. There is nothing else visual about this ticket.

## 6. Acceptance criteria

1. **No `ServiceLoader` left in runtime code; a tool module missing from the registry is a
   compile error, proven red.**
   - The runtime-module `git grep` in section 2 prints nothing.
   - Mutations A and B in section 2 show a missing module registry failing `:moba` compilation.
   - A tool module is a facet of its module's registry, so it goes missing the same way.
   - `ModuleRegistryTest.a listed module whose registry does not exist fails the build, naming the module`
     covers the same case in the harness.
   - `GeneratedAgentRegistryTest` reaches the fixture `ToolModule` and `StateModule` through the
     compiled `CodegenFixturesUdeaRegistry`.
2. **`net-protocol.lock` and `expected-generated-hashes.txt` byte-identical, or the diff
   explained.**
   - `git diff origin/kmp -- udea-codegen/net-protocol.lock moba/net-protocol.lock` is 0 bytes, so
     both locks are byte-identical.
   - `expected-generated-hashes.txt` changed. See section 7.
3. **Contract docs and `contracts.lock` updated in the same commit; `udeaVerifyContracts` green.**
   - `agent-tools.md`, the `AGENTS.md` row and `contracts.lock` are all in `cabf8e7`.
   - `udeaVerifyContracts` is green in `$S/build2.log` and `$S/gates.log`.
   - It was red before the lock rewrite (section 2).

## 7. Regenerated files

- `udea-codegen/net-protocol.lock`: not regenerated, and not changed. No component was added or
  removed, and no id moved.
- `udea-codegen/src/test/resources/expected-generated-hashes.txt`: regenerated with
  `:udea-codegen:test -Pudea.updateGeneratedHashes=true`.
  - Removed: `CodegenFixturesNetModule.kt`, `CodegenFixturesStateModule.kt`,
    `CodegenFixturesToolModule.kt`.
  - Added: `CodegenFixturesModuleRegistry.kt`, `CodegenFixturesUdeaRegistry.kt`.
  - Every replicator, tool, state and `CodegenFixturesNetProtocol.kt` hash line is unchanged.
- `docs/contracts.lock`: rewritten with `udeaWriteContractLock`. The `agent-tools.md` digest line
  is the only change.
