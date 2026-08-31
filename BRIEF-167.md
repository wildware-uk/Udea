af14319

# Issue #167 — Honour `@Net` visibility `OwnerOnly` in the snapshot writer

`af14319` is the code. This brief is the one commit on top of it and touches nothing else, so the
branch head is one further along; the diff to review is `origin/example..HEAD`, and every number
and transcript below was produced at `af14319`.

Branch `issue-167-owneronly-visibility`, rebased onto `origin/example` at `1f6cddd` (it moved
under me while I worked; the three commits it gained are a logo and a README line and touch nothing
this branch does, and the rebase was clean). Everything below was re-run after the rebase.
Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a3079d2d31be163cf`.

---

## 1. The evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew --console=plain \
  :udea-net:test    --tests '*OwnerOnlyVisibilityTest*' \
  :udea-codegen:test --tests '*GeneratedVisibilityTest*' \
  :moba:test        --tests '*InventoryVisibilityTest*'
```

One invocation, three named classes: the mechanism against a hand-written fixture replicator over
a real `ReplicationServer`, the generator against the real KSP processor, and the shipped game
against a real generated replicator and two live clients.

**Why not `:moba:runNetProof`**, which is the table's row for a replication ticket: it does **not**
go red when this feature is reverted, and it cannot. Its verdict is the same on `origin/example`
and on this branch (see §4), because `NetStateProbe` was narrowed in the same commit so that the
proof keeps measuring a true statement. A command that reports the same thing either way is not
evidence, and saying it was would be the exact error the developer contract is about.

### It goes red when the feature is reverted

Two mutations, each restored afterwards, each with its literal `git diff` from the run and the
test names from that run's console output. Both were taken at `af14319`.

#### Mutation A — the stripping reverted at its single point

```diff
diff --git a/udea-net/src/main/kotlin/dev/wildware/udea/net/wire/Visibility.kt b/udea-net/src/main/kotlin/dev/wildware/udea/net/wire/Visibility.kt
index 9acfdb4..f407735 100644
--- a/udea-net/src/main/kotlin/dev/wildware/udea/net/wire/Visibility.kt
+++ b/udea-net/src/main/kotlin/dev/wildware/udea/net/wire/Visibility.kt
@@ -86,7 +86,7 @@ public object VisibilityPolicy {
      * an owner-only field must not reach a non-owner by any of those routes.
      */
     public fun visibleMask(replicator: Replicator<*>, recipientOwnsEntity: Boolean): FieldMask =
-        if (recipientOwnsEntity) replicator.netMask else MaskOps.andNot(replicator.netMask, ownerOnlyMask(replicator))
+        replicator.netMask
 
     /** [OwnerOnlyFields.ownerOnlyMask], or empty for a replicator that declares none. */
     public fun ownerOnlyMask(replicator: Replicator<*>): FieldMask {
```

Evidence command output, `EXIT=1`. Lines 127-158 of `…/scratchpad/dev-167/mutation-a-run.txt`,
one contiguous run with no elisions:

```
> Task :udea-net:test FAILED

OwnerOnlyVisibilityTest > a component declaring no owner only field costs a non-owner nothing() FAILED
    org.opentest4j.AssertionFailedError at OwnerOnlyVisibilityTest.kt:224

OwnerOnlyVisibilityTest > the owner only mask is a subset of the net mask and is what the visible mask removes() FAILED
    org.opentest4j.AssertionFailedError at OwnerOnlyVisibilityTest.kt:95

OwnerOnlyVisibilityTest > the owner receives an owner only field and a non-owner never does() FAILED
    org.opentest4j.AssertionFailedError at OwnerOnlyVisibilityTest.kt:144

OwnerOnlyVisibilityTest > stripping clears a bit and never renumbers one() FAILED
    org.opentest4j.AssertionFailedError at OwnerOnlyVisibilityTest.kt:188

6 tests completed, 4 failed

> Task :moba:test FAILED

InventoryVisibilityTest > the generated replicator declares every inventory slot owner-only() FAILED
    org.opentest4j.AssertionFailedError at InventoryVisibilityTest.kt:50

InventoryVisibilityTest > a player is sent its own inventory and never another player's() FAILED
    org.opentest4j.AssertionFailedError at InventoryVisibilityTest.kt:88

2 tests completed, 2 failed

FAILURE: Build completed with 2 failures.

1: Task failed with an exception.
-----------
* What went wrong:
Execution failed for task ':udea-net:test'.
```

