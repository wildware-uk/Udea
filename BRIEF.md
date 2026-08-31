# Issue #132 — the shop, the items and the inventory

92ae5e0

Branch `issue-132-shop-and-items`, off `origin/example` at `866ba0a`.
Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a5b3c68bd564f1fda`.

Scope is the **first half** of #132 as the lead split it: the `item` asset kind, the `Inventory`
component, `ShopSystem`, enough items to exercise it, and acceptance criteria **1 and 4**.
`ItemPassiveSystem`, unique-passive deduplication and item actives are **#166** and are not here.

---

## 1. The evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew \
  :moba:test --tests 'dev.wildware.moba.item.*' \
  :udea-assets-compiler:test --tests '*ItemRecipeValidatorTest' \
  --console=plain
```

24 tests: `RecipeTest` (8), `ShopProofTest` (10), `ItemRecipeValidatorTest` (6). Every purchase in
the first two runs through `ShopService` and is carried out by `ShopSystem` on a real tick of the
shipped `MobaGame.definition()`; nothing calls `ShopRules` directly.

Everything quoted in this brief is spliced from a file under
`/tmp/udea-issue132-agent-a5b3c68bd564f1fda/`, which holds every log, JSON body, mutation diff and
PNG named below. Nothing here is retyped from memory, and §2 records the one number that was.

### Proof it goes red

Eleven mutations, each a shape the code plausibly *could* have had with one behaviour removed, each
run through the command above. **The literal `git diff` of every one is in §8**, taken from the run,
not retyped. Control before and after: 24 ran, 0 failed.

| # | What is removed | Red |
|---|---|---|
| M1 | `ShopSystem.buy` no longer clears the consumed slots | 6 |
| M2 | `ShopRules.priceFor` returns the shelf price, no recipe difference | 5 |
| M3 | `ShopSystem.buy` has no affordability check | 1 |
| M4 | `item(components = ...)` declared as a list of **id strings** instead of references | 11 |
| M5 | the `expecting<Item>()` stamp dropped, reference kept | 1 |
| M6 | `ItemRecipeValidator`'s pricing arm removed | 1 |
| M7 | no fountain check | 2 |
| M8 | `componentSlots` lets one slot satisfy two components | 1 |
| M9 | `hasRoomFor` ignores the slots the purchase frees | 1 |
| M10 | no aliveness check | 1 |
| M11 | `sellValue` returns the full shelf price | 1 |

**Two things worth reading rather than skimming.**

**M4 changed answer between two runs of the same mutation, and the first answer was the flattering
one.** The first pass reddened 4 tests; the re-run reddened 11. The first pass ran against a stale
packed bundle, so only the compiler-side tests saw the change; the re-run repacked and the runtime
shop lost its recipes too. Both runs were "green means green" — but one of them under-reported the
blast radius of the mutation, which is exactly what a mutation table is for. The 11 in the table is
the re-run.

