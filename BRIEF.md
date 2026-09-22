# BRIEF.md — issue #252, Hollow H4: combat and abilities through `udea-gas`, plus the ComposeGL HUD

**Review `e67dc701`** — every line of code, every test and every generated file on this branch is
at that commit. This brief is committed directly on top of it and changes nothing else, so the
branch tip differs from `e67dc701` by `BRIEF.md` alone. (A brief cannot name the SHA of the commit
that contains it; the tip is in the handover message.)

Branch `issue-252-hollow-combat`, worktree
`/srv/ssd1/workspace/Udea/.claude/worktrees/agent-a642493e1e401401e`, branched from `origin/master`
at `483cb10e` and merged with `origin/master` at `ed07e820` (commit `c3761af5`).

---

## 1. The evidence command

```
cd /srv/ssd1/workspace/Udea/.claude/worktrees/agent-a642493e1e401401e && \
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem \
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew :hollow:desktop:runFightShot -Phollow.shot.client=0 \
  --max-workers=4 -Dorg.gradle.workers.max=8 --console=plain
```

It passed at this SHA: `EXIT=0`, `BUILD SUCCESSFUL in 41s`, 24 PNGs, and none of the four checks
tripped. Run it again with `-Phollow.shot.client=1` and you get the same fight from the other
machine; the two transcripts are identical on all 24 frames (section 5).

It runs an authoritative `HollowServer` and two `HollowClient`s over `udea-net`'s in-process
harness with 100ms of latency each way, has both clients hold the attack control down and hunt the
nearest living fox while a wave of five closes in, and writes one PNG per known tick of the fight as
**one of the two clients draws it**, into `hollow/desktop/build/reports/udea/fight/`.

It is a check as well as a camera. It exits non-zero, with a message, when:

- a frame does not carry the HUD's two solid panels at the colours `HollowHudLook` names —
  the only thing that can tell a `CapturedUi` from a `UiLayer` from outside;
- no fox ever lost a hit point (nothing was fought);
- no fox was ever killed (the fight never resolved);
- no player was ever bitten (the foxes never fought back).

**Proof it goes red when the feature is reverted:** rows M1 and M2 of the mutation table in
section 6. Each is a `git diff` taken from the run, with the exact stderr the mutated run printed.

---

## 2. What I did, and what I decided

I picked this up from two developers who were cut off. Their game code was on disk, uncommitted and
not compiling; there were no tests, no launcher wiring, no lock regeneration and no brief.

### The fight

Health is a `udea-gas` attribute (`hollow.health`, capped by `hollow.maxHealth`) on players and
foxes alike. `Fox.health` — a `@Net` `Int` that only a test ever wrote — is **gone**, so the fox's
mind, the HUD and the wire read one number rather than two that could disagree.

Four abilities, all in `HollowCombat`, all tick-denominated and all drawing from `RngService`'s
`Combat` stream:

| Ability | What it does | Cooldown |
|---|---|---|
| `hollow/attack` | every living fox within 1.8m takes `25 + nextInt(Combat, 10)` | 36 ticks |
| `hollow/dash` | 12 m/s along the facing the dash started on | 180 ticks |
| `hollow/heal_self` | 35 hit points back, clamped at the maximum | 600 ticks |
| `hollow/bite` | the fox's, on the player it is chasing, `5 + nextInt(Combat, 4)` | 90 ticks |

Six systems: `ArmSystem` (gives anything that fights its health and ability bar),
`PlayerAbilitySystem`, `FoxBiteSystem`, `udea-gas`'s own `AbilitySystem` and `AttributeSystem`, and
`DeathSystem`. A client runs none of them — its world is a replicated view.

### The HUD

A `CapturedUi`, so a screenshot holds what a player sees: health and its rail, the three ability
slots with their bound key and the seconds left while each cools, the wave number, the score, and a
banner when the character is dead. `HollowHudModel` reads every number out of the world once a
frame; nothing in it is a second source of truth.

