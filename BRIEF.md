# BRIEF — issue #152, cross-OS replay equality with field-level divergence

    2d4d2c8

That is the code under review. This brief is the commit sitting on top of it, so
`git rev-parse --short HEAD` on `issue-152-replay-equality-ci` is one ahead of it; every
transcript below was produced against `2d4d2c8`.

Branch: `issue-152-replay-equality-ci`, from `origin/example` at `866ba0a`.
Worktree: `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a6cc34edc8f68a0fb`

---

## 1. The evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew :udea-replay:udeaReplayEqualityProof
```

Five processes: three `DriftDigestMain` runs that each replay the checked-in 3600-tick
`.udearep` and write a `.udeaeq` digest, and two `ReplayEqualsMain` runs that join them. Two of
the three legs are honest and must agree; the third carries a deliberate one-ulp divergence and
must be caught, at the right tick, naming the right entity, component and field.

Leaves behind `udea-replay/build/reports/udea/replay-equality/proof/` — three `.udeaeq` streams
(1.6MB each) and the two rendered verdicts, `equal.txt` and `planted.txt`.

### Its real output

Spliced from `build/evidence/proof.log`, the run of that exact command at `2d4d2c8`:

```
=== two honest legs, two separate JVM processes ===
replay-equality over 2 leg(s) of 'drift-3600.udearep', 3600 tick(s) from t0
  proof/leg-a  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20]
  proof/leg-b  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20]

replay equality holds: 3600 tick(s) of 'drift-3600.udearep' are cell-for-cell identical
  fixture drift-3600.udearep
  A = 'proof/leg-a'  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20]
  B = 'proof/leg-b'  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20]
=== a third leg carrying a planted one-ulp divergence ===
replay-equality over 2 leg(s) of 'drift-3600.udearep', 3600 tick(s) from t0
  proof/leg-a  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20]
  proof/leg-planted  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20]

