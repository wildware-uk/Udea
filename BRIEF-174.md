**SHA: 4058275**

*(the whole change, and this brief. A brief cannot name the commit that contains it, so
there is exactly one commit on top of that SHA — the branch head — and it edits nothing but this
line. `git diff <SHA> HEAD` is that one-line diff.)*

# BRIEF-174 — `docs/contracts/` is frozen by a lock the build reads

Branch `issue-174-freeze-contracts-gate`, off `origin/example` at `e7159c1`.

---

## 1. The evidence command

```
sh gradlew :build-logic:test --tests '*ContractFreeze*'
```

27 tests across two classes: `ContractFreezeTest` (19, the rules, several of them reading the
**real** `docs/contracts/`, the **real** `docs/contracts.lock` and the **real** `AGENTS.md`) and
`ContractFreezeCheckTest` (8, TestKit builds that apply the convention plugin and run `check`, so
the wiring and the failure message are exercised in a real Gradle build rather than asserted about).

It goes red when the feature is reverted, and the seven mutations below say exactly how red.
Every row was produced by `/tmp/claude-1000/-srv-ssd1-workspace-Udea/01ec1be7-305f-4987-ab53-69f61b72d43e/scratchpad/mutate.sh`,
which applies a mutation, runs the command above, saves the literal `git diff` and the failing test
names, and reverts. The diffs and the test lists are spliced from those saved files (`M*.log`,
`M*.failed`), not retyped, and the whole table was re-run against the final tree so no row is
counting a different version of the code from its neighbour.

| # | Mutation | Failing tests |
|---|---|---|
| M0 | control: a comment that *mentions* `emptyList()` | **0** — the fence does not fire on prose |
| M1 | `findings` returns nothing | 15 |
| M2 | added/removed halves dropped — "a digest of three known filenames" | 11 |
| M3 | digest of raw bytes, no newline normalisation | 1 |
| M4 | a missing lock skips the check instead of failing | 1 |
| M5 | the gate is not wired into `check` | 8 |
| M6 | `AGENTS.md` stops naming the gate | 1 |

### M0 — the control

A fence that fires on prose is as wrong as one that passes on it, so the control is run first,
and it is the mutation that had better change nothing.

```
diff --git a/build-logic/src/main/kotlin/dev/wildware/udea/build/ContractFreeze.kt b/build-logic/src/main/kotlin/dev/wildware/udea/build/ContractFreeze.kt
index 6e0ea73..ee11c41 100644
--- a/build-logic/src/main/kotlin/dev/wildware/udea/build/ContractFreeze.kt
+++ b/build-logic/src/main/kotlin/dev/wildware/udea/build/ContractFreeze.kt
@@ -153,7 +153,8 @@ public object ContractFreeze {
      * Every way [actual] disagrees with [locked], in path order.
      *
      * Sorted so two runs over the same disagreement print the same thing, which is what lets a
-     * failure be pasted into an issue and compared against the next run's.
+     * failure be pasted into an issue and compared against the next run's. A comment that merely
+     * mentions returning emptyList() is prose, and must not move the gate.
      */
     public fun findings(locked: Map<String, String>, actual: Map<String, String>): List<MigrationFinding> {
         val changed = actual.filter { (path, hash) -> locked[path] != null && locked[path] != hash }
```

Failing tests: **none**. (The `gradlew` mode line is this box's `chmod +x`, never staged;
`mutate.sh` ends with `git checkout -- .`, which is why it appears here and in no other diff.)
### M1 — `findings` returns nothing

```
diff --git a/build-logic/src/main/kotlin/dev/wildware/udea/build/ContractFreeze.kt b/build-logic/src/main/kotlin/dev/wildware/udea/build/ContractFreeze.kt
index 6e0ea73..71fd1bd 100644
--- a/build-logic/src/main/kotlin/dev/wildware/udea/build/ContractFreeze.kt
+++ b/build-logic/src/main/kotlin/dev/wildware/udea/build/ContractFreeze.kt
@@ -181,7 +181,7 @@ public object ContractFreeze {
                     "independently implement what it says; deleting it does not fail, it lies.",
             )
         }
-        return (changed + added + removed).sortedWith(compareBy({ it.path }, { it.rule.value }))
+        return emptyList()
     }
 
     /**
```

