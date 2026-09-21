# BRIEF — issue #274: a game outside the engine repository can set its component id space

SHA: _(filled in at commit)_

---

## Predictions, frozen before the box was free

**Everything in this section was written before a single JVM ran on this branch.** The build hold
(`BOX_FREE_274`) was still in place; nothing below is a number read off an output and written back
as a forecast. Each row's literal `git diff` is published under "Mutation table" once run, beside
the result.

Counts come from reading the test sources and marking which assertions name the mutated
behaviour. None of them is derived from the quantity being measured.

| # | Mutation | Predicted red | Predicted **correctly green**, and why |
|---|---|---|---|
| M1 | Delete `applyNetComponentsToKsp()` from `udea.kotlin-base.gradle.kts` — the fix itself | `NetComponentsWiringTest`: 4 of 6 (`handed the root registry`, `somewhere else`, `not an id space fails`, `cannot take an option`). Whole-repo `sh gradlew build` red: `udea-core`, `udea-nav`, `moba:game`, `hollow:game`, `udea-codegen` each set `udea.moduleName` and compile components, so each hits the processor's refusal. `outside-game-proof.sh` red at leg **1** | `NetComponentsWiringTest`: `no registry file means no option` and `a module that does not run KSP is left alone` — both assert an **absence**, which is what deleting the wiring produces. All of `UdeaNetComponentsTest` — the parse and sort rules were never the defect |
| M2 | Delete the sortedness check from `UdeaNetComponents.parse` | `UdeaNetComponentsTest`: `an out-of-order registry is refused` and `an unsorted registry is sorted by the rewrite` (its first assertion is that `parse` refuses). `NetComponentsWiringTest`: `a registry that is not an id space fails the build` | `rewriting this repository's own registry changes nothing` — the real file is already sorted, so the check being gone changes nothing about it |
| M3 | Move `writeComponentManifest(...)` in `UdeaSymbolProcessor.process` to **after** the id-space refusal | `ProjectIdSpaceTest`: `the failing run still reports what it compiled` and `a module emitting a protocol without the project list is refused`. `outside-game-proof.sh` red at leg **8** (`write-lock`), with `udeaWriteNetComponents` saying no module reported its components | `ProjectIdSpaceTest.a module with an id space reports the same list beside its lock` — that run **succeeds**, so it reaches the later write either way. This is exactly why the manifest is written early, and why a test over the succeeding path alone could not see it |
| M4 | Make `UdeaNetComponents.merge` drop names the build did not report | `UdeaNetComponentsTest.a name the build did not see this time is kept` | `rewriting this repository's own registry changes nothing` — that test hands `merge` the full name list, so there is nothing to drop. It is why the "kept" case needs a test of its own |
| M5 | Change `NetComponentsManifest.FILE_NAME` to `net-components.list` | `UdeaNetComponentsTest.the manifest name here is the one the processor writes`. `outside-game-proof.sh` red at leg **8** | Whole-repo `sh gradlew build` — the KSP option path is untouched, so the engine compiles and every module is numbered correctly. That is the whole reason the mirror test exists: nothing else in either build can see the two constants disagree |
| M6 | Make `netComponentsFile()` ignore the extension and always return the root default | `NetComponentsWiringTest.the root build script can put the registry somewhere else` | the other 5 of that class — the default path is what they use |
| M7 | Delete `.sorted()` from `NetComponentsManifest.render` | `GeneratedFileDeterminismTest.the component manifest emitter sorts what it is handed` — and that one only | `the component manifest is sorted, not in declaration order`, `the manifest this module really emitted is sorted`, `every generated resource is byte-identical across two runs`. All three go through `UdeaSymbolProcessor`, which orders its components by qualified name before any writer sees them, so the file still comes out sorted. This is why the pure function is tested where the mutation is observable instead of only end to end |
| M8 | Delete `.sorted()` from `render` **and** reverse the list in `writeComponentManifest` — the file now leaves in a wrong but stable order | `the component manifest is sorted, not in declaration order` and `the manifest this module really emitted is sorted` | `every generated resource is byte-identical across two runs` — reversal is deterministic, so byte-identity across two runs cannot see a wrong order. That is exactly why the ordering assertions are not redundant with the determinism ones |
| M9 | Make `render` prefix each name with `/home/someone/` | `nothing machine-specific reaches a generated file or resource` | everything keyed on names alone would still find them; this row exists as the positive control that the widened scan really reads **resources** and not just `.kt` files |

