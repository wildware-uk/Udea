# BRIEF — issue #274: a game outside the engine repository can set its component id space

SHA: `686365e` — the commit carrying the change.

This file lands in the commit on top of it, so the branch tip is one ahead of the SHA above. A
file cannot state its own commit's hash, and writing a hash here that turns out to be the
previous tip is the kind of true-but-misleading line this brief is otherwise about. Both are on
the branch: `git log --oneline origin/master..issue-274-net-components` shows the pair, and the
tip is what a reviewer should check out.

Branch `issue-274-net-components`, branched from `origin/master` at `2e7aed4`. Every comparison
below is against **that merge base**, not against `origin/master`'s current tip. The tip has moved
since (another branch has merged), and diffing against it lists that branch's files as though they
were mine. At the time of writing this branch is **2 ahead of and 13 behind** `origin/master`,
measured after a `git fetch`.

---

## Evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem ANDROID_HOME=$HOME/Android/Sdk \
  sh scripts/outside-game-proof.sh
```

It publishes the engine, copies `templates/new-game` to a directory **outside this repository**,
builds it against the published artifacts, runs it, and proves its inherited gates still fail. It
gained three legs for this ticket: that the game hand-wires nothing, that a name inserted ahead of
the game's moves its component's id, and the whole round trip from no registry through the named
failure to green.

A unit test that `UdeaNetComponents` parses a file would prove nothing here. The defect was never
that the rule was wrong; it is that **nothing published ever called it**, and only a build from
outside this tree can see that.

**It goes red when the feature is reverted, and this was executed twice.** With
`applyNetComponentsToKsp()` commented out of `udea.kotlin-base.gradle.kts` (M1 below), the script
exits **1** — earlier than predicted and for a stronger reason: the *engine itself* no longer
compiles, so leg 0 fails before the game is ever built.

The first M1 run's leg transcript was **overwritten** by the green run that followed it, and a
`grep` of this box for the failing line found it only in another ticket's scratchpad — which is
not mine and is not evidence about this branch. So rather than demote the block to prose I ran
M1 again and kept the artefact. What follows is spliced from that preserved copy,
`scratchpad/d274/m1-again-reports/publish.log`, lines **838-839**, consecutive and in order, with
the long single line 839 wrapped **only** at the marked point:

```
> Task :udea-core:kspCommonMainKotlinMetadata FAILED
e: [ksp] this module emits a wire protocol (udea.moduleName is 'Core') but the build did not set
udea.projectComponents, so its component type ids would be numbered from its own 8 component(s)
starting at 0. [... one sentence about what two peers then do ...] Run `gradlew
udeaWriteNetComponents` to put this module's components into the project's 'net-components.lock',
and review the diff: a name's position in that file is its component type id on the wire. This
module compiles dev.wildware.udea.core.physics.Box, [... six more ...],
dev.wildware.udea.core.spatial.Transform3D. The build reads that file from the root project and
passes it in; `udeaNetComponents { registry = ... }` in the root build script is how to keep it
somewhere else.
```

`scratchpad/d274/m1-again.marker` reads `EXIT=1`, then `REVERTED`, then the exit of the green
re-run that put the worktree's report directory back.

### What it settles, and what it does not

It settles: **a game in its own repository, applying the published conventions, compiles a
`@Replicated` component with nothing about the id space in any of its build scripts** — and
recovers from having no registry at all using a task the failure names.

It does **not** settle anything about publishing. `outside-game-proof.sh` resolves through
`mavenLocal()`, which enforces no namespace rule. That is the trap this repository has already
walked into once: the script was green while the real publish to Central returned 403 on every
convention plugin, because the rule that refused them exists only on Central. This ticket adds no
plugin id and no new coordinate, so nothing here is a new claim about publishing — but the green
is about wiring, not about Central.

---

## Summary

`UdeaNetComponents` has held the parse and the sorting since Phase 0, and **nothing published ever
called it**. Every module that needed `udea.projectComponents` wrote the same eight lines into its
own build script, and those scripts are all inside this repository — so a game applying the
published conventions had no way to pass the list at all. Its first `@Replicated` component failed
with a diagnostic ending "let the build pass the list in", and there was no surface that did.
robot-game re-implemented the parse *and the sorting*, which is the part that decides what a
component's id is.

**What changed.**

- `udea.kotlin-base` — the convention every Udea module and every module of a game on the
  published conventions is on — reads `net-components.lock` from the root project, parses it with
  `UdeaNetComponents`, and hands the sorted list to KSP. The five copies of that block are gone
  from `udea-core`, `udea-nav`, `udea-codegen`, `moba:game` and `hollow:game`.
- `udeaNetComponents { registry = ... }` in the **root** build script points at a different file.
- `udeaWriteNetComponents` writes the registry from what the build compiled, and works on the
  build that just failed for want of it.
- `templates/new-game`'s `Rover` is `@Replicated` with `@Net` on each field, and the template
  carries a `net-components.lock` of its own.

**Decisions I had to make.**

1. **The block is `udeaNetComponents { registry = ... }`, not the `udea { netComponents = ... }`
   the issue sketched.** `dev.wildware.udea.assets` already does
   `project.extensions.create("udea", UdeaAssetsExtension::class.java)`, and `hollow/game` writes
   `udea { assetRoots.from("assets") }` today. A multi-project game escapes the collision because
   the new block is on the root and the assets plugin goes on a module; a **one-project game**
   applying both would not — and a one-project game outside this repository is the shape this
   issue is about. The name is public API, so overturning it later is a break; recorded as a
   decision on the issue with the files to change and the second break that keeping `udea` would
   cost: https://github.com/wildware-uk/Udea/issues/274#issuecomment-5753330057
2. **The KSP extension is reached by name and `arg(String, String)` invoked reflectively.**
   `build-logic` deliberately does not put the KSP Gradle plugin on its classpath
   (`UdeaModuleRegistry` says why: every module applies it by id at the catalog's version, and a
   second copy here would be a version the catalog does not govern), so there is no `KspExtension`
   type to configure. A missing `arg` is a named `GradleException`, not a dropped option —
   silently dropping it would put every module back to numbering from its own symbols with a
   green build. M1's sixth test is that failure path.
3. **`udeaWriteNetComponents` depends on no task, and declares no inputs.** The build it exists to
   rescue is the one that just failed, so a `dependsOn` on the KSP tasks would make it unreachable
   in the only state anybody needs it in. The manifests are another task's declared outputs, so
   declaring them as inputs would make Gradle demand that dependency or refuse the build. It reads
   what the last build left and prints every manifest it read.
4. **The task only ever adds.** A name may legitimately be listed before its component exists —
   the engine's own lock header says so — and a module that failed to compile reports nothing at
   all, so dropping a name because this build did not see it would renumber the wire *because of a
   build failure*. Removing a name stays a hand edit.
5. **A hole I closed on the way, and the shape of the fix.** See "Generated resources" below.

**What the issue left open that I ruled on:** nothing merges a game's id space with the engine's.
A game's components are numbered in the game's build from 0; the engine's were numbered in the
engine's build and those ids are already inside the published jars. That is out of scope for #274
and it is now stated in `docs/new-game.md` with *where a collision would be caught* —
`ComponentRegistry`'s constructor refuses two types with one id, and a game builds one of those
when it wires replication, so a game that has not wired replication yet would get no warning.

---

## `sh gradlew build` — the real output

Run detached, exit code read off the marker file, never inferred from a process list.

```
$ cat scratchpad/d274/final-build.marker
EXIT=0
DONE

