f5a7f6d

# #171 — `game-bridge-mcp` conformance has never run

`f5a7f6d` is the change: every file in this ticket's diff, and the commit every number below was
measured against. This brief is the one commit on top of it and touches nothing else — `git log
--oneline -2` and `git show --stat HEAD` show that, so the branch tip and `f5a7f6d` are the same
code.

Branch `issue-171-vendored-client-hashes`, off `origin/example` (`7942823`).

---

## 1. The evidence command

```
npm --prefix .github/conformance run test:vendor
```

No JVM, no running game, no `npm install` — it uses only Node builtins. On this branch, the whole
of one run, `exit 0` (`scratchpad/dev171-evidence-green.txt`, complete, nothing elided):

```

> test:vendor
> node scripts/verify-vendor.mjs && node --test test/vendor-manifest.test.mjs

vendored client verified: 6 file(s) at https://github.com/wildware-uk/game-bridge-mcp@ecc9ac57384883104022bd5f313dcf13a774a361
✔ every recorded hash describes the bytes of the file it names (6.50462ms)
✔ the committed manifest is byte-identical to what record-vendor writes (2.492951ms)
✔ every .ts in vendor/ is named by the manifest (0.759289ms)
✔ no vendored file has been through a line-ending conversion (3.171688ms)
ℹ tests 4
ℹ suites 0
ℹ pass 4
ℹ fail 0
ℹ cancelled 0
ℹ skipped 0
ℹ todo 0
ℹ duration_ms 71.720928
```

(The timings differ from run to run and from the mutation captures in §3; the durations in this
document are whatever the run that produced each block reported, never normalised.)

A second, one-line check a reviewer can run on top: `npm --prefix .github/conformance run
record-vendor` on the committed tree prints `no change` and leaves `git status` clean, so the
manifest really is this tool's output and not something typed beside it.

### It goes red when the fix is reverted

Revert the fix — put the three shipped hashes back in `VENDORED.json` — and the command exits 1
before the test runner starts. That is mutation **M1** in the table below, run by the same script,
with its literal diff and its literal output.

---

## 2. What was wrong, and what I did

### The measurement that ranks the candidates

**Only three of the six files fail.** `errors.ts`, `launcher.ts` and `registry.ts` match their
recorded hashes exactly under plain `sha256sum` of the raw bytes, which is precisely what
`verify-vendor.mjs` computes. A systematic hashing difference in `verify-vendor` — the issue's
candidate 1: a BOM, a trailing newline, the path folded into the digest — would break all six
identically. It breaks three. **Candidate 1 is out on arithmetic**, not on inspection.

**The three that fail are the SHA-256 of exactly those bytes with LF converted to CRLF.** I probed
ten transforms per file (`scratchpad/probe-transforms.mjs`); each failing file matched under `crlf`
and nothing else, and each passing file matched under `raw` and *not* under `crlf`:

```
client.ts     recorded=9d50faeeb78759ba raw=c104dad0f6aeab47 matched-transforms=[crlf]
config.ts     recorded=d2d9deec6a00df83 raw=9bf2b4ed1a76255f matched-transforms=[crlf]
errors.ts     recorded=9e5f8be1f9e8a6e2 raw=9e5f8be1f9e8a6e2 matched-transforms=[raw, lf-from-crlf, utf8-string, latin1]
launcher.ts   recorded=44c9c02973f2e204 raw=44c9c02973f2e204 matched-transforms=[raw, lf-from-crlf, utf8-string, latin1]
manifest.ts   recorded=3f51bf137d2229be raw=9e4fe7f213fa5aba matched-transforms=[crlf]
registry.ts   recorded=cedb317d5a167ad3 raw=cedb317d5a167ad3 matched-transforms=[raw, lf-from-crlf, utf8-string, latin1]
```

The clean split is the control as well as the result: the transform that explains the failures does
*not* explain the successes, and vice versa. Reproducible in one line against the committed file:

```
$ sed 's/$/\r/' .github/conformance/vendor/manifest.ts | sha256sum
3f51bf137d2229bea6cb406ae43e17cf09760fb2cd716da331c5f01c35b4b982  -
```

That is character-for-character the value `VENDORED.json` recorded for `manifest.ts`.

### The vendored bytes really are upstream's at `ecc9ac5` — checked, not assumed

A clone of `game-bridge-mcp` is on this box at `/srv/ssd1/workspace/game-bridge-mcp`, with
`origin` = `https://github.com/wildware-uk/game-bridge-mcp` and commit
`ecc9ac57384883104022bd5f313dcf13a774a361` present. Every one of the six files at that commit
hashes to the value on disk in `vendor/`:

```
$ git -C /srv/ssd1/workspace/game-bridge-mcp show ecc9ac57384883104022bd5f313dcf13a774a361:src/client.ts | sha256sum
c104dad0f6aeab470ccf89e5fee375074af2fd671a616a9a99942ddaf3924c0b  -
$ git -C /srv/ssd1/workspace/game-bridge-mcp show ecc9ac57384883104022bd5f313dcf13a774a361:src/config.ts | sha256sum
9bf2b4ed1a76255fb13d639df408039525f9c15cd38a2fa1389efbbab984a211  -
$ git -C /srv/ssd1/workspace/game-bridge-mcp show ecc9ac57384883104022bd5f313dcf13a774a361:src/manifest.ts | sha256sum
9e4fe7f213fa5aba6dc2ba9e845733e3054a7caf5810c1d23a571d578997e5f1  -
$ git -C /srv/ssd1/workspace/game-bridge-mcp show ecc9ac57384883104022bd5f313dcf13a774a361:src/errors.ts | sha256sum
9e5f8be1f9e8a6e2f10532a073f8a41cdc9a3affb17feac0bade983325f9f1c5  -
$ git -C /srv/ssd1/workspace/game-bridge-mcp show ecc9ac57384883104022bd5f313dcf13a774a361:src/launcher.ts | sha256sum
44c9c02973f2e204a9203488502d81a3c3781cb62b5d9b49a6109f1d7952e004  -
$ git -C /srv/ssd1/workspace/game-bridge-mcp show ecc9ac57384883104022bd5f313dcf13a774a361:src/registry.ts | sha256sum
cedb317d5a167ad39455b2ea4cf18feee12b299dfca830abdb4e672cec12821c  -
```