replay equality FAILED at t1200 (1200 tick(s) matched first)
  fixture drift-3600.udearep
  A = 'proof/leg-a'  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20]
  B = 'proof/leg-planted'  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20]
  world hash: -7000854319554458987 against 8365181117672703832
  1 differing cell(s):
    NetId(#0@0) dev.wildware.udea.replay.equality.fixture.Drifter.x
      A = 6.5650873 (0x40d21532)
      B = 6.565088 (0x40d21533)
      the preceding 5 tick(s) of this cell:
        t1195  agreed  A = 7.066144 (0x40e21dda), B = 7.066144 (0x40e21dda)
        t1196  agreed  A = 6.9657426 (0x40dee75d), B = 6.9657426 (0x40dee75d)
        t1197  agreed  A = 6.8644977 (0x40dba9f7), B = 6.8644977 (0x40dba9f7)
        t1198  agreed  A = 6.763905 (0x40d871e9), B = 6.763905 (0x40d871e9)
        t1199  agreed  A = 6.6647997 (0x40d5460a), B = 6.6647997 (0x40d5460a)
replay-equality proof PASSED: two honest legs agree (exit 0); the planted leg fails (exit 1) naming Drifter.x at t1200, with five ticks of history.
```

`0x40d21532` and `0x40d21533` are adjacent representable floats — the difference is one bit of
the significand, which is the magnitude `determinism-audit.md` §3.1 measured `Math.sin` differing
from `StrictMath.sin` by.

### Proof it goes red when the feature is reverted

Two reverts, both run, both from `build/evidence/`.

**(a) Neutralise the comparison itself** — `ReplayEquality.ticksAgree` always says "agree", which
is the whole feature gone:

```diff
diff --git a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt
index 8174698..621fca8 100644
--- a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt
+++ b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt
@@ -234,7 +234,7 @@ public object ReplayEquality {
         // `ReplayDigestWriter` can produce, so meeting it means a file has been truncated, edited
         // or written by something else, and the caller is told that rather than handed a "they
         // agree" that is built on a stream nobody should trust.
-        if (expected.hashAt(index) != actual.hashAt(index)) return false
+        if (true) return true
         val mine = expected.cellsOf(index)
         val theirs = actual.cellsOf(index)
         if (mine.last - mine.first != theirs.last - theirs.first) return false
```

`build/evidence/proof-feature-reverted.log`, exit 1:

```
* What went wrong:
Execution failed for task ':udea-replay:udeaReplayEqualityProof'.
> a leg with a deliberately planted one-ulp divergence was NOT caught (exit 0). A gate that cannot fail proves nothing.
```

**(b) Neutralise the plant** — `DriftWorld.plant` writes the value back unchanged, so the third
leg is no longer a third answer. Same task, `build/evidence/proof-reverted.log`, exit 1, same
message. That second one matters separately: it shows the proof is not merely asserting that
three files differ, it is asserting that *the planted difference* is the one that was found.

---

## 2. What I did, what I decided, what I rejected

### The mechanism

Each matrix leg replays a checked-in `.udearep` headless and writes a `.udeaeq` **digest stream**:
per tick, every value `WorldHasher.hash(WorldSnapshot)` folds, each one keyed by
`(scope, NetId, ComponentTypeId, fieldIndex)`. A join step reads two or more of those files and
reports the first tick they disagree at, cell by cell.

The load-bearing property is in `ReplayDigestWriter.writeTick`: it **refolds its own cells through
`WorldHasher.fold` and refuses to write a tick that does not reproduce `WorldHasher.hash`**. The
cells are therefore provably the hash's own inputs, on every tick of every run. That is what lets
the join step promise something a hash stream cannot — if two legs' hashes differ, some *named*
cell differs, so there is always something to print. A run that diverged only in its random
streams reports `<rng>.word[0]`; only in the id allocator, `<handles>.nextFresh`; only in which
entity carries a component, `<roster>.presence[0]`. Those four pseudo-components exist precisely
because `WorldHasher` folds the clock, the RNG, the allocator and the roster shape, and not one of
them is a field of anything.

It also fails loudly the day `WorldHasher.hash` grows a folded input nobody added a cell for:
without that check the gate would keep passing while silently ceasing to cover the new state.

### Why the logic is in classes and tasks rather than in `ci.yml`

I cannot run GitHub Actions here and neither can the reviewer, so anything expressed only in YAML
is unverifiable until it has already gone wrong on a branch somebody merged. Every decision the
job makes — what to replay, what to write down, what counts as a divergence, how to render one,
which exit code to use — is a class with a `main` and a Gradle task that drives it. The workflow
is `./gradlew` lines and artifact plumbing over the top.

**Proven locally:** the replay, the digest format and its round trip, the comparison, the
rendering, the exit codes, the artifact sizes, the per-leg runtime, and both halves of the verdict.
**Resting on the YAML being correct:** that `ubuntu-latest` and `windows-latest` runners exist and
green, that `actions/upload-artifact` / `download-artifact` move the `.udeaeq` files between the
legs and the join job, and that `$GITHUB_STEP_SUMMARY` renders. `ReplayEqualityProofTest` asserts
the workflow delegates to the two tasks rather than reimplementing the comparison in shell, which
is the most a local test can say about a file it cannot execute.

### Decisions

**1. The fixture is `udea-replay`'s own world, not `moba`.** Commented on the issue
([#152 comment](https://github.com/wildware-uk/Udea/issues/152#issuecomment-5479780077)).
`BuildIdentity` refuses a recording whose `protoHash`, `assetGraphHash` or `inputSchemaHash` has
moved; #132 is in flight on `moba/` and `udea-gas/` this wave and will regenerate
`net-protocol.lock`, so a `moba` fixture checked in today is refused the day that merges — and the
tool for regenerating fixtures is #165, which I am not building. Landing a fixture whose
maintenance tool does not exist yet is landing a red gate. *Rejected:* checking in a `moba` 5v5
recording and registering the task in `moba/build.gradle.kts`. *Cost, stated plainly:* the job
gates the engine's snapshot and float paths, not `moba`'s gameplay float paths. *To change it:*
the machinery is generic over `ReplayWorld` and `MobaReplayWorld` already implements everything it
needs, so wiring `moba` in after #165 is one task registration plus a checked-in `.udearep`; add
it as a **second** fixture rather than replacing this one, because only a small world can be
perturbed by exactly one ulp at exactly one tick.

**2. Two `udea-core` declarations widen: `WorldHasher.fold` and `WorldHasher.OFFSET_BASIS`
(private → public), and `ColumnarFieldStore.hashableBits` (internal → public).** A digest is only
worth anything if its cells are provably the same values the hash folded, and the only way to
prove that is to fold them and get the hash back. *Rejected:* a second FNV-1a and a second float
canonicalisation written out in `udea-replay` — two implementations of a determinism gate's own
arithmetic that agree until somebody edits one. Both widenings carry a KDoc section saying why.
No frozen contract is touched: `docs/contracts/` is unchanged, and `WorldHasher`'s canonical
order is unchanged.

**3. The digest does not gate on the recording's own hash stream.** A `.udearep` carries one hash
per tick, produced by whichever machine recorded it. Gating on those would make the recording
machine the authority and report a genuine cross-platform float difference as the *other*
platform's fault, in the wrong job. `ReplayDigestRecorder` counts the mismatches and prints them
as a note; the verdict comes from comparing two legs against each other, which is symmetric.
`ReplayEqualityProofTest` documents the same reasoning for why it compares the regenerated fixture
input-for-input and not hash-for-hash.

**4. `-Pudea.replay.jvmVendor` is passed explicitly in CI.** `setup-java` sets `JAVA_HOME`, but
Gradle toolchain auto-detection also finds whatever JDKs the runner image ships, so two legs
asking only for "17" can both resolve to the same vendor and the second axis silently stops
existing while both stay green. Naming the vendor makes a missing one a loud failure. The digest
header records the vendor that actually ran and the join step prints it, so the claim is
checkable in the log rather than assumed from the YAML.

**5. The golden normalises the OS, the JVM and a float's *decimal*, but not its bits.**
`Float.toString` changed algorithm in JDK 19, so the same `Float` renders as different text on
either side of that release; the hexadecimal raw bits beside it are the value itself and are
pinned. The two world hashes and the tick are pinned too, because the fixture world uses only
exactly-specified arithmetic and is meant to produce the same numbers everywhere.

### What the issue left open, and what I ruled

- **"and is required for merge"** (criterion 1) is a GitHub branch-protection setting on the
  repository. Nobody in this session can set it. The job exists and is named
  `replay-equality (<os>, <distribution>)` plus `replay-equality (join)`; marking those required
  is an owner action.
- **"green for seven consecutive nights"** (criterion 5) is #165's nightly and is unachievable
  inside a ticket by construction. Not built, not faked.
- **The 36000-tick nightly fixture, `--update-replay-fixtures`, and the `replay.bisect` job-summary
  link** are #165 and are out of scope by the lead's split.
- **`udeaWriteReplayFixture`** *is* in scope and is not that flag: it is the one command that
  produced the checked-in bytes. Without it the fixture is a binary nobody can reproduce, which is
  worse than the regeneration risk. Nothing depends on it and nothing in CI runs it.

---

## 3. `sh gradlew build`

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build
```

`build/evidence/full-build-final.log`, exit 0:

```
BUILD SUCCESSFUL in 20s
204 actionable tasks: 5 executed, 2 from cache, 197 up-to-date
Configuration cache entry reused.
```

Mostly up-to-date because the same tasks had already executed green in earlier runs on this
branch; Gradle marks a task up-to-date only when its inputs and outputs are unchanged, so that is
a correct statement about the whole build at this SHA. The two that matter most were forced to
execute rather than taken up-to-date, below.

Across every `build/test-results/**/*.xml` in the tree after that run: **2446 test cases, 0
failures, 34 skipped.** (Counted by parsing every `testsuite` XML present, which includes the
budget tasks' own results; that is a different aggregation from the 2447 recorded at `8035374`,
so the two numbers are not directly comparable and I am not claiming they are. `:udea-replay` went from 27 to 53: `CrossPlatformDivergenceTest` 4, `DivergenceReportFormatTest` 9,
`ReplayDigestTest` 8, `ReplayEqualityProofTest` 5, on top of the existing `ReplayEngineTest` 7,
`ReplayFormatTest` 12 and `ReplayToolTest` 8.)

The 34 skips break down as **25 GL** — `udeaGlTest` and `udeaAgentGlTest` running under `check`
with no `DISPLAY`, which is the documented behaviour and is why §4 runs them again under xvfb —
and **9 in `:udea-assets-compiler:udeaPackGate`** (`AtlasPackerTest` 7, `ReproducibilityTest` 2),
which are pre-existing on `origin/example` and untouched by this branch. I checked rather than
assumed: the first draft of this sentence attributed all 34 to GL and was wrong.

### `:udea-assets-compiler:udeaDaemonBudget` — what I saw, and the solo run

The **first** full-build attempt failed, on that task only, at load ≈20 with melon-merge running
a scenario suite. `build/evidence/full-build.log` and its test XML:

```
 - a warm reload of one script decides inside the edit-to-observe budget()
    org.opentest4j.AssertionFailedError: the reload decision is the compile half of the under-3s edit-to-observe loop; median was 1118ms [1118, 895, 902, 1286]
 - a warm validate of one edited script is under 300ms()
    org.opentest4j.AssertionFailedError: spec 6 gates warm validate at 300ms; median was 632ms [70, 552, 632, 735]
```

Re-run alone, `build/evidence/daemon-budget-solo2.log`, exit 0:

```
    warm reload decision: median 358ms over 4 samples [443, 358, 354, 316]
    warm validate of one script: median 217ms over 4 samples [24, 215, 217, 236]
```

That is the box, not this branch: the branch touches nothing `:udea-assets-compiler` compiles or
runs. Load at the solo run was 24.05 and it still passed with three times the headroom.

### Gates outside `check`, run by name

```
JAVA_HOME=... sh gradlew udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd udeaVerifyMigration udeaLegacyReport
BUILD SUCCESSFUL in 10s
```

`udeaVerifyDeterminism`, forced to execute rather than taken up-to-date
(`build/evidence/determinism.log`, exit 0), and its report:

```
udeaVerifyDeterminism
  scanned :udea-core: 223 class files
  scanned :udea-gas: 115 class files
  scanned :udea-net: 179 class files
  scanned :moba: 318 class files
  allowlist entries used: 0
  findings: 0
```

`:moba:runUdpProof` is **red on `origin/example` and was not touched by this branch** — see
`HANDOFF.md`. I did not run it and I am not claiming anything about it.

---

## 4. GL

**This ticket touches no GL.** `udea-replay` is a designated headless module, and the only
`udea-core` edits are two visibility widenings in the snapshot spine. I ran the GL tests for real
anyway, so the omission cannot be a finding:

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  env JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true
```

`build/evidence/gl.log`, exit 0:

```
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest

BUILD SUCCESSFUL in 9s
```

From the XML, preserved in `build/evidence/gl-counts.txt` because a later `build` run overwrites
those result files with the skipping `check` variant:

```
udeaGlTest: 18 tests, 0 failures, 0 skipped
udeaAgentGlTest: 8 tests, 0 failures, 0 skipped
```

The zero skips are the point — under `check` with no `DISPLAY`, 25 of those 26 skip and report
green having checked nothing.

---

## 5. The two-JVM axis, run for real on this box

Criterion 4 asks for a second JVM. CI runs Temurin 17 and Corretto 17, which I cannot observe from
here — but this box has three JDKs from two vendors, so I ran the axis locally instead of asserting
it. Three digests, three separate JVMs, then one join:

```
sh gradlew :udea-replay:udeaReplayDigest -Pudea.replay.label=local/adoptium-17 -Pudea.replay.jvm=17 -Pudea.replay.jvmVendor=Adoptium -Pudea.replay.out=.../jvmaxis/adoptium-17.udeaeq
sh gradlew :udea-replay:udeaReplayDigest -Pudea.replay.label=local/adoptium-21 -Pudea.replay.jvm=21 -Pudea.replay.jvmVendor=Adoptium -Pudea.replay.out=.../jvmaxis/adoptium-21.udeaeq
sh gradlew :udea-replay:udeaReplayDigest -Pudea.replay.label=local/graalvm-21  -Pudea.replay.jvm=21 -Pudea.replay.jvmVendor=GraalVM  -Pudea.replay.out=.../jvmaxis/graalvm-21.udeaeq
sh gradlew :udea-replay:udeaReplayEquals -Pudea.replay.streams=.../jvmaxis
```

`build/evidence/jvm-axis-summary.txt`:

```
replay-equality over 3 leg(s) of 'drift-3600.udearep', 3600 tick(s) from t0
  local/adoptium-17  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20]
  local/adoptium-21  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 21.0.11]
  local/graalvm-21  [Linux amd64; GraalVM Community OpenJDK 64-Bit Server VM 21.0.2]