**The whole table was re-run into a private directory after `dev-154` found that
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/<uuid>/scratchpad/` is not session-private on this box** —
our `mut/M*.log` files had been overwriting each other. My failing-test names never came from those
logs (they are collected from this worktree's own `build/test-results/**/*.xml`, and the diffs were
printed by `git diff` in the same tool call), but "sound by construction" is not a check. Everything
in §8 was regenerated under `/tmp/udea-issue132-agent-a5b3c68bd564f1fda/mut/`, and the collector now
refuses to run outside this worktree and stamps the worktree path into every result file.

---

## 2. What I did, what I decided, and what I rejected

Every decision below is also on the issue as
[a comment](https://github.com/wildware-uk/Udea/issues/132#issuecomment-5480132031), so a later
reader can disagree with the reasoning rather than guess at it.

### The `item` asset kind is the engine's, not the game's

`udea-assets/Item.kt`, registered in `AssetKindHierarchy`, `DslKinds`, `GraphPacker` and
`AssetCodecs`, with an `item(...)` function on `AssetScope`. Every field on it is a number, a name
or a `Ref`, so it decodes out of a `.udeapak` with no running game — which is the test
`GameplayEffect`'s KDoc sets for a kind belonging here rather than in a game.

The alternative was `asset("item", ...)`, the generic escape. It was rejected because it loses the
typed references, and **that loss is acceptance criterion 4**: `components` declared as a
`List<String>` gets no `UDEA0004`, no `UDEA0013`, no did-you-mean and no line number — it gets a
null in the shop, in a match, with the build green. Mutation **M4** is that alternative, applied,
and it reddens 11 tests.

### `cost` is the price on the shelf; the counter price is derived

One authored number per item. A champion who already carries a component pays
`cost - sum(owned components' costs)` — the recipe difference — and the components are consumed.

Rejected: authoring a *combine cost* beside a total, which is how a lot of MOBAs store it. Two
numbers that must agree with no compiler checking they do is the unchecked duplication
`MobaAuthoredContentTest` exists to close, one level down; a designer who retunes one and not the
other ships an item whose displayed price is not its price.

The cost of the choice is that `cost` below the sum of the parts becomes expressible, and that
subtraction going negative is a shop that pays gold **out** on a purchase. So a new build-time rule
**`UDEA0037 ITEM_RECIPE`** refuses it, and refuses an item that lists itself as its own component.
If the owner prefers the other shape: add `combineCost` to `Item`, delete `ItemRecipeValidator`, and
have `ShopRules.priceFor` return it.

### The fountain is the champion's own spawn point

`Respawn.spawnX`/`spawnY`, radius `ShopRules.FOUNTAIN_RADIUS = 260`. That is what a fountain *is* in
this genre, it is per-team without a second table, and `Respawn` is already in the snapshot registry
so a `time.rewind` restores it. Rejected: a rectangle in a new `ShopGeometry`, which would be a
third place the map's layout is written down. 260 is above `LaneGeometry.XP_RADIUS` (220), so a
champion cannot both shop and soak a wave's experience from one spot.

### Orders queue; there is no RPC and no shop tool

`ShopService.buy/sell` enqueues a `ShopOrder`; `ShopSystem` drains it at a known point in a known
tick. That is the Command shape `SimBarrier` and `AbilityActivationSink` already use here, and it
means a bot, a test and (later) a client reach the simulation through one door.

Rejected: an `@Rpc` like `activateAbility`, and an `@AgentTool` shop toolset. Neither is asked for,
both add protocol or tool surface a reviewer would have to rule on, and the declared consumer of
this work is #133 (lane bots), which is in-tree Kotlin and needs neither.

### Twenty items, ten of them finished

Eight basic components, ten finished items with real build paths, two trinkets. The ten include one
built from a finished item (`warhammer` ← `greatsword` + `blade`) and one that names the same
component twice (`twin_blades` ← two `blade`s), because those are the two recipe shapes a naive
implementation gets wrong.

**These three numbers are measured, not remembered.** An earlier draft of this brief and my first
comment on the issue both said "nineteen items, nine finished"; I had counted from memory. The
figures above are the census `ShopProofTest` prints out of the **packed bundle** the game opens:

```
[shop] 20 items, 10 with build paths, 2 trinkets
```

A grep over the source made the same mistake in a second way: `grep -c "trinket = true"` over
`trinkets.udea.kts` answers **3**, because one of the hits is the word inside a comment. The bundle
says 2, and the bundle is what the shop reads.

### `@Net(visibility = OwnerOnly)` on `Inventory` is DECLARED and NOT ENFORCED

**Read this paragraph before assuming the guarantee holds.** `Visibility.OwnerOnly` has been in
`udea-annotations/Net.kt` since Phase 0 and **nothing reads it**. I grepped the tree: the only hits
are the enum declaration and `AnnotationVocabularyTest` lines 97 and 153. `udea-codegen`'s
`ComponentModelBuilder` never looks at the argument and `udea-net`'s `SnapshotWriter` has no
per-recipient mask stripping. So today **every client that holds a champion is sent every field of
its inventory** — a real information leak in a competitive game.

That is the same state `lifetime` was in before #114 turned it into bytes not sent. It is filed as
**#167** and deliberately not built here: it is a codegen mask, a wire marker, a writer policy and
an ownership seam landing on the wire contract, which is a second concern in one branch. The
annotation is on the component as the statement of intent #167 will make true. **There is no test
pretending otherwise** — a test asserting the annotation is present would prove nothing about the
wire and is the "test that cannot fail" the reject list names.

The evidence that nothing is enforced is in the regenerated lock (§9): all seven fields read
`i32:32`, with no visibility marker, beside `heading`'s `i32:32:oncreate` which shows what a
declaration that *is* enforced looks like in the same file.

### `Inventory` is seven `Int` fields, and an agent can read it

Six carried slots plus a trinket, each holding an `AssetIndex` value or `-1`. Not an array, because
`FieldLowering` accepts only `Boolean`/`Int`/`Long`/`Float`/`NetId`/`Tick`/enum and a component with
array state needs a hand-written `Replicator`, which `ReplicatorApiShapeTest` forbids in game code.

`Inventory` also joins `Position`, `GameUnit` and `MatchState` on `WorldToolset`'s component index
(second commit). Without it the feature is invisible to everything except a Kotlin test — see §5.
Read-only: a caller that could write a slot could hand itself `item/aegis` without paying for it.

### Four stale asset counts, and one I did not touch

Adding three asset scripts broke `MigratedCorpusCompilesTest`, `MigratedCorpusGapTest`,
`MigratedCorpusBundleTest` and `MobaWarmEditBudgetTest` — every one on a hard-coded `19` scripts or
`127` assets. Rather than renumber:

- the script count is now the **list** of script paths (a list invites an addition; a number invites
  a contradiction);
- the bundle count is **derived** from the compiled graph, which is what the test's own name claims;
- two counts that guarded nothing their neighbours did not are **deleted** rather than rewritten.

I grepped the class rather than fixing only what broke. `ExampleScanTest`, `AssetMigratorTest` and
`MigratorIdempotenceTest` also carry `19`, and they are about `TestPaths.exampleAssets` — the old
`example/` tree, unchanged — so they are correct and untouched. **One more is left standing:**
`udea-gradle/src/main/kotlin/dev/wildware/udea/gradle/UdeaAssetsPlugin.kt:162` says "On this corpus -
nineteen scripts, 127 declarations" in a KDoc. `moba/assets` is 22 scripts and 147 assets now.
`udea-gradle` is **dev-152's this wave**, so it is reported rather than edited.

### Passed on rather than fixed

`MobaReplayProofTest > a corrupted recording is caught at the tick it was corrupted` is a ~1-in-9
flake in my module. `dev-154` hit it and diagnosed it; I checked the arithmetic and it holds:
`corruptAxisAt` writes the constant `(-1f, 0f)` (`MobaReplayProofTest.kt:324`) and `Pilot.sample`
draws each axis from `rng.nextInt(3) - 1f` (lines 94-95), so `(-1, 0)` is 1 of 9 equiprobable pairs
and a t301 sample that already equals it makes the "corruption" a no-op. `(8/9)^5 ≈ 0.55`, so
`HANDOFF.md`'s recorded 5/5 was never evidence against it. **Not fixed here**: it is unrelated to
the shop and an unexplained `MobaReplayProofTest.kt` hunk in a diff about items costs a review round.
Passed to the lead to route. It did not fire in any of my runs.

---

## 3. `sh gradlew build`

```
$ JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew clean build --console=plain
...
> Task :moba:check
> Task :moba:build
> Task :udeaVerifyMigration
> Task :check
> Task :build
> Task :udea-compiler-plugin:test
> Task :udea-compiler-plugin:check
> Task :udea-compiler-plugin:build

BUILD SUCCESSFUL in 18s
229 actionable tasks: 137 executed, 78 from cache, 14 up-to-date
```

Test totals swept out of every `build/test-results/**/*.xml` afterwards: **365 suites, 2444 tests,
0 failures, 0 errors, 34 skipped.** `:moba:test` executed rather than coming from the cache.

**The caveat, stated:** 78 of those tasks were build-cache hits. A cache hit is keyed on the task's
inputs, so its stored results are valid for this tree — but it is not a cold run, and it took 18s
for that reason. I attempted a `clean build --no-build-cache` for a colder transcript; it failed,
and the failures were **the box, not the branch** (§4).

The 34 skipped are the GL tests skipping with no `$DISPLAY` (25 of them, across
`udea-render/gl` and `udea-agent-host/gl`) plus 9 in `udea-assets-compiler`'s
`ReproducibilityTest`/`AtlasPackerTest`.

### GL, run for real

This ticket touches **no GL** — nothing in the diff is in `udea-render` or the render half of
`udea-agent-host`. Run anyway, because a green `build` is not evidence about GL and the omission is
a finding:

```
$ xvfb-run -a -s "-screen 0 1280x720x24" \
    env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
    JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
    sh gradlew udeaGlTest udeaAgentGlTest -Pudea.render.requireGl=true --console=plain
...
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest

BUILD SUCCESSFUL in 6s
41 actionable tasks: 2 executed, 39 up-to-date
```

`udeaGlTest` 18 tests / 0 failures / **0 skipped**; `udeaAgentGlTest` 8 / 0 / **0 skipped**. Both
ran for real; neither skipped.

### The gates outside `check`

```
$ sh gradlew udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd \
             udeaVerifyMigration udeaLegacyReport udeaVerifyDeterminism
BUILD SUCCESSFUL in 1s
83 actionable tasks: 83 up-to-date
```

`:moba:runLaneShot` under xvfb: green, three PNGs (§6). `:moba:runUdpProof` not run — it is red on
`example` before this branch and nothing here claims to have moved it.

---

## 4. Failures that were the box, and one that was not a regression

**Load-flake set.** A first `sh gradlew build` and a later `clean build --no-build-cache` both failed
on wall-clock budget tests while two or three *other agents'* Gradle builds were on the box (load
17-21). Every one passed alone, on the same commit:

