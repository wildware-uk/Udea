1e57458

# Issue #186 — Kotlin 2.4.20, KSP 2.3.12, JDK 21, and ComposeGL on the classpath

Branch `issue-186-kotlin-2-4-20-toolchain`, off `origin/example` at `28bfcde`.
Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda`.

The SHA above is the **last commit of the change**, the convention `BRIEF-166.md` followed. The
commit that adds this file sits on top of it and contains nothing but this file.

> **On the filename.** The contract I was given says `BRIEF.md` in the worktree root. `4f075c4`
> is on `origin/example` and removed the committed root copy, because `BRIEF.md` there was "a
> loaded gun pointed at whichever brief happened to be sitting in it". The repository's
> convention is `BRIEF-<N>.md`, so this is `BRIEF-186.md`. If the lead wants the other name, the
> fix is `git mv`.

Three commits:

```
1e57458 KspVersionRule is internal: nothing outside build-logic reads it
aa1bdf3 Move the gdx determinism pin to 1.14.2, with the re-read written down
618f4c4 Kotlin 2.4.20, KSP 2.3.12, JDK 21, and ComposeGL on udea-render's classpath
```

Every run quoted below was made on this exact content. `1e57458`'s change was in the worktree
before the builds ran and was committed afterwards, so the tree that produced section 3's green
build is `1e57458`'s tree; the only other thing in `git status` is the `gradlew` mode bit this box
needs, which is deliberately not committed.

---

## 1. The evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew \
  :udea-render:test --tests 'dev.wildware.udea.render.ui.compose.ComposeGlLinkTest' --rerun-tasks
```

The recorded transcripts (`14-evidence-green.txt`, `15-evidence-red-M1.txt`) add `--no-daemon`,
and that is about this box rather than about the command: another project here runs
`gradlew --stop`, which killed two of my earlier runs mid-flight. Either form works.

Three tests, and each is red for a different half of the ticket:

| Test | What it fails on |
|---|---|
| `composing the probe places a ComposeGL text node with the text it was given` | the toolkit does not compose at all |
| `clicking the probe's button recomposes the count through Compose's own state` | it composes but the Compose runtime does not keep state |
| `the gdx backend links against this module's own libGDX and ComposeGL` | `composegl-gdx` and `udea-render` disagree about which `Batch` they mean |

**Proof it goes red when the feature is reverted** is mutation M1 in section 8: with
`alias(libs.plugins.composeCompiler)` taken back out of `udea-render/build.gradle.kts`, the same
command cannot even reach the test — `:udea-render:compileKotlin` fails in the JVM back end on
`remember`. Section 8 carries the literal diff and the spliced failure for that and for seven
other mutations, including one honest negative and one control.

---

## 2. Summary: what moved, and the decisions inside it

### 2.0 The whole diff, so every claim below has a denominator

```
$ git diff --stat origin/example..HEAD
 .github/workflows/ci.yml                           |  32 ++---
 AGENTS.md                                          |  11 +-
 README.md                                          |   2 +-
 build-logic/build.gradle.kts                       |   8 ++
 .../dev/wildware/udea/build/KspVersionRule.kt      |  78 +++++++++++++
 .../kotlin/dev/wildware/udea/build/UdeaVersions.kt |   4 +-
 .../dev/wildware/udea/build/UdeaVersionsTest.kt    |  74 +++++++++++-
 build.gradle.kts                                   |   8 +-
 common/build.gradle.kts                            |   8 +-
 .../kotlin/dev/wildware/udea/ability/Ability.kt    |  17 ++-
 .../dev/wildware/udea/ecs/system/AbilitySystem.kt  |   9 +-
 .../wildware/udea/ecs/system/AttributeSystem.kt    |   7 +-
 .../udea/ecs/system/NetworkServerSystem.kt         |  10 +-
 .../dev/wildware/udea/network/serializers.kt       |   7 +-
 determinism-allowlist.txt                          |   2 +-
 determinism-audit.md                               |  53 ++++++++-
 docs/budgets.md                                    |   4 +-
 docs/module-graph.md                               |  20 +++-
 example/build.gradle.kts                           |   8 +-
 .../wildware/udea/example/system/GameUnitSystem.kt |  14 ++-
 .../udea/example/system/PlayerControlSystem.kt     |  48 +++++---
 .../udea/example/system/ProjectileSystem.kt        |  44 ++++---
 .../wildware/udea/example/system/UnitAISystem.kt   | 108 +++++++++--------
 gradle-plugin/build.gradle.kts                     |  22 +++-
 .../dev/wildware/udea/assets/AssetScanner.kt       |  11 +-
 gradle/libs.versions.toml                          |  39 ++++++-
 .../wildware/moba/physics/Box2DPhysicsWorldTest.kt |   7 +-
 .../udea/assets/compiler/EmbeddedJvmTarget.kt      |  25 ++++
 .../wildware/udea/assets/compiler/scan/KtParser.kt |  37 ++++++
 .../udea/assets/compiler/script/UdeaAssetScript.kt |   3 +-
 .../compiler/transpile/TranspiledAssetLoader.kt    |   3 +-
 .../udea/assets/compiler/EmbeddedJvmTargetTest.kt  |  76 ++++++++++++
 udea-codegen/build.gradle.kts                      |  14 +++
 .../dev/wildware/udea/codegen/ProcessorHarness.kt  |  52 ++++++++-
 .../wildware/udea/codegen/ProcessorLoggingTest.kt  |  17 ++-
 .../udea/compiler/UdeaCompilerPluginRegistrar.kt   |  23 +++-
 .../wildware/udea/compiler/fir/UdeaDiagnostics.kt  |  34 ++++--
 .../compiler/fir/UdeaReplicatedPropertyChecker.kt  |   9 +-
 .../udea/compiler/UdeaCommandLineProcessorTest.kt  |  10 +-
 .../compiler/UdeaCompilerPluginRegistrarTest.kt    |  17 ++-
 udea-render/build.gradle.kts                       |  19 +++
 .../udea/render/ui/compose/ComposeGlProbe.kt       |  71 +++++++++++
 .../udea/render/ui/compose/ComposeGlLinkTest.kt    | 130 +++++++++++++++++++++
 43 files changed, 1011 insertions(+), 184 deletions(-)
```

The wrapper is **not** in that list, and that is deliberate: this box needs the wrapper's
executable bit set to run it at all, the mode shows as a modification in the working tree, and
committing a mode flip on the wrapper is a finding rather than a fix.


### 2.1 Three versions moved, and only one of them is a number

- **Kotlin 2.2.10 -> 2.4.20**, everywhere it is pinned: `gradle/libs.versions.toml`,
  `UdeaVersions.KOTLIN`, the root `kotlin("jvm")` declaration, `common` and `example`'s own
  plugin blocks, and `gradle-plugin`'s `buildscript` classpath.
- **KSP `2.2.10-2.0.2` -> `2.3.12`.** This is the shape change the issue predicted. From 2.3.0
  KSP publishes one version of its own and names no compiler in it, so a KSP version can no
  longer be read off the Kotlin version, and the old assertion in `UdeaVersionsTest` —
  `"the KSP version is built against the project Kotlin version"` — asserted something that is
  no longer expressible. **Why 2.3.12 specifically:** it was the newest 2.3.x on Maven Central when I
  checked, and it is the line the issue names; it resolves and runs KSP2 against the 2.4.20 compiler in this tree,
  which is the only property that matters and the only one a build can check.
- **libGDX 1.13.5 -> 1.14.2**, which the issue did not ask for and is not optional:
  `composegl-gdx:0.5.0` resolves gdx transitively, and leaving 1.13.5 pinned puts two gdx
  versions in one graph with native libraries loaded by name. Recorded as a decision comment on
  the issue.
- **JDK 17 -> 21**, also not in the issue, and also not optional — see 2.2.

### 2.2 The surprise: it failed at *resolution*, not at metadata

The issue's premise is a metadata-version rejection, and that premise is real and reproduced
verbatim (M4d). But the first failure on adding `composegl-gdx` was earlier than that:

```
Dependency resolution failed because of conflict(s) on the following attribute(s):
  - Variant 'apiElements' capability dev.wildware.composegl:composegl-gdx:0.5.0
    ... is only compatible with JVM runtime version 21 or newer
```

Every published `composegl-gdx` release declares `org.gradle.jvm.version = 21` in its Gradle
module metadata, and its class files are major 65. So the JDK move is a precondition of the
ticket rather than a side quest: `JVM_TOOLCHAIN` is now 21, the root `allprojects` block targets
`JvmTarget.JVM_21` and `JavaVersion.VERSION_21`, `gradle-plugin` uses `jvmToolchain(21)`, and all
sixteen `java-version` entries in `.github/workflows/ci.yml` say `"21"`.

That move had a consequence with teeth. `udea-assets-compiler` embeds the Kotlin compiler twice
at build time and both call sites had a hardcoded `-jvm-target` of `"17"`, which produced ten
copies of

```
Cannot inline bytecode built with JVM target 21 into bytecode that is being built with JVM target 17. Specify proper '-jvm-target' option.
```

from `AccessorCompilationTest`. Ten is a count, so it is a checked one: the mutation was re-run
to keep the report, and `mutations/M5b-AccessorCompilationTest.xml` holds the assertion message
with the line repeated ten times inside it. Fixed as one constant,
`EMBEDDED_JVM_TARGET`, with `EmbeddedJvmTargetTest` reading the class-file major version out of
this module's own compiled bytecode and requiring the constant to name it — so the next
toolchain move makes a test red instead of making a build-time compile fail somewhere else with
a message about inlining. That test carries its own control: it checks the class-file reader
agrees with `java.class.version` on `java.lang.Object`, because a reader that always returned
the same number would pass the real assertion too.