```
ContractFreezeCheckTest > a contract deleted from the directory fails(File) FAILED
ContractFreezeCheckTest > deleting the whole frozen directory fails, rather than failing to configure(File) FAILED
ContractFreezeCheckTest > renaming a contract fails from both ends(File) FAILED
ContractFreezeCheckTest > an edited contract fails check, naming the file and the route out(File) FAILED
ContractFreezeCheckTest > a contract added without being frozen fails(File) FAILED
ContractFreezeCheckTest > the deliberate route makes the same tree green again(File) FAILED
ContractFreezeCheckTest > deleting the lock fails rather than freezing nothing(File) FAILED
ContractFreezeTest > a content change is still caught on a translated checkout() FAILED
ContractFreezeTest > editing one byte of a frozen contract is caught, naming the file() FAILED
ContractFreezeTest > an empty lock freezes nothing and says so, rather than passing() FAILED
ContractFreezeTest > the failure message says stop, and names the deliberate route out() FAILED
ContractFreezeTest > the whole frozen directory going missing is every contract removed() FAILED
ContractFreezeTest > a rename is caught from both ends() FAILED
ContractFreezeTest > a contract added to the directory is caught() FAILED
ContractFreezeTest > a contract deleted from the directory is caught() FAILED
```
### M2 — the lazy digest the ticket names: three known filenames, no set comparison

```
diff --git a/build-logic/src/main/kotlin/dev/wildware/udea/build/ContractFreeze.kt b/build-logic/src/main/kotlin/dev/wildware/udea/build/ContractFreeze.kt
index 6e0ea73..9af0f08 100644
--- a/build-logic/src/main/kotlin/dev/wildware/udea/build/ContractFreeze.kt
+++ b/build-logic/src/main/kotlin/dev/wildware/udea/build/ContractFreeze.kt
@@ -165,22 +165,8 @@ public object ContractFreeze {
                         "content has changed since it was frozen.",
                 )
             }
-        val added = (actual.keys - locked.keys).map {
-            finding(
-                CONTRACT_ADDED,
-                it,
-                "$LOCK_PATH does not freeze this file, so it is in $DIRECTORY/ without having " +
-                    "been agreed. A contract nobody froze is not frozen.",
-            )
-        }
-        val removed = (locked.keys - actual.keys).map {
-            finding(
-                CONTRACT_REMOVED,
-                it,
-                "$LOCK_PATH freezes this file and it is not in the tree. Several modules " +
-                    "independently implement what it says; deleting it does not fail, it lies.",
-            )
-        }
+        val added = emptyList<MigrationFinding>()
+        val removed = emptyList<MigrationFinding>()
         return (changed + added + removed).sortedWith(compareBy({ it.path }, { it.rule.value }))
     }
 
diff --git a/gradlew b/gradlew
old mode 100644
new mode 100755
```

```
ContractFreezeCheckTest > a contract deleted from the directory fails(File) FAILED
ContractFreezeCheckTest > deleting the whole frozen directory fails, rather than failing to configure(File) FAILED
ContractFreezeCheckTest > renaming a contract fails from both ends(File) FAILED
ContractFreezeCheckTest > a contract added without being frozen fails(File) FAILED
ContractFreezeCheckTest > deleting the lock fails rather than freezing nothing(File) FAILED
ContractFreezeTest > an empty lock freezes nothing and says so, rather than passing() FAILED
ContractFreezeTest > the failure message says stop, and names the deliberate route out() FAILED
ContractFreezeTest > the whole frozen directory going missing is every contract removed() FAILED
ContractFreezeTest > a rename is caught from both ends() FAILED
ContractFreezeTest > a contract added to the directory is caught() FAILED
ContractFreezeTest > a contract deleted from the directory is caught() FAILED
```

Note what stays **green** here: the plain "somebody edited a byte" case. That is the point of
the mutation — a gate built this way looks like it works, right up until the day a contract is
added, deleted or renamed.
### M3 — digest of the raw bytes

```
diff --git a/build-logic/src/main/kotlin/dev/wildware/udea/build/ContractFreeze.kt b/build-logic/src/main/kotlin/dev/wildware/udea/build/ContractFreeze.kt
index 6e0ea73..32266ac 100644
--- a/build-logic/src/main/kotlin/dev/wildware/udea/build/ContractFreeze.kt
+++ b/build-logic/src/main/kotlin/dev/wildware/udea/build/ContractFreeze.kt
@@ -88,7 +88,7 @@ public object ContractFreeze {
      * lines look.
      */
     public fun digest(text: String): String {
-        val normalised = text.replace("\r\n", "\n").replace("\r", "\n")
+        val normalised = text
         return MessageDigest.getInstance("SHA-256")
             .digest(normalised.toByteArray(Charsets.UTF_8))
             .joinToString("") { "%02x".format(it) }
```

```
ContractFreezeTest > a checkout that translated the line endings does not trip the gate() FAILED
```

One test, and it is issue #176's failure mode: on a Windows checkout this gate would have been
red on a **perfect** tree.
### M4 — a missing lock quietly skips

