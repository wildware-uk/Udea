fdf050f

(The code under review is `fdf050f`: `76223bf` plus a merge of `origin/master` at `763b9c0`. This brief is committed on top of it and changes no code.)

# #246 Hollow E1: Transform3D replicates, with 3D render interpolation

## Evidence command

    sh gradlew :udea-net:jvmTest --tests dev.wildware.udea.net.replication.Transform3DReplicationTest :udea-render:jvmTest --tests dev.wildware.udea.render.interp.Interpolation3DTest

Green on `fdf050f`. It goes red for both halves of the feature. The diffs were taken from the runs (`scratchpad/issue246/mutations/*.diff`, `*.result`):

- **Replication reverted to master's shape** (every field `@Sim`, M10): 2 `Transform3DReplicationTest` failures, both `the client never received the entity`. With an empty net mask, the client never gets the component at all.
- **Interpolation removed** (M1, the four lerp lines deleted): 4 `Interpolation3DTest` failures: fractional alpha, 144Hz smoothness, barrier-applied snapshot, short-arc heading.

## Summary

- **`Transform3D`: every field is now `@Net`** (udea-core). Before, all were `@Sim`, so a client never saw a 3D entity at all.
  - I added `Transform3D.snapshotType()`, the registration a game puts in its `ComponentRegistry`, the same pattern as `Animator.snapshotType()`. Before this, nothing registered `Transform3D`, so it was not captured, rewound or replicated in any game.
- **Why all nine fields and not only x, y, z and heading** (decision, commented on the issue):
  - A client applies received state with `ReplicatedComponentType.applyOnto`, which writes through `allMask`.
  - With scale left `@Sim`, the client got scale 0: `expected: <[0.3, -0.2, 2.0, 3.0, 4.0]> but was: <[0.0, 0.0, 0.0, 0.0, 0.0]>` (red2, spliced from the saved XML).
  - Deltas carry only changed fields, so a scale that never changes is sent once, in the create record.
- **3D render interpolation** (udea-render `interp`, all `internal`):
  - `Interp3D` is a presentation-local component. `Interp3DSnapshotSystem` records position and heading at the **end** of each tick (`SimPhase.Cleanup`, registered by `RenderModule`). `Interpolator3D` lerps position and short-arc lerps heading at the render alpha. `Pose3D` is the out-parameter.
  - **Why end of tick, unlike 2D `Interp`, which records at the start:** a client's snapshot lands through the `SimBarrier`, which is drained *before* PreSimulation. A start-of-tick record would already hold the new position, and the model would hop. Pinned by `a position a snapshot applied through the barrier is slid to, not jumped to`.
  - **Draws current without lerping** in three cases: the entity has no record yet; a restore frame (same rule as `Interpolator`); or the transform was moved *between* ticks (an editor gizmo while paused), so a drag is not drawn a tick late.
  - Pitch, roll and scale are drawn as they stand.
- **`ModelRenderSystem` draws through it.** The placement logic moved into an `internal ModelPlacer` (GL-free, unit tested). It finds `Interp3DSnapshotSystem` in `world.systems` at bind. A world without `RenderModule` draws transforms as they stand, as before.
- **Docs.** I updated `AGENTS.md` ("What the engine does today"), `Transform3D` and `RenderModule` KDoc, and the `Transform3D` comment in `net-components.lock` (a comment only; no name added or removed).

**Rejected alternatives.**
- Keeping pitch, roll and scale `@Sim` and making clients apply only `netMask`: `applyOnto` also serves snapshot restore, which must write every field.
- Reusing `udea-net`'s `EntityInterpolator` (the delayed clock between snapshots): it is 2D-only, and udea-render cannot depend on udea-net.

**Scope notes, in the brief rather than a new issue.**
- (a) For H2: a predicted position must be written inside a tick (a system, or the barrier). A write between ticks is drawn as it stands, on purpose.
- (b) `@Net(authority = ...)` on a *field* has no runtime effect today; only RPCs read it. I left the default `Server`.
- (c) The lock gap, below.
- (d) A `Transform3D` teleport (respawn) is smeared over one tick (1/60s). There is no 3D `Teleport` snap yet.

For dev-248: the camera rig can switch to `Interpolator3D` (it is internal to udea-render). We agreed on this by message.

## `sh gradlew build`

`build.log` (scratchpad/issue246), on `fdf050f`, with other developers' builds on the box (load around 8):

    BUILD SUCCESSFUL in 2m 54s
    984 actionable tasks: 712 executed, 153 from cache, 119 up-to-date

GL, for real: `xvfb-run -a -s "-screen 0 1280x720x24" env LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe sh gradlew udeaGlTest udeaAgentGlTest udeaEditorGlTest -Pudea.render.requireGl=true`, from `gl.log`:

    BUILD SUCCESSFUL in 2m 58s
    126 actionable tasks: 12 executed, 114 up-to-date

Counted from the XML reports: udeaGlTest 24 tests, udeaAgentGlTest 2, udeaEditorGlTest 6, with **0 skipped** and 0 failed in each. That includes the new `GlModelInterpolationTest` and the existing 3D gizmo-drag GL test, which renders `Transform3D` models.

The gates outside `check`:
- `:moba:desktop:runNetProof`: BUILD SUCCESSFUL, every `unitHash`/`animHash`/`worldHash` line `MATCH` (for example `worldHash client=0xd0f06ffc72eacdb5 server@t240=0xd0f06ffc72eacdb5 MATCH`).
- `:moba:desktop:runUdpProof`: BUILD SUCCESSFUL, `MobaUdpTwoProcessTest` 2 tests, 0 failures.

