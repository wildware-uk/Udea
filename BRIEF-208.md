71bd703

# #208 - udea-agent (tools, dispatcher) on Kotlin Multiplatform

Branch `issue-208-agent-kmp`, off `origin/kmp` 8c733a4, with `origin/kmp` 25e516a (#216, #207) merged in at fba203f.

## 1. Evidence command

    JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem ANDROID_HOME=$HOME/Android/Sdk sh gradlew :udea-agent:wasmJsNodeTest

It runs `GeneratedToolDispatchTest` (in `commonTest`), `SpillMemoTest` and `WasmPlatformLimitsTest` on Node. It leaves `udea-agent/build/test-results/wasmJsNodeTest/*.xml`. On this SHA: 8 tests, 0 failures. `GeneratedToolDispatchTest` also runs under `jvmTest`.

**It goes red when the feature is taken away.**

- **On `origin/kmp`** (8c733a4, with only the test file added, before any port), captured before the first change:

      * What went wrong:
      Cannot locate tasks that match ':udea-agent:wasmJsNodeTest' as task 'wasmJsNodeTest' not found in project ':udea-agent'.

- **With dispatch broken** (mutation m1, below): `GeneratedToolDispatchTest`'s two dispatch tests FAILED, `8 tests completed, 2 failed`.

## 2. Summary

**What moved.** All of `udea-agent/src/main` is now `commonMain`, and `src/test` is now `jvmTest`. The module uses `udea.kotlin-multiplatform-no-ios`, so it builds for jvm, android and wasmJs. There is no iOS because `udea-core` has none (#215). Three things moved to `jvmMain`: `AssetsToolset` and `AssetToolModule`, which compile against the JVM asset daemon, and `AgentThreads` (a `ThreadFactory` only the JVM host uses). `udea-agent-host` is unchanged and stays JVM.

**JVM-only calls replaced in common code** (commented on the issue):
- Atomics now come from the stdlib `kotlin.concurrent.atomics`, with a per-file `@OptIn(ExperimentalAtomicApi::class)`.
- `synchronized(this)` became an atomicfu `SynchronizedObject` lock, the same lock `SimBarrier` uses.
- `ConcurrentLinkedQueue` became an `ArrayDeque` under that lock.
- `ConcurrentHashMap` in `AgentSessions` became `HashMap`s under one lock.
- `AgentClock.System` now uses `TimeSource.Monotonic`.
- `Math.round` became `roundToLong` (the NaN case returns earlier), and `Character.isHighSurrogate` became `Char.isHighSurrogate`.
- `javaClass.simpleName` became `::class.simpleName`.
- `EventsToolset` and `WorldToolset` each subclassed an access-ordered `LinkedHashMap`, a JVM-only API. Both copies became one common `SpillMemo`.

**Two answers Wasm cannot give, both refused honestly (not stubbed):**
- `diag.memory` reads the heap through `expect fun heapFigures()`. The JVM and Android use `Runtime`. On Wasm it returns `null`, and the tool answers the typed failure `memory_unreported`. The tool description is unchanged.
- An enum field is written by constant name, through `expect fun enumConstantsOf()`. On Wasm it returns `null`, so the write is refused as `bad_argument` naming the type. On the JVM I also fixed a latent defect: the old `current.javaClass.enumConstants` is `null` for a constant with a body, which gave an NPE. It now uses `declaringJavaClass`, and `FieldValuesEnumTest` pins it.

**KSP: two runs, the second one scoped** (commented on the issue).
- `kspCommonMainMetadata` generates every engine tool, the registry and the manifest fragment once, and that copy is compiled into all three targets.
- `kspJvm` exists only for `AssetsToolset`. KSP hands a per-target run the common sources as well. Unscoped, the JVM run generated 38 files instead of 9, and `compileKotlinJvm` failed with 28 `Redeclaration` errors (m3).
- I added one `udea-codegen` option, `udea.sourceSet`. When it is set, the processor only processes declarations under `src/<sourceSet>/` and writes no registry, lock or manifest.
- A second defect was found while mutating. The option alone is **not a tracked input** of `KspAATask`: removing it left `kspKotlinJvm` UP-TO-DATE, and even FROM-CACHE with the other setting's files. The build script now also declares it through `inputs.property`, in commit 71bd703.
- Consequence: the `UdeaAgent-agent-tools.json` fragment no longer lists the nine `assets.*` tools. `/tools` is unaffected, because the host builds it from `ToolIndex` (see criterion 2). `EngineToolSurfaceTest` now checks `AssetToolModule` against the JVM run's generated objects.

**A second codegen fix.** `ToolEmitter` wrote `import java.lang.UnsupportedOperationException` into every `ContextualToolDef`, which does not compile in `commonMain`. It now names `kotlin.UnsupportedOperationException`. `GeneratedSourceShapeTest` gained a check that no generated file names a `java.`/`javax.` type, including a control sample for each direction.

**Other.**
- `udea-replay` `ReplayFixtures.kt`: `:udea-net:test` became `:udea-net:jvmTest`, the hand-off fix.
- `ci.yml` latency-budget job: `:udea-agent:testClasses` became `:udea-agent:jvmTestClasses`. The old task no longer exists (measured: "task 'testClasses' not found"). The `ios-tests` comment now names `udea-agent`.
- One sentence each in `AGENTS.md` (multiplatform paragraph) and `docs/module-graph.md` (the `udea-agent` row). Both were merged with #207's edits to the same lines.
- `ModuleSources.mainSources` (the source-scan helper behind `AgentModuleBoundaryTest` and `NoReflectionInQueryPathTest`) now reads every `*Main` source set. Before, it read `src/main`, which no longer exists: the scans would have read zero files, and the "scan reads the whole module" guard caught that (3 red tests before the fix).

**Decisions left as they are** (commented on the issue):
- `udea-replay`'s `replay.*` toolset stays in `jvmMain` with `kspJvm`.
- `docs/contracts/agent-tools.md` still says the agent types are "Declared in `udea-agent`'s `src/main`". That is now `src/commonMain`. It is a stale location, not a shape change. **I did not edit the frozen contract.**

**Not touched: GL.** `udea-agent` is headless, and the render half of `udea-agent-host` is unchanged, so the xvfb GL run was not needed and was not run.

## 3. Build output

Baseline, `origin/kmp` 8c733a4, before any change (`scratchpad/baseline-build.log`):

    BUILD SUCCESSFUL in 1m 30s

Baseline `sh gradlew -p build-logic check --continue`:

    OuterBuildInputsTest > every repository file a build-logic test names is a declared input of this task() FAILED
    311 tests completed, 1 failed

Branch at 71bd703: `sh gradlew build --continue` (the `ANDROID_HOME` and `JAVA_HOME` above were set):

    BUILD SUCCESSFUL in 24s
    620 actionable tasks: 25 executed, 9 from cache, 586 up-to-date

The same command on the merged tree before the last two commits executed far more work: `BUILD SUCCESSFUL in 44s`, `620 actionable tasks: 60 executed, 15 from cache, 545 up-to-date`. Before the merge, on 15f1a77's content, it gave `BUILD SUCCESSFUL in 1m 24s`.

`sh gradlew -p build-logic check --continue` on 71bd703: `BUILD SUCCESSFUL in 1m`. #216 was merged in, so the baseline failure is gone.

`sh gradlew udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd udeaVerifyMigration udeaLegacyReport --continue`: `BUILD SUCCESSFUL in 6s`.

- **Tasks this ticket turned green:** `:udea-agent:wasmJsNodeTest` (did not exist before), `:udea-agent:compileKotlinWasmJs`, `:udea-agent:compileAndroidMain`, `:udea-agent:testAndroidHostTest`.
- **Baseline failures, unchanged:** none in the root build. The build-logic `OuterBuildInputsTest` was fixed by #216, not by this ticket.
- `:udea-agent:jvmTest`: 282 tests in the result XMLs, 0 failures. `udeaAssetTools` (9 tests), `udeaDigestBudget` and `udeaQueryBudget` ran green in `:udea-agent:check` on this box.

## 4. Images

None. The owner's ruling is Kool-only evidence with no LibGDX screenshots. Criterion 2 is a `/tools` transcript in Headless mode, which has no render context.

## 5. Criteria

**1. "`udea-agent` builds for all four targets and dispatches a generated tool in a `wasmJsNodeTest`."**
"All four" is jvm, android and wasmJs, per the lead's ruling. iOS waits for #215. `compileKotlinJvm`, `compileAndroidMain` and `compileKotlinWasmJs` are all in the green build. `GeneratedToolDispatchTest` runs on Node. From `udea-agent/build/test-results/wasmJsNodeTest/TEST-dev.wildware.udea.agent.dispatch.GeneratedToolDispatchTest.xml`:

    <testcase name="a generated tool is dispatched and moves the simulation by exactly what it was asked[wasmJs, node]" classname="dev.wildware.udea.agent.dispatch.GeneratedToolDispatchTest" time="0.002"/>
    <testcase name="the generated argument parser refuses text that is not a number and steps nothing[wasmJs, node]" classname="dev.wildware.udea.agent.dispatch.GeneratedToolDispatchTest" time="0.0"/>
    <testcase name="the index carries the generated description, not an empty one[wasmJs, node]" classname="dev.wildware.udea.agent.dispatch.GeneratedToolDispatchTest" time="0.001"/>

The tool is the KSP-generated `TimeToolsetStepTool`. It is indexed by `EngineToolModules` and submitted through `AgentBridge` and `SimHarness`, and the tick moves by exactly 7.

**2. "`/tools` on the JVM host lists the same tools with the same descriptions as `master`."**
I compared live `/tools` responses, Headless, from each tree. `master` is `origin/master` 409c044, extracted with `git archive` into the scratchpad and run there.

- `:udea-agent-host:udeaPhase1Demo`: the logs say `[phase1-demo] listening on http://127.0.0.1:7853 with 30 tools` (branch) and `…:7854 with 30 tools` (master). `cmp tools-master.json tools-branch.json` printed `IDENTICAL` (25981 bytes each).
- `:moba:run -PdebugPort=… -Pudea.render.mode=Headless`, which is the game with all 51 tools including `assets.*`, `replay.*`, `render.*` and `input.*`: both logs say `listening on … in Headless with 51 tools`. `cmp moba-tools-master.json moba-tools-branch.json` printed `IDENTICAL` (42956 bytes each). The diff is empty.
- On the branch instance I also dispatched two JVM-only tools. `assets.list` came back `"ok":true` with an 18212-character result spilled to `cap_0000`. `diag.memory` came back `"ok":true,"result":{"usedBytes":96084496,…,"processors":24}`. Both are saved in `scratchpad/moba-state-after-memory.json`.
- `cmp` was not only seen agreeing. The two branch documents (Phase 1 demo against moba) are 25981 and 42956 bytes, so they differ by construction.

**3. "`gamebridge.json` and the vendored bridge-client assertion unchanged and green."**
`git diff origin/kmp -- gamebridge.json .github/conformance` is empty. I ran the vendored client against the branch's live Phase 1 instance, the same way the CI `bridge-conformance` job does. From `scratchpad/conformance-branch.log`:

    ✔ health identifies this as a game surface (33.110664ms)
    ✔ commandAndSync takes the completedCommandId path, not the frame fallback (49.216451ms)
    ✔ time.step advances exactly the ticks asked for, confirmed (289.911546ms)
    ✔ without completedCommandId the client degrades to frames and says so (126.920243ms)
    ✔ every published tool survives the bridge's manifest normalisation (2.272932ms)
    ✔ every tool's inputSchema is a JSON Schema object a strict client accepts (42.040698ms)

The vendor half gave `ℹ pass 4` / `ℹ fail 0`, and the contract half gave `ℹ pass 6` / `ℹ fail 0`. After the run, `GET /command?cmd=close` returned `{"accepted":true,…}` and the port went quiet. `UdeaAgentPluginTest` and `AgentEntryPointTest`, the Kotlin-side `gamebridge.json` tests, are in the green build.

## 6. Mutations

Each diff below is the literal `git diff` of the run, saved as `scratchpad/mN.diff`. Each was reverted with `git checkout` afterwards.

**m1: dispatch broken.** Run: `:udea-agent:wasmJsNodeTest`. Result: `GeneratedToolDispatchTest` "a generated tool is dispatched…" FAILED, "the generated argument parser refuses…" FAILED, `8 tests completed, 2 failed`.

    -        synchronized(commandsLock) { commands.addLast(command) }
    +        Unit

**m2: emitter back to its pre-port `java.lang` literal.** Run: `:udea-codegen:test --tests '*GeneratedSourceShapeTest*' :udea-agent:wasmJsNodeTest --continue`. Result: "no generated source names a JVM-only type…" FAILED, and `:udea-agent:compileKotlinWasmJs` FAILED with `Unresolved reference 'java'` in five generated tools.

    -                    AgentNames.UNSUPPORTED_OPERATION,
    +                    UnsupportedOperationException::class,

**m3: JVM KSP run unscoped** (`udea-agent/build.gradle.kts`). Run: `:udea-agent:kspKotlinJvm --rerun :udea-agent:compileKotlinJvm --no-build-cache`. Result: `compileKotlinJvm` FAILED with 28 `Redeclaration` errors.

    -    kspConfig.processorOptions.put("udea.sourceSet", "jvmMain")
    -    inputs.property("udea.sourceSet", "jvmMain")
    +    // m3
    +    // m3

`--rerun --no-build-cache` is needed here because this box's local build cache holds an entry from before the `inputs.property` fix, stored under the key this mutation produces. Without the flags, the same diff came back FROM-CACHE and green.

**m4: scope admits everything.** Run: `:udea-codegen:test --tests '*PlatformSourceSetTest*'`. Result: blocked earlier, because `:udea-agent:compileKotlinJvm` FAILED with 26 `Redeclaration` errors. `udea-codegen`'s tests compile against `udea-agent`, so the unit test did not get to run under this mutation.

    -        val required = segment ?: return true
    -        return file != null && file.filePath.replace('\\', '/').contains(required)
    +        return true
    +

**m5: a malformed `udea.sourceSet` accepted.** Run: the same `PlatformSourceSetTest` command. Result: "a malformed source set name is refused rather than matching nothing" FAILED, `3 tests completed, 1 failed`.

    -        if (CodegenOptions.SOURCE_SET_FORMAT.matches(sourceSet)) return true
    +        if (sourceSet.isNotEmpty()) return true

**m6: Wasm heap zeros, and a spill memo without recency.** Run: `:udea-agent:wasmJsNodeTest`. Result: "diag memory refuses with a typed error rather than reporting zeros" FAILED, and "the least recently used text is the one forgotten" FAILED, `8 tests completed, 2 failed`.

    -internal actual fun heapFigures(): HeapFigures? = null
    +internal actual fun heapFigures(): HeapFigures? = HeapFigures(0L, 0L, 0L, 0)
    -        handles.remove(text)?.let { handle ->
    -            handles[text] = handle
    -            return handle
    -        }
    +        handles[text]?.let { handle -> return handle }

**m7: enum constants on the pre-port JVM call, and a guessing Wasm side.** Run: `:udea-agent:wasmJsNodeTest :udea-agent:jvmTest --tests '*FieldValuesEnumTest*' --continue`. Result: on Wasm, "an enum slot is not written by guessing…" FAILED. On the JVM, "a slot holding a constant with a body still lists its siblings" FAILED.

    -    value.declaringJavaClass.enumConstants.asList()
    +    value.javaClass.enumConstants.asList()
    -internal actual fun enumConstantsOf(value: Enum<*>): List<Enum<*>>? = null
    +internal actual fun enumConstantsOf(value: Enum<*>): List<Enum<*>>? = listOf(value)

## 7. Regenerated files

- **`expected-generated-hashes.txt`:** regenerated with `:udea-codegen:test -Pudea.updateGeneratedHashes=true`. Exactly one line changed, `TimelineAdvanceTool.kt` (7ddbde82… became ab342d2d…), because its import is now `kotlin.UnsupportedOperationException`. No ids moved.
- **`net-protocol.lock`:** untouched. No replicated component was added or removed.

Scratchpad for every log and diff above: `/tmp/claude-1000/-srv-ssd1-workspace-Udea/bd7d6b62-8578-42ab-a77f-a4ae709dcb42/scratchpad/`.
