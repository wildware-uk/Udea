# Udea — agent brief

A Kotlin/Kool/Fleks engine built so agents can do most of the work of making a game with it.
Package root `dev.wildware.udea`. Kotlin 2.4.20, KSP 2.3.12, Gradle 8.13, JDK 21.

Three of those four moved in issue #186, and two of the moves change a shape rather than a
number. KSP has left the `<kotlin>-<ksp>` scheme — from 2.3.0 it publishes one version of its
own and names no compiler — so a KSP version can no longer be read off the Kotlin version. And
the JDK went 17 to 21 because the ComposeGL artifacts published then were Java 21 bytecode
(`org.gradle.jvm.version = 21` in their module metadata, class-file major 65 throughout), which
is a resolution failure long before it is a compile failure. `gradle/libs.versions.toml` is the
authoritative source for all of it; `UdeaVersions` mirrors the two that build logic needs as
constants and `UdeaVersionsTest` is what stops the mirror drifting.

Four documents, in order of authority:

1. **`docs/engineering-standards.md`** — the charter. Binding on every `udea-*` module and on
   `moba`. Section 8 is the reject list a reviewer works from. Read it before writing code.
2. **`docs/superpowers/specs/2026-08-22-udea-ai-native-rewrite-design.md`** — the design. What
   to build and why, in eight phases.