| Test | Under load | Alone |
|---|---|---|
| `GraphBudgetTest` graph deserialisation | 28.07ms (budget 15ms) | best 6.77ms, **median 7.35ms** |
| `DaemonLatencyBudgetTest` warm reload | median 925ms, then 759ms | **median 137ms** `[146, 137, 134, 124]` |
| `DaemonLatencyBudgetTest` warm validate | median 390ms, then 486ms (budget 300ms) | **median 112ms** `[9, 147, 112, 109]` |
| `CharacterMoverBudgetTest` | median 6.975ms (budget 4ms) | not re-run alone; `dev-154` measured 2.163ms |
| `Phase2ExitTest` agent→world | 1169ms | **391ms** |
| `MobaWarmEditBudgetTest` | (failed on a stale count, §2) | **max 148ms, median 135ms**, budget 3000ms |

The tell is the one the contract names: a *different* thing failed each time.

**`:moba:runNetProof` — checked against the baseline before believing it.** This branch changes the
wire contract, so I ran the agreement proof. One of its three scenarios came back
`perfect units DISAGREED`, which reads exactly like a regression I had caused.

It is not. I checked out `origin/example` detached in this worktree and ran the same task three
times, then came back and ran it three times on the branch:

```
origin/example (866ba0a), 3 runs      issue-132 (92ae5e0), 3 runs
  perfect        units DISAGREED        perfect        units DISAGREED
  150ms+5% loss  units AGREED           150ms+5% loss  units AGREED
  TRELLO_8       units AGREED           TRELLO_8       units AGREED
```

