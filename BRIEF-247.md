3d2f210

(The code under review is `3d2f210`; this brief is committed on top of it and changes nothing else.)

# #247 Hollow E2: ground-plane physics drives Transform3D

Branch `issue-247-physics-transform3d`, from `origin/master` (merged up to `5722075`).
Worktree `/srv/ssd1/workspace/Udea/.claude/worktrees/agent-ae617503453103363`.
Artefacts below live in `S=/tmp/claude-1000/-srv-ssd1-workspace-Udea/1ad8c5e6-2def-4055-91d2-72acdfe77daf/scratchpad/issue247`.

## Evidence command

    ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew :udea-physics2d:jvmTest --tests dev.wildware.udea.physics2d.Transform3DPhysicsTest

It leaves the test XML and two CSVs, `udea-physics2d/build/reports/udea/transform3d/{wall,push}.csv`:
every entity's pose at every tick of the wall and push scenes, which the images are drawn from.

On `3d2f210`: `tests="8" skipped="0" failures="0" errors="0"` (`$S/evidence-green-results.xml`).

**Red with the feature reverted.** `$S/revert-proof.sh` checks the production files out of
`origin/master` (keeping the tests), deletes `Transform3DSystems.kt`, runs the command and puts the
branch back (`$S/revert-proof-status-after.txt` is empty: clean). The staged diff it reverted is
`$S/revert-proof.diff` (5 files, 3 insertions, 183 deletions). From `$S/revert-proof.log`:

```
Transform3DPhysicsTest[jvm] > a body without a Transform3D moves exactly as the solver alone moves it()[jvm] FAILED
Transform3DPhysicsTest[jvm] > a kinematic body follows its Transform3D and pushes a dynamic one()[jvm] FAILED
Transform3DPhysicsTest[jvm] > a dynamic body pushed into a static wall stops at the wall and its Transform3D shows it()[jvm] FAILED
Transform3DPhysicsTest[jvm] > a static body sits where its Transform3D says, and moves when it moves()[jvm] FAILED
Transform3DPhysicsTest[jvm] > a dynamic body with a Transform3D starts where its Transform3D says()[jvm] FAILED
Transform3DPhysicsTest[jvm] > a kinematic body turns with Transform3D rotationZ()[jvm] FAILED
8 tests completed, 6 failed
BUILD FAILED in 17s
EXIT 1
```

The two rewind tests pass with the whole feature reverted, because with nothing driving
`Transform3D` a restore has nothing to get wrong. They are proved by mutations M4 and M7 below instead.

## Summary

An entity with a `PhysicsBody` and a `Transform3D` now has one pose that is the truth, and which
one depends on the body kind. All of it is in `udea-physics2d`; **no `udea-core` change** and
nothing in `Transform3D` touched (dev-246 owns it).

- **Dynamic**: the solver decides. After each step, `Transform3DFromBodySystem` copies
  `PhysicsBody.x/y/angle` (which the step just wrote) into `Transform3D.x/y/rotationZ`.
- **Kinematic**: `Transform3D` decides. Before each step, `BodyFollowsTransform3DSystem` calls the
  new internal `SolverBackend.moveKinematicTo`, which is Box2D's own `b2Body_SetTargetTransform`:
  the velocity that lands the body on its target in one tick. So it sweeps there and pushes what is
  in the way, rather than appearing inside it.
- **Static** (the issue is silent): `Transform3D` decides. The same system teleports a static body
  whose `Transform3D` moved (bitwise compare against `PhysicsBody`), and updates `PhysicsBody`.
- **Spawn**: `Transform3DSeedSystem`, before reconciliation, sets a not-yet-built body's
  `PhysicsBody.x/y/angle` from `Transform3D`, so a body starts where the game placed it in 3D.
  Only bodies without a handle, so a rewind (whose `rebuildFrom` gives every body a handle) always
  rebuilds from the restored `PhysicsBody`.
- `z`, `rotationX/Y` and the scale are never read or written.

The three systems are registered by `Physics2DModule.simulation` in `SimPhase.Physics`, ordered
around `PhysicsReconcileSystem` and `PhysicsStepSystem`. They hold no state between ticks, so a
restore has nothing of theirs to restore. The follow system visits bodies in ascending `NetId`,
as reconciliation does, because a static teleport moves a broad-phase proxy.

