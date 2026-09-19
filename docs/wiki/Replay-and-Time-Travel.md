# Replay and Time Travel

Because the simulation is deterministic, Udea can record only a match's **inputs** and play the whole match back later, exactly. That recording is a `.udearep` file. It also stores a hash of the world at the end of every tick, so a replay proves itself: either every tick's hash matches, or you get the first tick where it did not and the fields that differ. Separately, a running game keeps a ring of recent snapshots so an agent can pause it, step it, and rewind it by any number of ticks.

A plain picture: a chess game written down as moves. Anyone can replay the moves and reach the same board. Writing the board position down after every move as well lets you spot the exact move where two replays went different ways.

## Rewind: the snapshot ring

A game built with a `TimeTravelFactory` keeps a `SnapshotRing` (`udea-core/src/commonMain/kotlin/dev/wildware/udea/core/snapshot/SnapshotRing.kt`). `moba` passes `snapshotTimeTravel(componentRegistry(...))` to its `UdeaGameDef`. The ring's shape is a `RingConfig`:

| Setting | Default | Meaning |
|---|---|---|
| `denseTicks` | 120 (about 2 seconds) | Every captured tick is kept this far back. This is the window prediction rolls back within |
| `sparseWindowTicks` | 3600 (about 60 seconds) | How far back keyframes are kept. This is the window an agent can rewind |
| `sparseInterval` | 6 (about 10 a second) | Keyframe spacing outside the dense window. It doubles under memory pressure; the window itself never shrinks |
| `budgetBytes` | `SnapshotBudgets.RING_BYTES` | A hard ceiling on the ring's memory |

A game with no ring is valid too: a dedicated server that nobody observes pays one null check a tick and keeps no history. Every rewind on it answers `RewindFailure.NoSnapshotRing`.

### `TimeControl`

`GameHost.time` is a `TimeControl` (`udea-core/src/commonMain/kotlin/dev/wildware/udea/core/loop/TimeControl.kt`). It offers `pause()`, `resume()`, `step(n)`, `timeScale(x)`, `snapshot()`, `listSnapshots()`, `rewind(ticks)` and `fastForward(ticks)`.

`rewind(ticks)` restores the newest keyframe at or before the target and then runs plain steps to close the gap. So it lands **exactly** on the tick asked for, not only on keyframe ticks. How it stays safe:

- It pauses the loop first, and waits for the tick in flight to finish.
- The restore is a named action on the `SimBarrier`, and the rewind forces the drain itself. If it waited for the next `step()`, the restore would apply and then a tick would run, so every rewind would overshoot by one.
- Failures are returned as a `RewindResult`, never thrown, and a refused rewind leaves the loop as it found it. The one exception is `RewindFailure.RestoreFailed`: the restore threw halfway, the world is in an unknown state, and the loop stays paused on purpose.

An agent reaches all of this through the `time.*` tools: `time.pause`, `time.resume`, `time.step`, `time.set_time_scale`, `time.snapshot`, `time.list_snapshots`, `time.rewind` and `time.fast_forward`. See [Agent Tool Surface](Agent-Tool-Surface).

What a snapshot restores depends on the `ComponentRegistry`: a component that is not registered is not captured, so a rewind cannot put it back. Physics bodies are rebuilt from their components, never restored from the solver. See [ECS and Components](ECS-and-Components) and [Physics](Physics).

## Recording: the `.udearep` file

`udea-replay` holds the recorder, the file format and the replay tools. `ReplayRecorder` (`udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayRecorder.kt`) is append-only and strict: `record` refuses any tick that is not exactly one after the previous one, and names both ticks. A recording that skipped or doubled a tick would otherwise replay into a divergence thousands of ticks later. `seal()` turns it into an immutable `ReplayRecording`.

The format (`udea-replay/src/commonMain/kotlin/dev/wildware/udea/replay/ReplayFormat.kt`) is self-describing and length-prefixed:

```text
MAGIC          8   "UDEAREP" + 0x1A
version        u16 FORMAT_VERSION
headerBytes    i32 length of the header section that follows
--- header ---------------------------------------------------------------------
rootSeed       i64   RngService.seed at record time
protoHash      i32   the build's wire-protocol hash
assetHash      u8 length, then that many bytes: AssetRegistry.contentHash
schemaHash     i64   the input schema's hash
tickRateHz     i32
firstTick      i64
tickCount      i32
peerCount      i32
gameId         string
gameVersion    string
axisNames      u16 count, then that many strings
actionNames    u16 count, then that many strings
--- frames ---------------------------------------------------------------------
tickCount * peerCount input samples, tick-major, peers ascending
--- hashes ---------------------------------------------------------------------
tickCount * i64  WorldHasher.hash(snapshot) at the END of each recorded tick
--- edits (format 2 only) ------------------------------------------------------
editCount      i32   calls made between ticks, in the order they were applied
--- trailer --------------------------------------------------------------------
crc32          i32 over every byte from MAGIC to the end of the last section
```

