# Issue #270 — a reverse lookup for mounted parts, and one engine answer to "which model does this entity draw"

**SHA:** `769265f` — the merge of `origin/master` into this branch, and the tree every number below
was measured on. Read back with `git rev-parse --short HEAD` in this worktree after the merge
existed, not copied out of a log.

The **code** commit is `e674861`; `769265f` is `e674861` merged with `origin/master` at `0378a95`,
and this brief lands on top of that in a commit of its own. So the branch tip will be one commit
further on than the SHA printed here by the time anyone reads it, and that is deliberate: a brief
cannot name the SHA of its own commit. `769265f` is the one to review.

**Branch:** `issue-270-attachment-index`, based on `origin/master` at **`edaded0`**, read back with
`git rev-parse --short HEAD` after the branch existed rather than from a log taken before it was
cut. That distinction is not pedantry: I first reported this branch as based on `edf82f0`, which was
a true sentence about the wrong ref — the log line was real, and described the repository a moment
before the fetch that moved `origin/master` on. A reviewer diffing against `edf82f0` would have got
a diff that is wrong in a way neither of us could see.

The branch reached `edaded0` by `merge --ff-only origin/master` with no commits of its own, and
**not** by a rebase: `master` had just archived `BRIEF.md` as `BRIEF-266.md`, which is the shape
git's rename detection misreads, and a fast-forward puts nothing in its way. Verified afterwards by
name — `BRIEF-266.md` opens `# BRIEF — #266 (and #259): screen-effect shaders a game writes in GLSL`
and does not appear in the status listing, which is what "untouched" means here; a tidy-looking tree
is not.

**The same rule is applied to the lock files below.** The claim that `expected-generated-hashes.txt`,
`moba/game/net-protocol.lock` and `hollow/game/net-protocol.lock` did not move is made by checking
that each is **absent from the status listing by name**, not by observing that the tree looked
tidy. An absence somebody asked about is a result; an absence nobody asked about is a blank.

---

## The evidence command

```
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew :udea-core:jvmTest :udea-render:jvmTest :udea-render:udeaGlTest \
    -Pudea.render.requireGl=true --no-build-cache
```

**On this branch: exit 0, 149 XML files, 941 tests, 0 skipped, 0 failures, 0 errors.**
**With the feature reverted: exit 1, 18 failures.**

One command, both halves of the ticket. `:udea-core:jvmTest` carries the reverse index -
`AttachmentIndexTest`, `AttachmentIndexCostTest`, `AttachmentDeterminismTest`,
`DrawnSnapshotTypeTest`. `:udea-render:jvmTest` carries the renderer bridge, `DrawnModelsTest`. The
GL task draws the thing: `GlDrawnModelTest` spawns an entity carrying `Transform3D` and `Drawn` and
nothing else, and asserts the engine attached a `ModelRenderer` and that a fox is on the screen.

`--no-build-cache` is in the command on purpose. `:udea-render:udeaGlTest` came back **`FROM-CACHE`**
in my first full build of this branch - the line is in that log - which is the exact trap in the
briefing: a restored test task and one that ran are indistinguishable, because the JUnit XML is what
gets restored. The script that runs this also deletes the three `test-results` directories first. `-Pudea.render.requireGl=true`
is the other half - without it, and with no `DISPLAY`, the GL tasks **skip** and the build stays
green having tested nothing.

**Proved red by taking the feature out.** `REVERT` is M1 and M6 applied together - the index is
never published and the renderer is never attached - which is the smallest edit that removes both
halves and leaves everything else, including every test, exactly as it is:

The `REVERT` row under **Mutations** carries its literal diff and all eighteen failing case names.
Its counts were taken before the merge with `origin/master` (935 tests then, 941 now, because that
merge brought #269's shader tests into `udea-render`); the green run above is post-merge. Both
numbers are printed rather than one quietly reused for the other.

---

## Summary

Two halves, shipped together because they are the same complaint: a game had to walk the world to
find out something the engine already knew.

**AC1 - the reverse lookup.** `AttachmentIndex` (`udea-core`) answers *what is mounted on this
entity* and *what is in this socket* from array reads. A game reaches it as
`ctx[CoreModule.ATTACHMENTS]`, the same `ServiceKey` shape `CoreModule.NET_IDS` already uses.
`AttachmentSystem` - which was already reading every `AttachedTo` to place its part - fills it in
the same pass, in `PostPhysics`. The index is **derived and never stored**: `beginRebuild()` at the
top of the tick, `endRebuild()` at the bottom, nothing carried between. That is what makes a rewind,
a snapshot restore or a level load re-derive it rather than find it stale, and it is why there is a
rewind case in `AttachmentDeterminismTest` rather than only forward-going ones.

It is rebuilt from nothing without clearing anything, because clearing is what a per-tick rebuild
cannot afford: the layout is CSR, and freshness is an **epoch stamp** per parent slot rather than a
memset. Ordering is ascending `NetId`, by index then generation, so two machines stepping the same
tick get the same list in the same order - the family's own iteration order is Fleks entity id,
which is not the same thing and is not replicated.

**AC2 - the asset reference draws itself.** `Drawn` (`udea-core`, `@Replicated`, `@Serializable`)
holds an `AssetIndex` slot. `ModelRenderSystem` keeps each entity's `ModelRenderer` in step with it,
on the Kool render thread, loading each model once through a `ModelLibrary` - `FileModelLibrary` on
the desktop. A game writes no bridge, and `ModelRenderer` stays render-side and unreplicated.

### Decisions, and what was rejected

**`Drawn` lives in `udea-core`, which means a new arrow `udea-core -> udea-assets` (`api`).**
Rejected: putting it in `udea-render`, because a dedicated server has to be able to say what a part
looks like without a renderer on the classpath, and a `@Replicated` component there would mean
adding KSP and a second protocol lock to the GL module. Also rejected: a bare `Int` with no typed
constructor, which falls on `AGENTS.md`'s "no bare `Int` for a domain concept" and hands the game
back the lookup this ticket removes. The arrow is plain data - no Fleks, no GL - and both modules
are already headless. Commented on the issue with what to change if the owner disagrees:
<https://github.com/wildware-uk/Udea/issues/270#issuecomment-5753324942>.

**The reverse arrow still fails, and the case that proves it already existed.**
`build-logic/src/test/kotlin/dev/wildware/udea/build/ModuleGraphRulesTest.kt:344`,
`UDEA-MG-006 fails udea-core on the asset model, which would be a dependency cycle`: it puts
`:udea-core` on `:udea-assets`' own compile classpath and asserts that is a violation. So the arrow
I added is legal in the direction I added it and still refused in the other, which is what stops it
becoming a cycle. Its name described a hypothetical before this branch and describes a live pair
now. No second case was added, per the instruction not to duplicate one that exists.

**`Drawn.at(slot)` is a companion factory, not a constructor.** `Drawn(Int)` and `Drawn(AssetIndex)`
both erase to `(I)V` on the JVM - Kotlin does not mangle constructor signatures the way it mangles
function names - so the two cannot coexist. The typed entry points are the `Ref<Model>` constructor
and `Drawn.at`, and the KDoc says why.

**A model change repoints the existing `ModelRenderer` rather than replacing it**, so
`ModelRenderer.mask` - which a game sets for the outline pass (#259, #266) - survives a model
change. Replacing it is M7 in the table below, and there is a test that dies when it does.

**A renderer a game attached by hand is never removed.** `DrawnModels` remembers the slot each
entity was synced at, keyed by Fleks id, with a separate `UNMANAGED` sentinel that is *not*
`Drawn.NONE`: `NONE` is a slot this cleared, `UNMANAGED` is an entity this never managed. Collapsing
them would take a game's own renderer off the first time it met a `Drawn` naming nothing.

### What I did not do

**No `docs/contracts/` file was changed**, and none needed to be. `Drawn` is an ordinary
`@Replicated` component and goes through the existing `Replicator<T>`; the `fieldNames[i]` == mask
bit *i* invariant is asserted for it directly in `DrawnSnapshotTypeTest`.

**No wall-clock measurement.** `WallClockBudgetCensusTest` makes an unregistered wall-clock reading
in a test source a build failure, so the cost claim is denominated in **entity visits per question**
instead of milliseconds. See P2 above for where each number comes from.


## Frozen predictions

Written **before the first build on this branch**, while the box was on a build hold and no JVM had
run. Recorded here so that the results below can be compared against them rather than written to
match them. Where a prediction and a result disagree, the disagreement is the finding.

### P1 — the lock files

`dev.wildware.udea.core.spatial.Drawn` sorts between `spatial.AttachedTo` and `spatial.Transform3D`,
so:

- `udea-core/net-protocol.lock`: `Drawn` takes id **30**; `Transform3D` moves **30 → 31**.
- `net-components.lock` (hand-edited, reviewed): `Drawn` inserted at position 30; `udea.nav.NavAgent`
  moves **31 → 32** and `udea.nav.NavObstacle` **32 → 33**. Neither has a `net-protocol.lock` of its
  own, so nothing else on disk records those two ids.
- `moba/game/net-protocol.lock`, `hollow/game/net-protocol.lock` and `udea-codegen/net-protocol.lock`
  **do not change**: every component in them sorts before `Drawn`.
- `udea-codegen/src/test/resources/expected-generated-hashes.txt` **does not change**: the fixture
  components' ids do not move.
- `udea-core/net-protocol.lock`'s `protoHash` **does** change (it is a hash over the file's
  non-comment content). That is a wire-format change, and it is harmless today: nothing has been
  released, only a `0.1.0-SNAPSHOT`, and no client is connected to anything.

### P2 — the cost measurement

`AttachmentIndexCostTest` builds a world of **20 000** mounted parts, of which **4** are on the
chassis it asks about. The measured quantity is **entity visits per question**.

- Index: **0** entity visits.
- The scan the issue quotes (`family { all(AttachedTo) }.forEach { … }`): **20 000** entity visits,
  and **0 parts returned** once the world is emptied.

**Where each number comes from, because a construction is not a measurement.** The 20 000 is the
size of the world the test builds; it is a count of entities the test created, not a figure the
index reports about itself, so nothing here measures its own output. The 0 is obtained by removing
every entity from the world after the index is built and asking the same questions again: there is
nothing left to visit, so any implementation that visited even one entity could not answer, and the
index answers unchanged. That is an argument from construction rather than from a counter, and it is
stated as such: no counter was added to production code for it.

**The cost case is paired, and the pairing is not optional.** "Still answers once every entity is
gone" is the signature of an index — and equally the signature of *a cache that is never
invalidated*, which is the one defect this index must not have, since it is derived and never
stored. A build-once index would pass the cost case on its own. So it sits beside four freshness
cases, and a build-once index fails all four:

| Freshness case | Where | What a stale index would do |
|---|---|---|
| `a part that has gone from the world is gone from the next rebuild` | `AttachmentIndexCostTest` | still report four parts after one was destroyed |
| `a part that is detached is no longer mounted on anything` | `AttachmentIndexTest` | still report the blown-off part |
| `swapping the part in a socket changes what that socket answers` | `AttachmentIndexTest` | answer the socket with the part that was hijacked away |
| `a rewind past a swap re-derives the index from the restored world` | `AttachmentDeterminismTest` | answer with the **future's** part after a rewind — the hardest direction, because the index has to *gain back* a part the future removed |

### P3 — which mutation kills which test

| # | Mutation | Predicted red |
|---|---|---|
| M1 | `AttachmentIndex.endRebuild()` never publishes (`readable` left alone) | every `AttachmentIndexTest` case that expects a part, all three `AttachmentIndexCostTest` cases, the rewind case |
| M2 | `endRebuild()` stops sorting each run | `the parts come back in NetId order and not in the family's` only |
| M3 | `childOf(parent, node)` ignores `node` | `the part in a socket is found by the node it is mounted on` |
| M4 | `AttachmentSystem` never calls `beginRebuild()` | the detach case, the swap case, and `mountCount` |
| M5 | the query is the issue's full-world scan | `an answer needs no entity in the world at all` **only**; every behaviour case stays green, which is the point |
| M6 | `DrawnModels.sync` never attaches a missing renderer | three `DrawnModelsTest` cases and `GlDrawnModelTest` |
| M7 | `DrawnModels` replaces the renderer instead of repointing it | `an entity whose model changes is repointed rather than given a new renderer` |
| M8 | `Drawn.model` becomes `@Sim` instead of `@Net` | `the model an entity draws is sent to clients, not only snapshotted` |
| M9 | `AttachmentIndex.NEVER` back to `0L` (the sentinel collision, below) | `an index that has published no rebuild answers nothing even with entries recorded in it` **only** |

**One prediction changed a test before it was ever run.** Writing "M2 kills the ordering test"
forced the question *how*, and the answer was that it could not: parts created in order get Fleks
entity ids and `NetId`s that ascend together, so an index that never sorted at all would have passed
it. Freezing the prediction is what found that, not running anything. The test now makes the two
orders disagree the way production does — the second part takes a `netIds.reserve()` taken before the
first part existed, which is what `BlueprintSpawner` does — so it is earlier by `NetId` and later in
the family, and M2 has something to kill.

### Two defects found by re-reading the diff, neither of them by a test

Both were found while the box was on hold and nothing could be run, and both are the same family
as everything else in this file: **an artefact that describes the code is not the code.**

**A KDoc that stated behaviour the code does not have.** `AttachmentIndex.beginRebuild` said *"Until
`endRebuild` every query reads the previous one."* It does not. The epoch is bumped on entry, nothing
is published at the new one yet, and `add` is already overwriting the previous rebuild's counts — so
a query in that window answers **empty**. Nothing observes the window, because both calls happen
inside one `AttachmentSystem.onTick` before any other system runs, which is precisely why no test
would ever have found it. A comment is a measurement with no exit code: nothing executes it, nothing
fails when it drifts, and a reader trusts it *because* it is specific. The next developer would have
quoted that sentence as a guarantee. It now says what the window does — answers empty — and why empty
is the safe half of the choice, so that nobody later "fixes" it toward the stale read.

**Two sentinels that were the same number, and the code was right only by luck.** `epoch` started at
`0` and `NEVER` was also `0`, so before the first tick `readable == epoch` compared **true**, a query
fell through into `parentEpoch`, and the zero-fill matched there too. Every answer was still correct,
because what it then read was a bucket count of zero. Two independent accidents lining up to produce
right answers is the shape that survives every suite and breaks later somewhere that names neither
sentinel.

`NEVER` is now `-1L`, and the two "never"s are documented as the different facts they are: zero means
*"this parent index has never been written"*, which is free because `copyOf` zero-fills and no rebuild
is ever epoch zero; `-1` means *"no rebuild has been published"*.

**The fix is asserted, and the assertion is the stronger of the two options.** `before the first tick
the index answers nothing` passed before the fix and passes after it — it cannot tell the sentinels
apart, and it is kept because it is the state a game meets. The discriminator is
`an index that has published no rebuild answers nothing even with entries recorded in it`: it records
an entry with no `beginRebuild` and no `endRebuild`, which is the only way to put a **non-zero** count
behind a never-published index, and it names the parent `NetId.of(0, 0)` on purpose, because that raw
word is `0` and any other parent would be saved by the `parentRaw` comparison against a zero-filled
array — the test would pass while saying nothing. With the sentinels collided it reports one part
mounted and hands back a `NetId` read out of an array nothing ever wrote. That is M9 in the table
above.

**What that test does not claim.** No caller can reach that state today: `AttachmentSystem` always
brackets its entries, so this pins a **trap** rather than a live defect. It is worth pinning because
the trap is invisible — restore the collision and nothing else in the suite objects.

_(Results, diffs and the correctly-silent rows are below, under **Mutations**.)_
## Mutations: the frozen table, measured

Each row was run on its own, from the pristine sources, with `--no-build-cache` and the
test-results directories deleted first, so no count here can be a cache restore. The diff
under each row is the literal `diff -u` of that mutation against the pristine copy, taken
from the run; the red names and every figure come out of that run's JUnit XML, never off
the console (`grep -c FAILED` also matches `BUILD FAILED`).

### REVERT — both halves taken out at once - the evidence command's red

```diff
--- /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/pristine/AttachmentIndex.kt	2026-09-21 02:25:25.888386426 +0000
+++ udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/AttachmentIndex.kt	2026-09-21 02:53:58.932028207 +0000
@@ -253,7 +253,7 @@
             val parent = touched[position]
             sortRun(bucketStart[parent], bucketCount[parent])
         }
-        readable = epoch
+        // M1: the rebuild is never published.
     }
 
     /**
--- /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/pristine/DrawnModels.kt	2026-09-21 02:25:25.893386524 +0000
+++ udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/DrawnModels.kt	2026-09-21 02:53:58.932028207 +0000
@@ -76,7 +76,7 @@
                 }
                 val wanted = library.modelAt(AssetIndex(slot))
                 if (renderer == null) {
-                    entity.configure { it += ModelRenderer(wanted) }
+                    // M6: a missing renderer is never attached.
                 } else {
                     renderer.model = wanted
                 }
```

Ran: the evidence command, verbatim. Exit 1. XML: 148 files, 935 tests, 0 skipped, 18 failures, 0 errors.

- RED: `AttachmentDeterminismTest.a rewind past a swap re-derives the index from the restored world()[jvm]`
- RED: `AttachmentIndexCostTest.a part that has gone from the world is gone from the next rebuild()[jvm]`
- RED: `AttachmentIndexCostTest.an answer needs no entity in the world at all()[jvm]`
- RED: `AttachmentIndexCostTest.the right parts come back out of a world of thousands of mounts()[jvm]`
- RED: `AttachmentIndexTest.a part mounted on a part belongs to that part and not to the chassis()[jvm]`
- RED: `AttachmentIndexTest.a part that is detached is no longer mounted on anything()[jvm]`
- RED: `AttachmentIndexTest.a part with no transform is still mounted()[jvm]`
- RED: `AttachmentIndexTest.a stale parent id answers nothing even when its index has been handed out again()[jvm]`
- RED: `AttachmentIndexTest.each reported part carries the node it is mounted on()[jvm]`
- RED: `AttachmentIndexTest.swapping the part in a socket changes what that socket answers()[jvm]`
- RED: `AttachmentIndexTest.the part in a socket is found by the node it is mounted on()[jvm]`
- RED: `AttachmentIndexTest.the parts come back in NetId order and not in the family's()[jvm]`
- RED: `AttachmentIndexTest.the parts mounted on a chassis are its parts and nobody else's()[jvm]`
- RED: `DrawnModelsTest.a Drawn that names nothing takes the model back off()[jvm]`
- RED: `DrawnModelsTest.a frame in which nothing changed writes nothing and asks the library nothing()[jvm]`
- RED: `DrawnModelsTest.an entity the simulation gave a model draws it, with no game-written bridge()[jvm]`
- RED: `DrawnModelsTest.an entity whose model changes is repointed rather than given a new renderer()[jvm]`
- RED: `GlDrawnModelTest.an entity the simulation gave an asset reference draws itself()`

**This is the evidence command's proof.** M1 and M6 together are the smallest edit that removes both halves of the feature and leaves everything else alone - every test, every fixture, every other line of production code. This row was run **before** the merge with `origin/master`, so its denominator is the 935 tests those tasks had then rather than the 941 they have now; on that same tree, green, the command gave 148 files, 935 tests, 0 skipped, 0 failures, exit 0. Reverted it gives the 18 above, exit 1. So the command is not vacuous, and it is not vacuous for the ticket's own reason rather than for some incidental one: thirteen of the eighteen are the reverse index and five are the renderer bridge.

### M1 — `AttachmentIndex.endRebuild()` never publishes

```diff
--- /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/pristine/AttachmentIndex.kt	2026-09-21 02:25:25.888386426 +0000
+++ /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a2b587ce4b7563c14/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/AttachmentIndex.kt	2026-09-21 02:38:53.989262989 +0000
@@ -253,7 +253,7 @@
             val parent = touched[position]
             sortRun(bucketStart[parent], bucketCount[parent])
         }
-        readable = epoch
+        // M1: the rebuild is never published.
     }
 
     /**
```

Ran: :udea-core:jvmTest. Exit 1. XML: 72 files, 524 tests, 0 skipped, 13 failures, 0 errors.

- RED: `AttachmentDeterminismTest.a rewind past a swap re-derives the index from the restored world()[jvm]`
- RED: `AttachmentIndexCostTest.a part that has gone from the world is gone from the next rebuild()[jvm]`
- RED: `AttachmentIndexCostTest.an answer needs no entity in the world at all()[jvm]`
- RED: `AttachmentIndexCostTest.the right parts come back out of a world of thousands of mounts()[jvm]`
- RED: `AttachmentIndexTest.a part mounted on a part belongs to that part and not to the chassis()[jvm]`
- RED: `AttachmentIndexTest.a part that is detached is no longer mounted on anything()[jvm]`
- RED: `AttachmentIndexTest.a part with no transform is still mounted()[jvm]`
- RED: `AttachmentIndexTest.a stale parent id answers nothing even when its index has been handed out again()[jvm]`
- RED: `AttachmentIndexTest.each reported part carries the node it is mounted on()[jvm]`
- RED: `AttachmentIndexTest.swapping the part in a socket changes what that socket answers()[jvm]`
- RED: `AttachmentIndexTest.the part in a socket is found by the node it is mounted on()[jvm]`
- RED: `AttachmentIndexTest.the parts come back in NetId order and not in the family's()[jvm]`
- RED: `AttachmentIndexTest.the parts mounted on a chassis are its parts and nobody else's()[jvm]`

Exactly the predicted set.

### M2 — `endRebuild()` stops sorting each parent's run

```diff
--- /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/pristine/AttachmentIndex.kt	2026-09-21 02:25:25.888386426 +0000
+++ /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a2b587ce4b7563c14/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/AttachmentIndex.kt	2026-09-21 02:39:06.829515112 +0000
@@ -249,10 +249,7 @@
             childRaw[at] = entryChild[entry]
             childNode[at] = entryNode[entry]
         }
-        for (position in 0 until touchedCount) {
-            val parent = touched[position]
-            sortRun(bucketStart[parent], bucketCount[parent])
-        }
+        // M2: each parent's run is left in family order.
         readable = epoch
     }
 
```

Ran: :udea-core:jvmTest. Exit 1. XML: 72 files, 524 tests, 0 skipped, 1 failures, 0 errors.

- RED: `AttachmentIndexTest.the parts come back in NetId order and not in the family's()[jvm]`

Exactly the predicted case, and nothing else. This is the proof that the repaired ordering test can now die: before the repair it would have stayed green here.

### M3 — `childOf(parent, node)` ignores the node

```diff
--- /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/pristine/AttachmentIndex.kt	2026-09-21 02:25:25.888386426 +0000
+++ /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a2b587ce4b7563c14/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/AttachmentIndex.kt	2026-09-21 02:39:20.278779191 +0000
@@ -156,7 +156,7 @@
         if (bucket < 0) return NetId.NONE
         val start = bucketStart[bucket]
         for (offset in 0 until bucketCount[bucket]) {
-            if (childNode[start + offset] == node) return NetId.ofRaw(childRaw[start + offset])
+            if (true) return NetId.ofRaw(childRaw[start + offset]) // M3: the node is ignored
         }
         return NetId.NONE
     }
```

Ran: :udea-core:jvmTest. Exit 1. XML: 72 files, 524 tests, 0 skipped, 1 failures, 0 errors.

- RED: `AttachmentIndexTest.the part in a socket is found by the node it is mounted on()[jvm]`

Exactly the predicted case.

### M4 — `AttachmentSystem` never calls `beginRebuild()`

```diff
--- /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/pristine/AttachmentSystem.kt	2026-09-21 02:25:25.890386465 +0000
+++ /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a2b587ce4b7563c14/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/AttachmentSystem.kt	2026-09-21 02:39:33.322035295 +0000
@@ -68,7 +68,7 @@
     private val offset = MountFrame()
 
     override fun onTick() {
-        attachments.beginRebuild()
+        // M4: the rebuild is never started, so entries accumulate.
         mounted.forEach { part ->
             // Only a mount whose parent is live is indexed: a stale parent id cannot be asked
             // for, and two generations of one index in the same rebuild is what `add` refuses.
```

Ran: :udea-core:jvmTest. Exit 1. XML: 72 files, 524 tests, 0 skipped, 15 failures, 0 errors.

- RED: `AttachmentDeterminismTest.a rewind past a swap re-derives the index from the restored world()[jvm]`
- RED: `AttachmentDeterminismTest.a run resumed from a snapshot taken before the swap reproduces the world it was captured from()[jvm]`
- RED: `AttachmentDeterminismTest.two independent runs of a mount, a swap and a detach agree tick for tick()[jvm]`
- RED: `AttachmentIndexCostTest.a part that has gone from the world is gone from the next rebuild()[jvm]`
- RED: `AttachmentIndexCostTest.an answer needs no entity in the world at all()[jvm]`
- RED: `AttachmentIndexCostTest.the right parts come back out of a world of thousands of mounts()[jvm]`
- RED: `AttachmentIndexTest.a part mounted on a part belongs to that part and not to the chassis()[jvm]`
- RED: `AttachmentIndexTest.a part that is detached is no longer mounted on anything()[jvm]`
- RED: `AttachmentIndexTest.a stale parent id answers nothing even when its index has been handed out again()[jvm]`
- RED: `AttachmentIndexTest.swapping the part in a socket changes what that socket answers()[jvm]`
- RED: `AttachmentIndexTest.the parts come back in NetId order and not in the family's()[jvm]`
- RED: `AttachmentIndexTest.the parts mounted on a chassis are its parts and nobody else's()[jvm]`
- RED: `AttachmentSystemTest.a chain is right on the tick it is built whatever order the parts were made in()[jvm]`
- RED: `AttachmentSystemTest.a part mounted on another part's socket lands two levels down()[jvm]`
- RED: `AttachmentSystemTest.two parts mounted on each other fail loudly rather than recursing for ever()[jvm]`

**Missed.** Three predicted, fifteen red. The cause is in the XML and it is the sentinel collision arriving from another direction: with no `beginRebuild`, `epoch` stays `0`, which is what a never-written `parentEpoch` slot also holds, so `add` takes its else branch and its consistency `check` throws - `NetId index 1 was offered as both NetId(#0@0) and NetId(#1@0) in one rebuild; only a live parent may be indexed`. It throws inside `AttachmentSystem.onTick`. It also takes down every `AttachmentSystemTest` and `AttachmentDeterminismTest` case that steps a world with a mount in it - six cases that ask the index nothing, five of which predate this branch. Reported as a miss rather than reconciled: the prediction was about which assertions would disagree, and what actually happens is an exception in a system.

### M5 — every query becomes the issue's own full-world scan

```diff
--- /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/pristine/AttachmentIndex.kt	2026-09-21 02:25:25.888386426 +0000
+++ /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a2b587ce4b7563c14/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/AttachmentIndex.kt	2026-09-21 02:39:46.645296895 +0000
@@ -1,6 +1,10 @@
 package dev.wildware.udea.core.spatial
 
+import com.github.quillraven.fleks.Family
+import com.github.quillraven.fleks.World
+import com.github.quillraven.fleks.World.Companion.family
 import dev.wildware.udea.core.identity.NetId
+import dev.wildware.udea.core.identity.NetIdIndex
 
 /**
  * What is mounted **on** an entity: the other way round from [AttachedTo] (issue #270).
@@ -69,6 +73,40 @@
      */
     private var epoch: Long = 0L
 
+    // --- M5 ---------------------------------------------------------------------------------
+    internal var scanWorld: World? = null
+    internal var scanNetIds: NetIdIndex? = null
+    private var scanFamily: Family? = null
+
+    /** Entities read by every query since this was last reset. What the cost claim counts. */
+    public var scanVisits: Int = 0
+
+    private fun scan(parent: NetId): List<NetId> {
+        val world = scanWorld ?: return emptyList()
+        val netIds = scanNetIds ?: return emptyList()
+        val mounted = scanFamily ?: world.family { all(AttachedTo) }.also { scanFamily = it }
+        val found = ArrayList<NetId>()
+        with(world) {
+            mounted.forEach { part ->
+                scanVisits++
+                if (part[AttachedTo].parent == parent) found += netIds.netIdOf(part)
+            }
+        }
+        found.sort()
+        return found
+    }
+
+    private fun scanNode(parent: NetId, node: Int): NetId {
+        val world = scanWorld ?: return NetId.NONE
+        val netIds = scanNetIds ?: return NetId.NONE
+        for (child in scan(parent)) {
+            val entity = netIds.resolveOrNull(child) ?: continue
+            if (with(world) { entity[AttachedTo].node } == node) return child
+        }
+        return NetId.NONE
+    }
+    // --- end M5 -----------------------------------------------------------------------------
+
     /** The last **completed** rebuild. Queries compare against this, so a half-built index reads empty. */
     private var readable: Long = NEVER
 
@@ -112,18 +150,14 @@
      * How many parts are mounted on [parent], directly. A part mounted on one of those parts is
      * that part's child, not this one's.
      */
-    public fun childCount(parent: NetId): Int {
-        val bucket = bucketOf(parent)
-        return if (bucket < 0) 0 else bucketCount[bucket]
-    }
+    public fun childCount(parent: NetId): Int = scan(parent).size
 
     /**
      * The [position]th part mounted on [parent], in ascending [NetId] order.
      *
      * @throws IndexOutOfBoundsException if [position] is not `0 until childCount(parent)`.
      */
-    public fun childAt(parent: NetId, position: Int): NetId =
-        NetId.ofRaw(childRaw[slotOf(parent, position)])
+    public fun childAt(parent: NetId, position: Int): NetId = scan(parent)[position]
 
     /**
      * The node the [position]th part is mounted on: a [ModelNode.index], or [ModelNode.NONE].
@@ -151,15 +185,7 @@
      * the lowest-[NetId] one - the same order [childAt] walks. Use [forEachChild] to see all of
      * them.
      */
-    public fun childOf(parent: NetId, node: Int): NetId {
-        val bucket = bucketOf(parent)
-        if (bucket < 0) return NetId.NONE
-        val start = bucketStart[bucket]
-        for (offset in 0 until bucketCount[bucket]) {
-            if (childNode[start + offset] == node) return NetId.ofRaw(childRaw[start + offset])
-        }
-        return NetId.NONE
-    }
+    public fun childOf(parent: NetId, node: Int): NetId = scanNode(parent, node)
 
     /** [childOf] by the node a part was mounted with: `childOf(chassis, Chassis.Nodes.socket_roof)`. */
     public fun childOf(parent: NetId, node: ModelNode): NetId = childOf(parent, node.index)
@@ -172,11 +198,11 @@
      * same reason `NetIdVisitor` is one.
      */
     public fun forEachChild(parent: NetId, visitor: AttachmentVisitor) {
-        val bucket = bucketOf(parent)
-        if (bucket < 0) return
-        val start = bucketStart[bucket]
-        for (offset in 0 until bucketCount[bucket]) {
-            visitor.visit(NetId.ofRaw(childRaw[start + offset]), childNode[start + offset])
+        val world = scanWorld ?: return
+        val netIds = scanNetIds ?: return
+        for (child in scan(parent)) {
+            val entity = netIds.resolveOrNull(child) ?: continue
+            visitor.visit(child, with(world) { entity[AttachedTo].node })
         }
     }
 
--- /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/pristine/AttachmentSystem.kt	2026-09-21 02:25:25.890386465 +0000
+++ /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a2b587ce4b7563c14/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/AttachmentSystem.kt	2026-09-21 02:39:46.645296895 +0000
@@ -68,6 +68,8 @@
     private val offset = MountFrame()
 
     override fun onTick() {
+        attachments.scanWorld = world
+        attachments.scanNetIds = netIds
         attachments.beginRebuild()
         mounted.forEach { part ->
             // Only a mount whose parent is live is indexed: a stale parent id cannot be asked
```

Ran: :udea-core:jvmTest. Exit 1. XML: 72 files, 524 tests, 0 skipped, 2 failures, 0 errors.

- RED: `AttachmentIndexCostTest.an answer needs no entity in the world at all()[jvm]`
- RED: `AttachmentIndexTest.a stale parent id answers nothing even when its index has been handed out again()[jvm]`

**Missed, and the miss is the better finding.** The cost case went red as predicted. The second red is `a stale parent id answers nothing even when its index has been handed out again`, and it fails `the stale id carries nothing ==> expected: <0> but was: <1>`. The orphan part still carries the dead parent's id in its `AttachedTo`, so a `part[AttachedTo].parent == parent` scan hands it back; the index refuses it because only a mount whose parent resolves live is ever recorded. So the pattern this issue removes was not merely slower - it answered with a child of a destroyed parent whose id slot had been reused. That is a correctness result the ticket did not claim and I did not predict.

### M6 — `DrawnModels.sync` never attaches a missing renderer

```diff
--- /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/pristine/DrawnModels.kt	2026-09-21 02:25:25.893386524 +0000
+++ /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a2b587ce4b7563c14/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/DrawnModels.kt	2026-09-21 02:34:59.361655470 +0000
@@ -76,7 +76,7 @@
                 }
                 val wanted = library.modelAt(AssetIndex(slot))
                 if (renderer == null) {
-                    entity.configure { it += ModelRenderer(wanted) }
+                    // M6: a missing renderer is never attached.
                 } else {
                     renderer.model = wanted
                 }
```

Ran: `:udea-render:jvmTest` and `:udea-render:udeaGlTest`, under xvfb with GL required. Exit 1. XML: 76 files, 411 tests, 0 skipped, 5 failures, 0 errors.

- RED: `DrawnModelsTest.a Drawn that names nothing takes the model back off()[jvm]`
- RED: `DrawnModelsTest.a frame in which nothing changed writes nothing and asks the library nothing()[jvm]`
- RED: `DrawnModelsTest.an entity the simulation gave a model draws it, with no game-written bridge()[jvm]`
- RED: `DrawnModelsTest.an entity whose model changes is repointed rather than given a new renderer()[jvm]`
- RED: `GlDrawnModelTest.an entity the simulation gave an asset reference draws itself()`

**Missed by one, and the extra red is a test dying in its own setup.** Four `DrawnModelsTest` cases, not three, plus the GL case. The fourth is `a Drawn that names nothing takes the model back off`, whose first line is `assertNotNull(rendererOf(part), "attached")` - it reaches the behaviour it is named for through the attach path, so M6 kills it before it gets there. Left as a miss: a count is a claim, and this one was wrong.

### M7 — `DrawnModels` replaces the renderer instead of repointing it

```diff
--- /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/pristine/DrawnModels.kt	2026-09-21 02:25:25.893386524 +0000
+++ /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a2b587ce4b7563c14/udea-render/src/commonMain/kotlin/dev/wildware/udea/render/model/DrawnModels.kt	2026-09-21 02:37:25.431524052 +0000
@@ -78,7 +78,8 @@
                 if (renderer == null) {
                     entity.configure { it += ModelRenderer(wanted) }
                 } else {
-                    renderer.model = wanted
+                    // M7: replace rather than repoint.
+                    entity.configure { it += ModelRenderer(wanted) }
                 }
                 syncedSlot[entity.id] = slot
                 changed++
```

Ran: `:udea-render:jvmTest`. Exit 1. XML: 47 files, 381 tests, 0 skipped, 1 failures, 0 errors.

- RED: `DrawnModelsTest.an entity whose model changes is repointed rather than given a new renderer()[jvm]`

Exactly the predicted case.

### M8 — `Drawn.model` becomes `@Sim` instead of `@Net`

```diff
--- /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/pristine/Drawn.kt	2026-09-21 02:25:25.892386504 +0000
+++ /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a2b587ce4b7563c14/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/Drawn.kt	2026-09-21 02:38:00.425211211 +0000
@@ -2,7 +2,7 @@
 
 import com.github.quillraven.fleks.Component
 import com.github.quillraven.fleks.ComponentType
-import dev.wildware.udea.annotations.Net
+import dev.wildware.udea.annotations.Sim
 import dev.wildware.udea.annotations.Replicated
 import dev.wildware.udea.assets.AssetIndex
 import dev.wildware.udea.assets.AssetRegistry
@@ -72,7 +72,7 @@
      * A `var`: a unit that is upgraded, damaged into a wreck or disguised changes what it draws
      * by assigning here, and the renderer follows on the next frame.
      */
-    @Net public var model: Int = NONE,
+    @Sim public var model: Int = NONE,
 ) : Component<Drawn> {
 
     /**
```

Ran: :udea-core:jvmTest. Exit 1. XML: 72 files, 524 tests, 0 skipped, 1 failures, 0 errors.

- RED: `DrawnSnapshotTypeTest.the model an entity draws is sent to clients, not only snapshotted()[jvm]`

Exactly the predicted case.

### M9 — `AttachmentIndex.NEVER` goes back to `0L`

```diff
--- /tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/pristine/AttachmentIndex.kt	2026-09-21 02:25:25.888386426 +0000
+++ /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a2b587ce4b7563c14/udea-core/src/commonMain/kotlin/dev/wildware/udea/core/spatial/AttachmentIndex.kt	2026-09-21 02:38:28.782768042 +0000
@@ -353,7 +353,7 @@
          * would compare equal and every query before the first tick would read the never-written
          * arrays instead of answering empty.
          */
-        const val NEVER: Long = -1L
+        const val NEVER: Long = 0L
 
         /** Room for this many parent indices and mounts before the arrays grow. */
         const val INITIAL_PARENTS = 64
```

Ran: :udea-core:jvmTest. Exit 1. XML: 72 files, 524 tests, 0 skipped, 1 failures, 0 errors.

- RED: `AttachmentIndexTest.an index that has published no rebuild answers nothing even with entries recorded in it()[jvm]`

Exactly the predicted case, and only it. The sentinel fix is asserted by one test that dies when the sentinels collide and by nothing else - which is what makes it a discriminator rather than a second copy of the cases that were already green either way.
---

## `sh gradlew build`

**Three full builds are reported here, and only the third is the one to read.** The first was warm -
`BUILD SUCCESSFUL in 1m 12s`, `1122 actionable tasks: 44 executed, 8 from cache, 1070 up-to-date`,
with `:udea-render:udeaGlTest` **`FROM-CACHE`** - so most of it proved nothing; a restored test task
and one that ran are indistinguishable. The second was `clean` with `--no-build-cache`:
`BUILD SUCCESSFUL in 8m 8s`, `1122 actionable tasks: 1039 executed, 83 up-to-date`, 0 failed. The
third is the same thing again after merging `origin/master`, and it is the one that describes the
SHA at the top of this file:

```
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew build --continue --no-configuration-cache --max-workers=4
```

```
[... every task line elided; the full log is on the box ...]
BUILD SUCCESSFUL in 7m 52s
1122 actionable tasks: 830 executed, 147 from cache, 145 up-to-date
EXIT=0
DONE
```

No `-x`, and no task line in that log reads `FAILED`: `grep -cE '^> Task .* FAILED'` returns `0`.
The anchor matters, because an unanchored `FAILED` also matches `BUILD FAILED`. **And the zero is
only worth anything because the same pattern was run against a log that should match**: the `M6`
mutation run, where it returns `2` and prints `> Task :udea-render:jvmTest FAILED` and
`> Task :udea-render:udeaGlTest FAILED`. A grep nobody has seen produce output is not a check.
Exit status read off the marker the script echoed, not off the text.

**The iOS compile is the one to notice.** `:udea-core:compileTestKotlinIosArm64` and
`:udea-core:compileTestKotlinIosSimulatorArm64` **executed** in the clean build. They are what caught
the only real failure this branch has had: a comma inside a backtick test name, which compiles on
the JVM and fails Kotlin/Native with `Name contains illegal characters: ","`. The test is now named
without one and its KDoc says why. **`:udea-core:iosSimulatorArm64Test` is `SKIPPED`** on this Linux
box - the log says so in as many words - and nothing here claims iOS was run.

### GL, for real, under xvfb

`check` depends on `udeaGlTest`, `udeaAgentGlTest` and `udeaEditorGlTest`, and with no `DISPLAY` and
no `-Pudea.render.requireGl=true` they **skip** while the build stays green. `$DISPLAY` is empty on
this box. So the GL half is this, separately - and it is also the evidence command:

```
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew :udea-core:jvmTest :udea-render:jvmTest :udea-render:udeaGlTest \
    -Pudea.render.requireGl=true --no-build-cache
```

```
[... task output elided ...]
191 actionable tasks: 16 executed, 175 up-to-date
Configuration cache entry stored.
EXIT=0
XML kept: 149 files
DONE
```

Counted out of the JUnit XML of that run, not off the console:

- all three tasks together: **149 XML files, 941 tests, 0 skipped, 0 failures, 0 errors**
- `:udea-render:udeaGlTest` alone: **29 files, 30 tests, 0 skipped, 0 failures**
- `GlDrawnModelTest`: `tests=1 skipped=0 failures=0 time=1.426`, and its own `system-out` says
  `GlDrawnModelTest: the fox appeared after 2 captured frame(s), 8898 pixels` and
  `GlDrawnModelTest: fox=8898 orange=0.84007645`

**And here is what the same GL task did inside the clean `build` above, on the same tree: 29 files,
30 tests, `skipped=29`.** Twenty-nine of the thirty skipped for want of a `DISPLAY` and the build
went green anyway. The one that ran is
`OffscreenBackendTest.Headless is refused rather than quietly opening a window`, which needs no
context to make its point. So a green `sh gradlew build` on this box is evidence about everything
except GL, and that is exactly why the run above exists.
---

## The merge with `origin/master`, and why it needed checking rather than trusting

I cut this branch at `edaded0`. By the time the work was done `origin/master` was at **`0378a95`**,
twelve commits on, and `git merge-base --is-ancestor edaded0 origin/master` says my base is still an
ancestor of it - so the branch is behind rather than diverged.

**One of those twelve is #274, and it changed the machinery this ticket edits.** It moved
`net-components.lock` to being read by the conventions from the root project and handed to every
module that runs KSP, deleted the per-module `ksp { arg("udea.projectComponents", ...) }` blocks,
and added `udeaWriteNetComponents`. So `udea-core/build.gradle.kts` and `net-components.lock` were
both touched by that change and by mine.

**The merge reported no conflict, which is exactly when the brief says not to believe it:** two
branches that both add a component merge with zero textual conflicts and produce a lock that agrees
with neither. So I regenerated rather than read:

```
sh gradlew :udea-core:udeaWriteProtocolLock
```

`LOCKWRITE_EXIT=0`, and the task said
`:udea-core: wrote /srv/.../udea-core/net-protocol.lock`. Then `git status --porcelain` **named
each of the seven files** - `udea-core/net-protocol.lock`, `net-components.lock`,
`expected-generated-hashes.txt`, `moba/game/net-protocol.lock`, `hollow/game/net-protocol.lock`,
`udea-codegen/net-protocol.lock` and the new `templates/new-game/net-components.lock` - and printed
**nothing**. Regenerating on the merged tree reproduced byte for byte what I had committed before
the merge: no id moved, and `Drawn` is still 30.

The whole build was then run again on the merged tree, and its result is in the section above.
---

## Images

All in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`.

| File | What it shows | What it proves |
|---|---|---|
| `issue270-before-and-after.png` | the same GL scene twice: left with the feature reverted (mutation `REVERT`), right with it in | the whole of AC2 in one picture. Same test, same camera, same tick; the only difference is whether the engine turns a `Drawn` into a `ModelRenderer` |
| `issue270-reverted-nothing-drawn.png` | the left tile alone: an entity carrying `Transform3D` and `Drawn` and an empty frame | the evidence command is not vacuous. This is what `GlDrawnModelTest` captures when the bridge is taken out - the entity exists, the asset resolves, and nothing draws |
| `issue270-drawn-from-asset-reference.png` | the right tile alone: the fox, drawn from `Drawn(reference<Model>("models/fox"), assets)` | AC2. The game named an asset; the engine loaded it, attached the renderer on the Kool render thread, and drew it |
| `issue270-moba-still-runs.png` | `moba` running live over the agent bridge, `render.screenshot` | no regression from the new component id and the new `PostPhysics` system. `moba` mounts nothing and draws no `Drawn`, so this is a control rather than a demonstration |

**AC1 has no picture, and that is the honest answer rather than a gap I am glossing.** A reverse
index is not a thing a frame can show: the same frame is drawn whether the answer came from an array
read or from a 20 000-entity scan. Its evidence is the test transcript and the mutation table, where
M5 puts the issue's own scan back in and a case goes red. Inventing a HUD to photograph it would be
a picture of the HUD.

---

## The issue, criterion by criterion

### AC1 — "A game asks the engine what is mounted on an entity, and in a given socket, without scanning the world."

| Half of the claim | What proves it |
|---|---|
| asks what is mounted on an entity | `AttachmentIndexTest.the parts mounted on a chassis are its parts and nobody else's`, `a part mounted on a part belongs to that part and not to the chassis`, `a part with no transform is still mounted`, `each reported part carries the node it is mounted on` |
| and in a given socket | `AttachmentIndexTest.the part in a socket is found by the node it is mounted on`, `swapping the part in a socket changes what that socket answers` — which is the issue's own hijacking case |
| **without scanning the world** | `AttachmentIndexCostTest.an answer needs no entity in the world at all`: 20 000 mounted parts, every entity then removed, the same four answers. M5 replaces the query with the issue's literal scan and that case goes red. See P2 for where the 0 and the 20 000 come from |
| exposed as a service like `NetIdIndex` is | `ctx[CoreModule.ATTACHMENTS]`, the same `ServiceKey` shape as `CoreModule.NET_IDS`. Exercised through `GameContext` by `AttachmentDeterminismTest` |
| and it is still right | the four freshness cases in the table above, and `a stale parent id answers nothing even when its index has been handed out again` |

**Where I departed from the issue's suggestion, and why.** The issue suggests
`attachments.childrenOf(netId)`. A method returning a collection allocates per question, and the
questions in the issue - draw a loadout in the HUD, count what a unit carries - are per-frame or
per-tick. So the shape is `childCount(parent)` / `childAt(parent, i)` / `nodeAt(parent, i)` for
indexed reads, `forEachChild(parent, visitor)` for the sweep, and `childOf(parent, node)` for the
swap case the issue names directly. Nothing about the index would have to change to add a
list-returning convenience later; the reverse is not true. If the owner would rather have
`childrenOf` returning a `List<NetId>`, it is an addition on top of what is here, not a rewrite.

### AC2 — "A game spawns an entity with an asset reference and it draws, with no game-written bridge to `ModelRenderer`."

| Half of the claim | What proves it |
|---|---|
| spawns with an asset reference | `Drawn(reference<Model>("models/fox"), assets)` - the constructor takes the `Ref<Model>` the issue asks for, and an asset that is not a model is refused where it is written: `DrawnSnapshotTypeTest.a reference that does not name a model is refused where it is written` |
| **and it draws** | the picture. `GlDrawnModelTest` spawns an entity with `Transform3D` and `Drawn` and nothing else, and gets a fox: 8898 lit pixels, orange share 0.84. `issue270-before-and-after.png` |
| with no game-written bridge | the same test asserts `ModelRenderer` was attached **by the engine**, and that `ModelRenderSystem.drawnCount == 1`. M6 removes the attach and both the GL case and four `DrawnModelsTest` cases go red |
| replicable and snapshot-safe | `DrawnSnapshotTypeTest.the model an entity draws comes back from a snapshot` and `...is sent to clients, not only snapshotted`. M8 flips `@Net` to `@Sim` and the second one dies |
| render-side state stays render-side | `ModelRenderer` is untouched: not `@Replicated`, not `@Serializable`, absent from every `net-protocol.lock`. The sync runs inside `ModelRenderSystem.render()`, which is on the Kool render thread by construction |

**The one place the implementation does not match the issue's wording.** The issue suggests the
component hold a `Ref<Model>`. `Drawn.model` is an `AssetIndex` **slot** (an `Int`), and the
`Ref<Model>` is the constructor parameter. That is not a preference: `FieldLowering` supports
Boolean, Int, Long, Float, `NetId`, `Tick`, enums and one level of composite, and it rejects
generics outright - so a `Ref<Model>` field cannot be `@Net`, and a `Drawn` that cannot be sent to
a client fails the criterion above it. The slot is the interned index the asset registry already
hands out, it is stable across a run, and the typed constructor is what a game writes. A game never
writes the `Int` unless it has one already (`Drawn.at(slot)`).

---

## Regenerated files

**`net-components.lock`** — hand-edited, as it is meant to be: `dev.wildware.udea.core.spatial.Drawn`
inserted between `spatial.AttachedTo` and `spatial.Transform3D`, with a paragraph of rationale in
the file's own house style.

**`udea-core/net-protocol.lock`** — regenerated by `sh gradlew :udea-core:udeaWriteProtocolLock`,
never hand-edited. The literal diff:

```diff
diff --git a/udea-core/net-protocol.lock b/udea-core/net-protocol.lock
index c886a35..5f4a4d8 100644
--- a/udea-core/net-protocol.lock
+++ b/udea-core/net-protocol.lock
@@ -13,7 +13,7 @@
 #
 # Generated by udea-codegen. Do not edit by hand.
 lockFormat 1
-protoHash 0xad48
+protoHash 0x0c1d
 component 23 dev.wildware.udea.core.physics.Box
   field 0 halfHeight f32:32
   field 1 halfWidth f32:32
@@ -68,7 +68,9 @@ component 29 dev.wildware.udea.core.spatial.AttachedTo
   field 15 offsetY f32:32
   field 16 offsetZ f32:32
   field 17 parent netid:32
-component 30 dev.wildware.udea.core.spatial.Transform3D
+component 30 dev.wildware.udea.core.spatial.Drawn
+  field 0 model i32:32
+component 31 dev.wildware.udea.core.spatial.Transform3D
   field 0 rotationX f32:32
   field 1 rotationY f32:32
   field 2 rotationZ f32:32
```

**The ids moved by one, and only inside `udea-core`.** `Drawn` takes **30**; `Transform3D` moves
**30 → 31**. Nothing else in that file moves, because `Drawn` sorts after every other component in
it except `Transform3D`.

**`protoHash 0xad48 → 0x0c1d`** is a consequence, not a decision: it is a `u16` over the file's
non-comment content, so any id movement changes it. It is a wire-format change and it is harmless
today - nothing has been released but a `0.1.0-SNAPSHOT`, and no client is connected to anything.
Said here because a `protoHash` move is the kind of thing that is obvious in the diff and invisible
in a summary.

**Two components have no lock of their own and still moved.** `udea-nav`'s `NavAgent` **31 → 32**
and `NavObstacle` **32 → 33** in `net-components.lock`. `udea-nav` has no `net-protocol.lock`, so
nothing else on disk records those two ids; they are named here because a reviewer reading only the
`.lock` diffs would not see them move.

### What did **not** move, checked by name

`git status --porcelain -- <path>` per file, which answers about the path I asked about rather than
about the tidiness of the tree. Empty output for all of:

- `udea-codegen/src/test/resources/expected-generated-hashes.txt`
- `moba/game/net-protocol.lock`
- `hollow/game/net-protocol.lock`
- `udea-codegen/net-protocol.lock`
- `*.udearep` — the two checked-in replay fixtures,
  `udea-replay/src/jvmTestFixtures/resources/fixtures/drift-3600.udearep` and `drift-36000.udearep`,
  whose `BuildIdentity` carries an asset-graph hash. Neither moved, so there is nothing to measure.

**And the positive control, because an empty result from a command nobody has seen produce output
is not a result.** The same command shape, on `udea-core/net-protocol.lock`, prints
` M udea-core/net-protocol.lock`. So the empties above are the command working, not the command
being silent.

---

## My own pass over the diff, against the reject list

Not a claim that the reviewer will agree - a record of what I looked at, so the round it would
otherwise cost is spent here instead.

**`docs/engineering-standards.md` section 8.**

- *A rule from section 1 reproduced.* The two that bite this diff are **"Linear scans as lookups -
  if a lookup is on a per-tick path, it is indexed"**, which is the ticket, and **"Never a bare
  `Int` for a domain concept"**, which is the one a reviewer should look hardest at. `Drawn.model`
  is an `Int`. It is the same shape `AttachedTo.node` already is (`@Net public var node: Int =
  ModelNode.NONE`) and the same shape `moba`'s `Inventory.slot0`..`slot5` already are (`@Net ...
  var slot0: Int = EMPTY`, KDoc: *"an item's `AssetIndex` value"*). The reason is structural rather
  than stylistic: `FieldLowering` cannot restore a value class whose only property is a `val` in
  place, and `AssetIndex` is one. Every typed entry point takes the typed thing - the `Ref<Model>`
  constructor, `Drawn.at(AssetIndex)`, `show(AssetIndex?)`, `show(Ref<Model>, AssetRegistry)` - so a
  game writes the `Int` only if it already holds one.
- *A `public` declaration nobody outside the module uses.* Every new public member of
  `AttachmentIndex` is called by a test in this branch, and `nodeAt`, `mountCount` and both
  `childOf` overloads included. **In-tree, nothing outside `udea-core` calls them, and nothing
  outside `udea-render` calls `ModelLibrary`.** That is the ticket rather than an oversight: the
  consumer is robot-game (#256), which lives in its own repository and resolves the published
  engine (#265). `moba` mounts nothing on anything and draws no `Drawn`. Said plainly here so it is
  a decision on the record rather than something a reviewer discovers.
- *A test that cannot fail.* Nine mutations, every one with its literal diff and its red names
  below. One test was **repaired** because writing the prediction down showed it could not fail
  (M2), and one was **added** because a fix was otherwise unasserted (M9).
- *Generated code by string concatenation.* None; nothing here emits code.
- *A new field on `GameContext`.* None. `CoreModule.ATTACHMENTS` is a `ServiceKey`, the extension
  point `CoreModule.NET_IDS` already uses.
- *Wall clock or unseeded randomness in simulation.* None. `AttachmentIndex` reads no clock and no
  random; its freshness is an epoch counter it increments itself.
- *`TODO()`, a stubbed return, or a swallowed exception on a reachable path.* None.
  `ModelLibrary.modelAt` **throws** `ModelLoadException` naming the slot rather than substituting a
  placeholder, which is the loud-failure rule rather than the convenient one.
- *Copy-pasted logic differing only in a constant.* The two `childOf` overloads are one body; the
  `ModelNode` one delegates.
- *GL, Kool or ComposeGL outside `udea-render`.* `udea-core` gained `udea-assets`, which is plain
  data. `udea-assets` is in `HEADLESS_PROJECTS` and so is `udea-core`; `udeaVerifyModuleGraph` is
  green.

**`AGENTS.md`'s "Do not".** No `by net(...)`; no second codec (`Drawn` goes through the generated
`Replicator<T>` like everything else); no setter instrumentation; no wall clock or unseeded random
in `step()`; no LibGDX; no reflection on a per-tick path; no bare domain primitive beyond the
`Int` slot argued above; no GL outside `udea-render`; the new render work is a `RenderSystem`
member, not a Fleks system; the new arrow points downward.

**The four this repository makes blocking.** No `docs/contracts/` file changed
(`udeaVerifyContracts` green). The `fieldNames[i]` == mask bit *i* == store index *i* alignment is
asserted for `Drawn` directly, in `changing the model sets the bit fieldNames puts it at`. Nothing
here expresses a duration or a deadline at all - the index's epoch is a rebuild counter, not a time.
`AGENTS.md` is updated in this change and `udeaVerifyAgentsMd` is green.

### What I did not exercise

- **The empty case** - an entity with no mount at all, a `Drawn` naming nothing, an index before its
  first tick - is covered (`an id nothing is mounted on answers nothing rather than throwing`,
  `an entity that never named a model is left exactly as it was`, `before the first tick the index
  answers nothing`).
- **The full case** is 20 000 mounts, which is well past a game's load and short of any limit; I did
  not test what happens at `NetId` exhaustion, because that throws in `NetIdIndex` before the index
  is reached and is that type's own contract.
- **The second time through** is covered for the render half (`a frame in which nothing changed
  writes nothing and asks the library nothing`) and for the sim half by every case that runs more
  than one tick.
- **The way back out** - detach, destroy, rewind - is the four freshness cases.
- **What I did not cover:** a `Drawn` on an entity with no `Transform3D`; concurrent mutation of
  `Drawn` from the simulation thread while the render thread is syncing (the same
  read-the-world-at-the-frame race every other render system already has, and not something this
  branch changes); and Android, where `udea-render` builds but no Kool backend runs.
- **iOS is compiled, not run.** `:udea-core:compileTestKotlinIosArm64` and
  `compileTestKotlinIosSimulatorArm64` **executed** in the clean build below - they are the tasks
  that caught a comma in a backtick test name, which compiles on the JVM and does not on
  Kotlin/Native. `iosSimulatorArm64Test` is `SKIPPED` on this Linux box and nothing here claims
  otherwise.