`GeneratedVisibilityTest` stays green under A, correctly: A reverts the runtime stripping and not
the generator, and that test is about what the processor emits. Mutation A alone therefore does
not prove the generator half; the "before" red in §3 does.

#### Mutation B — the wrong bits stripped, which is the misalignment shape

```diff
diff --git a/udea-net/src/main/kotlin/dev/wildware/udea/net/wire/Visibility.kt b/udea-net/src/main/kotlin/dev/wildware/udea/net/wire/Visibility.kt
index 9acfdb4..2bc5883 100644
--- a/udea-net/src/main/kotlin/dev/wildware/udea/net/wire/Visibility.kt
+++ b/udea-net/src/main/kotlin/dev/wildware/udea/net/wire/Visibility.kt
@@ -86,7 +86,7 @@ public object VisibilityPolicy {
      * an owner-only field must not reach a non-owner by any of those routes.
      */
     public fun visibleMask(replicator: Replicator<*>, recipientOwnsEntity: Boolean): FieldMask =
-        if (recipientOwnsEntity) replicator.netMask else MaskOps.andNot(replicator.netMask, ownerOnlyMask(replicator))
+        if (recipientOwnsEntity) replicator.netMask else MaskOps.andNot(replicator.netMask, MaskOps.single(1))
```

The same six test names fail (`mutation-b-run.txt`). Two of the messages differ from A's, and both
differences are the point. The byte-cost test now fails on its *first* assertion rather than on its
control, because B strips bit 1 from every recipient including components that declare nothing
owner-only — from `mutation-b-results.xml`, with the line break its own:

```
a non-owner's packet differs from the owner's for a world whose components declare no owner-only field at all. Array sizes differ. Expected size is 24, actual size is 19.
Expected <[2, 0, 8, 28, 0, 0, -32, 15, 0, 0, 0, -48, 0, 0, 0, 64, -64, 55, 0, 0, 0, 0, 0, 0]>, actual <[2, 0, 8, 20, 0, 0, -32, -49, 0, 0, 0, 64, 64, 55, 0, 0, 0, 0, 0]>.
```

And the alignment test, whose message from that run's JUnit XML (kept as
`mutation-b-results.xml`) reads:

```
the non-owner's only difference from the server must be the owner-only field, named as `gold`;
a different name here is the alignment being broken rather than a second field going missing
==> expected: <[gold]> but was: <[y, level]>
```

It named the wrong fields, which is precisely the failure mode acceptance criterion 2 exists for:
a stripping bug that decodes cleanly and makes `desync_report` lie. Under mutation A the same
assertion reads `expected: <[gold]> but was: <[]>` — the field simply arrived, so there was nothing
to report — which is a different failure and the right one for that mutation.

Both restored afterwards; the working tree carries neither (`git status` shows only `M gradlew`,
the `chmod +x` this box needs, and which is deliberately not committed). The XML both sets of
messages were read out of is kept as `mutation-a-results.xml` and `mutation-b-results.xml`.

---

## 2. What I did, and what I decided

`Visibility.OwnerOnly` was declared and read by nothing. `udea-codegen` never looked at the
argument and `udea-net` had no per-recipient field stripping, so `@Net(visibility = OwnerOnly)`
compiled, read as a guarantee, and did nothing — and did nothing in the leaking direction, which
is what separates it from #114. `moba`'s `Inventory` declares it on all seven slots, so both
players in a match were sent each other's items.

Four pieces, mirroring #114:

- **`udea-net/wire/Visibility.kt`** — `OwnerOnlyFields` beside `CreateOnlyFields`, and
  `VisibilityPolicy` beside `LifetimePolicy`. `Replicator` is frozen, so the mask is a marker the
  generator opts into rather than a widening of the contract.