All six equal the `sha256sum` of `vendor/*.ts` on this branch. **So the copies are upstream's bytes,
unadjusted — no import rewrites, no shims — and the lead's condition for re-recording is met by a
fetched copy diffed, not by assertion.**

I also enumerated every version of the three failing files that has ever existed upstream — all five
commits (`88cfe5d`, `89ac9ce`, `fa30f9d`, `ecc9ac5`, `48fa7f5`) plus the working tree — and no
version produces the recorded values. `client.ts` is `c104dad…` at every commit that has it and
absent at `88cfe5d`; `config.ts` is `9bf2b4e…` at all five; `manifest.ts` is `9e4fe7f…` at all four
that have it and `bbbb442…` at `88cfe5d`. **So it is not the issue's candidate 3 either.** The
recorded hashes describe no commit of that repository. They describe a line-ending conversion of the
right one.

### The root cause, stated plainly

`verify-vendor.mjs` had code behind it. The recorded side of the comparison did not. Six hex strings
were produced by hand, out of band, and three of them came through a route that converted line
endings on the way. The mechanism — clipboard, editor, browser view — is unrecoverable and does not
matter. A number a person types is a number nobody can reproduce, and nothing in the repository
could tell you where it came from.

### What I landed

| | |
|---|---|
| `scripts/vendor-hash.mjs` | The one procedure for "the hash of this file", called by the recorder, the verifier and the test. They cannot disagree about what hashing means. |
| `scripts/record-vendor.mjs`, `npm run record-vendor` | The writer that did not exist, in the same shape as `udeaWriteProtocolLock`: run it deliberately, review the diff. Refuses to record bytes containing a CR. |
| `test/vendor-manifest.test.mjs` | Four assertions; no JVM, no game, no compile. |
| `scripts/verify-vendor.mjs` | Rewritten on the shared module; also rejects a CR and an unlisted vendored file. |
| `.github/conformance/.gitattributes` | `vendor/** -text`, so a `core.autocrlf=true` checkout cannot reintroduce the shape. |
| `vendor/VENDORED.json` | Three hashes re-recorded from the bytes proved above to be upstream's. |
| `.github/workflows/ci.yml` | The conformance job's first step runs `npm run test:vendor` instead of `npm run verify-vendor`. |
| `.github/conformance/README.md` | The refresh procedure, and the explicit statement that the copies are unadjusted. |

### Decisions I made, and what I rejected

**Rejected: re-record the three hashes and stop.** That is the symptom. It leaves the same hole —
the next refresh is another six hand-typed strings — and it converts a gate that has never passed
into a gate that can never fail, which the issue names as worse than the bug.

**Rejected: normalise line endings on both sides** (hash after converting CRLF to LF, tolerating a
converted checkout). It would make the symptom go away with a one-line change to `hashBytes` and no
CR fence. I rejected it because it weakens what "vendored" asserts: the point of this directory is
that the bytes are upstream's, and a hash over normalised text no longer says that. **To overturn:**
make `hashBytes` normalise, drop `containsCarriageReturn` from `verify-vendor.mjs`,
`record-vendor.mjs` and `vendor-manifest.test.mjs`, and delete `.gitattributes`. Everything else
stands.

**Rejected: changing `gamebridge.json`'s `./gradlew` to `sh gradlew`.** The declaration is correct
and CI chmods the wrapper before using it; the failure is local to this box. See §6.

**Kept: `test:vendor` runs in the job's first step rather than as a new step.** A separate step would
report more precisely, but it would also be a second place for the conformance job to grow a
skipped-in-practice gate. The first step already exists to fail before anything is compiled, which
is exactly where these four assertions belong.

**No frozen contract was touched.** Nothing under `docs/contracts/` is in the diff.

---

## 3. Mutations

M1–M4 and the control are run by `scratchpad/dev171-mutations.sh`, which restores the tree between
each one; **M5 I ran by hand** and its captures sit in the same directory. Every diff below is the
literal `git diff -- .github/conformance` from that run (`git status --porcelain` appended, so
untracked files show), and every result block is the first four lines of the literal test summary —
`node --test` then repeats each failure under a `✖ failing tests:` heading, and that repetition is
the only thing cut. Nothing here is retyped.

Legend for the four assertions: **H** hashes describe the bytes · **B** manifest is byte-identical
to the recorder's output · **N** every `.ts` is named · **CR** no line-ending conversion.

| # | mutation | H | B | N | CR | `verify-vendor` |
|---|---|---|---|---|---|---|
| — | baseline (the tree as committed) | ✔ | ✔ | ✔ | ✔ | exit 0 |
| M1 | the fix reverted: the three shipped hashes | ✖ | ✖ | ✔ | ✔ | **exit 1** |
| M2 | a CRLF copy whose recorded hash agrees with it | ✔ | ✔ | ✔ | **✖** | **exit 1** |
| M3 | a vendored `.ts` the manifest does not name | ✔ | ✖ | **✖** | ✔ | **exit 1** |
| M4 | a hand-edited manifest, hashes all correct | ✔ | **✖** | ✔ | ✔ | exit 0 |
| M5 | a file the manifest names, missing from disk | ✖ | ✖ | ✖ | ✔ | **exit 1** |
| C | control: CR outside `vendor/`, CRLF non-`.ts` inside it | ✔ | ✔ | ✔ | ✔ | exit 0 |