$ tail -2 scratchpad/d274/final-build.log
BUILD SUCCESSFUL in 1m 34s
1122 actionable tasks: 225 executed, 56 from cache, 841 up-to-date
```

`BUILD SUCCESSFUL` is at line **1864** of that log and there is exactly one of it — `grep -n
"^BUILD "` returns that one line, so the marker's `EXIT=0` and the log's verdict are two
independent readings of the same run rather than one read twice.

The command inside that script is the one the contract specifies, with no exclusions:

```
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew build --continue --no-configuration-cache --max-workers=4 --console=plain
```

**That run used the build cache** (`56 from cache`), so no test *count* is quoted from it. Every
test figure in the mutation table below comes from a run with `--no-build-cache`, after deleting
the result directories, and each run printed the age of the oldest report it read (`0s`–`3s`) so a
restored report from an earlier run could not be mistaken for an executed one.

The first attempt at this build failed, and it is worth recording because it is mine:
`:udea-codegen:compileTestKotlin` red on `warnings found and -Werror specified` —
`Unnecessary non-null assertion (!!) on a non-null receiver of type 'File'` at
`GeneratedFileDeterminismTest.kt:194:29`. Replaced with `kotlin.test.assertNotNull`.

**This ticket touches no GL.** Nothing in it opens a context, so there is no xvfb run to report and
no claim about GL is made.

---

## The locks: nothing regenerated, and that is checked by name

`templates/new-game` is **not** a project of this build: `settings.gradle.kts` includes `udea-*`,
`moba:*` and `hollow:*` and nothing under `templates/`, and the template carries a
`settings.gradle.kts` and `gradle.properties` of its own. Its `@Replicated` component is compiled
only by `scripts/outside-game-proof.sh`, in a copy outside this tree.

The processor change writes one extra generated **resource**, and the hash fixture is keyed on
`.kt` files alone. Confirmed by the lead reading the filter rather than taking the claim:

```
udea-codegen/src/test/kotlin/dev/wildware/udea/codegen/GeneratedSources.kt:25
    .filter { it.isFile && it.extension == "kt" }