Nothing Box2D-typed is public: every new declaration is `internal`, and `NoBox2DInPublicApiTest`
is green. The Box2D side needed one new binding alias, `B2Transform`, in both `Box2DBindings.kt`
files (`Box2DBindingsMirrorTest` is green). I checked that the Android AAR has
`box2dandroid/b2Transform$Raw.class` before adding it.

**moba is unchanged**: no game installs `Physics2DModule` yet (`grep` of `moba/` finds none), and an
entity with no `Transform3D` is not visited by any of the three systems. The test
`a body without a Transform3D moves exactly as the solver alone moves it` checks that bit for bit
against the pre-#247 pipeline (reconcile, step, nothing else), beside a 3D entity the systems do move.

**Decisions**, commented on the issue:
https://github.com/wildware-uk/Udea/issues/247#issuecomment-5744084957
(static follows `Transform3D`; new bodies start at `Transform3D`; kinematic by velocity, not teleport).

**Tests** (`Transform3DPhysicsTest`, headless, zero gravity):
1. ball rolling into a static wall stops at its face, `Transform3D` equals the body bit for bit at every tick, `z` untouched;
2. a dynamic body starts at its `Transform3D`;
3. a kinematic pusher follows `Transform3D` within 1e-4 every tick and pushes a crate ahead of it;
4. a kinematic body turns with `rotationZ` over 80 ticks, past pi. Worst error 0.0016572475 rad
   (printed by the test). This is Box2D's approximate angle-to-rotation and rotation-to-angle
   functions. The error does not build up: each tick aims from where the body really is;
5. a static wall sits at, and moves with, its `Transform3D`, checked through a solver raycast and not only the component;
6. bodies without `Transform3D` move exactly as before (above);
7. restored run == run rebuilt from the same components, `Transform3D` bit for bit, in a contact-heavy scene;
8. restored run == the original run, `Transform3D` bit for bit, in a scene with no contacts or rotation.

Tests 7 and 8 are the engine's existing rewind guarantee (`Box2DPhysicsWorld` KDoc), now carried
through to `Transform3D`. **A restore is not bit-identical to the never-rewound run when bodies touch or
turn.** That is the known Box2D solver-state limitation `Box2DRewindTest` already measures, and nothing
here changes it. The kinematic body is driven the way a game system drives one (`x += step` from its
current `Transform3D`), so a restore that lost `Transform3D` would diverge (M7).

**Not done / for later** (not my call to file):
- Writing `Transform3D` on a *dynamic* body (an editor gizmo drag) is overwritten on the next step.
  Moving one is a `Teleport`, as in 2D. Hollow's editor ticket (H7, #255) may want a drag to teleport it.
- A `Teleport` on a kinematic or static body with a `Transform3D` is undone next tick, because
  `Transform3D` is the truth for those. Move them by writing `Transform3D`. This is in the
  `Physics2DModule` KDoc.
- A kinematic `Transform3D` jump of many units in one tick is a one-tick sweep at huge speed. That is
  correct for "follows" but hard on anything in the path.

Doc touches: `Physics2DModule` KDoc (a new section), `Box2DPhysicsWorld` KDoc (the "exactly two doors"
count became a list, because this adds a third), the `udea-physics2d` rows of `AGENTS.md` and
`docs/module-graph.md`.

## `sh gradlew build`

`ANDROID_HOME=... JAVA_HOME=.../21.0.11-tem sh gradlew --max-workers=6 --console=plain build --continue`,
on `3d2f210` (after merging `origin/master` at `5722075`). No exclusions. Tail of `$S/build1.log`:

```
BUILD SUCCESSFUL in 2m 13s
984 actionable tasks: 635 executed, 291 from cache, 58 up-to-date
Configuration cache entry stored.
EXIT 0
```

`grep -c FAILED $S/build1.log` gives `0`. Other builds were on the box (melon-merge, another Udea
worktree), and nothing failed, so there was nothing to re-run alone.

**GL:** not run under xvfb, because this ticket touches no GL module. The change is in `udea-physics2d`,
which is headless, plus docs. `udea-render`, `udea-agent-host` and `udea-editor` are not in the diff.

## Images