- **`udea-codegen`** reads `@Net(visibility = …)` and emits `ownerOnlyMask` **only** for a
  component that declares one. `ComponentModelBuilder`'s `lifetimeIsOnCreate()` became a general
  `netEnumArgumentIs(argument, constant)` used by both axes, rather than a second near-identical
  copy of the KSP-shape handling.
- **`SnapshotWriter`** takes `recipientOwnsEntity` on all three write entry points and intersects
  the visible mask into whatever the op decided. One `and`, covering the create, the baseline-loss
  full resend and the delta together.
- **`ReplicationServer`** asks an ownership seam once per entity per recipient.

### Decisions

**The ownership seam is `RpcOwnership`, not a new `OwnershipSet`.** The issue's scope asks for "an
ownership seam on `ReplicationServer`, shaped like `RelevancySet`". `udea-net` already has a
one-method interface with exactly the right signature and the right safe default —
`RpcOwnership.ownerOf(entity): PeerId`, `RpcOwnership.NONE` = "nothing is client-owned" — and a
game already holds an instance of it, because the generated RPC guard reads it. `moba`'s
`ChampionOwnership` is that instance. Minting a second registry would be a second thing that can
disagree with the first, and the disagreement would be silent and in the leaking direction: a
champion the RPC guard says a peer owns and the packer says it does not. `ReplicationServer`'s own
`writeRemovals` KDoc already states the principle ("the roster already exists … and a second one
is a second thing that can disagree"). *Rejected:* a new `OwnershipSet` with `isOwner(client,
netId)`. *If the owner disagrees:* it is a new `fun interface` in
`udea-net/replication`, a defaulted constructor parameter on `ReplicationServer`, and
`ChampionOwnership` implementing both — about twenty lines, and nothing else moves.

**`recipientOwnsEntity` has no default value.** Every call site states it. *Rejected:* defaulting
to `true` (an unaware caller leaks) or to `false` (an unaware caller silently drops data). A
default is how a new call site gets this wrong without anybody noticing, and the whole issue is
about a silent leak. Cost: eight call sites edited, all in tests.

**The default ownership is "nobody owns anything".** A `ReplicationServer` that has not been told
who owns what sends no owner-only field to anybody. That is the reversible failure (data missing,
loudly, for the owner too) rather than the irreversible one (data leaked). It is also why
`InventoryVisibilityTest` was red on the *owner's* assertion before `MobaHostSession` was wired —
see §3.

**`NetStateProbe` now folds `netMask and ownerOnlyMask.inv()`.** See §4 — this is the one thing in
the change that a reviewer should look at hardest, and it is a narrowing of a claim, stated in the
probe's own KDoc.

**`DesyncReport` was deliberately *not* narrowed.** It is handed a server store and a client store
and is never told who owns what, so its only available filter would be "drop owner-only
everywhere" — which would also stop it reporting a genuine desync on an *owner's own* private
field, the case an operator most needs. It therefore reports one row per owner-only field per
foreign entity in a converged `moba` session, and that is a true answer to "what does this client
not have". A KDoc section on `DesyncReport` says so. The number that must be quiet in a converged
session is a hash, and that is `NetStateProbe`, which was narrowed.

**No `docs/contracts/` file was changed.** This implements the frozen authority vocabulary; it
does not alter it. The `fieldNames[i]` == mask bit *i* == `FieldStore` index *i* alignment is
preserved structurally — `Replicator.write` emits a fixed-width mask — and asserted behaviourally
against `DesyncReport`'s field naming.

**`AGENTS.md` needed no edit.** No module moved, and its frozen-contracts table already lists
`visibility = All | OwnerOnly`. `udeaVerifyAgentsMd` passes.

---

## 3. Failing test first — the reds, before the implementation

Each was produced by running the test against a tree that did not yet have the corresponding
implementation, and each XML is kept under
`…/scratchpad/dev-167/`. Messages spliced from those files.

**`udea-net`, before `SnapshotWriter` applied the mask** (`red-before-implementation.xml`) —
3 of 6 failed:

```
an owner-only field was declared and the two recipients got identical bytes
a non-owner was sent an owner-only field; 0 is this store's untouched value and the server has never held 0 in it ==> expected: <0> but was: <4321>
the non-owner's only difference from the server must be the owner-only field, named as `gold`; a different name here is the alignment being broken rather than a second field going missing ==> expected: <[gold]> but was: <[]>
```

**`udea-codegen`, before the emitter learned the argument** (`red-codegen-before.xml`) — 4 of 5
failed, e.g.:

```
the generated replicator does not implement OwnerOnlyFields:
// Generated by udea-codegen from @Rep…
```

**`moba`, after codegen and the writer but before `MobaHostSession` passed its ownership**
(`red-moba-before-wiring.xml`) — 1 of 2 failed, and the *owner* was the one who lost the field,
which is what the safe default is supposed to do:

```
client 1 was not sent its own champion's inventory ==> expected: <4242> but was: <-1>
```

---

## 4. `sh gradlew build`

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew clean build --console=plain
```