```

**Checked by name rather than by reading a tidy tree.** Everything this branch changes, against
its own merge base:

```
$ git diff --name-only 2e7aed4 HEAD
AGENTS.md
BRIEF.md
build-logic/build.gradle.kts
build-logic/src/main/kotlin/dev.wildware.udea.game-gates.gradle.kts
build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaNetComponents.kt
build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaNetComponentsWiring.kt
build-logic/src/main/kotlin/udea.kotlin-base.gradle.kts
build-logic/src/test/kotlin/dev/wildware/udea/build/NetComponentsWiringTest.kt
build-logic/src/test/kotlin/dev/wildware/udea/build/UdeaNetComponentsTest.kt
docs/new-game.md
docs/wiki/Tutorial-Make-a-Game.md
hollow/game/build.gradle.kts
moba/game/build.gradle.kts
scripts/outside-game-proof.sh
templates/new-game/game/src/main/kotlin/com/example/newgame/sim/Rover.kt
templates/new-game/net-components.lock
udea-codegen/build.gradle.kts
udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/UdeaSymbolProcessor.kt
udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/protocol/NetComponentsManifest.kt
udea-codegen/src/test/kotlin/dev/wildware/udea/codegen/GeneratedFileDeterminismTest.kt
udea-codegen/src/test/kotlin/dev/wildware/udea/codegen/GeneratedSources.kt
udea-codegen/src/test/kotlin/dev/wildware/udea/codegen/IncrementalProcessingTest.kt
udea-codegen/src/test/kotlin/dev/wildware/udea/codegen/ProjectIdSpaceTest.kt
udea-core/build.gradle.kts
udea-nav/build.gradle.kts
```

**Named and absent:** no `net-protocol.lock` of any module,
no `udea-codegen/src/test/resources/expected-generated-hashes.txt`, no `docs/contracts.lock`, and
no `.udearep` replay fixture.

**One `net-components.lock` is in that list and it is not the engine's.**
`templates/new-game/net-components.lock` is a **new file** belonging to the template, a project
this build does not include; the engine's own `net-components.lock` at the repository root is
**not** in the listing. That distinction is worth stating rather than leaving to a scoped grep,
because a reader running

```
git diff --name-only 2e7aed4 HEAD | grep net-components.lock
```

gets one hit and would be right to ask. The root lock is also the one to look at twice, because
`udeaWriteNetComponents` was **run against it on purpose** — see the control below — and it came
back byte-identical.

`git status --porcelain` after the final build, the final proof and the write-lock control names
`BRIEF.md` and nothing else.

---

## Regenerated files

**None.** And that is a measurement rather than an absence, because the task that would have
regenerated one was run deliberately:

Spliced from `scratchpad/d274/writelock.log`, one consecutive run of seven lines, then — after
an elided "Problems report" line — the last two. The worktree's absolute path prefix is elided
at the marked point on each `read` line; nothing else is:

```
> Task :udeaWriteNetComponents
read .../hollow/game/build/generated/ksp/jvm/jvmMain/resources/udea/Hollow-net-components.txt
read .../moba/game/build/generated/ksp/jvm/jvmMain/resources/udea/Moba-net-components.txt
read .../udea-codegen/build/generated/ksp/test/resources/udea/CodegenFixtures-net-components.txt
read .../udea-core/build/generated/ksp/metadata/commonMain/resources/udea/Core-net-components.txt
read .../udea-nav/build/generated/ksp/metadata/commonMain/resources/udea/Nav-net-components.txt
.../net-components.lock already named every component this build compiled
[...]
BUILD SUCCESSFUL in 4s
10 actionable tasks: 1 executed, 9 up-to-date
```

**This is the strongest control in the ticket.** The names five modules reported are exactly the
names a human reviewed in `net-components.lock` — if the discovery were incomplete or the merge
mis-ordered, this would have rewritten the file. Measured three ways, all on this run:

- the script copied the lock before and after and `diff`ed them: `scratchpad/d274/writelock.marker`
  reads `EXIT=0`, `DIFF-EXIT=0`, and `scratchpad/d274/lock-diff.txt` is empty;
- `git status --porcelain` afterwards names `BRIEF.md` and nothing else, so the lock is unmodified
  in the index too;
- the five manifests concatenated and sorted are **33** lines, the lock's own names sorted are
  **33** lines, and `diff` between them exits 0.

The third is the one that says the discovery is complete rather than merely harmless: an empty
discovery would also leave the file alone. The same comparison is pinned as a unit test,
`UdeaNetComponentsTest.rewriting this repository's own registry changes nothing in it`, so it keeps
running after this branch, and **M4 turns it green while turning four others red** — which is what
shows it is not the one carrying the "kept" case.