3. **`docs/superpowers/specs/2026-09-16-kool-kmp-port-design.md`** — the port to Kool rendering
   and Kotlin Multiplatform (epic #199). Its decisions D1-D12 are settled; where it and the
   design above disagree about rendering or targets, it is the later word.
4. **This file** — orientation and rules. Not a tutorial, not API docs.

**Branches.** `master` is the integration branch: work branches from `origin/master` and merges
back into it. The port was done on `kmp`, which merged into `master` in issue #214 and is
retired; `example` was retired before it.

`docs/contracts/` holds the frozen contracts. **Frozen means frozen**: if your work needs one
to change, stop and say so. Do not change it and carry on. `./gradlew udeaVerifyContracts` fails
the build when one of them moves — see "Frozen contracts" below for the deliberate route.

---

## Do not

- **No `by net(...)` delegate.** Dead. Replication is capture-and-diff over a generated
  `Replicator<T>`.
- **No separate snapshot codec.** One `Replicator<T>` per component serves delta replication,
  snapshot capture, snapshot restore and the agent's field access. Four things that cannot
  disagree about what an entity is.
  **Level files are not snapshots.** Runtime snapshots - rewind, replication, desync reports -
  stay on `Replicator<T>`. A level file (`.udealevel`, issue #191) is saved content: it is Fleks'
  own `world.snapshot()` encoded with kotlinx CBOR through `UdeaGame.levels`, over the
  `@Serializable` components `udea-codegen` lists per module, plus the `NetId` bindings, clock,
  random streams and each module's `LevelSection`. It stores field names so it survives a
  component changing, and it never feeds rewind or the wire.
- **No setter instrumentation for dirty tracking.** In-place `Vector2` mutation defeats setters.
  Capture-and-diff, always.
- **No wall clock in simulation.** `System.currentTimeMillis`, `nanoTime`, `Instant.now` are
  forbidden inside `Simulation.step()`. Time is `SimClock`, denominated in `Tick`.
- **No unseeded randomness in simulation.** `Math.random` and `Random.Default` are forbidden.
  Use `RngService` and its named stream.
- **No LibGDX.** It left the tree in issue #213, and `UDEA-MG-009` fails the build if any
  project resolves a `com.badlogicgames` artifact again. Rendering is Kool, inside `udea-render`.
- **No reflection on a per-tick path**, no `TODO()` on a reachable path, no swallowed
  exception, no generated code built by string concatenation, no bare `Int`/`Long`/`String`
  for a domain concept.

---

## Modules

Arrows point downward only. A module may depend on modules below it in this table, never above.

| Module | Purpose |
|---|---|
| `udea-annotations` | Zero-dependency leaf: `@Net`, `@Sim`, `@Q`, `@Replicated`, `@AgentTool`, `@Arg`, and the gizmo handles `@PositionHandle`, `@SizeHandle`, `@RotationHandle`, `@ScaleHandle`, `@RadiusHandle`, `@RangeHandle` |
| `udea-diagnostics` | Zero-dependency leaf: `UdeaDiagnostic`, `Severity`, `SourceSpan`, `Fix`, rule ids, the JSON report |
| `udea-codegen` | The KSP2 processor and KotlinPoet emitters; owns id assignment |
| `udea-compiler-plugin` | The K2 FIR/IR plugin: checkers, KDoc propagation, gated declaration synthesis |
| `udea-fleks` | Fleks 2.14, the ECS, vendored as source so it has iOS targets. Third-party, MIT; not refactored |
| `udea-core` | Headless kernel. **No GL on the compile classpath** |
| `udea-assets` | Runtime asset model and `.udeapak` reader |
| `udea-assets-compiler` | Build-time only. **Zero Gradle types** |
| `udea-gas` | Abilities, attributes, effects — tick-denominated |
| `udea-net` | Transports, baselines, relevancy, prediction, RPC |
| `udea-physics2d` | 2D physics: Box2D 3 through `box2d-jni`, behind `udea-core`'s `PhysicsWorld`. Headless (no Kool, no GL), `jvm` and `android` only because that is what `box2d-jni` publishes; no Box2D type in any public API. It is also the ground plane of a 3D game: a dynamic body drives its entity's `Transform3D.x/y/rotationZ`, and a kinematic or static one follows it (#247) |
| `udea-render` | **The only module that touches GL**. Kool stays inside it (spec section 3), and so do the ComposeGL UI host (`UiLayer`, `CapturedUi`) and the Kool-backed `AudioDevice` (`koolAudioDevice`, desktop) |
| `udea-audio` | Drains `GameContext.cues` and plays sound. No GL, no `Gdx` and no Kool: playback is an `AudioDevice` SPI, `AudioDevice.Silent` is what Headless uses, and the device that makes a noise is `udea-render`'s |
| `udea-agent` | MCP tool surface and test harness — the same code path |
| `udea-agent-host` | HTTP server. Debug-only, verified absent from release |
| `udea-replay` | `.udearep` input recording, deterministic headless replay, and the bisect tools |
| `udea-editor` | The editor window: docked ComposeGL panels, the world in Scene and Game tabs (the Scene tab through its own editor camera), buttons that call `editor.*` tools, and the public gizmo API (`Gizmo`, `Handle`). Debug-only and JVM; only a game's `editor` source set may depend on it (`UDEA-MG-010`), and no editor class or `Gizmo` may reach a release classpath (`UDEA-MG-012`) |
| `udea-gradle` | Tasks, verifiers, `gamebridge.json` emission |
| `moba:game` | The example game: a 5v5 three-lane MOBA. A library - components, systems, assets and what it draws - with no entry point in it |
| `moba:desktop` | The desktop launcher: `run`, `runServer`, `runClient`, the shot mains, the proofs and the agent surface. JVM |
| `moba:android` | The Android launcher. One activity, and it boots the simulation headless because `udea-render` has no Android Kool backend yet |
| `hollow:game` | The 3D example game (epic #245): a third-person co-op survival arena in a lit forest clearing. A library like `moba:game` - components, CC0 models, the `clearing.udealevel` level, what it draws, and the server and client sessions |
| `hollow:desktop` | Hollow's desktop launcher: `run` (a listen server and its local player, `-Plevel=<path>`), `runServer` (headless), `runClient` (`host` or `join`) and `runShot`. JVM. No Android launcher in this epic |

Three rules that are cheap to break and expensive to find:

- **No GL outside `udea-render`.** `udea-core` must compile and run with no GL context at all.
- **Presentation systems are not Fleks systems.** They implement `RenderSystem` (or
  `OverlaySystem`) and live in `udea-render`, so `world.update(dt)` is pure simulation *by
  construction* rather than by convention.
- **No LibGDX anywhere.** `UDEA-MG-009` bans every `com.badlogicgames` artifact from every
  project, the GL-allowed ones included, and `udeaVerifyNoLibGdx` fails when any module's
  bytecode names a LibGDX class however it arrived - its old UI toolkit above all, since the
  interface is ComposeGL (#189).

Enforced by `./gradlew udeaVerifyModuleGraph`, applied automatically to every `udea-*` project
and every `moba` and `hollow` project. Rule ids and rationale: `docs/module-graph.md`.

**Multiplatform (the Kool/KMP port, issue #201).** A runtime module moves to KMP by applying
`udea.kotlin-multiplatform` (`jvm`, `android`, `wasmJs`, `iosArm64`, `iosSimulatorArm64`);
`udea-render` applies `udea.kotlin-multiplatform-render` (issue #211): `jvm` and `android` only, because Kool has no iOS backend (spec D2) and publishes no wasmJs artifact (issue #223).
`udea-core` is on `udea.kotlin-multiplatform`, iOS included, because Fleks is vendored as source
in `udea-fleks` (issue #215): Fleks publishes no iOS artifact at any version. `udea-fleks` is
third-party code under its own MIT licence (`udea-fleks/NOTICE.md`); do not refactor it, and an
edit to it fails `udeaVerifyDeterminism` until `determinism-audit.md` is re-read. `udea-gas`
(issue #204), `udea-replay` (issue #206) and `udea-audio` (issue #207) are on the full convention
too. `udea-net` (issue #209) and `udea-agent` (issue #208) are on
`udea.kotlin-multiplatform-no-ios` (`jvm`, `android`, `wasmJs`), because each has an `expect` with
no native `actual` yet, and each build script names it. `udea-net`'s UDP transport runs on `jvm`
and `android` through a shared `socketMain` source set, and `wasmJs` has the WebSocket client only. `udea-physics2d` is on `udea.kotlin-multiplatform-jvm-android` (`jvm`, `android`), because `box2d-jni` publishes desktop natives and an Android AAR and nothing else; the AAR puts the same API in another Java package, so the solver code lives in `src/box2dMain`, compiled into both targets, over a per-target `Box2DBindings.kt`. In `udea-audio` the
`AudioDevice` SPI, `AudioDevice.Silent` and the cue drain are `commonMain`, and a device that makes
a noise is not in it on any target: that is `udea-render`'s `KoolAudioDevice` (issue #221), common
code over Kool's `AudioClip`, with a clip loader on `jvm` only so far. In `udea-agent` the tools and dispatcher are common, the
`assets.*` toolset is `jvmMain` because the asset daemon is, and `udea-agent-host` stays JVM.
`moba` is three projects (spec D12, issue #212): `moba:game` is on `udea.kotlin-multiplatform-render`, so it builds for every target `udea-render` has, and each launcher is a single-platform project whose targets are the platform's. Two things do not cross that line yet, and both are a module's gap rather than the game's: `udea-replay` generates its registry on its JVM target alone, so `MobaReplay` lives in `moba:desktop`; and `:moba:game` runs KSP through `kspJvm` rather than `kspCommonMainMetadata`, because the Kotlin plugin creates no `commonMain` metadata compilation for a project whose targets are all JVM-family - `moba/game/build.gradle.kts` writes that out at length. Build-time modules stay on `udea.kotlin-library`. The module-graph gates govern each target's
classpath as the JVM classpath it stands for. `sh gradlew :<module>:allTests` skips iOS off
macOS; the `ios-tests` CI job runs it. The Android SDK comes from `ANDROID_HOME` or an
untracked `local.properties` (`sdk.dir=...`), which is never committed.

---

## The tick model

- **60Hz fixed simulation.** Every duration, deadline, ring slot, baseline and input stamp is a
  `Tick`. Never a float of seconds, never a wall-clock millisecond.
- **`SimClock.time` is derived** (`tick * dt`), never accumulated. Accumulating drifts.
- **Animation time is `Animator.clipTime(now)`**, derived from a start `Tick`, never accumulated
  (#241).
- **`SimBarrier` is drained at the top of `Simulation.step()`**, before any system runs. Scene
  swaps, asset hot-reload deltas, agent tool mutations and snapshot application all queue on it.
  No system ever observes a torn world, and there is one place to reason about atomicity.
- **20Hz snapshots** — every 3rd tick. **30Hz input send.** **Render is decoupled** with an
  interpolation alpha.
- **Seconds exist only in `udea-render` and audio.** An `OverlaySystem` takes `dtSeconds` and
  cannot read simulation time; the signature is the enforcement.

---

## Frozen contracts

The cross-cutting agreements from spec section 5. Each is frozen early because a late change
breaks several modules at once.

| Contract | The rule |
|---|---|
| **Serialization** | One `Replicator<T>`, two masks (`@Net`/`@Sim`), frozen in Phase 0 behind golden tests. `docs/contracts/replicator.md` |
| **Dirty determination** | Capture-and-diff, never setter instrumentation |
| **Id assignment** | One generator, sorted FQNs, checked-in `net-protocol.lock`, `u16 protoHash` in packet byte 0, generated-registry discovery: KSP emits one `<Module>ModuleRegistry` per module and one `<Module>UdeaRegistry` per launcher in sorted-FQN order, passed to `UdeaGameDef` (issue #202, no `ServiceLoader` at run time) |
| **Between-tick mutation** | One `SimBarrier`, drained at the top of `step()` |
| **Entity identity** | `NetId` (dense `u16` + `u8` generation), never a Fleks `Entity`, across snapshots, packets and tool calls. `IntArray` for O(1) resolution |
| **Time** | `Tick` is universal. `SimClock.time` is derived, never accumulated |
| **Authority vocabulary** | One family on `@Net`: `authority = Server \| OwnerPredicted \| OwnerWritable`, `lifetime = OnCreate \| Always`, `visibility = All \| OwnerOnly`, `agentWritable = false` by default |
| **Diagnostics** | One `UdeaDiagnostic`: severity, stable rule id, message, repo-relative `SourceSpan`, `assetId`, optional `Fix`. Capped at 25, root-cause-first, mandatory did-you-mean. K2 checkers emit the *same* rule ids as the asset validator |
| **Randomness** | `RngService` with named streams (`Combat`, `Loot`, `AI`, `Spawn`, `Wave`), xoshiro256\*\* with explicit state in the snapshot. Presentation gets a separately typed, wall-seeded `PresentationRandom` |

`docs/contracts/replicator.md` carries one invariant worth repeating, because breaking it is
silent: **`fieldNames[i]` == `FieldMask` bit *i* == `FieldStore` field index *i*.**
`desync_report` names the differing *field* by indexing `fieldNames` with each set bit of a mask
diff, so a misalignment does not fail — it lies.

### The gate on the freeze

`docs/contracts.lock` holds a SHA-256 of every file in `docs/contracts/`, and
`udeaVerifyContracts` runs on `check` — so `./gradlew build` fails if one of them has been
edited, or if a contract has appeared, vanished or been renamed. It is on `check` rather than
only in CI because these are documents a developer edits, and a rule only CI knows about is a
rule found after the work is done.

When a contract change is genuinely agreed, that is what the lock is for:

```
./gradlew udeaWriteContractLock
```

and commit `docs/contracts.lock` in the same change. Review the diff: it is the contract. There
is no `-P` flag for this, deliberately — a flag can be passed to a whole `build` and re-baseline
the freeze as a side effect of an ordinary one.

---

## Render modes

All three run the identical `Simulation` and differ only in whether a `Presentation` exists.
`/health` reports the mode, so an agent knows which toolsets are live before calling one.

| Mode | GL context | Window | Screenshots | Used by |
|---|---|---|---|---|
| `Headless` | none | none | typed `no_render_context` error | dedicated server, CI, `SimHarness`, fast-forward |
| `Offscreen` | real, Kool over LWJGL | hidden | full | `moba.agent` default |
| `Windowed` | real, Kool over LWJGL | visible | full | the player, and `udea-editor` |

The agent activity overlay draws only in `Windowed`, and only onto `ScreenTarget`, which
`FrameCapture` never reads. An agent must not be able to see its own narration in a screenshot
it diffs. That exclusion is structural, not a flag somebody remembers to clear.

---

## What the engine does today

The pieces a newcomer meets first, each with the issue that made it so.

- **Drawing is Kool, inside `udea-render`, on one thread.** Sprites and 3D models both:
  `Transform3D` (`udea-core`) places an entity in 3D - Z is up, a 2D position is the point on
  the ground plane, it is saved in levels, and it is `@Replicated` so the editor's tools can write it
  (#237), with every field `@Net` so a client sees it move, drawn between ticks at the render
  alpha (#246) - and `ModelRenderer`
  (`udea-render`) draws a built-in mesh or a glTF/GLB model imported as a typed asset (#240).
  A skinned model is posed from its entity's `Animator` - clip, time and crossfade, read at the
  tick plus the interpolation alpha - and skinned on the GPU; the pose is the renderer's alone,
  and a clip's time becomes seconds only there (#242).
  An `.fbx` is a model too: the asset build converts it to `.glb` with Assimp, textures
  embedded, and a broken one fails with `UDEA0039`; the converter is build-time only
  (`UDEA-MG-013`) (#244).
  Everything that touches the scene or the ComposeGL toolkit runs on the Kool render thread
  (#224); `docs/engineering-standards.md` section 2 states the rule.
- **Interface is ComposeGL, in two places that answer opposite questions.** A `UiLayer` is a
  menu or an editor panel: it draws into the window after the captured frame, so no screenshot
  sees it. A `CapturedUi` is a HUD: it draws into the captured frame, so an agent's screenshot
  sees the cooldown a player sees (#188).
- **Controls name keys.** A controls asset binds an `InputKey`, and `udea-render` owns the one
  table per backend that turns a platform key into it (#228). A key or a click the interface
  took never becomes an intent (#227, #230).
- **Levels are saved files.** `.udealevel` (#191, #192), under `moba/game/levels/` and
  `hollow/game/levels/`; a launcher takes `-Plevel=<path>`.
- **Assets hold no loops.** A `.udea.kts` with a loop, a lambda something may run more than once,
  or a function that calls itself fails with `UDEA0015`: the K2 checker in the asset compile is
  the guarantee and a syntactic first pass is the early warning (#192). The editor's Save writes
  an exact value back into the script (#195), and a value made inside a loop has no one place to
  write it.
- **Gizmos are editor-only and never ship** (#233). A handle annotation on a component
  (`@PositionHandle`, `@SizeHandle`, `@RotationHandle`, `@ScaleHandle` on the class, naming its fields;
  `@RadiusHandle`, `@RangeHandle` on a field) becomes a generated `Gizmo` in the game's `editor`
  source set, listed with the hand-written ones in `<Game>GizmoRegistry`. A misspelled or non-`Float`
  field fails the build with `UDEA0017` and a did-you-mean. A drag answers field writes and never
  mutates the world. `UDEA-MG-012` (`udeaVerifyEditorAbsent`, on `check`) fails the build when a
  `udea-editor` class or a `Gizmo` is on a release classpath.
  In the Scene tab a gizmo's handles are dragged with the mouse (#236): the built-in move, resize,
  rotate, radius and range handles (`moveHandles`, `sizeHandles`, ... on `GizmoScope`, which the
  generated gizmos call too), each drag one `editor.begin_edit` session and so one undo entry, Escape
  cancelling it. In 3D (#237) `Transform3D`'s handles are the built-in translate arrows and plane
  squares, a rotation ring per Euler angle (each ring writes its own angle alone) and scale boxes
  (`translateHandles`, `rotationRings`, `scaleHandles`), dragged through the Scene tab's 3D camera by
  holding the pointer's ray to the handle's line or plane. Snapping and world/local axes are per-project editor preferences in
  `<project>/.udea/editor-preferences.properties`, never committed; Ctrl bypasses snapping.
- **Replays are `.udearep` format 2**, which adds the recorded editor edits (#232). A recording
  with no edits is still written as format 1, and this build reads both.
- **Web is shelved** (#223, #226, owner decision of 2026-09-18). Kool 0.19.0 publishes no wasmJs
  artifact, so `udea-render` and `moba:game` have no wasmJs target; the headless modules still
  build and test on wasmJs.

---

## Driving a running game

Every Udea game exposes an MCP tool surface automatically, and there is no IDE plugin. For
`moba`, `sh gradlew :moba:desktop:run -PdebugPort=<port>` starts it `Offscreen` with the
surface on that port; the generated `gamebridge.json` launches the same task. The editor
is a screen over that tool surface, not a second implementation of it: `udea-editor`'s window
(`sh gradlew :moba:desktop:runEditor`, issue #194) turns each button into an `editor.*` call filed
under the author `editor`, so an agent calling with `session=editor` sees and undoes the same edits.
**The tool surface is the editor.** The window shows the world in a Scene tab, through the editor's
own camera, and a Game tab, the game's own picture (issue #234); `editor.screenshot` with
`view=scene` or `view=game` captures either, gizmos included, while `render.screenshot` never holds
a gizmo - the same structural exclusion as the agent overlay's. Both tabs sit in the gap the docked
panels leave and fill it at its own shape, and in an editor the game's frame is the Game tab's size,
so `render.screenshot` is too: it follows a divider drag (#234).

| Endpoint | Answers |
|---|---|
| `/health` | is it alive, which `RenderMode`, `completedCommandId` |
| `/state` | the world digest |
| `/command` | synthesised input |
| `/tools` | every generated tool, with the description a model actually reads |

`gamebridge.json`, emitted by `udea-gradle`, is what the unmodified `game-bridge-mcp` reads to
find the game. The bridge is not modified to suit Udea; Udea conforms to the bridge, and CI
asserts it against a vendored copy of the bridge's TS client.

---

## The old tree is gone

`common`, `gradle-plugin` and `example` were the previous engine. Issue #213 deleted them, with
the migration ledger and the three gates that policed them (`udeaVerifyNoLegacyDependencies`,
`udeaLegacyReport`, `udeaVerifyMigration`). `level-editor`, `idea-plugin` and `compose-ui` went
earlier (D6). None of them is coming back; `git log` has them if you need to read one.

`example-assets/` is not a module. It is the retired game's asset tree, kept for two readers:
`:moba:game:udeaStageCharacterArt` copies the licensed character art out of its `sprites/`, and
`udea-assets-compiler`'s tests use its `.udea.kts` files as their pre-migration corpus.

---

## Before you say it works

```
./gradlew build
```

No `-x` exclusions. The whole repository is green; if it is not, that is your change.

- **There is no art step.** `moba/game/assets/sprites/` is gitignored licensed art, and
  `:moba:game:udeaStageCharacterArt` copies it out of the tree that already holds it, ahead of the
  asset pipeline, on every build. So a clone builds, `git status` stays clean, and a `UDEA0032`
  about a `spritePath` is a real defect rather than a step you forgot. `docs/art-assets.md`.
- Tests assert **behaviour**. A test that cannot fail is a defect a reviewer will reject.
- Break the production code, watch the test go red, revert. A test you have not seen fail is
  unverified.
- Update this file in the same change whenever a contract or a module moves. A stale `AGENTS.md`
  is a correctness bug, not a docs nit — `./gradlew udeaVerifyAgentsMd` fails when its module
  table stops matching `settings.gradle.kts`.