(Abridged from the KDoc in that file, which also gives each edit's fields.) Everything is little-endian.

Things worth knowing:

- **Only inputs are recorded.** In `moba` that is one peer, the human champion. The 27 AI units are not recorded: they draw from the seeded random streams, so the seed reproduces them. If the AI needed recording, the seed would not be doing its job.
- **Identity is checked before replaying.** The seed, the protocol hash, the asset graph hash and the input schema hash are in the header. A build that cannot reproduce the recording refuses it and names the field that differs.
- **Format 2 adds the editor's edits.** Edits made with `editor.*` tools during a recording are stored with it, so a replay reproduces them too. A recording with no edits is still written as format 1 (`EDITLESS_FORMAT_VERSION`), and this build reads both.
- **Corruption is caught.** Every section carries its own length, and the file ends with a CRC32. A truncated file is a named refusal.

`moba`'s side of this is `MobaReplay` in `moba/desktop/src/main/kotlin/dev/wildware/moba/replay/MobaReplay.kt`. It copies between the game's `Intent` and `udea-replay`'s `InputSample`, because `udea-replay` is a headless module and may not name `udea-render`'s input types. A replay drives the game through `IntentSource`, the same seam a keyboard and the agent's `input.*` tools use, so the simulation cannot tell them apart.

## Verifying a replay

`ReplayVerifier` replays a recording headless and compares every tick's `WorldHasher` hash with the recorded one. The result is the evidence, not only a verdict: the number of ticks that actually ran, and on failure the first differing tick and the fields that differ. A verification that compared zero ticks would otherwise look like one that compared two thousand.

`WorldHasher` hashes a *captured* world in ascending `NetId` order, never the live Fleks world, so the hash does not depend on the order entities were spawned in.

## Replay equality across machines

A replay agreeing with itself on one machine is not enough; floats can differ between operating systems and JVMs. The **`replay-equality`** CI job is the real determinism gate:

1. Each leg replays a checked-in recording headless and writes a `.udeaeq` digest: per tick, every value `WorldHasher` folds, keyed by `NetId`, component type and field index.
2. Legs run on Linux (`ubuntu-latest`) and Windows (`windows-latest`).
3. A join step compares the digests. On a difference it reports the first tick, the entity, the component and the field, with the values on each side and their history.

The recordings are `moba/desktop/src/test/resources/fixtures/moba-3600.udearep` (one minute of a real match, on every push) and `moba-36000.udearep` (ten minutes, in the nightly `replay-equality-nightly` job). The Gradle tasks behind the job:

| Task | What it does |
|---|---|
| `sh gradlew :moba:desktop:udeaReplayDigest` | Replays a checked-in `moba` recording and writes this machine's `.udeaeq` digest |
| `sh gradlew :moba:desktop:udeaReplayEqualityProof` | Proves the gate both ways: two honest legs agree, and a leg with a planted one-ulp difference fails, naming the tick, the entity, the component and the field |
| `sh gradlew :moba:desktop:udeaWriteReplayFixture` | Rebuilds the checked-in recordings, after a deliberate change to the simulation |

The recordings go stale whenever the protocol hash, the asset graph hash or the input schema hash moves, which ordinary gameplay work does. `MobaReplayFixturesCurrentTest` catches that in `:moba:desktop:test` on the machine that made the change, and prints the command that fixes it, instead of leaving it to three red CI legs on an unrelated pull request.

## Bisecting a divergence: the `replay.*` tools

There is no single "bisect" command. A bisect is a loop a reader drives, and every step is a decision. `ReplayToolset` (`udea-replay/src/jvmMain/kotlin/dev/wildware/udea/replay/tools/ReplayToolset.kt`) gives the loop, in the order an agent runs it:

1. `replay.load` opens a `.udearep`, and refuses it now if this build cannot reproduce it, naming the identity field that differs.
2. `replay.verify` replays the whole thing against the recorded hashes. Either it is bit-exact, or the answer is the first tick and the fields that differ.
3. `replay.seek` lands exactly on the tick before the divergence.
4. `replay.step 1` walks into it, reading `world.*` between steps.
5. `replay.rewind 1` goes back to try again.

`replay.info` describes the loaded recording.

These tools drive a **second** simulation, built from the recording, not the game the host is running. So the live `world.*` tools do not see the replay's world, and every answer that matters is in the replay tool's own result. A long `replay.seek` runs inside the host's tick, so a watched window pauses while it runs, as it does for `time.fast_forward`.

The job's summary, green or red, includes a reproduction guide rendered by `ReplayBisectGuide`, and `ReplayBisectGuideTest` checks every tool name in it against the generated `replay.*` toolset, so the guide cannot name a tool that does not exist.

## See also

- [Tick Model and Determinism](Tick-Model-and-Determinism)
- [Replication and Networking](Replication-and-Networking)
- [Agent Tool Surface](Agent-Tool-Surface)
- [Levels](Levels)
- [Build and Verification](Build-and-Verification)