---

## Generated resources: a hole this ticket opened and closed

Read the filter above the other way round: **a generated resource is outside every determinism
check this build has.** `net-protocol.lock`, the tool manifest and now the component manifest are
generated, packaged into jars, and compared to nothing.

That was tolerable while no resource's correctness depended on its order. The component manifest's
does: `udeaWriteNetComponents` folds it into `net-components.lock`, where a name's position **is**
its `ComponentTypeId`. A later change emitting it in visit order rather than sorted order would
mint different ids on two machines from identical sources, with `protoHash` reporting agreement —
the exact failure the `udea.projectComponents` diagnostic exists to prevent, arriving by the back
door.

**What I did:** `GeneratedSources.resources` — *every* generated resource, not only mine — now goes
through a two-run byte-identity comparison, an order assertion against a source whose declaration
order disagrees with sorted order, an assertion over the artefact `kspTestKotlin` really wrote, and
the machine-specific scan.

**What I did not do, and what it costs.** Widening `GeneratedSources.files` would have covered the
same resources in one line *and* hashed them, which is stronger. But `relativePaths()` is what `the
hash file covers every generated file` compares against, so widening adds three rows to
`expected-generated-hashes.txt` — a regeneration of a checked-in fixture another branch owns this
wave, which is the one merge nobody can resolve as text. Nothing is narrower about *which*
resources are protected; what is given up is a **checked-in** hash. Two runs agreeing says a
resource is consistent; a checked-in hash says it is the resource somebody reviewed, and a resource
that changes for a reason nobody intended is consistently wrong on both runs and passes everything
here.

**The follow-up, stated here so it outlives this branch.** Once the lock regeneration this wave
owns has merged, change `GeneratedSources.files` to take every generated file rather than only
`*.kt`, and regenerate `expected-generated-hashes.txt`, which gains three rows for
`udea-codegen`'s own generated resources. It is one line plus a regeneration, and what it buys is
the half this branch could not: a **reviewed, checked-in** hash for each generated resource,
which a two-run comparison cannot give. I am not filing it as an issue — the owner's rule — so it
is in this brief, in my report to the lead, and nowhere else; if it is not picked up there it
will be lost, and that is worth saying plainly rather than implying somebody has it.

**No narrowing was needed, and that is measured.** The widened scan reads
`udea/CodegenFixtures-agent-tools.json`, which declares the JSON Schema dialect URL, and the
baseline run is green over it (`base-cg`: `reports=35 cases=295 failing=0`, `EXIT=0`) — the
`MACHINE_PATH` lookbehind was written for exactly that shape and holds. So there is no exclusion
list. M9 is what says the scan is not merely silent: a machine path planted in the manifest turns
it red. The three resources the scan covers in `udea-codegen`:

```
udea-codegen/build/generated/ksp/test/resources/udea/CodegenFixtures-agent-tools.json
udea-codegen/build/generated/ksp/test/resources/udea/CodegenFixtures-net-components.txt
udea-codegen/build/generated/ksp/test/resources/udea/CodegenFixtures-net-protocol.lock
```

---

## Mutation table

**Every prediction in the "predicted" column was written and sent to the lead before a single JVM
ran on this branch**, while the build hold was still in place. They are reproduced here unchanged,
beside what was measured, including where they disagree.

Method: apply the mutation, `git diff` it, delete the test-result directories, run with
`--no-build-cache`, read the failing names **out of the JUnit XML with an XML parser**, revert.
Each run printed `reports=N cases=N failing=N` and the age of the oldest report it read; every
row's oldest report was 0-3 s old, so no row read a restored XML from an earlier run.

The two baselines, unmutated, measured the same way. Spliced from `scratchpad/d274/chain2.runner`,
its first three lines:

```
### baseline, codegen
oldest report 3s old, newest 3s old
reports=35 cases=295 failing=0
```

and `base-bl`, the build-logic suite, `EXIT=0` with `reports=44 failing=0`. Its **392** case count
is not in that baseline's own line — the baseline ran before the harness started printing `cases=`
— so it is taken from the four build-logic mutation rows, each of which read `reports=44 cases=392`
off the same task. A reader who wants the baseline's own case count should re-run it rather than
take that number from here.

| # | Mutation | Predicted red | Measured red | Verdict |
|---|---|---|---|---|
| M1 | `applyNetComponentsToKsp()` commented out | 4 of 6 `NetComponentsWiringTest`; evidence command red at leg **1** | **exactly those 4**; evidence command red at leg **0**, `EXIT=1` | names matched; the proof failed **earlier** than predicted — see below |
| M2 | the sortedness check deleted from `parse` | `an out-of-order registry is refused`, `an unsorted registry is sorted by the rewrite`, `a registry that is not an id space fails the build` | **exactly those 3** | matched |
| M3 | the manifest written **after** the id-space refusal | `the failing run still reports what it compiled`, `a module emitting a protocol without the project list is refused` | **exactly those 2** | matched |
| M4 | `merge` drops names the build did not report | `a name the build did not see this time is kept` (1) | **4** | **missed — see below** |
| M5 | `NetComponentsManifest.FILE_NAME` → `net-components.list` | the mirror test (1) | **1** in build-logic, **5** in udea-codegen | **under-counted — see below** |
| M6 | `netComponentsFile()` ignores the extension | `the root build script can put the registry somewhere else` | **exactly that 1** | matched |
| M7 | `render`'s `.sorted()` deleted | `the component manifest emitter sorts what it is handed`, **and that one only** | **exactly that 1** | matched, and it is the row that matters most |
| M8 | both sorts deleted **and** the list reversed | `the component manifest is sorted…`, `the manifest this module really emitted is sorted` | **5** | under-counted; the load-bearing half held |
| M9 | `render` prefixes each name with `/home/someone/` | `nothing machine-specific reaches a generated file or resource` | **5**, including that one | under-counted; the control fired |

### Correct silences, each with its reason

- **M1 leaves `no registry file means no option, and the build is not failed for it` and `a module
  that does not run KSP is left alone` green.** Both assert an **absence**, which is exactly what
  deleting the wiring produces. Predicted, and it held. All of `UdeaNetComponentsTest` stayed green
  too: the parse and sort rules were never the defect.
- **M3 leaves `a module with an id space reports the same list beside its lock` green.** That run
  *succeeds*, so it reaches the later write either way. This is the whole argument for writing the
  manifest before the id-space check, and it is why a test over the succeeding path alone could
  never have seen the difference. Predicted, and it held.
- **M7 leaves all three end-to-end manifest tests green.** `UdeaSymbolProcessor` orders its
  components by qualified name before any writer sees them, so the file still comes out sorted with
  `render`'s own sort deleted. The pure-function test is the *only* thing standing between the
  emitter and a silent loss of its ordering guarantee — which is why it is named in the emitter's
  KDoc as well as the test's, so a later round does not delete it as redundant. Predicted, and it
  held exactly.
- **M8 leaves `every generated resource is byte-identical across two runs` green.** Reversal is
  deterministic: the file leaves in a wrong but perfectly stable order, and byte-identity across
  two runs cannot see a wrong order. This is a *demonstration* that the ordering assertions are not
  redundant with the determinism ones, rather than an argument that they are not. Predicted, and it
  held.
