# Assets

Game content in Udea is written as Kotlin scripts, `.udea.kts` files, under a game's asset root. Characters, sprite animations, abilities, gameplay effects, items, controls, sounds, levels and 3D models are all declared this way. At build time the asset compiler turns the whole tree into one binary bundle, a `.udeapak`, which is what ships. Anything wrong is a build error with a stable rule id and, where it helps, a did-you-mean. While a game runs, the asset daemon can recompile one changed file in well under a second and hot-reload it.

A plain picture: a recipe book that gets proof-read and printed before the restaurant opens. The cooks never read the handwritten drafts; they read the printed book. The proof-reader refuses a recipe that names an ingredient the pantry does not have.

## Where assets live

A game declares its asset root in its build script. `moba` has one root, `moba/game/assets/`:

```kotlin
// moba/game/build.gradle.kts
udea {
    assetRoots.from("assets")
    kotlinVersion.set(libs.versions.kotlin.get())
}
```

Exactly one root is supported today. An asset's id is the script's folder under the root plus the declaration's `name`: `spriteAnimation(name = "soldier_idle", ...)` in `moba/game/assets/character/soldier.udea.kts` has the id `character/soldier_idle`.

`moba`'s character sprite sheets are licensed art that this repository may not redistribute, so `moba/game/assets/sprites/` is gitignored. `:moba:game:udeaStageCharacterArt` copies them in from `example-assets/sprites/` on every build. There is no manual step, and a `UDEA0032` about a missing `spritePath` is a real defect. `docs/art-assets.md` has the details.

## What an asset script looks like

A script is a list of declarations. Each declaration is a DSL call whose `name` is a string literal. Two real examples:

```kotlin
// moba/game/assets/models/fox.udea.kts
model(name = "fox", file = "models/fox/Fox.glb")
```

```kotlin
// moba/game/assets/ability/gameplay_effects.udea.kts
gameplayEffect(
    name = "heal_over_time",
    target = "health",
    modifierType = "Additive",
    magnitude = setByCaller("Data.Heal"),
    effectDuration = duration("Data.Duration"),
    period = 0.25F,
    cues = listOf("HealCue"),
)
```

A declaration refers to another by `reference("id")`:

```kotlin
// moba/game/assets/character/soldier.udea.kts
spriteAnimation(name = "soldier_idle", sheet = reference("character/soldier_idle_sheet"))
```

The kinds the DSL knows are the keys of `DslKinds.TYPES`, in `udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/gen/DslKinds.kt` - sprite sheets and animations, sounds, models, shaders, blueprints, levels, controls and bindings, characters, abilities, effects and items. Read the table rather than a list written down here: this sentence used to enumerate the words, and it had already gone stale twice by the time a shader was added to it.

### Assets hold no loops

A `.udea.kts` may not contain a loop, a lambda that something might run more than once, or a function that calls itself. Any of these is `UDEA0015`. The reason is the editor: its Save writes an exact value back into the script, and a value made inside a loop has no single place in the file to write it to. The K2 checker in the asset compile is the guarantee; a quick syntactic pass gives the early warning.

### Seconds are fine here

Content authors may write seconds, as `period = 0.25F` does above. `udea-gas`'s `ticksFromSeconds` converts it once, at load, to a tick count (15 at 60Hz). Simulation code itself never sees seconds. See [Tick Model and Determinism](Tick-Model-and-Determinism).

## The build

`AssetPipeline` (`udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/pipeline/AssetPipeline.kt`) runs the five passes of the design over the whole tree: scan, compile, validate, pack the atlas, write the bundle. The `dev.wildware.udea.assets` Gradle plugin (in `udea-gradle`) wires them as tasks:

| Task | What it does |
|---|---|
| `udeaScanAssets` | Pass 1. Scans declarations and `reference(...)` spans with the Kotlin parser only, compiling nothing |
| `udeaGenerateAccessors` | Writes the typed `GameAssets` accessors, and the asset index (the resource META-INF/udea/asset-index.json) that the compiler plugin checks `reference(...)` against |
| `udeaValidateAssets` | Compiles every script and validates the graph; writes `diagnostics.json`. Runs on `check` |
| `udeaPackBundle` | Packs a deterministic sprite atlas and writes the `.udeapak` |
| `udeaVerifyRelocatable` | Fails when a generated asset document contains this checkout's absolute path. Runs on `check` |

For `moba` these are `:moba:game:udeaValidateAssets`, `:moba:game:udeaPackBundle` and so on. The bundle ends up as a classpath resource (its name is `MobaAssets.RESOURCE`), which `MobaAssets` in `moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaAssets.kt` opens.

`udea-assets-compiler` contains no Gradle types (rule `UDEA-MG-003`). That is deliberate: the Gradle tasks and the in-game asset daemon drive the same objects, so they cannot disagree about whether a tree is valid.

### Typed accessors, for Kotlin only

`udeaGenerateAccessors` emits `dev.wildware.udea.generated.GameAssets`. Kotlin game code can write `GameAssets.character.orcElite` instead of a string. Asset scripts cannot use it: they keep `reference("id")` strings, which are validated. If scripts could see the accessors, every asset rename would change their compile classpath and recompile every script in the tree.

### Models and typed animation clips

A `model` declaration points at a glTF 2.0 file (`.glb` or `.gltf`) or an `.fbx`.