Identical, 3/3 on each side. The perfect-link disagreement predates this branch. (Note it is
`runNetProof`, the in-process proof — **not** the `runUdpProof` that `HANDOFF.md` records as red; a
reader should not merge the two.)

What the same output *does* say about this branch, and the reason it is worth quoting:

```
mine: 14 replicated component types on the wire: CharacterView, Player, Position, Combatant,
      Projectile, Inventory, LaneCreep, LaneState, Tower, Wallet, GameUnit, MatchState, Respawn,
      Attributes
base: 13 replicated component types on the wire: CharacterView, Player, Position, Combatant,
      Projectile, LaneCreep, LaneState, Tower, Wallet, GameUnit, MatchState, Respawn, Attributes
```

`Inventory` is genuinely on the wire, and both lossy scenarios still agree on the 28-unit roster
with it there.

---

## 5. Driving the real game, and why there is no picture of the feature

**There is no picture of a champion carrying an item, and there cannot be one from this branch.**
Issue #132 puts item icon art out of scope; nothing in `moba` renders an inventory; and a HUD panel
is neither asked for nor in a file this ticket owns. Building one to have something to photograph
would be building a harness for the evidence rather than for the game. So the feature's evidence is
the test transcript in §1 and the live reading below, and the pictures in §6 are **regression
controls** — labelled as such rather than passed off as the feature.

What I did drive, on a live `:moba:run -PdebugPort=7842` under xvfb (`Offscreen`, 51 tools —
read off `/tools`, not assumed):

```
$ curl -s http://127.0.0.1:7842/health
{"ok":true,"frame":246,"tick":246,"paused":false,"renderMode":"Offscreen","role":"standalone","sessionId":"s-e1ad"}

[moba.agent] asset daemon: ok=true 6469ms 147 assets over .../moba/assets
[moba.agent] listening on http://127.0.0.1:7842 in Offscreen with 51 tools
```

Then, paused at tick 247:

```
$ curl -s "http://127.0.0.1:7842/command?cmd=world.query_entities&with=Inventory\
&fields=slot0,slot1,slot2,slot3,slot4,slot5,trinket"
{"accepted":true,"commandId":3,"frame":1326}

$ curl -s http://127.0.0.1:7842/state -o live-state-d.json     # then, out of that file,
$ python3 -c "import json; ...; print(result with id 3)"       # the entry for command 3:
{
 "id": 3,
 "ok": true,
 "result": {
  "total": 1, "offset": 0, "returned": 1, "hasMore": false,
  "entities": [ { "id": 0, "slot0": -1, "slot1": -1, "slot2": -1, "slot3": -1,
                  "slot4": -1, "slot5": -1, "trinket": -1 } ]
 }
}
```

One champion, seven empty slots — which is correct for a match where nobody has shopped, and is the
reading that proves the component reached the generated surface end to end. **What it does not
show** is a purchase: there is no shop tool, deliberately (§2), so the only way to buy over the
bridge would be a write to `Inventory`, which is refused by design. Buying is proved by §1.

The instance was closed with `cmd=close` and confirmed gone (`pgrep -af "[d]ebugPort=784"` empty).

The same query against the **previous** commit answered, verbatim,
`no component named Inventory; registered: GameUnit, MatchState, Position` — that is the
before-state, and it is why the second commit exists.

---

## 6. Images

All three in `/srv/ssd1/workspace/Udea/build/debug-screenshots/`.

| File | What it shows | What it proves |
|---|---|---|
| `issue132-game-runs-with-shop.png` | `render.screenshot` from the live instance at tick 247: the brawl, the HUD, 5 orcs / 11 soldiers / 9 undead | The game boots, ticks and draws with `ItemModule` in the definition and every component id after `ability.Projectile` shifted by one. A **regression control**, not the feature. |
| `issue132-lane-still-draws-wave.png` | `:moba:runLaneShot` wave frame, tick 272, wave 1 walking | The lane renders on a real GL context after the wire contract changed |
| `issue132-lane-still-draws-clash.png` | `:moba:runLaneShot` clash frame, tick 642, both towers and the meeting point | Same, at the frame with the most on it. I looked at both: towers, aggro cones, health bars and the ability bar are all where they were |