replay equality holds: 3600 tick(s) of 'drift-3600.udearep' are cell-for-cell identical
  fixture drift-3600.udearep
  A = 'local/adoptium-17'  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20]
  B = 'local/adoptium-21'  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 21.0.11]

replay equality holds: 3600 tick(s) of 'drift-3600.udearep' are cell-for-cell identical
  fixture drift-3600.udearep
  A = 'local/adoptium-17'  [Linux amd64; Eclipse Adoptium OpenJDK 64-Bit Server VM 17.0.20]
  B = 'local/graalvm-21'  [Linux amd64; GraalVM Community OpenJDK 64-Bit Server VM 21.0.2]
```

Two vendors, three JVMs, 3600 ticks, cell-for-cell identical. The headers are not decoration — the
join step printed the vendor each leg actually ran on, which is how I know the axis was real and
not three runs of the same JDK.

**What this does not say:** nothing about Windows. The OS axis genuinely cannot be observed from
this box, and I have not implied otherwise anywhere in this brief.

Per-leg replay time, from the `--timing` files the task writes:
`adoptium-17` 483ms, `adoptium-21` 527ms, `graalvm-21` 441ms, for 3600 ticks each.

---

## 6. Timing, against criterion 6

The criterion asks for the added PR CI wall time to be under 4 minutes, measured and printed in
the job summary.

- **Measured here:** the replay itself is 441–527ms per leg and the join is under a second. The
  digest artifact is 1.6MB gzipped per leg, so upload and download are seconds.
- **Printed in the job summary:** each leg starts a clock in its first step and, in an
  `if: always()` step, appends `| <os> / <distribution>-17 | <n>s |` to `$GITHUB_STEP_SUMMARY`
  and emits a `::warning::` over 240s. That is whole-job seconds — checkout, JDK setup and the
  Gradle build included — because those are wall time a pull request pays for too.
- **What I cannot do:** measure a GitHub-hosted runner from this box. The dominant term is
  `setup-java` plus Gradle configuration, not the replay, and I will not guess at it. The number
  will be in the first run's summary.

---

## 7. Images

Both in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`. A determinism gate has nothing to
photograph — the artefact *is* text — so these are the tool's own output rendered, byte for byte,
by `build/evidence/render-verdict.py` from the summary files the tasks wrote. Neither composes a
line of its own beyond the title.

