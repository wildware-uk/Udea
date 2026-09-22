05b644c9

(That is the SHA of the code. This brief is committed on top of it and changes nothing else.)

# #275 part 2: a game outside the tree can write a `UiScreen`

Branch `issue-275-compose-convention`, off `origin/master` at `9938829f`.
Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a14d15eb22dd3dd1a`.

Parts 1 and 3 of #275 (pointer position, the overlay closing the pipeline) are dev-275's, on
`issue-275-overlay-pointer`. Nothing here touches `udea-render`'s sources.

## 1. The evidence command

```
sh gradlew -p build-logic test --tests '*ComposeUiConventionTest*'
```

It builds a small game with Gradle TestKit - `dev.wildware.udea.kotlin-library` plus the new
`dev.wildware.udea.compose-ui`, `composegl-ui`, a screen and a test - and runs *that game's* test,
which composes the screen with ComposeGL's headless `uiTest` and reads its text back.

**It goes red with the convention reverted.** The mutation, its literal diff, and both runs:

| Mutation | Command | Result |
|---|---|---|
| the convention's `plugins { }` block emptied (diff below) | the evidence command | `3 tests completed, 2 failed`, `BUILD FAILED in 49s`; XML `tests="3" ... failures="2"` |
| restored (`git checkout --`, `git status` clean) | the same | `BUILD SUCCESSFUL in 40s`; XML `tests="3" skipped="0" failures="0" errors="0"` |

```
diff --git a/build-logic/src/main/kotlin/dev.wildware.udea.compose-ui.gradle.kts b/build-logic/src/main/kotlin/dev.wildware.udea.compose-ui.gradle.kts
index fd9d55c4..c7f89dca 100644
--- a/build-logic/src/main/kotlin/dev.wildware.udea.compose-ui.gradle.kts
+++ b/build-logic/src/main/kotlin/dev.wildware.udea.compose-ui.gradle.kts
@@ -34,5 +34,5 @@
  */
 
 plugins {
-    id("org.jetbrains.kotlin.plugin.compose")
+    // id("org.jetbrains.kotlin.plugin.compose")
 }