---

## 7. The acceptance criteria, one by one

Criteria 2 and 3 belong to **#166** and are not claimed here.

### ☑ 1. `RecipeTest`: buying a finished item consumes owned components, refunds the recipe difference correctly, and fails cleanly with insufficient gold

`moba/src/test/kotlin/dev/wildware/moba/item/RecipeTest.kt`, 8 tests, all through `ShopSystem` on a
real tick of the shipped definition.

| Clause | Test | Goes red under |
|---|---|---|
| consumes owned components | `buying a finished item consumes its components and charges the difference` — asserts the two parts are **gone** from the inventory, not merely paid for | M1, M2, M4 |
| refunds the difference **correctly** | same test (both parts owned), plus `a component the champion does not own is not discounted` (one owned — the case a shop that credits every component whether owned or not passes), plus `a recipe naming one component twice needs two of it`, plus `a recipe trades in a finished component, not the parts inside it` | M1, M2, M4, M8 |
| fails cleanly with insufficient gold | `a purchase with too little gold is refused and moves nothing` — a typed `ShopRefusal.InsufficientGold`, **and** the purse untouched, **and** the inventory untouched. "Cleanly" is three assertions: a shop that took what it could and delivered nothing would satisfy "the purchase did not happen" | M3 |
| the boundary underneath it | `a purchase with exactly enough gold succeeds and leaves nothing` — without it, "refused when short" is satisfied by a shop that refuses everything | M2 |

Prices are read off the catalogue rather than written as literals, so what is asserted is the
*relationship* (`paid == shelf - parts`) and a balance pass moves both sides at once.

### ☑ 4. `udeaValidateAssets` passes on the whole item tree, including a negative test that a recipe referencing a nonexistent component is a build error with file, line and did-you-mean

Positive half, on the real tree:

```
> Task :moba:udeaValidateAssets
[udeaValidateAssets] 147 asset(s), 0 diagnostic(s)

> Task :moba:udeaPackBundle
[udeaPackBundle] assets.udeapak: 147 asset(s), 38 sheet(s), 1 atlas page(s), 101450 bytes
```

Negative half, `ItemRecipeValidatorTest` — every fixture a real `.udea.kts` on disk through the real
passes 1, 2 and 3:

| Test | Asserts | Red under |
|---|---|---|
| `a well priced item tree produces no diagnostics at all` | **the control.** A fence that fires on a healthy tree is as wrong as one that stays quiet on a broken one | — (it is the control) |
| `a recipe naming a component that does not exist is a located build error` | `UDEA0004`, `Severity.Error`, `assetId=item/greatsword`, `causedBy=item/whetstoen`, **"did you mean `item/whetstone`?"** in the message, a span whose path ends `item/shop.udea.kts`, starts `udea-assets-compiler/` (repo-relative) and has a positive line number. Run through the **pipeline**, because that is what `udeaValidateAssets` runs | M4 |
| `a recipe pointing at something that is not an item is a kind mismatch` | `UDEA0013` naming both kinds | M4, M5 |
| `an item that costs less than its components fails the build` | `UDEA0037`, with the arithmetic in the message | M4, M6 |
| `an item that is its own component fails the build` | `UDEA0037`, other arm | M4 |
| `an unresolved component is not also reported as a pricing failure` | one defect, one diagnostic | — |

`ShopProofTest > every finished item in the bundle costs at least its parts` re-checks the same
property at **runtime**, against the bytes that shipped rather than the source tree.

### Scope bullets outside the numbered criteria

| Scope bullet | Where |
|---|---|
| `item/*.udea.kts` kind: cost, stats, components, unique, granted ability, passive | `udea-assets/Item.kt`, `AssetScope.item(...)`, `moba/assets/item/*.udea.kts` |
| ~20 items, ≥8 finished, real build paths, component cost refund | **20 items, 10 with build paths, 2 trinkets** — the census `ShopProofTest` prints out of the packed bundle. §2 |
| ≥3 with actives, ≥3 with uniques | **4** declare `grantedAbility` (3 finished + 1 trinket); **6** declare `unique`, across **3** groups — `fortified` ×3, `vitality` ×2, `sharpened` ×1. Schema only; #166 acts on them |
| `Inventory`, 6 slots + trinket, `@Net` `OwnerOnly` | `ItemComponents.kt`. **Declared, not enforced — §2** |
| buy | `RecipeTest` throughout |
| sell at reduced value | `ShopProofTest > selling returns a reduced price and empties the slot` (exact value **and** the two inequalities that say the number means something) |
| recipe combine on purchase | `RecipeTest`, plus `a full inventory can still combine two of its own slots` |
| only in the fountain radius | `the shop refuses a champion who has walked out of the fountain` — **both** directions, one unit outside and one unit inside, measured off `FOUNTAIN_RADIUS` |
| only while alive | `the shop refuses a corpse` — killed the way the game kills, by zeroing health and letting `DeathSystem` take the `Combatant` |