Each row isolates a different assertion, and **M2 and M4 are the two that matter**: M2 shows the CR
fence catches a fork the hashes cannot see, and M4 shows `verify-vendor` alone would pass a manifest
nobody generated.

### M1 — the fix reverted

```diff
diff --git a/.github/conformance/vendor/VENDORED.json b/.github/conformance/vendor/VENDORED.json
index 7bd2388..8b5e6de 100644
--- a/.github/conformance/vendor/VENDORED.json
+++ b/.github/conformance/vendor/VENDORED.json
@@ -8,11 +8,11 @@
     "path": "src/"
   },
   "files": {
-    "client.ts": "c104dad0f6aeab470ccf89e5fee375074af2fd671a616a9a99942ddaf3924c0b",
-    "config.ts": "9bf2b4ed1a76255fb13d639df408039525f9c15cd38a2fa1389efbbab984a211",
+    "client.ts": "9d50faeeb78759ba6b0d9588456215a7b36f807ad22cb42880431bfeae9a7c42",
+    "config.ts": "d2d9deec6a00df8303bb44ab281d19826f6d731c372dae8b9f40afeb006863d4",
     "errors.ts": "9e5f8be1f9e8a6e2f10532a073f8a41cdc9a3affb17feac0bade983325f9f1c5",
     "launcher.ts": "44c9c02973f2e204a9203488502d81a3c3781cb62b5d9b49a6109f1d7952e004",
-    "manifest.ts": "9e4fe7f213fa5aba6dc2ba9e845733e3054a7caf5810c1d23a571d578997e5f1",
+    "manifest.ts": "3f51bf137d2229bea6cb406ae43e17cf09760fb2cd716da331c5f01c35b4b982",
     "registry.ts": "cedb317d5a167ad39455b2ea4cf18feee12b299dfca830abdb4e672cec12821c"
   }
 }
```

```
✖ every recorded hash describes the bytes of the file it names (6.984404ms)
✖ the committed manifest is byte-identical to what record-vendor writes (3.004377ms)
✔ every .ts in vendor/ is named by the manifest (1.105622ms)
✔ no vendored file has been through a line-ending conversion (1.666432ms)
```

```
The vendored game-bridge-mcp client does not match VENDORED.json (commit ecc9ac57384883104022bd5f313dcf13a774a361):
  - client.ts does not match its recorded hash.
    recorded 9d50faeeb78759ba6b0d9588456215a7b36f807ad22cb42880431bfeae9a7c42
    actual   c104dad0f6aeab470ccf89e5fee375074af2fd671a616a9a99942ddaf3924c0b
    Re-copy it from https://github.com/wildware-uk/game-bridge-mcp src/ at the recorded commit, or re-record deliberately with `npm run record-vendor`.
```
*(first of three identical-shaped problems; the `config.ts` and `manifest.ts` blocks follow in
`dev171-mutations/m1.verify` and are elided here)*

**This is the proof the evidence command goes red on revert.** `verify-vendor exit=1`.

### M2 — a CRLF copy whose recorded hash agrees with it

The silent fork the hashes structurally cannot see. Two commands:

```
$ sed -i 's/$/\r/' .github/conformance/vendor/manifest.ts
$ sha256sum .github/conformance/vendor/manifest.ts
3f51bf137d2229bea6cb406ae43e17cf09760fb2cd716da331c5f01c35b4b982  .../vendor/manifest.ts
```

…then record that hash, which is the *historically shipped* value:

```diff
diff --git a/.github/conformance/vendor/VENDORED.json b/.github/conformance/vendor/VENDORED.json
index 7bd2388..6d916cd 100644
--- a/.github/conformance/vendor/VENDORED.json
+++ b/.github/conformance/vendor/VENDORED.json
@@ -12,7 +12,7 @@
     "config.ts": "9bf2b4ed1a76255fb13d639df408039525f9c15cd38a2fa1389efbbab984a211",
     "errors.ts": "9e5f8be1f9e8a6e2f10532a073f8a41cdc9a3affb17feac0bade983325f9f1c5",
     "launcher.ts": "44c9c02973f2e204a9203488502d81a3c3781cb62b5d9b49a6109f1d7952e004",
-    "manifest.ts": "9e4fe7f213fa5aba6dc2ba9e845733e3054a7caf5810c1d23a571d578997e5f1",
+    "manifest.ts": "3f51bf137d2229bea6cb406ae43e17cf09760fb2cd716da331c5f01c35b4b982",
     "registry.ts": "cedb317d5a167ad39455b2ea4cf18feee12b299dfca830abdb4e672cec12821c"
   }
 }
```

The `manifest.ts` half of the diff is every line of the file replaced by itself plus a CR, so it is
elided; `wc -l` on the committed file gives 252, and git's own `--stat` is the literal record. The
`sed` above reproduces the bytes exactly:

```
 .github/conformance/vendor/VENDORED.json |   2 +-
 .github/conformance/vendor/manifest.ts   | 504 +++++++++++++++----------------
 2 files changed, 253 insertions(+), 253 deletions(-)
```

```
✔ every recorded hash describes the bytes of the file it names (5.849552ms)
✔ the committed manifest is byte-identical to what record-vendor writes (4.18341ms)
✔ every .ts in vendor/ is named by the manifest (1.757914ms)
✖ no vendored file has been through a line-ending conversion (4.078828ms)
```

**Three assertions green; only the fence catches it.** `verify-vendor exit=1`, and the recorder
refuses outright:

```
Refusing to record: CR found in manifest.ts. https://github.com/wildware-uk/game-bridge-mcp is an LF repository, so this is a converted copy rather than upstream's bytes. Re-copy without line-ending translation and run this again.
record-vendor exit=1
```

### M3 — a vendored file the manifest does not name

```
$ cp .github/conformance/vendor/errors.ts .github/conformance/vendor/composites.ts
?? .github/conformance/vendor/composites.ts
```

