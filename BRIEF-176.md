# BRIEF-176 — the Windows CRLF checkout, and the gate that could not see its own subject

**b9f790b**

That is the commit under review: every change is in it. `BRIEF-176.md` lands in the one commit on
top, so `git rev-parse --short HEAD` shows that instead — `git diff b9f790b HEAD --stat` names
`BRIEF-176.md` and nothing else. A brief cannot contain its own hash.

Branch `issue-176-windows-crlf-golden`, off `origin/example` at `db477f4`.

> **Why `BRIEF-176.md` and not `BRIEF.md`.** `BRIEF.md` in this tree is issue #154's deliverable
> and overwriting it would destroy another ticket's work; `BRIEF-165.md` … `BRIEF-173.md` are the
> established shape. If the reviewer wants it at `BRIEF.md`, it is a rename. Recorded on the issue.

---

## 1. The evidence command

One command. It builds a **real simulated Windows checkout** — `git clone -c core.autocrlf=true`,
which is what Git for Windows does — and runs the four affected test classes inside it. Paste from
the worktree root:

```sh
BRANCH=issue-176-windows-crlf-golden; \
d=$(mktemp -d /tmp/udea-crlf-XXXXXX) && \
git clone -q -c core.autocrlf=true --no-hardlinks --shared -b "$BRANCH" . "$d" && \
export JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem && \
sh gradlew --console=plain -p "$d/build-logic" test \
   --tests '*AgentsMdTest*' --tests '*CompilerPluginSwitchTest*'; a=$?; \
sh gradlew --console=plain -p "$d" :udea-assets-compiler:test :udea-replay:test; b=$?; \
echo "### build-logic exit=$a   root exit=$b"
```

Three things about its shape, each a decision rather than an accident:

- **Two Gradle invocations inside one pasteable command.** `build-logic` is an included build and
  the root build cannot run its tests — `ci.yml` says exactly that in the `legacy-ledger` job
  comment, and it is also why `AgentsMdTest` has never run in `build (windows-latest)` (§2.1).
- **`-p` points the outer wrapper at the clone**, so nothing in the clone is edited and the
  clone's own `gradlew` — which the checkout also translates — is never executed.
- **`--tests` filters `build-logic` to the two classes I touched** rather than running its whole
  suite. Not to hide anything: `KotlinPinCheckTest` cannot pass on this box at all, on any branch,
  and §2.3 gives the checked reason and the control. Dropping the filter makes the command red for
  a reason that is about the machine.

### It goes red on `origin/example` — same command, one word changed

`BRANCH=example`; everything else identical, and `example` here resolves to `db477f4`, the same
commit as `origin/example`. Full saved output:
`…/scratchpad/evidence/brief-cmd-red.txt` (paths in §7).

Lines 19–30 of that file, consecutive:

```
> Task :test FAILED

AgentsMdTest > a row for a module that has been deleted fails() FAILED
    java.util.NoSuchElementException at AgentsMdTest.kt:62

CompilerPluginSwitchTest > the checkers-fire probe is not written into a tree build-logic compiles a second time() FAILED
    org.opentest4j.AssertionFailedError at CompilerPluginSwitchTest.kt:142

CompilerPluginSwitchTest > the checkers-fire probe is in a module the K2 plugin is actually applied to() FAILED
    org.opentest4j.AssertionFailedError at CompilerPluginSwitchTest.kt:142

17 tests completed, 3 failed
```

Lines 143–161 of the same file, from the second invocation, consecutive:

```
> Task :udea-replay:test

CrossPlatformDivergenceTest > a planted one-ulp divergence fails the comparison and names tick, entity, component and field() FAILED
    org.opentest4j.AssertionFailedError at CrossPlatformDivergenceTest.kt:183

97 tests completed, 1 failed

> Task :udea-replay:test FAILED
> Task :udea-assets-compiler:test

ExampleScanTest > two checkouts produce byte-identical json(Path) FAILED
    org.opentest4j.AssertionFailedError at ExampleScanTest.kt:154

ExampleScanTest > the scan of the example tree matches the golden() FAILED
    org.opentest4j.AssertionFailedError at ExampleScanTest.kt:48

167 tests completed, 2 failed
```

Its line 196, the last, is `### build-logic exit=1   root exit=1`.

On this branch, the same command: `…/scratchpad/evidence/brief-cmd-green.txt`, lines 20, 133 and
136 (each fragment separated by elided Gradle task output):

```
BUILD SUCCESSFUL in 10s
```
```
BUILD SUCCESSFUL in 1m 35s
```
```
### build-logic exit=0   root exit=0
```

Both files were produced by pasting the block above into a file and running it — verbatim, not a
script that resembles it — so the command in this brief and the command that produced these
transcripts are the same bytes. The red one differs only in `BRANCH=example` on its first line;
the earlier pair `evidence-red-on-example.txt` / `evidence-green-on-branch.txt` in §7 are the same
two runs made through `evidence-cmd.sh`, kept because they carry a `###` header naming the clone
directory.

---

## 2. Summary

### 2.0 What was wrong

This repository has no root `.gitattributes`, and Git for Windows checks out with
`core.autocrlf=true`. Committed LF files therefore arrive CRLF on Windows, and four test classes
read them in a way that cares.

The one the ticket is really about is `AgentsMdTest`, because it is the test behind
`udeaVerifyAgentsMd`, and `udeaVerifyAgentsMd` is what makes `CLAUDE.md`'s "a stale `AGENTS.md` is
a correctness bug, not a docs nit" a **checkable** claim rather than an assertion. On Windows it
was not checkable. Measured on `core.autocrlf=true` clones rather than argued:

| tree | `AGENTS.md` | `AgentsMdTest` |
|---|---|---|
| `origin/example` | correct | **red** — 1 case, `NoSuchElementException` |
| `origin/example` | stale (the `udea-gas` row deleted) | **red** — 5 cases |
| this branch | correct | **green** |
| this branch | stale (same deletion) | **red** — 7 cases, naming `udea-gas` |

The issue says the gate "reports the same red whether the document is correct or not". The precise
version, which is what those four runs show: on `origin/example` the suite was red on a **correct**
document, so a red carried no information about staleness. The fix is rows 1 and 2 becoming
distinguishable. Row 4 is reproduced on real Windows CI in §6, criterion 2.