**M9 is also the control for a narrowing, if one turns out to be needed.** The widened
machine-specific scan now reads `udea/CodegenFixtures-agent-tools.json`, which declares the JSON
Schema dialect URL; `MACHINE_PATH`'s lookbehind was written for exactly that shape, so it should
hold. If it does not, the answer is to exclude that resource **by name, with a reason**, and never
to loosen the regex — loosening a pattern so one file passes is how a scanner stops catching what
it was written for. An excluded file and a file the scan cannot read look identical from outside,
so M9 is what shows the narrowed scan still bites: a machine path planted in a resource that *is*
covered, and caught. Whether a narrowing was needed is recorded below with the run.

### The sentence the rest of this brief is written against

**"Clean" is precisely the word that was true and wrong last time.** A rebase that reported no
conflict had put a developer's brief into an archived file and left none behind; the word was
accurate and the result was not. The same shape is available to every check here — a grep that
found nothing because it could not run, a task that changed nothing because it never ran, a
mutation that went red for a reason other than the one predicted. So nothing below is reported as
"it passed": each claim carries the number, the exit status, or the artefact it came from, and
each absence carries a positive control showing the same search can still find something.

### Two rules applied to the "it is gone" claims

- Every "X appears nowhere" claim in this brief is printed **beside a positive control**: the same
  search shape against a string that is certainly present, returning non-zero. The proof script
  does the same thing at leg 6 before its `udea.projectComponents` miss.
- An empty result is recorded as UNKNOWN unless an exit status says the search ran. Blanks in the
  table below are scored as interesting, not as "no change".

---

## The locks: nothing of `dev-270`'s moved

`templates/new-game` is **not** a project of this build — `settings.gradle.kts` includes
`udea-*`, `moba:*` and `hollow:*` and nothing under `templates/`, and the template carries a
`settings.gradle.kts` and `gradle.properties` of its own. So the `@Replicated` component added to
the template is compiled only by `scripts/outside-game-proof.sh`, in a copy outside this tree,
and it cannot move `udea-codegen/net-protocol.lock` or `expected-generated-hashes.txt`.

The processor change cannot move them either: it writes one extra generated **resource**, and
`GeneratedFileDeterminismTest` hashes `.kt` files under `build/generated/ksp/test/kotlin` alone
(`GeneratedSources.files` filters `extension == "kt"`). `net-protocol.lock` is a function of the
components and their fields, and this branch adds no component to the engine's own build.

Confirmed independently by the lead, who read the filter rather than taking the claim:

```
udea-codegen/src/test/kotlin/dev/wildware/udea/codegen/GeneratedSources.kt:25
    .filter { it.isFile && it.extension == "kt" }
```

_(post-build `git status` goes here)_

### And the hole that filter opens, closed on this branch

Read the other way round, that filter says **a generated resource is outside every determinism
check this build has**. `net-protocol.lock`, the tool manifest and now the component manifest are
generated, shipped inside jars, and compared to nothing. That was tolerable while no resource's
correctness depended on its order. The component manifest's does: `udeaWriteNetComponents` folds
it into `net-components.lock`, where a name's position **is** its `ComponentTypeId`. A future
change emitting it in visit order rather than sorted order would mint different ids on two
machines from identical sources, with `protoHash` reporting agreement — the exact failure the
`udea.projectComponents` diagnostic exists to prevent, arriving by the back door.

**What I did, and what I did not.** I added `GeneratedSources.resources` — every generated
resource, not only mine — and put it through the machine-specific scan, a two-run byte-identity
comparison, an order assertion against a source whose declaration order disagrees with sorted
order, and an assertion over the artefact `kspTestKotlin` really wrote.

**What widening `GeneratedSources.files` would have caught, and cost.** It would have covered the
same resources with one line, and it would also have hashed them, which is stronger. The cost is
that `relativePaths()` is what `the hash file covers every generated file` compares against, so
widening adds three rows to `expected-generated-hashes.txt` — a **regeneration of a checked-in
fixture `dev-270` owns this wave**, which is the one merge nobody can resolve as text. So the
coverage was taken and the hashing was not. Nothing about the choice is narrower in *which*
resources it protects; what is given up is a checked-in hash per resource, and the two-run
comparison stands in for it.

**Follow-up, and it is a real cost rather than a formality.** Once #270's lock regeneration has
merged, widening `GeneratedSources.files` to cover resources is a one-line change plus three rows
in `expected-generated-hashes.txt`, and it buys what a two-run comparison cannot: a **checked-in**
hash. Two runs agreeing says a resource is consistent; a checked-in hash says it is the resource
somebody reviewed. A resource that changes for a reason nobody intended is consistently wrong on
both runs and passes every check on this branch. The lead is recording it in `WAVE.md` as
unassigned so it outlives this branch.

---

## Evidence command

_(filled in)_

---

## What the proof settles, and what it does not

_(filled in)_