```

Spliced from `scratchpad/dev-275ui/mut-red.diff` (the `git diff` of the run that produced the red
row) and `scratchpad/dev-275ui/mut.marker`:

```
START 2026-09-22T18:01:13+00:00 wt=/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a14d15eb22dd3dd1a head=05b644c9 status=0
red EXIT=1
restored: 0 changed files
green EXIT=0
DONE 2026-09-22T18:02:43+00:00
```

The third test in that class is the **control**: it builds the same game with no
`dev.wildware.udea.compose-ui` at all, requires the build to *fail at `:game:test`*, and asserts
the failure is a `NoSuchMethodError` from the game's own test - so "it compiled" can never be
mistaken for "it works", which is exactly this defect's shape.

## 2. What I did, and what I decided

`UiLayer.show(UiScreen)` takes a `@Composable`, and no published convention applied the Compose
compiler. A game outside this repository therefore could not write interface widgets at all. (The
owner's correction on the issue stands: text was always available without Compose, through
`BitmapFont2D.builtIn`; the gap is widgets.)

**Decision: a new published plugin, `dev.wildware.udea.compose-ui`**, applied *beside* whichever
Kotlin convention a project is on, in either order. It applies
`org.jetbrains.kotlin.plugin.compose` at the catalog's `kotlin` (2.4.20, the version ComposeGL's
runtime is built against and the only version that loads) and does nothing else.

**Alternative rejected: folding it into `dev.wildware.udea.kotlin-multiplatform-render`.** Two
reasons. Needing Compose is about *writing a composable*, not about drawing - `hollow:game` draws
and has no screen, and a JVM game on `dev.wildware.udea.kotlin-library` (the template's shape) can
have one and would get nothing from the render convention. And the Compose compiler rewrites every
class of a project it is applied to (`$stable` and friends), so it belongs only where a composable
is. A new `dev.wildware.udea.*` id is additive; un-publishing one later is a break
(`AGENTS.md`, "Naming a convention plugin"). Commented on the issue:
https://github.com/wildware-uk/Udea/issues/275#issuecomment-5780998708 - with what to change to
overturn it.

**The engine's own modules now take the same path.** `udea-render`, `udea-editor` and `moba:game`
apply `id("dev.wildware.udea.compose-ui")` instead of `alias(libs.plugins.composeCompiler)`, so a
game applies the plugin the engine is built with rather than a parallel one. I checked the whole
class rather than the three I edited, with a control beside it:

```
$ git grep -n "alias(libs.plugins.composeCompiler)" -- '*.kts'
exit=1
$ git grep -n "alias(libs.plugins.kotlinSerialization)" -- '*.kts'
hollow/game/build.gradle.kts:30:    alias(libs.plugins.kotlinSerialization)
moba/game/build.gradle.kts:40:    alias(libs.plugins.kotlinSerialization)
udea-core/build.gradle.kts:26:    alias(libs.plugins.kotlinSerialization)
udea-fleks/build.gradle.kts:20:    alias(libs.plugins.kotlinSerialization)
udea-gas/build.gradle.kts:14:    alias(libs.plugins.kotlinSerialization)
udea-nav/build.gradle.kts:15:    alias(libs.plugins.kotlinSerialization)
exit=0
```

The second is the same search shape against a sibling alias that *is* still applied, so the empty
first result is a search that works and found nothing, not a search that never ran.

`moba/desktop` keeps its one-compilation `kotlinCompilerPluginClasspathEditorTest` line: that is
deliberately not the whole project (its comment says why), and I only corrected the plugin name in
it.

**The catalog's `composeCompiler` plugin alias is kept**, unused in this build, because the catalog
is published as `udea-version-catalog` and removing an entry from a published catalog is a break for
anyone using it. Its comment now says the convention is what applies it.

**Things I decided that the issue left open:**

- *Does the plugin also bring `composegl-ui` or `udea-render`?* No. It brings the compiler only. The
  toolkit arrives with `udea-render`, which any project that shows a screen already depends on, and
  a convention that added dependencies would be one a module cannot opt out of.
- *Does the template use it?* `templates/new-game/settings.gradle.kts` now states its version with
  the other published ids - one line - so a game can apply it without looking the version up, and
  `scripts/outside-game-proof.sh` leg 0b now checks a marker was published for it. The template's
  game module does not apply it: the template has no screen, and dev-269b owns the template's
  sources this wave.

## 3. Proof from outside the tree

Chosen: **both** - the TestKit fixture above (which is in `build`, so it cannot rot), and a one-off
run of the real thing, because the fixture's screen stands in for `UiScreen` while the outside run
uses the published `dev.wildware.udea:udea-render`'s own interface.

`scratchpad/dev-275ui/outside.sh` published the engine and `build-logic` into a **private** Maven
repository, `/srv/ssd1/workspace/udea-review/dev-275ui/m2` (never `~/.m2`), copied
`templates/new-game` to `/srv/ssd1/workspace/udea-review/dev-275ui/my-game`, added two lines to its
build script and one screen, and ran the game's own Gradle.

The two lines added to the copied game (`scratchpad/dev-275ui/outside/game-build.diff`):

```
--- game/build.gradle.kts	2026-09-22 17:21:07.300290291 +0000
+++ /srv/ssd1/workspace/udea-review/dev-275ui/my-game/game/build.gradle.kts	2026-09-22 17:37:09.509245899 +0000
@@ -19,6 +19,7 @@
     // No version on any `dev.wildware.udea.*` id: `settings.gradle.kts` states it
     // once, from the `udeaVersion` property.
     id("dev.wildware.udea.kotlin-library")
+    id("dev.wildware.udea.compose-ui")
 
     // `gamebridge.json`, the debug-only `agent` source set, and the `-PdebugPort=` wiring that
     // puts the agent's HTTP surface on a running instance.