- `/srv/ssd1/workspace/Udea/build/debug-screenshots/issue247-wall-stops-ball.png`: a top-down view,
  every 20 ticks (the tile number is the tick). The dynamic ball's `Transform3D` rolls at the static
  wall and stops at x = 4.50, against the face at 5. **Proves AC1 visually.**
- `/srv/ssd1/workspace/Udea/build/debug-screenshots/issue247-kinematic-pushes-crate.png`: the same view.
  The kinematic pusher's `Transform3D` is at x = tick × 0.05 (1.00, 2.00 ... 6.00), and the crate is
  held ahead of it at pusher + 1 (7.00 at tick 120). **Proves AC2 visually.**

Both are **diagrams drawn from the test's CSVs** (`$S/draw.py`, then `tools/collage.py`), not engine
renders. Hollow has no scene or models yet (H1/H2), so there is nothing 3D to screenshot. Each entity is drawn at
its `Transform3D` pose, with the size from its shape component. Source CSVs: `$S/wall.csv`, `$S/push.csv`.

## The issue, criterion by criterion

| Criterion | Proof |
|---|---|
| A dynamic body pushed into a static wall stops at the wall, and `Transform3D` shows it (headless test, seen red) | Test 1. Red before the code (`$S/red1-results.xml`: `tick 0: Transform3D.x is the body's x ==> expected: <1032358025> but was: <0>`), red with the feature reverted, and red under M1. Image `issue247-wall-stops-ball.png` |
| A kinematic body follows `Transform3D` and pushes a dynamic one | Tests 3 and 4. Red before the code (`tick 0: the pusher's body is at 0.0, its Transform3D at 0.05`) and under M2. Image `issue247-kinematic-pushes-crate.png` |
| Rewind and restore give bit-identical `Transform3D` after re-simulation (test) | Tests 7 and 8, within the scope in the Summary. Red under M7 (both) and M4 (test 8) |

## Mutations

Each row's diff is verbatim in `$S/mut/<id>.diff` (made by `$S/mut/mutate.py`, run by
`$S/mut/runall.sh`, which reverts with `git checkout` after each). The logs are `$S/mut/<id>.log`, run
on `3d2f210`. The full `udea-physics2d` suite is 44 tests.

| Id | Mutation (the diff's changed lines) | Tests that went red |
|---|---|---|
| M1 | `-        registry.add(SimPhase.Physics, { Transform3DFromBodySystem() }) {` (and its two lines) | wall test; kinematic push test; "without a Transform3D" test (its check that the systems ran) |
| M2 | `-        B2Body.setTargetTransform(bodyIds[slot], transform, tickSeconds)` | kinematic push; kinematic turn |
| M3 | `rebuildFrom`: `-B2World.destroyWorld(worldId)` / `-worldId = openWorld()` / `+destroyAllBodies()` (reuse the world, the alternative the KDoc rejects) | only `ChainStaticGeometryTest` (a test that already existed). **None of mine**: both of my rewind comparisons go through the same rebuild, and nothing in these scenes shows the difference |
| M4 | `-        registry.add(SimPhase.Physics, { Transform3DSeedSystem() }) {` (and its two lines) | dynamic-starts-at-Transform3D; restore == original (the kinematic now starts at the origin, not at y = -20, and sweeps through the ball) |
| M5 | `-            BodyKind.Static -> if (` / `+            BodyKind.Static -> if (false &&` | static body test |
| M6 | a kinematic body with no `Transform3D` is held at its own pose: `+            ?: Transform3D(x = body.x, y = body.y, rotationZ = body.angle)` | "without a Transform3D" test |
| M7 | the scene's registry drops `Transform3D`: `+    val registry = ComponentRegistry((PhysicsSnapshotTypes.all() + transform3DType()).dropLast(1))` | restore == control; restore == original |
| M8 | after the step, snap a kinematic's `PhysicsBody` to its `Transform3D` (+7 lines in `Transform3DFromBodySystem`) | **none: survived.** In these scenes the solver lands the kinematic body exactly on its target, so the snap writes the value already there. Equivalent here, and kept as a record |

## Regenerated files

None. No component was added or removed. `net-protocol.lock` and `expected-generated-hashes.txt` are
untouched, and so is `docs/contracts/`.
