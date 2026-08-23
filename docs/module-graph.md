# Module graph

The rewrite tree (spec §4), the convention plugin each module is on, the old code it
replaces, and its arrows. The old modules that remain (`common`, `example`, `gradle-plugin`)
stay in `settings.gradle.kts` until the Phase 6 exit; nothing below may depend on them.
`level-editor`, `idea-plugin` and `compose-ui` were deleted in Phase 0 under D6 — they had no
replacement to wait for. They stay on the banned list below regardless: a coordinate that
cannot match costs nothing, and it is what stops one being quietly re-added.
`docs/migration/ledger.md` carries the retirement order.

**Rule, enforced from Phase 0:** no `udea-*` or `moba` project may have `common` on its
compile classpath. Anything needed is copied forward deliberately, file by file, with the
copy reviewed.

## Convention plugins (`build-logic`)

| Plugin | For | What it gives you |
|---|---|---|
| `udea.kotlin-library` | every runtime module and `moba` | Kotlin JVM, JDK 17 toolchain, `explicitApi()`, kotlin.test on JUnit 5. **No GL.** |
| `udea.kotlin-library-gl` | `udea-render` only | `udea.kotlin-library` plus gdx and the LWJGL3 backend, as `implementation` so GL cannot leak downstream |
| `udea.kotlin-build-tool` | `udea-codegen`, `udea-compiler-plugin`, `udea-assets-compiler` | `udea.kotlin-library` plus the exact-Kotlin-version pin (spec §7), checked at configuration time |
| `udea.gradle-plugin` | `udea-gradle` | `udea.kotlin-library` plus `compileOnly(gradleApi())` and TestKit for tests |

Every version comes from `gradle/libs.versions.toml`. `build-logic`'s `UdeaVersions.KOTLIN`
mirrors the catalog's `kotlin` key and a test in `build-logic` fails if the two drift.

## Modules