### 2.1 The census is longer than the issue's list, and the jobs are not the ones it names

Reproduced by cloning with `-c core.autocrlf=true`, against an **identical control clone** at
`-c core.autocrlf=false`. Red in the first, green in the second, so it is the checkout and not the
box. The golden arrives `CR=169 LF=169` in the first and `CR=0 LF=169` in the second — 169 is
exactly the count the issue quotes out of `test-reports-windows-latest`.

Six failing cases, not two:

| # | case | module | Windows job that runs it |
|---|---|---|---|
| 1 | `ExampleScanTest > the scan of the example tree matches the golden` | `udea-assets-compiler` | `build (windows-latest)` |
| 2 | `ExampleScanTest > two checkouts produce byte-identical json` | `udea-assets-compiler` | `build (windows-latest)` |
| 3 | `AgentsMdTest > a row for a module that has been deleted fails` | `build-logic` | `determinism (windows-latest, *)` |
| 4 | `CompilerPluginSwitchTest > the checkers-fire probe is not written into a tree build-logic compiles a second time` | `build-logic` | `determinism (windows-latest, *)` |
| 5 | `CompilerPluginSwitchTest > the checkers-fire probe is in a module the K2 plugin is actually applied to` | `build-logic` | `determinism (windows-latest, *)` |
| 6 | `CrossPlatformDivergenceTest > a planted one-ulp divergence fails the comparison and names tick, entity, component and field` | `udea-replay` | `build (windows-latest)` |

Rows 3–5 are why acceptance criterion 1 cannot be met as literally written. **`build-logic` is an
included build, so the root `build` does not run its tests** — `ci.yml`'s `legacy-ledger` job says
that in a comment — and `AgentsMdTest` therefore does not run in `build (windows-latest)` at all.
The Windows job that runs it is `determinism (windows-latest, temurin)` and `(windows-latest,
corretto)`, whose step "Verify the allowlist parser, the audit and float portability" is
`./gradlew -p build-logic test`. Both runs are linked in §6.

Row 6 is in `udea-replay`, which dev-172 owns this wave. I asked before touching it; they replied
"take both — the `.gitattributes` and the two-line CR fence in `CrossPlatformDivergenceTest.kt`. I
am not touching that file on `issue-172-replay-gate-at-moba`", and listed what their branch does
touch (`ReplayDigestCli.kt`, three test fixtures, `ReplayEqualityProofTest.kt`,
`ReplayEqualityPathsTest.kt`, `build.gradle.kts`) — no overlap with anything here.

This is what I found, not a proof that nothing else exists. §2.2 says how far the search went.

### 2.2 What I did, split by what each assertion is actually about

The issue ranks three fixes and names option 3 as the trap. I took **1 and 2 together**, split by
subject.

**Byte subjects — option 1, `.gitattributes` plus a CR fence.** The shape #171 shipped for the
vendored bridge sources, and that `udea-diagnostics/.gitattributes` already carried for its own
golden:

- `udea-assets-compiler/.gitattributes` → `src/test/resources/golden/*.json -text`.
  `two checkouts produce byte-identical json` is a determinism assertion whose whole subject is
  bytes — the issue says so, and relaxing that comparison would be wrong.
- `udea-replay/.gitattributes` → `src/test/resources/expected/*.txt -text`.
- The fences: `GoldenResource` (new, `internal`, test source set only) for the first, and one
  `assertEquals` in `CrossPlatformDivergenceTest.assertGolden` for the second. Each refuses a
  translated copy by name. Without one, the failure is an equality diff in which the only
  difference is a carriage return — issue #176's own words: "The rendered assertion looks
  **identical on both sides**, which is the tell." That is why it survived on CI until somebody
  counted bytes in an artefact, and it is what the fence removes.

  A `.gitattributes` on its own fixes today's checkout and leaves no gate: it does nothing about a
  copy restored from an archive, unzipped on Windows, saved by a CRLF editor, or sitting in a
  working tree that predates the attribute. That asymmetry — the attribute governs the checkout,
  the fence governs everything else — is the whole reason #171 shipped both.

**Content subjects — option 2, normalise where the file is read.** Already the settled habit in
this repository: `UdeaProtocolLock.normalise`, `MigrationLedger` (twice),
`GeneratedManifestGoldenTest`, `CoreModuleManifestGoldenTest`, `ReplicatorApiDumpTest`,
`BitLayoutGoldenTest` and `SnapshotLayoutGoldenTest` all strip `\r\n` before comparing — the
sweep output lists them with line numbers.

- `AgentsMdTest`: the failing mutation was built from a literal `\n` —
  `settings.replace("include(\"udea-gas\")\n", "")` — which removed nothing on CRLF, so `findings`
  returned an empty list and `single()` threw. Now one helper, `settingsWithout`, matching
  `\r?\n`, **with an assertion inside it that the removal removed something**. A removal that
  removes nothing is exactly how the original produced a failure with no hint of its cause.
- `CompilerPluginSwitchTest`: `text.indexOf("\n  checkers-fire:\n")` returned −1 on CRLF, so the
  fence reported "ci.yml no longer has a `checkers-fire:` job" about a workflow that has one — a
  fence announcing the absence of the thing it was looking at. `checkersFireJob` now takes the
  workflow as text and normalises once, and `probeDeclaration` passes it through. **`ci.yml`
  itself is untouched**; dev-172 owns it this wave.

Nothing in `AgentsMd` — the production gate — changed. Its regexes are anchored on `^` and `\s`
matches a carriage return, so it was already correct on CRLF. That is now **asserted** rather than
accidental, by `the module table reads the same whatever the checkout did to the line endings`.

**The class sweep.** A finding is a sample; the class is "a committed file whose line endings a
checkout translates, read by something that cares". Script `…/scratchpad/sweep.sh`, output
`…/evidence/class-sweep.txt`. Two searches:

1. Every committed file under a `src/*/resources` tree, with CR/LF counts. The only two
   byte-compared goldens without protection were the two this branch fixes. The rest —
   `udea-codegen/CodegenFixtures-agent-tools.json`, `udea-core/golden/core-module-systems.txt`,
   `udea-net/goldens/bit-layout.txt` and `snapshot-layout.txt` — are read through
   `.replace("\r\n", "\n")` at the comparison; `udea-diagnostics/golden/diagnostics.json` already
   had its own `.gitattributes`; `udea-codegen/expected-generated-hashes.txt` is read with
   `readLines()`, which strips `\r\n`.
