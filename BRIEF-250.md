da2e88e

(The code as reviewed. The commit on top of it adds this file and nothing else.)

# BRIEF-250: Hollow H2 — the player, WASD and the mouse, the gait from the speed, and collisions

Branch `issue-250-hollow-player`, off `origin/master`. `23e53d1`, two commits back, is the merge of
`origin/master` at `9c95e0e` into the work, so this branch is 0 behind it. The merge had one conflict,
`ClearingLevelTest`'s mid-game-save test: master had rewritten its comment for the #246 follow-up
while this branch had rewritten its body for the new `HollowGame.build(...).use`. Resolved by
keeping master's comment and this branch's body.

## Evidence command

    sh gradlew :hollow:game:jvmTest --tests "dev.wildware.hollow.PlayerMovementTest" --tests "dev.wildware.hollow.PlayerAnimationTest" --tests "dev.wildware.hollow.net.PlayerReplicationTest"

17 tests: 6 on movement and collisions, 5 on the gait, 6 on a server with two clients.

**It goes red when the feature is reverted**, and the "Mutations" table below is the proof: every
row is one change to production code, with its literal `git diff` taken from the run, and the tests
that run turned red. `M1` — the one that neutralises the engine change this ticket needed — turns
**8 of the 17** red across all three files.

`:hollow:desktop:runPlayerShot` is the picture, and it is deliberately **not** the evidence command:
with the movement reverted it still writes 30 PNGs, of a character standing in a field.

## Summary

### The engine gap, which is the part that was not in the ticket

A dynamic Box2D body could not be **steered**. `PhysicsBody.linearX/linearY` reached the solver only
when the body was built, so a game could write a velocity every tick and the body carried on with
whatever it was created holding. `SolverBackend` gains `pushWrittenVelocity(component)`, and
`DrivenVelocitySystem` (`SimPhase.Physics`, after `PhysicsReconcileSystem`, before
`PhysicsStepSystem`) calls it once a tick for every dynamic body.

The cost argument is exact rather than approximate: the backend compares the component's velocity
**bitwise** against the one the solver last wrote back, and does nothing when they match. A scene
nobody steers makes zero native calls, and `DrivenBodyTest` asserts that on a counter rather than
asserting it in prose — including "exactly one push on the tick the game wrote a velocity, and none
on the others". Alternatives rejected (teleport per tick, impulses, rebuild-on-change) are argued on
the issue: <https://github.com/wildware-uk/Udea/issues/250#issuecomment-5750103499>.

### The player

`Player` is `@Replicated`, five plain floats-and-friends, no Kool type: `heading`, `moveX`, `moveY`,
`owner`, `running`. It is driven through the same `IntentSource` seam a keyboard, an agent's
`input.*` tool and a datagram all go through, so `PlayerControlSystem` cannot tell them apart.

- **WASD is camera-relative.** `CameraRelativeIntent` (presentation) turns the screen axis into a
  world axis using `ThirdPersonRig`'s reported ground facing, so the *intent* carries the facing and
  the rig still writes nothing into the world.
- **`PlayerMovementSystem`** (Movement) writes `linearX/linearY` and nothing else: walk 2.4 u/s, run
  5.4 u/s, the axis clamped to the unit circle so a diagonal is no faster than a straight line.
- **`PlayerPoseSystem`** (PostPhysics, after the solver) does the two halves that must come from
  different sources. The **heading** is what the player asked for, remembered on `Player.heading` and
  written to `Transform3D.rotationZ` every tick — every tick because `Transform3DFromBodySystem` has
  just overwritten `rotationZ` with the body's solved angle, so a heading written only while moving
  is undone the moment the player stops. The **gait** is what the solver actually produced: idle
  below 0.2 u/s, walk, then run at 3.9 u/s, with a 12-tick crossfade. A character pushing into a
  rock therefore stands still facing the rock, rather than running on the spot.
- **`ClearingBodySystem`** gives every piece of scenery a static body from a per-prop `blockRadius`
  measured off the model's own accessor bounds (trees narrowed to their trunks).

### Multiplayer