- **`issue152-jvm-axis-equal.png`** — the three-JVM join above. Shows two vendors and three JVM
  versions producing cell-for-cell identical 3600-tick streams. Proves the second axis of
  criterion 4 is a real second JVM and that the fixture world is genuinely portable across JVM
  implementations, which is the property the whole gate rests on.
- **`issue152-planted-divergence.png`** — the planted verdict from the evidence command. Shows the
  failure naming the tick, the `NetId`, the component FQN, the field, both values with their raw
  bits, and five preceding ticks that agreed. Proves criterion 2's rendering, and shows what a red
  CI job will actually print.

---

## 8. The issue, criterion by criterion

| # | Criterion | Proved by | Verdict |
|---|---|---|---|
| 1 | The `replay-equality` job is green on `ubuntu-latest` and `windows-latest` **and is required for merge** | The job exists (`ci.yml`, `replay-equality` + `replay-equality-join`); its Gradle tasks are proven locally in §1 and §5; `ReplayEqualityProofTest` asserts the workflow invokes them | **Partly.** Green-on-two-OSes can only be observed in CI, by design of the thing being built. "Required for merge" is a repository branch-protection setting nobody in this session can apply — owner action, not faked |
| 2 | `CrossPlatformDivergenceTest`: a planted float-sensitive divergence behind a test flag fails the job, the log names tick / `NetId` / component / field, verified against a checked-in expected-output fixture | `udea-replay/src/test/.../CrossPlatformDivergenceTest.kt` (4 tests) against `src/test/resources/expected/planted-divergence.txt`; plus §1's five-process run, which asserts the same four strings on the real exit codes | **Met** |
| 3 | `DivergenceReportFormatTest` asserts no failure path can emit a bare "hash mismatch at tick N" without a field | `DivergenceReportFormatTest.kt`. What its cases have in common: each drives the comparison to a state where the *only* difference is one that is not a component field, and asserts the rendering names it — one per pseudo-component (`<rng>.word[0]`, `<handles>.nextFresh`, `<clock>.tick`, `<roster>.presence[0]`), plus a component field for contrast, a forged stream whose hash disagrees with its own cells (refused as corrupt, never rendered as a divergence), a forged stream that *shares* a hash while its cells differ (caught by the cell walk), a direct assertion that the renderer throws rather than printing a hash when handed a non-equal verdict with no cell, and a control asserting an equal verdict reads as equal and contains no failure text | **Met**, and structurally: `ReplayDigestWriter` refolds its cells into the hash on every tick, so a differing hash implies a differing named cell by construction (`ReplayDigestTest`, with a per-cell negative control) |
| 4 | The two-JVM axis runs and is green, defined in the same workflow file as the Phase 0 matrix | The third matrix leg (`ubuntu-latest` / `corretto`) in `ci.yml`, the same file as the Phase 0 `build` matrix; and §5, where three JVMs from two vendors agree cell-for-cell on this box | **Met locally**, on three JVMs rather than two. The CI legs themselves are observable only in CI |
| 5 | The nightly ten-minute 5v5 replay-equality run is green for seven consecutive nights | — | **Not this ticket.** #165 owns the 36000-tick nightly; seven nights is unachievable inside a ticket by construction. Stated, not faked |
| 6 | Added PR CI wall time <4 minutes, measured and printed in the job summary | The per-leg clock steps in `ci.yml` and the `::warning::` over 240s; measured replay time in §6 | **Printed.** The number itself can only be measured on a hosted runner |

