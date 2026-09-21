cb64e973

# windows-crlf-shaders - a shader's line endings no longer change the asset graph hash

Code commit `cb64e973`, on `origin/master` `4a4a2df5`. This brief is committed on top of it and
changes no code. Branch `windows-crlf-shaders`. No issue tracks this ticket (it was found on
windows-green), so the two decisions are commented on #269, the shader-asset issue that began
packing the text.

**Windows replay stays red after this branch.** The CRLF cause is fixed and proven. A second,
separate cause (X, section 7) makes Windows pack a different hash: the Assimp native library
converts `Human.fbx` slightly differently on Windows. By the coordinator's decision, this branch
merges the CRLF fix alone, and X is recorded on the existing ticket by the lead.

## 1. Evidence command

    sh gradlew :udea-assets-compiler:test --tests dev.wildware.udea.assets.compiler.validate.ShaderAssetTest --no-build-cache

Green at `cb64e973`: 11 tests, 0 failures. The in-XML timestamp is `2026-09-21T05:27:14.727Z`
against a wall clock of `05:27:30Z`; the results directory was emptied first (`green.marker`).

Red with the fix reverted, on Linux (row M-REVERT in section 4, with its literal diff;
`mut-REVERT.summary`):

    ShaderAssetTest > a shader packs the same bytes whether its file ends lines with LF, CRLF or CR() FAILED
    ShaderAssetTest > a version pragma in a file with lone-CR endings is named at its real line() FAILED
    11 tests completed, 2 failed

The first failure's message begins
`org.opentest4j.AssertionFailedError: crlf: the asset graph hash moved` (`mut-REVERT.xml`). That
is the coordinator's arm C condition: a test feeds CRLF text in, asserts the LF hash, and goes red
**on Linux** without the fix.

The end-to-end evidence is the three-arm experiment in section 3, arm C kept.

## 2. Summary

**The defect.** `ShaderSources` read a `.frag` verbatim and packed its text into the graph, so
the file's bytes are part of the asset graph hash every `.udearep` records. Git on Windows checks
text out with CRLF (`core.autocrlf=true`), and the repository has no root `.gitattributes`. So a
CRLF checkout of master packs `29e3d06a...` where Linux packs `c011c548...`.

**The fix.** One call: `ShaderSources.read` passes the text through the existing
`UdeaDeclarationScanner.normalizeLineEndings`, which turns CRLF and then a lone CR into `\n`. Both
drivers of the passes read here (the Gradle pipeline and the dev daemon), so a hot reload packs
the same text. `Shader.source`'s KDoc said "verbatim" and now says what it is.