`HollowGame.build` takes a `NetRole`. A client installs no `Physics2DModule` and registers none of
Hollow's systems, because a client that simulated would fight replication for the same fields and its
own dynamic body would hold `Transform3D` hostage. It still ticks, so its clock advances and the
animation plays. Its own character is predicted through `udea-net`'s `LocalPrediction` over
`HollowMoveModel` — the server's arithmetic as a pure function — so a key press moves the local
character on the tick it was pressed and reconciles against `PacketHeader.inputAck`.

Three smaller rulings (client simulation, physics components deliberately left out of the net
registry, and the one permitted `atan2`) are argued here:
<https://github.com/wildware-uk/Udea/issues/250#issuecomment-5750106664>.

### What I found that the ticket did not say

- **Characters collide with each other.** Two seats spawn 1.5 apart, and my first replication test
  drove one straight through the other, which is why two of its assertions failed on the first run.
  That is the solver being right, so the test now drives them apart *and* a new test drives one into
  the other on purpose and asserts every machine agrees about the push.
- **Two of my own assertions could not fail**, caught by M1 staying green where it should not have.
  Both held the live `Transform3D` across a `run` and then compared it with itself. Fixed in
  `2c2fc68`, and I grepped the class across every test this branch touches: **those two and nothing
  else** — everywhere else the capture is a `Float`, a `Tick` or a list of `Float`s, or nothing steps
  between the capture and the assert.
- **moba's roster golden `worldHash` moved without any unit moving**, because `WorldHasher` folds
  each component's global `typeId`. Checked programmatically that the other 29 lines are
  byte-identical before touching it; `TestLevelRosterTest`'s KDoc now records why.

### What I did not do

- **No agent-bridge session.** Hollow has no agent surface yet: `hollow/desktop/build.gradle.kts`
  says so in as many words — *"There is no `agent` source set and no `runEditor` yet: the agent
  surface and the editor are issue #255."* There is no `-PdebugPort`, so there is nothing for
  `launch_instance` to attach to, and the honest substitute is the scripted shot below, which drives
  the game through the same `IntentSource` a keyboard drives.
- **No iOS anything.** It cannot build on this Linux box.

## Mutations

Each mutation was applied to the working tree of `23e53d1` — the merge, two commits back; the only
code change since is `MoveAxis` and `HollowMoveModel` becoming `internal` — then the evidence
command was run and the mutation reverted. Each diff below is `git diff -U2` output spliced from
that run, and each failing list is that run's own output in the order it printed. Gradle's ordering
is not stable between runs, so the order here is one run's and is not a claim about ordering.

### M1 — the written velocity never reaches the solver

```diff
@@ -551,4 +551,5 @@ internal class Box2DPhysicsWorld(
         if (x.toRawBits() == solvedVelX[slot].toRawBits() && y.toRawBits() == solvedVelY[slot].toRawBits()) return
         val body = bodyIds[slot]
+        if (slot >= 0) return
         B2Body.setLinearVelocity(body, vec(vecA, x, y))
         if (x != 0f || y != 0f) B2Body.setAwake(body, true)
```

8 of 17 red:

```
PlayerAnimationTest[jvm] > walking plays the walk, running plays the run, and letting go goes back to idle()[jvm] FAILED
PlayerAnimationTest[jvm] > a change of gait is a crossfade rather than a cut()[jvm] FAILED
PlayerMovementTest[jvm] > holding the run control covers more ground than walking does()[jvm] FAILED
PlayerMovementTest[jvm] > a character pushing into a rock can still walk away from it()[jvm] FAILED
PlayerMovementTest[jvm] > holding a direction walks the character that way, and letting go stops it()[jvm] FAILED
PlayerReplicationTest[jvm] > what a player does to their character is the same on the server and on both clients()[jvm] FAILED
PlayerReplicationTest[jvm] > a character walked into another pushes it, and every machine sees the same push()[jvm] FAILED
PlayerReplicationTest[jvm] > the clip a character is playing is the same on the server and on both clients()[jvm] FAILED
17 tests completed, 8 failed
```

The body still coasts at whatever velocity it was *built* with, which is why the failures read as
"the running second covered 2.399998" rather than as nothing moving. That is the mutation restoring
the real pre-#250 shape rather than merely breaking something: before this ticket, the creation-time
velocity was the only one Box2D ever saw.