Three scope bullets — the 36000-tick nightly fixture, `--update-replay-fixtures`, and the
`replay.bisect` job-summary link — are #165 by the lead's split and are deliberately absent.

---

## 9. Regenerated files

**None.** `udea-codegen/net-protocol.lock` and
`udea-codegen/src/test/resources/expected-generated-hashes.txt` are **untouched**, and that is
deliberate rather than lucky: the fixture world's two components carry no `@Replicated` annotation
and their `Replicator`s are hand-written, exactly as `TransformReplicator` and
`SnapshotComponents` are. No component id moved, so no id moved for anything after it.

```
$ git diff --stat 866ba0a..HEAD -- udea-codegen/
(no output)
```

One new binary is checked in: `udea-replay/src/testFixtures/resources/fixtures/drift-3600.udearep`,
66,413 bytes, regenerable with `sh gradlew :udea-replay:udeaWriteReplayFixture`.
`ReplayEqualityProofTest` asserts the checked-in file matches what the generator produces, sample
for sample across all 3600 ticks, so it cannot rot into an unreproducible blob.

---

## 10. Self-review against the reject list

Read my own diff against §8 of `docs/engineering-standards.md` and the `AGENTS.md` do-not list
before reporting. Two things it caught, both fixed in `2d4d2c8`:

- **Copy-pasted logic differing only in a constant.** The digest's writer and reader each had a
  private parallel-array accumulator differing in three methods over identical storage and an
  identical `grow()`. Folded into one `CellBuffer` with the initial capacity as a parameter.
- **`public` declarations nobody uses.** `ReplayDigestWriter.tickCount` deleted; `describeJvm` and
  `describeOs` made private; `DigestCellKey` now carries an assertion rather than only being
  rendered.