`EXIT=0`, and `grep -E FAILED` over the whole log returns nothing. Tail, spliced from
`…/scratchpad/dev-167/build-final.txt`:

```
> Task :check
> Task :build
> Task :moba:test
> Task :moba:check
> Task :moba:build
> Task :udea-compiler-plugin:test
> Task :udea-compiler-plugin:check
> Task :udea-compiler-plugin:build

BUILD SUCCESSFUL in 47s
225 actionable tasks: 139 executed, 80 from cache, 6 up-to-date
```

Counted off every `TEST-*.xml` in the tree afterwards: **372 suites, 2484 tests, 0 failures,
0 errors, 34 skipped.** (The contract's recorded 2447 at `8035374` is an older tree; this branch
adds 13 tests.)

### Two earlier full-build attempts failed, and both were the documented box effect

`melon-merge` was running a `lwjgl3:run` scenario suite throughout; no other Udea `gradlew` build
was on the box (`pgrep -af "[g]radlew"` each time). Load averages 7.5 → 29.8.

- **Attempt 1** (`build-1.txt`): `:udea-core:udeaBenchCharacterMover`,
  `:udea-assets-compiler:udeaDaemonBudget` and `:udea-assets-compiler:udeaPackGate` failed. Every
  failing line in that log is one of those four wall-clock budget tasks and nothing else:
  ```
  CharacterMoverBudgetTest > 200 movers replayed 60 times fit in the per-frame budget() STANDARD_OUT
      [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) median 7.090ms, budget 4.0ms
  ```
  Re-run alone (`budgets-solo-1.txt`), **EXIT=0**:
  ```
      [CharacterMoverBudgetTest] 200 movers x 60 replays (12000 move calls) median 2.775ms, budget 4.0ms
      graph deserialisation: best=8.762736ms median=11.013849ms over 2000 assets (budget 15ms)
      warm reload decision: median 366ms over 4 samples [382, 366, 260, 233]
      warm validate of one script: median 210ms over 4 samples [28, 267, 210, 204]
  BUILD SUCCESSFUL in 12s
  ```
- **Attempt 2** (`build-2.txt`): a *different* one failed — `:udea-agent-host:udeaPhase2Exit`, the
  fourth on the list. Re-run alone (`phase2exit-solo.txt`), **EXIT=0**:
  ```
      phase 2 exit: agent request -> running world observed changed in 392ms
  BUILD SUCCESSFUL in 53s
  ```

A different task failing each time, all four from the contract's named set, all passing solo. That
is the box, not the branch.

### GL

**This ticket touches no GL.** The changed modules are `udea-net`, `udea-codegen` and `moba`'s
networking and item code; nothing opens a context. I ran the GL gates for real anyway, because the
`build` above reported 34 skipped and an unexamined skip is worth one command:

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true --console=plain
```

with `--rerun-tasks --no-build-cache`, so the transcript is an execution and not a cache hit:

Lines 75-87 of `…/scratchpad/dev-167/gl-final.txt`, one contiguous run:

```
> Task :udea-render:udeaGlTest
> Task :udea-agent:jar
> Task :udea-agent-host:compileKotlin
> Task :udea-agent-host:compileJava NO-SOURCE
> Task :udea-agent-host:classes UP-TO-DATE
> Task :udea-agent-host:jar
> Task :udea-agent-host:compileTestKotlin
> Task :udea-agent-host:compileTestJava NO-SOURCE
> Task :udea-agent-host:testClasses UP-TO-DATE
> Task :udea-agent-host:udeaAgentGlTest