```
✔ every recorded hash describes the bytes of the file it names (6.653498ms)
✖ the committed manifest is byte-identical to what record-vendor writes (5.608487ms)
✖ every .ts in vendor/ is named by the manifest (1.887446ms)
✔ no vendored file has been through a line-ending conversion (1.797124ms)
```

`verify-vendor exit=1`: *"composites.ts sits in vendor/ and VENDORED.json does not name it, so
nothing hashes it."* This was a real hole before the change: the old script iterated
`manifest.files`, so an unlisted file was outside the gate entirely.

### M4 — a hand-edited manifest with every hash correct

```diff
diff --git a/.github/conformance/vendor/VENDORED.json b/.github/conformance/vendor/VENDORED.json
index 7bd2388..f1f45f9 100644
--- a/.github/conformance/vendor/VENDORED.json
+++ b/.github/conformance/vendor/VENDORED.json
@@ -9,8 +9,8 @@
   },
   "files": {
     "client.ts": "c104dad0f6aeab470ccf89e5fee375074af2fd671a616a9a99942ddaf3924c0b",
-    "config.ts": "9bf2b4ed1a76255fb13d639df408039525f9c15cd38a2fa1389efbbab984a211",
     "errors.ts": "9e5f8be1f9e8a6e2f10532a073f8a41cdc9a3affb17feac0bade983325f9f1c5",
+    "config.ts": "9bf2b4ed1a76255fb13d639df408039525f9c15cd38a2fa1389efbbab984a211",
     "launcher.ts": "44c9c02973f2e204a9203488502d81a3c3781cb62b5d9b49a6109f1d7952e004",
     "manifest.ts": "9e4fe7f213fa5aba6dc2ba9e845733e3054a7caf5810c1d23a571d578997e5f1",
     "registry.ts": "cedb317d5a167ad39455b2ea4cf18feee12b299dfca830abdb4e672cec12821c"
```

```
✔ every recorded hash describes the bytes of the file it names (6.804511ms)
✖ the committed manifest is byte-identical to what record-vendor writes (5.583287ms)
✔ every .ts in vendor/ is named by the manifest (1.971078ms)
✔ no vendored file has been through a line-ending conversion (1.56828ms)
```

**`verify-vendor exit=0`** — the hashes are all correct, so the verifier is satisfied. Only the
byte-identity assertion notices that the file is not what the recorder writes. That is the
assertion whose absence is how the three wrong hashes got in.

### M5 — a file the manifest names, missing from disk

```
$ mv .github/conformance/vendor/registry.ts /tmp/.../registry.ts.bak
```

```
✖ every recorded hash describes the bytes of the file it names (8.391666ms)
✖ the committed manifest is byte-identical to what record-vendor writes (5.142327ms)
✖ every .ts in vendor/ is named by the manifest (3.309582ms)
✔ no vendored file has been through a line-ending conversion (2.705207ms)
```

with, from the failure detail of the first of those:

```
    registry.ts: recorded cedb317d5a167ad39455b2ea4cf18feee12b299dfca830abdb4e672cec12821c, but the file is missing (ENOENT: no such file or directory, open '/srv/ssd1/workspace/Udea/.claude/worktrees/agent-abeb18c3aaaf4e15d/.github/conformance/vendor/registry.ts')
```

and `verify-vendor exit=1`:

```
The vendored game-bridge-mcp client does not match VENDORED.json (commit ecc9ac57384883104022bd5f313dcf13a774a361):
  - registry.ts is missing: ENOENT: no such file or directory, open '/srv/ssd1/workspace/Udea/.claude/worktrees/agent-abeb18c3aaaf4e15d/.github/conformance/vendor/registry.ts'
verify-vendor exit=1
```

**Running this is what produced a change to the test.** On the first attempt the missing file
surfaced as a bare `Error: ENOENT` with a stack into `node:internal/fs/promises`, which names node
rather than the vendored copy. The test now catches it and reports it as a mismatch, which is the
`recorded …, but the file is missing (…)` line above — the second run, after the fix.

`record-vendor` in the same state is the `removed` branch, and it does the sane thing:

```
recorded 5 file(s) at https://github.com/wildware-uk/game-bridge-mcp@ecc9ac57384883104022bd5f313dcf13a774a361
  registry.ts: removed
Review the diff: it is the claim that this directory holds upstream's code and not a fork.
record-vendor exit=0
```

I restored `registry.ts` from the copy and checked its hash back to
`cedb317d5a167ad39455b2ea4cf18feee12b299dfca830abdb4e672cec12821c`, and `git checkout`'d the
manifest.

### C — the control, which must stay green

A fence that fires on the wrong thing is as bad as one that never fires. Two things that mention or
neighbour the defect but are not it:

```diff
diff --git a/.github/conformance/scripts/record-vendor.mjs b/.github/conformance/scripts/record-vendor.mjs
index 3c0d22d..d861f1d 100644
--- a/.github/conformance/scripts/record-vendor.mjs
+++ b/.github/conformance/scripts/record-vendor.mjs
@@ -72,3 +72,4 @@ if (added.length > 0 || removed.length > 0 || changed.length > 0) {
     "Review the diff: it is the claim that this directory holds upstream's code and not a fork."
   );
 }
+
\ No newline at end of file
 M .github/conformance/scripts/record-vendor.mjs
?? .github/conformance/vendor/NOTES.md
```

produced by `printf '\r' >> scripts/record-vendor.mjs` (a real CR byte outside `vendor/`) and
`printf 'Upstream is at ecc9ac5\r\n' > vendor/NOTES.md` (a CRLF non-`.ts` file *inside* `vendor/`).

```
✔ every recorded hash describes the bytes of the file it names (9.099765ms)
✔ the committed manifest is byte-identical to what record-vendor writes (5.983595ms)
✔ every .ts in vendor/ is named by the manifest (0.966608ms)
✔ no vendored file has been through a line-ending conversion (1.171993ms)
```

