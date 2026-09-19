# Build and Verification

One command checks everything: `./gradlew build`, with no `-x` exclusions. Besides compiling and testing, `check` runs a set of gates, named `udeaVerify*` and similar, that fail the build when a rule of the project is broken: a module arrow pointing the wrong way, a frozen contract edited, a wall-clock read in simulation code, a stale document. Each gate reports a named rule. A few measurements that need a quiet machine or a real GPU are deliberately left off `check` and run by name or in their own CI job.

A plain picture: a factory line with inspection stations. Most inspections happen on every item as it passes. A few, such as a stress test that needs the whole room quiet, happen in a separate test room so they do not slow the line or give false readings.

## Building

```
./gradlew build
```

What you need, and what trips people up:

- **JDK 21** to run Gradle. Gradle 8.13 does not run on JDK 25. The Kotlin toolchain inside the build is provisioned separately.
- **The Android SDK**, from `ANDROID_HOME` or an untracked `local.properties` (`sdk.dir=...`). Never commit `local.properties`.
- **No art step.** `moba`'s character sprites are staged by `:moba:game:udeaStageCharacterArt` on every build. A `UDEA0032` about a `spritePath` is a real defect.
- **iOS** builds only on macOS. Off macOS, `sh gradlew :<module>:allTests` skips iOS; the `ios-tests` CI job runs it.
- If the checked-in wrapper has lost its executable bit on your machine, run it as `sh gradlew build`. CI runs `chmod +x ./gradlew` for the same reason.

The rule in `CLAUDE.md` and `AGENTS.md` is that the repository is green, so any red task after your change is your change. The exception is a wall-clock latency budget, which can fail on a loaded machine; re-run it alone before believing it.

[Getting Started](Getting-Started) has the step-by-step setup.

## The gates on `check`

Every gate below runs as part of `./gradlew build`.

### Structure

| Gate | Where | What it catches |
|---|---|---|
| `udeaVerifyModuleGraph` | every `udea-*` and `moba` project, plus a root aggregate | A dependency arrow that breaks a `UDEA-MG-*` rule: GL outside `udea-render`, LibGDX anywhere, the editor on a game's classpath, a leaf module that is no longer a leaf. Every rule and its reason are in `docs/module-graph.md` |
| `:udea-render:udeaVerifyHeadless` | `udea-render` | A headless module's bytecode referencing a renderer type |
| `:udea-render:udeaVerifyNoLibGdx` | `udea-render` | Any module's bytecode referencing LibGDX, however it arrived |
| `udeaVerifyEditorAbsent` | projects with a release classpath | A `udea-editor` class or a `Gizmo` on a release classpath (`UDEA-MG-012`) |
| `udeaVerifyKotlinPin` | every Kotlin module | A resolved `kotlin-stdlib` other than the catalog's Kotlin version |
| `udeaVerifyCompilerPlugin` | every Kotlin module | `-Pudea.compilerPlugin.enabled=false` no longer keeping the K2 plugin off the compiler classpath |
| `:udea-compiler-plugin:udeaVerifyPluginOptional` | `udea-compiler-plugin` | Production code depending on a compiler plugin type, which would make the optional plugin required |

### Determinism

| Gate | What it catches |
|---|---|
| `udeaVerifyDeterminism` (root) | A bytecode scan of the simulation source sets for wall-clock reads and unseeded randomness. Exceptions live in `determinism-allowlist.txt`, with a reason each. It also pins the Fleks version the audit in `determinism-audit.md` was done against, so editing `udea-fleks` fails it until the audit is re-read |
| `:udea-gas:udeaVerifyGasTime` | `udea-gas` simulation code naming seconds, a wall clock or LibGDX |
| `:udea-gas:udeaGasAllocationBudget` | Any allocation in the attribute recompute (gated at zero bytes) |

A green scan does not prove determinism. [Tick Model and Determinism](Tick-Model-and-Determinism) explains why, and why the cross-OS replay job is the real gate.

### Contracts and generated files

| Gate | What it catches |
|---|---|
| `udeaVerifyContracts` (root) | Any file in `docs/contracts/` edited, added, removed or renamed since `docs/contracts.lock` froze it |
| `udeaCheckProtocolLock` | The generated wire protocol no longer matching a module's checked-in `net-protocol.lock` |
| `udea-codegen`'s generated-hash test | Generated sources no longer matching `udea-codegen/src/test/resources/expected-generated-hashes.txt` |

### Documents