BUILD SUCCESSFUL in 39s
32 actionable tasks: 32 executed
```

Neither GL task carries a `FROM-CACHE` or `UP-TO-DATE` marker and all 32 tasks executed, which is
what says these ran rather than being replayed.

`udeaGlTest` 4 suites / 18 tests / 0 failures / 0 skipped; `udeaAgentGlTest` 2 suites / 8 tests /
0 failures / 0 skipped. Re-counting skips across the tree afterwards leaves **9**, all in
`:udea-assets-compiler:udeaPackGate` (`AtlasPackerTest` 7, `ReproducibilityTest` 2), unrelated to
this change — so the 34 the `build` run reported are those 9 plus the GL suites skipping for want
of a display.

### Gates outside `check`

| Task | On `origin/example` (`5dc9024`, this checkout, before any edit) | On `af14319` |
|---|---|---|
| `:moba:runNetProof` | `perfect DISAGREED` / `150ms+5% loss AGREED` / `TRELLO_8 AGREED` | identical |
| `udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd udeaVerifyMigration udeaLegacyReport` | — | `BUILD SUCCESSFUL` |
| `:moba:runUdpProof` | not run — documented red before this branch, and this change is not about it | not run |

The baseline is a real `--rerun-tasks` run I made in this worktree before touching anything,
`netproof-baseline-5dc9024.txt` lines 189-191:

```
[moba.netproof] perfect        units DISAGREED
[moba.netproof] 150ms+5% loss  units AGREED
[moba.netproof] TRELLO_8       units AGREED
```

and the after is `netproof-af14319.txt` lines 194-196, the same three lines. `perfect units
DISAGREED` is pre-existing and matches `HANDOFF.md`; I did not fix it and do not claim to have.

**The surprise, and the most important paragraph in this brief.** Between those two runs there is
a third (`netproof-after-wiring.txt`, lines 193-195) in which **all three** scenarios said `DISAGREED`:

```
[moba.netproof] perfect        units DISAGREED
[moba.netproof] 150ms+5% loss  units DISAGREED
[moba.netproof] TRELLO_8       units DISAGREED
```

Wiring `moba`'s ownership was correct and it broke the proof, because
`NetStateProbe` folds every `@Net` field of every present component and a client is now *supposed*
to be missing another champion's inventory. The probe's own KDoc already argued the general case —
it excludes `@Sim` because "hashing those would report a desync on every tick of a perfectly
converged session" — and `OwnerOnly` is the same argument in a new place. The fold is now
`netMask and ownerOnlyMask.inv()`: the closure of what *every relevant client* is promised. The
narrowing is real and the KDoc says so plainly: that number no longer says anything about whether
an owner received its own private fields, and the claim that it did moved to
`InventoryVisibilityTest` and `OwnerOnlyVisibilityTest`, both against live clients.

---

## 5. Images

There is **no on-screen consequence to this change**, and I would rather say so than dress a
screenshot up as proof. Nothing in `MobaHud` draws any champion's inventory: `grep -n
'gold\|item\|slot' moba/src/main/kotlin/dev/wildware/moba/MobaHud.kt` returns ability-slot and
`Attributes`-slot code and no reference to `Inventory` at all, and `Inventory` appears in no file
under `moba/src/main/kotlin/dev/wildware/moba/` outside `item/` and `net/NetStateProbe.kt`. So no
pixel differs before or after. The proof is a transcript and a test, in §1 and §3.

What the pictures below *do* prove is that a whole match still runs, renders and is won with the
change in, which is worth having and is not the same claim.

| File in `/srv/ssd1/workspace/Udea/build/debug-screenshots/` | What it shows | What it proves |
|---|---|---|
| `issue167-match-hud-after.png` | `MatchShot` at tick 561, HUD with a cooldown sweep running, 27 alive, score orc 2 / soldier 11 / undead 8 | the game renders and the HUD reads correctly after the change |
| `issue167-match-melee-after.png` | a melee exchange mid-match | combat is unaffected |
| `issue167-match-result-after.png` | tick 1864, match 1 won by team 1, decided on tick 1832 | a match still reaches a decided end state |
| `issue167-match-sequence.png` | the three above tiled | one card for the gallery |

Produced by `xvfb-run … sh gradlew :moba:runMatchShot` (it needs a real context; without a display
it dies with `GLFW_PLATFORM_UNAVAILABLE`).

---

## 6. Acceptance criteria

**1. A test asserts a non-owner's replica does not carry an `OwnerOnly` field, and the owner's
does — over the real snapshot path, not a unit of the mask.**

`OwnerOnlyVisibilityTest.the owner receives an owner only field and a non-owner never does`
(`udea-net`). Real `ReplicationSession`: real `SnapshotService` → real `SnapshotRing` → real
`ReplicationServer` packing a datagram per client per tick → two real `ReplicationClient`s
decoding. The owner reads `4321`, the non-owner reads the store's untouched `0` for a value the
server has never held, and **both** still read `level = 7` and `weapon = 9`, so the component was
stripped rather than dropped. The field is changed mid-session so the `Update` path is exercised
and not only the `Create`.

`InventoryVisibilityTest.a player is sent its own inventory and never another player's` (`moba`)
is the same claim against the **generated** `InventoryReplicator` over `MobaLoopbackSession` with
two clients: each reads its own `slot0` and finds no `Inventory` record at all for the other
champion — while still holding that champion, so the assertion is about fields and not about
relevancy. Red before the fix (§3), red under both mutations (§1).

