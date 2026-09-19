# Module graph

The module tree (spec §4), the convention plugin each module is on, the old code it
replaced, and its arrows. The old tree - `common`, `gradle-plugin`, `example` and
`example:assets` - was deleted in issue #213, with LibGDX and the gates that policed the old
tree (`UDEA-LEGACY-001` and the migration ledger's `UDEA-MIG-*`). `level-editor`, `idea-plugin`
and `compose-ui` went earlier, in Phase 0 under D6. The "Replaces" column below names what each
module took over from that tree, as history.

## Convention plugins (`build-logic`)

| Plugin | For | What it gives you |
|---|---|---|
| `udea.kotlin-base` | applied by the Kotlin conventions below, never on its own | JDK 21 toolchain, `explicitApi()`, the `kotlin-stdlib` pin with `udeaVerifyKotlinPin`, the K2 compiler plugin with `udeaVerifyCompilerPlugin` |
| `udea.kotlin-library` | every JVM runtime module and `moba:desktop` | Kotlin JVM plus `udea.kotlin-base`, kotlin.test on JUnit 5. **No GL.** |
| `udea.kotlin-multiplatform` | runtime modules ported to KMP (issue #201) | Kotlin Multiplatform on `jvm`, `android` (AGP's KMP library plugin), `wasmJs` (Node), `iosArm64`, `iosSimulatorArm64`, plus `udea.kotlin-base`; kotlin.test in `commonTest`, JUnit 5 on `jvmTest` |
| `udea.kotlin-multiplatform-no-ios` | a runtime module that cannot have an iOS target yet, whose build script names why: `udea-net`, whose `webSocketEngine` has no native `actual` (issue #209); `udea-agent`, whose `enumConstantsOf` and `heapFigures` have none (issue #208). `udea-core` left it when Fleks was vendored (issue #215) | `udea.kotlin-multiplatform` without the iOS targets; switching back to `udea.kotlin-multiplatform` re-enables them |
| `udea.kotlin-multiplatform-render` | `udea-render` (spec D2, issue #211) and `moba:game`, which builds for every target `udea-render` has (issue #212) | `udea.kotlin-multiplatform-no-ios`, applied rather than copied |
| `udea.kotlin-multiplatform-jvm-android` | `udea-physics2d`, whose native library `box2d-jni` publishes for the desktop JVM and Android only | Kotlin Multiplatform on `jvm` and `android`, the same target set as the render convention but its own plugin, so the two move independently |
| `udea.jvm-test-fixtures` | a KMP module with JVM test fixtures | a `jvmTestFixtures` source set published under the `-test-fixtures` capability, so `testFixtures(project(...))` works from a JVM consumer |
| `udea.kotlin-build-tool` | `udea-codegen`, `udea-compiler-plugin`, `udea-assets-compiler` | `udea.kotlin-library` plus the exact-Kotlin-version pin (spec §7), checked at configuration time |
| `udea.gradle-plugin` | `udea-gradle` | `udea.kotlin-library` plus `compileOnly(gradleApi())` and TestKit for tests |

Every version comes from `gradle/libs.versions.toml`. `build-logic`'s `UdeaVersions.KOTLIN`
mirrors the catalog's `kotlin` key and a test in `build-logic` fails if the two drift.

## Modules

| Module | Convention | Purpose | Replaces | Depends on | Depended on by |
|---|---|---|---|---|---|
| `udea-annotations` | `udea.kotlin-multiplatform` | Zero-dependency leaf: `@Net`, `@Sim`, `@Q`, `@Replicated`, `@AgentTool`, `@Arg` | Two conflicting `UdeaNetworked` declarations on one classpath | *(Kotlin stdlib only)* | `udea-codegen`, `udea-compiler-plugin`, `udea-core`, `udea-assets`, `udea-assets-compiler` (`@AssetDsl`, issue #192) |
| `udea-diagnostics` | `udea.kotlin-multiplatform` | Zero-dependency leaf: the one `UdeaDiagnostic` — severity, stable rule id, `SourceSpan`, `assetId`, optional `Fix` (spec §5) | new — the shared vocabulary the K2 checkers and the asset validator both emit | *(Kotlin stdlib only)* | `udea-compiler-plugin`, `udea-assets`, `udea-assets-compiler`, `udea-gradle`; and, on `testImplementation(testFixtures(...))` only, `udea-core`, `udea-agent` and `udea-agent-host` for `LatencyBudget` (issue #175) |
| `udea-codegen` | `udea.kotlin-build-tool` | KSP2 processor + KotlinPoet emitters; owns id assignment and `net-protocol.lock` | `NetworkGenerator`, `UdeaDslProcessor`, `@CreateDsl` | `udea-annotations`, `symbol-processing-api`, KotlinPoet | build-time only (`ksp(...)` from consumers) |
| `udea-compiler-plugin` | `udea.kotlin-build-tool` | K2 FIR/IR plugin: checkers, KDoc propagation, gated declaration synthesis | new (D8) | `udea-annotations`, `udea-diagnostics`, `kotlin-compiler-embeddable` (`compileOnly`) | build-time only; `udea-assets-compiler` on `runtimeOnly`, so the scripting host finds it as a service and every asset script compiles with it (issue #192), while no production source names a plugin type |
| `udea-fleks` | `udea.kotlin-multiplatform` | Fleks 2.14, the entity component system, vendored as source so it has iOS targets (issue #215). Third-party MIT code, not refactored; `NOTICE.md` records its origin and the build differences | the `io.github.quillraven.fleks:Fleks` Maven artifact | kotlinx-serialization-core (api), and nothing else by `UDEA-MG-007` | `udea-core` |
| `udea-core` | `udea.kotlin-multiplatform` | Headless kernel: `Simulation`, `SimBarrier`, `NetId`, `Tick`, snapshot ring, `Replicator`. **No GL on the compile classpath** | `UdeaGameManager`/`GameScreen`, the globals, `common/.../properties.kt`, `reflection.kt` | `udea-annotations` (api), `udea-fleks` (api), kotlinx-serialization-core (api) and -cbor for level files (issue #191), atomicfu for the `SimBarrier` inbox lock (issue #203) | `udea-gas`, `udea-net`, `udea-physics2d`, `udea-render`, `udea-agent`, `moba` |
| `udea-assets` | `udea.kotlin-multiplatform` | Runtime asset model + `.udeapak` reader | `common/assets/*`, the `Assets` global | `udea-annotations` (api), `udea-diagnostics` | `udea-assets-compiler`, `udea-render`, `moba` |
| `udea-assets-compiler` | `udea.kotlin-build-tool` | The five-pass asset compiler. **Zero Gradle types** — one implementation behind both the Gradle task and the dev daemon | `scriptHost.kt`, `AssetScanner`, `GameAssetLoader` | `udea-assets` (api), `udea-diagnostics`, `udea-annotations`, `udea-compiler-plugin` (`runtimeOnly`) | `udea-gradle` |
| `udea-gas` | `udea.kotlin-multiplatform` | Abilities, attributes, effects — tick-denominated | `common/ability/*`, `AbilitySystem`, `AttributeSystem` | `udea-core` (api) | `udea-agent-host`, `moba` |
| `udea-net` | `udea.kotlin-multiplatform-no-ios` | Transports, baselines, relevancy, prediction, RPC | `common/network/*`, both `Network*System`s, KryoNet | `udea-core` (api); Ktor sockets and WebSocket client, cryptography-kotlin for the connect token; the Ktor WebSocket server on `jvm` only, without `kotlin-reflect` (issue #209) | `moba` |
| `udea-physics2d` | `udea.kotlin-multiplatform-jvm-android` | 2D physics: `udea-core`'s `PhysicsWorld` over Box2D 3 (`box2d-jni`), headless. Bodies are built from `PhysicsBody` and the shape components, and the solved pose is copied back into `PhysicsBody` once per tick | `Box2DSystem`, `Box2DPhysicsWorld` (LibGDX Box2D, deleted in #212) | `udea-core` (api); `box2d-jni` + desktop natives on `jvm`, `box2d-jni-android` on `android` | *(nothing yet - a game adds `Physics2DModule`)* |
| `udea-render` | `udea.kotlin-multiplatform-render` | The only module that touches GL. Kool stays inside it (port spec section 3): the renderer, the ComposeGL frontend, and the Kool-backed `AudioDevice` (issue #221) | `SpriteBatchSystem` et al., `GameScreen`'s rendering half | `udea-core` (api), `udea-audio` (api), `udea-assets`, Kool, `composegl-ui` (api), `composegl-kool`; `composegl-lwjgl3` on `jvm` | `moba` |
| `udea-audio` | `udea.kotlin-multiplatform` | Cue-driven sound: the drain that empties `GameContext.cues`, the cue-to-`SoundCue` routing table, distance attenuation, stereo pan, pitch variance and a per-frame voice cap. **No GL and no `Gdx`** — playback is an `AudioDevice` SPI, and `AudioDevice.Silent` is a shipped implementation, so a headless process drains the queue and makes no noise | `common/.../ecs/system/SoundSystem.kt`, which read `gameScreen.camera` off a file-level global inside a Fleks system and called `play` on a `Sound` held by an asset value | `udea-core` (api), `udea-assets` | `udea-render`, `moba` |
| `udea-agent` | `udea.kotlin-multiplatform-no-ios` | MCP surface + test harness — same code path; common on `jvm`, `android` and `wasmJs`, with the `assets.*` toolset in `jvmMain` (issue #208) | FruitGameKTX's `DebugBridge` pattern, generalised | `udea-core` (api) | `udea-agent-host` |
| `udea-agent-host` | `udea.kotlin-library` | HTTP server, plus the toolsets that need a render context (spec §4: render, input, ui). Debug-only, verified absent from release | `level-editor`, `idea-plugin`, `compose-ui` | `udea-agent` (api); `udea-render` + `udea-net` (`implementation` — see below) | *(nothing — deliberately not `moba`)* |
| `udea-editor` | `udea.kotlin-library` | The editor window (issue #194): docked ComposeGL panels, the world in a `SceneView`, and buttons that call `editor.*` tools in-process. Debug-only, JVM | `level-editor` (D6), as a screen over the tool surface rather than a second implementation of it | `udea-render`, `udea-agent` (`api`), and `udea-assets` through `udea-render` until the asset panels (issue #195) name it directly; kotlinx-serialization-json to read tool answers | `moba:desktop`'s `editor` source set, and nothing else (`UDEA-MG-010`) |
| `udea-gradle` | `udea.gradle-plugin` | Tasks, verifiers, `gamebridge.json` emission | old `gradle-plugin` (which leaked `gradleApi` onto the game runtime) | `udea-assets-compiler`, `udea-diagnostics`, `gradleApi()` (`compileOnly`) | *(nothing — applied as a plugin, never depended on)* |
| `moba:game` | `udea.kotlin-multiplatform-render` | The example game as a library: components, systems, assets and what it draws, with no entry point (issue #212) | `example` | `udea-core`, `udea-render` (`api`); `udea-annotations`, `udea-gas`, `udea-net`, `udea-assets`, `udea-audio` (`implementation`) | `moba:desktop`, `moba:android` |
| `moba:desktop` | `udea.kotlin-library` | The desktop launcher: the client, the server, the shot mains, the proofs and the agent surface | `moba`'s entry points | `moba:game`, `udea-replay` (`api`); `udea-core`, `udea-render`, `udea-net`, `udea-gas`, `udea-audio`, `udea-assets` (`implementation`); the agent source set's `udea-agent-host` comes from `dev.wildware.udea.agent` and is kept out of release by `UDEA-REL-002` | — |
| `moba:android` | `udea.android-application` | The Android launcher, one activity | — | `moba:game` | — |

## Arrows that must never appear

- Anything → LibGDX, in any form (`UDEA-MG-009`). It left the tree in issue #213.
- Anything except `udea-render`, `udea-agent-host` and `udea-editor` → Kool / LWJGL3 / GL.
  `udea-core` in particular is the headless kernel; physics reaches it behind its own
  `PhysicsWorld` interface, never as a backend dependency. The exempt modules are
  `ModuleGraphRules.GL_ALLOWED_PROJECTS`, and adding one means editing that set and the test
  that pins it. `udea-editor` is exempt at run time only: `UDEA-MG-011` keeps every renderer
  artifact off what it compiles against.
- Anything → `udea-editor`, except a game's `editor` source set (`UDEA-MG-010`).
- `udea-assets-compiler` → any Gradle type. The daemon and CI must run identical code.
- `udea-audio` → Kool or LibGDX. It is a designated headless module, so `UDEA-MG-002` bans
  `de.fabmax.kool:*` on its classpath, `UDEA-MG-009` bans LibGDX there as everywhere, and
  `UDEA-MG-002-BYTECODE` bans the `com/badlogic/` namespace in its classes. The class that turns a path into a
  noise is `udea-render`'s `KoolAudioDevice` (issue #221), behind this module's `AudioDevice`
  interface: the same shape as `Presentation`, which `udea-core` holds without owning a renderer.
  `moba`'s own `GdxAudioDevice` went with LibGDX in issue #212, and `moba` now plays through
  `koolAudioDevice`.
- Any game module → `udea-gradle`. The old `gradle-plugin` put the Gradle API on the game's
  runtime classpath through `implementation(gradleApi())`; here `gradleApi()` is
  `compileOnly` and nothing depends on the project.
- `moba` (or any shipping runtime classpath) → `udea-agent-host`.

## `udea-agent-host` → `udea-render`, and why that arrow is allowed

The render toolset is declared against a `RenderControl` port; `udea-render` implements the other
half as `PresentationControl`; something has to join them, and for one phase the answer was
"nobody may". `udea-render` still cannot name `udea-agent-host` — that arrow would put the agent
surface on `moba`'s runtime classpath and make `UDEA-REL-002` impossible to pass. The arrow the
other way used to be barred too, because `udea-agent-host` was in `HEADLESS_PROJECTS`.

That combination was a **contradiction, not a trade-off.** Spec §4 gives this module "the
toolsets that need a render context or live input: render, input, ui". A module that owns the
render toolset and may not name a render type cannot implement the toolset's own port. The
observable cost was not hypothetical: `OffscreenRenderControl` and the GL `OverlaySystem` were
both written and both proven against a real LWJGL3 context, and both sat in **test** sources
because that was the only place the rule allowed — so every `render.*` tool answered
`no_render_context` on a real game run, and the activity overlay spec §3.7 describes was drawn
by nothing but its own tests.

So `udea-agent-host` takes `udea-render` as a plain `implementation` dependency, owns both
adapters in `src/main`, and is out of `HEADLESS_PROJECTS`. **What is given up and what is kept:**
`udeaVerifyHeadless` no longer scans this module's bytecode for GL types, which is the whole
cost. What it protects is unchanged — `udea-core`, the simulation kernel that must run in a test
JVM, a dedicated server and an agent harness with no display, is still headless at both
enforcement levels, and `RenderModuleGraphTest` still fails if it names a `udea.render` type. And
`udea-agent-host` is the *debug* HTTP host: `UDEA-REL-001` and `UDEA-REL-002` keep it out of every
shipped artifact and off every shipped runtime classpath, independently enforced by
`udeaVerifyRelease` and independently tested. It is on the plain JVM convention;
`udea-render` is still the only engine module on the render convention, and
`RenderModuleGraphTest` asserts it.

---

# Build gates

The arrows above are enforced by the build, not by discipline. The tasks below do it, and each
rule has a stable id so a failure message and this document can be joined up by search.

| Task | Registered on | Reads | Runs from |
|---|---|---|---|
| `udeaVerifyModuleGraph` | every `:udea-*` and `:moba:*` project | the resolved dependency graph | that project's `check`, plus the root aggregate |
| `udeaVerifyRelease` | `:moba:desktop` | the **packaged artifact**, plus the release runtime classpath | `finalizedBy` on `:moba:desktop:assemble`, release builds only |

Both read the **resolved** graph rather than declared dependencies, because the arrow
that matters is the one nobody declared: a module two hops away from a banned artifact has
nothing in its own build file to grep for. Failure messages therefore print the resolution path from the
root, not just the offending coordinate.

Coordinates are normalised before matching: `group:module` for an external module (the
version is dropped — no rule here is version-sensitive), the Gradle path for a project, and
`file:<display name>` for a file dependency. That last one is not a detail: `gradleApi()`
reaches a classpath as loose jars under the single name `Gradle API` and is invisible to a
scan that reads only the component graph.

## `UDEA-MG-001` — `udea-annotations` resolves the Kotlin stdlib and nothing else

**Spec §4.** Allowed on `runtimeClasspath`: `org.jetbrains.kotlin:kotlin-stdlib`,
`org.jetbrains:annotations`, and `org.jetbrains.kotlin:kotlin-stdlib-wasm-js`, which is the
stdlib itself as the Wasm target resolves it. Everything else fails.

Since issue #201 the module is multiplatform, and "`runtimeClasspath`" means every target's
copy of it: `jvmRuntimeClasspath`, `androidRuntimeClasspath`, `wasmJsRuntimeClasspath`. Each
rule on this page governs a target's classpath as the JVM classpath it stands for, and a
failure names the target's classpath it was found on.

The annotation vocabulary is on the compile classpath of the engine, the game, the KSP
processor and the K2 plugin at once, so a dependency added here is added to all four — and
two of them are loaded inside the Kotlin compiler, where an extra jar is a classloader
conflict rather than an inconvenience.

This rule and `UDEA-MG-004` are *budgets* — they say what is allowed rather than what is
banned — and a budget over a classpath that resolved nothing has no offenders either, so it
would pass forever while the module quietly accumulated dependencies. `DependencyRules.vacuity`
therefore fails any budget rule whose classpath resolved only the project itself. The branch is
reachable: `gradle.properties` carries `kotlin.stdlib.default.dependency = true`, and flipping
it empties this module's `runtimeClasspath`. `udea-annotations` used to enforce its own budget a
second time, in a `udeaVerifyAnnotationsLeaf` task with a private allow list and no rule id;
that task is gone and its one unique branch is the vacuity guard above.

## `UDEA-MG-002` — only the GL-allowed modules may see Kool, a ComposeGL backend, a GL backend or a native

**Spec §4, §3.5; Kool port spec §3.** Banned on `compileClasspath` and `runtimeClasspath` of
**every `udea-*` module except the GL-allowed ones** (`udea-render`, `udea-agent-host`, `udea-editor`): `de.fabmax.kool:*`, the
ComposeGL backends `dev.wildware.composegl:composegl-kool*`,
`composegl-lwjgl3*`, `composegl-webgl*` and `composegl-android*`, and `org.lwjgl:*`.

The port to Kool (issue #211) turned "no GL outside `udea-render`" into "no Kool and no ComposeGL
backend outside `udea-render`". `dev.wildware.composegl:composegl-ui` is the toolkit with no
backend in it — a tree can be composed and asserted on with no window — so it is not on the list.
The LibGDX coordinates this rule used to carry (`gdx-backend-lwjgl3`, the `*-platform` natives,
`composegl-gdx*`) left it in issue #213, when `UDEA-MG-009` began banning every LibGDX artifact
from every project.

The module set is not written out here, or anywhere twice. It is
`ModuleGraphRules.HEADLESS_PROJECTS` in `build-logic`, and `ModuleGraphRulesTest` derives the
same set from `settings.gradle.kts` — minus `ModuleGraphRules.GL_ALLOWED_PROJECTS`, the
modules above — and fails if the two have drifted. So including a new `udea-*` module puts it
under this rule automatically instead of leaving a gap somebody has to notice, and exempting one
means adding it to a *named* set rather than deleting it from a list. It used to be two
hand-written lists (this rule's, and the bytecode scan's below) that disagreed in both
directions, with `udea-agent-host`, `udea-diagnostics`, `udea-gradle` and
`udea-compiler-plugin` in neither: a GL backend on any of those passed both gates while this
document called them the same rule.

`udea-agent-host`'s exemption is a controller ruling with its own reasoning — see
[`udea-agent-host` → `udea-render`](#udea-agent-host--udea-render-and-why-that-arrow-is-allowed)
above. The short version: it owns the render toolset, and `UDEA-REL-002` rather than
`UDEA-MG-002` is what keeps it off a shipped classpath.

`udea-core` is the headless kernel: the simulation has to run in a test JVM, in a dedicated
server and inside an agent harness with no display. Once a GL backend is on the compile
classpath, a context reference or a static initialiser gets written and the headless path is
gone.

**The one per-module exemption: the FBX converter (issue #244).** `udea-assets-compiler` converts
an `.fbx` model to glTF with Assimp, which arrives through LWJGL's binding, and `udea-gradle`
carries the compiler. On those two modules - `ModuleGraphRules.MODEL_CONVERTER_PROJECTS` - this rule
excuses `org.lwjgl:lwjgl` and `org.lwjgl:lwjgl-assimp` and nothing else, through the rule's
`allowedIn`: `lwjgl-opengl`, Kool or a ComposeGL frontend on either still fails, and Assimp on any
other headless module still fails here as well as under `UDEA-MG-013`. Both modules stay in
`HEADLESS_PROJECTS`.

### `UDEA-MG-002-BYTECODE` — the same rule, one level down

`udeaVerifyModuleGraph` above reads *dependencies*. `udeaVerifyHeadless` — a task in
`udea-render`, backed by `HeadlessScan` — reads the *compiled classes* of every headless
module and fails if one names a GL type. "Every headless module" is the same
`ModuleGraphRules.HEADLESS_PROJECTS` the dependency rule uses: `udea-render`'s build script
reads it from `build-logic` and hands it to the scan as a system property, and the scan fails
loudly rather than scanning nothing if that hand-off breaks. That is what makes "the same
rule, one level down" a fact about the code rather than a claim about it. It reports under `UDEA-MG-002-BYTECODE`, an
extension of this rule rather than an id of its own, because a GL dependency and a GL type
reference are one defect at two enforcement levels and a CI filter should only have to know
one number.

It exists because a configuration check structurally cannot see two cases:

- a GL type arriving **transitively** through a dependency that is itself allowed — until
  issue #213, `com.badlogicgames.gdx:gdx` was legal for `Vector2` and carried
  `com/badlogic/gdx/graphics/Texture` in the same jar;
- a type named in source while the dependency providing it is `compileOnly`, so it never
  reaches a classpath this rule inspects.

The second case is how the old tree lost the property: `SpriteRenderer.kt` imported
`com.badlogic.gdx.graphics.Texture` into a component the world tick touched, and nothing
failed. `UDEA-MG-002` is checked first, because "you added `lwjgl-opengl` to `udea-core`" is
a better message than forty class-level ones. The banned namespaces are `org/lwjgl/`,
`com/badlogic/` and `box2dLight/`; the LibGDX carve-outs this table used to need (gdx-math and
the `utils` collections legal, `graphics/`, `backends/` and viewports banned) went with LibGDX. The one per-module
allowance is the FBX converter's (issue #244): in `ModuleGraphRules.MODEL_CONVERTER_PROJECTS`, a
reference into `ModuleGraphRules.MODEL_CONVERTER_NAMESPACE` (`org/lwjgl/assimp/`) is excused, handed
to the scan through `udea.headless.modelConverter` as the module set is. Every other `org/lwjgl/`
reference in those modules still fails, and for GL the fix is always to move the code to `udea-render`.

## `UDEA-MG-009` — no project resolves LibGDX

**Kool port spec §3, §4, D4, D9, D12.** Banned on `compileClasspath` and `runtimeClasspath` of
**every** `udea-*` and `moba` project, on every target, the GL-allowed modules included:
`com.badlogicgames.*:*` (LibGDX, and the extensions published beside it such as
`com.badlogicgames.box2dlights`) and `dev.wildware.composegl:composegl-gdx*`, ComposeGL's LibGDX
backend, which drags gdx in without a build script naming it.

LibGDX left in three steps, and until the last one this rule was scoped to whichever project had
just been cleaned. `udea-render` moved to Kool in issue #211, under `UDEA-MG-008`, a ban on that
module alone. `moba` followed in issue #212, and this id was added for the `moba` projects. Issue
#213 deleted the old tree and every `com.badlogicgames` coordinate from the version catalog, and
widened this rule to every project: a ban scoped to the modules that last had LibGDX lets it back
in through any other one, and being allowed GL is not being allowed a second renderer.
`UDEA-MG-008` was folded in here and its id is not reused.

`moba` was the last project that legitimately named gdx: it took `libs.gdx` so a `RenderSystem` could name `Batch` and
`TextureRegion`, and `libs.gdx.box2d` plus the desktop natives for `Box2DPhysicsWorld`. Issue #211
took the first reason away — the game draws through `udea-render`, which draws with Kool — and
spec D4 takes the second: Box2D leaves with LibGDX, and `MobaPhysicsModule` was never installed.
Issue #212 removes both and this is what keeps them removed.


## `UDEA-MG-010` — no compile or runtime classpath resolves `udea-editor`

**Issue #194.** Banned on `compileClasspath` and `runtimeClasspath` of every `udea-*` and `moba`
project: `:udea-editor`.

The editor window writes any field of any entity through the `editor.*` tools, which ignore
`agentWritable` because authoring a level needs every field. It is a tool for a person at a desk,
never for a player. A game reaches it through an `editor` source set of its desktop launcher -
`:moba:desktop`'s, which holds `runEditor` - and that source set's classpaths
(`editorCompileClasspath`, `editorRuntimeClasspath`) are not among the scanned configurations. So
the rule can be blunt: `:udea-editor` on a scanned classpath is either the shipped game carrying
the editor (`:moba:desktop`'s `runtimeClasspath` is its release classpath) or an engine module
depending upward on it. The project's own classpath never violates it, and test classpaths are not
scanned by it. The gizmo epic (#231, ticket G2) extends this rule to gizmo classes rather than
adding a parallel one.

## `UDEA-MG-011` — `udea-editor` compiles against no renderer

**Kool port spec §3; issue #194.** Banned on `udea-editor`'s `compileClasspath`: the same patterns
as `UDEA-MG-002` — `de.fabmax.kool:*`, the ComposeGL frontends, `org.lwjgl:*`.

The editor is in `GL_ALLOWED_PROJECTS` because its runtime classpath carries Kool through
`udea-render`, and cannot not: the viewport shows the world Kool draws. What it may *name* is
narrower. Its panels are `composegl-ui` widgets, the toolkit with no backend in it, and the world
reaches its `SceneView` through `udea-render`'s `WorldView`, so the Kool frontend and the world
draw stay inside `udea-render` as spec §3 requires. This rule is that sentence as a gate.

## `UDEA-MG-013` — the FBX converter runs in the asset build and nowhere else

**Issue #244.** Banned on `compileClasspath` and `runtimeClasspath` of every `udea-*` module and
every `moba` project except `ModuleGraphRules.MODEL_CONVERTER_PROJECTS` (`udea-assets-compiler`
and `udea-gradle`): `*:*assimp*`, every Assimp binding, LWJGL's included.

Kool reads glTF and has no FBX loader, so a game that drops an `.fbx` into its assets gets the
`.glb` the asset compiler converts it to, with Assimp through `org.lwjgl:lwjgl-assimp`. The
conversion belongs to the build because that is where a model that does not convert fails with a
diagnostic (`UDEA0039`) rather than in a player's session, and a runtime module that could name
the converter could start importing models at run time. The rule governs the GL-allowed modules
and the game too, which is where it adds to `UDEA-MG-002`: those are the classpaths already
allowed LWJGL, through Kool.

What it does not scan: a game's `agent` and `editor` source sets. `:moba:desktop`'s `agent` source
set runs the asset daemon in process, and the daemon is the asset compiler, so that classpath
carries the converter as it already carries the Kotlin compiler. `UDEA-REL-002` keeps the `agent`
source set out of every release; the `runtimeClasspath` this rule reads is the one that ships.

The same two modules are the one named exemption in `UDEA-MG-002` - see below - and in
`udeaVerifyHeadless`, which excuses the namespace `org/lwjgl/assimp/` in their classes and no other
part of `org/lwjgl/`.

## `UDEA-MG-003` — `udea-assets-compiler` holds zero Gradle types

**Spec §4.** Banned on every scanned classpath: `org.gradle:*`, `file:Gradle *` (which is
`gradleApi()` and `gradleTestKit()`).

The five-pass asset compiler is one implementation behind both the Gradle task and the dev
daemon. A Gradle type in the compiler makes the daemon path either impossible or a second
implementation, and a second implementation is how CI and the IDE come to disagree about
whether an asset is valid.

## `UDEA-MG-004` — nothing depends on `udea-gradle`

**Spec §4.** Banned on `runtimeClasspath` and `testRuntimeClasspath` of every `:udea-*`
project and `:moba`: `:udea-gradle`.

A Gradle plugin is applied, never depended on. The old `gradle-plugin` module *was* depended
on — by `example` — and its `implementation(gradleApi())` put the whole Gradle API on the
shipped game's runtime classpath. In the rewrite `gradleApi()` is `compileOnly`, and this rule
closes the other half of the same hole.

## `UDEA-MG-005` — no scripting host and no classpath scanner in the game

**Spec §6 (Phase 2 exit), §3.6.** Banned on the `runtimeClasspath` of every `moba` project -
`:moba:game`, `:moba:desktop`, `:moba:android`, plus `:moba`, so that re-creating a flat `moba`
module does not re-open the hole, and `:moba:web` for the same reason ahead of issue #226:
`org.jetbrains.kotlin:kotlin-scripting-*`, `org.jetbrains.kotlin:kotlin-reflect`,
`org.reflections:reflections`.

It was placed as a ratchet *before* Phase 2 had a reason to reach for
`kotlin-scripting-jvm-host`. Until issue #212 it named the flat `:moba` alone, which after the
split is a project with no classpath at all, so for a while it scanned nothing and passed;
`ModuleGraphRulesTest` now fails any rule that governs only paths `settings.gradle.kts` does not
include. The old `common` module pulled in five
`kotlin-scripting-*` artifacts and `org.reflections:reflections`, which is both a
startup cost and the mechanism behind the reflection-on-hot-paths smell the rewrite exists to
kill. Asset scripts are compiled at build time; discovery is a generated registry.

## `UDEA-MG-006` — the runtime asset model is a leaf

**Spec §4, §3.6, §6.** Allowed on `:udea-assets`'s `compileClasspath` and `runtimeClasspath`, and
nothing else: `:udea-annotations`, `:udea-diagnostics`, `org.jetbrains.kotlin:kotlin-stdlib`,
`org.jetbrains:annotations`, `org.jetbrains.kotlinx:kotlinx-io-core` and
`org.jetbrains.kotlinx:kotlinx-io-bytestring`, each also as the per-target artifact a target's
classpath resolves it to (`kotlinx-io-core-jvm`, `kotlinx-io-core-wasm-js` and so on).

An allow list rather than a deny list, for the same reason `UDEA-MG-001` is one: the budget is
the point, and the interesting failure is the dependency nobody has thought of yet.

The old asset model could not be read without three separate stacks. Asset values held live
`com.badlogic.gdx.audio.Sound` and `Texture` handles behind `by lazy` blocks that reached a
global `gameManager` (`common/.../audio.kt`, `common/.../animationSets.kt`); they were
serialised with Jackson polymorphic type ids (`@JsonTypeInfo` on `Asset`); and they existed at
all only because a `BasicJvmScriptingHost` evaluated `.udea.kts` at runtime. Spec §3.6 compiles
and validates assets at build time and ships a packed bundle, which makes the runtime model
plain data — so a dependency appearing here means an asset value has started holding something
that is not data, and that is exactly the regression this rule catches. `udea-assets-compiler`,
which does the compiling, is deliberately not governed by it.

kotlinx-io joined the list with the multiplatform port (issue #205). It holds no asset value: it
is how the `.udeapak` reader names and reads a file on every target, where `java.io` and
`java.nio` exist only on the JVM (spec §6, "kotlinx-io everywhere"). The two artifacts are named
rather than matched by a `kotlinx-*` wildcard, so serialization or coroutines arriving here is
still a failure.

## `UDEA-MG-007` — vendored Fleks is a leaf

**Spec §4.** Allowed on `:udea-fleks`'s `compileClasspath` and `runtimeClasspath`, and nothing
else: `org.jetbrains.kotlin:kotlin-stdlib` (and `kotlin-stdlib-wasm-js`),
`org.jetbrains:annotations`, and `org.jetbrains.kotlinx:kotlinx-serialization-core` with the
per-target artifacts a target's classpath resolves it to (`kotlinx-serialization-core-jvm` and so
on), and `kotlinx-serialization-bom`, the classless platform it brings on the JVM and Android.

`udea-fleks` is Fleks 2.14's own source, vendored in issue #215 because Fleks publishes no iOS
artifact at any version. It is the bottom of the engine: `udea-core` exposes it as `api`, so
whatever it resolves reaches every module above it and the shipped game. Two things this rule
catches:

- **An arrow back up.** Any Udea module on this classpath is an upward arrow from the library the
  kernel is built on.
- **Third-party code growing dependencies.** Upstream's main source needs only
  `kotlinx-serialization-core`, for its `@Serializable` `Entity`, `Snapshot` and `ComponentType`.
  Upstream's build also declares `kotlinx-serialization-json` on the main classpath, which that
  source does not use; here it is a test dependency of the vendored tests, and this rule keeps it
  there. The serialization artifacts are named rather than matched by a
  `kotlinx-serialization-*` wildcard for that reason.

## `UDEA-REL-001` — no agent class in the packaged artifact

**Spec §4, §6 (Phase 1 exit).** Banned entry prefixes, configurable via
`udeaVerifyRelease.bannedPrefixes`:

- `dev/wildware/udea/agent/`
- `dev/wildware/udea/agenthost/`

Scanned: every zip entry of every archive `:moba:desktop` produces — the jar today,
`distZip`/`distTar` the day a distribution is added. Selected by task type rather than by name
so the gate cannot silently narrow when the packaging changes.

`udea-agent` is an MCP surface with `spawn_blueprint` and `set_component_field` on it, and
`udea-agent-host` serves it over loopback HTTP. Spec §4 words this as "verified absent from
release" rather than "excluded from release" for a reason: the exclusion depends on a Gradle
variant a developer can misconfigure, and a misconfigured variant fails **silently**. Reading
the packaged zip rather than the configuration model is the whole point — a green model check
over a leaky jar is the exact failure mode — and it is also what keeps the gate honest once
shading or fat-jar packaging arrives, where the model stops describing what ships.

Finding no archive at all fails too. A release gate with no input passes forever.

## `UDEA-REL-002` — no agent module on the release runtime classpath

**Spec §4, §6 (Phase 1 exit).** Banned on `:moba:desktop`'s `runtimeClasspath` in a release build:
`:udea-agent`, `:udea-agent-host`.

Belt to `UDEA-REL-001`'s braces, and not redundant: the model check says *which dependency* to
remove, which the artifact scan cannot; the artifact scan catches a clean model with dirty
packaging, which the model check cannot.

A release build is `-Pudea.release=true`. `udeaVerifyRelease` is release-only on purpose — a
development build is *supposed* to carry the agent surface, and a gate that failed on it is a
gate people learn to route around.

## Adding a rule

Rules are data in `build-logic/src/main/kotlin/dev/wildware/udea/build/ModuleGraphRules.kt`,
so adding one is a `DependencyRule(...)` entry. Two things then fail until you finish the job:
`ModuleGraphRulesTest` asserts every rule id appears in this document, and `DependencyRule`
rejects a rule that declares neither a ban list nor an allow list, because a rule that cannot
fail reads as enforcement while reporting green forever.

---

# The Kotlin version pin

Three Kotlin versions exist in this build. Only one of them is a choice anybody makes
casually, and the other two are the reason `udeaVerifyKotlinPin` exists.

| Version | Where | Chosen by |
|---|---|---|
| `2.4.20` | every `udea-*` module and `moba`, compiler and stdlib alike | `gradle/libs.versions.toml`, mirrored by `UdeaVersions.KOTLIN` |
| Gradle 8.13's embedded `2.0.21` | `build-logic` itself | the Gradle distribution — this is what prints "Unsupported Kotlin plugin version" on every build |
| whatever KSP2 brings | `udea-codegen`'s **test** JVM only | KSP2's standalone compiler, and recorded as a `UdeaStdlibPin.Exemption` |

## Why the resolved stdlib had to be pinned

The catalog's `kotlin` version controls the compiler. On its own it says nothing about the
`kotlin-stdlib` that ends up on a classpath, because Gradle resolves the **highest** requested
version — and Fleks 2.14 asks for `kotlin-stdlib:2.3.21` while KotlinPoet 2.3.0 asks for
2.3.20. Before the pin, every `udea-*` module compiled with the catalog's compiler against a
higher stdlib than the catalog named,
and `./gradlew :udea-core:dependencies` was the only way to find out.

That is the direction that hurts. Newer stdlib metadata is read by the older compiler under a
tolerance warning, and a call site resolved against a 2.3 signature becomes a
`NoSuchMethodError` on whatever stdlib the classloader actually hands over. For the jars
loaded *inside* the compiler — `udea-codegen`, `udea-compiler-plugin`, `udea-assets-compiler`
(spec §7) — that classloader is the compiler's own and stdlib loading is parent-first, so the
mismatch is certain rather than merely possible.

`udea.kotlin-library` therefore pins the resolved stdlib back to the catalog version, in one
place, for every module. `udeaVerifyKotlinPin` runs from each module's `check` and fails
naming both versions. Raising the catalog version is how you get a newer stdlib; a transitive
dependency is not.

Pinned classpaths: `compileClasspath`, `runtimeClasspath`, `testCompileClasspath`,
`testRuntimeClasspath`, `testFixturesCompileClasspath`, `testFixturesRuntimeClasspath`. The
Kotlin plugin's own tool classpaths (`ksp`, `kotlinCompilerPluginClasspath`) are deliberately
left alone — forcing the project's stdlib onto the tool that compiles the project would be a
rule meant to protect the compiler breaking it instead.

## Why `build-logic` uses `embeddedKotlin("test")`

`kotlin-dsl` compiles build logic with the Kotlin the *Gradle distribution* embeds — 2.0.21
for Gradle 8.13 — not with the catalog's version. A 2.0.21 compiler cannot read the catalog
kotlin-test's metadata, so `libs.kotlin.test` here fails at compile time with a
metadata-version error. `embeddedKotlin("test")` resolves the kotlin-test that matches the compiler actually
running, which is the only version that can work.

This is a third Kotlin version in the build and it is deliberate rather than accidental. The
catalog pin governs the `udea-*` tree; it cannot govern `build-logic`, because Gradle chooses
that compiler. The day build-logic needs the catalog's Kotlin, the fix is a Gradle upgrade,
not a version override in `build-logic/build.gradle.kts`.

`gradle-plugin` used to be a fourth case and is no longer. It applied `kotlin-dsl` *and*
`kotlin("jvm")`, which is what printed the second "Unsupported Kotlin plugin version" warning,
and on Kotlin 2.4 that clash stopped being survivable: `kotlin-dsl` pins `languageVersion` to
1.8, and 2.4 refuses to compile below 2.0 at all. It applies `java-gradle-plugin` instead
(issue #186) — the half of `kotlin-dsl` it was actually using, `gradlePlugin { }`, without the
pin.