- **M5 leaves `every generated resource is byte-identical across two runs` green.** Renaming a
  resource is deterministic too.
- **M4 leaves `rewriting this repository's own registry changes nothing in it` green.** That test
  hands `merge` the full name list, so there is nothing to drop — which is why the "kept" case
  needs a test of its own.

### The disagreements, with the arithmetic

**M1 failed the proof at leg 0, not leg 1.** I predicted the game would fail to build; in fact the
*engine* fails to publish, because `udea-core`, `udea-nav`, `udea-codegen`, `moba:game` and
`hollow:game` all set `udea.moduleName` and compile `@Replicated` components, so every one of them
hits the processor's refusal. The prediction was right about the mechanism and wrong about which
leg reports it first, and the measured behaviour is the stronger of the two. Cause read from the
log rather than inferred: `> Task :udea-core:kspCommonMainKotlinMetadata FAILED`.

**M4 turned 4 red, not 1.** The extra three are `a new name is inserted in sorted position`,
`a trailing note travels with the name it is on` and `an unsorted registry is sorted by the
rewrite`. The arithmetic: `merge` has 11 tests, and 4 of them hand it a `discovered` list narrower
than the reviewed file — those are exactly the 4 that went red. I counted only the test whose
*name* describes the behaviour and forgot that three others exercise it incidentally. The mutation
is faithful (it is the "replace, don't add" shape a reasonable implementation could have had) and
the magnitude is fully explained.

**M5, M8 and M9 each turned 5 red where I predicted 1 or 2.** Same cause in all three: five
udea-codegen tests address the manifest by its exact resource path or its exact bytes
(`udea/Moba-net-components.txt`, `udea/Gas-net-components.txt`,
`setOf("udea/$module-net-components.txt")`, and the two exact-content assertions), so **any** change
to the manifest's name or content breaks all of them. I predicted only the test each mutation was
aimed at. In M8's case the count also includes M7's edit, which M8 contains by construction — that
one is my own bookkeeping rather than a surprise. In every case the load-bearing prediction — which
test the mutation was aimed at, and which one must stay green — held.

### The instrument was wrong first, and I audited it before the code

The first run of this harness reported four mutations whose failing sets had nothing to do with
what was mutated: M6 "failing" a test about a module that does not run KSP, M5 "failing" a test
about comments. The mutations were fine; the **reader** was not. A passing JUnit `<testcase .../>`
is self-closing and a failing one is not, so a
`<testcase name="([^"]+)"[^>]*>(.*?)</testcase>` pattern pairs a *passing* test's name with the
next test's failure body. Replaced with `xml.etree.ElementTree`, and every figure above comes from
the parser. Then every build-logic row was **re-run from scratch** with `--no-build-cache` and
produced byte-identical failing sets, so the numbers are measured twice rather than measured once
and trusted.

That is the general rule and it earned its place here: **be most suspicious when a check tells you
that you were wrong.**

### And a near-miss worth recording

The mutation harness originally reverted with `git checkout -- build-logic udea-codegen`, which on
an **uncommitted** tree reverts the work as well as the mutation — and it did, silently, on the
first run. It was recovered from the `git diff` the same harness had captured one line earlier, and
re-applied cleanly. The branch was committed before any further mutation ran, which is what makes
`git checkout` the correct revert rather than a destructive one. Nothing was lost; the file list
after recovery matched the list before it, name for name.

### The literal diffs

Every row, exactly as `git diff` printed it during its run — not retyped. Hunk headers and index
lines elided where marked.

**M1** — `build-logic/src/main/kotlin/udea.kotlin-base.gradle.kts`
```diff
-applyNetComponentsToKsp()
+// applyNetComponentsToKsp()  // M1
```

**M2** — `build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaNetComponents.kt`
```diff
-        if (names != names.sorted()) {
+        if (false) {
```

**M3** — `udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/UdeaSymbolProcessor.kt`
```diff
-        options.moduleName?.let { moduleName ->
-            writeComponentManifest(moduleName, components)
-        }
+        // M3: moved below the id-space check
```
… and, further down the same file, after the refusal returns:
```diff
+        options.moduleName?.let { writeComponentManifest(it, components) }
         val emitted = ArrayList<Pair<ReplicatedComponent, Int>>(components.size)
```