**2. A test asserts the `fieldNames[i]` == FieldMask bit *i* == FieldStore field index *i*
alignment still holds with an owner-only mask in play.**

`OwnerOnlyVisibilityTest.stripping clears a bit and never renumbers one`. It asserts on the field
**name** `DesyncReport` produces — the actual consumer that indexes `fieldNames` with a mask bit —
not on a mask: the non-owner's only difference from the server must be named `gold`, with server
value `1234` and client value `0`. It then re-reads `level` and `weapon` off the store by index,
because the reporter and the store would agree with each other even if both were shifted, and
pins the fixture's field order.

The fixture is built for this: `Loadout`'s owner-only field is at index **0**, deliberately not
last, so a compacting implementation would put `level` where `gold` belongs. Mutation B is the
demonstration that the test discriminates — it reported `<[y, level]>` instead of `<[gold]>`.

**3. `net-protocol.lock` regenerated, with the `:owneronly` token visible in the diff, and the id
movement stated.** §7.

**4. A component declaring no `visibility` emits no mask and no extra bytes — the default costs
nothing.**

Two halves, both in tests.

*No mask:* `GeneratedVisibilityTest.a component with no OwnerOnly field names no udea-net type at
all` asserts the generated source contains neither `OwnerOnlyFields` nor `ownerOnlyMask`, so such
a module gains no `udea-net` compile edge.

*No extra bytes:* `OwnerOnlyVisibilityTest.a component declaring no owner only field costs a
non-owner nothing` writes the **same** `Create` for `recipientOwnsEntity = true` and `= false`
over the plain fixture registry and asserts the byte arrays are identical, with
`the plain fixture components declare nothing owner-only` as the guard that those components
really declare none. Its control is in the same test: over the registry that *does* declare one,
the two byte arrays must differ and the non-owner's must be **smaller**. Without that control the
first assertion is satisfied by a build that strips nothing anywhere — which is the state this
issue was filed about. Under mutation A the **control** fires (`mutation-a-results.xml`):
`an owner-only field was declared and the two recipients got identical bytes`. Under mutation B
the **first assertion** fires instead: `Array sizes differ. Expected size is 24, actual size is
19.` Two mutations, two different halves of the same test, which is what says both halves are
load-bearing.

Also covered, beyond the four: `GeneratedVisibilityTest.the two independent stripping declarations
compose on one field` (a field may be both `OnCreate` and `OwnerOnly`, and both markers are
emitted) and `…both tokens appear in one field description in a fixed order` (`:oncreate:owneronly`,
because the token is hashed and the other order would break a handshake between two builds that
agree).

---

## 7. Regenerated files