Restores the real shape of the lazy implementation: no baseline, so nothing to compare.

```
diff --git a/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaContractTasks.kt b/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaContractTasks.kt
index dc8610e..d0ecbdb 100644
--- a/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaContractTasks.kt
+++ b/build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaContractTasks.kt
@@ -84,7 +84,8 @@ public abstract class UdeaVerifyContractsTask : UdeaContractTask() {
         val root = repoRoot.get().asFile
         val actual = ContractFreeze.digestsOf(contractsDirectory.get().asFile, root)
         val lock = lockFile.singleFile
-        val locked = if (lock.isFile) ContractFreeze.parse(lock.readText()) else emptyMap()
+        if (!lock.isFile) return
+        val locked = ContractFreeze.parse(lock.readText())
 
         report.get().asFile.apply {
             parentFile.mkdirs()
```

```
ContractFreezeCheckTest > deleting the lock fails rather than freezing nothing(File) FAILED
```
### M5 — the gate exists but is not on `check`

```
diff --git a/build-logic/src/main/kotlin/udea.contract-freeze.gradle.kts b/build-logic/src/main/kotlin/udea.contract-freeze.gradle.kts
index 4454861..dca88d6 100644
--- a/build-logic/src/main/kotlin/udea.contract-freeze.gradle.kts
+++ b/build-logic/src/main/kotlin/udea.contract-freeze.gradle.kts
@@ -68,6 +68,6 @@ val udeaVerifyContracts = tasks.register<UdeaVerifyContractsTask>(ContractFreeze
     mustRunAfter(udeaWriteContractLock)
 }
 
-tasks.named("check") {
-    dependsOn(udeaVerifyContracts)
-}
+// tasks.named("check") {
+//     dependsOn(udeaVerifyContracts)
+// }
```

```
ContractFreezeCheckTest > a contract deleted from the directory fails(File) FAILED
ContractFreezeCheckTest > deleting the whole frozen directory fails, rather than failing to configure(File) FAILED
ContractFreezeCheckTest > renaming a contract fails from both ends(File) FAILED
ContractFreezeCheckTest > an edited contract fails check, naming the file and the route out(File) FAILED
ContractFreezeCheckTest > a contract added without being frozen fails(File) FAILED
ContractFreezeCheckTest > the deliberate route makes the same tree green again(File) FAILED
ContractFreezeCheckTest > an untouched tree passes, and check is what runs the gate(File) FAILED
ContractFreezeCheckTest > deleting the lock fails rather than freezing nothing(File) FAILED
```
### M6 — `AGENTS.md` stops naming the gate, and the finding that came with it

This one did not bite the first time it was run, and that is the most useful thing in this brief.

```
diff --git a/AGENTS.md b/AGENTS.md
index 118b85e..47ae941 100644
--- a/AGENTS.md
+++ b/AGENTS.md
@@ -12,7 +12,7 @@ Three documents, in order of authority:
 3. **This file** — orientation and rules. Not a tutorial, not API docs.
 
 `docs/contracts/` holds the frozen contracts. **Frozen means frozen**: if your work needs one
-to change, stop and say so. Do not change it and carry on. `./gradlew udeaVerifyContracts` fails
+to change, stop and say so. Do not change it and carry on. `./gradlew the freeze gate` fails
 the build when one of them moves — see "Frozen contracts" below for the deliberate route.
 
 ---
@@ -119,7 +119,7 @@ diff, so a misalignment does not fail — it lies.
 ### The gate on the freeze
 
 `docs/contracts.lock` holds a SHA-256 of every file in `docs/contracts/`, and
-`udeaVerifyContracts` runs on `check` — so `./gradlew build` fails if one of them has been
+`the freeze gate` runs on `check` — so `./gradlew build` fails if one of them has been
 edited, or if a contract has appeared, vanished or been renamed. It is on `check` rather than
 only in CI because these are documents a developer edits, and a rule only CI knows about is a
 rule found after the work is done.
```

```
ContractFreezeTest > AGENTS_md names the gate and the deliberate route out() FAILED
```

**First run of this mutation: zero failures**, and the reason was not the test. From that run's
log:

```
> Task :build-logic:test FROM-CACHE
```

`build-logic/build.gradle.kts` declares the outer-build files its tests read, precisely so the
task does not stay `UP-TO-DATE` "across exactly the edits it exists to notice" — its own words —
and **`AGENTS.md` was not on that list**. So the new test could not have failed for an
`AGENTS.md` reason, ever. Declaring it (one line, in the file this ticket already owns) is what
turned M6 from 0 into 1.