2. Every literal-`\n` manipulation of file text in a `.kt`/`.kts`. Two offenders, both fixed. The
   sweep output also lists **the places that already normalise**, so a reader can tell a clean
   sweep from an unmade one. Two survivors are structurally safe rather than lucky:
   `AgentsMd.indexOf("\n## ")` and `ReplayEqualityProofTest.indexOf("\n      - ")` both find their
   needle *inside* a `\r\n`. `AssetCatalogSeamTest` and `AssetIndexWriterTest`'s `endsWith("\n")`
   assert on text the module just produced, not on a committed file — and the latter already
   asserts `"\r" !in text`.
   Search 1 misses goldens outside `src/*/resources`; search 2 catches them —
   `udea-core/api/replicator-contract.api` is one, and `ReplicatorApiDumpTest` already normalises.

   **Nothing else was found**, and here is exactly how far that goes. Empirically, on the CRLF
   clone I ran `build-logic`, `udea-assets-compiler`, `udea-replay`, `udea-codegen`, `udea-core`,
   `udea-net` and `udea-diagnostics` in full; the remaining modules were covered by the two
   searches only. Some of those do own committed resources that search 1 lists, and each is read
   by something that does not care about a line ending:
   `gradle-plugin`, `udea-codegen` and `udea-compiler-plugin`'s four `META-INF/services/…` files
   go through `ServiceLoader`; `udea-gradle`'s two `META-INF/gradle-plugins/*.properties` are
   loaded by `java.util.Properties.load` in `UdeaAgentPluginIdTest` rather than compared as
   bytes; `common`'s two are a Kotlin script-template marker (zero bytes of content) and a
   `test.udea.kts` fixture; and `example/src/main/resources/assets/**` is the CRLF corpus itself,
   read through `UdeaDeclarationScanner.normalizeLineEndings`. `udea-annotations`, `udea-gas`,
   `udea-agent`, `udea-agent-host`, `udea-render`, `udea-audio` and `moba` appear in neither
   search — `grep -E '^(udea-annotations|udea-gas|udea-agent|udea-agent-host|udea-render|udea-audio|moba)/'`
   over `class-sweep.txt` returns nothing.

### 2.3 One failure that is NOT a symptom, and a correction to my own first account

`KotlinPinCheckTest` fails **on this box**, on this branch and on `origin/example` alike.
`…/evidence/facts.txt` line 97, one line in the file, wrapped here only to fit:

```
Cannot find a Java installation on your machine (Linux 6.8.0-138-generic amd64) matching:
{languageVersion=17, vendor=any vendor, implementation=vendor-specific}. Toolchain download
repositories have not been configured.
```

I first wrote this off as an artefact of my throwaway clone. **That was wrong**, and I corrected it
on the issue: it fails in this worktree too, and I only noticed by running the whole `build-logic`
suite rather than my two filtered classes. The checked account:

- `KotlinPinCheckTest` drives a nested build through `GradleTestKit`. `GradleFixture.write`
  materialises the fixture's own `settings.gradle.kts`
  (`build-logic/src/test/kotlin/dev/wildware/udea/build/GradleFixture.kt:128`) — `rootProject.name`,
  the `include(...)` lines, and optionally a version-catalog block, but on **no** branch of that
  function a `pluginManagement` block or a foojay resolver. The repository's real
  `settings.gradle.kts:12` does apply `org.gradle.toolchains.foojay-resolver-convention`, which is
  why the main build provisions a 17 toolchain and the fixture cannot.
- This box has no JDK 17 at all. `…/evidence/facts.txt` lines 100–106, consecutive:
  `$ ls /home/shaun/.sdkman/candidates/java/` → `11.0.32-tem`, `21.0.11-tem`, `21.0.2-graalce`,
  `25.0.2-tem`, `25.3.4+1.r25-graalce`, `current`. So auto-detection finds nothing either.
- CI is unaffected — `actions/setup-java` puts a real 17 on the runner, and `legacy-ledger`
  (`./gradlew -p build-logic check`) and both `determinism (windows-latest, *)` legs are green on
  this branch.
- **Control:** the same two cases fail identically in a clean `core.autocrlf=false` clone of
  `example` at `db477f4` on this box, with none of my changes in it.

Not a CRLF symptom (it fails on LF and CRLF alike), and not something I am fixing — giving the
fixture a toolchain resolver is a `build-logic` harness change with nothing to do with #176, and I
am not widening a ticket on the way past. Worth its own issue if anyone wants `-p build-logic test`
runnable on a machine with no 17.

### 2.4 What I rejected

- **Option 3, a repository-wide `* text=auto eol=lf`.** The issue names it as the trap and it is.
  `example/`'s asset corpus is committed CRLF on purpose —
  `UdeaDeclarationScanner.normalizeLineEndings`'s KDoc says "Every `.udea.kts` in the repository is
  CRLF today, so this is not a hypothetical portability nicety: without it, pass 1 is wrong on the
  actual corpus" — and a repo-wide rule would rewrite it. Verified untouched: §6, criterion 4.
- **Marking `AGENTS.md`, `settings.gradle.kts` and `ci.yml` `text eol=lf` too.** It fixes today's
  checkout and leaves the tests still carrying `\n` literals that break the moment anyone edits
  those files in a Windows editor, and it could not have covered `ci.yml` without touching a file
  another ticket owns this wave. **If the owner disagrees**, the change is to add those three paths
  to a root `.gitattributes` — but keep the normalisation at read regardless, because that is the
  half that survives an editor.
- **Normalising the `udea-replay` comparison instead of marking its golden.** Its subject is a
  rendered report, so normalising would have worked — but it would have left the `-text` attribute
  untested and treated the two byte-subject goldens inconsistently. **If the owner disagrees**,
  delete `udea-replay/.gitattributes` and turn that `assertEquals(0, …)` into a `.replace("\r\n",
  "\n")` on `expected`.
