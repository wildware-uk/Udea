47bdb85

# BRIEF-213: delete the old tree, LibGDX and the legacy gates

Branch `issue-213-delete-old-tree`, off `origin/kmp` at `407123a`. The SHA above is the last code
commit; this brief is committed on top of it and changes no code.

## 1. The evidence command

Run from a fresh clone of the branch (it is the fresh-clone proof AC2 asks for). `$SRC` is this
repository or worktree, `$DIR` an empty scratch path:

```
git clone -q --branch issue-213-delete-old-tree "$SRC" "$DIR" && cd "$DIR" \
&& ! git grep -n com.badlogicgames -- gradle/libs.versions.toml '*.gradle.kts' \
&& xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
   ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
   sh gradlew udeaVerifyModuleGraph udeaVerifyAgentsMd :moba:desktop:runShot \
&& test -z "$(git status --porcelain)"
```

It leaves `moba/desktop/build/reports/udea/roster.png`. Each part maps to one criterion: the grep is
AC1, `runShot` plus the empty `git status` is AC2 (staging runs ahead of the asset pack the shot
draws from), and the two gates are AC3.

**As run** (clone at `47bdb85`, in `scratchpad/issue213/clone`; I ran the steps one at a time
because this session's shell refuses compound commands that start `git` outside its worktree).
The grep printed nothing and exited 1. Spliced from `scratchpad/issue213/logs/evidence-green.log`:

```
[udeaStageCharacterArt] staged 33 sheet(s) into /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue213/clone/moba/game/assets/sprites
...
BUILD SUCCESSFUL in 28s
```

Before the build, `ls moba/game/assets/sprites` in the clone listed only `arrow` and
`champion_idle.png`. After it, the tree held 35 PNGs and `git status --porcelain` printed nothing.
`roster.png` is 152885 bytes and shows all six characters (image below).

**It goes red when the feature is reverted.** Three mutations, each made in the clone and then
reverted with `git checkout -- .`. The diffs are in `scratchpad/issue213/logs/mutation-{1,2,3}.diff`.

1. The art source pointed back at the deleted path:
   ```
   -    internal const val SOURCE_TREE: String = "example-assets/sprites"
   +    internal const val SOURCE_TREE: String = "example/src/main/resources/assets/sprites"
   ```
   The same Gradle line fails (`evidence-red-1.log`):
   ```
   * What went wrong:
   A problem was found with the configuration of task ':moba:game:udeaStageCharacterArt' (type 'UdeaStageCharacterArtTask').
     - Type 'dev.wildware.udea.build.UdeaStageCharacterArtTask' property 'sourceTree' specifies directory '/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue213/clone/example/src/main/resources/assets/sprites' which doesn't exist.
   ...
   BUILD FAILED in 1m 6s
   ```
2. A LibGDX coordinate put back, and used:
   ```
   +gdx = { module = "com.badlogicgames.gdx:gdx", version = "1.14.2" }
   +    implementation(libs.gdx)            (udea-agent-host/build.gradle.kts)
   ```
   The grep prints `gradle/libs.versions.toml:126:gdx = { module = "com.badlogicgames.gdx:gdx", version = "1.14.2" }`,
   and `:udea-agent-host:udeaVerifyModuleGraph` fails (`evidence-red-2.log`):
   ```
   > udeaVerifyModuleGraph: 4 violations
     UDEA-MG-009 :udea-agent-host compileClasspath -> com.badlogicgames.gdx:gdx
         no project resolves a LibGDX artifact
         resolution path: :udea-agent-host -> com.badlogicgames.gdx:gdx
     UDEA-MG-009 :udea-agent-host compileClasspath -> com.badlogicgames.gdx:gdx-jnigen-loader
   ```
   `udea-agent-host` is one of the two GL-exempt modules, so it is the case the widened rule is for.
3. A deleted module's row put back in `AGENTS.md`:
   ```
   +| `common` | **Old tree.** Replaced module by module, deleted in Phase 6 |
   ```
   `udeaVerifyAgentsMd` fails (`evidence-red-3.log`):
   ```
   > udeaVerifyAgentsMd found 1 problem(s):
     
       AGENTS.md:1:1: error: [UDEA-DOC-001] the module table lists 'common', which settings.gradle.kts does not include. A row for a module that is gone reads as current.
     
     AGENTS.md's module table (under '## Modules') must list exactly the projects settings.gradle.kts includes, and the file must name every spec section 5 contract. Edit AGENTS.md in the same change as the module or contract it describes.
   ```

## 2. Summary

**Deleted:** `common`, `gradle-plugin`, `example` and `example:assets` (all four from
`settings.gradle.kts`, the files with them); `docs/migration/ledger.md`;
`scripts/gen-migration-ledger.py`; the gates `udeaVerifyNoLegacyDependencies` (`UDEA-LEGACY-001`),
`udeaLegacyReport` and `udeaVerifyMigration` (`UDEA-MIG-001..004`) with their code
(`LegacyDependencyRules`, `MigrationLedger`, `UdeaMigrationTasks`), their plugin
(`udea.legacy-dependency-check`), their tests (`LegacyDependencyCheckTest`,
`LegacyDependencyRulesTest`, `MigrationLedgerTest`, `MigrationVerifyTest`) and their CI step; the
unused `udea.kotlin-library-gl` convention, which put gdx and its LWJGL3 backend on whoever applied
it (nobody did); and every `com.badlogicgames` entry in `gradle/libs.versions.toml`, plus the
other old-tree-only catalog entries (`junit`, `opentest4j`, `junit-jupiter`, the `fleks` *library*,
`composegl-gdx`, the `changelog`/`intelliJPlatform`/`kotlin`/`kover`/`qodana` plugins). None of
those was referenced outside the old tree. The `fleks` *version* stays; the determinism pin reads it.
No rule id lived in `udea-diagnostics`, so that module is untouched.

**The art.** `example/src/main/resources/assets/` moved whole, history kept, to `example-assets/`
at the repository root. That covers the 64 art files, the 24 sounds and the 19 asset scripts: 107
renames, all at 100% similarity (`git diff -M --name-status origin/kmp..HEAD`).
`CharacterArtStaging.SOURCE_TREE` is now `example-assets/sprites`. `LICENSE` names the new paths
with the same exclusions, and `docs/art-assets.md` keeps option 1 unchanged and adds a section on
the move. The whole tree moved, not only `sprites/`, because `udea-assets-compiler`'s tests (the
pass-1 scan golden, the migrator, sheet geometry) use it as their pre-migration corpus. Moving it as
one unit keeps that corpus byte-identical; only the repo-relative paths inside
`golden/example-declarations.json` changed. Rejected: committing the art into
`moba/game/assets/sprites/` (the lead ruled it out, and it doubles the exposure), and putting it in
any module (it would ship with that module and be deleted with it). Commented on #213.