**M4** — `build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaNetComponents.kt`
```diff
-        val lines = (reviewed + added.associateWith { it })
+        val lines = (reviewed.filterKeys(discovered::contains) + added.associateWith { it })
```

**M5** — `udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/protocol/NetComponentsManifest.kt`
```diff
-    const val FILE_NAME: String = "net-components.txt"
+    const val FILE_NAME: String = "net-components.list"
```

**M6** — `build-logic/src/main/kotlin/dev/wildware/udea/build/UdeaNetComponentsWiring.kt`
```diff
-    rootProject.extensions.findByType<UdeaNetComponentsExtension>()?.registry?.orNull?.asFile
-        ?: rootProject.layout.projectDirectory.file(UdeaNetComponents.FILE_NAME).asFile
+    rootProject.layout.projectDirectory.file(UdeaNetComponents.FILE_NAME).asFile
```

**M7** — `udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/protocol/NetComponentsManifest.kt`
```diff
-        qualifiedNames.sorted().joinToString(separator = "\n", postfix = "\n")
+        qualifiedNames.joinToString(separator = "\n", postfix = "\n")
```

**M8** — M7's diff, plus
`udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/UdeaSymbolProcessor.kt`
```diff
-            stream.write(NetComponentsManifest.render(names).toByteArray(StandardCharsets.UTF_8))
+            stream.write(NetComponentsManifest.render(names.reversed()).toByteArray(StandardCharsets.UTF_8))
```

**M9** — `udea-codegen/src/main/kotlin/dev/wildware/udea/codegen/protocol/NetComponentsManifest.kt`
```diff
-        qualifiedNames.sorted().joinToString(separator = "\n", postfix = "\n")
+        qualifiedNames.sorted().map { "/home/someone/" + it }.joinToString(separator = "\n", postfix = "\n")
```

---

## Images

**There are none, and that is not an omission.** Nothing in this ticket has a visual surface: it is
a convention plugin, a Gradle task, a KSP resource and a template's build. The artefacts a reviewer
should open are the proof transcripts under
`build/reports/udea/outside-game/`, listed by name in the criteria below. Producing a screenshot of
`moba` to fill this section would be a picture of something this branch does not change.

---

## The issue, criterion by criterion

The issue's body has one "what happens", one "what a game has to do instead", and a "what would fix
it" with three parts. Each is below with what proves it.

**1. "There is no way to let the build pass the list in from outside Udea's own repository."**
Fixed and proved by `outside-game-proof.sh` leg 1 (`build.log`, green) together with leg 6, which
asserts that **nothing in the game's build scripts mentions `udea.projectComponents`** — and does
so with its positive control printed beside it, because a `grep` that finds nothing reads exactly
like a `grep` that did not run:

```
=== components: the game hand-wires nothing
  control: 1 hit(s) for a string that is in the template
  and no hit for udea.projectComponents anywhere in the game's build scripts
  RoverReplicator.kt -> .../build/reports/udea/outside-game/RoverReplicator.kt
```
Four consecutive lines of the proof's own summary output, the absolute path prefix on the last
elided at the marked point. The control's one hit is kept as a file, so the positive half is
checkable rather than asserted: `grep-control.txt` is 159 bytes naming
`game/build.gradle.kts:66:udeaAgent {`, and `hand-wired.txt` — the negative — is **0 bytes**. A
`grep` that did not run would leave both empty, which is why the pair is reported together.

**2. "Have the conventions look for `net-components.lock` in the root project, parse it with
`UdeaNetComponents`, and pass it to KSP."** Done in `udea.kotlin-base`.
Proved four ways: `NetComponentsWiringTest.a module whose build script says nothing is handed the
root registry, sorted`; leg 6 above; leg 7, which shows the id **comes from the file** rather than
from counting the module's own symbols —

```
=== components: a name inserted ahead of it moves the id

=== id-moved: ./gradlew :game:kspKotlin
  green  -> .../build/reports/udea/outside-game/id-moved.log
  ComponentTypeId(0) -> ComponentTypeId(1): the file is what numbers it
```
(`RoverReplicator.kt` and `RoverReplicator-shifted.kt` in the report directory, line 58 of each);
and M1, which shows the whole engine stops compiling without it.