The same hole covered the pre-existing `AgentsMdTest`, which has read the real `AGENTS.md` since
issue #138 with the file undeclared. Nothing escaped through it, because the *task*
`udeaVerifyAgentsMd` declares `AGENTS.md` as a proper `@InputFile` — but its unit half could go
stale across the one edit it watches, and that is now closed too.

**Grepping for the class, not the instance.** The class is "a `build-logic` test that reads a
file in the outer build which `outerBuildInputs` does not declare". Grepping the test sources for
outer-tree reads (`File(repoRoot, …)`, `File("../…")`, `repoRoot.resolve(…)`) leaves these still
undeclared after my fix:

| File | Test that reads it |
|---|---|
| `gradle/libs.versions.toml` | `UdeaVersionsTest`, `GradleFixture` |
| `docs/migration/trello-map.md` | `TrelloMapTest` |
| `docs/superpowers/specs/2026-08-22-udea-ai-native-rewrite-design.md` | `TrelloMapTest` |
| `determinism-allowlist.txt` | `AllowlistParserTest`, `AuditTest` |
| `determinism-audit.md` | `AuditTest` |

Each is one line in the same list, and each belongs to another ticket's subject (the trello map,
the determinism audit, the version catalog). **I fixed only `AGENTS.md`, because my own test
needed it**, and am reporting the rest rather than widening this diff into three other tickets.
Same failure mode in every row: those gates can pass from cache across the edit they exist to
catch. It is on the issue as a comment.

---

## 2. Summary

`AGENTS.md` said `docs/contracts/` was frozen and nothing acted on it. The wire ids have
`net-protocol.lock`, the generated bytes have `expected-generated-hashes.txt`, the module arrows
have `udeaVerifyModuleGraph`, `AGENTS.md`'s own module table has `udeaVerifyAgentsMd` — and the
three documents those implementations are checked against had a reviewer's attention and nothing
else.

**What was added**

- `docs/contracts.lock` — SHA-256 of every file in `docs/contracts/`, generated by the task
  below, never by hand.
- `udeaVerifyContracts` — on `check`, so `sh gradlew build` catches an edit on a fresh clone.
  It compares the **whole directory against the whole lock**, so an edit, an addition, a
  deletion and a rename are all findings (`UDEA-FRZ-001/002/003`).
- `udeaWriteContractLock` — the one sanctioned route out, named in the failure message, in the
  lock's own header, and in `AGENTS.md`.
- `AGENTS.md` — the "Frozen contracts" section now names the gate and the route (AC4), and the
  freeze statement at the top points at it.

