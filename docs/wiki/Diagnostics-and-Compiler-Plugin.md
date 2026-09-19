# Diagnostics and Compiler Plugin

Every tool in Udea that finds a problem reports it the same way: as a `UdeaDiagnostic` with a severity, a stable rule id such as `UDEA0004`, a message, a repo-relative source location and, where it helps, a suggested fix and a "did you mean". The Kotlin compiler plugin, the KSP code generator and the asset compiler all use the same ids from one registry. So a mistake has one id whichever tool caught it, and an agent can act on it in the same turn.

A plain picture: every inspector in a building uses the same printed form and the same code book. Whether the electrician or the plumber spots a problem, you look up the same code and it always means the same thing.

## `UdeaDiagnostic`

The type lives in `udea-diagnostics`, a zero-dependency leaf module, so every other module can use it (`udea-diagnostics/src/commonMain/kotlin/dev/wildware/udea/diagnostics/UdeaDiagnostic.kt`):

| Field | Meaning |
|---|---|
| `severity` | `Error`, `Warning` or `Info` |
| `ruleId` | A stable id, `UDEA` and four digits |
| `message` | What is wrong, in words |
| `span` | A `SourceSpan`: repo-relative `path`, start and end line and column. Never an absolute path |
| `assetId` | The asset the problem is about, if any |
| `fix` | A `Fix`: a description and a list of text replacements |
| `causedBy` | Set when this diagnostic is a consequence of another problem |

It prints in the usual compiler shape:

```
<path>:<line>:<column>: error: [UDEA0004] <message>
```

### Rule ids are permanent

`UdeaRules` (`udea-diagnostics/src/commonMain/kotlin/dev/wildware/udea/diagnostics/UdeaRules.kt`) is the registry of shared ids. The contract written there:

- An id always means the same defect. It is never renumbered and never reused, even after its rule is retired.
- A rule's default severity and description may be refined; message text may be reworded freely.
- Ids are what suppression files, CI filters and agent prompts key on, so they outlive any one implementation.

Build a diagnostic from the rule, never by typing the id: `UdeaRules.UNRESOLVED_REFERENCE.diagnostic(message, span, ...)` takes the id and default severity from the registry, so two producers of one rule cannot drift apart.

### Capped, root cause first

A `DiagnosticSink` collects diagnostics and, in `build()`, turns a pile into something an agent can act on in one turn:

1. **Dedupe.** The same rule at the same place is one problem.
2. **Collapse consequences.** If five blueprints reference one asset id that does not exist, that is one missing asset, not five problems. Diagnostics that share a `causedBy` collapse to one, and disappear entirely if their root cause is also reported.
3. **Rank and cap.** Errors first, then root causes before consequences, then by location. At most `MAX_DIAGNOSTICS` (25) are kept, and the rest are counted as suppressed. The order is total, so two producers given the same input write byte-identical `diagnostics.json`.

### Did you mean

`DidYouMean` (`udea-diagnostics/src/commonMain/kotlin/dev/wildware/udea/diagnostics/DidYouMean.kt`) suggests the closest known name by edit distance. It is mandatory, not a nicety: "unknown asset `charater/orc`" costs an agent a turn listing the directory, while "did you mean `character/orc`?" lets it fix the typo at once. Every producer that reports an unresolved name attaches a suggestion when one exists.

## Who reports what

| Ids | Reported by | About |
|---|---|---|
| `UDEA0001`, `UDEA0002`, `UDEA0003`, `UDEA0005` | K2 compiler plugin and KSP (`udea-codegen`) | `@Net` or `@Sim` on a `val`, the 64-field limit, `@Q` on a non-float |
| `UDEA0004` | K2 compiler plugin and the asset compiler | `reference(...)` to an asset that does not exist |
| `UDEA0006`, `UDEA0007` | KSP (`udea-codegen`) | A replicated field type the generator cannot store; `@Q` arguments that do not describe a quantisation |
| `UDEA0008`–`UDEA0012` | KSP (`udea-codegen`) | The agent surface: an `@AgentTool` with no or too short a description, a parameter with no `@Arg` description, a parameter type with no JSON Schema mapping, a non-scalar `@AgentState`, two tools with the same name |
| `UDEA0013`, `UDEA0014` | K2 compiler plugin | `reference(...)` to an asset of the wrong kind; an unreadable asset index |
| `UDEA0015` | K2 compiler plugin, inside the asset compile | A loop in an asset script |
| `UDEA0016` | K2 compiler plugin | A model clip that does not exist, such as `Fox.Clips.Rnu` |
| `UDEA0017` | KSP (`udea-codegen`) | A gizmo handle annotation naming a field that is not a mutable `Float` |
| `UDEA0020`–`UDEA0039` | The asset compiler | Asset scripts and files. [Assets](Assets) lists them all |