**3. "Failing that, an explicit surface, e.g. `udea { netComponents = file(...) }`, so a game
states it once."** Done as `udeaNetComponents { registry = ... }` — the rename is decision 1 above
and is commented on the issue. Proved by `NetComponentsWiringTest.the root build script can put the
registry somewhere else`, and M6 shows that test fails if the extension stops being honoured.

**4. "A `udeaWriteNetComponents` task, like `udeaWriteProtocolLock`, would also help: today the
first build of a new component fails, and the author has to hand-write the fully-qualified name
into a sorted file."** Done, and proved end to end by leg 8 — the failure, the task, and the green
build after it:

```
e: [ksp] this module emits a wire protocol (udea.moduleName is 'NewGame') but the build did not
set udea.projectComponents, ... Run `gradlew udeaWriteNetComponents` to put this module's
components into the project's 'net-components.lock', and review the diff: a name's position in
that file is its component type id on the wire. This module compiles
com.example.newgame.sim.Rover. ...
```
(`no-lock.log` line 14, wrapped here; the file has it on one line.)

```
> Task :udeaWriteNetComponents
read .../my-game/game/build/generated/ksp/main/resources/udea/NewGame-net-components.txt
.../my-game/net-components.lock: added com.example.newgame.sim.Rover. Every name at or after the
first of those has a new component type id.
```
(`write-lock.log` lines 13-15.) Then `after-write.log`, green. The file it wrote is
`net-components.lock.written` in the report directory.

**5. "Every game now owns a private copy of a rule the engine cares about more than the game
does — including the sorting."** The five copies inside this repository are deleted; the diff of
`udea-core`, `udea-nav`, `udea-codegen`, `moba/game` and `hollow/game` build scripts shows each
losing the eight-line block and the `arg(UdeaNetComponents.KSP_OPTION, ...)` line. Nothing sorts
but `UdeaNetComponents`.

**6. The engine itself still numbers correctly.** `sh gradlew build` green with no exclusions, and
`udeaWriteNetComponents` run against the engine's own reviewed lock produces a byte-identical file
— see "Regenerated files".

---

## Merging this branch: what "clean" would mean, and why it is not the word here

At `a2b73ff` this repository renamed `BRIEF.md` to `BRIEF-266.md` and left no `BRIEF.md` behind.
Git's rename detection then matched another branch's brief onto the *archived* file and applied it
there, and the rebase that did it reported **no conflict at all**. **Clean is precisely the word
that was true and wrong last time**, which is why this section states a conflict rather than an
absence of one.

`origin/master` now carries a `BRIEF.md` placeholder again (`f2a3354`), so there is a file on both
sides and rename detection has nothing to guess at. Measured with a trial merge that touches no
working tree, `git merge-tree --write-tree --name-only origin/master HEAD`, exit **1**:

```
0936ac265c94972727b993efcfc837d25323553e
BRIEF.md

Auto-merging AGENTS.md
Auto-merging BRIEF.md
CONFLICT (content): Merge conflict in BRIEF.md
Auto-merging docs/new-game.md
```

The whole output, nothing elided. **One conflicted path, and it is this file** — which is the
intended behaviour: two briefs meeting in one place is a text conflict a person resolves by
archiving one, not a silent overwrite. `AGENTS.md` and `docs/new-game.md`, the two documents this
branch shares with what has landed since, merge without conflict.

At the merge base `2e7aed4` this branch is **2 ahead, 13 behind**. It has never been rebased, so
the rename trap has had no opportunity to fire on it in the first place; the measurement above is
about what happens when it lands, not about what has already happened to it.

## What I did not exercise

- **`udeaWriteNetComponents` with a registry that already has a comment between two names.** The
  refusal is unit-tested (`a comment between two names is refused rather than moved`) but no real
  repository is in that state, so it has not been seen through the task.
- **Two games on one machine.** Nothing here changes port ranges or the bridge; leg 3 still checks
  the template's own range.
- **iOS test execution.** `compileTestKotlinIosArm64` and `compileTestKotlinIosSimulatorArm64`
  execute in the full build on this box and are green; *running* iOS tests needs macOS, so no iOS
  test result is claimed.
- **A game whose own component count reaches the engine's lowest id.** That is the unmerged id
  space described above, out of scope, and now documented with where it would be caught.