**This mutation is also how the two dead assertions were found.** On the first run of it,
`holding a direction walks the character that way, and letting go stops it` stayed green while the
body demonstrably coasted; a throwaway probe printed the body tick by tick
(`vel=(2.4, 0.0)` on every tick, `x` rising through the "stopped" half), which is what exposed the
assertion comparing a live component with itself.

### M2 — the heading is written only on the ticks the character is moving

```diff
@@ -66,5 +66,7 @@ public class PlayerPoseSystem : SimSystem() {
             // body's solved angle over `rotationZ`, and a character that stopped would otherwise
             // swing back to whatever torque a contact had put on its circle.
-            entity[Transform3D].rotationZ = player.heading + Player.MODEL_FACING
+            if (player.moveX != 0f || player.moveY != 0f) {
+                entity[Transform3D].rotationZ = player.heading + Player.MODEL_FACING
+            }
             val body = entity[PhysicsBody]
             val animator = entity[Animator]
```

1 of 17 red — and this is the version of the code I wrote first, which is why the mutation exists:

```
PlayerMovementTest[jvm] > the character faces the way it is walking()[jvm] FAILED
17 tests completed, 1 failed
```

### M3 — the run clip is never chosen

```diff
@@ -78,5 +78,5 @@ public class PlayerPoseSystem : SimSystem() {
         speed < IDLE_BELOW -> Human.Clips.Idle
         speed < RUN_AT -> Human.Clips.Walk
-        else -> Human.Clips.Run
+        else -> Human.Clips.Walk
     }
```

2 of 17 red:

```
PlayerAnimationTest[jvm] > walking plays the walk, running plays the run, and letting go goes back to idle()[jvm] FAILED
PlayerReplicationTest[jvm] > the clip a character is playing is the same on the server and on both clients()[jvm] FAILED
17 tests completed, 2 failed
```

### M4 — a joining peer's character is never stamped with its owner

```diff
@@ -138,4 +138,5 @@ public class HollowServer(
         replication.addClient(peer)
         val character = unownedCharacter()?.also { stamp(it, peer.raw) } ?: spawnCharacter(peer)
+        stamp(character, Player.UNOWNED)
         val seat = Seat(
             peer = peer,
```

2 of 17 red:

```
PlayerReplicationTest[jvm] > the local character answers on the tick the key goes down, and converges when it stops()[jvm] FAILED
PlayerReplicationTest[jvm] > each client is told which character is its own, and they are different characters()[jvm] FAILED
17 tests completed, 2 failed
```

### M5 — no piece of scenery is given a collision body

```diff
@@ -49,5 +49,5 @@ public class ClearingBodySystem : SimSystem() {
             // has one radius - an ellipse is not a shape `udea-physics2d` has.
             val radius = prop.blockRadius * entity[Transform3D].scaleX
-            if (radius <= 0f) return@forEach
+            if (radius >= 0f) return@forEach
             entity.configure {
                 it += PhysicsBody(kind = BodyKind.Static)
```

2 of 17 red — and these two are the ticket's "the player stops at a rock", which M1 does **not**
turn red, because under M1 the character coasts into the rock on its creation velocity and the
contact still stops it:

```
PlayerAnimationTest[jvm] > a character pushing into a rock is idle, because it is not going anywhere()[jvm] FAILED
PlayerMovementTest[jvm] > the character stops at a rock and does not walk through it()[jvm] FAILED
17 tests completed, 2 failed
```

## `sh gradlew build`

The full run, no exclusions, on the merged tree:

    ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew build --continue

The run that executed the work — 833 tasks — failed one test, and it is not this branch's:

```
* What went wrong:
Execution failed for task ':udea-net:allTests'.
> There were failing tests. See the report at: file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a270e9172406f94bb/udea-net/build/reports/tests/allTests/index.html
```

```
message="org.opentest4j.AssertionFailedError: rateLimited moved during a well-behaved session: {malformed=0, oversized=0, unknownConnection=0, replayed=0, rateLimited=1, amplificationBlocked=0, tokenRejected=0, tokenExpired=0, completed=2, denied=0, fragmentsTimedOut=0, fragmentsRefused=0, sendsToUnknownPeer=0, receiveErrors=0} ==&gt; expected: &lt;0&gt; but was: &lt;1&gt;"
```