The rest of the list, checked and clear:

- **No wall clock or unseeded randomness in simulation.** `System.nanoTime` appears once, in
  `ReplayDigestRecorder`, timing a build task and never entering a world. `java.util.Random`
  appears once, in `DriftFixtureRecorder`, authoring a recording offline — seeded, and specified
  by the JDK as an LCG so it reproduces on any machine. Simulation randomness is `RngService` and
  its named streams. `udeaVerifyDeterminism` is green with 0 findings over 835 class files.
- **No frozen contract changed.** `docs/contracts/` is untouched. The
  `fieldNames[i]` == `FieldMask` bit *i* == `FieldStore` index *i* alignment is what the digest's
  keys and the component table are built on, and `ComponentSchema.of` checks it at construction
  for both fixture components.
- **No `Tick` expressed as seconds or milliseconds.** The one millisecond figure is
  `ReplayDigestRun.elapsedMillis`, a build measurement of a Gradle task, KDoc'd as such.
- **No new `common` dependency, no GL outside `udea-render`, no presentation system as a Fleks
  system, no module arrow pointing upward.** `udeaVerifyModuleGraph` and
  `udeaVerifyNoLegacyDependencies` green.
- **No `AGENTS.md` staleness.** No module moved; `udeaVerifyAgentsMd` green.
- **No `TODO()`, stubbed return or swallowed exception.** `ReplayEqualsMain` catches exactly two
  exception types and turns each into a distinct exit code and a printed reason.
- **Self-describing format.** Every cell carries its own key rather than relying on both sides
  walking in the same order, and the file is magic-, version- and length-prefixed — the
  `PacketUtil.kt:122` smell §1 names.

### What I did not exercise

- **Windows.** Cannot be run here. Everything about the OS axis rests on the YAML.
- **A `moba` world.** By decision, above. The gate covers the engine's snapshot and float paths.
- **A digest with more than 25 differing cells.** The `MAX_REPORTED` cap and the
  "... and N more" line are inherited from `DivergenceReport` and rendered by the same branch, but
  no test drives a world that far apart.
- **A stream from a *different* build of the same game.** `incomparabilitiesAgainst` covers the
  fixture, game, version, tick range and component table, and the fixture and component-table
  cases have tests; the game-version case does not.
- **`ReplayEqualsMain` with more than three legs.** Three is what CI runs.

---

## 11. Mutation table

Nine mutations, each applied on its own, with the literal `git diff` and the failing test names
taken from the JUnit XML (`build/evidence/run-mutations.py`, output in
`build/evidence/mutations.txt`). Names come from the XML rather than a console `grep -c FAILED`,
which would also match `BUILD FAILED` and inflate every count by one.

**Baseline before any mutation: 53 tests, 0 failed.**

One of these had to be earned. M1 originally produced **zero** failures, because the hash check
precedes the cell walk and every honest divergence moves both — so the cell walk could have been
deleted with nothing going red. Rather than describe that away, I added
`two runs that share a hash but not their cells are still caught, and named`, which forges a
stream whose recorded hash is the honest one's while its `x` is not. M1 now fails.

### M1 comparison ignores the cells
```diff
diff --git a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt
index f4c704e..d45f3c0 100644
--- a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt
+++ b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt
@@ -241,7 +241,6 @@ public object ReplayEquality {
         var a = mine.first
         var b = theirs.first
         while (a <= mine.last) {
-            if (expected.valueAt(a) != actual.valueAt(b)) return false
             if (expected.scopeAt(a) != actual.scopeAt(b)) return false
             if (expected.netIdAt(a) != actual.netIdAt(b)) return false
             if (expected.typeIdAt(a) != actual.typeIdAt(b)) return false
```
53 tests, 1 failed
  DivergenceReportFormatTest > two runs that share a hash but not their cells are still caught, and named()

### M2 the digest omits the RNG cells
```diff
diff --git a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayDigestFormat.kt b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayDigestFormat.kt
index ce56138..09de4d8 100644
--- a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayDigestFormat.kt
+++ b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayDigestFormat.kt
@@ -173,9 +173,6 @@ public class ReplayDigestWriter internal constructor(
         collectComponents(fields, buffer)
 
         buffer.add(DigestScope.Clock, NetId.NONE.raw, NO_TYPE, NO_FIELD, snapshot.tick.value)
-        for (word in snapshot.rng.indices) {
-            buffer.add(DigestScope.Rng, NetId.NONE.raw, NO_TYPE, word, snapshot.rng[word])
-        }
 
         val handles = snapshot.handles
         buffer.add(
```
53 tests, 10 failed
  CrossPlatformDivergenceTest > a planted one-ulp divergence fails the comparison and names tick, entity, component and field()
  CrossPlatformDivergenceTest > the plant is the smallest change a float can carry, not a visible one()
  CrossPlatformDivergenceTest > with the plant off, two runs of the fixture are cell-for-cell identical()
  ReplayDigestTest > a digest survives a round trip through the file()
  ReplayDigestTest > a tick's cells fold back to the world hash()
  ReplayDigestTest > dropping a single cell breaks the fold()
  ReplayDigestTest > the component table carries the fully qualified name and the field kinds()
  ReplayDigestTest > the first tick is the tick that was simulated, not the clock after it()
  ReplayDigestTest > the fixture world really does churn its roster, its presence bits and its free list()
  ReplayDigestTest > two streams of different fixtures refuse to be compared()