`verify-vendor exit=0`. The fence is scoped to vendored `.ts` sources, and says so truthfully.

---

## 4. `sh gradlew build`

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --console=plain
```

**No exclusions.** Four runs, because this box was under sustained load from another project the
whole time and the wall-clock budget tasks fail under it. Every log named below is in the
scratchpad and every number is spliced from one.

### Run A — cold, at load 10–12 → `BUILD FAILED in 1m 25s`

Five assertions in four tasks, and all four are the ones the developer contract names as
load-sensitive wall-clock budgets (`dev171-build.log`):

```
    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) median 19.621ms, budget 4.0ms
    graph deserialisation: best=12.648283ms median=20.829643ms over 2000 assets (budget 15ms)
    warm reload decision: median 723ms over 4 samples [1356, 638, 672, 723]
    warm validate of one script: median 433ms over 4 samples [24, 296, 433, 540]
    phase 2 exit: agent request -> running world observed changed in 1080ms
```

Failing tasks: `:udea-core:udeaBenchCharacterMover`, `:udea-assets-compiler:udeaPackGate`,
`:udea-assets-compiler:udeaDaemonBudget`, `:udea-agent-host:udeaPhase2Exit`.

### Run B — those four alone, `--rerun-tasks`, at load ~5 → `BUILD SUCCESSFUL in 36s`

`46 actionable tasks: 46 executed` (`dev171-budgets-solo.log`):

```
    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) median 2.697ms, budget 4.0ms
    graph deserialisation: best=6.848151ms median=8.657363ms over 2000 assets (budget 15ms)
    warm reload decision: median 226ms over 4 samples [232, 226, 144, 172]
    warm validate of one script: median 135ms over 4 samples [19, 135, 135, 128]
    phase 2 exit: typo'd reference rejected in 19ms (median of [339, 19, 12])
```

**Every one of the five moved by a factor of 3 to 7 with nothing changed but the load.** That is the
box, not this branch — and it cannot be this branch, because the diff contains no Kotlin at all
(§10).

### Run C — `sh gradlew build` again → `BUILD SUCCESSFUL in 13s`

`204 actionable tasks: 7 executed, 197 up-to-date` (`dev171-build2.log`). Green, but heavily
incremental, so I did not stop there.

### Run D — `sh gradlew build --rerun-tasks`, load back to ~20 → `BUILD FAILED in 1m 17s`

**`148 actionable tasks: 148 executed`** — everything in `build` really ran, nothing skipped, nothing
cached. Exactly two tasks failed, both wall-clock budgets, both from run A's set:

```
CharacterMoverBudgetTest > 200 movers replayed 60 times fit in the per-frame budget() FAILED
> Task :udea-core:udeaBenchCharacterMover FAILED
DaemonLatencyBudgetTest > a warm reload of one script decides inside the edit-to-observe budget() FAILED
> Task :udea-assets-compiler:udeaDaemonBudget FAILED
BUILD FAILED in 1m 17s
148 actionable tasks: 148 executed
```

with, from the same log:

```
    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) median 5.898ms, budget 4.0ms
    warm reload decision: median 548ms over 4 samples [689, 548, 545, 537]
    graph deserialisation: best=10.411791ms median=14.994212ms over 2000 assets (budget 15ms)
```

`udeaPackGate` scraped through at 14.994ms against a 15ms budget, which is the same signal from the
other side.

### Run E — `sh gradlew build` again → `BUILD SUCCESSFUL in 9s`

`204 actionable tasks: 10 executed, 2 from cache, 192 up-to-date` (`dev171-build3.log`). **Read that
carefully rather than taking the green**: the two tasks that failed in run D are the two `from
cache` —

```
> Task :udea-core:udeaBenchCharacterMover FROM-CACHE
> Task :udea-assets-compiler:udeaDaemonBudget FROM-CACHE
```

— so this run did not measure them. It replayed run B's passing result for the same inputs. That is
correct Gradle behaviour and it is *not* a fresh measurement, so I did not stop here either.

### Run F — those two, `--rerun-tasks --no-build-cache`, at load 11.7 → `BUILD SUCCESSFUL in 18s`

`30 actionable tasks: 30 executed` (`dev171-budgets-solo2.log`):

```
    [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) median 2.117ms, budget 4.0ms
    warm reload decision: median 172ms over 4 samples [208, 172, 155, 148]
    warm validate of one script: median 117ms over 4 samples [10, 117, 122, 110]