**Decisions** (each commented on #269):
- *Normalise at the read, not at the pack.* At the pack, the validator would still see raw text,
  and it counts lines by `\n`, so a lone-CR file would report every `#version` at line 1. The
  second new test pins that.
- *No `.gitattributes` for `*.frag`.* It would make Windows CI check out LF and pass with or
  without the fix, so CI could no longer test it. A game in its own repository inherits none of
  ours (#265). If wanted later, it is additive and moves no hash.
- *Reuse the scanner's normaliser* rather than write a second one (section 8 of the standards,
  copy-pasted logic).

**Worktree.** The coordinator asked for a new worktree. The isolation checker refuses git
operations outside this agent's own worktree, so I made the branch in the existing one with
`git switch -c windows-crlf-shaders origin/master`, and unset the upstream before the first push.

**The sweep for other text read into the hash.** The hash is the sha256 of everything after the
`.udeapak` header: the graph section, the atlas index, the atlas pages (PNG pixels) and blobs,
which no production path produces (only tests pass `blobs`). So a text file can reach the hash only
by becoming a graph value. I grepped every file read in `udea-assets-compiler/src/main` at master
(`sweep-reads.txt`); `ShaderSources.kt:118 readText` is in it, the known positive.
- `.udea.kts` scripts: pass 1 and the transpiler already normalise. The scripting host compiles
  the raw file, so I probed the case that path could miss: a multi-line raw string in a CRLF or
  CR-only script (`ScriptEndingsProbeTest`, scratch only, not committed; `script-probe.txt`):

      PROBE lf disk_CR=0
      PROBE lf declared=1 display={description=first<LF>second} errors=0 hash=c313f92540a99eefe5e0ce8f4d16a0fe68ba5e45df31e9de53128253b73ba82b
      PROBE crlf disk_CR=6
      PROBE crlf declared=1 display={description=first<LF>second} errors=0 hash=c313f92540a99eefe5e0ce8f4d16a0fe68ba5e45df31e9de53128253b73ba82b
      PROBE cr disk_CR=6
      PROBE cr declared=1 display={description=first<LF>second} errors=0 hash=c313f92540a99eefe5e0ce8f4d16a0fe68ba5e45df31e9de53128253b73ba82b

  The carriage returns are on disk (6 each), the value arrives as LF, and the three hashes agree:
  the script path already makes a raw string's line endings LF. Nothing to fix.
- glTF (`GltfClips`, `GltfNodes`, `ModelFileValidator`): parsed as JSON, where a CR can only be
  whitespace between tokens, because a CR inside a JSON string must be escaped.
- `DeterminismValidator` and `AssetSources` (the editor's Save) already normalise, and neither
  feeds the pack. `AssetCompiler.hashOf` hashes raw bytes, but nothing in any `src/main` calls
  it. `FbxConverter` reads binary.

Tracked files of shader or glTF-text shape (`git ls-files '*.frag' '*.vert' '*.glsl' '*.gltf'`):
`moba/game/assets/shaders/scanlines.frag`, the known positive, and the test fixture
`udea-assets-compiler/src/test/resources/packassets/shaders/tint.frag`.

For moba's whole tree the sweep is empirical as well: after the fix, arm B (every text file CRLF,
`shaders.udea.kts` included) packs byte-identical to arm A (section 3). So on Linux no other
CRLF-sensitive input exists there. That says nothing about native code behaving differently on
Windows, which is X.

## 3. Predictions and the three arms

Frozen in `predictions.md` before any arm result was read. The arms are clones of the commit
under test: A with `core.autocrlf=false`, B with `core.autocrlf=true`, and C, which is A with only
`scanlines.frag` run through `sed -i 's/$/\r/'`. Each arm builds `:moba:game:udeaPackBundle`
(`--no-daemon --no-build-cache --max-workers=3`). The hash is `od -An -v -tx1 -j12 -N32` of the
pack.

The P0 control counts carriage returns with `tr -cd '\r' | wc -c` (`clones.txt`):

    armA HEAD=4a4a2df5 autocrlf=false frag_CR=0 frag_lines=23 kts_CR=0 gradlew_CR=0
    armB HEAD=4a4a2df5 autocrlf=true frag_CR=23 frag_lines=23 kts_CR=5 gradlew_CR=234

Before the fix, at master `4a4a2df5` (`pre-result.txt`):

    A exit=0 frag_CR=0 hash=c011c548ff5d5c8790a5abfcb8c271857d9e4233cddf8a952aa1740935e2cbba
    B exit=0 frag_CR=23 hash=29e3d06ab88c898070ce978107f11d693b4f386601ce1487ca64c914e066b7d2
    C exit=0 frag_CR=23 hash=29e3d06ab88c898070ce978107f11d693b4f386601ce1487ca64c914e066b7d2
    restored_frag_CR=0

`cmp pre-B.udeapak pre-C.udeapak` exits 0, and B is 23 bytes longer than A (114776 bytes against
114753), one byte per line of the `.frag`.

P1 held: B equals C, both differ from A, and A has moved off the stale `f3556cc2...`, as #271 was
expected to move it. P2 held: A is the hash both checked-in fixtures record. In
`p2-fixture-hash.txt`, the stale value, searched the same way, is the negative control:

    c011c548 moba-3600.udearep 27
    c011c548 moba-36000.udearep 27
    f3556cc2 moba-3600.udearep -1
    f3556cc2 moba-36000.udearep -1

After the fix, at `cb64e973` (`clones-post.txt` shows the same CR counts; `post-result.txt`):

    A exit=0 frag_CR=0 hash=c011c548ff5d5c8790a5abfcb8c271857d9e4233cddf8a952aa1740935e2cbba
    B exit=0 frag_CR=23 hash=c011c548ff5d5c8790a5abfcb8c271857d9e4233cddf8a952aa1740935e2cbba
    C exit=0 frag_CR=23 hash=c011c548ff5d5c8790a5abfcb8c271857d9e4233cddf8a952aa1740935e2cbba
    restored_frag_CR=0

`cmp` of whole packs, not only the header, gives `cmp_preA_postA=0`, `cmp_A_B=0` and `cmp_A_C=0`.
P3 held: B and C moved to A, and A did not move.

## 4. Mutation table

Run by `mutate.sh` at `cb64e973`. Each row seds the production line, saves the `git diff`,
empties the results directory, runs `ShaderAssetTest` with `--no-build-cache`, and restores the
file. The predictions were frozen in `predictions.md` first. Both rows matched them exactly,
including which case fails first.

**M-REVERT**, the fix removed (`mut-REVERT.diff`):

```diff
@@ -128,7 +128,7 @@ internal object ShaderSources {
         val file = assetRoot.resolve(path.value)
         if (!file.isRegularFile()) return NOTHING
         return try {
-            Read(UdeaDeclarationScanner.normalizeLineEndings(file.readText()), failure = null)
+            Read(file.readText(), failure = null)
         } catch (failure: IOException) {
             Read("", "${failure::class.simpleName}: ${failure.message}")
         }
```

11 tests, 2 failures (in-XML timestamp `2026-09-21T05:31:28.530Z`). The endings test fails with
`crlf: the asset graph hash moved`. The lone-CR test fails with a message saying `at line 1`.

**M-CRLF_ONLY**, the real shape of a half fix, the one `AssetTreeMigration.readTextNormalized`
uses (`mut-CRLF_ONLY.diff`):

```diff
@@ -128,7 +128,7 @@ internal object ShaderSources {
         val file = assetRoot.resolve(path.value)
         if (!file.isRegularFile()) return NOTHING
         return try {
-            Read(UdeaDeclarationScanner.normalizeLineEndings(file.readText()), failure = null)
+            Read(file.readText().replace("\r\n", "\n"), failure = null)
         } catch (failure: IOException) {
             Read("", "${failure::class.simpleName}: ${failure.message}")
         }
```

11 tests, 2 failures (in-XML timestamp `2026-09-21T05:31:47.007Z`). The endings test now fails
with `cr: the asset graph hash moved`, because LF and CRLF agree. The lone-CR test again says
`at line 1`.

Both rows restored clean (`restored_clean=yes` in `mutate.marker`).

## 5. `sh gradlew build`

Run at `cb64e973` on a clean tree, from `run.sh` (`build.marker`):

    start=2026-09-21T07:04:49Z worktree=/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a540016436e369d0a args=build --continue --no-daemon --max-workers=3
    EXIT=0
    end=2026-09-21T07:09:17Z
    DONE

The tail of `build.log`:

    BUILD SUCCESSFUL in 4m 27s
    1113 actionable tasks: 401 executed, 3 from cache, 709 up-to-date
    Configuration cache entry reused.

The three tasks from cache are all `extractAndroidMainAnnotations`; none is a test. Most of the
up-to-date tasks were already up to date because of an earlier full build at the same commit. I
killed that one at 06:54Z on the coordinator's order, when three builds were thrashing the box; it
is kept as `build-killed.log` and `build-killed.marker`, and no verdict is drawn from it. The
tests this ticket depends on executed in this run, and their in-XML timestamps fall inside its
window. From `build-xml.txt`, `grep -ho` over the four XML files:

    testsuite name="dev.wildware.moba.replay.MobaReplayEqualityTest" tests="9" skipped="0" failures="0" errors="0" timestamp="2026-09-21T07:08:53.589Z"
    testsuite name="dev.wildware.moba.replay.MobaReplayFixturesCurrentTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-21T07:09:01.993Z"
    testsuite name="dev.wildware.moba.level.TestLevelRosterTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-09-21T07:08:53Z"
    testsuite name="dev.wildware.udea.assets.compiler.validate.ShaderAssetTest" tests="11" skipped="0" failures="0" errors="0" timestamp="2026-09-21T07:05:50.358Z"

`git status --short --untracked-files=all` printed nothing after the build.

GL: this ticket touches no GL module (only `udea-assets-compiler`, plus one KDoc line in
`udea-assets`), so there was no xvfb run.

## 6. Windows CI: run 35570488258 on `cb64e973`

Job conclusions at completion (`ci-run1-jobs.txt`):

    build-logic tests: success
    determinism (ubuntu-latest, temurin): success
    determinism (windows-latest, corretto): success
    iOS simulator tests: success
    build (windows-latest): failure
    agent brief matches the tree: success
    build with the K2 plugin disabled: success
    clean build under budget: success
    game-bridge-mcp conformance: success
    latency budgets (ubuntu-latest): success
    a game outside this repository: success
    build (ubuntu-latest): success
    KSP stays incremental: success
    gl tests (xvfb): success
    the FIR checkers fail a real build: success
    replay-equality (windows-latest, temurin): failure
    determinism (windows-latest, temurin): success
    determinism (ubuntu-latest, corretto): success
    replay-equality (ubuntu-latest, corretto): success
    latency budgets (windows-latest): success
    replay-equality (ubuntu-latest, temurin): success
    kotlin upgrade probe (non-blocking): skipped
    replay-equality-nightly (${{ matrix.os }}, ${{ matrix.distribution }}): skipped
    replay-equality-nightly (join): skipped
    replay-equality (join): skipped

The Windows jobs:
- **Green:** `determinism (windows-latest, temurin)`, `determinism (windows-latest, corretto)`
  and `latency budgets (windows-latest)`.
- **`replay-equality (windows-latest, temurin)`: red on X alone.** The job log's refusal line
  (`ci-replay-win.log`, job 106240910533):

      2026-09-21T06:57:42.7704440Z   assetGraphHash: recorded c011c548ff5d5c87... (32 bytes), this build 380c5f8cbbe13228... (32 bytes)

  On master `4a4a2df5` the same job built `c9199709...` (`ci-master-replay-win.log`, run
  35564021364, job 106223070072). The fix changed the Windows value, so it removed the CRLF
  cause; what remains is X.
- **`build (windows-latest)`: red, on neither X nor this change, in its first attempt** (job
  106240910351). The Windows build does not pass `--continue`, so it stopped at the first failing
  task, `:udea-net:allTests`, with
  `WebSocketWasmClientTest.aWasmClientReplicatesSnapshotsFromAJvmServer[wasmJs, node] FAILED`, an
  `AssertionError` in a WebSocket client test (`ci-build-win.log`). It never reached moba's tests.
  This branch changes nothing `udea-net` runs: its only change outside `udea-assets-compiler` is
  a KDoc line. Master's own `build (windows-latest)` on `4a4a2df5` also stopped early, at a
  different test (`UdeaAgentPluginTest > a release build generates a flag that refuses to bind()
  FAILED`, `ci-master-build-win.log`).
- **Re-run of `build (windows-latest)`: still running** at the time of writing (job
  106255873165, started about 07:21Z). The prediction, frozen in `predictions.md` before the
  re-run: either it hits a flake again before moba, or it fails at `:moba:desktop:test` on X
  alone (`MobaReplayFixturesCurrentTest` and `MobaReplayEqualityTest`'s "gate fixture is
  regenerable", recorded `c011c548...` against built `380c5f8c...`), with nothing in
  `udea-assets-compiler` failing. The reviewer can read the result from the run.

## 7. X: why Windows still packs a different hash

Found with a throwaway branch, `dev-windows-probe` (probe run 35570884899, now deleted). It
packed moba at `cb64e973` on `ubuntu-latest` and `windows-latest` and uploaded what the asset
build wrote (`probe/`).

- **The packs differ in 69 bytes.** Both are 114753 bytes long. 32 of the differing bytes are
  the header hash. The other 37 fall between offsets 38457 and 44100, in runs of one or two
  bytes, always the low-order bytes of a 4-byte little-endian value, which is the shape of a float
  off in its last bits. I did not map each offset to a field; that these are the model's node
  values is inferred from the `.glb` diff below. The Linux CI pack is byte-identical to my local
  one (sha256 `a170c515...`). The start of `probe/packdiff.txt`:

      len 114753 114753 differing bytes 69
      in header (magic+version+flags+hash) 32 in body 37
      run at 38457 len 1 linux 000004347a02bd windows 000004327a02bd

- **The source is `Human.fbx`'s conversion to `.glb`.** The converted `Human.glb` is 1068752
  bytes on Linux and 1068760 on Windows. Node rotations and accessor bounds differ in their last
  bits, and so do 614 bytes of the binary buffer. The two generator strings
  (`probe/glbdiff.txt`):

      == asset: {"version": "2.0", "generator": "Open Asset Import Library (assimp v5.4.0)"}  |  {"version": "2.0", "generator": "Open Asset Import Library (assimp v5.4.90d2a697)"}

- **These are two native builds.** Both natives come from LWJGL 3.3.6's `lwjgl-assimp`, and both
  carry the same version format (`natives-strings.txt`, from `strings -a` of the linux `.so`
  and then the windows `.dll`):

      Open Asset Import Library (assimp v%d.%d.%x)
      Open Asset Import Library (assimp v%d.%d.%x)

  The last field is the git revision the native was built from: the `.so` fills it with `0` and
  the `.dll` with `90d2a697`. So the difference is in the values Assimp computes, not in node
  order or float encoding. A canonical order would change nothing, and rounding could not be
  shown to be bounded, because the gap grows as transforms compose. Windows players also get a
  very slightly different Human model, not only a different hash.

This is the coordinator's "cannot be contained" case. The lead is recording X and the options on
the existing ticket.

## 8. Images

None. Nothing visible changes: the packed shader text on Linux is byte-identical before and after
(section 3), so a screenshot would show the same frame.

## 9. Criterion by criterion

The ticket, from the coordinator's messages:
- *Line endings do not affect the asset hash (CRLF and lone CR to LF, at read time).*
  `ShaderAssetTest`'s endings test covers LF, CRLF, CR and mixed files (section 1); arms B and C
  equal A after the fix (section 3).
- *Look for any other text asset read into the hash the same way, and fix it too.* Section 2's
  sweep, with the script probe: there is none to fix.
- *Re-measure A, B and C on the new master before writing code, and freeze B == C == A after the
  fix.* Section 3: the arms ran from 05:21:08Z (`pre.marker`) to 05:24:06Z (`pre-C.udeapak`
  written), and the fix was committed at 05:27:45Z (`cb64e973`).
- *On Linux the fix moves no generated file.* Section 10.
- *Keep arm C, and have a test that goes red on Linux with the fix reverted.* Section 3 (arm C),
  and M-REVERT in section 4.
- *Full build with `--no-daemon --max-workers=3`.* Section 5.
- *Push the branch and read a Windows CI run.* Section 6; the run is complete except the one
  re-run job.
- *Normalise at read time, not only with `.gitattributes`.* Section 2, decisions.
- *Windows replay green.* **Not met**, because of X (section 7). The coordinator decided to
  merge the CRLF fix alone.

## 10. Regenerated files

None, as predicted (P4 in `predictions.md`). The packed text was already LF on Linux, so the fix
moves no generated file: no `.udearep` fixture, `net-protocol.lock`, `net-components.lock`,
`expected-generated-hashes.txt` or `test_level.roster.txt` changed. The full build left the tree
clean (section 5), and `MobaReplayFixturesCurrentTest` and `TestLevelRosterTest` passed in it.

All the artefacts named here are in
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/dev-windows/`.
