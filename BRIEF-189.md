# BRIEF-189: ban scene2d from every module, and make the docs say ComposeGL

1a7b8b5

Branch `issue-189-scene2d-ban`, worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a385595b60c7fd164`, off `origin/master` at `5cbf6d0` (re-fetched just
before the final build; `git merge-base --is-ancestor origin/master HEAD` holds, so nothing needed merging).
`1a7b8b5` is the code. The commit that adds this file sits on top of it and changes nothing else.

Every artefact quoted below is on disk in `/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue189/`. Blocks marked *spliced* were generated from those
files by `brief.py` in the same directory, not typed.

## 1. Evidence command

```
sh gradlew :udea-render:udeaVerifyNoLibGdx udeaVerifyModuleGraph udeaVerifyAgentsMd && { git grep --untracked -n -i scene2d -- ':(glob)udea-*/src/**' ':(glob)moba/*/src/**' docs/home.md ':(exclude,glob)**/src/*Test/**' ':(exclude,glob)**/src/test/**'; test $? -eq 1; }
```

(The recorded runs used `evidence.sh`, which is this line with `--console=plain` added for the log.)

The Gradle half runs the new bytecode gate and the two verifiers the issue names. The grep half
exits 0 only when `git grep` exits exactly 1 ("no match"). An error exit (128) therefore counts as
red, not green. The grep covers every `udea-*` and `moba` source set except test ones
(`src/*Test`, `src/test`), including untracked files, plus `docs/home.md`. Control: the same pathspec
lists 682 files with a `package` line in them (`pathspec-control.out`), none of them under a test directory.

**Green at `1a7b8b5`** (`evidence-green.log`, spliced):
```
1a7b8b5
> Task :udea-render:udeaVerifyNoLibGdx FROM-CACHE
BUILD SUCCESSFUL in 4s
EVIDENCE EXIT=0
```
(`evidence.sh` echoes `EVIDENCE EXIT=`. `FROM-CACHE` means the gate's inputs matched an
earlier green run byte for byte, after the red proof below had been removed.)

**Red, gate half.** Vendored scene2d source, and no LibGDX artifact anywhere. I added
`moba/game/src/commonMain/kotlin/com/badlogic/gdx/scenes/scene2d/Stage.kt` (a two-line
`internal class Stage`) and `moba/game/src/commonMain/kotlin/dev/wildware/moba/VendoredHud.kt`,
which calls `Stage().act(...)`. Then I ran the command above (`evidence-red-vendored.log`, spliced):
```
1a7b8b5
UdeaVerifyNoLibGdxTest > no module references a LibGDX type() FAILED
> Task :udea-render:udeaVerifyNoLibGdx FAILED
BUILD FAILED in 23s
EVIDENCE EXIT=1
```
The diagnostic, from the same experiment's first run (`red-vendored.log`, at `5986d78`, test report copied to `red-vendored-results/`, spliced):
```
[UDEA-MG-009-BYTECODE] moba/game compiles dev.wildware.moba.VendoredHud.tick()F against com/badlogic/gdx/scenes/scene2d/Stage -- scene2d is LibGDX's GL-backed widget toolkit, and the UI layer that replaced it is ComposeGL: udea-render's UiLayer for menus and panels, CapturedUi for a HUD (issues #187, #188, #189). No module may name LibGDX, however the class arrived: vendored source, a files() jar or a shaded jar all pass UDEA-MG-009, the dependency-level rule owned by udeaVerifyModuleGraph, which this extends. Reported under UDEA-MG-009-BYTECODE.
```
Both files were deleted afterwards. `git status --porcelain --untracked-files=all` came back empty,
and the green run above came after the deletion.

**Red, grep half.** `scene2d-grep.sh` is the grep half verbatim; given a ref it runs the same pathspec against that ref (without `--untracked`). Run against `origin/master`
(`grep-origin-master.out`, spliced), it finds these hits:
```
origin/master:docs/home.md:9:*   **Modular UI:** Screen-based UI management using Scene2D.
origin/master:moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaHud.kt:343: * Then a scene2d `Stage`, then - once LibGDX left in issue #211 - a painter drawing `BitmapFont2D`
origin/master:moba/game/src/commonMain/kotlin/dev/wildware/moba/MobaHudScreen.kt:38: * sprite batch, placing each by hand (issue #212's stand-in for the scene2d HUD, which left with
origin/master:udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/AgentBridge.kt:28: * Box2D or scene2d, because they are not here. The simulation thread is the only writer of
origin/master:udea-assets/src/commonMain/kotlin/dev/wildware/udea/assets/GameConfig.kt:54:/** The Scene2D skin a game's UI uses, when it has one. */
origin/master:udea-assets/src/commonMain/kotlin/dev/wildware/udea/assets/GameConfig.kt:61: * `Network`, `Physics` and `Scene2D` each extended `Asset<T>`, which gave four things with no
origin/master:udea-core/src/commonMain/kotlin/dev/wildware/udea/core/snapshot/SnapshotExclusions.kt:46:     * Untouched. Scene2d holds a widget tree with its own focus and animation state, none of
origin/master:udea-core/src/commonMain/kotlin/dev/wildware/udea/core/snapshot/SnapshotExclusions.kt:49:    Scene2dUi("UI state is not simulated and rewinding it would disrupt the observer"),
exit=1
```
With one reworded line put back in the working tree (`R3.diff`, `R3.out`, spliced):
```
diff --git a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/AgentBridge.kt b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/AgentBridge.kt
index b41b588..badf88a 100644
--- a/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/AgentBridge.kt
+++ b/udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/AgentBridge.kt
@@ -25,7 +25,7 @@ import dev.wildware.udea.agent.state.DigestBudgets
  * `FruitGameKTX`'s `DebugBridge` has this shape and it has survived a lot of automated
  * sessions, because the invariant is structural rather than a rule anyone has to remember:
  * **the off-thread side only ever calls [snapshot] and [submit].** It cannot reach Fleks,