### What I did not exercise

- **A second match.** The inventory dies with the entity on a scene swap, which is asserted nowhere.
- **A rewind across a purchase.** `Inventory` is in `MobaGame.componentRegistry`, so
  `SnapshotRestoreProofTest`'s coverage check covers it structurally, but no test buys an item,
  rewinds past the purchase and asserts the slot is empty again. That is the test I would add next.
- **Two champions shopping in one tick.** The queue is FIFO and per-order, so it should be fine;
  nothing proves it.
- **`ShopRefusal.NoRoom` for a trinket when the trinket slot is full** *is* covered
  (`a trinket fits when the six carried slots do not` buys a second one and asserts the refusal).

---

## 8. The mutation diffs

Each block is the literal `git diff` from that mutation's run, under
`/tmp/udea-issue132-agent-a5b3c68bd564f1fda/mut/M*.diff`. Hunk headers kept; nothing retyped.

**M1 — components not consumed** (6 red: 5 × `RecipeTest`, `ShopProofTest > a full inventory can still combine two of its own slots`)
```diff
@@ -303,7 +303,6 @@ public class ShopSystem(
         var tradedIn = 0
         for (slot in 0 until Inventory.CARRIED) {
             if (consumed and (1 shl slot) == 0) continue
-            inventory.place(slot, null)
             tradedIn++
         }
         val destination = if (entry.item.trinket) Inventory.TRINKET else inventory.firstFreeCarried()
```

**M2 — no recipe difference** (5 red, all `RecipeTest`)
```diff
@@ -133,7 +133,7 @@ public object ShopRules {
             if (consumed and (1 shl slot) == 0) continue
             traded += catalog.at(inventory, slot)?.item?.cost ?: 0
         }
-        return entry.item.cost - traded
+        return entry.item.cost
     }
```

**M3 — no affordability check** (1 red: `RecipeTest > a purchase with too little gold is refused and moves nothing`)
```diff
@@ -296,9 +296,7 @@ public class ShopSystem(
             return ShopOutcome.Refused(order.champion, ShopRefusal.NoRoom)
         }
         val price = ShopRules.priceFor(inventory, catalog, entry, consumed)
-        if (wallet.gold < price) {
-            return ShopOutcome.Refused(order.champion, ShopRefusal.InsufficientGold)
-        }
+        // mutation: no affordability check
 
         var tradedIn = 0
         for (slot in 0 until Inventory.CARRIED) {
```

**M4 — a recipe as a list of id strings** (11 red: all 4 compiler-side, 5 × `RecipeTest`, 2 × `ShopProofTest`)
```diff
@@ -688,7 +688,7 @@ public class AssetScope(
         name,
         "cost" to cost,
         "stats" to LinkedHashMap(stats),
-        "components" to components.map { it.expecting<Item>() },
+        "components" to components.map { it.id },
         "unique" to unique,
         "grantedAbility" to grantedAbility?.expecting<Ability>(),
         "passive" to passive?.expecting<GameplayEffect>(),
```

**M5 — the typed stamp dropped, reference kept** (1 red: `ItemRecipeValidatorTest > a recipe pointing at something that is not an item is a kind mismatch`)
```diff
@@ -688,7 +688,7 @@ public class AssetScope(
         name,
         "cost" to cost,
         "stats" to LinkedHashMap(stats),
-        "components" to components.map { it.expecting<Item>() },
+        "components" to components.map { it },
         "unique" to unique,
         "grantedAbility" to grantedAbility?.expecting<Ability>(),
         "passive" to passive?.expecting<GameplayEffect>(),
```

**M6 — UDEA0037's pricing arm removed** (1 red: `ItemRecipeValidatorTest > an item that costs less than its components fails the build`)
```diff
@@ -242,7 +242,7 @@ public object ItemRecipeValidator : AssetValidator {
 
             val cost = costs.getValue(item.id)
             val parts = components.sumOf { costs.getValue(it.id) }
-            if (cost >= parts) continue
+            continue
             val listed = components.joinToString { "`${it.id}` at ${costs.getValue(it.id)}" }
             diagnostics += AssetValidationRules.ITEM_RECIPE.diagnostic(
                 message = "`${item.id}` costs $cost gold but is built from components worth " +
```

**M7 — no fountain check** (2 red: both `ShopProofTest` fountain tests)
```diff
@@ -278,9 +278,7 @@ public class ShopSystem(
             val spawn = entity.getOrNull(Respawn) ?: return refused
 
             if (Corpse in entity) return ShopOutcome.Refused(order.champion, ShopRefusal.Dead)
-            if (!ShopRules.inFountain(position.x, position.y, spawn.spawnX, spawn.spawnY)) {
-                return ShopOutcome.Refused(order.champion, ShopRefusal.OutsideFountain)
-            }
+            // mutation: no fountain check
             return when (order) {
                 is ShopOrder.Buy -> buy(order, wallet, inventory)
                 is ShopOrder.Sell -> sell(order, wallet, inventory)
```