**Module-graph rules.** Commented on #213.
- `UDEA-MG-009` was "no moba project resolves LibGDX". It now governs **every** project and bans
  `com.badlogicgames.*:*` and `composegl-gdx*`. Deleting the catalog entries alone left
  `com.badlogicgames.gdx:gdx` explicitly legal on every headless module (it used to be allowed for
  `Vector2`), so nothing would have stopped LibGDX returning through any module but `udea-render`
  and `moba`.
- `UDEA-MG-008` (`udea-render` only) is folded into MG-009. Its id is retired and not reused.
- `UDEA-MG-002` drops its three LibGDX patterns, so one cause gives one rule id.
- Everything else is unchanged: the no-GL-outside-`udea-render` rule, the leaf budgets, the
  upward-arrow rules and the release rules. `governs()` and the scanned-configuration set moved
  from `LegacyDependencyRules` into `ModuleGraphRules` with the same values.

**`udea.migration-check` became `dev.wildware.udea.docs-check`.** It also registered `udeaVerifyAgentsMd` and
`udeaVerifyTrelloMap`, and both stay. Their failure message used to end "docs/migration/ledger.md
explains the columns", which would have pointed at a deleted file, so each gate now states its own
remedy. The shared finding type `MigrationFinding` is renamed `GateFinding`.

**A finding the lead asked to hear about.** One non-old-tree module still took a LibGDX
coordinate: `udea-render`'s `jvmTest` had `implementation(libs.gdx)`, only so the headless bytecode
gate's fixtures could name real LibGDX types. I removed it rather than keep it. The fixtures now
name LWJGL's `GL11` as the positive control and `udea-assets`' `Vec2` as the negative one. The
banned-owner table's LibGDX carve-outs (gdx-math and the `utils` collections legal; `graphics/`,
`backends/` and viewports banned) collapse into one `com/badlogic/` entry, since no LibGDX jar can
be resolved to need a carve-out. **`gdx-box2d`:** nothing live resolves it on `origin/kmp`. Only
`common/build.gradle.kts` declared it, and no live source names `com.badlogic.gdx.physics`, so
there was no physics backend to lose. `NoBox2DInCoreTest` stays: it still fails if `udea-core`
names a LibGDX type.

**Smaller edits.** `NoDuplicateFqnTest` lost the test that scanned the two old trees, and kept its
own-module checks with a non-empty guard added. `AgentModuleBoundaryTest` stopped banning the
`common` jar. The determinism allowlist dropped its `@version gdx` pin and `PINNED_ALIASES` its
`gdx` alias, since no version is left to drift, and `determinism-audit.md` records why. `AGENTS.md`
lost the four module rows and the "old tree" section. Its do-not bullet about `common` became "No
LibGDX", backed by MG-009. `README.md`, `docs/home.md`, `docs/module-graph.md` and `.gitignore`
comments were updated to match. CI: the `build` job stops naming `udeaVerifyNoLegacyDependencies`.
The `legacy-ledger` job becomes `build-logic` and keeps its `-p build-logic check` step, the only
place CI runs the gate rules' own tests. Neither `master` nor `kmp` has branch protection or
rulesets (`gh api .../branches/{master,kmp}/protection` answered 404, `.../rulesets` answered `[]`),
so the job rename cannot strand a required check.