### M3 the renderer prints a hash with no cell
```diff
diff --git a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt
index f4c704e..4538f75 100644
--- a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt
+++ b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt
@@ -115,12 +115,6 @@ public class ReplayEqualityResult(
             .append(" (").append(matchingTicks).append(" tick(s) matched first)")
         appendLegs(builder)
         builder.append("\n  world hash: ").append(expectedHash).append(" against ").append(actualHash)
-        check(divergences.isNotEmpty()) {
-            "a divergence at $tick named no cell. That is unreachable by construction - " +
-                "ReplayDigestWriter refolds its cells into the world hash before writing them - " +
-                "and reporting it as a hash mismatch would be exactly the bare hash this gate " +
-                "exists to replace."
-        }
         builder.append("\n  ").append(divergingCells).append(" differing cell(s):")
         for (divergence in divergences) appendDivergence(builder, divergence)
         if (divergingCells > divergences.size) {
```
53 tests, 1 failed
  DivergenceReportFormatTest > a result that carries a tick but no cell refuses to render rather than printing a hash()

### M4 no history is gathered
```diff
diff --git a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt
index f4c704e..d723c72 100644
--- a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt
+++ b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt
@@ -304,7 +304,7 @@ public object ReplayEquality {
     ): CrossRunCellDivergence {
         val component = expected.componentOf(key.typeIdRaw) ?: actual.componentOf(key.typeIdRaw)
         val history = ArrayList<CellHistoryEntry>(ReplayEquality.HISTORY_TICKS)
-        val from = maxOf(0, index - HISTORY_TICKS)
+        val from = index
         for (earlier in from until index) {
             history += CellHistoryEntry(
                 tick = expected.tickAt(earlier),
```
53 tests, 1 failed
  CrossPlatformDivergenceTest > a planted one-ulp divergence fails the comparison and names tick, entity, component and field()

### M5 the report names the component but not the field
```diff
diff --git a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt
index f4c704e..7edb78a 100644
--- a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt
+++ b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt
@@ -382,7 +382,7 @@ public object ReplayEquality {
 
     private fun fieldNameOf(key: DigestCellKey, component: DigestComponentInfo?): String =
         when (key.scope) {
-            DigestScope.Component -> component?.nameOf(key.field) ?: "<field ${key.field}>"
+            DigestScope.Component -> "<field>"
             DigestScope.ComponentType -> "<typeId>"
             DigestScope.ComponentSlots -> "<slotsUsed>"
             DigestScope.RowCount -> "rowCount"
```
53 tests, 3 failed
  CrossPlatformDivergenceTest > a planted one-ulp divergence fails the comparison and names tick, entity, component and field()
  DivergenceReportFormatTest > a component field divergence names the entity, the component FQN and the field()
  DivergenceReportFormatTest > two runs that share a hash but not their cells are still caught, and named()

### M6 the corrupt-stream refusal is silenced
```diff
diff --git a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt
index f4c704e..4fb0008 100644
--- a/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt
+++ b/udea-replay/src/main/kotlin/dev/wildware/udea/replay/equality/ReplayEquality.kt
@@ -234,7 +234,6 @@ public object ReplayEquality {
         // `ReplayDigestWriter` can produce, so meeting it means a file has been truncated, edited
         // or written by something else, and the caller is told that rather than handed a "they
         // agree" that is built on a stream nobody should trust.
-        if (expected.hashAt(index) != actual.hashAt(index)) return false
         val mine = expected.cellsOf(index)
         val theirs = actual.cellsOf(index)
         if (mine.last - mine.first != theirs.last - theirs.first) return false
```
53 tests, 1 failed
  DivergenceReportFormatTest > a stream whose hash disagrees with its own cells is refused, not reported as a divergence()