### 2.3 The compiler plugin had to be ported, not bumped

`udea-compiler-plugin` did not compile on 2.4.20. The shapes were found by disassembling
`kotlin-compiler-embeddable-2.4.20.jar` with `javap`, not guessed:

- `CompilerPluginRegistrar.pluginId` is abstract now. It reads `UdeaCompilerPlugin.PLUGIN_ID`,
  the same constant the CLI processor and the `gradle-plugins` descriptor use.
- `RootDiagnosticRendererFactory` is gone. A diagnostic factory now carries its own renderer,
  taken from the `KtDiagnosticsContainer` its `error1`/`warning0` delegate was declared on, so
  `UdeaDiagnostics` overrides `getRendererFactory()` and the registrar registers nothing. That is
  strictly better than what it replaced: before, a factory whose map had not been registered
  rendered as the literal text `null`.
- `KtDiagnosticFactoryToRendererMap`'s constructor is `internal`; the replacement is a top-level
  function of the same name returning a `Lazy`. `Renderers.MAP` is therefore `by` and not `=`,
  and the laziness is load-bearing: the container reads the renderer factory and the renderer
  factory reads the container's `factories`, so eagerly whichever the JVM touched first would
  see the other half-built.
- `FirProperty.symbol.callableId` is nullable — a *local* property has no callable id. The
  checker falls back to the simple name rather than returning early, because `@Net` on a local
  `val` is exactly the silent-failure case that checker's KDoc refuses to narrow away.

`udea-assets-compiler`'s parser needed three new opt-in markers and one new runtime
precondition: `KotlinCoreEnvironment.createForProduction` now requires `extensionsStorage` to be
set, and without it `:moba:udeaScanAssets` threw `IllegalStateException: Extensions storage is
not registered` at *run* time with an unchanged signature. An empty
`CompilerPluginRegistrar.ExtensionStorage()` is the right value there: that parser runs no
plugin.

### 2.4 Decisions I had to make, with the alternative I rejected

**One language version for the whole repository.** `:common` (11 errors) and `:example` (25 —
both counts measured, see M7 and M7b in section 8) hit
Kotlin 2.4's stable context parameters, which now compete with implicit receivers — the
compiler's words are *"uses an implicit receiver shadowed by a context parameter"* — plus one
class literal on a nullable type. The cheap fix was `languageVersion = KOTLIN_2_2` on those two
modules. I tried it, found it silenced only the class-literal error, and **reverted it**: two
language versions in one repository is a second dialect nobody is tracking. (That measurement
is prose rather than a transcript — the worktree state it was made in no longer exists. What is
a transcript is M7 and M7b, which show what the unfixed source does on 2.4.20.)

Every site is fixed in source instead: `with(world) { ... }` around the Fleks system bodies in
`example`, explicit `this.` inside `AbilitySpec.commit()`, and `<reified T : Any>` on one
serializer overload. Each carries a comment saying why the rewrite is behaviour-preserving — the
`AbilitySpec.commit()` one argues it from the call graph, because `commit()` has a single caller
and the context `spec` there is the same object as `this`.

**I did not drop `example` from `settings.gradle.kts`.** The project's own documents say that is
safe, and it would have made `example`'s 25 errors disappear. A build that goes green by no longer looking
is the failure mode those same documents are most emphatic about, so `example` stays in and its
sources are fixed. #142 can delete it when #142 decides to.

**`kotlin-dsl` had to go from `gradle-plugin`.** Kotlin 2.4 refuses `languageVersion` below 2.0
outright, and `kotlin-dsl` pins 1.8. Swapped to `java-gradle-plugin`, which cost exactly one
call site: `AssetScanner`'s `tasks.register("scanAssets") { doLast { ... } }` used a receiver
lambda that is a Kotlin DSL extension. Adding `implementation(gradleKotlinDsl())` did **not**
restore it — measured, not assumed — so that one site is now `{ task -> task.doLast { ... } }`.

**A KSP 2.3.12 info message is filtered, and the filter is asserted.** KSP 2.3.12 logs
`Processor '...UdeaSymbolProcessor' has not opted in for upcoming features yet...` at `info`, and
`ProcessorLoggingTest` asserted the processor logs nothing at all. I checked by disassembly that
no published KSP 2.3.12 API exposes that opt-in (`KSPConfig.Builder`'s booleans are
`incremental`, `incrementalLog`, `allWarningsAsErrors`, `mapAnnotationArgumentsInJava`,
`experimentalPsiResolution`), so the harness filters that one message — and the test now asserts
both that the filtered list is empty **and** that exactly one info message still contains the
notice, so the exclusion cannot quietly outlive the message it excludes.