`UdpTwoProcessTest` is two OS processes over a real socket, and this branch touches no transport.
Re-run alone it is green:

```
BUILD SUCCESSFUL in 24s
```

and the next full build is green too:

```
BUILD SUCCESSFUL in 6s
1108 actionable tasks: 22 executed, 1 from cache, 1085 up-to-date
```

Before the merge, the same command on this branch's own work ran cold after a `clean`:

```
BUILD SUCCESSFUL in 20s
1097 actionable tasks: 677 executed, 328 from cache, 92 up-to-date
```

(Fast because the build cache was warm from the runs that produced it; 677 tasks executed.)

Two failures during development were mine and are fixed in the tree under review:
`ClearingReplicationTest`'s `entitiesCreated` count, which moved because a joining peer now gets a
character, and moba's roster `worldHash`, above.

## GL, run for real

This ticket draws a character, so the GL suites were run with a driver rather than skipped:

    xvfb-run -a -s "-screen 0 1280x720x24" \
      env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
      sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true

```
> Task :udea-agent-host:udeaAgentGlTest
> Task :udea-render:udeaGlTest
> Task :udea-editor:udeaEditorGlTest

BUILD SUCCESSFUL in 1m 45s
126 actionable tasks: 12 executed, 114 up-to-date
```

## The pictures