### Decisions I had to make

**The saved level had to be regenerated, and that is the surprise of this ticket.** Adding
`GasModule` to Hollow's module list adds a `LevelSection` (`udea-gas.effectHandles`), and `GasLevel`
refuses a level that has no section for it. The committed `clearing.udealevel` predates the section,
so **fifteen tests that have nothing to do with combat** went red at once with
`LevelFormatException: level has no 'udea-gas.effectHandles' section`. I rewrote the file with the
task that exists for it, `:hollow:desktop:udeaWriteClearing`, which writes it from `ClearingLayout`
— still the clearing's source, because the editor ticket (#255) has not landed and nobody has
hand-edited the file. The alternative was to relax `GasLevel`, which is an engine module outside
this ticket and would weaken a check that exists to catch exactly this. Commented on the issue
(`#252#issuecomment-5781780139`) with what to change if the owner disagrees. **Measured** in
section 7: the first 49,988 bytes are byte-identical.

**A cooldown reaches a client as three `@Net` `Tick`s on `Player`, not as an effect.** `udea-gas`'s
`GameplayEffects` codec has an empty `netMask` — the effect ledger is server state — so a client's
HUD would have no cooldown to count down. `Player.attackReady`, `dashReady` and `healReady` are that
copy, written once per use from the cooldown effect itself rather than reckoned a second time.
`CombatTest` holds the copy equal to the effect on **every tick** of a whole cooldown and the swing
after it, which is what stops the two drifting; mutation M4 is what proves that test can fail.

**A swing hits all the way round, not in an arc.** An arc needs the player's facing against each
fox's bearing, and a swing that misses a fox a metre behind reads as a bug to somebody who can see
it. A circle is also the rule a test can state from both sides of one number.