- * Box2D or the UI toolkit, because they are not here. The simulation thread is the only writer of
+ * Box2D or scene2d, because they are not here. The simulation thread is the only writer of
  * world state and the only consumer of commands, so no lock is needed and none is taken.
  *
  * Three things are carried forward from that implementation unchanged: the published document
```
```
udea-agent/src/commonMain/kotlin/dev/wildware/udea/agent/AgentBridge.kt:28: * Box2D or scene2d, because they are not here. The simulation thread is the only writer of
exit=1
```

## 2. Summary

**AC1: the gate.** The lead asked whether `UDEA-MG-009` already satisfies AC1. It does not.
`UDEA-MG-009` matches artifact coordinates. A scene2d class that arrives without a coordinate
compiles with `udeaVerifyModuleGraph` green. `red-vendored.log` is that run with `--continue`:
`:moba:game:udeaVerifyModuleGraph`, the module holding the vendored class, ran and passed, and the
only failed task was `:udea-render:udeaVerifyNoLibGdx`. A `files(...)` jar also passes. `ResolutionScan` records it as
`file:<display name>`, which no `com.badlogicgames.*:*` pattern matches. I established that by
reading `ResolutionScan.coordinateOf`, not by running it.

So there is a new gate, `:udea-render:udeaVerifyNoLibGdx`, on `udea-render`'s `check`, under the
rule id `UDEA-MG-009-BYTECODE`. It reads the main bytecode of every project that has a build
script: all `udea-*` modules and `moba:game`, `moba:desktop` and `moba:android`. That list includes
`udea-render`, `udea-agent-host` and `udea-editor`, the modules the headless scan exempts. It fails
on `com/badlogic/` or `box2dLight/`. scene2d (`com/badlogic/gdx/scenes/scene2d/`) is listed first,
so its message says what replaced it.

What changed:
- **`BytecodeBan`** (new, test sources). The walk, the empty-module refusal, the source span and
  the reading of the property hand-off used to live inside `HeadlessScan`. They now live here
  once, and `HeadlessScan` and `LibGdxScan` are both built on it. This avoids a second copy that
  differs only in constants. `HeadlessScan`'s rule id, violation message and module list are unchanged. The
  missing-property message is now shared wording, and it still names the task to run.
- **The headless GL table drops LibGDX.** It now bans `org/lwjgl/` only. LibGDX moved to
  `LIBGDX_BANNED_OWNERS`, which is read over every module. This mirrors the dependency level,
  where `UDEA-MG-002` leaves LibGDX to `UDEA-MG-009`. One reference gives one diagnostic, and a
  test pins that the two tables share no owner.
- **`moba:android` gets a `udeaMainBytecode` task.** The `dev.wildware.udea.android-application` convention
  registers it on `compileReleaseKotlin`, the variant the APK ships. `RepoLayout.classFiles` reads
  an Android application's `build/tmp/kotlin-classes/release`. Without this the gate would either
  skip the app or fail as vacuous.
- **The module list is handed over as a system property**, as the headless list is.
  `UdeaVerifyNoLibGdxTest` checks it against `settings.gradle.kts` (every `udea-*` and `moba:*`
  include), so a module cannot quietly drop out.

The issue names `BannedOwner.kt` and `HeadlessScanTest`. Both still exist: the table is widened by
moving LibGDX out of it into the everywhere table. `HeadlessScanTest` no longer names a gdx fixture,
because #213 already replaced those fixtures with LWJGL ones. The scene2d `Stage` fixture now lives
in `LibGdxScanTest`, as vendored stub source in `udea-render`'s test sources (`VendoredScene2d.kt`),
the same shape as the defect the gate exists for. "Drop whatever gdx scene2d dependency is unused":
there is none left, because #213 removed every `com.badlogicgames` coordinate.

**AC2: the grep.** I reworded the remaining mentions in `udea-*` and `moba` main sources:
`AgentBridge.kt`, `MobaHud.kt`, `MobaHudScreen.kt` (comments only) and `GameConfig.kt` (KDoc). Two
of them were code, not comments. `SnapshotExclusion.Scene2dUi` is now `SnapshotExclusion.Ui`; it has
no references anywhere, so the rename is inert. `UiConfig.defaultSkin` is kept, with a KDoc saying it
is carried over and nothing uses it. Both decisions are commented on #189. The grep is
case-insensitive, which is stricter than the issue's `grep -rn scene2d`.

**AC3: the docs.** `AGENTS.md` already said "Interface is ComposeGL". Two edits: the `udea-render`
row now names the ComposeGL UI host (`UiLayer`, `CapturedUi`), and the "No LibGDX anywhere" rule names
the new gate. In `docs/home.md`, the "Modular UI ... using Scene2D" line now describes ComposeGL.
`docs/module-graph.md` gets a `UDEA-MG-009-BYTECODE` section, and the stale sentence saying
`UDEA-MG-002-BYTECODE` bans `com/badlogic/` is fixed. The comment on the ComposeGL entry in
`gradle/libs.versions.toml` said "replaces scene2d"; it now reads "replaced LibGDX's".

Decisions, all commented on #189 (issuecomment-5741014469, -5741015056):
- The bytecode gate bans all of LibGDX, not scene2d alone. `UDEA-MG-009` already bans all of it
  at the dependency level, and scene2d keeps its own reason. To scope the gate to scene2d only,
  move the other two entries back to `GL_BANNED_OWNERS`.
- The rule id is `UDEA-MG-009-BYTECODE`, derived the way `UDEA-MG-002-BYTECODE` is. No new
  `UDEA-MG-0NN` id is used, so the ids held by dev-233 and dev-244 are untouched.

Not done, out of scope:
- `docs/home.md` still opens with "built on **LibGDX**" and describes KryoNet and the "two trees".
  The whole page predates the rewrite. I changed only the UI line the issue names.
- The headless GL table does not name Kool (`de/fabmax/kool/`). A Kool type reaching a headless
  module by a route `UDEA-MG-002` cannot see (for example vendored source) would pass
  `udeaVerifyHeadless`. That is pre-existing, and it is the same class of hole this ticket closes
  for LibGDX. Noted for the lead.

## 3. `sh gradlew build`

`ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue`,
no exclusions.

At `5986d78`, the first code commit (`build-1.log`, spliced tail):
```
BUILD SUCCESSFUL in 3m 26s
960 actionable tasks: 706 executed, 13 from cache, 241 up-to-date
Configuration cache entry stored.
```
At `1a7b8b5`, which adds only comment rewording and a KDoc sentence (`build-2.log`, spliced tail):
```
BUILD SUCCESSFUL in 2m 30s
951 actionable tasks: 132 executed, 819 up-to-date
Configuration cache entry reused.
```
The two task counts differ (960, then 951 on a reused configuration-cache entry). I have not
traced why, and I am not claiming a cause. Both runs are green. Both runs executed
`udeaVerifyNoLibGdx` (build-1: `> Task :udea-render:udeaVerifyNoLibGdx`).
No latency-budget task failed in either run.

`sh gradlew -p build-logic check`, because the Android application convention changed (`buildlogic-check.log`, spliced tail):
```
BUILD SUCCESSFUL in 1m 58s
13 actionable tasks: 4 executed, 9 up-to-date
```

**GL.** The ticket changes `udea-render`'s test sources and build script, but no GL path. I ran the
GL suites for real anyway:
`xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true`
(`gl-1.log`, spliced):
```
> Task :udea-editor:udeaEditorGlTest
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest
BUILD SUCCESSFUL in 2m 46s
```
This run is at `1a7b8b5`, after both builds. Test results, summed from each task's JUnit XML, which
was copied to `gl-results/` straight after the run:
```
gl-results/udeaGlTest: tests=19 skipped=0 failures=0 errors=0
gl-results/udeaAgentGlTest: tests=2 skipped=0 failures=0 errors=0
gl-results/udeaEditorGlTest: tests=1 skipped=0 failures=0 errors=0
```

## 4. Mutations

Each mutation was applied to the committed tree, run, and reverted. Every diff below is the literal
`git diff` saved during the run (`M1.diff`..`M4.diff`). The failing tests are spliced from the
matching `M*.log`.

**M1: no scene2d entry** (the table as it would be with LibGDX banned only as a whole):
```diff
diff --git a/udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/bytecode/BannedOwner.kt b/udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/bytecode/BannedOwner.kt
index 7efea15..3923327 100644
--- a/udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/bytecode/BannedOwner.kt
+++ b/udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/bytecode/BannedOwner.kt
@@ -67,12 +67,6 @@ internal val GL_BANNED_OWNERS: List<BannedOwner> = listOf(
  * namespace that contains it and a scene2d reference is told what replaced it.
  */
 internal val LIBGDX_BANNED_OWNERS: List<BannedOwner> = listOf(
-    BannedOwner(
-        "com/badlogic/gdx/scenes/scene2d/",
-        "scene2d is LibGDX's GL-backed widget toolkit, and the UI layer that replaced it is " +
-            "ComposeGL: udea-render's UiLayer for menus and panels, CapturedUi for a HUD " +
-            "(issues #187, #188, #189)",
-    ),
     BannedOwner(
         "com/badlogic/",
         "LibGDX left the tree in issue #213; rendering is Kool inside udea-render, and " +
```
`:udea-render:jvmTest --tests 'dev.wildware.udea.render.libgdx.*' --tests 'dev.wildware.udea.render.headless.*'`:
```
LibGdxScanTest[jvm] > a class naming scene2d is reported with the scene2d reason under the bytecode id of UDEA-MG-009()[jvm] FAILED
LibGdxScanTest[jvm] > every banned owner in the table is matched by the scan()[jvm] FAILED
```

**M2: scan the engine only, not the game:**
```diff
diff --git a/udea-render/build.gradle.kts b/udea-render/build.gradle.kts
index 7f60dc2..dc012f5 100644
--- a/udea-render/build.gradle.kts
+++ b/udea-render/build.gradle.kts
@@ -192,7 +192,7 @@ val udeaVerifyHeadless = tasks.register<Test>("udeaVerifyHeadless") {
  * `UdeaVerifyNoLibGdxTest` checks it against `settings.gradle.kts`, so a module cannot drop out.
  */
 val libGdxScanModules: List<String> = rootProject.subprojects
-    .filter { it.projectDir.resolve("build.gradle.kts").isFile }
+    .filter { it.projectDir.resolve("build.gradle.kts").isFile && it.path.startsWith(":udea-") }
     .map { it.path.removePrefix(":").replace(':', '/') }
     .sorted()
```
`:udea-render:udeaVerifyNoLibGdx`:
```
UdeaVerifyNoLibGdxTest > the designated list is every project settings includes, the GL-allowed ones and the game included() FAILED
```

**M3: no Android-application layout** (`RepoLayout` as it was before this branch):
```diff
diff --git a/udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/support/RepoLayout.kt b/udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/support/RepoLayout.kt
index 1d64fde..c683d71 100644
--- a/udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/support/RepoLayout.kt
+++ b/udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/support/RepoLayout.kt
@@ -53,7 +53,6 @@ internal object RepoLayout {
      * pass while it did.
      */
     fun classFiles(module: String, sourceSet: String = "main"): List<File> {
-        if (sourceSet == "main" && isAndroidApplication(module)) return androidApplicationClassFiles(module)
         val classesRoot = moduleDir(module).resolve("build/classes")
         val languageDirs = classesRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
         val multiplatform = isMultiplatform(module)
```
`:udea-render:udeaVerifyNoLibGdx`:
```
UdeaVerifyNoLibGdxTest > every designated module was actually scanned() FAILED
UdeaVerifyNoLibGdxTest > no module references a LibGDX type() FAILED
```

**M4: LibGDX back in the headless GL table** (the `com/badlogic/` entry `origin/master`'s table carried):
```diff
diff --git a/udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/bytecode/BannedOwner.kt b/udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/bytecode/BannedOwner.kt
index 7efea15..9fbbca5 100644
--- a/udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/bytecode/BannedOwner.kt
+++ b/udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/bytecode/BannedOwner.kt
@@ -52,6 +52,11 @@ internal val GL_BANNED_OWNERS: List<BannedOwner> = listOf(
         "org/lwjgl/",
         "LWJGL is the native GL/GLFW binding; nothing outside udea-render may name it",
     ),
+    BannedOwner(
+        "com/badlogic/",
+        "LibGDX left the tree in issue #213; its graphics, backends and natives are GL, and " +
+            "UDEA-MG-009 bans every artifact of it",
+    ),
 )
 
 /**
```
Same jvmTest filter as M1:
```
LibGdxScanTest[jvm] > no owner is banned by both the LibGDX table and the headless GL table()[jvm] FAILED
HeadlessScanTest[jvm] > every banned owner in the table is matched by the scan()[jvm] FAILED
```

**The dependency-level control.** This shows `UDEA-MG-009` still does its own job.
`implementation("com.badlogicgames.gdx:gdx:1.13.5")` was added to `moba/desktop/build.gradle.kts`
(`red-artifact.diff`), then `:moba:desktop:udeaVerifyModuleGraph` was run (`red-artifact.log`, spliced):
```
> udeaVerifyModuleGraph: 4 violations
  UDEA-MG-009 :moba:desktop compileClasspath -> com.badlogicgames.gdx:gdx
  UDEA-MG-009 :moba:desktop compileClasspath -> com.badlogicgames.gdx:gdx-jnigen-loader
  UDEA-MG-009 :moba:desktop runtimeClasspath -> com.badlogicgames.gdx:gdx
  UDEA-MG-009 :moba:desktop runtimeClasspath -> com.badlogicgames.gdx:gdx-jnigen-loader
```

The first red is TDD's red: the tests existed before `LibGdxScan` did (`red-1.log`, first line):
```
e: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a385595b60c7fd164/udea-render/src/jvmTest/kotlin/dev/wildware/udea/render/libgdx/LibGdxScanTest.kt:5:42 Unresolved reference 'LIBGDX_BANNED_OWNERS'.
```

## 5. Images

None. Nothing a player or an agent sees changes: the HUD files changed in KDoc only. No file was
copied to the gallery.

## 6. Criterion by criterion

- **"A gate that fails when a `com.badlogic.gdx.scenes.scene2d` reference is compiled into any
  shipped module, proved red by adding one."** `:udea-render:udeaVerifyNoLibGdx`. Proved red with
  a vendored scene2d reference in `moba:game` while `UDEA-MG-009` stayed green (section 1). Its
  detection is tested by `LibGdxScanTest`, and its reach by `UdeaVerifyNoLibGdxTest`:
  every project in settings, and every one of them contributing classes. M1-M4 show those tests
  can fail. Current results (spliced from the JUnit XML):
```
<testsuite name="dev.wildware.udea.render.libgdx.LibGdxScanTest" tests="7" skipped="0" failures="0" errors="0"
<testsuite name="dev.wildware.udea.render.headless.HeadlessScanTest" tests="9" skipped="0" failures="0" errors="0"
<testsuite name="dev.wildware.udea.render.libgdx.UdeaVerifyNoLibGdxTest" tests="3" skipped="0" failures="0" errors="0"
```
- **"`grep -rn scene2d` over `udea-*` and `moba` main sources returns nothing."** The grep half of
  the evidence command: clean at `1a7b8b5`, 8 hits on `origin/master` (section 1).
- **"`sh gradlew udeaVerifyAgentsMd udeaVerifyModuleGraph` green, and `AGENTS.md` says ComposeGL."**
  Both are in the evidence command, green above. `AGENTS.md`: the `udea-render` row and the
  "No LibGDX anywhere" rule (section 2).

## 7. Regenerated files

None. No replicated component changed, so `net-protocol.lock` and `expected-generated-hashes.txt`
are untouched. No file in `docs/contracts/` changed.