**Not touched:** the retired `example` *branch*; `HANDOFF.md` (no gate reads it); `.claude/`;
provenance comments in `moba/` that say "ported from `example/...`", in files dev-228 and dev-192
are editing; `spikes/` transcripts; `docs/superpowers/` specs.

**Open for the owner (commented on #213):** `moba/game/assets/sounds/` holds byte-identical copies
of the 24 `.ogg` files whose provenance `LICENSE` calls unknown (`cmp`, 24 of 24), and `LICENSE`
never named that path. The gap predates this ticket. I only moved the paths `LICENSE` already
names, because widening an exclusion is a licence decision.

## 3. Tests written first

- `CharacterArtStagingTest.the art is copied out of a tree no Gradle project owns`: red on the
  unchanged tree with `expected: <[]> but was: <[example]>` (`logs/red-art.log`, and the XML
  failure message quoted there), green after the move.
- `ModuleGraphRulesTest.UDEA-MG-009 fails every LibGDX artifact on every project, gdx-math included`,
  `ModuleGraphCheckTest.UDEA-MG-009 fails gdx itself on the kernel`,
  `AgentsMdTest.the deleted modules are not documented as if they still existed` and
  `AgentsMdTest.a failure report says what to fix and points at no document that is gone`: all four
  red before the implementation (`logs/red-2.log`: `61 tests completed, 11 failed`; the other
  seven were AgentsMd cases red because `settings.gradle.kts` had changed ahead of `AGENTS.md`). The
  report test failed on its "ledger"/"migration" assertion: the message began
  `udeaVerifyAgentsMd found 5 migration problem(s):`.
- Headless gate mutation (`logs/mutation-4.diff`, the `org/lwjgl/` entry removed from
  `GL_BANNED_OWNERS`): `HeadlessScanTest` went `9 tests completed, 4 failed`, including
  `a class naming a GL type is reported with its class, its member and the banned owner`.

## 4. The build

`ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue`
at `47bdb85` (`logs/build-2.log`):

```
BUILD SUCCESSFUL in 2m 51s
915 actionable tasks: 568 executed, 36 from cache, 311 up-to-date
```

The baseline is the lead's `kmp` at `6a7a9b1`: fully green, 952 tasks. The count falls because the
old tree's tasks are gone. This ticket turned no named task green, since there was no red one, and
turned no task red.

An earlier full run at `eef1f93` (`logs/build-1.log`) failed one task, `:moba:android:packageDebug`,
with `A failure occurred while executing ...PackageAndroidArtifact$IncrementalSplitterRunnable` and
no cause printed, at load average 17. Re-run alone it passed (`BUILD SUCCESSFUL in 1m 36s`,
`logs/pkg.log`), and it passed inside the full run above. This change does not touch Android.

`sh gradlew -p build-logic check` at `47bdb85` (`logs/bl-check-3.log`): `BUILD SUCCESSFUL in 1m 20s`,
290 tests, 0 skipped, 0 failed (counted from the JUnit XML).

**GL, run for real** because `udea-render`'s test sources changed:
`xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true`
(`logs/gl.log`): `BUILD SUCCESSFUL in 1m 8s`. Counted from the JUnit XML: `udeaGlTest` 12 tests,
0 skipped, 0 failed; `udeaAgentGlTest` 2 tests, 0 skipped, 0 failed.

All logs are in
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue213/logs/`.

## 5. Images

- `issue213-fresh-clone-roster.png`: `:moba:desktop:runShot` from the fresh clone at `47bdb85`, with
  all six characters drawn from art the build staged out of `example-assets/sprites/`. It proves AC2.
  The soldier lying down is the same pose `issue212-parity-roster.png` shows on `kmp`, so it is not
  something this change did.

## 6. Criterion by criterion

| Criterion | Proof |
|---|---|
| No `com.badlogicgames` in `gradle/libs.versions.toml` or any build script | the grep in the evidence command (empty, exit 1), and red with mutation 2. `UDEA-MG-009` keeps it true: it fails on any project that resolves one (mutation 2's output) |
| A fresh clone still stages art and `:moba:desktop:runShot` draws the characters | the evidence run: `staged 33 sheet(s)`, `BUILD SUCCESSFUL`, empty `git status --porcelain`, `issue213-fresh-clone-roster.png`. Red with mutation 1 |
| `udeaVerifyModuleGraph udeaVerifyAgentsMd` green | both ran in the evidence command, each project's `udeaVerifyModuleGraph` included, and both run on `check` in the green `build`. `udeaVerifyAgentsMd` is red with mutation 3 |

## 7. Regenerated files

None of the lock or hash files. `net-protocol.lock` and `expected-generated-hashes.txt` did not
move, because no replicated component changed. The one golden that changed is
`udea-assets-compiler/src/test/resources/golden/example-declarations.json`: a path rewrite of
`example/src/main/resources/assets` to `example-assets` in `assetRoot` and every `file`, with no
other byte changed. `ExampleScanTest` compares it byte for byte, including a relocated-checkout
case, and passes in the green build.