**The score is 0 and says so.** Scoring is H5 (#253). `HollowHudState.score` is documented as such
and `HollowHudTest` asserts it, so the day H5 lands, the assertion is what tells it to change.

**`FoxWaves.DEFAULT` became public.** A launcher now names the schedule twice — once for the session
it starts and once for the HUD, which shows the wave number. Two launch lines with two schedules
would put a number on the screen that no wave matches.

### Out of scope, for the lead

Nothing found that belongs elsewhere. `hollow:desktop` still has no `agent` source set and no
`runEditor` (#255), and the HUD's death banner is the only end-of-life state a dead player gets —
respawn is #253's.

---

## 3. `sh gradlew build`

Run twice through the shared full-lane lock, `--no-daemon --max-workers=4
--no-configuration-cache --no-build-cache --continue`, no `-x`.

**At this SHA**, with the marker's head read *inside* the hold:

```
QUEUED 2026-09-22T21:11:41Z lane=full
START  2026-09-22T22:07:13Z head=e67dc701 dirty=2
EXIT=0 2026-09-22T22:09:04Z head=e67dc701
BUILD SUCCESSFUL in 1m 51s
1124 actionable tasks: 33 executed, 1091 up-to-date
```

`dirty=2` is `BRIEF.md` and `gradlew`'s mode bit, neither of which is a build input. Only 33 tasks
executed because the tree was already built; the **cold** green is the earlier run:

```
EXIT=0
BUILD SUCCESSFUL in 9m 31s
1124 actionable tasks: 1002 executed, 122 up-to-date
```

A `grep -cE '^> Task .*FAILED'` over either log returns **0**.

### The cold run's marker carried a stale SHA, and here is what it actually built

That 9m 31s run's marker says `HEAD=2173a570`, because my script read `HEAD` when it was
*launched* (18:51:18) and then waited nineteen minutes for the lock. I merged `origin/master` and committed `538d4023` at
19:04:41 while it was still queued. Two independent checks say the build compiled the **merged**
tree:

- Arithmetic on the marker itself: `END=19:19:37` minus the build's own `9m 31s` puts Gradle's
  start at about **19:10:06**, which is after the 19:04:41 commit.
- The decisive artefact: `ComposeUiConventionTest` — a class that exists only after `ed07e820`
  (19:01:37) — has an in-XML timestamp of **2026-09-22T19:10:43.711Z**, 3 tests, 0 failures. It
  cannot have run from the pre-merge tree, because there it does not exist.

The script now reads `HEAD` **inside** the hold. Any script with a queue in front of it has this
bug, and a green build carrying a stale SHA is exactly the true-but-wrong line this repository's
notes warn about, so it is written down here rather than quietly corrected.

### The headless suite on its own, at this SHA

`:hollow:game:jvmTest` and `:hollow:desktop:test`, `--no-build-cache`: `EXIT=0`, **63 tests, 0
failures**, in-XML timestamps 19:05:16–19:05:19 against a 19:05 wall clock — so nothing was
restored from the build cache. That run is also the green baseline the mutation table is scored
against.

---

## 4. The GL runs under xvfb

### The run

```
xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  sh gradlew udeaHollowGlTest udeaGlTest udeaAgentGlTest udeaEditorGlTest \
  -Pudea.render.requireGl=true --continue \
  --no-daemon --max-workers=4 --no-configuration-cache --no-build-cache --console=plain
```

```
> Task :hollow:desktop:udeaHollowGlTest
> Task :udea-render:udeaGlTest
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-editor:udeaEditorGlTest
BUILD SUCCESSFUL in 3m 59s
```

Counted out of the JUnit XML that run wrote:

| Suite | Classes | Tests | Skipped | Failed |
|---|---|---|---|---|
| `udeaGlTest` | 30 | 31 | 0 | 0 |
| `udeaAgentGlTest` | 2 | 2 | 0 | 0 |
| `udeaEditorGlTest` | 6 | 6 | 0 | 0 |
| `udeaHollowGlTest` | 2 | 2 | 0 | 0 |
| **total** | **40** | **41** | **0** | **0** |

### Why the green build in section 3 says nothing about this

`$DISPLAY` was empty for `sh gradlew build`, and `-Pudea.render.requireGl` defaults to `false`, so
those same four suites **ran and skipped inside the green build**: 40 classes, every one with
`skipped="1"` in its XML, including my two new soak tests. That is the documented silent skip. The
xvfb run above is what covers GL on this branch. The green build is not evidence about GL and is
not offered as any.

### `udeaHollowGlTest` is new on this branch

`:hollow:desktop`, `forkEvery = 1`, wired onto `check`, modelled on `udea-editor`'s
`udeaEditorGlTest` and skipping without a display in the same way, through its own
`GlAvailabilityHere` (a copy, as `udea-agent-host` and `udea-editor` each keep one, because
`udea-render`'s is internal to its own tests).

It holds the #275 soak: `HollowHudOffscreenSoakTest` and `HollowHudWindowedSoakTest`, each running
the real `HollowLaunch.start` — the same public call `:hollow:desktop:run` makes, with the HUD
registered the way a launcher registers it — for **sixteen seconds of wall-clock frames**. It
samples the frame counter every second, so a loop that stops half way through fails naming the
second; it keeps any throwable out of the frame callback and re-asserts it at the end; and it
finishes by reading the HUD's two panels out of a fresh capture, so "still drawing" means the world
pass and the captured interface both still ran. Row M7 is the proof it can fail.

---

## 5. The images

All under `/srv/ssd1/workspace/Udea/build/debug-screenshots/`. The dashboard service was refusing
connections throughout (`ECONNREFUSED`), so these are on disk and named here rather than posted;
they can go up when it is back.

| File | What it shows | What it proves |
|---|---|---|
| `issue252-hud-mid-fight.png` | One full-size frame, client 0, tick 236: two players back to back with foxes on them; `WAVE 1   SCORE 0` along the top; `HEALTH 100 / 100` and its rail bottom-left; the three slots — `SPACE ATTACK` greyed with `0.5s` under it while it cools, `Q DASH READY`, `E HEAL READY` | Acceptance criterion 2. The HUD is a `CapturedUi`, so a screenshot holds what a player sees. Nothing is clipped, collided or crushed against an edge, and the cooling slot reads its seconds |
| `issue252-fight-key-moments.png` | Six frames of the same run, reading order 04, 07, 10, 13, 18, 22 | The fight end to end, legibly: foxes closing, players swinging, health falling 100 to 92 with the rail visibly shorter, the pack thinning, an empty clearing at the end |
| `issue252-fight-sequence-client0.png` | All 24 frames of client 0, numbered, ticks 152 to 428 at 12-tick steps | Acceptance criterion 3. Each numbered tile maps to a named tick in the transcript in section 8 |
| `issue252-fight-sequence-client1.png` | The same 24 ticks as client 1 drew them | The same fight from the second machine |
| `issue252-same-tick-two-clients.png` | Tick 236 side by side from both clients | The owner's criterion in a picture: one world, two machines — and each HUD shows **its own** character, 100/100 on client 0 and 73/100 on client 1, the one being bitten |

The camera is each client's own `ThirdPersonRig`, pulled back to 9 metres at a 32-degree pitch so
the whole pack is in frame, and turned 45 degrees for the second client so the two runs are two
players' views rather than one view twice.

---

## 6. The mutation table

Every row's diff is the literal `git diff` of the mutation, taken from the run that produced the
failures beside it, in a **separate worktree** (`/srv/ssd1/workspace/udea-review/dev252c-mut`, a
`git worktree` at this branch's commit) so that the evidence worktree was never mutated. Every
prediction was written down before any mutation was applied:
`scratchpad/dev-252c/mutation-predictions.md`.

### M3 — the swing's reach

Predicted: exactly one failure, `a swing does not touch a fox outside its reach`.
**Measured: exactly that, and nothing else** (14 tests ran, 1 failed).

```diff
--- a/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/CombatSystems.kt
+++ b/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/CombatSystems.kt
@@ -103,7 +103,7 @@ internal class AttackExec(private val arena: Arena) : AbilityExec {
                 val there = fox[Transform3D]
                 val dx = there.x - at.x
                 val dy = there.y - at.y
-                if (dx * dx + dy * dy > REACH_SQUARED) continue
+                if (false) continue
                 arena.hurt(context, fox, context.self, arena.roll(CombatRules.ATTACK_DAMAGE, CombatRules.ATTACK_SPREAD))
```

```
CombatTest > a swing does not touch a fox outside its reach()[jvm]
    org.opentest4j.AssertionFailedError: a fox 1.9m away - a tenth of a metre out of reach - was hit ==> expected: <100.0> but was: <72.0>
TOTAL FAILURES: 1
```

### M4 — the ready tick a client is sent

Predicted: exactly two failures — `the ready tick a client is sent is the cooldown effect itself`
and `each slot counts its own cooldown down to zero` — and `holding the attack key swings once and
then waits out the cooldown` **still passing**, because the gate is the effect and not the copy.
**Measured: exactly that.** The asymmetry is why M4 and M8 are separate rows.

```diff
--- a/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/CombatSystems.kt
+++ b/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/CombatSystems.kt
@@ -269,8 +269,7 @@ internal class PlayerAbilitySystem(
[... 2 lines of KDoc context elided ...]
-    private fun readyAt(abilities: Abilities, effects: GameplayEffects, slot: Int): Tick =
-        tick + gas.activation.cooldownRemaining(abilities, effects, slot, tick).toLong()
+    private fun readyAt(abilities: Abilities, effects: GameplayEffects, slot: Int): Tick = tick
```

```
CombatTest > the ready tick a client is sent is the cooldown effect itself()[jvm]
    org.opentest4j.AssertionFailedError: at t2, Player.attackReady says 0 ticks left and the cooldown effect says 35 ==> expected: <35> but was: <0>
HollowHudTest > each slot counts its own cooldown down to zero()[jvm]
    org.opentest4j.AssertionFailedError: the swing's slot does not show its whole cooldown the tick after it fired ==> expected: <35> but was: <0>
TOTAL FAILURES: 2
```

### M5 — the corpse

Predicted: exactly one failure, `a fox killed by swings falls and is gone once its corpse has lain`.
**Measured: exactly that.**

```diff
--- a/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/CombatSystems.kt
+++ b/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/CombatSystems.kt
@@ -366,7 +366,7 @@ internal class DeathSystem(
[... 2 lines of context elided ...]
             } else if (entity has Fox && now.ticksSince(died) >= CombatRules.CORPSE.count) {
-                gone += entity
+                // mutation: the corpse is never collected
             }
```

```
CombatTest > a fox killed by swings falls and is gone once its corpse has lain()[jvm]
    org.opentest4j.AssertionFailedError: a corpse was still in the world 90 ticks after it died
TOTAL FAILURES: 1
```

### M6 — health on the wire

Predicted: `CombatReplicationTest` fails on the tick-for-tick comparison; `CombatTest` stays green
because it is headless. **Measured: exactly that, one failure.** (I also predicted
`FoxReplicationTest` would fail the same way and did **not** run it in this row, so that half of the
prediction is untested and I am not claiming it.)

```diff
--- a/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/net/HollowNet.kt
+++ b/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/net/HollowNet.kt
@@ -80,11 +80,6 @@ public object HollowNet {
             // Issue #252: a fighter's health, through `udea-gas`'s own codec, which takes an id above
             // this build's `@Replicated` space (`net-components.lock` says why). Its `base` is `@Net`,
             // and it is what a client's HUD and a fox's flight are read from.
-            fleksComponentType(
-                AttributesReplicator(attributes),
-                ComponentSchema.of(AttributesReplicator(attributes), "Attributes", listOf(FieldKind.Object)),
-                Attributes,
-            ) { Attributes(attributes) },
         ),
     )
```

```
CombatReplicationTest > every fighter's health and every cooldown agree on the server and on both clients, tick for tick()[jvm]
    org.opentest4j.AssertionFailedError: client1 disagrees with the server about the fight at tick 62 ==> expected: <{184=FighterView(isFox=false, health=100.0, attackReady=83, dashReady=0, healReady=0), 185=FighterView(isFox=false, health=100.0, attackReady=83, dashReady=0, healReady=0), 186=FighterView(isFox=true, health=100.0, attackReady=-1, dashReady=-1, healReady=-1), 187=FighterView(isFox
TOTAL FAILURES: 1
```

### M8 — the cooldown gate itself (acceptance criterion 1's "seen red")

Predicted: three failures — the held-key test, the HUD countdown, and
`CombatReplicationTest`'s "no client ever held a running cooldown" — with `the ready tick a client
is sent is the cooldown effect itself` **still passing**, because with no cooldown both the effect
and the copy say zero.

**Measured: five. My prediction under-counted by two, and both extras are the same mutation
biting somewhere I had not thought about:**

- `a heal restores hit points and never past the maximum` — the test holds the heal control for
  two ticks expecting one heal. With a zero cooldown it fires on both: 60 + 35 + 35 = 130, clamped
  to the maximum. The failure says `actual <100.0>` against `expected <95.0>`, which is that
  arithmetic exactly.
- `a fox bites only once it is within reach, and not on the way in` — with a zero cooldown the fox
  bites *every* tick, so the player is dead in about fifteen ticks (100 hit points at 5–8 a tick).
  The last bite before death lands on a player with 3 hit points left and the clamp at zero makes
  the drop 3.0, which is what the message says.

The prediction's positive half held: `the ready tick a client is sent is the cooldown effect
itself` is **not** in the failure list.

```diff
--- a/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/HollowCombat.kt
+++ b/hollow/game/src/commonMain/kotlin/dev/wildware/hollow/HollowCombat.kt
@@ -317,7 +317,7 @@ public class HollowCombat internal constructor() {
         fun ability(name: String, exec: String, cooldown: Ticks) = AbilityDef(
             name = name,
             execId = execs.idOf(exec),
-            cooldownTicks = cooldown.count.toInt(),
+            cooldownTicks = 0,
             cooldownEffectIndex = effects.indexOf(COOLDOWN),
```

```
CombatTest > a fox bites only once it is within reach, and not on the way in()[jvm]
    org.opentest4j.AssertionFailedError: a bite did 3.0, less than 5
CombatTest > a heal restores hit points and never past the maximum()[jvm]
    org.opentest4j.AssertionFailedError: one heal did not restore 35.0. Expected <95.0> with absolute tolerance <0.001>, actual <100.0>.
CombatTest > holding the attack key swings once and then waits out the cooldown()[jvm]
    org.opentest4j.AssertionFailedError: the held key did not swing again on the tick the cooldown ended
CombatReplicationTest > every fighter's health and every cooldown agree on the server and on both clients, tick for tick()[jvm]
    org.opentest4j.AssertionFailedError: no client ever held a running cooldown, so the ready ticks were never compared
HollowHudTest > each slot counts its own cooldown down to zero()[jvm]
    org.opentest4j.AssertionFailedError: the swing's slot does not show its whole cooldown the tick after it fired ==> expected: <35> but was: <0>
TOTAL FAILURES: 5
```

---

## 7. Regenerated files

The prediction was frozen before anything was regenerated
(`scratchpad/dev-252c/lock-prediction.md`). Predicted against measured:

| File | Predicted | Measured |
|---|---|---|
| `net-components.lock` | unchanged — no `@Replicated` component added or removed | **unchanged** |
| `udea-codegen/net-protocol.lock` | unchanged | **unchanged** |
| `udea-core/net-protocol.lock` | unchanged | **unchanged** |
| `moba/game/net-protocol.lock` | unchanged — component ids did not move | **unchanged** |
| `udea-codegen/src/test/resources/expected-generated-hashes.txt` | unchanged — its 32 lines are all `codegen/fixtures/*` and no fixture moved | **unchanged** |
| both moba `.udearep` fixtures | unchanged — no id shift, no asset change | **unchanged** |
| `moba/desktop/src/test/resources/levels/test_level.roster.txt` | unchanged — `worldHash` folds component `typeId`s and none moved | **unchanged** |
| `hollow/game/net-protocol.lock` | changes: `protoHash` moves; `Fox` loses `health` and gains a fourth `FoxMode`; `Player` goes 5 fields to 13; **component ids stay 0 and 1** | **exactly that** |

`git status` after regenerating lists `hollow/game/net-protocol.lock` and nothing else in that
family — and it *does* list that one, which is what makes the other seven rows mean something
rather than being an empty command's output.

The hollow lock, in full:

```diff
 lockFormat 1
-protoHash 0x6214
+protoHash 0x3d9a
 component 0 dev.wildware.hollow.Fox
   field 0 decideAt tick:64
   field 1 goalX f32:32
   field 2 goalY f32:32
   field 3 heading f32:32
-  field 4 health i32:32
-  field 5 mode enum:32:Wander,Chase,Flee
-  field 6 target netid:32
+  field 4 mode enum:32:Wander,Chase,Flee,Dead
+  field 5 target netid:32
 component 1 dev.wildware.hollow.Player
-  field 0 heading f32:32
-  field 1 moveX f32:32
-  field 2 moveY f32:32
-  field 3 owner i32:32
-  field 4 running bool:1
+  field 0 attack bool:1
+  field 1 attackReady tick:64
+  field 2 dash bool:1
+  field 3 dashReady tick:64
+  field 4 faceX f32:32
+  field 5 faceY f32:32
+  field 6 heading f32:32
+  field 7 heal bool:1
+  field 8 healReady tick:64
+  field 9 moveX f32:32
+  field 10 moveY f32:32
+  field 11 owner i32:32
+  field 12 running bool:1
```

Written by `sh gradlew :hollow:game:udeaWriteProtocolLock`, never by hand. Nothing else in the
build has a `net-protocol.lock` that moved, so `udeaCheckProtocolLock` is green on `check`.

### The level file, measured rather than trusted

`hollow/game/levels/clearing.udealevel`, rewritten by `:hollow:desktop:udeaWriteClearing`
(184 entities, 50,050 bytes). Against the committed file kept at
`scratchpad/dev-252c/clearing.before.udealevel`:

```
$ cmp -l clearing.before.udealevel hollow/game/levels/clearing.udealevel
cmp: EOF on .../clearing.before.udealevel after byte 49990
49989 377 166
49990 377 165
```

Two bytes differ before the old file's end, and the new file is 60 bytes longer. The two bytes are
the old file's trailing CBOR break markers (`0xff 0xff`) at 49989 and 49990; they are pushed to the
new end by the section that now sits there:

```
$ tail -c 80 hollow/game/levels/clearing.udealevel | xxd
00000000: 7469 6f6e 739f ffff 6873 6563 7469 6f6e  tions...hsection
00000010: 73bf 7675 6465 612d 6761 732e 6566 6665  s.vudea-gas.effe
00000020: 6374 4861 6e64 6c65 739f 3840 1864 186e  ctHandles.8@.d.n
00000030: 1865 1878 1874 0018 6918 6c18 6918 7618  .e.x.t..i.l.i.v.
00000040: 6518 4318 6f18 7518 6e18 7400 20ff ffff  e.C.o.u.n.t. ...
```

So the first **49,988** bytes are byte-identical: no entity, prop, transform or `NetId` changed,
and what is new is `udea-gas.effectHandles` with `next = 0` and `liveCount = 0`.

---

## 8. The issue, criterion by criterion

### AC1 — "Headless: an attack damages a fox in range and not one out of range; cooldowns block re-use (seen red)"

`CombatTest` (`hollow/game/src/jvmTest/.../CombatTest.kt`), nine tests, all through the real
`HollowGame` definition with the real Box2D solver and the same `IntentSource` seam a keyboard goes
through. Both sides of every threshold:

- `a swing damages a fox inside its reach` — a fox at 1.7m takes 25–34.
- `a swing does not touch a fox outside its reach` — a fox at 1.9m takes nothing. **Seen red** as
  row M3, which is the only test that fails when the reach test is removed.
- `holding the attack key swings once and then waits out the cooldown` — the key is held for the
  whole 36-tick cooldown and one tick more, and the fox's health is asserted on **every** tick, so
  a second swing anywhere inside the cooldown fails it. **Seen red** as row M8.
- `the ready tick a client is sent is the cooldown effect itself` — **seen red** as row M4.
- Plus the dash, the heal and its clamp, the bite from both sides of `BITE_REACH`, death and corpse
  collection, and a dead character that cannot swing, walk or be chased.

### AC2 — "The HUD shows live numbers, readable in a screenshot"

Two halves, because they are two claims.

**The numbers are the world's:** `HollowHudTest`, five tests, sampling `HollowHudModel` through the
same call `HollowHudSystem` makes once a frame — health following the attribute down to zero and
the `dead` flag with it; each slot counting its own cooldown down tick by tick while the other two
stay at zero; the wave number from the schedule on both sides of each arrival tick; the score
asserted at 0; and a HUD with no character yet showing the wave and nothing else.

**They are readable in a screenshot:** `issue252-hud-mid-fight.png`, full size. The panels reaching
the *capture* rather than the window is checked mechanically too — `HudPanels.missingFrom` reads a
pixel of each panel at the colour `HollowHudLook` names, and the evidence command fails on any
frame that lacks one. Across the two runs at this SHA, 48 frames, zero failures.

### AC3 — "A screenshot sequence of a fight"

`issue252-fight-sequence-client0.png` (24 numbered tiles, ticks 152 to 428) and
`issue252-fight-key-moments.png` (six of them, large enough to read the HUD). The transcript below
names every tile's tick, so a tile can be read back to the fight rather than admired.

### AC4, the owner's addition — "a headless test runs a server and two clients, in-process, and this ticket's feature agrees on every machine"

`CombatReplicationTest`: an authoritative `HollowServer` and two `HollowClient`s over `udea-net`'s
harness at 150ms each way. The server records what its world held at the end of **every** tick; each
client is compared against the record for the tick **that client was last told about**, never
against now. What is compared is every number this ticket puts on the wire — each fighter's health,
and each player's `attackReady`, `dashReady` and `healReady`.

It refuses to pass on agreement about nothing: it asserts it saw a hurt fox, a bitten player, a
running cooldown and at least one fighter killed. **Seen red** as rows M6 and M8.

"Where it makes sense, the screenshots show two clients": `issue252-same-tick-two-clients.png`, and
the two full sequences. The two runs' transcripts are **identical on all 24 frames** — same tick,
same health for every fighter — while each client's HUD shows its own character.

### The #275 rule — a public hook proven by a game living with it

`udeaHollowGlTest`, sixteen seconds of real frames per render mode through the same
`HollowLaunch.start` a launcher calls, Offscreen and Windowed, asserting frames still arriving each
second, no swallowed throwable, and the HUD still in the capture at the end. **Seen red** as row M7,
in both modes.

### The transcript the images are read against (client 0, at this SHA)

```
[hollow.fight] fight-client0-00.png tick 152 server 146: player184=100 player185=100 fox186=100 fox187=100 fox188=100 fox189=100 fox190=100
[hollow.fight] fight-client0-03.png tick 188 server 182: player184=100 player185=92 fox186=100 fox187=100 fox188=100 fox189=100 fox190=100
[hollow.fight] fight-client0-04.png tick 200 server 194: player184=100 player185=86 fox186=100 fox187=41 fox188=100 fox189=32 fox190=100
[hollow.fight] fight-client0-07.png tick 236 server 230: player184=100 player185=73 fox186=41 fox187=0 fox188=34 fox189=0 fox190=100
[hollow.fight] fight-client0-10.png tick 272 server 266: player184=92 player185=73 fox186=0 fox187=0 fox188=0 fox189=0 fox190=38
[hollow.fight] fight-client0-13.png tick 308 server 302: player184=92 player185=73 fox186=0 fox187=0 fox188=0 fox189=0 fox190=0
[hollow.fight] fight-client0-15.png tick 332 server 326: player184=92 player185=73 fox186=0 fox188=0 fox190=0
[hollow.fight] fight-client0-18.png tick 368 server 362: player184=92 player185=73 fox190=0
[hollow.fight] fight-client0-21.png tick 404 server 398: player184=92 player185=73
[hollow.fight] fight-client0-23.png tick 428 server 422: player184=92 player185=73
```

Elided: frames 01, 02, 05, 06, 08, 09, 11, 12, 14, 16, 17, 19, 20, 22, each a consecutive line of
the same run's output. Read it downward and the fight is all there: the first bite at tick 188, two
foxes dead by 236, all five at zero by 308, the corpses collected one by one from 332 (the fox
count falls 5, 3, 1, 0), and both players alive on 92 and 73.

### What I did not exercise

- **A player dying.** Two players beat one wave without going below 73 hit points, so the HUD's
  death banner and a dead character's fall are covered by `CombatTest` and `HollowHudTest`
  headlessly but appear in no screenshot.
- **The dash and the heal in a picture.** Both are tested headlessly; neither is fired by the fight
  shot, whose clients hold attack and a move axis only.
- **iOS.** `compileTestKotlinIosArm64` ran in the green build; running iOS tests needs macOS and was
  not done here.
- **A second wave.** The shot plays one wave of five. `FoxWaveTest` and `HollowHudTest` cover the
  schedule's arithmetic past the first.