```

A genuine execution with the cache off, of exactly the two tasks run D failed on, and they pass with
roughly half the budget to spare — at a load of 11.7, higher than run D's own starting load. So the
failures are contention *timing*, not a threshold this branch crossed.

### What this adds up to, and what it does not say

- **Run D executed all 148 actionable tasks** with nothing cached or skipped. Two failed, and both
  are wall-clock budgets the developer contract names as load-sensitive.
- **Runs B and F executed those budgets fresh, cache off, and they passed** with 2–7× headroom.
- **No non-budget task has failed in any of the six runs.**
- `sh gradlew build` reports `BUILD SUCCESSFUL` in runs C and E — but in both of those the budget
  tasks were up-to-date or from cache, so **neither of those greens is by itself evidence about the
  budgets.** The evidence about the budgets is run F.
- This cannot be my change: the diff contains **no Kotlin, no Gradle logic and no simulation code**
  (§10) — nine files, all under `.github/`.
- Independently corroborated: dev-170, on an unrelated branch on this box, reports the same four-task
  family failing inside a full build at load ~16 (`udeaPackGate` graph deserialisation median
  31.36ms vs 15ms; warm reload 646ms; warm validate 377ms) and passing re-run alone at load ~8
  (7.60ms, 195ms, 151ms).

I am not claiming a clean cold green `sh gradlew build` on this box; I am claiming every task in
`build` has been executed and passed on this tree, with the two flaky ones measured separately and
the numbers above.

### Three gates outside `check`

Run by name, and **`--rerun-tasks`** because the first attempt reported all three `UP-TO-DATE`,
which measures nothing (`dev171-gates2.log`):

```
> Task :udeaVerifyModuleGraph
> Task :udeaVerifyNoLegacyDependencies
> Task :udeaVerifyAgentsMd
BUILD SUCCESSFUL in 4s
33 actionable tasks: 33 executed
```

I did **not** run `:moba:runUdpProof`, `:moba:runLaneShot` or `:moba:runNetProof`. Two of the three
are documented red before this branch, none of them touches `.github/conformance`, and my diff
contains no Kotlin that could reach them.

### Build scripts

None edited. The diff contains no `build.gradle.kts` and no `build-logic` file, so the
`:moba:tasks` check for a KDoc that swallows task registrations does not apply here.

---

## 5. The Actions run

**`game-bridge-mcp conformance` is green through every step:**
<https://github.com/wildware-uk/Udea/actions/runs/33429924827/job/99612896957> — job `99612896957`,
head SHA `f5a7f6d`, conclusion `success`, 58s.

```
✓ game-bridge-mcp conformance in 58s (ID 99612896957)
  ✓ Set up job
  ✓ Run actions/checkout@v4
  ✓ Run actions/setup-java@v4
  ✓ Run gradle/actions/setup-gradle@v4
  ✓ Run actions/setup-node@v4
  ✓ Make the wrapper executable
  ✓ Compile the vendored client
  ✓ Generate the launch declaration the way a clean clone would
  ✓ Parse it with the bridge's own reader
  ✓ Boot a headless instance with the agent surface bound
  ✓ Wait for GET /health
  ✓ Drive the real client against it
  ✓ Ask the game to close itself, and assert it did
  ✓ Upload the instance log
```

Three `node --test` suites in that job report `# fail 0`, with `ok N -` lines totalling 16 across
them (4 + 6 + 6). Every block below is spliced from
`scratchpad/dev171-conformance-f5a7f6d.log` (`gh api …/actions/jobs/99612896957/logs`), with the
leading ISO timestamp column removed by `cut -c30-`; every elision is marked, and each block is a
consecutive run of lines from that file.

Lines 296–303 — the step that had never passed:

```
added 3 packages in 638ms

> test:vendor
> node scripts/verify-vendor.mjs && node --test test/vendor-manifest.test.mjs

vendored client verified: 6 file(s) at https://github.com/wildware-uk/game-bridge-mcp@ecc9ac57384883104022bd5f313dcf13a774a361
TAP version 13
# Subtest: every recorded hash describes the bytes of the file it names
```

*…[elided: TAP detail for tests 1–3 and the start of test 4, lines 304–320]…*

Lines 321–335:

```
# Subtest: no vendored file has been through a line-ending conversion
ok 4 - no vendored file has been through a line-ending conversion
  ---
  duration_ms: 4.186621
  type: 'test'
  ...
1..4
# tests 4
# suites 0
# pass 4
# fail 0
# cancelled 0
# skipped 0
# todo 0
# duration_ms 80.204205
```

Lines 526, 532, 538, 544, 550, 556 — the real vendored client against a live instance, **which had
never executed before this branch** *(each line is a separate `ok`, with its TAP detail elided
between them)*:

```
ok 1 - health identifies this as a game surface
ok 2 - commandAndSync takes the completedCommandId path, not the frame fallback
ok 3 - time.step advances exactly the ticks asked for, confirmed
ok 4 - without completedCommandId the client degrades to frames and says so
ok 5 - every published tool survives the bridge's manifest normalisation
ok 6 - every tool's inputSchema is a JSON Schema object a strict client accepts
```

Lines 617–619 — the close half:

```
{"accepted":true,"commandId":6,"frame":89}
port 7820 went quiet and GET /state reports GameOffline
process 2679 exited on its own after close; nothing signalled it
```

### There was no second failure behind the first

The lead told me to expect one. There is not one. Every step of the job's own sequence passed on the
first attempt once step 1 stopped blocking, and I rehearsed the whole sequence locally before
pushing (§6) with the same result. Worth stating explicitly, because "nothing else was broken" is
only knowable now that it has run.

### #170 does not reach this job

`moba`'s 25 × `UDEA0032` asset failure runs in the `build` job, not here. This job's only Gradle
invocations are `:moba:udeaGenerateLaunchDeclaration` and `:udea-agent-host:udeaPhase1Demo`, neither
of which depends on asset validation, and both are green in the run above.

### What else is red in that run, and it is not mine — one job moved, and only one