- **Sharing one CR fence between the two modules.** They have no common test-fixture module, and
  they are not the same code: one reads a classpath resource and `check`s, the other reads a
  `Path` and `assertEquals`. A shared module for six lines would be a worse change than the
  duplication.

Both decisions, and the correction in §2.3, are on the issue:
[first](https://github.com/wildware-uk/Udea/issues/176#issuecomment-5484697460),
[second](https://github.com/wildware-uk/Udea/issues/176#issuecomment-5485161580),
[correction](https://github.com/wildware-uk/Udea/issues/176#issuecomment-5485205168).

---

## 3. `sh gradlew build`

No exclusions. `…/evidence/full-build.log`, its last three non-blank lines:

```
BUILD SUCCESSFUL in 15s
226 actionable tasks: 138 executed, 80 from cache, 8 up-to-date
Configuration cache entry reused.
```

Run as `sh gradlew clean build` — `clean` added so the green cannot be an artefact of stale
outputs. Tallied across every JUnit XML in the tree afterwards, with the GL run below folded in
(`…/scratchpad/count-tests.sh`, and `…/evidence/facts.txt` line 53):
**`tests=2562 skipped=9 failures=0 errors=0`**.

The 9 skips are accounted for rather than tolerated. Every `<testsuite>` in the tree with a
non-zero `skipped` — `…/evidence/facts.txt` lines 39–40, the whole answer:

```
dev.wildware.udea.assets.compiler.pack.RealArtReproducibilityTest tests=2 skipped=2
dev.wildware.udea.assets.compiler.atlas.RealArtAtlasPackerTest tests=7 skipped=7
```

Those two are the bodies pointed at the paid Tiny RPG archives, and `ci.yml` line 122 calls them
out by name as the ones its "Assert the atlas determinism tests ran and none skipped" step
deliberately does **not** cover, because they are *expected* to skip on any machine without the
archives. Nothing else in the tree skips.

The gates the ticket touches are in the log by name: `:udeaVerifyAgentsMd` (line 37),
`:udea-codegen:udeaCheckProtocolLock` and `:moba:udeaCheckProtocolLock` (375, 399),
`:udeaVerifyDeterminism` (449).

### The GL run, even though this ticket does not touch GL

The diff contains no `src/main` file at all, and nothing in `udea-render` or in the render half of
`udea-agent-host`. So `-Pudea.render.requireGl=true` is not owed here — but a green `build` is no
evidence about GL on this box regardless: `$DISPLAY` is empty and `-Pudea.render.requireGl`
defaults to `false`, so these tasks are free to assert nothing, and in the `clean build` above
they were served from the build cache anyway (`full-build.log` line 349: `> Task
:udea-render:udeaGlTest FROM-CACHE`). Run for real rather than argued:

```sh
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true \
  --rerun-tasks --no-build-cache --console=plain
```

`--rerun-tasks --no-build-cache` because the first attempt came back `> Task
:udea-render:udeaGlTest FROM-CACHE`, and a cache hit is not a GL run. Forced —
`…/evidence/gl-tests.log` lines 83–87, consecutive:

```
> Task :udea-render:udeaGlTest
> Task :udea-agent-host:udeaAgentGlTest

BUILD SUCCESSFUL in 15s
32 actionable tasks: 32 executed
```

`32 executed, 0 from cache` is the part that matters. And the suites those two tasks wrote, so
"it ran" is a count rather than an absence of complaint — `…/evidence/facts.txt` lines 44–49:

```
dev.wildware.udea.render.gl.GlCaptureDeterminismTest tests=4 skipped=0 failures=0
dev.wildware.udea.render.gl.GlCaptureTest tests=5 skipped=0 failures=0
dev.wildware.udea.render.gl.GlOverlayIsolationTest tests=1 skipped=0 failures=0
dev.wildware.udea.render.gl.OffscreenBackendTest tests=8 skipped=0 failures=0
dev.wildware.udea.agent.host.gl.OffscreenRenderToolsTest tests=7 skipped=0 failures=0
dev.wildware.udea.agent.host.gl.OverlayCaptureIsolationTest tests=1 skipped=0 failures=0
```

### What I saw before that, and what the solo runs gave

Being explicit, because the contract asks for it and because a reader deserves the failures as
well as the pass. Every full-build run I made on this box, in order:

| run | load (1 min) | failures | log |
|---|---|---|---|
| `sh gradlew build` | 2.14 measured, straight after a spike from 10.27 | `:udea-core:udeaBenchCharacterMover`, `:udea-assets-compiler:udeaDaemonBudget` | **overwritten** — see below |
| `sh gradlew clean build` | not measured | **none** | `…/evidence/full-build-clean.log` |
| `sh gradlew clean build --no-build-cache` | 8.41 measured | the same two, plus `GraphBudgetTest` inside `:udea-assets-compiler:udeaPackGate` | `…/evidence/full-build-cold.log` |
| `sh gradlew clean build` (the run reported above) | not measured | **none** | `…/evidence/full-build.log` |

The first row is prose, not a transcript, and deliberately carries no numbers: I wrote its log to
`full-build.log` and later runs overwrote that path. What is checkable is which two tasks failed,
which is the same pair as row 3 and the same pair CI fails on `example` (§6, criterion 1). I am
not quoting figures I can no longer show.

Every failure in rows 1 and 3 is a **wall-clock budget**, and every one passes solo with room.
The three that failed in the cold run, each one line quoted from `…/evidence/full-build-cold.log`
at the line number given — they are 33 and 43 lines apart in that file, not adjacent:

| file:line | measured |
|---|---|
| `full-build-cold.log:491` | `    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) median 7.319ms, budget 4.0ms` |
| `full-build-cold.log:524` | `    graph deserialisation: best=9.152451ms median=19.503156ms over 2000 assets (budget 15ms)` |
| `full-build-cold.log:567` | `    warm reload decision: median 648ms over 4 samples [893, 461, 648, 448]` |

Re-run alone, with `--rerun-tasks` so nothing could come from a cache, at load **8.18** — the same
neighbourhood as the load they failed at. `…/evidence/budgets-solo.log`, same four line numbers
given because these are not adjacent either:

| file:line | measured |
|---|---|
| `budgets-solo.log:62` | `    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) median 2.320ms, budget 4.0ms` |
| `budgets-solo.log:67` | `    graph deserialisation: best=5.900689ms median=7.152128ms over 2000 assets (budget 15ms)` |
| `budgets-solo.log:72` | `    warm reload decision: median 179ms over 4 samples [179, 174, 182, 158]` |
| `budgets-solo.log:77` | `BUILD SUCCESSFUL in 22s` |

The arithmetic is the argument. Solo: 2.320 / 4.0 = 58% of budget, 7.152 / 15 = 48%, warm reload
179ms. In the cold full build: 7.319 / 4.0 = 183%, 19.503 / 15 = 130%, warm reload 648ms. Same
box, same commit, similar `/proc/loadavg`; what differs is that inside `build` these tasks share
the machine with every other module's test workers. `pgrep -af "[m]elon-merge"` was returning 16
processes throughout, so the machine was not this branch's alone at any point.
**That is the box, not the branch**, and `build (ubuntu-latest)` on `example` at `db477f4` — a
tree with none of my changes in it — fails on the same `:udea-assets-compiler:udeaDaemonBudget`
and `:udea-agent-host:udeaPhase2Exit` (§6, criterion 1). Nothing in this diff is on any of those
paths: it contains no `src/main` file.

---

## 4. Images

**This ticket has nothing to photograph, and I have not invented anything.** It is a checkout,
`.gitattributes` and test-source change: there is no rendered frame, no simulation state and no
agent-visible surface anywhere in the diff. `git diff --stat origin/example..b9f790b` names eight
files: two `.gitattributes` and six test sources, and **no `src/main` file in any module**. Driving
`moba` over the bridge would produce a screenshot that is identical before and after, which is
worse than no screenshot.

The evidence is the executed transcripts in §1 and §6, and the Actions runs.

---

## 5. Regenerated files

**None.** No replicated component was added or removed, so `udea-codegen/net-protocol.lock` and
`udea-codegen/src/test/resources/expected-generated-hashes.txt` are byte-identical to
`origin/example` — `git diff --stat origin/example..b9f790b -- udea-codegen/` prints nothing.
`udeaCheckProtocolLock` runs on `check` and is green in §3, for `:udea-codegen` and `:moba` both.

---

## 6. The issue's acceptance criteria, one by one

### Criterion 1 — "A real Actions run shows `build (windows-latest)` passing `ExampleScanTest` and `AgentsMdTest`. Link it."

**Met, but it takes two runs and one correction**, for the reason in §2.1: `build
(windows-latest)` does not run `AgentsMdTest` at all.

Read out of the uploaded artefacts, not off a job's colour — `test-reports-windows-latest` and
`determinism-windows-latest-temurin`, rendered per case by `…/scratchpad/report.sh`:

| job | case | `example` @ `db477f4` — [run 33438832167](https://github.com/wildware-uk/Udea/actions/runs/33438832167) | this branch @ `b9f790b` — [run 33445917606](https://github.com/wildware-uk/Udea/actions/runs/33445917606) |
|---|---|---|---|
| `build (windows-latest)` | `ExampleScanTest > the scan of the example tree matches the golden` | failed | **passed** |
| `build (windows-latest)` | `ExampleScanTest > two checkouts produce byte-identical json` | failed | **passed** |
| `determinism (windows-latest, temurin)` | `AgentsMdTest > a row for a module that has been deleted fails` | failed | **passed** |
| `determinism (windows-latest, temurin)` | `CompilerPluginSwitchTest > the checkers-fire probe is in a module the K2 plugin is actually applied to` | failed | **passed** |
| `determinism (windows-latest, temurin)` | `CompilerPluginSwitchTest > the checkers-fire probe is not written into a tree build-logic compiles a second time` | failed | **passed** |

The run is on `b9f790b`, the commit at the top of this brief — not on a predecessor. All three new
cases pass there too: `a deleted module is still caught when the checkout translated the line
endings`, `the module table reads the same whatever the checkout did to the line endings`, and
`the job slice survives a checkout that translated the line endings`; `GoldenResourceTest` reports
all three of its cases `passed` inside `build (windows-latest)`, which is the fence itself running
on the platform the ticket is about. As whole jobs, `determinism (windows-latest, temurin)` and
`(windows-latest, corretto)` both went `failure` → `success`.

**`build (windows-latest)` is still red as a job, and I am not claiming otherwise.** It fails on
`:udea-agent-host:udeaPhase2Exit` and `:udea-assets-compiler:udeaDaemonBudget`, two wall-clock
budgets. Both are pre-existing and neither is about line endings —
`grep -oE 'Execution failed for task .[^.]*\.'` over the three downloaded job logs,
`…/evidence/facts.txt` lines 55–71, consecutive:

```
=== which tasks each failing CI job names ===
$ grep -oE 'Execution failed for task .[^.]*\.' ci-final-win-build.log | sort -u
Execution failed for task ':udea-agent-host:udeaPhase2Exit'.
Execution failed for task ':udea-assets-compiler:udeaDaemonBudget'.

$ grep -oE 'Execution failed for task .[^.]*\.' ci-final-ubuntu-build.log | sort -u
Execution failed for task ':udea-agent-host:udeaPhase2Exit'.

$ grep -oE 'Execution failed for task .[^.]*\.' ci-final-plugindisabled.log | sort -u
Execution failed for task ':udea-agent-host:udeaPhase2Exit'.

$ grep -oE 'Execution failed for task .[^.]*\.' ci-baseline-ubuntu-build-failed.log | sort -u
Execution failed for task ':udea-agent-host:udeaPhase2Exit'.
Execution failed for task ':udea-assets-compiler:udeaDaemonBudget'.

$ grep -oE 'Execution failed for task .[^.]*\.' ci-baseline2-ubuntu-build-failed.log | sort -u
Execution failed for task ':udea-agent-host:udeaPhase2Exit'.
```

The first three are this branch's three failing jobs on run 33445917606; the last two are
`example`'s `build (ubuntu-latest)` at `db477f4` and at `ea9b267`. **Every failing job in every
one of those runs, on both platforms, names `:udea-agent-host:udeaPhase2Exit`** — a latency gate
in a module this diff does not touch, failing on a tree that predates this branch. Nothing else
appears on this branch that does not also appear on `example`.

Ubuntu has no CRLF problem at all, so those two are not this defect, and they were failing before
this branch existed. What the artefact proves is that `ExampleScanTest` **ran inside the Windows
job and passed**, which is the criterion. There is no honest way for me to produce a green job
without fixing two budgets this ticket is not about.

One consequence, stated because it is a gap rather than because it is comfortable: the job stops
at those budgets, so `:udea-replay:test` never runs on Windows and `CrossPlatformDivergenceTest`
(§2.1, row 6) has **no Windows Actions result either way**. Checked, not assumed — there is no
`udea-replay` directory in the `test-reports-windows-latest` artefact of run 33445917606, and
`find … -iname '*CrossPlatform*'` over it returns nothing. Its evidence is therefore the local
CRLF clone — red on `example`, green here, §1 — plus the attribute demonstrably making the golden
arrive `CR=0 LF=14` on a `core.autocrlf=true` checkout where it previously arrived `CR=14 LF=14`
(mutation M1, below).

### Criterion 2 — "A genuinely stale `AGENTS.md` still makes `AgentsMdTest` fail **on Windows**, not only on Linux. Show it."

**Met on real Windows CI.** Throwaway branch `issue-176-stale-agents-md-probe` (this branch's
code, plus one deleted row), pushed, read, deleted:
[run 33442323758](https://github.com/wildware-uk/Udea/actions/runs/33442323758).
`determinism (windows-latest, temurin)`, `AgentsMdTest`, out of the downloaded
`determinism-windows-latest-temurin` artefact — `…/evidence/facts.txt` lines 75–85:

```
a deleted module is still caught when the checkout translated the line endings() :: failed
a module added to settings without a row fails() :: failed
a row for a module that has been deleted fails() :: failed
all nine section 5 contracts are checked for, not eight() :: passed
an AGENTS_md with no module section is a hard failure() :: passed
dropping a spec section 5 contract fails, naming it() :: failed
every module in settings gradle kts has a row() :: failed
tables outside the module section are not mistaken for modules() :: passed
the committed AGENTS_md matches the committed settings script() :: failed
the deleted D6 modules are not documented as if they still existed() :: passed
the module table reads the same whatever the checkout did to the line endings() :: failed
```

and the message, grepped out of that same HTML report — `…/evidence/facts.txt` line 89, raw, its
trailing `]&gt;` being where the HTML's CDATA section ends:

```
udea-gas', which has no row in the module table. An agent starting cold would not know the module exists.]&gt;
```

It names its subject, which is the whole point: on `origin/example` under CRLF the same class was
red on a *correct* `AGENTS.md` with `NoSuchElementException`, so red said nothing (§2.0's table).

Locally as well, mutation **M3** — `…/evidence/mutations.txt` lines 38–55, run against `b9f790b`.
The diff is the literal one that run printed, not a description of it; nothing in that script
pipes a diff through `head` or `cut`:

```
############ M3: a genuinely stale AGENTS.md, CRLF checkout ############
--- git diff (literal, untruncated) ---
diff --git a/AGENTS.md b/AGENTS.md
index 0eca113..322abdd 100644
--- a/AGENTS.md
+++ b/AGENTS.md
@@ -53 +52,0 @@ Arrows point downward only. A module may depend on modules below it in this tabl
-| `udea-gas` | Abilities, attributes, effects — tick-denominated |
--- run ---
> Task :test FAILED
AgentsMdTest > the committed AGENTS_md matches the committed settings script() FAILED
AgentsMdTest > a module added to settings without a row fails() FAILED
AgentsMdTest > the module table reads the same whatever the checkout did to the line endings() FAILED
AgentsMdTest > dropping a spec section 5 contract fails, naming it() FAILED
AgentsMdTest > a deleted module is still caught when the checkout translated the line endings() FAILED
AgentsMdTest > every module in settings gradle kts has a row() FAILED
AgentsMdTest > a row for a module that has been deleted fails() FAILED
11 tests completed, 7 failed
```

### Criterion 3 — "A genuine byte difference still fails `two checkouts produce byte-identical json`."

**Met.** Mutation **M2**: one character in the golden, in a `core.autocrlf=false` clone, line
endings untouched. `…/evidence/mutations.txt` lines 20–36, verbatim and consecutive:

```
############ M2: one byte changed in the golden, LF checkout ############
--- git diff (literal, untruncated) ---
diff --git a/udea-assets-compiler/src/test/resources/golden/example-declarations.json b/udea-assets-compiler/src/test/resources/golden/example-declarations.json
index 87443b8..d09e39b 100644
--- a/udea-assets-compiler/src/test/resources/golden/example-declarations.json
+++ b/udea-assets-compiler/src/test/resources/golden/example-declarations.json
@@ -32 +32 @@
-    {"id": "character/orc_elite_walk", "kind": "spriteSheet", "name": "orc_elite_walk", "file": "example/src/main/resources/assets/character/orc_elite.udea.kts", "startLine": 129, "startColumn": 5, "endLine": 129, "endColumn": 16},
+    {"id": "character/orc_elite_wallk", "kind": "spriteSheet", "name": "orc_elite_walk", "file": "example/src/main/resources/assets/character/orc_elite.udea.kts", "startLine": 129, "startColumn": 5, "endLine": 129, "endColumn": 16},
--- line endings unchanged ---
udea-assets-compiler/src/test/resources/golden/example-declarations.json CR=0 LF=169
--- run ---
ExampleScanTest > two checkouts produce byte-identical json(Path) FAILED
ExampleScanTest > the scan of the example tree matches the golden() FAILED
170 tests completed, 2 failed
> Task :udea-assets-compiler:test FAILED
BUILD FAILED in 55s
```

`170 tests completed, 2 failed` is the load-bearing part: all three `GoldenResourceTest` cases
stayed green, so the CR fence has not quietly become the only thing being asserted. Confirmed in
its own report — `TEST-…GoldenResourceTest.xml` from that run reads `tests="3" skipped="0"
failures="0" errors="0"`.

**And the fences themselves go red, which is the other direction.** Mutation **M1**: both
`.gitattributes` removed from a `core.autocrlf=true` checkout, so git re-materialises the goldens
translated. Same file, lines 1–18, verbatim and consecutive:

```
############ M1: both .gitattributes removed, CRLF checkout ############
--- git diff HEAD --stat (the mutation) ---
 udea-assets-compiler/.gitattributes | 25 -------------------------
 udea-replay/.gitattributes          | 17 -----------------
 2 files changed, 42 deletions(-)
--- resulting line endings ---
udea-assets-compiler/src/test/resources/golden/example-declarations.json CR=169 LF=169
udea-replay/src/test/resources/expected/planted-divergence.txt CR=14 LF=14
--- run ---
CrossPlatformDivergenceTest > a planted one-ulp divergence fails the comparison and names tick, entity, component and field() FAILED
97 tests completed, 1 failed
> Task :udea-replay:test FAILED
ExampleScanTest > two checkouts produce byte-identical json(Path) FAILED
ExampleScanTest > the scan of the example tree matches the golden() FAILED
GoldenResourceTest > the golden reached this checkout untranslated() FAILED
170 tests completed, 3 failed
> Task :udea-assets-compiler:test FAILED
BUILD FAILED in 58s
```

This one mutation is a whole-file deletion, so `--stat` is the diff without loss: the two files
contain nothing but the comment blocks and the one attribute line each, 25 and 17 lines, and
`wc -l` on them in the worktree gives the same two numbers. Note which `GoldenResourceTest` case
fails: only the one whose subject is *this checkout*. The two that test the fence's own behaviour
stay green, which is what the fixture in its `private companion object` is for.

And the messages, spliced from the JUnit XML of that run (`…/evidence/mutations/m1/…`), which is
the difference between a fence and a bare equality failure. Two edits and no others: the XML's
`&amp;&amp;` is shown as `&&`, and the long single line is wrapped:

```
/golden/example-declarations.json reached the test with 169 carriage return(s) in it. It is
committed with LF, and nothing in this repository writes CRLF into it, so the copy on the
classpath was translated on the way here - a checkout with core.autocrlf=true (Git for Windows'
default) is the usual cause, and udea-assets-compiler/.gitattributes marks the goldens `-text` to
prevent it. Refresh the working tree (git rm --cached -r . && git reset --hard) or fix the editor
that saved it. Reported here rather than by the byte comparison, whose rendered diff looks
identical on both sides when the only difference is a carriage return (issue #176).
```

```
src/test/resources/expected/planted-divergence.txt reached this test with carriage returns in it.
It is committed with LF and the renderer emits LF, so this copy was translated on the way here -
a checkout with core.autocrlf=true (Git for Windows' default) is the usual cause, and
udea-replay/.gitattributes marks it `-text` to prevent that. Reported here because the comparison
below would print two blocks that look identical. ==> expected: <0> but was: <14>
```

### Beyond the criteria: the two new tests M1–M3 do not reach

M1 fails `the golden reached this checkout untranslated` and M3 fails `the module table reads the
same whatever the checkout did to the line endings`, so those two have been seen red. Two of the
new cases are not covered by any of the three, because their subject is the new logic itself
rather than the tree. **M4** neuters that logic directly, on an ordinary LF checkout —
`…/scratchpad/mut4.sh`, output `…/evidence/mutations4.txt`, run against `b9f790b`:

| mutation | literal diff | what went red |
|---|---|---|
| **M4a** the CR fence stops refusing anything | `-        check(carriageReturns == 0) {` / `+        check(true) {` in `GoldenResource.kt:47` | `GoldenResourceTest > a translated copy is refused, and the failure names the cause and the file` — `3 tests completed, 1 failed` |
| **M4b** the job slicer stops normalising | `-        val text = ciYaml.replace("\r\n", "\n")` / `+        val text = ciYaml` in `CompilerPluginSwitchTest.kt:152` | `CompilerPluginSwitchTest > the job slice survives a checkout that translated the line endings` — `9 tests completed, 1 failed` |

`1 failed` in each is the load-bearing part: the mutation bites the case it is supposed to bite
and no other. The diffs above are quoted from `mutations4.txt`, which prints
`git diff -U1` in full for each.

**And a note on how M4 nearly went wrong, because it is the exact defect this ticket is about one
level up.** The first version of `mut4.sh` had a `sed` whose backslash escaping was wrong for
M4b. It matched nothing, the diff printed empty, and the build came back `BUILD SUCCESSFUL` —
which, written into a mutation table, would have read as "this test cannot fail" when in fact the
mutation had never been applied. The check ran against the wrong subject and returned the answer
that looked like news. The script now has a `guard()` that aborts if `git diff --quiet` says the
file is unchanged, so a mutation that does not apply can never be reported as a green.

Compare the pre-fix failure for the same condition, grepped raw out of
`…/evidence/before-ExampleScanTest.xml` — `…/evidence/facts.txt` line 93, the first 180 characters
of that attribute (the grep says so: `[^"]\{0,180\}`):

```
failure message="org.opentest4j.AssertionFailedError: a relocated checkout must still match the golden ==&gt; expected: &lt;{&#13;&#10;  &quot;assetRoot&quot;: &quot;example/src/main/resources/assets&quot;,&#13;&#10;  &quot;declarations&quot;: [&#13;&#10
```

That is the whole of what the pre-fix failure said about the cause: nothing. The `&#13;` is
visible there only because XML escapes a carriage return; on a terminal it is a control character
that moves the cursor and prints as nothing, which is why the issue's author had to count bytes.

### Criterion 4 — "`example/`'s deliberately-CRLF corpus is unchanged. `git diff --stat` shows it."

**Met, two ways.** Both from `…/evidence/facts.txt`, which records each command with its answer.
Lines 1–15, consecutive:

```
=== the corpus is untouched ===
$ git diff --stat origin/example..b9f790b -- example/

$ git diff --stat origin/example..b9f790b
 .../kotlin/dev/wildware/udea/build/AgentsMdTest.kt | 75 ++++++++++++++++++++--
 .../udea/build/CompilerPluginSwitchTest.kt         | 45 +++++++++++--
 udea-assets-compiler/.gitattributes                | 25 ++++++++
 .../udea/assets/compiler/scan/ExampleScanTest.kt   | 14 ++--
 .../udea/assets/compiler/scan/GoldenResource.kt    | 60 +++++++++++++++++
 .../assets/compiler/scan/GoldenResourceTest.kt     | 64 ++++++++++++++++++
 udea-replay/.gitattributes                         | 17 +++++
 .../replay/equality/CrossPlatformDivergenceTest.kt | 12 ++++
 8 files changed, 300 insertions(+), 12 deletions(-)

$ git diff --stat origin/example..b9f790b -- udea-codegen/
```

The first and last print nothing, which is the point of quoting the whole run rather than the
first command alone: the whole diff is eight files, none of them under `example/` and none under
`udea-codegen/`.

Second way, the one that actually matters, because an empty diff says nothing about what a
*checkout* does. Lines 24–35 of the same file, consecutive — a `core.autocrlf=true` clone of
`b9f790b` (the one mutation M3 made), then the same measurement on a `core.autocrlf=true` clone
of `origin/example`:

```
=== what a core.autocrlf=true checkout of b9f790b writes ===
[… line 25, the eol.sh command with its absolute paths, elided …]
example/src/main/resources/assets/config.udea.kts                      CR=7      LF=7      len=128
udea-assets-compiler/src/test/resources/golden/example-declarations.json CR=0      LF=169    len=35725
udea-replay/src/test/resources/expected/planted-divergence.txt         CR=0      LF=14     len=790
AGENTS.md                                                              CR=185    LF=185    len=10188
settings.gradle.kts                                                    CR=52     LF=52     len=1913

=== the same file in a core.autocrlf=true checkout of origin/example ===
[… line 33, the same command against the pre-fix clone, elided …]
example/src/main/resources/assets/config.udea.kts                      CR=7      LF=7      len=128
udea-assets-compiler/src/test/resources/golden/example-declarations.json CR=169    LF=169    len=35894
```

Row by row, because every row is a decision:

- the corpus file is `CR=7 LF=7 len=128` in **both** clones — byte-for-byte what it was before,
  because its blob already holds CRLF and git does not double-convert;
- the golden went `CR=169 LF=169 len=35894` → `CR=0 LF=169 len=35725`, which is the fix, and 169
  is the count the issue quotes from CI;
- `AGENTS.md` and `settings.gradle.kts` still arrive CRLF, on purpose — they are content
  subjects, the tests deal with it at the read, and marking them is the change I rejected in
  §2.4. (`AGENTS.md` reads 185 rather than 186 because M3 had deleted a row from it.)

And `git check-attr text` confirms no attribute of mine reaches the corpus — lines 17–22:

```
=== no attribute of mine reaches the corpus or the brief ===
$ git check-attr text -- udea-assets-compiler/src/test/resources/golden/example-declarations.json udea-replay/src/test/resources/expected/planted-divergence.txt example/src/main/resources/assets/config.udea.kts AGENTS.md
udea-assets-compiler/src/test/resources/golden/example-declarations.json: text: unset
udea-replay/src/test/resources/expected/planted-divergence.txt: text: unset
example/src/main/resources/assets/config.udea.kts: text: unspecified
AGENTS.md: text: unspecified
```

---

## 7. Where the artefacts are

All under
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/01ec1be7-305f-4987-ab53-69f61b72d43e/scratchpad/`:

| path | what it is |
|---|---|
| `brief-cmd.sh` | §1's block, extracted from this file with `sed` and run verbatim |
| `brief-cmd-red.sh` | the same, with `BRANCH=example` |
| `evidence/brief-cmd-red.txt` | §1's red run on `example` (= `db477f4`) |
| `evidence/brief-cmd-green.txt` | §1's green run on this branch |
| `evidence-cmd.sh`, `evidence/evidence-{red-on-example,green-on-branch}.txt` | the same two runs through a script wrapper that echoes the clone directory |
| `evidence/budgets-solo.log` | the three wall-clock budgets re-run alone (§3) |
| `sweep.sh`, `evidence/class-sweep.txt` | §2.2's class sweep, script and output |
| `evidence/before-*.xml` | JUnit XML from the pre-fix CRLF clone |
| `evidence/lf-control-*.xml` | the same classes from the LF control clone |
| `evidence/mut1/`, `mut2/`, `mut3/` | the three mutation runs (§6) |
| `evidence/win-before/`, `win-after/` | `test-reports-windows-latest` from both Actions runs |
| `evidence/det-win-before/`, `det-win-after/`, `det-win-probe/` | `determinism-windows-latest-temurin` from all three |
| `evidence/full-build.log`, `full-build-clean.log`, `full-build-cold.log` | the three surviving full-build runs (§3) |
| `evidence/gl-tests.log` | the forced xvfb GL run (§3) |
| `facts.sh`, `evidence/facts.txt` | every one-line fact this brief quotes, with the command that produced it |
| `mutations.sh`, `evidence/mutations.txt` | mutations M1–M3 (§6), against `b9f790b` |
| `mut4.sh`, `evidence/mutations4.txt` | mutations M4a and M4b (§6), with the apply-guard |
| `evidence/mutations/` | the five throwaway clones those two scripts made |
| `count-tests.sh`, `report.sh`, `eol.sh` | tally JUnit XML; render a Gradle HTML class report as `name :: passed/failed`; count CR and LF |
| `verify-splices.py` | the contiguity check described below |

These live outside the repository on purpose: they are run output, not source. Every Actions figure
is read out of a downloaded artefact rather than from a job's colour.

**And every fenced block in this file was checked against them**, by
`…/scratchpad/verify-splices.py <this file> <the scratchpad>`. It is a *contiguity* check, not a
membership one: each segment between elision markers must appear as a consecutive, in-order run of
lines in some artefact, so a block whose lines were reordered or hand-typed fails it. It caught
nine such blocks in an earlier draft of this brief — a GL fragment with a blank line silently
dropped, three budget lines 33 and 43 apart presented as adjacent, and several command-and-output
blocks I had typed rather than run — which is why `facts.txt` exists at all. Run it and it reports
`ok` for every block except three, all of them declared where they appear:

| block | why it cannot match a line for line |
|---|---|
| §2.3's `Cannot find a Java installation …` | one long line in the XML, wrapped here to fit |
| §6 criterion 3's two CR-fence messages | one long line each, wrapped, and the XML's `&amp;&amp;` shown as `&&` |

The `sh` block in §1 is skipped by the checker because it is input rather than output; it was
verified the other way, by extracting it from this file with `sed` and running it (§1).