**Two written-down claims were replaced rather than edited.** `UdeaVersionsTest`'s KSP-scheme
assertion and `ProcessorHarness`'s hardcoded `"2.2"`/`"17"` both asserted things that stopped
being true. The scheme assertion became `KspVersionRule`, which checks the weaker property that
*is* still checkable — a KSP version must not name a Kotlin compiler other than this project's —
and whose KDoc names where the real proof lives (`:udea-codegen:test`, `udeaCheckProtocolLock`,
each module's `kspKotlin`), because no string comparison can carry it. The harness now reads the
language version and JVM target from `UdeaVersions` through system properties, with
`checkNotNull` and no default, so a missing property fails loudly instead of testing last year's
toolchain.

**The gdx determinism pin.** Moving libGDX failed `udeaVerifyDeterminism` under `ALLOW005`, by
design. Section 7 is the re-read; the decision and its rejected alternatives are also a comment
on the issue.

### 2.5 What I did not do

- **No contract file was touched.** `docs/contracts/` is byte-identical to `origin/example` and
  `udeaVerifyContracts` runs green on `check`. The toolchain move needed none of them.
- **No `-Xskip-metadata-version-check`.** It appears nowhere in the diff; `git diff
  origin/example..HEAD | grep -c skip-metadata-version-check` is 0.
- **No scene2d removal, no `UiLayer` rewrite, no `MobaHud` port.** Those are #187/#188/#189, and
  `UiLayer`/`UiScreen`/`MobaHud` still run on scene2d on this branch.
- **The `gradlew` mode change is not committed.** This box needs `chmod +x gradlew`; it shows as
  `M gradlew` in `git status` and it is deliberately not in any commit. `git diff --stat
  origin/example..HEAD` does not list it.

### 2.6 Flagged, not fixed

- **The harness prompts are stale.** `.claude/agents/*.md` and `.claude/skills/dev-team/SKILL.md`
  still describe the toolchain as Kotlin 2.2.10 / JDK 17. They are the lead's own prompt files
  rather than repository documents, so I flagged them instead of editing them.
- **A false sentence in `udea-core`, found while re-reading the determinism audit.**
  `udea-core/src/main/kotlin/dev/wildware/udea/core/physics/NoOpPhysicsWorld.kt:29` says "no
  module imports `com.badlogic.gdx.physics.box2d`".
  `moba/src/main/kotlin/dev/wildware/moba/physics/Box2DPhysicsWorld.kt` does, and
  `determinism-audit.md` section 3 says so two paragraphs above its own rows. It was presumably
  true when written. Not fixed on a toolchain branch — a physics KDoc correction belongs in a
  change a reviewer is looking at physics in — and recorded on the issue so it is not
  rediscovered a third time.
  **I grepped for the class rather than stopping at the instance:** every `^import com.badlogic`
  in the repository is in `common`, `example` or `moba`, and the only "no module imports" style
  claims about gdx anywhere in the tree are that one line and the audit's own paragraph, which
  is correct.

---

## 3. `sh gradlew build`

Two runs, and the second is the one that is evidence about the tests.

**The first one is a true green that says less than it looks like.** `12-clean-build.txt`:

```
$ JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew clean build --console=plain

BUILD SUCCESSFUL in 23s
224 actionable tasks: 142 executed, 74 from cache, 8 up-to-date
Configuration cache entry reused.
```

74 tasks `FROM-CACHE`, and `:udea-render:test`, `:moba:test`, `:udea-codegen:test` and
`:udea-render:udeaGlTest` are among them. Cached results are real results, but they are not a
run on this tree, so quoting that as "the build is green" would be the shape of claim the rest of
this document is about.

**So here is the one where every test executed.** `17-clean-build-nocache.txt`:

```
$ JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew clean build --no-build-cache --console=plain

BUILD SUCCESSFUL in 2m 45s
233 actionable tasks: 224 executed, 9 up-to-date
Configuration cache entry stored.
EXIT=0
```

No `-x`, no excluded task, cache off, 458 `> Task` lines in the transcript. Summing the JUnit
XMLs it left behind:

```
$ python3 -c '<sum tests/failures/errors/skipped over */build/test-results/**/TEST-*.xml>'
xml files=418 tests=2869 failures=0 errors=0 skipped=35
```

That count is a snapshot of this run rather than a property of the repository — it moves the
moment anyone adds a test — and the 35 skipped are the GL tests, which this run *correctly* skips
because it has no `DISPLAY`. Section 4 is where they actually run.

**`build-logic`'s own tests are not part of root `build`**, which is why CI runs them separately
and so did I. `22-build-logic-check.txt`:

```
$ JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew -p build-logic check --rerun-tasks --console=plain

BUILD SUCCESSFUL in 1m 18s
13 actionable tasks: 13 executed
EXIT=0
```

That is the run that covers `UdeaVersionsTest`, `KspVersionRule`'s tests, `AuditTest` — which is
what holds `determinism-audit.md` to the versions the allowlist pins — and
`FloatPortabilityTest`.

### Two things about this build worth a reviewer's attention

**It was killed twice before it passed, and not by anything on this branch.** Two runs died with

```
* What went wrong:
Gradle build daemon has been stopped: stop command received
```

which is another project on this box running `gradlew --stop` against the shared 8.13 daemon;
the second time it also left a half-written `build-logic/build/classes/kotlin/main` that the next
run could not delete. Both were re-run, and the later stages use `--no-daemon` so a stop aimed at
somebody else's daemon cannot land on mine. Reported here rather than quietly, because a killed
build is not a failing build and the difference matters.

**`udeaDaemonBudget` passed under real load.** The documented risk is that it fails inside a full
`build` on a loaded box. This run's load average went from 8 to **37** while it ran — three other
projects were building — and the budget still passed inside `:udea-assets-compiler:check`, so
there is no solo re-run to report.

**New compiler warnings, none of them errors, and two are worth naming:**

- `w: The argument '-Xcontext-parameters' is redundant for the current language version 2.4.` —
  the flag is now the default. It is set in `udea-compiler-plugin`, `common` and `example`'s build
  files. I left it: removing it is a behaviour-neutral tidy-up in three files including two in the
  old tree, and it is not what this ticket is for.
- `w: The used Gradle version (Gradle 8.13) is deprecated ... The minimum supported Gradle version
  will become Gradle 8.14.4 in Kotlin 2.5.0.` — a note for whoever moves Kotlin next: **that move
  will also need Gradle**. Nothing to do today.

---

## 4. The GL tests, run for real under xvfb

The ticket touches `udea-render`, so a green `build` says nothing here: `-Pudea.render.requireGl`
defaults to `false` and `$DISPLAY` is empty on this box, so `udeaGlTest` and `udeaAgentGlTest`
**skip** and the build stays green.

So they were run for real, **after** the plain build rather than before it — `check` writes
SKIPPED XMLs over whatever was there, so the order decides whether the results left in the tree
are the honest ones. `13-gl-xvfb.txt`:

```
$ xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true --rerun-tasks --no-daemon --console=plain
```

```
> Task :udea-render:udeaGlTest
```
```
> Task :udea-agent-host:udeaAgentGlTest
```
```
BUILD SUCCESSFUL in 55s
44 actionable tasks: 44 executed
EXIT=0
```

`-Pudea.render.requireGl=true` is what makes a missing context an error instead of a skip, and
`--rerun-tasks` is what stops a cached SKIPPED result standing in for a run. The XMLs were copied
out at capture time to `issue186-evidence/gl-xml-final/`, and this is what they contain:

```
dev.wildware.udea.agent.host.gl.OffscreenRenderToolsTest: tests=7 failures=0 errors=0 skipped=0 time=2.129
dev.wildware.udea.agent.host.gl.OverlayCaptureIsolationTest: tests=1 failures=0 errors=0 skipped=0 time=0.521
dev.wildware.udea.render.gl.GlCaptureDeterminismTest: tests=4 failures=0 errors=0 skipped=0 time=1.453
dev.wildware.udea.render.gl.GlCaptureTest: tests=5 failures=0 errors=0 skipped=0 time=1.313
dev.wildware.udea.render.gl.GlOverlayIsolationTest: tests=1 failures=0 errors=0 skipped=0 time=0.329
dev.wildware.udea.render.gl.GlThreadShutdownTest: tests=1 failures=0 errors=0 skipped=0 time=0.115
dev.wildware.udea.render.gl.OffscreenBackendTest: tests=8 failures=0 errors=0 skipped=0 time=1.975
TOTAL tests=27 failures+errors=0 skipped=0
```

**`skipped=0` on every one of them is the line that matters**, because a green `udeaGlTest` with
`skipped=7` is exactly the false pass the flag exists to prevent. Real LWJGL3 contexts, on
llvmpipe, capturing real frames.

---

## 5. Images

### What the pictures are for, and what they are not

**No ComposeGL frame is rendered on this branch.** That is deliberate and the reason is specific
rather than general: drawing a ComposeGL tree needs `GdxCanvas`, which needs a live `Batch` and a
`GdxFonts` with a registered typeface, which needs the gdx-freetype **natives** —
`composegl-gdx:0.5.0`'s POM brings `gdx-freetype` but no `gdx-freetype-platform`. Wiring that is
#187's job, and doing it here would be the `UiLayer` rewrite the issue puts out of scope. So the
run-time half of the link is proved by composing against ComposeGL's own `HeadlessBackend` and
reading nodes back out, which is what `composegl-testing`'s `uiTest` harness exists for.

**What the pictures do prove is the thing this ticket could plausibly have broken and nobody
asked about.** libGDX moved a minor version under the renderer — 1.13.5 to 1.14.2 — and the JDK
moved from 17 to 21. A green test suite would not show a HUD drawing in the wrong place or a
sprite failing to load. So all three shot tasks were run under xvfb and every frame was looked
at:

```
$ xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew :moba:runShot :moba:runMatchShot :moba:runLaneShot --no-daemon --console=plain

BUILD SUCCESSFUL in 1m 43s
56 actionable tasks: 3 executed, 53 up-to-date
EXIT=0
```

All ten, in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`:

| File | What it shows | What it proves |
|---|---|---|
| `issue186-renderer-after-gdx-1-14-2.png` | all ten frames tiled | the whole renderer surface at a glance, under gdx 1.14.2 on JDK 21 |
| `issue186-roster.png` | six characters on tiled grass with shadows | the sprite pipeline still resolves art. A `UDEA0032` would have been a real defect here, and there is none |
| `issue186-match-hud.png` | score bar, selection ring, health bars, ability bar with live cooldowns, event log | the HUD still draws — and it is still scene2d, which is what the issue's out-of-scope line requires |
| `issue186-match-melee.png` | an orc and a soldier trading hits mid-swing | animation frames and damage numbers under the new gdx |
| `issue186-match-spin.png` | the orc spin ability firing | an ability's own visual effect |
| `issue186-match-item-bar.png` | the shared item bar populated | the #166 work this branch sits on top of still renders |
| `issue186-match-item-fired.png` | an item active discharging | the same, in its fired state |
| `issue186-match-result.png` | the `SOLDIER WINS` panel | the match-end overlay, text centred and not clipped |
| `issue186-lane-wave.png` | two towers, lane bands, a creep wave walking | the lane renderer |
| `issue186-lane-farm.png` | creeps being farmed at the lane midpoint | the same, mid-fight |
| `issue186-lane-clash.png` | the two waves meeting | the same, at the clash |

**Looked at, not measured.** Every frame draws its sprites, its shadows, its bars and its text;
nothing is a black rectangle and nothing is missing art. The one thing to point at rather than
leave for a reviewer to notice: in `issue186-match-hud.png` a group of creeps at the top of the
frame is partly behind the score bar. That is where the camera puts them in that scenario, not a
HUD layout that has moved — and the reason I can say so without a before-and-after is that this
branch changes no drawing code at all. The whole of its `udea-render` diff is the build file plus
the two new Compose files, and the whole of its `moba` diff is one line in a test
(`git diff --stat origin/example..HEAD` in section 2 is the complete list). **I did not render
the same frame on `origin/example` to compare**, so that is an argument from the diff rather than
a measurement.

---

## 6. The issue, criterion by criterion

### AC-1 — `sh gradlew build` green, no `-x`, on Kotlin 2.4.20

Two runs, because the first one was not evidence about the tests. Section 3 has both: a
`clean build` that came back in 23s with 74 tasks `FROM-CACHE`, and then a
`clean build --no-build-cache` where every test task executed. The second is the one to read.

"On Kotlin 2.4.20" is a separate claim from "green", so it is proved separately rather than
inferred from the catalog. `:udea-render:dependencies --configuration compileClasspath`
(`21-render-deps.txt`) resolves `org.jetbrains.kotlin:kotlin-stdlib:2.4.20` with every transitive
stdlib request upgraded to it, and `UdeaVersionsTest` asserts `UdeaVersions.KOTLIN` equals the
catalog's `kotlin` entry, so the mirror cannot drift from the string the build actually uses.
M4 and M4d are the other direction: put 2.2.10 back and the tree stops compiling.

### AC-2 — a `udea-render` test that composes a real ComposeGL `@Composable` and asserts on the result, red if the plugin or the dependency is removed

`udea-render/src/test/kotlin/dev/wildware/udea/render/ui/compose/ComposeGlLinkTest.kt`, three
tests, against `ComposeGlProbe` in **main** sources — deliberately main, so it proves something
about `compileKotlin` and not only about `compileTestKotlin`.

- *composes a real `@Composable`*: `uiTest(size, HeadlessBackend()) { ComposeGlProbe() }`, then
  `ui.assertExists(tag)` and `ui.text(tag)`. It is ComposeGL's own `uiTest` harness, not a
  home-made one.
- *asserts on the result*: the greeting test reads the text back off the node; the click test
  presses a tagged `Button`, settles, and reads the count out of Compose's slot table, which is
  the half no compile can show.
- *red if the plugin is removed*: **M1** — `:udea-render:compileKotlin` FAILED, back end, on
  `remember`.
- *red if the dependency is removed*: **M2b** (both artifacts) and **M3** (`composegl-gdx`
  alone). **M2** (`composegl-ui` alone) stayed green and is reported as such, with the reason.

### AC-3 — `net-protocol.lock` and `expected-generated-hashes.txt` regenerated **if** the KSP move shifts them, with the diff read and reported

They did not shift, so nothing was regenerated, and section 9 says how that is known rather than
assumed: neither file appears in `git diff --stat origin/example..HEAD`, and
`udeaCheckProtocolLock` plus the hash-fixture test both run on `check`, so a drift would have
failed the green build rather than landing quietly. **Ids moved by zero.**

### The scope items, one by one

| Issue scope item | Where it landed |
|---|---|
| `kotlin` -> 2.4.20 in the catalog | `gradle/libs.versions.toml` |
| `udea-compiler-plugin` FIR/IR | `UdeaCompilerPluginRegistrar.kt`, `UdeaDiagnostics.kt`, `UdeaReplicatedPropertyChecker.kt` and the two tests that name the moved types; see 2.3 and M4 |
| `udea-assets-compiler` scripting host | `KtParser.kt` now opts in to `ExperimentalCompilerApi`, `CompilerConfiguration.Internals`, `MessageCollectorAccess` and `CoreEnvironmentDeprecation`, and sets an empty `extensionsStorage`; `EMBEDDED_JVM_TARGET` replaced the two hardcoded `"17"`s |
| `build-logic`'s `UdeaVersions.KOTLIN` | `UdeaVersions.kt`, asserted against the catalog by `UdeaVersionsTest` |
| the `udea.kotlin-build-tool` stdlib pin | it reads `UdeaVersions.KOTLIN` and `check`s the running Kotlin against it, so moving the constant moved the pin with no edit here — `udea.kotlin-build-tool.gradle.kts:29`, `udea.kotlin-library.gradle.kts:88`. `udeaVerifyKotlinPin` runs on `check` |
| move KSP, and say which and why | `2.3.12`; the reasoning is in 2.1 and in the catalog comment, with `KspVersionRule` and M6/M6control as the executable part |
| `org.jetbrains.kotlin.plugin.compose` on `udea-render` **only** | `alias(libs.plugins.composeCompiler)` in `udea-render/build.gradle.kts`, with a comment saying why it is not in the `udea.kotlin-library-gl` convention |
| `composegl-ui` + `composegl-gdx` 0.5.0 | `udea-render/build.gradle.kts`, `implementation` not `api` |
| `google()` in repositories | **already there** — `build.gradle.kts`'s `allprojects { repositories { ... } }` block, line 46, unchanged by this branch. The issue expected it to need adding; it did not, and the androidx artifacts resolve, which is visible in `21-render-deps.txt` |
| prove the two halves link | AC-2 |

---

## 7. The determinism re-read (`ALLOW005`)

Moving libGDX failed `clean build`:

> `determinism-allowlist.txt: [ALLOW005] line 35: the audit was performed against gdx 1.13.5,`
> `but the build resolves 1.14.2. Re-read the affected rows of determinism-audit.md against the`
> `new source, then move the pin. Iteration order, lookup tables and pool reuse are all things a`
> `point release is entitled to change.`

Both jars are in the Gradle module cache, so the re-read is a **diff rather than a reading**:
`javap -p -c` over every class section 3 of the audit makes a claim about, in each version, then
a per-class diff — and a second diff with constant-pool indexes normalised, so a pool index
shifting does not read as a behaviour change and a behaviour change cannot hide behind one. A
diff cannot miss a change in a method nobody thought to re-check; a re-reading can.

Per-class result, from
`/srv/ssd1/workspace/Udea/build/issue186-evidence/determinism/javap-gdx-{1.13.5,1.14.2}.txt`:

```
com.badlogic.gdx.math.Vector2: raw-diff-lines=14 pool-normalised-diff-lines=14
com.badlogic.gdx.math.MathUtils: raw-diff-lines=0 pool-normalised-diff-lines=0
com.badlogic.gdx.math.MathUtils$Sin: raw-diff-lines=0 pool-normalised-diff-lines=0
com.badlogic.gdx.math.RandomXS128: raw-diff-lines=0 pool-normalised-diff-lines=0
com.badlogic.gdx.utils.ObjectMap: raw-diff-lines=51 pool-normalised-diff-lines=51
com.badlogic.gdx.utils.ObjectSet: raw-diff-lines=73 pool-normalised-diff-lines=73
com.badlogic.gdx.utils.IntMap: raw-diff-lines=73 pool-normalised-diff-lines=73
com.badlogic.gdx.utils.IntSet: raw-diff-lines=19 pool-normalised-diff-lines=19
com.badlogic.gdx.utils.LongMap: raw-diff-lines=75 pool-normalised-diff-lines=75
com.badlogic.gdx.utils.Array: raw-diff-lines=64 pool-normalised-diff-lines=47
com.badlogic.gdx.utils.IntArray: raw-diff-lines=83 pool-normalised-diff-lines=75
com.badlogic.gdx.utils.Pool: raw-diff-lines=0 pool-normalised-diff-lines=0
```

`MathUtils`, `MathUtils$Sin`, `RandomXS128` and `Pool` are identical, which is the part that
matters: the float story in 3.1, the sin table filled at class-init by `java.lang.Math.sin`, the
`RandomXS128()` seeding and the LIFO `Pool.freeObjects` are the same bytecode the rows were
written against. What moved is a new `Vector2.One` static (`len`, `len2`, `nor`, `angleDeg` are
byte-identical), an added `putMissing` on the three maps going through the same
`locateKey`/`resize`, one bytecode offset inside `ObjectSet`, `Array`'s copy constructor
widening to `Array<? extends T>`, gdx's own `StringBuilder` replaced by `java.lang.StringBuilder`
in two `toString`s, and `IntArray.truncate` now rejecting a negative argument. Section 3.0 of
`determinism-audit.md` now carries that table with the reasoning per row.

The audit's two used-surface checks were **re-run rather than assumed**:
`grep -rn "^import com.badlogic"` over the three scopes' `src/main` returns nothing, and
`javap -p -c` over every `.class` in each scope's `build/classes/kotlin/main` yields zero
`com/badlogic/gdx` targets.

**And the grep was run against a known positive first**, because a grep that has only ever
returned nothing is not a check yet. The identical pipeline over `:moba`'s
`dev.wildware.moba.physics` classes returns 197 matching lines:

```
control dump: /srv/ssd1/workspace/Udea/build/issue186-evidence/determinism/javap-simscope-control-moba-box2d.txt (4838 lines)
com/badlogic/gdx hits: 197
com/badlogic/gdx/math/Vector2
com/badlogic/gdx/physics/box2d/Body
com/badlogic/gdx/physics/box2d/BodyDef
com/badlogic/gdx/physics/box2d/BodyDef$BodyType
com/badlogic/gdx/physics/box2d/Box2D
com/badlogic/gdx/physics/box2d/ChainShape
com/badlogic/gdx/physics/box2d/CircleShape
com/badlogic/gdx/physics/box2d/Contact
```

Then the pin moved, and `AuditTest` in `build-logic` — which asserts the audit is stamped with
the versions the allowlist pins — passes on the new pair. The dumps, the diffs and the control
are all under `/srv/ssd1/workspace/Udea/build/issue186-evidence/determinism/`.

---

## 8. Mutations

Every row's diff is the literal `git diff` from the run that produced the failure beside it,
taken from `/srv/ssd1/workspace/Udea/build/issue186-evidence/mutations/`. Nothing here is
retyped.

Each row is: what was neutralised, the command, and what went red. The diff under each row is
the literal `git diff` from that run — `M<n>.diff` in the evidence directory — and the failure
text is spliced from `M<n>.out` in the same place, with every elision marked.

| # | What it neutralises | Result |
|---|---|---|
| M1 | the Compose compiler plugin on `udea-render` | `:udea-render:compileKotlin` FAILED in the JVM back end |
| M2 | `composegl-ui` alone | **green — an honest negative, see below** |
| M2b | `composegl-ui` and `composegl-gdx` together | `:udea-render:compileKotlin` FAILED, unresolved `androidx` and `composegl` |
| M3 | `composegl-gdx` alone | `:udea-render:compileTestKotlin` FAILED, unresolved `gdx` and `GdxBackend` |
| M4 | Kotlin back to 2.2.10 (catalog + mirror + old tree) | `:udea-compiler-plugin:compileKotlin` FAILED on the 2.4 FIR APIs |
| M4d | Kotlin back to 2.2.10 **with the 2.4 port also reverted** | the ticket's own premise, reproduced verbatim |
| M5 | `EMBEDDED_JVM_TARGET` back to `"17"` | 2 of 5 tests FAILED in `:udea-assets-compiler` |
| M5b | the same mutation, re-run to keep the test report | the compiler message itself, ten times in one assertion |
| M6 | KSP back to a `2.2.10-`-prefixed version | `UdeaVersionsTest` FAILED |
| M6control | KSP to a `2.4.20-`-prefixed version | **green — the control for M6** |
| M7 | the `common` context-parameter fixes reverted | 11 errors in `:common:compileKotlin` |
| M7b | the `example` context-parameter fixes reverted, alone | 25 errors in `:example:compileKotlin` |

### M1 — the Compose compiler plugin

This is the evidence command's red proof. With the plugin gone the command cannot reach a test at
all: the module stops compiling.

```
diff --git a/udea-render/build.gradle.kts b/udea-render/build.gradle.kts
index cde501e..1074b1d 100644
--- a/udea-render/build.gradle.kts
+++ b/udea-render/build.gradle.kts
@@ -13,7 +13,7 @@ plugins {
     // today. The convention's stated job is "this module is allowed GL"; Compose is a separate
     // permission, and rolling the two together would mean the next GL-allowed module silently
     // acquired a compiler plugin it never asked for.
-    alias(libs.plugins.composeCompiler)
+    // M1: alias(libs.plugins.composeCompiler) removed
 }
 
 dependencies {
```

`:udea-render:test --tests '...ComposeGlLinkTest' --rerun-tasks`, spliced from `M1.out`:

```
> Task :udea-render:compileKotlin FAILED
e: java.lang.RuntimeException: Exception while generating code for internal final fun ComposeGlProbe (): kotlin.Unit [companion] declared in dev.wildware.udea.render.ui.compose.ComposeGlProbeKt:
FUN name:ComposeGlProbe visibility:internal modality:FINAL returnType:kotlin.Unit [companion]
  annotations:
  ... [elided: the IR dump of ComposeGlProbe, M1.out lines 241-327] ...
Caused by: org.jetbrains.kotlin.codegen.CompilationException: Back-end (JVM) Internal error: Couldn't inline method call: CALL 'public final fun remember <T> (calculation: @[DisallowComposableCalls] kotlin.Function0<T of androidx.compose.runtime.ComposablesKt.remember>): T of androidx.compose.runtime.ComposablesKt.remember [inline,companion] declared in androidx.compose.runtime.ComposablesKt' type=androidx.compose.runtime.MutableState<kotlin.Int> origin=null
Method: null
File is unknown
The root cause java.lang.IllegalStateException was thrown at: org.jetbrains.kotlin.codegen.inline.SourceCompilerForInlineKt.getMethodNode(SourceCompilerForInline.kt:131)
	at org.jetbrains.kotlin.backend.jvm.codegen.IrInlineCodegen.genInlineCall(IrInlineCodegen.kt:85)
	at org.jetbrains.kotlin.backend.jvm.codegen.IrInlineCallGenerator.genCall(IrInlineCallGenerator.kt:36)
  ... [elided: the rest of the back-end stack, M1.out lines 334-374] ...
BUILD FAILED in 1m 6s
```

It was re-run at the end of the ticket with the command recorded on the transcript's first line,
because a transcript that does not say what produced it is half a transcript:
`15-evidence-red-M1.txt` opens with the evidence command verbatim, ends `BUILD FAILED in 50s`
and `EXIT=1`, and carries `couldn't find inline method
Landroidx/compose/runtime/ComposablesKt;.remember(Lkotlin/jvm/functions/Function0;)Ljava/lang/Object;`
underneath the back-end error. `15-evidence-red-M1.diff` is the mutation as the diff printed it
in that run, and the script restored the file from `HEAD` afterwards and printed the working tree
to show it.

Worth being precise about *why* it is red, because my first KDoc for this got it wrong and said
the plugin's absence would throw at first composition instead. It does not get that far. The
plugin is what rewrites a `@Composable` call to thread a `Composer` through it, so without it the
compiler looks for a `remember` signature the runtime jar does not publish, and the JVM back end
fails on the inline. Both KDocs now say the measured thing.

### M2 — `composegl-ui` alone: green, and that is the honest result

```
diff --git a/udea-render/build.gradle.kts b/udea-render/build.gradle.kts
index cde501e..30f7842 100644
--- a/udea-render/build.gradle.kts
+++ b/udea-render/build.gradle.kts
@@ -24,7 +24,7 @@ dependencies {
     // GL backend, so UDEA-MG-002 bans it from every headless module, and this is the one module
     // the graph allows GL in. `implementation` rather than `api` for the same reason gdx is:
     // nothing consuming this module should be able to see the renderer's toolkit.
-    implementation(libs.composegl.ui)
+    // M2: implementation(libs.composegl.ui) removed
     implementation(libs.composegl.gdx)
 
     // The gdx desktop natives. Runtime-only because nothing compiles against them: the
```

```
BUILD SUCCESSFUL in 25s
```

**It did not go red, and the reason is not a defect in the test.** `composegl-gdx`'s POM brings
`composegl-ui-jvm` at compile scope, so removing the catalog entry removes a declaration and not
a classpath. The row stays in this table because a mutation table that quietly drops its
negatives is a table nobody can calibrate. M2b is the mutation that isolates the toolkit.

### M2b — both ComposeGL artifacts

```
diff --git a/udea-render/build.gradle.kts b/udea-render/build.gradle.kts
index cde501e..8176f66 100644
--- a/udea-render/build.gradle.kts
+++ b/udea-render/build.gradle.kts
@@ -24,8 +24,8 @@ dependencies {
     // GL backend, so UDEA-MG-002 bans it from every headless module, and this is the one module
     // the graph allows GL in. `implementation` rather than `api` for the same reason gdx is:
     // nothing consuming this module should be able to see the renderer's toolkit.
-    implementation(libs.composegl.ui)
-    implementation(libs.composegl.gdx)
+    // M2: implementation(libs.composegl.ui) removed
+    // M3: implementation(libs.composegl.gdx) removed
 
     // The gdx desktop natives. Runtime-only because nothing compiles against them: the
     // LWJGL3 backend loads `gdx64.dll`/`libgdx64.so` through `SharedLibraryLoader` at
```

```
> Task :udea-render:compileKotlin FAILED
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/compose/ComposeGlProbe.kt:3:8 Unresolved reference 'androidx'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/compose/ComposeGlProbe.kt:4:8 Unresolved reference 'androidx'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/compose/ComposeGlProbe.kt:5:8 Unresolved reference 'androidx'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/compose/ComposeGlProbe.kt:6:8 Unresolved reference 'androidx'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/compose/ComposeGlProbe.kt:7:8 Unresolved reference 'androidx'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/compose/ComposeGlProbe.kt:8:21 Unresolved reference 'composegl'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/compose/ComposeGlProbe.kt:9:21 Unresolved reference 'composegl'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/compose/ComposeGlProbe.kt:10:21 Unresolved reference 'composegl'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/compose/ComposeGlProbe.kt:11:21 Unresolved reference 'composegl'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/compose/ComposeGlProbe.kt:12:21 Unresolved reference 'composegl'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-render/src/main/kotlin/dev/wildware/udea/render/ui/compose/ComposeGlProbe.kt:47:2 Unresolved reference 'Composable'.
  ... [elided: the remaining unresolved references in the same file, M2b.out lines 101-129] ...
BUILD FAILED in 17s
```

### M3 — `composegl-gdx` alone

The third test in the evidence command exists for exactly this: `composegl-ui` composing is not
the same claim as `composegl-gdx` and `udea-render` agreeing on which `Batch` they mean.

```
diff --git a/udea-render/build.gradle.kts b/udea-render/build.gradle.kts
index cde501e..ad81f74 100644
--- a/udea-render/build.gradle.kts
+++ b/udea-render/build.gradle.kts
@@ -25,7 +25,7 @@ dependencies {
     // the graph allows GL in. `implementation` rather than `api` for the same reason gdx is:
     // nothing consuming this module should be able to see the renderer's toolkit.
     implementation(libs.composegl.ui)
-    implementation(libs.composegl.gdx)
+    // M3: implementation(libs.composegl.gdx) removed
 
     // The gdx desktop natives. Runtime-only because nothing compiles against them: the
     // LWJGL3 backend loads `gdx64.dll`/`libgdx64.so` through `SharedLibraryLoader` at
```

```
> Task :udea-render:compileTestKotlin FAILED
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-render/src/test/kotlin/dev/wildware/udea/render/ui/compose/ComposeGlLinkTest.kt:4:31 Unresolved reference 'gdx'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-render/src/test/kotlin/dev/wildware/udea/render/ui/compose/ComposeGlLinkTest.kt:102:35 Inapplicable candidate(s): fun isAssignableFrom(p0: Class<*>!): Boolean
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-render/src/test/kotlin/dev/wildware/udea/render/ui/compose/ComposeGlLinkTest.kt:102:52 Unresolved reference 'GdxBackend'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-render/src/test/kotlin/dev/wildware/udea/render/ui/compose/ComposeGlLinkTest.kt:102:70 Cannot infer type for type parameter 'T'. Specify it explicitly.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-render/src/test/kotlin/dev/wildware/udea/render/ui/compose/ComposeGlLinkTest.kt:102:70 Candidate 'val <T> KClass<T>.java: Class<T>' is inapplicable because of a receiver type mismatch.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-render/src/test/kotlin/dev/wildware/udea/render/ui/compose/ComposeGlLinkTest.kt:112:26 Unresolved reference 'GdxBackend'.
  ... [elided: the rest of the same file's errors, M3.out lines 115-137] ...
BUILD FAILED in 19s
```

### M4 — Kotlin back to 2.2.10, with the 2.4 port left in place

```
diff --git a/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaVersions.kt b/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaVersions.kt
index 58eb59b..c1bcaf3 100644
--- a/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaVersions.kt
+++ b/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaVersions.kt
@@ -15,7 +15,7 @@ public object UdeaVersions {
      * version exactly — a K2 plugin built against a different compiler than the one
      * loading it fails at class-load time, not at compile time.
      */
-    public const val KOTLIN: String = "2.4.20"
+    public const val KOTLIN: String = "2.2.10"
 
     /** JDK release every module targets. */
     public const val JVM_TOOLCHAIN: Int = 21
diff --git a/build.gradle.kts b/build.gradle.kts
index 5e8f4ce..cf0ce60 100644
--- a/build.gradle.kts
+++ b/build.gradle.kts
@@ -7,7 +7,7 @@ plugins {
     // Not applied: the root project has no sources of its own. It is declared so that the
     // Kotlin Gradle plugin is on this script's classpath, which is what makes the
     // `KotlinCompile` type below resolvable for the `allprojects` jvmTarget rule.
-    kotlin("jvm") version "2.4.20" apply false
+    kotlin("jvm") version "2.2.10" apply false
 
     // Phase 0 build gates from the `build-logic` included build. Applied to the rewrite
     // subprojects below, never to the root or to the old tree.
diff --git a/common/build.gradle.kts b/common/build.gradle.kts
index 67b75a5..8f06bf3 100644
--- a/common/build.gradle.kts
+++ b/common/build.gradle.kts
@@ -2,10 +2,10 @@ import org.gradle.internal.execution.caching.CachingState.enabled
 import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
 
 plugins {
-    kotlin("jvm") version "2.4.20"
+    kotlin("jvm") version "2.2.10"
     id("java-library")
     id("maven-publish")
-    kotlin("plugin.serialization") version "2.4.20"
+    kotlin("plugin.serialization") version "2.2.10"
     id("com.google.devtools.ksp") version "2.3.12"
     id("kotlin-kapt")
 }
diff --git a/example/build.gradle.kts b/example/build.gradle.kts
index a3c9af7..cfc0fcd 100644
--- a/example/build.gradle.kts
+++ b/example/build.gradle.kts
@@ -1,9 +1,9 @@
 import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
 
 plugins {
-    kotlin("jvm") version "2.4.20"
+    kotlin("jvm") version "2.2.10"
     id("com.google.devtools.ksp") version "2.3.12"
-    kotlin("plugin.serialization") version "2.4.20"
+    kotlin("plugin.serialization") version "2.2.10"
 }
 
 group = "dev.wildware.udea"
diff --git a/gradle-plugin/build.gradle.kts b/gradle-plugin/build.gradle.kts
index f95af42..7d07e1f 100644
--- a/gradle-plugin/build.gradle.kts
+++ b/gradle-plugin/build.gradle.kts
@@ -40,7 +40,7 @@ kotlin {
 
 buildscript {
     dependencies {
-        classpath(kotlin("gradle-plugin", version = "2.4.20"))
+        classpath(kotlin("gradle-plugin", version = "2.2.10"))
     }
 }
 
diff --git a/gradle/libs.versions.toml b/gradle/libs.versions.toml
index 44d87f0..8575f81 100644
--- a/gradle/libs.versions.toml
+++ b/gradle/libs.versions.toml
@@ -7,7 +7,7 @@ libgdx = "1.13.1"
 # plugins
 changelog = "2.2.1"
 intelliJPlatform = "2.5.0"
-kotlin = "2.4.20"
+kotlin = "2.2.10"
 kover = "0.9.1"
 qodana = "2024.3.4"
 junitJupiter = "5.8.1"
```

```
> Task :udea-compiler-plugin:compileKotlin FAILED
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/UdeaCompilerPluginRegistrar.kt:35:5 'pluginId' overrides nothing.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaDiagnostics.kt:12:41 Unresolved reference 'KtDiagnosticsContainer'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaDiagnostics.kt:43:35 Unresolved reference 'KtDiagnosticsContainer'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaDiagnostics.kt:141:5 'getRendererFactory' overrides nothing.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaDiagnostics.kt:158:60 Type 'KtDiagnosticFactoryToRendererMap' has no method 'getValue(UdeaDiagnostics.Renderers, KProperty1<*, *>)', so it cannot serve as a delegate.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaDiagnostics.kt:159:54 Too many arguments for 'constructor(name: String): KtDiagnosticFactoryToRendererMap'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaDiagnostics.kt:159:56 Cannot infer type for this parameter. Specify it explicitly.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaDiagnostics.kt:160:21 Unresolved reference 'put'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/udea-compiler-plugin/src/main/kotlin/dev/wildware/udea/compiler/fir/UdeaDiagnostics.kt:165:25 Unresolved reference 'put'.
  ... [elided: M4.out lines 62-75] ...
BUILD FAILED in 31s
```

This is the mutation that shows the port is the work rather than the version string. Ten errors,
every one of them a 2.4-only compiler API.

### M4d — the ticket's premise, reproduced

M4 reverts the version and keeps the port. M4d reverts both, which is what `origin/example` plus
a bumped version string would actually look like — and it reproduces the failure the issue was
written from. The revert touched 14 files:

```
 .../kotlin/dev/wildware/udea/build/UdeaVersions.kt |  2 +-
 build.gradle.kts                                   |  2 +-
 common/build.gradle.kts                            |  4 +--
 example/build.gradle.kts                           |  4 +--
 gradle-plugin/build.gradle.kts                     |  2 +-
 gradle/libs.versions.toml                          |  2 +-
 .../wildware/udea/assets/compiler/scan/KtParser.kt | 37 ----------------------
 .../udea/assets/compiler/script/UdeaAssetScript.kt |  3 +-
 .../compiler/transpile/TranspiledAssetLoader.kt    |  3 +-
 .../udea/compiler/UdeaCompilerPluginRegistrar.kt   | 23 ++++----------
 .../wildware/udea/compiler/fir/UdeaDiagnostics.kt  | 34 ++++----------------
 .../compiler/fir/UdeaReplicatedPropertyChecker.kt  |  9 +-----
 .../udea/compiler/UdeaCommandLineProcessorTest.kt  | 10 +-----
 .../compiler/UdeaCompilerPluginRegistrarTest.kt    | 17 +++-------
 14 files changed, 29 insertions(+), 123 deletions(-)
```

Its version half, the part the issue is about:

```
diff --git a/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaVersions.kt b/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaVersions.kt
index 58eb59b..c1bcaf3 100644
--- a/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaVersions.kt
+++ b/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaVersions.kt
@@ -15,7 +15,7 @@ public object UdeaVersions {
      * version exactly — a K2 plugin built against a different compiler than the one
      * loading it fails at class-load time, not at compile time.
      */
-    public const val KOTLIN: String = "2.4.20"
+    public const val KOTLIN: String = "2.2.10"
 
     /** JDK release every module targets. */
     public const val JVM_TOOLCHAIN: Int = 21
diff --git a/build.gradle.kts b/build.gradle.kts
index 5e8f4ce..cf0ce60 100644
--- a/build.gradle.kts
+++ b/build.gradle.kts
@@ -7,7 +7,7 @@ plugins {
     // Not applied: the root project has no sources of its own. It is declared so that the
     // Kotlin Gradle plugin is on this script's classpath, which is what makes the
     // `KotlinCompile` type below resolvable for the `allprojects` jvmTarget rule.
-    kotlin("jvm") version "2.4.20" apply false
+    kotlin("jvm") version "2.2.10" apply false
 
     // Phase 0 build gates from the `build-logic` included build. Applied to the rewrite
     // subprojects below, never to the root or to the old tree.
diff --git a/gradle/libs.versions.toml b/gradle/libs.versions.toml
index 44d87f0..8575f81 100644
--- a/gradle/libs.versions.toml
+++ b/gradle/libs.versions.toml
@@ -7,7 +7,7 @@ libgdx = "1.13.1"
 # plugins
 changelog = "2.2.1"
 intelliJPlatform = "2.5.0"
-kotlin = "2.4.20"
+kotlin = "2.2.10"
 kover = "0.9.1"
 qodana = "2024.3.4"
 junitJupiter = "5.8.1"
```

```
> Task :udea-render:compileKotlin FAILED
e: file:///home/shaun/.gradle/caches/modules-2/files-2.1/dev.wildware.composegl/composegl-gdx/0.5.0/7b0d81a634b81cc4e1e83dd28106e21af1dedb77/composegl-gdx-0.5.0.jar!/META-INF/dev.wildware.composegl_composegl-gdx.kotlin_module Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.4.0, expected version is 2.2.0.
e: file:///home/shaun/.gradle/caches/modules-2/files-2.1/dev.wildware.composegl/composegl-render-jvm/0.5.0/6fd0d8a9cebd7f00f895db53467f2ebd239a7c4b/composegl-render-jvm-0.5.0.jar!/META-INF/dev.wildware.composegl_composegl-render.kotlin_module Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.4.0, expected version is 2.2.0.
e: file:///home/shaun/.gradle/caches/modules-2/files-2.1/dev.wildware.composegl/composegl-ui-jvm/0.5.0/94444e319603c237f6bf1e64b48343b754d7fb6f/composegl-ui-jvm-0.5.0.jar!/META-INF/dev.wildware.composegl_composegl-ui.kotlin_module Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.4.0, expected version is 2.2.0.
  ... [elided: M4d.out lines 50-74] ...
BUILD FAILED in 9s
```

That is the issue's own claim, measured on this tree: the published jars carry metadata version
2.4.0 and a 2.2.10 frontend refuses them. It is also the reason
`-Xskip-metadata-version-check` appears nowhere in the diff — this is the check working.

### M5 — the embedded JVM target

```
diff --git a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/EmbeddedJvmTarget.kt b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/EmbeddedJvmTarget.kt
index 48c60a8..5767606 100644
--- a/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/EmbeddedJvmTarget.kt
+++ b/udea-assets-compiler/src/main/kotlin/dev/wildware/udea/assets/compiler/EmbeddedJvmTarget.kt
@@ -22,4 +22,4 @@ package dev.wildware.udea.assets.compiler
  *
  * `internal`, because no caller outside this module embeds a compiler.
  */
-internal const val EMBEDDED_JVM_TARGET: String = "21"
+internal const val EMBEDDED_JVM_TARGET: String = "17"
```

```
EmbeddedJvmTargetTest > the embedded jvm target is the bytecode level this module was compiled to() FAILED
    org.opentest4j.AssertionFailedError at EmbeddedJvmTargetTest.kt:25

AccessorCompilationTest > a fixture that types the member as Ref of Blueprint compiles() FAILED
    org.opentest4j.AssertionFailedError at AccessorCompilationTest.kt:82

5 tests completed, 2 failed

> Task :udea-assets-compiler:test FAILED
  ... [elided: M5.out lines 83-94] ...
BUILD FAILED in 21s
```

Two tests, and the pair is the point. `EmbeddedJvmTargetTest` is the new guard, and
`AccessorCompilationTest` is the test that actually broke when the JDK moved — so the guard is
red in the same place the real defect was, rather than somewhere convenient.

**Re-run to keep the report (`M5b`).** The console output above this paragraph records only
`AssertionFailedError at AccessorCompilationTest.kt:82`; the compiler message that makes the
mutation meaningful lives in the test report, and the next build overwrote it. So M5 was applied
again and the XML kept, as `mutations/M5b.out` and
`mutations/M5b-AccessorCompilationTest.xml`. The assertion in it reads

```
org.opentest4j.AssertionFailedError: the generated accessors did not compile ==> expected: <[]> but was: <[Cannot inline bytecode built with JVM target 21 into bytecode that is being built with JVM target 17. Specify proper '-jvm-target' option.,
  ... [elided: the same line nine more times, then the closing ]> of the assertion] ...
```

with that line repeated ten times inside the list — which is where section 2.2's "ten copies"
comes from.

### M7 and M7b — the context-parameter sites, put back

Section 2.4 says `:common` had 11 errors and `:example` 25, and that the decision not to pin
those two modules to language version 2.2 was worth the source changes. Those are counts, so
they are measured here rather than remembered: the fixed files are replaced with their
`origin/example` versions and the modules compiled.

**M7 — `common`.** The revert:

```
 .../kotlin/dev/wildware/udea/ability/Ability.kt    |  17 +---
 .../dev/wildware/udea/ecs/system/AbilitySystem.kt  |   9 +-
 .../wildware/udea/ecs/system/AttributeSystem.kt    |   7 +-
 .../udea/ecs/system/NetworkServerSystem.kt         |  10 +-
 .../dev/wildware/udea/network/serializers.kt       |   7 +-
 .../wildware/udea/example/system/GameUnitSystem.kt |  14 +--
 .../udea/example/system/PlayerControlSystem.kt     |  48 ++++-----
 .../udea/example/system/ProjectileSystem.kt        |  44 ++++-----
 .../wildware/udea/example/system/UnitAISystem.kt   | 108 ++++++++++-----------
 9 files changed, 97 insertions(+), 167 deletions(-)
```

```
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/common/src/main/kotlin/dev/wildware/udea/ability/Ability.kt:130:13 Call to 'val ability: AssetReference<Ability>' defined in 'dev.wildware.udea.ability.AbilitySpec' uses an implicit receiver shadowed by a context parameter. Make the receiver explicit using 'this' or 'spec'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/common/src/main/kotlin/dev/wildware/udea/ability/Ability.kt:131:51 Call to 'val ability: AssetReference<Ability>' defined in 'dev.wildware.udea.ability.AbilitySpec' uses an implicit receiver shadowed by a context parameter. Make the receiver explicit using 'this' or 'spec'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/common/src/main/kotlin/dev/wildware/udea/ability/Ability.kt:132:40 Call to 'fun getSetByCallerMagnitudes(): Map<GameplayTag, Float>' defined in 'dev.wildware.udea.ability.AbilitySpec' uses an implicit receiver shadowed by a context parameter. Make the receiver explicit using 'this' or 'spec'.
  ... [elided: the other eight, M7.out lines 205-212] ...
BUILD FAILED in 33s
```

Counted off that transcript, and grouped by the diagnostic rather than by the line:

```
$ grep -c '^e: ' M7.out
11
$ grep '^e: ' M7.out | sed 's/.*\.kt:[0-9]*:[0-9]* //' | sort | uniq -c | sort -rn
      3 Call to 'val ability: AssetReference<Ability>' ... uses an implicit receiver shadowed by a context parameter. Make the receiver explicit using 'this' or 'spec'.
      3 Call to 'fun <reified T : Component<*>> Entity.get(...)' ... Disambiguate the receiver by wrapping the call in 'with(this) { ... }' or 'with(contextOf<World>()) { ... }'.
      2 Call to 'fun getSetByCallerMagnitudes(): Map<GameplayTag, Float>' ... Make the receiver explicit using 'this' or 'spec'.
      1 Expression in class literal has nullable type 'T (of fun <T> T.inPlaceSerializer)'. Use '!!' to make the type non-nullable.
      1 Call to 'fun <reified T : Component<*>> Entity.get(...)' ... or 'with(world) { ... }'.
      1 Call to 'fun Entity.configure(...)' ... or 'with(contextOf<World>()) { ... }'.
```

Ten of the eleven are the implicit-receiver/context-parameter clash and the eleventh is the
nullable class literal, which is the split section 2.4 describes. **The message texts above are
abbreviated with `...` for width; the unabridged lines are in `M7.out`.**

**M7b — `example`, on its own.** M7 reverted both modules at once, `:common:compileKotlin` failed
first and `:example:compileKotlin` never ran, so M7's transcript reports zero errors in
`example` — which is true of that run and says nothing about the module. That is the shape of
wrong answer worth catching, so `example` was reverted alone:

```
 .../wildware/udea/example/system/GameUnitSystem.kt |  14 +--
 .../udea/example/system/PlayerControlSystem.kt     |  48 ++++-----
 .../udea/example/system/ProjectileSystem.kt        |  44 ++++-----
 .../wildware/udea/example/system/UnitAISystem.kt   | 108 ++++++++++-----------
 4 files changed, 85 insertions(+), 129 deletions(-)
```

```
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/example/src/main/kotlin/dev/wildware/udea/example/system/GameUnitSystem.kt:45:9 Call to 'fun <reified T : Component<*>> Entity.get(type: ComponentType<T>): T' defined in 'com.github.quillraven.fleks.EntityComponentContext' uses an implicit receiver shadowed by a context parameter. Disambiguate the receiver by wrapping the call in 'with(this) { ... }' or 'with(contextOf<World>()) { ... }'.
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a96ab5cb67dd9cfda/example/src/main/kotlin/dev/wildware/udea/example/system/PlayerControlSystem.kt:37:26 Call to 'fun <reified T : Component<*>> Entity.get(type: ComponentType<T>): T' defined in 'com.github.quillraven.fleks.EntityComponentContext' uses an implicit receiver shadowed by a context parameter. Disambiguate the receiver by wrapping the call in 'with(this) { ... }' or 'with(contextOf<World>()) { ... }'.
  ... [elided: the other twenty-three, M7b.out lines 473-495] ...
BUILD FAILED in 39s
```

```
$ grep -c '^e: ' M7b.out
25
```

Every one of the 25 is the same clash on a Fleks call inside a `context(world)` body, which is
why the fix is `with(world) { ... }` around the body rather than 25 separate edits, and why the
diff for those four files is almost entirely re-indentation. The full diffs are `M7.diff` and
`M7b.diff`.

### M6 — KSP naming another compiler, and M6control

`KspVersionRule` replaced an assertion that stopped being expressible when KSP left the
`<kotlin>-<ksp>` scheme. What is still checkable is that a KSP version must not name a Kotlin
compiler *other than* this project's — so the mutation has to be a KSP version that names the
wrong one, and the control has to be one that names the right one.

```
diff --git a/gradle/libs.versions.toml b/gradle/libs.versions.toml
index 44d87f0..463534f 100644
--- a/gradle/libs.versions.toml
+++ b/gradle/libs.versions.toml
@@ -31,7 +31,7 @@ junitJupiter = "5.8.1"
 # The consequence for this repository is that `UdeaVersions.KOTLIN` can no longer be checked
 # against this string, and `UdeaVersionsTest` no longer pretends it can - see `KspVersionRule`
 # for what is still checkable and where the executable proof lives instead.
-ksp = "2.3.12"
+ksp = "2.2.10-2.0.2"
 kotlinpoet = "2.3.0"
 fleks = "2.14"
 # libGDX. Moved 1.13.5 -> 1.14.2 with the ComposeGL move (issue #186), and not for its own
```

```
> Task :test FAILED

UdeaVersionsTest > the catalog KSP does not name a Kotlin compiler other than the project's() FAILED
    org.opentest4j.AssertionFailedError at UdeaVersionsTest.kt:85

4 tests completed, 1 failed
  ... [elided: M6.out lines 26-35] ...
BUILD FAILED in 19s
```

**The control**, which is the half that says the rule is not simply rejecting every prefixed
version:

```
diff --git a/gradle/libs.versions.toml b/gradle/libs.versions.toml
index 44d87f0..6ab31b8 100644
--- a/gradle/libs.versions.toml
+++ b/gradle/libs.versions.toml
@@ -31,7 +31,7 @@ junitJupiter = "5.8.1"
 # The consequence for this repository is that `UdeaVersions.KOTLIN` can no longer be checked
 # against this string, and `UdeaVersionsTest` no longer pretends it can - see `KspVersionRule`
 # for what is still checkable and where the executable proof lives instead.
-ksp = "2.3.12"
+ksp = "2.4.20-2.0.2"
 kotlinpoet = "2.3.0"
 fleks = "2.14"
 # libGDX. Moved 1.13.5 -> 1.14.2 with the ComposeGL move (issue #186), and not for its own
```

```
BUILD SUCCESSFUL in 10s
```

`2.4.20-2.0.2` names this project's compiler, so it passes; `2.2.10-2.0.2` names another one, so
it fails; and the shipped `2.3.12` names none at all, so the rule has nothing to object to. A
rule that failed on all three would have passed M6 too.

---

## 9. Regenerated files: neither moved

`net-protocol.lock` and `expected-generated-hashes.txt` are **byte-identical to
`origin/example`**, and that is a result rather than an oversight. The third acceptance criterion
is conditional — "regenerated **if** the KSP move shifts them" — and it did not: no replicated
component was added, removed or renamed, so no id moved, and KSP 2.3.12 emits the same bytes
2.2.10-2.0.2 did for the same inputs.

Three independent things say so rather than one.

**The blob hashes are equal.** This is the check that cannot be fooled by a path typo, which is
why it is the one quoted:

```
$ git rev-parse origin/example:udea-codegen/net-protocol.lock \
    HEAD:udea-codegen/net-protocol.lock \
    origin/example:udea-codegen/src/test/resources/expected-generated-hashes.txt \
    HEAD:udea-codegen/src/test/resources/expected-generated-hashes.txt
cdf61b39dd6048ccbea5efc9fd9f49363e75e31f
cdf61b39dd6048ccbea5efc9fd9f49363e75e31f
59a08a051e1c7fe5ec02b157321c2fe8473db1fc
59a08a051e1c7fe5ec02b157321c2fe8473db1fc
```

**`git diff --stat origin/example..HEAD` lists neither file** — section 2's diffstat is the whole
list. On its own that would be weak evidence, because a misspelled path produces the same empty
answer: `git diff --stat origin/example..HEAD -- udea-codegen/net-protocol.lock.nope` also
returns nothing. Both real paths were confirmed to exist first, and the same command over
`determinism-allowlist.txt` does print a change, so the command is answering about content.

**And `udeaCheckProtocolLock` runs on `check`**, with the hash-fixture test beside it, so a drift
in either would have failed the green `build` in section 3 rather than landing quietly.

**Id movement: zero.**

---

## 10. Gates outside `check`

### The graph and migration verifiers

`18-verifiers.txt`, with `--rerun-tasks` because the first attempt came back "86 actionable
tasks: 86 up-to-date", which prints nothing and therefore proves nothing:

```
$ JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd udeaVerifyMigration udeaLegacyReport udeaVerifyDeterminism udeaVerifyContracts --rerun-tasks --no-daemon --console=plain

BUILD SUCCESSFUL in 54s
86 actionable tasks: 86 executed
EXIT=0
```

`udeaVerifyAgentsMd` passing is what says the module table in `AGENTS.md` still matches
`settings.gradle.kts` — no module moved, so it did not need to, but the header did and it was
updated in the same change. `udeaVerifyContracts` passing is what says no file in
`docs/contracts/` moved. And `udeaVerifyDeterminism` printed its counts, which is where the
223/120/181 in section 7 comes from rather than from my own dump:

```
> Task :udeaVerifyDeterminism
udeaVerifyDeterminism
  scanned :udea-core: 223 class files
  scanned :udea-gas: 120 class files
  scanned :udea-net: 181 class files
  scanned :moba: 350 class files
  allowlist entries used: 0
  findings: 0
```

### `:moba:runNetProof`

Run because the toolchain moved underneath replication, not because the issue asked.
`20-netproof.txt`:

```
$ JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew :moba:runNetProof --console=plain

BUILD SUCCESSFUL in 26s
50 actionable tasks: 2 executed, 48 up-to-date
EXIT=0
```

On the perfect link, all four hashes agree:

```
  server   tick=241 units=28 entities=50
  client1  tick=240 units=28 entities=50 applied=239 stale=0
           unitHash  client=0x52d717cd735d2dd2 server@t240=0x52d717cd735d2dd2 MATCH
           worldHash client=0x18f7fdc2cf4fee76 server@t240=0x18f7fdc2cf4fee76 MATCH
```
```
  client2  tick=240 units=28 entities=50 applied=239 stale=0
           unitHash  client=0x52d717cd735d2dd2 server@t240=0x52d717cd735d2dd2 MATCH
           worldHash client=0x18f7fdc2cf4fee76 server@t240=0x18f7fdc2cf4fee76 MATCH
```

Under loss the 28-unit roster hash still matches on both clients and the whole-world hash does
not:

```
  server   tick=241 units=28 entities=52
  client1  tick=232 units=28 entities=55 applied=221 stale=0
           unitHash  client=0x97b7e8ccb3216a20 server@t232=0x97b7e8ccb3216a20 MATCH
           worldHash client=0xc3988f3c0183d2c6 server@t232=0x6f6c5414fa0fe21c DIFFER
```

**That is the pre-existing shape this repository already documents, not a regression on this
branch**, and the `entities=` numbers are the reason: 52 on the server against 55 and 56 on the
clients, on a churning creep and projectile population, so the two sides are hashing different
rosters at the sampled tick. It is the same behaviour `:moba:runUdpProof` fails on, and the
section below is about that. The task itself passes, because what it asserts is the roster hash.

### `:moba:runUdpProof` — and the one thing on this branch that surprised me

`HANDOFF.md` says this gate "fails under loss, 5/5". **It does not.** It is intermittent, and it
is intermittent on `origin/example` too, so this branch neither fixed it nor broke it.

I ran it five times here, then five times on a `git archive` of `origin/example` extracted to a
scratch directory — same command, same `--rerun-tasks`, same box, same JDK launcher. The tallies
are identical:

```
run1: exit=1  'whole-roster hash DIFFER' lines=1  BUILD FAILED in 39s
run2: exit=0  'whole-roster hash DIFFER' lines=0  BUILD SUCCESSFUL in 39s
run3: exit=0  'whole-roster hash DIFFER' lines=0  BUILD SUCCESSFUL in 37s
run4: exit=0  'whole-roster hash DIFFER' lines=0  BUILD SUCCESSFUL in 36s
run5: exit=1  'whole-roster hash DIFFER' lines=1  BUILD FAILED in 36s
```
```
baseline run1: exit=1  'whole-roster hash DIFFER' lines=1  BUILD FAILED in 1m 28s
baseline run2: exit=1  'whole-roster hash DIFFER' lines=1  BUILD FAILED in 49s
baseline run3: exit=0  'whole-roster hash DIFFER' lines=0  BUILD SUCCESSFUL in 49s
baseline run4: exit=0  'whole-roster hash DIFFER' lines=0  BUILD SUCCESSFUL in 58s
baseline run5: exit=0  'whole-roster hash DIFFER' lines=0  BUILD SUCCESSFUL in 49s
```

Three of five green on each side. Every transcript is kept, in
`issue186-evidence/udpproof-runs/` and `issue186-evidence/udpproof-baseline/`.

**What a failing run actually says**, spliced from `udpproof-runs/run1.txt`:

```
[udp-proof][lossy] MobaUdpClient {tick=196, units=28, entities=50, applied=160, stale=0, unitHash=5954467849656552871}
[udp-proof][lossy] server@t196 {tick=196, entities=53, units=28, unitHash=5615634140392175804}
[udp-proof][lossy]   Attributes MATCH
[udp-proof][lossy]   CharacterView MATCH
[udp-proof][lossy]   Combatant MATCH
[udp-proof][lossy]   GameUnit MATCH
[udp-proof][lossy]   Inventory MATCH
[udp-proof][lossy]   LaneCreep MATCH
[udp-proof][lossy]   LaneState MATCH
[udp-proof][lossy]   MatchState MATCH
[udp-proof][lossy]   Player MATCH
[udp-proof][lossy]   Position DIFFER
[udp-proof][lossy]   Projectile MATCH
[udp-proof][lossy]   Respawn MATCH
[udp-proof][lossy]   Tower MATCH
[udp-proof][lossy]   Wallet MATCH
[udp-proof][lossy] MobaUdpClient whole-roster hash DIFFER
```

`Position` is the only component that does not MATCH, with 28 units on both sides and 50
entities against 53. So the shape `HANDOFF.md` describes is real — the client is behind on creates, and
its units' positions have not caught up at the moment the sample is taken.

**What I am not claiming.** I am not claiming a mechanism. The obvious one — "the sampled tick
moved" — does not survive the data: the branch's failures sampled at tick 196 and its passes at
198, which looks like a rule until you read the baseline, where tick 195 both failed (runs 1 and
2) and passed (run 4). Whatever decides the outcome is finer-grained than the tick the sample
lands on, and finding it is the work `HANDOFF.md` is pointing at rather than something a
toolchain branch should attempt.

**What is worth acting on**, and it is cheap: `HANDOFF.md`'s "5/5" should become "intermittent,
about 3 in 5 green on this box", because a gate believed to be deterministically red gets treated
as a known failure, and a gate that is intermittent is a flaky test that will also go green in CI
often enough to hide the defect. I have not edited `HANDOFF.md` — it is the previous wave's
handover document and not mine to rewrite — so this paragraph and the ten transcripts are the
record.