## Images (`/srv/ssd1/workspace/Udea/build/debug-screenshots/`)

- `issue246-alpha-sequence.png`: `GlModelInterpolationTest`'s five frames. A cube jumped left to right in one tick, through the barrier as a snapshot does, drawn at alpha 0, 0.25, 0.5, 0.75 and 1. It slides and turns, and is halfway at 0.5.
- `issue246-before-no-interpolation.png`: the same test with M1 applied, under xvfb. All five frames show the cube on the right. The test fails: `the cube barely moved between the two ticks: [327.97983, 327.97983, 327.97983, 327.97983, 327.97983]` (from `mutations/M1-gl.xml`).

## The issue, criterion by criterion

1. **Headless net test: the server moves a 3D entity and the client's `Transform3D` follows within the usual delay.** `Transform3DReplicationTest` (udea-net).
   - Real `ReplicationServer`/`ReplicationClient` over the in-memory network, with a 3-tick latency. Each tick, the received store is applied onto a client Fleks world with `applyOnto`.
   - Asserts the client's x, y, z and heading equal the server's pose at the client's server tick, on every tick.
   - Asserts the lag is at most latency + 1, and that the entity actually travelled (x > 20).
   - A second test checks pitch, roll and scale arrive.
2. **Interpolation is smooth between snapshots, tested on values at fractional alpha.**
   - `Interpolation3DTest`: exact values at alpha 0, 0.25, 0.5 and 0.75; under 1% per-frame variation at 144Hz; a barrier-applied snapshot slid to rather than jumped to; alpha 1 bit-exact; the heading's short arc; the restore, between-ticks and new-entity rules; and purity (a world with and without the system ticks identically).
   - `ModelPlacerTest`: the renderer's placement uses it.
   - `GlModelInterpolationTest`: pixels, plus the images above.
3. **`runNetProof` and `runUdpProof` stay green, and replay equality holds.**
   - Both proofs are green (above).
   - `MobaReplayEqualityTest` (9), `MobaReplayProofTest` (4) and `MobaReplayFixturesCurrentTest` (1) are green in the build, and `udeaVerifyDeterminism` runs on `check`.
   - What these do **not** show: moba registers no `Transform3D`, so they prove nothing regressed, not 3D replay. For 3D: capture and restore of every field is `Transform3DSnapshotTypeTest`, and the render recorder not touching simulated state is the purity test. A 3D replay over a recorded match is H6's.

## Mutation table (diffs in `scratchpad/issue246/mutations/`)

| # | Diff (from the run) | Failing tests |
|---|---|---|
| M1 | `Interpolator3D.kt`: `-into.x = Interpolator.lerp(interp.prevX, t.x, alpha)` and the y, z and `rotationZ` lines deleted | Interpolation3DTest: 144Hz, barrier snapshot, fractional alpha, short arc; ModelPlacerTest: between the last two ticks (GL: GlModelInterpolationTest) |
| M2 | `-if (isRestoreFrame \|\| movedBetweenTicks(t, interp)) return true` `+if (isRestoreFrame) return true` | Interpolation3DTest: moved between ticks |
| M3 | same line `+if (movedBetweenTicks(t, interp)) return true` | Interpolation3DTest: first frame after a rewind |
| M4 | `-...lerpAngle(interp.prevHeading...` `+...lerp(interp.prevHeading...` | Interpolation3DTest: short way across pi |
| M5 | `Interp3DSnapshotSystem.kt`: `+t.rotationX = 0f` after `record(...)` | Interpolation3DTest: purity; pitch, roll and scale drawn as they stand |
| M6 | `ModelPlacer.kt`: `firstOrNull { it is Interp3DSnapshotSystem }` changed to `firstOrNull { false }` | ModelPlacerTest: between the last two ticks |
| M7 | `RenderModule.kt`: `-registry.add(SimPhase.Cleanup, { Interp3DSnapshotSystem() })` | RenderModuleTest: 3D pose record in Cleanup |
| M8 | same line with `SimPhase.PreSimulation` | RenderModuleTest: 3D pose record in Cleanup |
| M9 | `Transform3D.kt`: rotationX, rotationY and scaleX/Y/Z `@Net` changed to `@Sim` | Transform3DReplicationTest: pitch, roll and scale; Transform3DSnapshotTypeTest: every field sent |
| M10 | `Transform3D.kt`: all nine changed to `@Sim` (master) | both Transform3DReplicationTest tests; Transform3DSnapshotTypeTest: every field sent |

## Regenerated files

I ran `:udea-codegen:udeaWriteProtocolLock`, `:udea-core:udeaWriteProtocolLock` and `:udea-codegen:test -Pudea.updateGeneratedHashes=true`. **The diff was empty for all three** (`locks.diff` and `locks2.diff` are 0 lines). No id moved and no lock `protoHash` changed. `udeaCheckProtocolLock` passes for udea-core, udea-codegen and moba:game.

**Why the lock did not move: the lock cannot see this change.** `net-protocol.lock` records ids, names, widths and ranges, but not whether a field is `@Net` or `@Sim`. The runtime handshake hash does see it: `ProtocolDescriptor.of` mixes each type's `netMask`, so two builds that differ on this refuse each other at connect. No shipped registry contains `Transform3D` (moba does not register it), so no running game's protocol hash changes. I left this gap for the lead rather than widening the lock format in this ticket.

The field-alignment invariant holds: `Transform3DSnapshotTypeTest` checks name, mask bit and store column for every field.