All under `/srv/ssd1/workspace/Udea/build/debug-screenshots/`, written by
`sh gradlew :hollow:desktop:runPlayerShot` (30 PNGs, one every 15 ticks, each named with the tick it
was taken on in the task's own output).

| File | What it shows | What it proves |
|---|---|---|
| `issue250-sequence.png` | All 30 frames: standing, walking north, a lap of the clearing at a run, then the charge into a rock | The character exists, is animated, is followed by the camera, and goes where it is driven |
| `issue250-stops-at-a-rock.png` | Frames 24-29 — sprinting at a boulder, then standing against it for four frames with the key still held | Issue #250's first criterion, as a picture: the solver stops the character, and the gait follows the solver (clip 2 → clip 0) |
| `issue250-idle.png` | Frame 0, tick 74, nobody driving | The idle clip plays on a character with no input |
| `issue250-walk.png` | Frame 3, tick 119, walking north | The walk gait and the model facing the way it walks |
| `issue250-run.png` | Frame 14, tick 283, mid-lap at a run | The run gait |

The task's transcript is what names each frame's tick, position, facing and clip, so a frame can be
read back against the route; the last six lines of the run are:

```
[hollow.player] player-024.png tick 433: (2.97, 2.40) facing 1.57, clip 2
[hollow.player] player-025.png tick 448: (4.32, 2.40) facing 1.57, clip 2
[hollow.player] player-026.png tick 464: (4.61, 2.40) facing 1.57, clip 0
[hollow.player] player-027.png tick 478: (4.61, 2.40) facing 1.57, clip 0
[hollow.player] player-028.png tick 493: (4.61, 2.40) facing 1.57, clip 0
[hollow.player] player-029.png tick 508: (4.61, 2.40) facing 1.57, clip 0
```

Clip 2 is `Human.Clips.Run` and clip 0 is `Human.Clips.Idle`, per the generated `Human` accessor.
The x stops at 4.61 and stays there while the route is still holding "east, running".

## The issue, criterion by criterion

| Criterion | Proved by |
|---|---|
| **Headless: input moves the player** | `PlayerMovementTest`: `holding a direction walks the character that way, and letting go stops it` (a second of walking covers `WALK_SPEED` ± 0.06 and stops dead on release), `holding the run control covers more ground than walking does`, `a diagonal is no faster than a straight line`. The first two go red under M1; the diagonal one does not, because under M1 the body coasts on the clamped velocity it was *created* with, which is the same length. What pins the diagonal is the distance it asserts — `WALK_SPEED` ± 0.06 along the hypotenuse, where an unclamped diagonal would be 41% further. |
| **Headless: the player stops at a rock** | `PlayerMovementTest`: `the character stops at a rock and does not walk through it` (it reaches the rock's face and does not pass it, and the static rock does not move) and `a character pushing into a rock can still walk away from it`. Both go red under M5, which takes the scenery's collision bodies away; the walk-away one goes red under M1 too. Picture: `issue250-stops-at-a-rock.png`. |
| **The animation follows speed, shown by a test on the `Animator`** | `PlayerAnimationTest`, 5 tests: idle with no input; walk, then run, then idle again; the change of gait is a crossfade with the old clip kept and `blendWeight` between 0 and 1 partway through; the clip is not restarted while the gait holds and its time comes from its start tick; **and a character pressed against a rock at full intent plays the idle**, which is the one that says the gait comes from the solver rather than from the key. M3 turns the run test red; M5 turns the rock one red. |
| **A screenshot sequence of the player running round the clearing** | `issue250-sequence.png` — 30 frames from `:hollow:desktop:runPlayerShot`, route and per-frame transcript above. |
| **(owner-added) A headless test runs a server and two clients, in-process, and this ticket's feature agrees on every machine** | `PlayerReplicationTest`, 6 tests over `NetHarness` at 9 ticks of latency each way: each client is told which character is its own; position agrees on all three machines to every replicated field of `Transform3D` after two players drive in different directions; the clip agrees on all three, mid-walk, mid-run and after stopping, to every replicated field of `Animator`; a character nobody drives stays put everywhere; **one character walked into another pushes it and all three agree about both**; and the local character answers on the tick the key goes down, leads the server's answer by at least the input in flight, never snaps, and converges exactly when the key is released. |

## Regenerated files

`dev.wildware.hollow.Player` sorts before every `dev.wildware.moba.*` and `dev.wildware.udea.*`
name, so it takes id 0 and **every component id after it moves by exactly 1**.

| File | Task | What moved |
|---|---|---|
| `net-components.lock` | `udeaWriteProtocolLock` | `dev.wildware.hollow.Player` inserted first; 11 lines added |
| `udea-core/net-protocol.lock` | same | ids 22-25 → 23-26 and so on; `protoHash 0xcd86` → `0xad48` |
| `udea-codegen/net-protocol.lock` | same | ids +1; `protoHash` moves |
| `moba/game/net-protocol.lock` | same | ids +1 across 16 components; `protoHash` moves |
| `hollow/game/net-protocol.lock` | same (new file, `registerNetProtocolLock` added to `hollow/game/build.gradle.kts`) | new: `component 0 dev.wildware.hollow.Player`, `protoHash 0xc16a` |
| `udea-codegen/src/test/resources/expected-generated-hashes.txt` | `:udea-codegen:test -Pudea.updateGeneratedHashes=true` | 7 rows |
| `moba/desktop/src/test/resources/fixtures/moba-3600.udearep`, `moba-36000.udearep` | `:moba:desktop:udeaWriteReplayFixture` | rebuilt; the task said why: `protoHash: recorded 0xe91d, this build 0x07b6` |
| `moba/desktop/src/test/resources/levels/test_level.roster.txt` | hand-edited, one line | `worldHash=bf7638fec93721f1` → `fd265e081128cdd2`. `WorldHasher` folds each component's `typeId`. The other 29 lines are byte-identical, checked by extracting both sides out of the JUnit XML and diffing them line by line rather than by eye. |

**Re-checked in the merged tree**, as the standing rule asks: after merging `origin/master` I ran
`udeaWriteProtocolLock` again and `git status` reported no change to any lock, so the merge produced
a lock that agrees with the regeneration.

## Files a reviewer will want first

- `udea-physics2d/src/commonMain/.../Physics2DModule.kt` — `SolverBackend.pushWrittenVelocity` and
  `DrivenVelocitySystem`.
- `udea-physics2d/src/box2dMain/.../Box2DPhysicsWorld.kt` — the bitwise guard and the counter.
- `hollow/game/src/commonMain/.../Player.kt`, `PlayerPose.kt`, `HollowMovement.kt`,
  `ClearingBodies.kt`.
- `hollow/game/src/commonMain/.../net/HollowServer.kt`, `HollowClient.kt` — seats, the intent
  router, prediction.
- `hollow/game/src/jvmTest/.../PlayerScene.kt` — the fixture the movement and animation tests drive.