Regenerated with
`sh gradlew :moba:udeaWriteProtocolLock :udea-codegen:udeaWriteProtocolLock`, never by hand.
`git diff origin/example..HEAD -- moba/net-protocol.lock`, complete and unelided:

```diff
diff --git a/moba/net-protocol.lock b/moba/net-protocol.lock
index 70d1966..759d046 100644
--- a/moba/net-protocol.lock
+++ b/moba/net-protocol.lock
@@ -13,7 +13,7 @@
 #
 # Generated by udea-codegen. Do not edit by hand.
 lockFormat 1
-protoHash 0xea9f
+protoHash 0xc67b
 component 0 dev.wildware.moba.CharacterView
   field 0 character i32:32
   field 1 flipX bool:1
@@ -45,13 +45,13 @@ component 6 dev.wildware.moba.ability.Projectile
   field 5 stunTicks i32:32
   field 6 teamId i32:32
 component 7 dev.wildware.moba.item.Inventory
-  field 0 slot0 i32:32
-  field 1 slot1 i32:32
-  field 2 slot2 i32:32
-  field 3 slot3 i32:32
-  field 4 slot4 i32:32
-  field 5 slot5 i32:32
-  field 6 trinket i32:32
+  field 0 slot0 i32:32:owneronly
+  field 1 slot1 i32:32:owneronly
+  field 2 slot2 i32:32:owneronly
+  field 3 slot3 i32:32:owneronly
+  field 4 slot4 i32:32:owneronly
+  field 5 slot5 i32:32:owneronly
+  field 6 trinket i32:32:owneronly
 component 8 dev.wildware.moba.lane.LaneCreep
   field 0 goldBounty i32:32
   field 1 heading i32:32:oncreate
```

That is the **whole** diff for the file. `git diff --stat origin/example..HEAD --
udea-codegen/net-protocol.lock udea-codegen/src/test/resources/expected-generated-hashes.txt`
prints nothing, which is the check that neither of those moved rather than an assumption.

- **`moba/net-protocol.lock`**: seven `:owneronly` tokens, `protoHash 0xea9f → 0xc67b`.
- **Id movement: none.** Component ids come from sorted fully-qualified names and no component was
  added or removed, so every id is where it was — `Inventory` is still 7, `LaneCreep` still 8.
  Field indices did not move either: a token change does not reorder `FieldOrder`'s name sort.
- **`udea-codegen/net-protocol.lock`: unchanged.** No fixture in that module declares a
  `visibility`, so its wire descriptions are identical.
- **`udea-codegen/src/test/resources/expected-generated-hashes.txt`: unchanged**, and this was
  checked rather than assumed — `:udea-codegen:test` passes without
  `-Pudea.updateGeneratedHashes=true`, and `git status` never listed the file. The emitter's KDoc
  table gained two `when` branches but the existing branches' literal strings were left byte for
  byte alone precisely so that no fixture's generated source would move.

---

## 8. What I did not exercise

- **Real UDP.** `:moba:runUdpProof` was not run. It is red on `example` for reasons documented in
  `HANDOFF.md` and unrelated to field visibility; running it would produce a red I could not
  attribute either way. The loopback path this change lives on is the same `SnapshotWriter` and the
  same `ReplicationServer`.
- **A mixed component in the shipped game.** `Inventory` is entirely owner-only, so `moba` never
  writes a *partial* mask. That case is exercised only by the `udea-net` fixture (`Loadout`), which
  is why the fixture has public fields either side of the private one.
- **More than one owner.** Both tests use one owner per entity, which is all `RpcOwnership` can
  express. A "my team may see this" visibility is not in the frozen vocabulary and is out of scope.
- **The removal-record skip for an entirely owner-only component** (`SnapshotSection.kt`, the
  `MaskOps.isEmpty(visible) && MaskOps.isNotEmpty(replicator.netMask)` guard) has no test of its
  own. It is a correctness-preserving optimisation — without it a non-owner receives a removal
  record for a component it was never sent, which the reader handles as a claim-then-release
  no-op — and `MobaComponentRemovalTest` and `ComponentRemovalTest` both stay green, which is what
  says the guard did not break the removal path it sits in. I would rather state that than write a
  test that asserts an absence of bytes I cannot attribute.