| Module | Convention | Purpose | Replaces | Depends on | Depended on by |
|---|---|---|---|---|---|
| `udea-annotations` | `udea.kotlin-library` | Zero-dependency leaf: `@Net`, `@Sim`, `@Q`, `@Replicated`, `@AgentTool`, `@Arg` | Two conflicting `UdeaNetworked` declarations on one classpath | *(Kotlin stdlib only)* | `udea-codegen`, `udea-compiler-plugin`, `udea-core`, `udea-assets` |
| `udea-diagnostics` | `udea.kotlin-library` | Zero-dependency leaf: the one `UdeaDiagnostic` — severity, stable rule id, `SourceSpan`, `assetId`, optional `Fix` (spec §5) | new — the shared vocabulary the K2 checkers and the asset validator both emit | *(Kotlin stdlib only)* | `udea-compiler-plugin`, `udea-assets`, `udea-assets-compiler`, `udea-gradle` |
| `udea-codegen` | `udea.kotlin-build-tool` | KSP2 processor + KotlinPoet emitters; owns id assignment and `net-protocol.lock` | `NetworkGenerator`, `UdeaDslProcessor`, `@CreateDsl` | `udea-annotations`, `symbol-processing-api`, KotlinPoet | build-time only (`ksp(...)` from consumers) |
| `udea-compiler-plugin` | `udea.kotlin-build-tool` | K2 FIR/IR plugin: checkers, KDoc propagation, gated declaration synthesis | new (D8) | `udea-annotations`, `udea-diagnostics`, `kotlin-compiler-embeddable` (`compileOnly`) | build-time only |
| `udea-core` | `udea.kotlin-library` | Headless kernel: `Simulation`, `SimBarrier`, `NetId`, `Tick`, snapshot ring, `Replicator`. **No GL on the compile classpath** | `UdeaGameManager`/`GameScreen`, the globals, `common/.../properties.kt`, `reflection.kt` | `udea-annotations` (api), Fleks | `udea-gas`, `udea-net`, `udea-render`, `udea-agent`, `moba` |
| `udea-assets` | `udea.kotlin-library` | Runtime asset model + `.udeapak` reader | `common/assets/*`, the `Assets` global | `udea-annotations` (api), `udea-diagnostics` | `udea-assets-compiler`, `udea-render`, `moba` |
| `udea-assets-compiler` | `udea.kotlin-build-tool` | The five-pass asset compiler. **Zero Gradle types** — one implementation behind both the Gradle task and the dev daemon | `scriptHost.kt`, `AssetScanner`, `GameAssetLoader` | `udea-assets` (api), `udea-diagnostics` | `udea-gradle` |
| `udea-gas` | `udea.kotlin-library` | Abilities, attributes, effects — tick-denominated | `common/ability/*`, `AbilitySystem`, `AttributeSystem` | `udea-core` (api) | `moba` |
| `udea-net` | `udea.kotlin-library` | Transports, baselines, relevancy, prediction, RPC | `common/network/*`, both `Network*System`s, KryoNet | `udea-core` (api) | `moba` |
| `udea-render` | `udea.kotlin-library-gl` | The only module that touches GL | `SpriteBatchSystem` et al., `GameScreen`'s rendering half | `udea-core` (api), `udea-assets`, gdx + gdx-backend-lwjgl3 | `moba` |
| `udea-audio` | `udea.kotlin-library` | Cue-driven sound: the drain that empties `GameContext.cues`, the cue-to-`SoundCue` routing table, distance attenuation, stereo pan, pitch variance and a per-frame voice cap. **No GL and no `Gdx`** — playback is an `AudioDevice` SPI, and `AudioDevice.Silent` is a shipped implementation, so a headless process drains the queue and makes no noise | `common/.../ecs/system/SoundSystem.kt`, which read `gameScreen.camera` off a file-level global inside a Fleks system and called `play` on a `Sound` held by an asset value | `udea-core` (api), `udea-assets` | `moba` |
| `udea-agent` | `udea.kotlin-library` | MCP surface + test harness — same code path | FruitGameKTX's `DebugBridge` pattern, generalised | `udea-core` (api) | `udea-agent-host` |
| `udea-agent-host` | `udea.kotlin-library` | HTTP server, plus the toolsets that need a render context (spec §4: render, input, ui). Debug-only, verified absent from release | `level-editor`, `idea-plugin`, `compose-ui` | `udea-agent` (api); `udea-render` + gdx (`implementation` — see below) | *(nothing — deliberately not `moba`)* |
| `udea-gradle` | `udea.gradle-plugin` | Tasks, verifiers, `gamebridge.json` emission | old `gradle-plugin` (which leaked `gradleApi` onto the game runtime) | `udea-assets-compiler`, `udea-diagnostics`, `gradleApi()` (`compileOnly`) | *(nothing — applied as a plugin, never depended on)* |
| `moba` | `udea.kotlin-library` | The example game | `example` | `udea-core`, `udea-gas`, `udea-net`, `udea-assets`, `udea-render` (all `implementation`) | — |

## Arrows that must never appear

- Anything → `common`. That is the whole point of the rewrite tree.
- Anything except `udea-render` and `udea-agent-host` → gdx / LWJGL3 / GL. `udea-core` in
  particular is the headless kernel; Box2D and gdx-math arrive later behind a
  `PhysicsWorld`-style interface, never as a backend dependency. The two exempt modules are
  `ModuleGraphRules.GL_ALLOWED_PROJECTS`, and adding a third means editing that set and the
  test that pins it.
- `udea-assets-compiler` → any Gradle type. The daemon and CI must run identical code.
- `udea-audio` → gdx, in any form. It is a designated headless module, so `UDEA-MG-002` bans
  the backend on its classpath and `UDEA-MG-002-BYTECODE` bans `com/badlogic/gdx/Gdx` by
  exact name. The class that turns a path into a noise is `moba`'s
  `dev.wildware.moba.audio.GdxAudioDevice`, behind this module's `AudioDevice` interface —
  the same shape as `Presentation`, which `udea-core` holds without owning a renderer.
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
`no_render_context` on a real `:moba:run`, and the activity overlay spec §3.7 describes was drawn
by nothing but its own tests.