- A glTF file is checked when it is validated: `UDEA0038` if it is not readable glTF 2.0, or if it names files that are not in the asset root.
- An `.fbx` is converted to `.glb` during the asset build, with Assimp (through LWJGL), textures embedded. A broken one fails with `UDEA0039`. The converter is build-time only, and rule `UDEA-MG-013` keeps it off every runtime classpath.
- The build reads each model's animation clips and generates a typed object for them. `moba`'s fox has `Fox.Clips.Survey`, `Fox.Clips.Walk` and `Fox.Clips.Run`. Game code plays a clip by name the compiler checks. If the clips cannot be read, that is `UDEA0027`. A misspelt clip such as `Fox.Clips.Rnu` is `UDEA0016` from the compiler plugin, with the nearest clip name suggested.

[Models and Animation](Models-and-Animation) covers how models are drawn and animated.

## The `.udeapak` bundle

The bundle format is in `udea-assets/src/commonMain/kotlin/dev/wildware/udea/assets/pack/BundleFormat.kt`. The writer is in `udea-assets-compiler`; the reader, `BundleReader`, is in `udea-assets`, which is on the classpath of the game, the engine and the agent.

- **Hand-rolled, not CBOR.** `udea-assets` must be a leaf module (`UDEA-MG-006`): it may depend only on `udea-annotations`, `udea-diagnostics`, kotlinx-io and the standard library.
- **Every field is fixed-width little-endian.** No variable-length integers, because an encoder with a choice of encodings is an encoder that can produce two different files from the same input.
- **Self-describing.** Every record carries the fully qualified name of its kind and a tagged field tree. A game's own kinds that the engine does not know are read as `OpaqueAsset` instead of failing.
- **Deterministic.** The same sources give the same bytes. `UDEA0035` catches a script that reads a clock or an unseeded random.

At run time `Bundle.registry` is an `AssetRegistry`: the decoded graph with every reference already bound.

## Diagnostics

Every problem is a `UdeaDiagnostic` with a rule id, a message, a repo-relative source span and, where it helps, a suggested fix. [Diagnostics and Compiler Plugin](Diagnostics-and-Compiler-Plugin) explains the format. The asset rules are:

| Id | Meaning |
|---|---|
| `UDEA0004` | `reference("...")` names an asset that does not exist. Reported at the string, with a did-you-mean |
| `UDEA0013` | `reference("...")` names an asset of the wrong kind |
| `UDEA0014` | The asset index could not be read |
| `UDEA0015` | A loop in an asset script |
| `UDEA0020` | A declaration's name is not a string literal, so its id is not known statically |
| `UDEA0021` | The Kotlin script compiler rejected a file |
| `UDEA0022` | A script threw while it was evaluated, or its isolated worker died |
| `UDEA0023` | A construct the transpiled front end cannot rewrite faithfully |
| `UDEA0024` | A field holds a value the `.udeapak` format cannot encode |
| `UDEA0025` | A declaration's kind cannot be packed into a runtime graph |
| `UDEA0026` | The asset migrator left a construct it could not rewrite, marked `TODO(udea-migrate)` |
| `UDEA0027` | A model's clips cannot be read |
| `UDEA0030` | Two declarations claim the same id |
| `UDEA0031` | An asset's parent chain has a cycle |
| `UDEA0032` | A declaration names a resource file that is not in the asset root |
| `UDEA0033` | A sprite sheet's rows and columns do not divide the image |
| `UDEA0034` | An animation notify is on a frame past the end of its sheet |
| `UDEA0035` | A script reads a clock or an unseeded random |
| `UDEA0036` | An asset validator threw: a bug in the tool, not in the assets |
| `UDEA0037` | An item recipe costs less than its components, or lists itself |
| `UDEA0038` | A model file is not readable glTF 2.0, or names files outside the asset root |
| `UDEA0039` | An `.fbx` cannot be converted |

The ids live in `udea-diagnostics/src/commonMain/kotlin/dev/wildware/udea/diagnostics/UdeaRules.kt` (the shared ones), `udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/AssetCompilerRules.kt` and `udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/validate/AssetValidationRules.kt`.

## The asset daemon and hot reload

`AssetDaemon` (`udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/daemon/AssetDaemon.kt`) is the compiler kept warm inside a running debug game. It adds three things to the passes above: a graph per file, the last graph that validated ("last-good"), and the difference between two graphs.

- `reload` recompiles only the files it is told changed. That is why it is fast.
- A reload that fails to validate or pack leaves the last-good graph untouched. The running game keeps the assets it had.
- A reload that succeeds produces a `GraphDelta`, which is applied through the `SimBarrier` at the top of the next tick, like every other outside change.
- `AssetWatcher` watches the asset roots and hands the daemon the set of changed files. It debounces: an editor's save makes several file events, and the watcher waits for the last one before reporting.

An agent drives the daemon through the `assets.*` tools (in `udea-agent/src/jvmMain/kotlin/dev/wildware/udea/agent/assets/AssetsToolset.kt`): `assets.list`, `assets.search`, `assets.get`, `assets.fields`, `assets.graph`, `assets.resolve_reference`, `assets.changed_since`, `assets.validate`, `assets.create`, `assets.set`, `assets.patch` and `assets.write`. `assets.validate` is "the compile loop: run it after every edit instead of a Gradle build". `assets.patch` replaces one exact substring, validates and hot-reloads; if the file fails to validate, it is restored byte for byte. See [Agent Tool Surface](Agent-Tool-Surface).

The budgets for this loop, and where CI gates them, are in `docs/budgets.md`. `:udea-assets-compiler:udeaDaemonBudget` measures a warm reload and a warm validate.

## See also

- [Levels](Levels)
- [Abilities (GAS)](Abilities-GAS)
- [Models and Animation](Models-and-Animation)
- [Diagnostics and Compiler Plugin](Diagnostics-and-Compiler-Plugin)
- [Agent Tool Surface](Agent-Tool-Surface)
