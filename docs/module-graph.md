# Module graph

The rewrite tree (spec §4), the convention plugin each module is on, the old code it
replaces, and its arrows. The old modules (`common`, `example`, `gradle-plugin`,
`level-editor`, `idea-plugin`, `compose-ui`) stay in `settings.gradle.kts` until the
Phase 6 exit; nothing below may depend on them.

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
| `udea-agent` | `udea.kotlin-library` | MCP surface + test harness — same code path | FruitGameKTX's `DebugBridge` pattern, generalised | `udea-core` (api) | `udea-agent-host` |
| `udea-agent-host` | `udea.kotlin-library` | HTTP server. Debug-only, verified absent from release | `level-editor`, `idea-plugin`, `compose-ui` | `udea-agent` (api) | *(nothing — deliberately not `moba`)* |
| `udea-gradle` | `udea.gradle-plugin` | Tasks, verifiers, `gamebridge.json` emission | old `gradle-plugin` (which leaked `gradleApi` onto the game runtime) | `udea-assets-compiler`, `udea-diagnostics`, `gradleApi()` (`compileOnly`) | *(nothing — applied as a plugin, never depended on)* |
| `moba` | `udea.kotlin-library` | The example game | `example` | `udea-core`, `udea-gas`, `udea-net`, `udea-assets`, `udea-render` (all `implementation`) | — |

## Arrows that must never appear

- Anything → `common`. That is the whole point of the rewrite tree.
- Anything except `udea-render` → gdx / LWJGL3 / GL. `udea-core` in particular is the
  headless kernel; Box2D and gdx-math arrive later behind a `PhysicsWorld`-style interface,
  never as a backend dependency.
- `udea-assets-compiler` → any Gradle type. The daemon and CI must run identical code.
- Any game module → `udea-gradle`. The old `gradle-plugin` put the Gradle API on the game's
  runtime classpath through `implementation(gradleApi())`; here `gradleApi()` is
  `compileOnly` and nothing depends on the project.
- `moba` (or any shipping runtime classpath) → `udea-agent-host`.