### M7 the plant is a no-op
```diff
diff --git a/udea-replay/src/testFixtures/kotlin/dev/wildware/udea/replay/equality/fixture/DriftWorld.kt b/udea-replay/src/testFixtures/kotlin/dev/wildware/udea/replay/equality/fixture/DriftWorld.kt
index c9c6c59..34aca90 100644
--- a/udea-replay/src/testFixtures/kotlin/dev/wildware/udea/replay/equality/fixture/DriftWorld.kt
+++ b/udea-replay/src/testFixtures/kotlin/dev/wildware/udea/replay/equality/fixture/DriftWorld.kt
@@ -318,7 +318,7 @@ public class DriftWorld(
         val entity = checkNotNull(netIds.resolveOrNull(leadNetId)) { "$leadNetId is not live" }
         with(fleks) {
             val drifter = entity[Drifter]
-            drifter.x = Math.nextUp(drifter.x)
+            drifter.x = drifter.x
         }
     }
 
```
53 tests, 2 failed
  CrossPlatformDivergenceTest > a planted one-ulp divergence fails the comparison and names tick, entity, component and field()
  CrossPlatformDivergenceTest > the plant is the smallest change a float can carry, not a visible one()

### M8 the fixture world never churns its roster
```diff
diff --git a/udea-replay/src/testFixtures/kotlin/dev/wildware/udea/replay/equality/fixture/DriftWorld.kt b/udea-replay/src/testFixtures/kotlin/dev/wildware/udea/replay/equality/fixture/DriftWorld.kt
index c9c6c59..bd439a3 100644
--- a/udea-replay/src/testFixtures/kotlin/dev/wildware/udea/replay/equality/fixture/DriftWorld.kt
+++ b/udea-replay/src/testFixtures/kotlin/dev/wildware/udea/replay/equality/fixture/DriftWorld.kt
@@ -193,7 +193,7 @@ internal class PopulationSystem(private val netIds: NetIdIndex) : SimSystem() {
             }
             netIds.allocate(entity)
         }
-        if (at % RETIRE_INTERVAL == 0L && drifters.entities.size > MIN_POPULATION) {
+        if (false && at % RETIRE_INTERVAL == 0L && drifters.entities.size > MIN_POPULATION) {
             // The newest, not the oldest: the lead drifter the pilot steers must survive the
             // whole fixture, or the recording stops mattering half way through it.
             val entity = drifters.entities[drifters.entities.size - 1]
```
53 tests, 2 failed
  CrossPlatformDivergenceTest > a planted one-ulp divergence fails the comparison and names tick, entity, component and field()
  ReplayDigestTest > the fixture world really does churn its roster, its presence bits and its free list()


### M9 the stale placeholder is put back in ci.yml
```diff
diff --git a/.github/workflows/ci.yml b/.github/workflows/ci.yml
index 4df3400..a6cd7da 100644
--- a/.github/workflows/ci.yml
+++ b/.github/workflows/ci.yml
@@ -974,7 +974,7 @@ jobs:
     # snapshot-equivalence test and the cross-OS `replay-equality` job at the foot of this file,
     # which issue #152 added on top of `:udea-replay`'s headless replay. A green tick *here* is
     # still not one there: if the two ever disagree, the replay result wins and this scanner
-    # grows a rule.
+    # grows a rule. Until that job exists, THIS FILE CONTAINS NO REPLAY-EQUALITY GATE.
     name: determinism (${{ matrix.os }}, ${{ matrix.distribution }})
     runs-on: ${{ matrix.os }}
     strategy:
```
53 tests, 1 failed
  ReplayEqualityProofTest > the determinism job no longer claims this file has no replay-equality gate()


---

## 12. Where the artefacts are

`build/` is gitignored, so these live only in the worktree. Everything quoted above was spliced
from one of them.

| File | What it is |
|---|---|
| `build/evidence/proof.log` | The evidence command's full run |
| `build/evidence/proof-feature-reverted.log` | The same command with `ReplayEquality` neutralised |
| `build/evidence/proof-reverted.log` | The same command with the plant neutralised |
| `build/evidence/jvm-axis-summary.txt` | The three-JVM join |
| `build/evidence/full-build-final.log` | `sh gradlew build` |
| `build/evidence/full-build.log` | The first attempt, red on `udeaDaemonBudget` at load 20 |
| `build/evidence/daemon-budget-solo2.log` | That task alone, green |
| `build/evidence/gl.log` | `udeaGlTest` + `udeaAgentGlTest` under xvfb |
| `build/evidence/gl-counts.txt` | Their counts, saved because a later `build` overwrites the XML |
| `build/evidence/determinism.log` | `udeaVerifyDeterminism`, forced to execute |
| `build/evidence/verifiers.log` | The module-graph and migration verifiers |
| `build/evidence/test-totals.txt` | The 2446/0/34 count, as counted |
| `build/evidence/mutations.txt` | The table in §11, as generated |
| `build/evidence/run-mutations.py` | What generated it |
| `build/evidence/render-verdict.py` | What rendered the two PNGs |

None of these was written to the shared `/tmp` scratchpad. dev-154 found that the directory the
harness advertises as session-specific is shared per project on this box, so a generic filename
there is a silent cross-branch overwrite; the two files I had put there
(`replay-equality-job.yml`, `commit-msg.txt`) were both consumed at write time and I re-read both
out of their destinations — `yaml.safe_load` over the whole workflow and `git log -1 --format=%B`
— and both came back as mine. Worth one line in the wave handoff.