So `udea-agent-host` takes `udea-render` as a plain `implementation` dependency, owns both
adapters in `src/main`, and is out of `HEADLESS_PROJECTS`. **What is given up and what is kept:**
`udeaVerifyHeadless` no longer scans this module's bytecode for GL types, which is the whole
cost. What it protects is unchanged — `udea-core`, the simulation kernel that must run in a test
JVM, a dedicated server and an agent harness with no display, is still headless at both
enforcement levels, and `RenderModuleGraphTest` still fails if it names a `udea.render` type. And
`udea-agent-host` is the *debug* HTTP host: `UDEA-REL-001` and `UDEA-REL-002` keep it out of every
shipped artifact and off every shipped runtime classpath, independently enforced by
`udeaVerifyRelease` and independently tested. It does **not** apply `udea.kotlin-library-gl`;
`udea-render` is still the only module on that convention, and `RenderModuleGraphTest` asserts
it.

---

# Build gates

The arrows above are enforced by the build, not by discipline. Three tasks do it, they run
from `check`, and each has a stable rule id so a failure message and this document can be
joined up by search.

| Task | Registered on | Reads | Runs from |
|---|---|---|---|
| `udeaVerifyNoLegacyDependencies` | every `:udea-*` project and `:moba` | the resolved dependency graph | that project's `check`, plus the root aggregate |
| `udeaVerifyModuleGraph` | every `:udea-*` project and `:moba` | the resolved dependency graph | that project's `check`, plus the root aggregate |
| `udeaVerifyRelease` | `:moba` | the **packaged artifact**, plus the release runtime classpath | `finalizedBy` on `:moba:assemble`, release builds only |

All three read the **resolved** graph rather than declared dependencies, because the arrow
that matters is the one nobody declared: a module two hops away from `common` has nothing in
its own build file to grep for. Failure messages therefore print the resolution path from the
root, not just the offending coordinate.

Coordinates are normalised before matching: `group:module` for an external module (the
version is dropped — no rule here is version-sensitive), the Gradle path for a project, and
`file:<display name>` for a file dependency. That last one is not a detail: `gradleApi()`
reaches a classpath as loose jars under the single name `Gradle API` and is invisible to a
scan that reads only the component graph.

## `UDEA-LEGACY-001` — no old-tree project on a rewrite classpath

**Spec §4.** Banned: `:common`, `:gradle-plugin`, `:level-editor`, `:idea-plugin`,
`:compose-ui`, `:example`, `:example:*`. Scanned on `compileClasspath`, `runtimeClasspath`,
`testCompileClasspath`, `testRuntimeClasspath`, `testFixturesCompileClasspath`,
`testFixturesRuntimeClasspath`.

The old tree is replaced module by module and deleted at the Phase 6 exit. Spec §7 rates two
coexisting module trees on one classpath as the top structural risk and says why: the
duplicate declarations and revived globals it produces surface far from the module that added
the edge. Anything needed from the old tree is copied forward file by file, with the copy
reviewed. `:example` is banned for a second reason as well — it depends on `:gradle-plugin`,
whose `implementation(gradleApi())` puts the whole Gradle API downstream.

This rule is run by its own task rather than folded into `udeaVerifyModuleGraph`, so a
failure cannot mean either "you brought back the old tree" or "you put GL on the kernel".

## `UDEA-MG-001` — `udea-annotations` resolves the Kotlin stdlib and nothing else

**Spec §4.** Allowed on `runtimeClasspath`: `org.jetbrains.kotlin:kotlin-stdlib`,
`org.jetbrains:annotations`. Everything else fails.

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

## `UDEA-MG-002` — only the GL-allowed modules may see a GL backend or a native

**Spec §4, §3.5.** Banned on `compileClasspath` and `runtimeClasspath` of **every `udea-*`
module except `udea-render` and `udea-agent-host`**: `com.badlogicgames.gdx:gdx-backend-lwjgl3`,
`org.lwjgl:*`, `com.badlogicgames.gdx:*-platform`.

The module set is not written out here, or anywhere twice. It is
`ModuleGraphRules.HEADLESS_PROJECTS` in `build-logic`, and `ModuleGraphRulesTest` derives the
same set from `settings.gradle.kts` — minus `ModuleGraphRules.GL_ALLOWED_PROJECTS`, the two
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

`com.badlogicgames.gdx:gdx` is deliberately **not** banned — `Vector2` and the rest of
gdx-math are headless. The ban is on GL and on native loaders, not on maths.