The asset compiler keeps its own ids (from `UDEA0020`) in `udea-assets-compiler`, but in the same `UDEAnnnn` space.

## The K2 compiler plugin

`udea-compiler-plugin` hooks into the Kotlin K2 compiler's front end (FIR). It adds diagnostics KSP cannot, because it sees the code the way the compiler resolves it. Its checkers are in `udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/`:

| Checker | Rules | What it does |
|---|---|---|
| `UdeaReplicatedPropertyChecker` | `UDEA0001`, `UDEA0003`, `UDEA0005` | `@Net` or `@Sim` on a `val`; `@Q` on a non-`Float` |
| `UdeaComponentFieldLimitChecker` | `UDEA0002` | More than 64 `@Net` and `@Sim` fields, since a field mask is one 64-bit word |
| `UdeaAssetReferenceChecker` | `UDEA0004`, `UDEA0013`, `UDEA0014` | `reference("typo")`, reported **at the string literal**, with a did-you-mean from the generated asset index |
| `UdeaAssetLoopChecker` | `UDEA0015` | A loop in a `*.udea.kts` |
| `UdeaAnimationClipChecker` | `UDEA0016` | An unknown member of a model's generated clip object |

### The asset loop checker

The asset compiler's first pass refuses loop keywords by spelling, but spelling can be dodged: `(1..3).forEach { }`, `import kotlin.repeat as times`, or a helper the script declares itself. `UdeaAssetLoopChecker` looks at what a call **resolves to** instead, and refuses three shapes:

- a `for`, `while` or `do`-`while`;
- a lambda handed to a function that may call it more than once. A function is trusted only if it promises to call the lambda at most once (a Kotlin `callsInPlace` contract, as `let`, `apply` and `run` have) or carries `@AssetDsl`;
- a function that calls itself.

It runs only in files named `*.udea.kts`. `udea-assets-compiler` carries the plugin at run time and its script host registers it, so ordinary game and engine code, where loops are the point, is never checked.

### The plugin is optional

The plugin is the part of the build most sensitive to Kotlin version changes, so the project must always compile without it. Two switches prove it:

- `-Pudea.compilerPlugin.enabled=false` keeps the plugin jar off every compiler plugin classpath. `udeaVerifyCompilerPlugin`, on `check` in every module, asserts the switch really does that. CI's `plugin-disabled` job builds the whole project with it.
- `:udea-compiler-plugin:udeaVerifyPluginOptional` fails if any production source in a `udea-*` module or `moba` references a `dev.wildware.udea.compiler.` type.

The plugin also has an opt-in KDoc harvester that writes a `kdoc-index.json`, and it deliberately does not synthesise declarations; `docs/compiler-plugin.md` records that decision (a "NO-GO") and everything else in detail.

## The KSP checks

`udea-codegen` validates as it generates, and fails the build with the same ids rather than emitting code that would fail somewhere else. It never catches an exception around a symbol: a component it cannot handle is an error at that symbol and a failed build, never a silently skipped file. A successful run prints nothing. Examples:

- A replicated field of a type it cannot store is `UDEA0006`, with the reason. There is no slow fallback path.
- A gizmo handle annotation names its fields as strings, because a Kotlin annotation cannot hold a property reference. So KSP checks every name and reports `UDEA0017` at the annotation, with a did-you-mean. Unchecked, the typo would surface as a compile error in a generated file in another module.
- An `@Rpc` with nothing to check fails the build, instead of generating a guard that checks nothing. See [Replication and Networking](Replication-and-Networking).

## Where diagnostics end up

- **Compiler and KSP errors** appear in the Gradle build output, prefixed with their id.
- **Asset diagnostics** are written to `diagnostics.json` by `udeaValidateAssets`, and returned directly by the `assets.validate` tool.
- **Build gates** in `build-logic` (module graph, contracts, documents) use their own id families, `UDEA-MG-*`, `UDEA-REL-*`, `UDEA-FRZ-*` and `UDEA-DOC-*`, rendered in the same `path:line:column: error: [id] message` shape. `build-logic` cannot depend on `udea-diagnostics`, since it configures the build that compiles it. See [Build and Verification](Build-and-Verification).

## See also

- [Assets](Assets)
- [ECS and Components](ECS-and-Components)
- [Build and Verification](Build-and-Verification)
- [Agent Tool Surface](Agent-Tool-Surface)
- [Gizmos](Gizmos)