| Gate | What it catches |
|---|---|
| `udeaVerifyAgentsMd` (root) | The module table in `AGENTS.md` not listing exactly the projects in `settings.gradle.kts`, or `AGENTS.md` not naming one of the frozen contracts (`UDEA-DOC-001`, `UDEA-DOC-002`) |
| `udeaVerifyTrelloMap` (root) | A card named in the design spec's section 9 that `docs/migration/trello-map.md` does not account for (`UDEA-DOC-003`) |
| `udeaVerifyWiki` (root) | A page in `docs/wiki/` that links to a missing page (`UDEA-DOC-004`), names a repository path in backticks that does not exist (`UDEA-DOC-005`), or writes a Gradle task path whose project is not in `settings.gradle.kts` (`UDEA-DOC-006`) |

`udeaVerifyWiki` is what keeps this wiki honest. Some details:

- Page links may be written `[text](Page-Name)`, `[text](Page-Name.md)`, `[[Page Name]]` or `[[text|Page Name]]`. A relative link with a slash in it, such as an image, must resolve from `docs/wiki/`. Links inside code are not checked.
- A backticked path is checked when it has a slash in it and either its first folder exists at the repository root or its last part looks like a file name. `...` stands for any run of folders: `udea-core/src/.../Tick.kt`. Paths under a `build/` folder, globs, and `/health`-style endpoints are skipped.
- A task path is checked anywhere, code blocks included, because a command in a code block is exactly what a reader pastes. Only the project part is checked; task names are registered by plugins and a document gate cannot see them.
- `docs/wiki/.pending` lists pages another branch is still writing, so they can be linked early. A pending page that exists is itself a failure (`UDEA-DOC-007`), so the list is deleted once the last of them lands.

The rules are in `build-logic/src/main/kotlin/dev/wildware/udea/build/WikiCheck.kt`, and `WikiCheckTest` covers each one both ways: a page that breaks it fails, and a similar page that does not break it passes.

### Assets, editor and GL

| Gate | What it catches |
|---|---|
| `udeaValidateAssets`, `udeaVerifyRelocatable` | Asset diagnostics; a generated asset document holding an absolute path from this checkout. See [Assets](Assets) |
| `:udea-assets-compiler:udeaPackGate` | A `.udeapak` that is not byte-identical when built from two checkouts |
| `:udea-agent:udeaAssetTools` | The `assets.*` tools, against a real warm daemon and a real headless game |
| `:moba:desktop:editorTest` | The editor tests for `moba`'s `editor` source set |
| `udeaGlTest`, `udeaAgentGlTest`, `udeaEditorGlTest` | The render, agent-host and editor tests that need a real Kool context and a display |

### The GL trap