**M8 — one slot satisfies two components** (1 red: `RecipeTest > a recipe naming one component twice needs two of it`)
```diff
@@ -105,7 +105,6 @@ public object ShopRules {
         for (component in entry.componentIndices) {
             for (slot in 0 until Inventory.CARRIED) {
                 val bit = 1 shl slot
-                if (claimed and bit != 0) continue
                 if (inventory.rawAt(slot) != component) continue
                 claimed = claimed or bit
                 break
```

**M9 — room ignores the slots the purchase frees** (1 red: `ShopProofTest > a full inventory can still combine two of its own slots`)
```diff
@@ -149,7 +149,7 @@ public object ShopRules {
         if (entry.item.trinket) return inventory.isEmpty(Inventory.TRINKET)
         var free = 0
         for (slot in 0 until Inventory.CARRIED) {
-            if (inventory.isEmpty(slot) || consumed and (1 shl slot) != 0) free++
+            if (inventory.isEmpty(slot)) free++
         }
         return free > 0
     }
```

**M10 — no aliveness check** (1 red: `ShopProofTest > the shop refuses a corpse`)
```diff
@@ -277,7 +277,7 @@ public class ShopSystem(
             val inventory = entity.getOrNull(Inventory) ?: return refused
             val spawn = entity.getOrNull(Respawn) ?: return refused
 
-            if (Corpse in entity) return ShopOutcome.Refused(order.champion, ShopRefusal.Dead)
+            // mutation: no aliveness check
             if (!ShopRules.inFountain(position.x, position.y, spawn.spawnX, spawn.spawnY)) {
                 return ShopOutcome.Refused(order.champion, ShopRefusal.OutsideFountain)
             }
```

**M11 — a sale returns the full price** (1 red: `ShopProofTest > selling returns a reduced price and empties the slot`)
```diff
@@ -78,7 +78,7 @@ public object ShopRules {
     public const val NO_SLOTS: Int = 0
 
     /** Gold returned for selling an item that cost [cost]. */
-    public fun sellValue(cost: Int): Int = cost * SELL_PERCENT / 100
+    public fun sellValue(cost: Int): Int = cost
 
     /** Whether a champion at ([x], [y]) is inside the fountain at ([spawnX], [spawnY]). */
     public fun inFountain(x: Float, y: Float, spawnX: Float, spawnY: Float): Boolean {
```

(`NO_SLOTS` is `private` on the branch as committed — the M11 diff was taken before the
public-surface trim in `92ae5e0`, and the mutated line is the one below it either way.)

---

## 9. Regenerated files, and by how much the ids moved

Three files, each rewritten by its own task, never by hand.

**`net-components.lock`** — hand-edited, as it is meant to be: one name added.
`dev.wildware.moba.item.Inventory` sorts between `ability.Projectile` and `lane.LaneCreep`.

**`moba/net-protocol.lock`** — `sh gradlew :moba:udeaWriteProtocolLock`.
`Inventory` takes **id 7**, and every moba component after it moves up by **one**:
`lane.LaneCreep` 7→8, `lane.LaneState` 8→9, `lane.LastHit` 9→10, `lane.Tower` 10→11,
`lane.Wallet` 11→12, `level.GameUnit` 12→13, `match.MatchState` 13→14, `match.Respawn` 14→15.
`protoHash` **0xdf75 → 0xea9f**.

```diff
-component 7 dev.wildware.moba.lane.LaneCreep
+component 7 dev.wildware.moba.item.Inventory
+  field 0 slot0 i32:32
+  field 1 slot1 i32:32
+  field 2 slot2 i32:32
+  field 3 slot3 i32:32
+  field 4 slot4 i32:32
+  field 5 slot5 i32:32
+  field 6 trinket i32:32
+component 8 dev.wildware.moba.lane.LaneCreep
   field 0 goldBounty i32:32
   field 1 heading i32:32:oncreate
```

Read that diff for the `OwnerOnly` point in §2: seven plain `i32:32` tokens with no visibility
marker, two lines above `heading`'s `i32:32:oncreate`, which is what a declaration the wire *does*
honour looks like in the same file.

**`udea-codegen/net-protocol.lock`** — `sh gradlew :udea-codegen:udeaWriteProtocolLock`.
The six fixture components move **15-20 → 16-21**; `protoHash` **0x0140 → 0xf167**.

**`udea-codegen/src/test/resources/expected-generated-hashes.txt`** —
`sh gradlew :udea-codegen:test -Pudea.updateGeneratedHashes=true`. **Seven** hashes moved: the six
fixture replicators (whose emitted `typeId` shifted) plus `CodegenFixturesNetProtocol.kt`. The
agent-state and tool files are unchanged, which is the right shape — nothing about them moved.

Nothing has shipped against any of these ids: no recorded replay and no connected client decodes
with them.

---