`origin/example` at `7942823` has its own run,
[33425479983](https://github.com/wildware-uk/Udea/actions/runs/33425479983), pushed 20 minutes
before mine. Comparing every job by name, the two runs are **identical except for one row**:

| job | `example` `7942823` | this branch `833654b` |
|---|---|---|
| **game-bridge-mcp conformance** | **failure** | **success** |
| agent brief matches the tree | success | success |
| KSP stays incremental | success | success |
| gl tests (xvfb) | success | success |
| migration ledger | success | success |
| replay-equality (join) | success | success |
| replay-equality ×3 | success | success |
| build (ubuntu-latest) | failure | failure |
| build (windows-latest) | failure | failure |
| build with the K2 plugin disabled | failure | failure |
| clean build under budget | failure | failure |
| determinism ×4 | failure | failure |
| the FIR checkers fail a real build | failure | failure |
| kotlin upgrade probe (non-blocking) | skipped | skipped |

That is the cleanest statement of what this branch did: **one job flipped from red to green and
nothing else moved.**

Of the pre-existing failures:

- `build (ubuntu-latest)` on my run carries **25** occurrences of `UDEA0032`
  (`grep -c UDEA0032` over `scratchpad/dev171-build-ubuntu-ci.log`, fetched with
  `gh api …/actions/jobs/99603927180/logs`). That is #170 exactly, and #170 owns the fix.
- `the FIR checkers fail a real build` fails on `A broken component fails a real build, and compiles
  clean without the plugin`. That is #173's block of `ci.yml` (lines ~474–587), which I did not
  touch.
- The rest I did not investigate; they are red on `example` at the commit I branched from, and my
  diff contains no Kotlin, no Gradle logic and no simulation code that could reach them.

---

## 6. Driven for real

I rehearsed every step of the conformance job on this box before pushing, and then drove `moba`
itself through the bridge's tool surface.

**The job's sequence, locally.** `:moba:udeaGenerateLaunchDeclaration` → `BUILD SUCCESSFUL in 9s`;
`node --test test/launch-declaration.test.mjs` → 6/6; a live `:udea-agent-host:udeaPhase1Demo` on
port 7861 answering `{"ok":true,"frame":59,"tick":58,"paused":false,"renderMode":"Headless",…}`;
`UDEA_AGENT_PORT=7861 node --test test/bridge-contract.test.mjs` → 6/6; and `GET /command?cmd=close`
→ `{"accepted":true,"commandId":6,"frame":1433}`, after which the instance log ends

```
> Task :udea-agent-host:udeaPhase1Demo
[phase1-demo] listening on http://127.0.0.1:7861 with 30 tools
[phase1-demo] closed: local conformance rehearsal
…
BUILD SUCCESSFUL in 35s
```

(`scratchpad/agent-instance.log`) and port 7861 went quiet. Nothing signalled the process.

**`moba` itself.** `:moba:run -PdebugPort=7845` under `xvfb-run`, `renderMode: Offscreen`, real
LWJGL3, `51 tools` — `list_toolsets` returned 10 toolsets and I read them rather than assuming.
`render.follow_entity` then `render.screenshot` produced the frames in §7; `close` shut it down and
the port went quiet. **Nothing is left running.**

**One box-local finding, and I did not "fix" it.** `mcp__game-bridge__launch_instance` fails here
with the one-line Gradle error `25.0.2`, because the bridge runs `gamebridge.json`'s
`./gradlew :moba:run -PdebugPort={port}` with this box's ambient `JAVA_HOME` (Temurin 25, which
Gradle 8.13 rejects). Also, `moba.agent`'s Offscreen mode still needs GLFW, so with no `DISPLAY` it
dies with `GLFW_PLATFORM_UNAVAILABLE` — `xvfb-run` fixes that. **Neither is a defect in the
declaration**: CI chmods the wrapper and provisions JDK 17, and a developer's machine has a display.
Putting `JAVA_HOME` into the declaration's `env` block would bake one box's sdkman path into a
generated file. Left alone, recorded here.

---

## 7. Images