**Decisions, and what to change if the owner disagrees** (also on the issue as
[a comment](https://github.com/wildware-uk/Udea/issues/174#issuecomment-5486043167)):

1. **Digest, not a diff-keyed CI step.** As the issue prefers: it works on a clean clone and
   offline. No reason to prefer the other shape turned up. **No `ci.yml` change was needed** —
   `build` reaches the gate, so `dev-175`'s file was not touched.
2. **The lock lives at `docs/contracts.lock`, beside the frozen directory rather than inside
   it.** Rejected `docs/contracts/contracts.lock`: a lock inside the guarded directory must
   exclude itself from its own digest, and an excluded name inside a frozen directory is a hole
   in the freeze — anything committed under that name would be unfrozen by construction. To move
   it, change `ContractFreeze.LOCK_PATH` and re-run the write task; nothing else knows the path.
3. **The route out is a task, not `-Pudea.updateContracts=true`.** This is the one deliberate
   divergence from the issue's wording, which names the flag shape first. A `-P` flag can be
   passed to a whole `gradlew build` and re-baseline the freeze as a side effect of an ordinary
   build — the exact act the gate exists to refuse. A task name cannot be triggered by accident,
   and it is the shape `udeaWriteProtocolLock` already uses for this repository's other frozen
   agreement. To switch to a flag, register a system property on the verify task the way
   `udea-codegen/build.gradle.kts` does for `udea.updateGeneratedHashes`.
4. **Newline-normalised digest.** No root `.gitattributes` exists and Git for Windows checks out
   `core.autocrlf=true`, so a raw-byte digest would be red on a perfect tree there — issue #176's
   failure, in advance. Every content byte still reaches the digest (M3 pins both halves).
5. **A `-Pudea.render.requireGl` run was not done, deliberately.** This ticket touches
   `build-logic`, `AGENTS.md` and a new lock file; it opens no GL context and changes nothing
   `udeaGlTest` or `udeaAgentGlTest` covers. Saying a green `build` is evidence about GL would be
   the error this project keeps writing down, so: no GL surface touched, no GL run claimed.

**A surprise worth the owner's eye.** `AGENTS.md` calls all three documents frozen. Their own
status lines disagree: only `replicator.md` says `**Status:** frozen (Phase 0)`;
`agent-tools.md` says `**Status:** active (Phase 1 wave 1)` and `asset-index.md` says
`**Status:** proposed (Phase 2, issue #40). Frozen once pass 5 (issue #90) writes it.` The lock
freezes all three, because that is what `AGENTS.md` says and what the issue asks for. The
consequence is not that `asset-index.md` can never be finished — it is that finishing it costs
one deliberate command and shows up in the diff. Either the status lines are stale or the blanket
claim is; that is a call for the owner, and **nothing in this branch resolves it**.

**Not changed:** no word inside `docs/contracts/replicator.md`, `agent-tools.md` or
`asset-index.md`. `git diff origin/example -- docs/contracts/` is empty.

**Two things a reviewer working from the closed reject list may want pre-empted.**

- *"Generated code produced by string concatenation."* `ContractFreeze.render` builds
  `docs/contracts.lock` with `buildString`. That is a **data** file, not code — section 1's rule
  is "generated *code* is emitted with KotlinPoet", and the two lock files this repository
  already has are written exactly this way (`ProtocolLock`, and
  `GeneratedFileDeterminismTest.write` for `expected-generated-hashes.txt`). No Kotlin source is
  emitted anywhere in this change.
- *"A bare `String` for a domain concept."* Digests and repo-relative paths are `String`s, which
  is the established shape in this very package: `LedgerRow.sourceHash` is a `String?` and
  `SourceFile.path` a `String`, and `MigrationFinding.path` — which my findings produce — is
  typed that way already. Introducing a value class for a digest here would make this gate the
  only one in `build-logic` that does. The value class that *is* used is `RuleId`, as the other
  gates use it.

---

## 3. `sh gradlew build`

**Green at the branch head.** It takes two invocations on this box, and the first is reported here
rather than quietly dropped, because the reason it is red is worth stating precisely — and because
it came out the same way four separate times, with a different subset failing each time. That is
the tell for the box rather than the branch.

The box was shared throughout. `ps -eo pcpu,pid --sort=-pcpu` during the red run, with the pid
checked through `/proc/<pid>/cmdline` rather than guessed from its magnitude:

- pid **190190**, 550% CPU — `.../java -Dmegamerge.fps=60 ... -Dmegamerge.scenario=all`, i.e.
  `melon-merge`'s scenario suite;
- earlier in the session, pid **227365**, 187% CPU —
  `.../java -Dorg.gradle.internal.worker.tmpdir=/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a5773b1d0f90f1f83/udea-core/build/tmp/udeaSnaps...`,
  i.e. **another Udea agent's** build in a different worktree.

**Invocation 1 — red on one wall-clock latency budget, which is issue #175's subject and not this
branch's.** Spliced from `scratchpad/build-c473be8.log`:

```
    graph deserialisation: best=8.370133ms median=9.496785ms over 2000 assets (budget 15ms)
```
```
DaemonLatencyBudgetTest > a warm reload of one script decides inside the edit-to-observe budget() STANDARD_OUT
    warm reload decision: median 543ms over 4 samples [704, 543, 540, 331]

DaemonLatencyBudgetTest > a warm reload of one script decides inside the edit-to-observe budget() FAILED
```
```
> Task :udea-assets-compiler:udeaDaemonBudget FAILED
BUILD FAILED in 2m 32s
175 actionable tasks: 119 executed, 56 up-to-date
```

**The budget re-run alone**, `sh gradlew :udea-assets-compiler:udeaDaemonBudget --rerun`
(`scratchpad/daemonbudget-solo2.log`):

```
    warm reload decision: median 393ms over 4 samples [492, 337, 314, 393]
    warm validate of one script: median 208ms over 4 samples [24, 208, 244, 190]
BUILD SUCCESSFUL in 15s
```

543ms inside a loaded parallel build against the edit-to-observe budget, 393ms alone with
`melon-merge` still running — and 156ms/117ms in an earlier solo run when the box was quieter
(`scratchpad/daemonbudget-solo.log`). My branch touches neither `:udea-assets-compiler` nor
anything it compiles.

**Invocation 2 — green** (`scratchpad/build-c473be8-2.log`, same tree, nothing changed between
them):

```
BUILD SUCCESSFUL in 1m 11s
207 actionable tasks: 26 executed, 181 up-to-date
```

Stated plainly rather than left to inference: in that green run `udeaVerifyContracts` is
`UP-TO-DATE`, because it executed and passed on the identical tree in an earlier invocation, and
`udeaDaemonBudget` is `UP-TO-DATE` because the solo `--rerun` above had just executed it. Neither
is being passed off as a fresh execution.

Two things do show the freeze gate executing rather than being restored from cache, and both are
below in §5: `scratchpad/ac1-red.log`, where a one-byte edit makes this same `sh gradlew build`
fail in 6 seconds with the configuration cache **reused**; and `scratchpad/ac2-green.log`, where
line 20 is `> Task :udeaVerifyContracts` with no `UP-TO-DATE` after the re-baseline.

The identical pattern appeared three more times earlier in the day, on states of this branch that
were later amended away (so no SHA of theirs still exists to cite): `scratchpad/build-green.log`
red on `udeaDaemonBudget` **and** `udeaPackGate` at load 24.58, `scratchpad/budgets-solo.log` green
on both alone (`warm validate ... median 125ms`, `graph deserialisation ... best=5.246168ms
median=7.733099ms`), `scratchpad/build-green2.log` green; then `scratchpad/build-head.log` red on
`udeaDaemonBudget` only, `scratchpad/daemonbudget-solo.log` green alone,
`scratchpad/build-head2.log` green. Four reds, three different subsets of the same five wall-clock
gates, and not one of them ever red alone.

**The GL tests were not run, deliberately.** `-Pudea.render.requireGl` defaults to `false` and
`$DISPLAY` is empty here, so `udeaGlTest` and `udeaAgentGlTest` skipped inside every `build` above
— and a skip is not evidence. This ticket adds a Gradle convention plugin, two tasks, a lock file
and one section of `AGENTS.md`. It opens no GL context, touches no `udea-render` source, and
changes nothing either GL suite covers, so there is nothing an `xvfb-run` could say about it. I am
not claiming a green `build` as evidence about GL.

---

## 4. There is nothing to photograph

This is a build-logic and repository-rule change. It draws nothing, opens no GL context, and
changes no pixel of `moba`. A screenshot of a game the ticket does not touch would be an
irrelevant image, which is worse than none — so the evidence here is executed transcripts, and
every one of them is spliced from a file still on disk under
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/01ec1be7-305f-4987-ab53-69f61b72d43e/scratchpad/`
(`M0`–`M6` as `.log` and `.failed` pairs, `red-no-lock.log`, `build-c473be8.log`,
`daemonbudget-solo2.log`, `build-c473be8-2.log`, `ac1-red.log`, `ac2-write.log`, `ac2-lock.diff`,
`ac2-green.log`, `ac2-idempotent.log`, and the earlier-state runs `build-green.log`,
`budgets-solo.log`, `build-green2.log`, `build-head.log`, `daemonbudget-solo.log`,
`build-head2.log`). Nothing was copied to the gallery, and no image filenames
are claimed.

---

## 5. The acceptance criteria, one by one

### AC1 — editing a byte of `docs/contracts/replicator.md` makes `sh gradlew build` fail, naming the file and the sanctioned way to change it deliberately

Done on the real tree: `printf 'x' >> docs/contracts/replicator.md`, then `sh gradlew build`.
Spliced from `scratchpad/ac1-red.log`:

```
FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':udeaVerifyContracts'.
> udeaVerifyContracts found 1 change(s) to the frozen contracts:
  
    docs/contracts/replicator.md:1:1: error: [UDEA-FRZ-001] this frozen contract no longer matches the digest in docs/contracts.lock, so its content has changed since it was frozen.
  
  docs/contracts/ holds the frozen contracts. Frozen means frozen: if your work
  needs one to change, stop and say so. Do not change it and carry on.
  
  If the change is agreed, the deliberate route is:
      gradlew udeaWriteContractLock
  then commit docs/contracts.lock in the same change. Review the diff: it is the contract.

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights.
> Get more help at https://help.gradle.org.

BUILD FAILED in 6s
73 actionable tasks: 1 executed, 72 up-to-date
Configuration cache entry reused.
```

Note the last line: the configuration cache was **reused** and the gate still ran and still
failed, which is the answer to "could this go stale?" — its declared inputs are the directory and
the lock, so a changed byte re-runs it.

The byte was reverted afterwards; `git diff origin/example -- docs/contracts/` is empty, and
`sha256sum docs/contracts/replicator.md` back on the committed file gives
`31a0567934f4dcf1979540af4d7f9a7a8b8212a1ae6298616e4d1e568b31d08a`, which is the digest the
committed `docs/contracts.lock` holds.

### AC2 — the deliberate route works: run it, commit the result, show the build green again

`sh gradlew udeaWriteContractLock` on the still-edited tree (`scratchpad/ac2-write.log`):

```
> Task :udeaWriteContractLock
wrote /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3feddd0686493e0b/docs/contracts.lock freezing 3 contract(s). Review the diff: it is the contract.
BUILD SUCCESSFUL in 7s
```

and the diff it produced — one line, which is what "review the diff" means here:

```
diff --git a/docs/contracts.lock b/docs/contracts.lock
index 02813d3..017ed30 100644
--- a/docs/contracts.lock
+++ b/docs/contracts.lock
@@ -9,4 +9,4 @@
 # it is the contract.
 docs/contracts/agent-tools.md  2b04fa03afd60558bdc508269178bd6207a5d80c9675554bd9c3b22b57c8b532
 docs/contracts/asset-index.md  714837e74df1b1e741595ee46a1909c9a485e10800d1aab23dacbf76b95825a0
-docs/contracts/replicator.md  31a0567934f4dcf1979540af4d7f9a7a8b8212a1ae6298616e4d1e568b31d08a
+docs/contracts/replicator.md  96e60833630249209618cbf809d264411f6a7f7b2455e5baf99d3e85b5d7f81a
```

then `sh gradlew build` on that tree (`scratchpad/ac2-green.log`), where line 20 is the gate
executing rather than sitting `UP-TO-DATE`:

```
> Task :udeaVerifyContracts
```
```
BUILD SUCCESSFUL in 1s
207 actionable tasks: 3 executed, 204 up-to-date
```

**One deviation from the criterion's wording, and it is deliberate:** the criterion says
"commit the result". The result being committed there is *an edited frozen contract*, which this
ticket is explicitly out of scope for and forbidden from doing (the issue's own "Out of scope":
"Changing anything *in* the three contracts") — so the round trip was run and then reverted rather
than committed. The committed-and-green half is covered twice over:

- `ContractFreezeCheckTest > the deliberate route makes the same tree green again` does the whole
  cycle in a real Gradle build (edit → `check` fails → `udeaWriteContractLock` → `check` passes)
  on a fixture where committing an edited contract is harmless. M1 and M5 both take it red.
- On the real tree, running `udeaWriteContractLock` again after reverting the byte reproduced the
  committed lock **byte for byte** — `git status` came back showing nothing but the untracked
  `BRIEF-174.md` (`scratchpad/ac2-idempotent.log`). So the lock in this branch is exactly what the
  sanctioned route emits, and running the route twice is a no-op rather than a churn.

### AC3 — the gate is on `check` (or something `build` reaches), not only in `ci.yml`

`build-logic/src/main/kotlin/udea.contract-freeze.gradle.kts` ends:

```kotlin
tasks.named("check") {
    dependsOn(udeaVerifyContracts)
}
```

applied at the root in `build.gradle.kts` alongside `udea.migration-check` and
`udea.determinism-check`. Proved three ways rather than asserted:

- AC1 above is a `sh gradlew build` failing on a fresh edit — nothing but `check` invoked it.
- `ContractFreezeCheckTest > an untouched tree passes, and check is what runs the gate` asserts
  `result.task(":udeaVerifyContracts")?.outcome == SUCCESS` from a run of `check` alone.
- M5 comments the wiring out and takes seven TestKit tests red.

The TestKit assertions address the task as `":${ContractFreeze.VERIFY_TASK}"` rather than as a
literal, so the constant, the registered task and the name in the failure message are one string.

**`.github/workflows/ci.yml` was not touched** (`dev-175` owns it this wave). It needed no
change: CI runs `build`, and `build` reaches `check`.

### AC4 — `AGENTS.md`'s "Frozen contracts" section names the gate

Added under that section, and a pointer added to the freeze statement at the top of the file:

```
### The gate on the freeze

`docs/contracts.lock` holds a SHA-256 of every file in `docs/contracts/`, and
`udeaVerifyContracts` runs on `check` — so `./gradlew build` fails if one of them has been
edited, or if a contract has appeared, vanished or been renamed. ...
```

`sh gradlew udeaVerifyAgentsMd` green after the edit (it gates the module table and the nine spec
section 5 contract names, neither of which moved).

Pinned by a test rather than by this paragraph:
`ContractFreezeTest > AGENTS_md names the gate and the deliberate route out` asserts that the
brief contains `ContractFreeze.VERIFY_TASK`, `WRITE_TASK` and `LOCK_PATH` — and those are the
same constants the convention plugin registers the tasks by
(`tasks.register<UdeaVerifyContractsTask>(ContractFreeze.VERIFY_TASK)`), so renaming a task
cannot leave `AGENTS.md` describing a command that does not exist. M6 takes it red.

### The cases a lazy digest misses, which the issue asks to be decided and stated

| Case | What the gate does | Proved by |
|---|---|---|
| A file **added** to `docs/contracts/` | `UDEA-FRZ-002`, build fails | `ContractFreezeCheckTest > a contract added without being frozen fails`; M1, M2 |
| A file **deleted** | `UDEA-FRZ-003`, build fails | `... a contract deleted from the directory fails`; M1, M2 |
| A **rename** | both at once — `002` for the new name, `003` for the old | `... renaming a contract fails from both ends`; M1, M2 |
| The **lock itself deleted** | every contract reported unfrozen, build fails | `... deleting the lock fails rather than freezing nothing`; M4 |
| The **whole directory deleted** | every contract reported removed, build fails — not a Gradle validation error about a missing input | `... deleting the whole frozen directory fails, rather than failing to configure`; `... the whole frozen directory going missing is every contract removed`; M1, M2 |
| A contract filed in a **new subdirectory** | frozen too — the walk is recursive, not a listing of the top level | `ContractFreeze.digestsOf` KDoc; the `fileTree(...)` input in the convention plugin |

The reasoning, in one line: an empty lock is a specific state, not a neutral one, so the gate
compares a set against a set rather than iterating the rows it happens to have.

### The failing test, seen red before it was made to pass

The first run of `ContractFreezeTest` was against a tree where nothing froze the contracts —
which is `origin/example`'s state. Reproduced and saved (`scratchpad/red-no-lock.log`, produced
by moving `docs/contracts.lock` aside and running the evidence command):

```
ContractFreezeTest > a content change is still caught on a translated checkout() FAILED
ContractFreezeTest > editing one byte of a frozen contract is caught, naming the file() FAILED
ContractFreezeTest > the lock freezes every contract in the directory, by name() FAILED
ContractFreezeTest > findings are reported in a stable order() FAILED
ContractFreezeTest > the committed lock matches the committed contracts() FAILED
ContractFreezeTest > a checkout that translated the line endings does not trip the gate() FAILED
ContractFreezeTest > a rename is caught from both ends() FAILED
ContractFreezeTest > a contract added to the directory is caught() FAILED
ContractFreezeTest > a contract deleted from the directory is caught() FAILED
ContractFreezeTest > a clean tree produces no message at all() FAILED
17 tests completed, 10 failed
BUILD FAILED in 1s
```

Ten of seventeen, all on `java.io.FileNotFoundException` for `docs/contracts.lock` — there was
nothing to compare against, because nothing had ever kept one.

---

## 6. What I did not exercise

Stated rather than left for the reviewer to find:

- **A second checkout on Windows.** The CRLF behaviour is proved by translating the bytes in the
  test the way a checkout would (`asCrlf()`, the shape `AgentsMdTest` established for #176), not
  by a Windows machine. That is a reproduction of the input, not of the platform.
- **`udeaWriteContractLock` under a merge conflict in the lock.** Same shape as
  `net-protocol.lock`: two branches that both re-baseline merge textually and produce a lock
  agreeing with neither, and the answer is to re-run the task in the merged tree. There is no
  test for it; the write task being idempotent (shown in AC2) is what makes the re-run trustworthy.
- **A contract whose bytes are not UTF-8 text.** `digestsOf` reads with `readText()`. Every file
  in `docs/contracts/` is UTF-8 markdown (`file` reports "Unicode text, UTF-8 text" for all
  three). A binary file dropped into that directory would still be *detected* — its digest is of
  whatever `readText()` produced, and adding it fails as `UDEA-FRZ-002` regardless — but the
  digest of such a file is not byte-exact. If contracts ever stop being text, `digest` should
  take a `ByteArray`.
- **The three `check`-adjacent gates outside `check`** (`runUdpProof`, `runLaneShot`, the
  `udeaVerify*` trio) beyond `udeaVerifyAgentsMd`, `udeaVerifyModuleGraph` and
  `udeaVerifyNoLegacyDependencies`, which I ran and which are green. `:moba:runUdpProof` is red on
  `origin/example` and this branch does not touch it.

**Grepping for the class rather than the instance.** The class here is "a rule this repository
states and nothing checks". The only other document in the tree that declares itself frozen is
`docs/contracts/replicator.md` (`grep -rn '^\*\*Status:\*\* frozen' docs/` returns exactly that
one line), and it is inside the directory this gate now covers. `docs/home.md` line 18 describes
the replication contract as frozen, which is prose about a file the gate covers, not a second
unguarded subject. Extending the idea to `docs/engineering-standards.md` and the spec is
explicitly out of scope in the issue — those are living documents. **Nothing else found.**

---

## 7. Regenerated files

**None.** `udea-codegen/net-protocol.lock` and
`udea-codegen/src/test/resources/expected-generated-hashes.txt` are untouched — no replicated
component was added or removed, and `git diff origin/example` shows neither file. `docs/contracts.lock`
is new, and is generated by `udeaWriteContractLock`, never edited by hand.