## 10. Self-review against the reject list

Read against `docs/engineering-standards.md` §8 and `AGENTS.md`'s "Do not", both closed lists.

- **§1 smells** — no top-level `var`, no mutable singleton (`ShopService` is constructed by
  `ItemModule` and injected), no god object, every wire/disk field self-describing, no stringly-typed
  domain (`UniqueName` is a value class; a slot holds an `AssetIndex` value with the `Int` storage
  justified against `Tower.targetRaw`'s precedent), no string-concatenated codegen, no silent
  failure (every refusal is a typed `ShopRefusal`), no reflection, no linear scan on a per-tick path
  (`netIds.resolveOrNull` is O(1); `ItemCatalog` is an array read), no magic buffer.
- **A `public` declaration nobody outside the module uses** — swept in `92ae5e0`.
  `ItemCatalog.EMPTY`, `ShopService.pending` and `Inventory.itemAt` had no callers and are deleted;
  `ItemCatalog.atRaw` and `ShopRules.NO_SLOTS` had none outside their own files and are private.
- **A test that cannot fail** — §1 and §8. Eleven mutations, every test file represented, control
  runs on both sides. The `ItemRecipeValidatorTest` control (`a well priced item tree produces no
  diagnostics at all`) is the known-negative for the four validator tests.
- **Generated code by string concatenation** — none; the packer emits `PackValue`s.
- **A new field on `GameContext`** — none. `ShopService` reaches the world through
  `builder.service(KEY, …)`, the same door `LaneService` uses.
- **Wall clock or unseeded randomness in simulation** — none in the diff.
  `ShopSystem` reads no clock at all; `ShopService`'s queue is an `ArrayDeque` (insertion order);
  `ItemCatalog.byId` is a `HashMap` that is **only ever looked up, never iterated**, with the ordered
  answer being `entries`, built from `registry.ids` — that is written into its KDoc so a reviewer
  does not have to work it out.
- **`TODO()`, stubbed return, swallowed exception** — none. The one `check` in `ShopSystem.buy`
  fires only on a disagreement between `hasRoomFor` and `firstFreeCarried`, which is a defect in that
  file rather than a state a player can reach, and it fails loudly rather than writing to slot −1.
- **Copy-pasted logic differing only in a constant** — the seven-way `when` in `Inventory.rawAt`/
  `place` is the closest thing, and it is the honest cost of a component with no arrays; the "which
  slot" arithmetic lives in exactly those two functions and nowhere else.
- **`by net(...)`, a second snapshot codec, setter instrumentation** — none.
- **A new module depending on `common`** — none; `udeaVerifyNoLegacyDependencies` green.
- **GL outside `udea-render`** — none.
- **A presentation system as a Fleks system** — none; nothing here draws.
- **A module arrow pointing upward** — none; `udeaVerifyModuleGraph` green.
- **A `docs/contracts/` file changed** — **none.** `git diff origin/example...HEAD --name-only`
  has no `docs/contracts/` entry.
- **`fieldNames[i]` == FieldMask bit *i* == FieldStore index *i*** — held.
  `InventoryReplicator.fieldNames` is `["slot0","slot1","slot2","slot3","slot4","slot5","trinket"]`,
  and `ItemModule.snapshotTypes()` passes `List(Inventory.CAPACITY) { FieldKind.Int }` — **derived**
  from the capacity rather than transcribed, and every one of the seven fields really is an `Int`, so
  a kind cannot be typed wrong at the right length here.
- **A duration expressed in seconds or milliseconds rather than a `Tick`** — the diff contains no
  duration at all. Gold is `Int`; the sell rate is integer percent, so the arithmetic truncates
  identically on a server, a client and a replay.
- **`AGENTS.md`'s module table stale** — no module moved; `udeaVerifyAgentsMd` green.

---

## 11. Files

**New** — `udea-assets/…/Item.kt`; `moba/assets/item/{components,finished,trinkets}.udea.kts`;
`moba/src/main/…/item/{ItemComponents,ItemCatalog,ShopRules,ShopSystem,ItemModule}.kt`;
`moba/src/test/…/item/{ShopHarness,RecipeTest,ShopProofTest}.kt`;
`udea-assets-compiler/src/test/…/validate/ItemRecipeValidatorTest.kt`.

**Changed** — `udea-assets/…/{Names.kt, pack/AssetCodecs.kt}`;
`udea-assets-compiler/…/{AssetKind, AssetScope, gen/DslKinds, gen/AccessorGenerator,
pack/GraphPacker, validate/AssetValidationRules, validate/AssetValidator,
validate/GraphValidators}.kt`; the four `udea-assets-compiler` tests carrying stale counts;
`moba/src/main/…/MobaGame.kt`; `moba/src/agent/…/MobaAgent.kt`; the three lock files.

**`gradlew` shows as `M` in `git status` and is deliberately not staged** — it is the `chmod +x` the
contract prescribes for this box, and a mode flip on the wrapper is not part of this change.
