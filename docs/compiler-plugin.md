# The K2 compiler plugin

`udea-compiler-plugin` is the K2/FIR half of decision **D8** (spec §3.2): KSP2 emits files,
K2 adds diagnostics and reads what KSP structurally cannot. It is the most
Kotlin-version-fragile component in the project, and everything below exists to keep that
fragility from spreading.

**The plugin is never allowed to be required for the project to compile.** That sentence is
the whole design constraint. Spec §7 rates "the plugin blocks every phase" as a higher cost
than any feature the plugin could deliver.

---

## What it does

| Concern | Where | Notes |
|---|---|---|
| `@Net`/`@Sim` on a `val` (UDEA0001, UDEA0005) | `UdeaReplicatedPropertyChecker` | error at the property name |
| `@Q` on a non-`Float` (UDEA0003) | `UdeaReplicatedPropertyChecker` | error at the property name |
| more than 64 `@Net`/`@Sim` fields (UDEA0002) | `UdeaComponentFieldLimitChecker` | error on the class declaration |
| KDoc → `kdoc-index.json` | `KDocHarvestExtension` | opt-in, off unless `kdocIndex` is set |
| FIR declaration synthesis | **not shipped** | NO-GO; see [The synthesis gate](#the-synthesis-gate) |

Rule ids come from `udea-diagnostics`' `UdeaRules` and nowhere else. `udea-codegen`'s KSP
errors read the same constants and print the same `UDEAnnnn: ` prefix, so a developer sees one
id per defect whichever tool caught it (spec §5). `UdeaRuleParityTest` asserts that every id
the checkers can raise is registered.

### What the checkers deliberately do not decide

`udea-codegen` owns field lowering, and that table is growing: `NetId`, `Tick`, and value
types whose `var` properties lower to one field each (`position` → `position.x`,
`position.y`). A FIR checker that reproduced it would drift out of step with the generator on
the next type it learns, and every drift is a **false positive** — a red error on code that
builds. So the plugin decides only what it can decide from the type alone:

- the `val` rules fire only on a **directly stored** type (`Boolean`, `Int`, `Long`, `Float`,
  an enum). `@Net val position: Vector2` is legal, because `Replicator.apply` restores a
  composite by writing its components in place;
- the field ceiling counts *declared* annotated properties, which is a lower bound on the
  lowered field count, so it can never fire on a component the generator would accept;
- **UDEA0006 (unsupported field type) is not raised here at all.** KSP raises it, with the
  same id, one task boundary later. A missing in-editor diagnostic costs a developer one
  build; a false one costs them their trust in the checkers.

---

## The rule for merging a checker

**No checker merges without all three of these**, in `udea-compiler-plugin`'s compile-testing
suite:

1. a **positive case** — a fixture that must produce the diagnostic;
2. a **negative case** — `assertCompilesClean` on code that must *not*, including the
   well-formed component fixture (`Fixtures.WELL_FORMED_COMPILATION`), so a false-positive
   regression is caught the moment it appears;
3. a **position assertion** — the exact `line:column`, through `assertDiagnostic(...)` or an
   inline `// expect: UDEA0001 @ 10:9` marker. Presence alone is not enough: a rule that fires
   on the right symbol and one that fires on the enclosing file pass the same test without it,
   and only one of them satisfies Phase 0's "red at the property name" demo criterion.

**The suite gates Kotlin upgrades.** Spec §3.2's version policy: `./gradlew
:udea-compiler-plugin:check` must be green before any Kotlin version bump merges. The nightly
`kotlin-upgrade-probe` CI leg reruns it and reports the newest published Kotlin beside the
pinned one.

### Harness notes

The suite drives a real `K2JVMCompiler` through
`exec(MessageCollector, Services, K2JVMCompilerArguments)`, with the plugin supplied the way
Gradle supplies it — the plugin jar *plus its runtime classpath*, and
`plugin:<id>:<option>=<value>` arguments.

Issue #37 named `dev.zacsweers.kctfork:core`. It is not used, for two checkable reasons:

- **it cannot assert a position.** kctfork's result type is
  `DiagnosticMessage(severity, message)` — no location. Every position assertion would have to
  be scraped out of rendered message text, which is exactly the string scraping issue #37's
  own notes want avoided;
- **it is pinned to a different Kotlin.** kctfork 0.8.0 is built against Kotlin 2.2.0 and
  0.9.0 against 2.2.20; this module is pinned to the project's exact version so that "the
  suite must pass before any Kotlin upgrade merges" means something.

kctfork is a wrapper around `K2JVMCompiler`. The harness drives the same compiler, at the
pinned version, and gets a real `CompilerMessageSourceLocation` back.

---

## The plugin is optional, and both switches prove it

Two switches, outer and inner:

| Switch | Effect | State |
|---|---|---|
| `-Pudea.compilerPlugin.enabled=false` (Gradle) | no `udea-compiler-plugin` jar reaches a `kotlinCompilerPluginClasspath`, so no `-Xplugin` argument is produced at all | live, and asserted by `udeaVerifyCompilerPlugin` on every module |
| `-P plugin:dev.wildware.udea:enabled=false` (compiler) | the plugin loads and registers **zero** extensions | live, and asserted by `UdeaCompilerPluginRegistrarTest` |

### How the outer switch is wired

`UdeaCompilerPluginSupport` in `build-logic` is a `KotlinCompilerPluginSupportPlugin`, applied
by the `udea.kotlin-library` convention that every `udea-*` module and `moba` is on. It reads
the flag in `isApplicable`, so with the flag off it declares no compilation applicable and the
Kotlin Gradle plugin adds nothing to anything.

Two details are worth knowing before changing it:

- **it lives in `build-logic`, not in `udea-gradle`.** `udea-gradle` is a subproject of the
  build being configured, and Gradle cannot apply a plugin implemented by a sibling project.
  `udea-gradle` stays the home of the plugin a *consumer game* applies. Issue #164 asked for
  either, and only one of them is possible;
- **the plugin artifact is substituted, not resolved.** `getPluginArtifact()` can only name
  Maven coordinates, and `udea-compiler-plugin` is published nowhere, so
  `UdeaCompilerPluginSupport.apply` substitutes `dev.wildware.udea:udea-compiler-plugin` to
  `project(":udea-compiler-plugin")` on every compiler-plugin classpath. The coordinate's
  version is the unpublishable `substituted-to-project` on purpose: if the substitution ever
  stops being registered the build fails by name instead of quietly resolving a stale jar out
  of `mavenLocal()`.

Three `udea-*` modules are deliberately excluded, all for one reason — they are the plugin's
own runtime classpath, so applying it to them asks Gradle to build a jar in order to build
itself: `udea-compiler-plugin`, `udea-annotations` and `udea-diagnostics`. None of them
declares a `@Replicated` component, so nothing is lost.
`UdeaCompilerPluginWiringTest` re-derives that list from `udea-compiler-plugin/build.gradle.kts`
and fails if a fourth project dependency is added without widening it.

**The IDE still does not load the plugin, and that is settled.** Issue #43's spike returned
NO-GO (see [The synthesis gate](#the-synthesis-gate)): IntelliJ's `KotlinK2BundledCompilerPlugins`
is a closed enum of eleven bundled registrars. Applying the plugin through Gradle changes
nothing about that — it fails the **build**, which is where a defect has to be caught. Do not
try to make the IDE load it; the per-developer registry key at the end of the synthesis section
is the only lever, and it must not become a prerequisite for anything.

### What each gate enforces

- **`udeaVerifyCompilerPlugin`** (on `check`, so every `./gradlew build` runs it) reads the
  module's resolved `kotlinCompilerPluginClasspath*` and fails if it disagrees with
  `UdeaCompilerPluginWiring.appliesTo`: the plugin missing from a module that must have it, the
  plugin present on one that must not, or the plugin resolved from a repository instead of from
  `:udea-compiler-plugin`. Applying a compiler plugin is invisible, and a wiring that silently
  stopped applying compiles exactly as green as one that works — which is the state this
  repository was in for the whole of Phase 0. This is the gate that notices.
- **`plugin-disabled`** builds every module, `moba` included, with
  `-Pudea.compilerPlugin.enabled=false`, and names `udeaVerifyCompilerPlugin` explicitly. With
  the wiring live it is a real degrade-path test rather than a second copy of the `build` job.
  **This job must be configured as a required status check on `master`.** A workflow file
  cannot assert that; it is a repository branch-protection setting, set once by hand.
- **`checkers-fire`** writes a `@Net val` and a `@Q`-annotated `Int` into `udea-gradle`'s real
  source set, asserts the build fails with `UDEA0001` and `UDEA0003` at the computed
  `line:column` of each property name, then asserts the same file compiles clean with the flag
  off. It is the only gate that answers "does a defect in a real module stop a real build?", and
  for a whole phase the answer was no while every other gate was green. To run it by hand, write
  such a component into any `udea-*` module and compile it. **Required status check on
  `master`.**
- **`udeaVerifyPluginOptional`** (`./gradlew :udea-compiler-plugin:udeaVerifyPluginOptional`)
  fails if any production source under a `udea-*` module or `moba` references a
  `dev.wildware.udea.compiler.` type. Test sources are exempt. An empty scan is a failure, not
  a pass — a check that walks nothing stays green forever.

`CompilerPluginSwitchTest` in `build-logic` is the tripwire on all of this going away: it fails
if nothing implements a `KotlinCompilerPluginSupportPlugin`, if the convention stops applying
it, if the flag stops gating it, or if this document and `ci.yml` drift back to describing a
switch that does nothing.

### The degrade procedure

When a Kotlin release breaks the plugin:

1. add `udea.compilerPlugin.enabled=false` to `gradle.properties` (or pass `-P` on the
   command line);
2. open a tracking issue naming the Kotlin version and the failure;
3. **keep moving.** The project builds, the tests run, the game ships. What is lost is
   in-editor diagnostics that `udea-codegen`'s KSP errors still raise at the task boundary,
   under the same rule ids;
4. fix the plugin against the new compiler, get `:udea-compiler-plugin:check` green, flip the
   switch back.

Nothing in this procedure requires a code change outside `gradle.properties`. Keeping that
true is what `udeaVerifyPluginOptional` is for.

---

## The synthesis gate

Spec §3.2 gates FIR **declaration synthesis** — deleting `override fun type()` and
`companion object : ComponentType<T>()` from every Fleks component — behind a timeboxed spike
of *observed IDE behaviour*, because if IntelliJ does not resolve a synthesised member then
every component in the project shows "abstract member not implemented" in red, and D6 dropped
the IDEA plugin that could have fixed it.

### Verdict: **NO-GO**

Recorded 2026-08-22. The boilerplate stays. The gated `// deleted later — see §3.2` comment on
spec §3.1's `Transform` example is now permanent.

**Evidence** (IntelliJ IDEA Ultimate, build `IU-261.22158.277`, bundled Kotlin plugin, K2 mode):

1. The IDE's Kotlin plugin declares a registry key
   `kotlin.k2.only.bundled.compiler.plugins.enabled`, described as *"Allow only bundled K2
   compiler plugins to be used"*, with **`defaultValue="true"`**
   (`kotlin.plugin.k2.xml` inside `plugins/Kotlin/lib/kotlin-plugin.jar`).
2. It is read by `org.jetbrains.kotlin.idea.fir.extensions.KtCompilerPluginsProviderIdeImpl`.
3. The bundled set is a **closed enum**,
   `org.jetbrains.kotlin.idea.fir.extensions.KotlinK2BundledCompilerPlugins`, with exactly
   eleven entries — all-open, no-arg, sam-with-receiver, assignment, kotlinx-serialization,
   Compose, Lombok, Parcelize, scripting, js-plain-objects, dataframe — each naming a specific
   `CompilerPluginRegistrar` class. `udea-compiler-plugin` is not in it and cannot join it.

So on a **freshly opened, Gradle-synced project with default settings** — the state an agent
or a new checkout starts in, which the spike was told to test hardest — the IDE does not load
this plugin's FIR extensions at all. A synthesised `type()` would not resolve, and every
`@Replicated` component written without the boilerplate would be red. That is precisely the
failure mode the gate exists to prevent, so the answer is NO-GO regardless of anything a
warmed-up IDE might do afterwards.

The compiler side is unaffected: FIR checkers work exactly as designed at the command line
and in Gradle, which is where the build fails or passes.

**Consequences.**

- Issue #44 (adopt synthesis, delete the boilerplate) is closed **won't-do**. `override fun
  type()` and `companion object : ComponentType<T>()` stay in every component.
- `UdeaCompilerPluginRegistrar` registers **zero** `FirDeclarationGenerationExtension`s, and
  `UdeaCompilerPluginRegistrarTest` asserts it. The `synthesis` CLI option is kept because the
  option contract is already public, but it selects nothing.
- This is a *good* outcome for the plugin-optional constraint above: synthesis was the one
  change in this epic that could have broken the plugin-disabled leg.

### Seeing the checkers in the IDE

The same finding explains why `@Net val health` is **not** red in a default IntelliJ: the
plugin's diagnostics are not loaded there either. Spec §3.2 anticipated this — "if the IDE
fails to load the plugin the worst case is an invisible warning and the build still fails" —
and the build does still fail, with the same rule id.

To see the diagnostics in the editor, set
`kotlin.k2.only.bundled.compiler.plugins.enabled = false` in **Help → Find Action → Registry**
and resync Gradle. That is a per-developer setting and is not, and must not become, a
prerequisite for anything.

---

## KDoc propagation (Trello #12)

`KDocHarvestExtension` walks each compiled file, finds the doc comment attached to every
declaration and writes `kdoc-index.json`: FQN, repo-relative span, summary, `@param`s and the
`@return`/`@see`/`@throws` tags. Anything else is dropped rather than re-emitted, because a
tag naming something a generated file cannot see is malformed KDoc in generated code.
`[Foo]` links are rewritten to fully qualified names against the source file's own imports and
declarations, so they still resolve from the generated file's package.

It runs only when the `kdocIndex` option names an output path, and `repoRoot` must be given
with it — there is no default, because a guessed root produces a span that is relative and
wrong, and a wrong relative path survives into a shipped artefact.

**Still to land, in other modules:** the `udeaHarvestKdoc` Gradle step in `udea-gradle` that
runs the harvest *before* KSP and passes the index on as a processor option, and the
`addKdoc(...)` calls in `udea-codegen`'s emitters. A missing index entry must mean no KDoc,
never a build failure — which is also what keeps this feature from making the plugin
load-bearing.

---

## Known gaps

| Gap | Why |
|---|---|
| No `@Q(bits)`-out-of-range or `min >= max` checker | The rule id exists — `UdeaRules.MALFORMED_QUANTIZATION` is `UDEA0007`, and `udea-codegen` raises it three times from `ComponentModelBuilder`. The remaining blocker is FIR-side: a checker would have to constant-evaluate the `@Q` arguments to know `bits` or `min`/`max` at all, which is what `UdeaRules.kt`'s own note on the rule says. KSP sees the literals and already raises both. |
| No "`@Net`/`@Sim` on a property of a non-`@Replicated` class" checker | No registered id for it. The `val` rules do fire outside `@Replicated`, so the silent-failure case is not entirely uncovered. |
| No authority-vocabulary warning (`OwnerPredicted` on a class with no owner concept) | There is no owner concept in the tree yet, so the check has nothing to test against. |
| The `kotlin-upgrade-probe` CI leg does not actually build against an RC | `UdeaVersions.KOTLIN` is a hard-coded constant and `udea.kotlin-build-tool` fails configuration if the running KGP differs. Making it overridable is a `build-logic` change. |