`udeaGlTest`, `udeaAgentGlTest` and `udeaEditorGlTest` need a display. With no display they **skip** and the build stays green, because `-Pudea.render.requireGl` defaults to `false`. So a green build on a machine with no display says nothing about rendering. When a change touches `udea-render`, the render half of `udea-agent-host`, `udea-editor` or anything that opens a GPU context, run them for real under a virtual display:

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  ./gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true
```

With `-Pudea.render.requireGl=true` a missing display is a failure, not a skip. CI's `gl-tests` job runs them that way.

## Frozen contracts and their locks

The frozen contracts in `docs/contracts/` are cross-module agreements: the `Replicator<T>` interface, the agent tool surface and the asset index. A late change to one breaks several modules at once, silently. So:

- `docs/contracts.lock` holds a SHA-256 of every file in `docs/contracts/`, and `udeaVerifyContracts` fails the build if one moves.
- If your work needs a contract to change, **stop and say so**. Do not change it and carry on.
- When a change is genuinely agreed, `./gradlew udeaWriteContractLock` rewrites the lock, and the lock is committed in the same change. There is deliberately no `-P` flag for this, because a flag could be passed to a whole `build` and re-baseline the freeze by accident.

Other checked-in files that record an agreed ordering or a baseline, each rewritten only by its own task:

| File | Rewritten by | Why it exists |
|---|---|---|
| `*/net-protocol.lock` | `udeaWriteProtocolLock` | Component ids, field order and widths: the wire contract. Ids come from sorted names, so adding a component renumbers those after it |
| `udea-codegen/src/test/resources/expected-generated-hashes.txt` | `sh gradlew :udea-codegen:test -Pudea.updateGeneratedHashes=true` | Hashes of generated code, so a change to generated output is seen and reviewed |
| `determinism-allowlist.txt` | by hand, with a reason per entry | The only exceptions the determinism scan accepts |
| `moba/desktop/src/test/resources/fixtures/*.udearep` | `sh gradlew :moba:desktop:udeaWriteReplayFixture` | The recordings the replay-equality job replays |

A merge conflict in a lock file is resolved by running its task again in the merged tree, never by editing text: two branches that each add a component can merge with no textual conflict and still produce a lock that agrees with neither.

## Gates that are not on `check`

These measure wall-clock time or need a real GPU, so running them beside a parallel build would measure the machine, not the code. Run them by name, on a quiet machine:

| Task | What it measures |
|---|---|
| `./gradlew udeaLatencyBudgets --no-parallel --max-workers=1` | Every wall-clock latency budget, together. `docs/budgets.md` lists each number and why |
| `./gradlew :udea-assets-compiler:udeaDaemonBudget` | A warm validate and reload of one edited script, under 300ms |
| `./gradlew :udea-core:udeaSnapshotBudget` | Snapshot capture at 1000 entities: under 1ms, zero allocation, under a 64MB ring |
| `./gradlew :udea-agent:udeaDigestBudget`, `:udea-agent:udeaQueryBudget` | The agent's world digest and entity query at 500 entities |
| `./gradlew :moba:desktop:runUdpProof` | Three OS processes over real UDP, including a lossy leg |
| `./gradlew :moba:desktop:runLaneShot` | Lane screenshots, which need a real GPU context |
| `./gradlew udeaVerifyRelease` | No agent class in the shipped `moba:desktop` artifact or on its release runtime classpath (`UDEA-REL-001`, `UDEA-REL-002`) |

Do not "fix" one of these by wiring it into `check`. Each task's KDoc says why it is kept off.

`build-logic` is an included build, so the root `build` compiles it but never runs its tests. Run them with `./gradlew -p build-logic check`.

## CI

Everything is in one workflow, `.github/workflows/ci.yml`. Its jobs:

| Job | What it does |
|---|---|
| `build` | `./gradlew build udeaVerifyModuleGraph` on Linux and Windows |
| `ios-tests` | The iOS tests of the multiplatform modules, on a macOS runner |
| `latency-budgets` | `udeaLatencyBudgets` on a runner of its own |
| `gl-tests` | The GL tests with `requireGl=true`, so they pass or fail rather than skip |
| `build-logic` | `build-logic`'s own tests |
| `agents-md` | `udeaVerifyAgentsMd`, `udeaVerifyTrelloMap` and `udeaVerifyWiki` |
| `plugin-disabled` | The whole build with the K2 plugin switched off, plus `udeaVerifyPluginOptional` |
| `checkers-fire` | A `@Net val` in a real module fails with the right rule id at the right symbol, and compiles with the plugin off |
| `kotlin-upgrade-probe` | A scheduled early warning for Kotlin version changes |
| `clean-build-budget`, `ksp-incremental-budget` | Build-time regressions, compared rather than timed against an absolute number |
| `bridge-conformance` | The vendored `game-bridge-mcp` client driven against a live headless game |
| `determinism` | `udeaVerifyDeterminism` and the float-portability probe, on two operating systems and two JVMs |
| `replay-equality`, `replay-equality-join` | The real determinism gate: replays on Linux and Windows compared tick by tick. See [Replay and Time Travel](Replay-and-Time-Travel) |
| `replay-equality-nightly`, `replay-equality-nightly-join` | The same with a ten-minute recording, nightly only |

## The reviewer's list

Every change is reviewed against `docs/engineering-standards.md`. Its section 8 is a closed list of what a reviewer rejects, including: a `public` declaration nobody outside the module uses, a test that cannot fail, generated code built by string concatenation, a new field on `GameContext` without a justification, wall-clock time or unseeded randomness in simulation code, a `TODO()` or swallowed exception on a reachable path, copy-pasted logic that differs only in a constant, and GL or Kool outside `udea-render`. `AGENTS.md` adds its do-not list. Read both before writing code.

Tests assert behaviour, and a test you have not seen fail is unverified: break the code it covers, watch it go red, then put it back.

## See also

- [Architecture](Architecture)
- [Tick Model and Determinism](Tick-Model-and-Determinism)
- [Diagnostics and Compiler Plugin](Diagnostics-and-Compiler-Plugin)
- [Replay and Time Travel](Replay-and-Time-Travel)
- [Getting Started](Getting-Started)