@@ -41,6 +42,7 @@
 dependencies {
     // The kernel: `UdeaGameDef`, `GameHost`, `SimSystem`, `SimClock`, `NetId`.
     implementation("dev.wildware.udea:udea-core:$udeaVersion")
+    implementation("dev.wildware.udea:udea-render:$udeaVersion")
 
     // `@Replicated`, `@Net` and `@Sim` for this game's own components.
     implementation("dev.wildware.udea:udea-annotations:$udeaVersion")
```

The screen is `com.example.newgame.PauseMenu : UiScreen`, two `Text`s in a `Column`
(`scratchpad/dev-275ui/game-files/main/PauseMenu.kt`), and the test holds it as `UiScreen` and
composes it.

`scratchpad/dev-275ui/outside.marker`, whole:

```
START 2026-09-22T17:35:59+00:00 wt=/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a14d15eb22dd3dd1a head=be66c1ce m2=/srv/ssd1/workspace/udea-review/dev-275ui/m2 game=/srv/ssd1/workspace/udea-review/dev-275ui/my-game
publish EXIT=0
publish-build-logic EXIT=0
compose-ui marker poms: 1
names the checkout: 0 lines
green EXIT=0
green-build EXIT=0
red-no-plugin EXIT=1
publish-build-logic-empty EXIT=0
red-empty-convention EXIT=1
publish-build-logic-restored EXIT=0
green-again EXIT=0
worktree clean of the mutation: 0 changed lines
DONE 2026-09-22T17:38:48+00:00
```

- `green` / `green-build`: the outside game's `test` and its full `build`, both green.
  `green-build.log` shows `:game:udeaVerifyModuleGraph`, `:game:udeaVerifyCompilerPlugin`,
  `:game:udeaVerifyEditorAbsent` and `:game:udeaVerifyKotlinPin` all running over it - so a game with
  `udea-render` and a screen still passes the gates it inherits.
- `red-no-plugin`: the same game with the `compose-ui` line deleted. `NoSuchMethodError` on
  `UiTestKt.uiTest$default`, XML `tests="1" failures="1"`.
- `red-empty-convention`: the *game unchanged*, with `build-logic` republished carrying the
  emptied convention (the diff in section 1). Same failure. This is the leg that says the
  convention is what is doing the work, from outside, over a published artifact.
- `green-again`: the real convention republished, game unchanged, green again.
- `compose-ui marker poms: 1` is the published plugin marker,
  `m2/dev/wildware/udea/compose-ui/dev.wildware.udea.compose-ui.gradle.plugin/0.1.0-SNAPSHOT/...pom`
  - inside the `dev.wildware` namespace, which is what Central requires.
- `names the checkout: 0 lines` is the grep for `includeBuild` or this worktree's path in the
  copied game: it resolves the engine from a repository, not from here.

`PluginNamespaceTest` stays green: it ran inside the full build below, and again alone -
`tests="7" skipped="0" failures="0" errors="0" timestamp="2026-09-22T18:04:28.113Z"`.

## 4. `sh gradlew build`

`scratchpad/dev-275ui/fullbuild.marker` and the tail of `fullbuild.log`
(`build --continue --no-daemon --max-workers=4 --no-configuration-cache --no-build-cache`, under
the shared box lock):

```
START 2026-09-22T17:40:51+00:00 wt=/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a14d15eb22dd3dd1a head=05b644c9 status=0
EXIT=0 2026-09-22T17:49:48+00:00
DONE
```

```
BUILD SUCCESSFUL in 8m 56s
1122 actionable tasks: 626 executed, 496 up-to-date
```

No exclusions. `:build-logic:test` executed in it (not from cache - the run was
`--no-build-cache`), and the in-XML timestamps land inside the build's window:
`ComposeUiConventionTest` at `2026-09-22T17:41:27.689Z`, `PluginNamespaceTest` at
`2026-09-22T17:42:25.124Z`. `:udeaVerifyAgentsMd` and `:udeaVerifyWiki` both ran.

`:udea-assets-compiler:udeaDaemonBudget` did not fail; no solo re-run was needed.

### GL, for real

This change edits `udea-render/build.gradle.kts` (the plugin it applies), so the GL suites were run
rather than left to skip. `scratchpad/dev-275ui/gl.marker`:

```
START 2026-09-22T17:57:09+00:00 wt=/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a14d15eb22dd3dd1a head=05b644c9 status=0
EXIT=0 2026-09-22T18:00:35+00:00
DONE
```

The command, exactly as run (`scratchpad/dev-275ui/gl.sh`):

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true \
  --continue --no-daemon --max-workers=4 --no-configuration-cache --no-build-cache --console=plain
```

```
> Task :udea-render:udeaGlTest
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-editor:udeaEditorGlTest
BUILD SUCCESSFUL in 3m 25s
```

Counted out of the XML rather than off the console, with the in-XML timestamps, because a skipped
GL suite also prints green (`scratchpad/dev-275ui/glcount.txt`):

```
udea-render udeaGlTest files=30 tests=31 skipped=0 failed=0 timestamps 2026-09-22T17:57:32.389Z .. 2026-09-22T17:59:15.435Z
udea-agent-host udeaAgentGlTest files=2 tests=2 skipped=0 failed=0 timestamps 2026-09-22T17:59:20.999Z .. 2026-09-22T17:59:25.383Z
udea-editor udeaEditorGlTest files=6 tests=6 skipped=0 failed=0 timestamps 2026-09-22T17:59:28.763Z .. 2026-09-22T18:00:27.912Z
```

Every timestamp is inside the run's window and nothing was skipped, so these are executions.

## 5. Images

None, and the reason is not modesty: this change is a build convention. What it produces that a
person could look at is *text a game's screen draws*, and the outside game's test reads that text
back (`"Paused"`, `"Press Escape to resume"`) rather than photographing it. Drawing one to a PNG
would need a GL context and a `UiLayer`, which is part 3's territory - the overlay/offscreen bug
dev-275 is on - and a screenshot taken through that path would be evidence about that bug rather
than about this plugin.

## 6. The issue, criterion by criterion

#275 is a report of three gaps, not a list of acceptance criteria; this branch is part 2 alone.

| What the issue says | Where it is proved |
|---|---|
| "`UiLayer.show(UiScreen)` takes a `@Composable`, but none of the published convention plugins apply the Compose compiler plugin" | Reproduced twice: `ComposeUiConventionTest`'s control (a game with no such plugin compiles and then throws `NoSuchMethodError`), and `red-no-plugin` outside the tree |
| "A game outside the engine's own build has no way to compile a composable, so the entire interface surface is unreachable" | `outside.marker` `green EXIT=0`: `com.example.newgame.PauseMenu : UiScreen`, composed, its text read back, in a game built from `/srv/ssd1/workspace/udea-review/dev-275ui/my-game` against artifacts in a private repository |
| "Either a convention that brings Compose, or a non-Compose drawing surface with text, would do" | The first: `dev.wildware.udea.compose-ui`, published, with its marker inside `dev.wildware` |
| owner's comment: "the Compose gap is about interface *widgets*, not about text" | The screen uses `Column` + `Text` widgets; nothing here changes `BitmapFont2D` |
| lead: "`PluginNamespaceTest` must stay green" | it ran inside the green full build (in-XML `17:42:25.124Z`), and alone afterwards: `<testsuite name="dev.wildware.udea.build.PluginNamespaceTest" tests="7" skipped="0" failures="0" errors="0" timestamp="2026-09-22T18:04:28.113Z"` |
| lead: "Update AGENTS.md and docs/new-game.md (a short UI section)" | `AGENTS.md` interface bullet; `docs/new-game.md` "Menus and a HUD"; also `docs/module-graph.md`'s convention table and `docs/wiki/UI-with-ComposeGL.md` |

## 7. Regenerated files

**None.** This change adds no component, so no `net-protocol.lock`, no
`expected-generated-hashes.txt`, no `.udearep` fixture and no `test_level.roster.txt` moved.
`git status` is clean after the full build.

## 8. What I did not exercise

- **A multiplatform game applying the convention.** `udea-render` and `moba:game` are on
  `dev.wildware.udea.kotlin-multiplatform-render` and compile with it, which is that path inside this
  repository; the outside game is JVM, because the template is.
- **A `CapturedUi` from outside.** `CapturedUi`'s content is a composable like `UiScreen`'s and
  compiles the same way, but showing one needs a GL context, which is part 3's problem today.
- **Central.** The outside run publishes to a private local repository, like
  `scripts/outside-game-proof.sh` does. What that stand-in cannot check is the namespace rule, and
  that is checked separately: the marker's coordinates are read back out of the repository the
  publish wrote (section 3), and `PluginNamespaceTest` holds the source side.