All in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`.

| file | what it shows | what it proves |
|---|---|---|
| `issue171-conformance-green.png` | the step list from `gh run view 33429924827 --job 99612896957` on `f5a7f6d`, rendered verbatim — every row a green tick, from `Set up job` through `Upload the instance log` | AC1: the job is green through every step, not just the first |
| `issue171-cause-crlf-split.png` | the transform probe: three files matched only by `crlf`, three only by `raw` | the cause, and that it is not a systematic bug in `verify-vendor` — a systematic bug would not split 3/3 |
| `issue171-moba-live-via-launch-declaration.png` | a real Offscreen GL frame of `moba` at 1280×720: undead skeletons with swords and shields, the match HUD (`MATCH 2 ORC 1 SOLDIER 4 UNDEAD 8`), the `ORC_ELITE 500/540` health bar and the ability bar | the tool surface the conformance job guards really drives the shipped game — captured with `render.follow_entity` then `render.screenshot` over the bridge. The skeletons sit at the top edge partly behind the HUD bar because the camera had only just been retargeted; that is the framing of this capture, not a HUD defect |
| `issue171-moba-hud-death-banner.png` | the same instance a few hundred ticks earlier, the `YOU DIED / back in 1.5s` banner across a live match | the same instance was simulating, not a static frame — the score line differs from the frame above (`ORC 0 SOLDIER 4 UNDEAD 10`) |

The two `moba` frames are `cap_0003.png` and `cap_0002.png` from
`moba/build/udea-agent-artifacts/`, copied unmodified.

---

## 8. The acceptance criteria

**☑ "A real Actions run shows `game-bridge-mcp conformance` green through every step, not just the
first. Link the run."**
<https://github.com/wildware-uk/Udea/actions/runs/33429924827/job/99612896957>, on `f5a7f6d`. §5 has
the step list, the assertions and the close transcript spliced from that job's log, and §11 is the
check that those splices are contiguous and verbatim.

**☑ "The cause is stated in a comment on this issue, with the alternative considered."**
<https://github.com/wildware-uk/Udea/issues/171#issuecomment-5482952555>, and §2 here. Both name the
alternative I rejected (normalise on both sides) and say how to overturn my choice.

**☑ "An evidence command that goes red when the fix is reverted."**
`npm --prefix .github/conformance run test:vendor`. §1 for the green run, mutation **M1** in §3 for
the literal diff of the revert and its `exit 1`.

**☑ "If the vendored bytes differ from upstream on purpose, the difference is described where the
next person copying a new version will read it."**
They do not differ, and I proved it rather than asserting it (§2, six `sha256sum` lines against the
upstream clone). `README.md` now says so in the section a person refreshing the copy reads, and
tells them what to do if a future refresh ever *does* need an adjustment:

> **The copies are upstream's bytes with nothing adjusted.** No import rewriting, no shim, no `.js`
> extension fixups… If a future refresh ever does need an adjustment, describe it in this section,
> because the recorded hash cannot: a hash says the bytes are what somebody recorded, not that they
> are what upstream published.

The same section carries the four-step refresh procedure, ending "Read the diff."

---

## 9. Regenerated files

**None.** `udea-codegen/net-protocol.lock` and
`udea-codegen/src/test/resources/expected-generated-hashes.txt` are untouched — no replicated
component was added or removed. `git diff --stat origin/example...f5a7f6d` names nine files, all under
`.github/`.

---

## 10. My own pass over the diff

Against the closed reject list — engineering-standards §8 and the `AGENTS.md` do-not list.

- **No Kotlin, no simulation code, no Fleks, no GL.** The whole diff is `.github/`: three `.mjs`
  scripts, one `.mjs` test, `VENDORED.json`, `package.json`, `README.md`, `.gitattributes` and one
  line of `ci.yml`. No `by net(...)`, no snapshot codec, no setter instrumentation, no wall clock or
  unseeded randomness in simulation, no `common` dependency, no reflection on a per-tick path, no
  bare primitive for a domain concept, no module arrow, no `GameContext` field.
- **No frozen contract changed.** Nothing under `docs/contracts/` in the diff.
- **`fieldNames`/`FieldMask`/`FieldStore` alignment**: not touched — no replicator or component work.
- **`Tick`**: no duration, deadline, ring slot, baseline or input stamp added anywhere.
- **`AGENTS.md`'s module table**: no module moved. `udeaVerifyAgentsMd` executed and passed locally
  with `--rerun-tasks` (§4) and the `agent brief matches the tree` job is green in CI.
- **A test that cannot fail**: every one of the four has been watched red for a distinct reason,
  with its literal diff, in §3 — plus a control that must stay green and does. Each mutation there
  produces a **different** set of red assertions, which is what says the four are four assertions
  and not one written out four times.
- **Generated code by string concatenation**: `renderManifest` uses `JSON.stringify(…, null, 2)`,
  not concatenation, and its output is asserted byte-identical to the committed file.
- **`TODO()`, stubbed return, swallowed exception**: none. `verify-vendor`'s one `catch` reports the
  file as missing with the error's message and continues to collect the rest, then exits 1 — it is a
  reported problem, not a swallowed one.
- **Copy-pasted logic differing only in a constant**: `verify-vendor` and the test both assert over
  the vendored set, but they share the primitives that must not disagree (`vendor-hash.mjs`) and each
  expresses its own assertions — a CLI reporter and a test suite over one library, not two copies.
  M4 shows they are not equivalent: it is red in the test and green in the verifier.
- **`public` nobody outside the module uses**: every name `vendor-hash.mjs` exports is imported by
  at least one of `verify-vendor.mjs`, `record-vendor.mjs` and `vendor-manifest.test.mjs` — checked
  by grepping each export name across the three, not asserted.
- **`gradlew`'s mode bit is not in the diff.** I ran `chmod +x gradlew` locally as the box requires
  and committed with `-c core.fileMode=false`; `git diff origin/example...f5a7f6d --stat` does not name
  it.

### What I did not exercise

I wrote a longer list here first, then noticed that two of its entries were *untested* rather than
untestable and took five minutes each, so I ran them instead. They are **M5** in §3: the missing-file
branch of `verify-vendor` and the `removed` branch of `record-vendor`. Running the first is what
found that a missing file surfaced as a raw `ENOENT` naming node's internals, which is now a
reported mismatch instead. What is left:

- **`record-vendor` *adding* a row.** M3 puts an unlisted file in `vendor/` and M5 takes one away,
  so the `(new)` marker in the changed-row loop is the one printing path no mutation has taken. It
  prints; it decides nothing.
- **Windows.** The `.gitattributes` fence is the fix for a `core.autocrlf=true` checkout and I
  cannot check out on Windows from here. What I *can* say is what it does on a Linux checkout, which
  is nothing: the control row shows the suite green with `vendor/** -text` in place. The claim that
  it prevents CRLF on a Windows checkout rests on git's documented `-text` semantics, not on a run.

---

## 11. The splices in this document are checked, and the checker has been seen to fail

Every transcript block in §5 is claimed to come from
`scratchpad/dev171-conformance-f5a7f6d.log`, the job log fetched with
`gh api repos/wildware-uk/Udea/actions/jobs/99612896957/logs`. "Every line appears somewhere in the
source" would pass a block whose lines are in the wrong order, so
`scratchpad/dev171-check-splices.py` looks for each block as a **consecutive, in-order window** in
the log, at the line number the brief names, and also requires it to appear verbatim in this file:

```
ok    296-303 vendor step: contiguous at CI log line 296, verbatim in the brief
ok    321-335 vendor summary: contiguous at CI log line 321, verbatim in the brief
ok    617-619 close: contiguous at CI log line 617, verbatim in the brief
ok    ok-line 526: matches log and brief
ok    ok-line 532: matches log and brief
ok    ok-line 538: matches log and brief
ok    ok-line 544: matches log and brief
ok    ok-line 550: matches log and brief
ok    ok-line 556: matches log and brief
ok    §1 evidence block: the captured file appears verbatim and entire in the brief

0 failure(s)
```

The six bridge-contract `ok` lines are deliberately *not* a contiguous block — TAP detail sits
between them — so the brief gives their individual line numbers and the checker asserts each is on
exactly the line claimed.

**The known negative.** A check I have only ever seen pass tells me nothing, so I copied this brief,
transposed two adjacent lines of the close block, and ran the checker against the copy:

```
transposed brief lines 647 and 648
ok    296-303 vendor step: contiguous at CI log line 296, verbatim in the brief
ok    321-335 vendor summary: contiguous at CI log line 321, verbatim in the brief
FAIL  617-619 close: block is not present verbatim in BRIEF-171.md
...
1 failure(s)
EXIT=1
```

It caught exactly the failure mode it exists for, and the transposed copy is deleted.

This also caught a real defect while I was writing: the close block originally carried
`process 2764`, the pid from an earlier run on a different SHA, against a log that says
`process 2679`. That is a spliced block being one round stale, which is the thing that is
impossible to notice by reading.