`udea-core` is the headless kernel: the simulation has to run in a test JVM, in a dedicated
server and inside an agent harness with no display. Once a GL backend is on the compile
classpath, a `Gdx.gl` reference or a static initialiser gets written and the headless path is
gone.

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

- a GL type arriving **transitively** through a dependency that is itself allowed —
  `com.badlogicgames.gdx:gdx` is legal for `Vector2` and carries
  `com/badlogic/gdx/graphics/Texture` in the same jar;
- a type named in source while the dependency providing it is `compileOnly`, so it never
  reaches a classpath this rule inspects.

The second case is how the old tree lost the property: `SpriteRenderer.kt` imported
`com.badlogic.gdx.graphics.Texture` into a component the world tick touched, and nothing
failed. `UDEA-MG-002` is checked first, because "you added `gdx-backend-lwjgl3` to
`udea-core`" is a better message than forty class-level ones. There is no per-module
allowlist: the fix is always to move the code to `udea-render`.

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

**Spec §6 (Phase 2 exit), §3.6.** Banned on `:moba`'s `runtimeClasspath`:
`org.jetbrains.kotlin:kotlin-scripting-*`, `org.jetbrains.kotlin:kotlin-reflect`,
`org.reflections:reflections`.

This passes trivially while `moba` is empty, which is the point: it is a ratchet placed
*before* Phase 2 has a reason to reach for `kotlin-scripting-jvm-host`. `common` pulls in five
`kotlin-scripting-*` artifacts and `org.reflections:reflections` today, which is both a
startup cost and the mechanism behind the reflection-on-hot-paths smell the rewrite exists to
kill. Asset scripts are compiled at build time; discovery is codegen and `ServiceLoader`.

## `UDEA-MG-006` — the runtime asset model is a leaf

**Spec §4, §3.6.** Allowed on `:udea-assets`'s `compileClasspath` and `runtimeClasspath`, and
nothing else: `:udea-annotations`, `:udea-diagnostics`, `org.jetbrains.kotlin:kotlin-stdlib`,
`org.jetbrains:annotations`.

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

## `UDEA-REL-001` — no agent class in the packaged artifact

**Spec §4, §6 (Phase 1 exit).** Banned entry prefixes, configurable via
`udeaVerifyRelease.bannedPrefixes`:

- `dev/wildware/udea/agent/`
- `dev/wildware/udea/agenthost/`

Scanned: every zip entry of every archive `:moba` produces — the jar today,
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

**Spec §4, §6 (Phase 1 exit).** Banned on `:moba`'s `runtimeClasspath` in a release build:
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
| `2.2.10` | every `udea-*` module and `moba`, compiler and stdlib alike | `gradle/libs.versions.toml`, mirrored by `UdeaVersions.KOTLIN` |
| Gradle 8.13's embedded `2.0.21` | `build-logic` itself | the Gradle distribution — this is what prints "Unsupported Kotlin plugin version" on every build |
| whatever KSP2 brings | `udea-codegen`'s **test** JVM only | KSP2's standalone compiler, and recorded as a `UdeaStdlibPin.Exemption` |

## Why the resolved stdlib had to be pinned

The catalog's `kotlin` version controls the compiler. On its own it says nothing about the
`kotlin-stdlib` that ends up on a classpath, because Gradle resolves the **highest** requested
version — and Fleks 2.14 asks for `kotlin-stdlib:2.3.21` while KotlinPoet 2.3.0 asks for
2.3.20. Before the pin, every `udea-*` module compiled with 2.2.10 against a 2.3.21 stdlib,
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
for Gradle 8.13 — not with the catalog's 2.2.10. A 2.0.21 compiler cannot read kotlin-test
2.2.10's metadata, so `libs.kotlin.test` here fails at compile time with a metadata-version
error. `embeddedKotlin("test")` resolves the kotlin-test that matches the compiler actually
running, which is the only version that can work.

This is a third Kotlin version in the build and it is deliberate rather than accidental. The
catalog pin governs the `udea-*` tree; it cannot govern `build-logic`, because Gradle chooses
that compiler. The day build-logic needs the catalog's Kotlin, the fix is a Gradle upgrade,
not a version override in `build-logic/build.gradle.kts`.
