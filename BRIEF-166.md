6f9f531

# Issue #166 — Item actives and unique passives on the shared item cooldown slot

Branch `issue-166-item-actives-unique-passives`, off `origin/example` at `60a9471`.

The SHA above is `6f9f531`, the **last commit of the change** — the convention `c26afe4` set for
`BRIEF-182`. The commit that adds this file sits on top of it and contains nothing but this file.

> **On the filename.** The contract I was given says `BRIEF.md` in the worktree root. `4f075c4`
> landed on `origin/example` while this ticket was in flight and removed that file for the reason
> its message gives: `BRIEF.md` at the root is "a loaded gun" that each ticket points at the last
> one's deliverable. The repository's convention is now `BRIEF-<N>.md`, so this is `BRIEF-166.md`.
> If the lead wants the older name instead, the fix is `git mv`.

---

## 1. The evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew :moba:test \
  --tests 'dev.wildware.moba.item.UniquePassiveTest' \
  --tests 'dev.wildware.moba.item.ItemActiveTest'
```

Green on this branch, forced to actually execute rather than reported up-to-date:

```
> Task :moba:test

BUILD SUCCESSFUL in 20s
49 actionable tasks: 49 executed
```

`dev.wildware.moba.item.ItemActiveTest tests= 8 failures= 0 errors= 0`
`dev.wildware.moba.item.UniquePassiveTest tests= 7 failures= 0 errors= 0`

*(Spliced from `evidence-green.txt` and `evidence-green-counts.txt` — see §8 for where the
artefacts are.)*

### It goes red when the feature is reverted

Six mutations, each applied to the tree at `759fb4c`, run, and reverted. Every diff below is the
literal `git diff` of that mutation, not a description of it, and every failing-test list is
`grep -E "Test > .*FAILED$|tests completed"` over that run's own log.

| # | The mutation | Tests it turns red |
|---|---|---|
| **M1** | neither item system is registered — the feature, reverted | **14 of 15** |
| **M2** | the unique deduplication is removed | 3 |
| **M3** | the item slots no longer share a cooldown group | 2 |
| **M4** | `Debuffs.Dead` is dropped from every ability's `blockedBy` | 1 |
| **M5** | `DeathTagSystem` is not registered | 1 |
| **M6** | `AbilityActivation.grant` no longer adopts a live group cooldown | 1 |

**M1 — the feature reverted.** `moba/src/main/kotlin/dev/wildware/moba/item/ItemModule.kt`:

```diff
-        registry.add(
+        if (false) registry.add(
             SimPhase.Gameplay,
             { ctx ->
                 ItemPassiveSystem(
...
-        registry.add(
+        if (false) registry.add(
             SimPhase.Gameplay,
             { ctx -> ItemActiveSystem(bonuses, ctx[GasServices.KEY].activation) },
```

```
ItemActiveTest > the item key casts the active it was granted() FAILED
ItemActiveTest > an active granted while the item bar is cooling adopts the cooldown() FAILED
ItemActiveTest > buying an item with an active grants it into an item slot() FAILED
ItemActiveTest > an item active is blocked while dead() FAILED
ItemActiveTest > firing one item active puts the other item slot on the same cooldown() FAILED
ItemActiveTest > an item active fires from the ability bar() FAILED
ItemActiveTest > an item active's cooldown is independent of a champion's ability cooldowns() FAILED
UniquePassiveTest > a passive outside a unique group stacks per copy() FAILED
UniquePassiveTest > a second unique group grants a second instance() FAILED
UniquePassiveTest > selling one of a unique pair leaves the other's passive active() FAILED
UniquePassiveTest > selling every member of a unique group takes its passive away() FAILED
UniquePassiveTest > two copies of the same unique grant exactly one effect instance() FAILED
UniquePassiveTest > a carried item's stats are applied as effects() FAILED
UniquePassiveTest > a second item in the same unique group still contributes its stats() FAILED
15 tests completed, 14 failed
```

The one survivor is `selling an item revokes its active`, which passes vacuously when nothing was
ever granted — stated rather than left for a reader to notice.

**M2 — the unique deduplication removed.** `ItemEffectSystems.kt`:

```diff
         if (group != ItemBonusTable.NONE) {
-            if (groupSeen[group]) return
             groupSeen[group] = true
         }
```

```
UniquePassiveTest > selling every member of a unique group takes its passive away() FAILED
UniquePassiveTest > two copies of the same unique grant exactly one effect instance() FAILED
UniquePassiveTest > a second item in the same unique group still contributes its stats() FAILED
15 tests completed, 3 failed
```

Note which test does **not** fail: `selling one of a unique pair leaves the other's passive
active`. With the dedup gone, selling one of two still leaves one instance behind, so that
assertion alone does not pin the deduplication — which is why the pair of tests exists rather
than one.

**M3 — the item slots no longer share.** `UnitBlueprints.kt`:

```diff
         public val ITEM_COOLDOWN_SHARING: CooldownSharing = CooldownSharing { slot ->
-            if (isItemSlot(slot)) ITEM_COOLDOWN_GROUP else CooldownGroup.NONE
+            if (false && isItemSlot(slot)) ITEM_COOLDOWN_GROUP else CooldownGroup.NONE
         }
```

```
ItemActiveTest > an active granted while the item bar is cooling adopts the cooldown() FAILED
ItemActiveTest > firing one item active puts the other item slot on the same cooldown() FAILED
15 tests completed, 2 failed
```

**M4 — the dead tag no longer blocks.** `MobaAbilities.kt`:

```diff
-            val blockedWhileHelpless = tags.table.setOf(listOf(tags.stunned, tags.dead))
+            val blockedWhileHelpless = tags.table.setOf(listOf(tags.stunned))
```

```
ItemActiveTest > an item active is blocked while dead() FAILED
15 tests completed, 1 failed
```

**M5 — nothing applies the dead tag.** `MobaAbilityModule.kt`:

```diff
-        registry.add(SimPhase.Cleanup, { DeathTagSystem(effects.dead, gas.applier) })
+        if (false) registry.add(SimPhase.Cleanup, { DeathTagSystem(effects.dead, gas.applier) })
```

```
ItemActiveTest > an item active is blocked while dead() FAILED
15 tests completed, 1 failed
```

M4 and M5 turn the same test red from the two opposite ends of one mechanism: the tag not being
named, and the tag not being applied. Both halves are load-bearing.

**M6 — a grant no longer adopts the group's cooldown.** `udea-gas/.../AbilityActivation.kt`:

```diff
-        if (!best.isInvalid) abilities.instanceAt(slot).cooldownHandle = best
+        if (false && !best.isInvalid) abilities.instanceAt(slot).cooldownHandle = best
```

```
ItemActiveTest > an active granted while the item bar is cooling adopts the cooldown() FAILED
15 tests completed, 1 failed
```

After the last mutation the tree was restored and `git status` was clean apart from the `gradlew`
mode bit, which is this box's `chmod +x` and is deliberately not committed.

---

## 2. Summary — what I did, what I decided, what I rejected

`#132` landed the `item/*.udea.kts` asset kind, the `Inventory` component and the shop, and
authored `unique`, `passive` and `grantedAbility` with nothing reading them. This ticket built the
two systems that read them.

**`ItemPassiveSystem`** reconciles a champion's applied GAS effects against what its inventory
holds, every tick, rather than reacting to a purchase. A carried item's `stats` are summed per
attribute and applied as one `item/stat_*` effect each — so two strength items move one number
rather than stacking two applications. Its `passive` is the *named* bonus, and its `unique` group
deduplicates it: two items in one group grant one instance between them and the lowest inventory
slot is the one that grants it. Their stat blocks still stack, which is what the genre means by
"unique passive" and what makes the deduplication about the passive rather than about the item.

**`ItemActiveSystem`** grants an item's `grantedAbility` into a slot above a champion's own two,
bound to `E` and `R` in the asset graph. Those slots share one cooldown; a champion's own two are
not in it.

**Decision 1 — the cooldown group is a property of the slot, not of the `AbilityDef`.**
*(commented on the issue: [#166 comment](https://github.com/wildware-uk/Udea/issues/166#issuecomment-5528600950))*
The obvious shape is a `cooldownGroup` field on `AbilityDef`. That is wrong here and would have
silently broken the acceptance criterion: an item active **is** one of this game's existing
definitions — `item/aegis` grants `ability/priest_heal`, which is also the priest's own slot-one
ability — so a group on the definition would put the champion's own heal and the item's heal in one
group and cool them down together. `udea-gas` therefore gains `CooldownGroup` (a value class) and
`CooldownSharing` (a `fun interface` the game supplies once to `GasModule`), and
`AbilityActivation` propagates the cooldown *application handle it already writes* across the
group. **No new replicated component**, so neither lock file moved — see §7.

**Decision 2 — an item's `health` and `mana` stats are `maxHealth` and `maxMana`.**
*(commented on the issue: [#166 comment](https://github.com/wildware-uk/Udea/issues/166#issuecomment-5528604336))*
`health` is declared `max = value(maxHealth)` and `AttributeRecompute.applyModifiers` clamps each
modifier against that bound as it applies it, so an infinite additive modifier on `health` is
discarded in full on any unit at full health — which is every champion walking out of its own
fountain. A `+80 health` vial would have been a stat that visibly did nothing. I renamed the
authored keys rather than adding an alias table inside the system, because a hidden rename between
the file a designer edits and the stat that moves is found by reading the source and never by
reading the item.

**Decision 3 — "blocked while dead" is a GAS tag, and it is wider than item actives.**
A corpse keeps its `Abilities`, `Attributes` and `GameplayEffects`, which is `AbilitySystem`'s
entire family, so a dead champion could cast — through a key press and through the
`activateAbility` RPC, neither of which asked. `Debuffs.Dead` is applied to a corpse by a new
`DeathTagSystem` and named in every ability's `blockedBy`. I rejected a `Corpse in entity` check at
the item-active call site because there are three ways into `AbilityActivation.activate` in this
game and a check written at one is a check the other two do not make — and because an item active
is one of the same `AbilityDef`s a champion casts, so there is no way to block one and not the
other from the definition anyway. **This is a behaviour change beyond the ticket's literal scope**:
a champion's own abilities are now blocked while dead too, and `AbilityActivation.tick` cancels a
cast that was in flight when the caster fell. I believe it is a fix; it is flagged here and on the
issue rather than buried. Reverting it is one line (M4).

**Two things a picture caught that no test did.**
The first `item_bar.png` showed the ability-name column running off the bottom of the window — with
four slots the fourth name's baseline landed at y=20, descenders cut. And the first live capture
showed the empty item bar as two blank boxes with no key on them: a slot a player has no way to
discover. Both are fixed in `49067ff`, and both were invisible to every assertion in the suite.

**One self-review finding.** `ItemPassiveSystem` was doing a string scan over
`MobaEffects.ITEM_STATS` per stat per item per champion per tick, plus a string comparison per slot
pair for the unique dedup. Standards §1: *"If a lookup is on a per-tick path, it is indexed."*
`ItemBonusTable` now resolves every item's strings into table positions once, at module
construction, and both systems read it by asset index. An item naming a stat or a passive this game
cannot apply now fails while the definition is being assembled.

### What I did not exercise

- **A champion carrying more actives than the bar holds.** `ItemActiveSystem` fills the two item
  slots from the lowest inventory slots and grants no third; that is stated in its KDoc and is not
  under test. Reaching it needs three actives in one inventory, which the shop allows.
- **A `time.rewind` across a purchase.** Both systems are reconcilers and need nothing from a
  snapshot, which is the argument for the design — but the argument is written down, not measured.
  `SnapshotRestoreProofTest` covers `Inventory` itself (`#132`), not the effects derived from it.
- **Selling every item in a group and buying one back.** The shared cooldown is held on the slots,
  so emptying every slot in the group loses it. `AbilityActivation.grant`'s KDoc states that gap
  and what closing it would cost.
- **Two clients.** `Inventory` is `OwnerOnly` (`#167`) and nothing here changes replication, but no
  test in this change puts an item active in front of a second client.

---

## 3. `sh gradlew build`

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew clean build
```

```
BUILD SUCCESSFUL in 23s
223 actionable tasks: 144 executed, 72 from cache, 7 up-to-date
Configuration cache entry reused.
```

No `-x`. Test totals read out of every `*/build/test-results/*/TEST-*.xml` that run produced:

```
sh gradlew clean build: 2574 tests, 0 failures, 0 errors, 34 skipped
  moba: 233          udea-agent: 281        udea-agent-host: 162
  udea-annotations: 11   udea-assets: 76    udea-assets-compiler: 191
  udea-audio: 15     udea-codegen: 244      udea-compiler-plugin: 127
  udea-core: 435     udea-diagnostics: 87   udea-gas: 105
  udea-gradle: 49    udea-net: 255          udea-render: 204
  udea-replay: 99
```

### The GL gates, run for real

This ticket changes HUD drawing (`MobaHud.drawSlots`), so a green `build` is not evidence about it:
`$DISPLAY` is empty on this box and both GL tasks skip silently. Run under xvfb with the flag, and
with `--rerun-tasks` because the first attempt came back `FROM-CACHE`, which is not evidence that
anything ran:

```
xvfb-run -a -s "-screen 0 1280x720x24" \
  env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
  sh gradlew :udea-render:udeaGlTest :udea-agent-host:udeaAgentGlTest \
    -Pudea.render.requireGl=true --rerun-tasks
```

```
> Task :udea-render:udeaGlTest
> Task :udea-agent-host:udeaAgentGlTest

BUILD SUCCESSFUL in 15s
34 actionable tasks: 34 executed
```

`udea-render/build/test-results/udeaGlTest: classes=4 tests=18 failed=0 skipped=0`
`udea-agent-host/build/test-results/udeaAgentGlTest: classes=2 tests=8 failed=0 skipped=0`

**Zero skipped is the number that matters** — it is what separates a GL run from a GL skip.

The HUD drawing itself is exercised by `sh gradlew :moba:runMatchShot` under the same xvfb, which
produced the PNGs in §5 through a real LWJGL3 context.

### The verifiers outside `check`

`sh gradlew udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd
udeaVerifyContracts udeaVerifyMigration udeaLegacyReport` — `BUILD SUCCESSFUL`. No
`docs/contracts/` file was touched and `docs/contracts.lock` is unchanged.

**`:moba:runUdpProof` was not run.** It is red on `origin/example` before this branch existed
(`HANDOFF.md`), it costs three OS processes, and nothing in this change touches replication — no
component was added or removed and neither lock file moved. That is a stated omission, not a
result.

---

## 4. What is on the branch

| Area | What changed |
|---|---|
| `udea-gas` | `CooldownGroup`, `CooldownSharing`, `AbilityActivation.grant`, cooldown-handle propagation, `GasModule.sharing` |
| `moba/item` | `ItemBonusTable`, `ItemPassiveSystem`, `ItemActiveSystem`, wired in `ItemModule` |
| `moba/ability` | `Debuffs.Dead` + `ability/dead` + `DeathTagSystem`; `ITEM_STATS`/`ITEM_PASSIVES`; four ability slots and the item-slot layout |
| `moba` controls/HUD | `moba/item_1` (E) and `moba/item_2` (R); four slot boxes; key on an empty slot; the names column anchored so four fit |
| `moba/assets/item` | `stats.udea.kts` (five stat effects, four named passives); `maxHealth`/`maxMana`; a `passive` on every `unique` |
| tests | `UniquePassiveTest`, `ItemActiveTest`, `CooldownGroupTest`, two additions to `MobaHudTest`, `MobaFieldTest`'s bindings, the corpus script list |
| fixtures | both `.udearep` files regenerated — §7 |

---

## 5. The images

All under `/srv/ssd1/workspace/Udea/build/debug-screenshots/`.

| File | What it shows | What it proves |
|---|---|---|
| `issue166-sequence.png` | the three states of the item bar, tiled left to right | the whole feature in one frame: empty, granted, fired |
| `issue166-empty-item-bar-before.png` | a live `:moba:run` instance at tick ~700, nothing carried | the bar exists before anything is bought, and **E and R are printed on the empty boxes** — the fix the first capture forced |
| `issue166-item-bar-granted.png` | after buying `item/warhammer` and `item/aegis` | `SPACE NPC_MELEE`, `Q ORC_ELITE_SPIN`, `E ORC_ELITE_SPIN`, `R PRIEST_HEAL` — the two actives are on the same bar as the champion's own, all four ready, all four names clear of the bottom edge. Health reads `460 / 750`: the aegis' `+250 maxHealth` arrived as a GAS effect |
| `issue166-shared-item-cooldown.png` | one press of `E`, two ticks later | **the acceptance criterion in one frame** — E and R are both dark and both read `14.9s`, while SPACE and Q are still lit. One shared item cooldown, independent of the champion's own |
| `issue166-hud-midmatch.png` | tick 561 of the same match | the four-slot bar in an ordinary frame of the game |
| `issue166-match-result.png` | the decided match | `runMatchShot`'s existing `result` subject still lands (see §6, deadline) |

**Read `issue166-shared-item-cooldown.png` carefully**: the two dark boxes are *both* item slots,
and only `E` was pressed. That is the difference between "an item has a cooldown" and "the item bar
has one cooldown".

---

## 6. The issue, criterion by criterion

> **`UniquePassiveTest` asserts two copies of the same unique grant exactly one effect instance,
> and that selling one leaves the other active.**

`moba/src/test/kotlin/dev/wildware/moba/item/UniquePassiveTest.kt`, 7 tests, all green.

| Assertion | Test | Proof it can fail |
|---|---|---|
| two copies of one unique → one instance | `two copies of the same unique grant exactly one effect instance` — buys `item/bulwark` then `item/sentinel_greaves`, both `unique/fortified`, and asserts `applied("item/passive_fortified") == 1` | M1, M2 |
| selling one leaves the other active | `selling one of a unique pair leaves the other's passive active` — sells the bulwark and asserts the count is still 1 and the greaves are what is left | M1 |
| …and that is not a system that never removes anything | `selling every member of a unique group takes its passive away` — sells both and asserts 0 | M1, M2 |
| …and not a system capped at one passive | `a second unique group grants a second instance` — `unique/fortified` and `unique/vitality` are 1 each | M1 |
| …and the key is the **unique id**, not the effect | `a passive outside a unique group stacks per copy` — `item/bloodletter` and `item/archmage_staff` both name `item/passive_vigour` and declare no unique, so a champion carrying both carries **2** | M1 |
| stats are applied as GAS effects | `a carried item's stats are applied as effects` | M1 |
| the deduplication is of the passive, not of the item | `a second item in the same unique group still contributes its stats` | M1, M2 |

> **`ItemActiveTest` activates an item ability, asserts its cooldown is independent of champion
> ability cooldowns, and asserts it is blocked while dead.**

`moba/src/test/kotlin/dev/wildware/moba/item/ItemActiveTest.kt`, 8 tests, all green.

| Assertion | Test | Proof it can fail |
|---|---|---|
| activates an item ability | `an item active fires from the ability bar` | M1 |
| …from the ability bar, by its key | `the item key casts the active it was granted` — `InjectedIntent` presses `moba/item_1`, `PlayerControlSystem.itemActivesRequested` moves | M1 |
| cooldown independent of champion cooldowns | `an item active's cooldown is independent of a champion's ability cooldowns` — asserts both directions: firing the item leaves slots 0 and 1 at 0, and firing slot 0 does not clear the item | M1 |
| the item cooldown is *shared* | `firing one item active puts the other item slot on the same cooldown` | M1, M3 |
| …and cannot be reset by buying into it | `an active granted while the item bar is cooling adopts the cooldown` | M1, M3, M6 |
| blocked while dead | `an item active is blocked while dead` — asserts `BlockedByTag(Debuffs.Dead)` specifically, and that the active is still *granted* | M1, M4, M5 |
| the way back out | `selling an item revokes its active` | — (passes vacuously under M1; see §1) |

> **At least 3 items with actives and at least 3 with unique passives.**

Four items declare a `grantedAbility`: `item/warhammer` (`ability/orc_elite_spin`), `item/aegis`
and `item/phoenix_charm` (`ability/priest_heal`), `item/scouting_totem`
(`ability/soldier_fire_arrow`). Six declare a `unique` **and** a `passive`: `item/greatsword`
(`unique/sharpened`), `item/bulwark`, `item/sentinel_greaves`, `item/aegis` (`unique/fortified`),
`item/lifestone`, `item/phoenix_charm` (`unique/vitality`). `#132` authored the unique groups and
left them pointing at no passive; this branch authored the four passive effects they name, which is
what makes the groups mean anything.

### The engine half, tested in its own module

`udea-gas/src/test/kotlin/dev/wildware/udea/gas/CooldownGroupTest.kt`, 7 tests, all green. The
`moba` tests above prove the *acceptance criteria* through the real game, which is where they
belong. What they cannot state is **why the group is a property of the slot rather than of the
`AbilityDef`** — that argument needs one definition granted into three slots, two of them grouped,
and an assertion that firing one of the pair leaves the third ready. Under a
group-on-the-definition design that assertion is unsatisfiable, and `slots granted the same
definition inside and outside a group do not share` is what says so.

It also carries the **control** for the adoption test: `Abilities.grant` does *not* adopt a live
group cooldown and `AbilityActivation.grant` does, and both halves are asserted — so if the plain
grant ever started adopting, the adoption test would go on passing and stop meaning anything.

Neutralising the propagation in `AbilityActivation.activate`:

```diff
-            if (peer != slot && sharing.groupOf(peer) == group) {
+            if (false && peer != slot && sharing.groupOf(peer) == group) {
```

```
CooldownGroupTest > slots granted the same definition inside and outside a group do not share() FAILED
CooldownGroupTest > firing one slot in a group cools down every slot in it() FAILED
7 tests completed, 2 failed
```

> **Out of scope:** the asset kind, `Inventory` and the shop (`#132`, merged and untouched here);
> bots buying items (`#133`); consumables with charges, wards and vision items — none added.

---

## 7. Regenerated files

**Neither lock file moved.** No replicated component was added or removed, so
`udea-codegen/net-protocol.lock` and `udea-codegen/src/test/resources/expected-generated-hashes.txt`
are byte-identical to `origin/example`; `git diff HEAD -- udea-codegen/ net-components.lock` is
empty and `:udea-codegen:udeaCheckProtocolLock` is `BUILD SUCCESSFUL`. That was the point of
Decision 1: the shared cooldown is carried on the effect application `AbilityActivation` already
writes, so no ids moved and nothing after them shifted.

**A third generated-artefact family did move, and the brief I was given does not mention it.**
The checked-in replay fixtures carry the asset-graph hash and the input-schema hash, and this
change moves both — ten new gameplay effects in the asset graph, and two new controls in the input
catalog. `MobaReplayFixturesCurrentTest` refuses them by name rather than diverging at some tick:

```
java.lang.IllegalStateException: 2 replay fixture(s) cannot be replayed by this build:
  moba-3600.udearep: REFUSED - this build cannot replay it - assetGraphHash: recorded 76a6569c70018406... (32 bytes), this build 44e487ef20d4439d... (32 bytes); inputSchemaHash: recorded 2229103034793186487, this build 407227863552470576
  moba-36000.udearep: REFUSED - this build cannot replay it - assetGraphHash: recorded 76a6569c70018406... (32 bytes), this build 44e487ef20d4439d... (32 bytes); inputSchemaHash: recorded 2229103034793186487, this build 407227863552470576

If that is expected - an id moved, a component was added, the fixture world changed - rebuild them and review the diff:
  ./gradlew :moba:test -Dupdate.replay.fixtures=true
```

*(Re-captured on this branch by checking the pre-change fixtures back out, running that one test,
and reading the failure out of its own result XML. The bytes above are that run's, not a
transcription.)*

Regenerated with the command the failure names:

```
sh gradlew :moba:test -Dupdate.replay.fixtures=true
```

Both `moba/src/test/resources/fixtures/moba-3600.udearep` and `moba-36000.udearep` are rewritten.
The change is the header identity, not the pilot: `MobaReplayEqualityTest.the checked-in gate
fixture is regenerable, input for input` rebuilds the input stream from
`MobaFixtureRecorder`'s seeded LCG and compares it tick for tick, and it passes. The input schema
went from 2 actions to 4 because `moba/item_1` and `moba/item_2` joined the catalog. **If another
branch in this wave also regenerated these, the merge resolution is to run that command again in
the merged tree** — the bytes are not text and a textual merge of two regenerations agrees with
neither.

---

## 8. Where the artefacts are

Working artefacts are under
`/tmp/claude-1000/-srv-ssd1-workspace-Udea/184f8e9c-009e-46cb-9cba-389394ecf6fb/scratchpad/evidence/`:
`full-build.txt`, `test-counts.txt`, `gl-tests.txt`, `evidence-green.txt`,
`evidence-green-counts.txt`, `replay-fixture-refusal.txt`, and `mutation-m{1..6}-*.{diff,log,result}`.

They are **outside** the repository's `build/` on purpose: they were there first, and
`sh gradlew clean build` deleted them. Everything quoted above was regenerated afterwards, so every
block in this brief can be reproduced by re-running the command beside it. The scratchpad is
session-scoped; the images in `/srv/ssd1/workspace/Udea/build/debug-screenshots/` and this file are
the durable record.

**Which tree produced which block, precisely.** The six mutation runs and their diffs are from
`759fb4c`; the `clean build` totals, the GL run and the green evidence run are from `6f9f531`. The
one commit between them adds `CooldownGroupTest` and a defaulted parameter on `GasFixture` and
touches no `moba` source at all — `git diff 759fb4c 6f9f531 -- moba/` is empty — so every mutation
above applies verbatim to `6f9f531`.

---

## 9. One number in the evidence that is not what it looks like

`sh gradlew :moba:runMatchShot` initially failed to capture its existing `result.png`: the match
was not decided inside `MatchShot.DEADLINE_TICKS` (2,400). I did not assume that was pre-existing.

- On `origin/example` at `4f075c4`, the same command decides the match on **tick 1832** and writes
  `result.png`.
- On this branch **with the two purchases removed and nothing else changed**, it decides on **tick
  1832** as well — spin at 426, hud at 561, `orc=0 soldier=11 undead=0, alive=18`, identical to the
  baseline.

So the engine half of this change — four ability slots, the dead tag, both item systems — leaves
the match trajectory tick-identical, and the perturbation is entirely `MatchShot`'s own new step of
handing the champion 3,200 gold of items. `DEADLINE_TICKS` is now 5,700, above
`MatchRules.MATCH_LIMIT_TICKS`, so `result` is decided by the clock if it is not decided by a
wipeout first — which is what that deadline should have been all along, since below the game's own
limit it goes missing whenever a balance change makes a match last longer.
