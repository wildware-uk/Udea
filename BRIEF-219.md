b726aa5

# BRIEF - issue #219: runUdpProof lossy, client `Position` disagrees

Branch `issue-219-udp-straddle-position`, two commits on `7073adc` (then `origin/kmp`):
`6f9c2e4` (the fixes and tests) and `b726aa5` (a third test, and tidying). `origin/kmp` has since
moved to `22bc1ba` (#217 and #193 merged); `git merge-tree --write-tree HEAD origin/kmp` reports no
conflicts. The branch has not been rebased.

## 1. The evidence command

```
ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem sh gradlew \
  :udea-net:jvmTest --rerun --tests 'dev.wildware.udea.net.replication.AckWindowConvergenceTest' --tests 'dev.wildware.udea.net.replication.RecycledIndexStallTest' \
  :moba:test --rerun --tests 'dev.wildware.moba.net.MobaStraddledPollTest' --continue
```

`--rerun` matters: without it `:moba:test` came back `FROM-CACHE` once on this box and the report
would have been an old one.

Green at `b726aa5`, from `evidence-fixed-final/gradle.log`:

```
> Task :udea-net:jvmTest
> Task :moba:test
[... lines 253-261 elided ...]
BUILD SUCCESSFUL in 8s
```

(`AckWindowConvergenceTest` 3 tests, `RecycledIndexStallTest` 2, `MobaStraddledPollTest` 1, 0 failures,
from the saved XML reports.)

**Red when each fix is removed.** Each mutation below is the literal `diff -u` of the committed
file against the mutated one (at the end of this brief). These three runs used the same command
without `--rerun`; each changed production code, so both test tasks executed rather than came
from cache - the logs show them `FAILED`.

| Mutation | What fails | Failure message (from the XML report) |
|---|---|---|
| M1: `ClientReplicationState.kt` back to `origin/kmp` (the overflow fix removed) | `AckWindowConvergenceTest` "…round trip outlasts the pending-send list", `MobaStraddledPollTest` | `tick 62 — client1 differs: NetId(#0@0).Vitals.shielded: server=false client=true …` / `seed 9, tick 125: client1 holds server tick 117 and disagrees on the units: [NetId(#0@0) CharacterView.state 3 vs 0]` |
| M2: `ReplicationServer.kt` back to `origin/kmp` (the stall fix removed) | `RecycledIndexStallTest` "a new occupant keeps moving…", `MobaStraddledPollTest` | `tick 28: the client holds NetId(#0@1) at x=21.0 where the server had x=22.0 at tick 22 - the server stopped updating it while it settled the removal of NetId(#0@0)` / `seed 2, tick 194: client1 holds server tick 186 and disagrees on the units: [NetId(#36@4) Position.x -296.94083 vs -297.5797, NetId(#36@4) Position.y 404.2084 vs 403.92236]` |
| M3: the `writeDisplacedRemovals(...)` call commented out | `RecycledIndexStallTest` "a dead generation is destroyed when its new occupant does not fit the datagram" | ``the client still holds NetId(#0@0) 10 ticks after it died: its `Destroy` was held back for NetId(#0@1), which never fitted, and was not written in its place`` |

M2's `MobaStraddledPollTest` failure is the issue's symptom exactly: a recycled creep index (`#36@4`),
`Position` only.

## 2. Summary

### Root cause

Datagrams straddling two polls do not corrupt anything. They make the round trip longer, and a
longer round trip reaches two defects in `udea-net`'s replication server. Neither is in snapshot
application, input application or fragment reassembly.

**Defect 1, the `Position`-only failure: a recycled index froze its new occupant.** Layer:
baseline/ack recovery (`ReplicationServer` removal ordering).

1. A creep is created, and dies before the ack for its create comes back. The server is not
   tracking it for that client, so it writes nothing when it dies.
2. The next creep takes the same `NetId` index. Its `Create` goes out and the client applies it.
3. The late ack for the dead creep arrives. `acknowledge` records that the client held that
   generation, and `writeRemovals` writes a `Destroy` for it.
4. `accumulateAndSelect` skipped every entity on an index with a pending `Destroy`, so the new
   creep stopped being sent. The client held it frozen at its spawn point until the `Destroy`
   was confirmed, which takes a round trip.

A walking creep changes only `Position`, which is why only `Position` differed. It was found with
temporary logging (not committed) added to the server and clients of `runUdpProof`, on the
`origin/kmp` replication code. 4 of 30 runs failed, all `Position`-only. In every one, the differing
unit was a creep on the server's blocked list at the sampled tick, frozen at the creep spawn point.
From `udp219/nofixdiag/run-1.log`:

```
    [udp-proof][lossy] DIAGS client1 overflows=0 blockedAt=[NetId(#31@4), NetId(#32@4), NetId(#33@4), NetId(#41@4), NetId(#43@4)] blockedTicks=145 client2 overflows=0 blockedAt=[NetId(#31@4), NetId(#32@4), NetId(#33@4), NetId(#41@4), NetId(#43@4)] blockedTicks=145
    [udp-proof][lossy] DIAGDIFF MobaUdpClient unit 33g4 client=-283.49008,392.4981 server=-275.0148,397.4187
```

**Defect 2: an overflowed pending-send list forgot sends.** Layer: baseline/ack recovery
(`ClientReplicationState`). The server diffs each entity against every state the client might
hold: the acked baseline plus up to 32 unacknowledged sends. Past 32, the entity went to full
writes and **stopped recording sends**, and *any* later ack cleared the overflow, including an ack
for a packet sent before it. The packer then diffed against the one baseline while the client held
a later full write. A field that changed and changed back was left out, and the client kept the
wrong value. None of the 30 instrumented UDP runs overflowed (`overflows=0` above), so this is
not what `runUdpProof` hit. The in-process straddle harness did overflow, and the defect showed as
`CharacterView` mismatches there (M1's row above).

### The fixes

- **Defect 1 (`ReplicationServer`).** If a dead generation's index already holds a live occupant
  this client may see, its `Destroy` is not written. The new occupant's `Create` goes out instead.
  That is enough on the client: a create for another generation replaces the row at that index
  (`ReplicaStore.createRow`), and a removal names its generation exactly. If the occupant's
  `Create` does not make it into the datagram (budget, or nothing visible), the `Destroy` is written
  after the entities (`writeDisplacedRemovals`). An entity whose *own* removal is pending (a
  `Leave` coming back into view) still waits, as before.
- **Defect 2 (`ClientReplicationState`).** The overflow is now a tick, `untrackedThrough`: the
  newest send that did not fit. The list is emptied and keeps recording later sends. The overflow
  ends only when an ack arrives for a tick at or after `untrackedThrough`.

### Decisions and what was rejected (also on the issue)

- **Fixed both defects, not only the one the issue names.** Defect 2 is the same class ("the
  client holds a state the packer does not diff against"), it is reached by the same thing (a
  long round trip), and it was already red in the in-process reproduction. Leaving it would leave
  the harness red.
- **Rejected for defect 1: ignoring a late ack for a generation the index has moved past.** It
  leaks a corpse. If the new occupant dies before any of its creates arrive, nothing ever destroys
  the old one on the client.
- **Rejected: draining polls fully, or a bigger pending list.** Both only make the long round trip
  rarer. The defects stay.
- **The test transport is not the issue's `taken > 0` break.** A loopback link at a fixed latency
  hands one server datagram to each poll, so a reader that only stops after taking one never leaves
  anything behind. `Straddling` can stop before the first datagram too. It delays and never drops
  or reorders.
- **No frozen contract, wire format or lock file changed.** No new record, op or field on the wire.
  Only what the packer chooses to write changed.

### Not done, stated

- **A generation regression inside one ack message is still possible.** `applyAck` handles the
  newest sequence first, so an old state record for a dead generation can re-track it after its
  successor was tracked. With this change that costs extra full creates and no freeze. It was not
  seen, and it is not fixed here.
- **`MobaStraddledPollTest` depends on its seeds.** Seeds 2 and 9 were picked by running seeds
  1-40 with each fix removed. With the stall fix removed, 30 of the 40 disagreed (2 and 9 among
  them); with the overflow fix removed, only 9 did. A change to the level or the link can move
  which seeds reach either defect. The udea-net tests pin each mechanism without seeds.
- **The `udea-core` snapshot code was not touched.** Neither were `NetIdIndex` or `LevelService`.
- **Documentation claims that are now false were deleted or rewritten:** `NetStateProbe.unitHash`,
  `MobaNetProof.ClientRow.agrees`, `MobaUdpTwoProcessTest`'s class KDoc. They said "a recycled index
  waits one acknowledgement" and "units are not recycled inside a match". Creeps are units, and they
  are recycled. A search for the class (`git grep -i "one ack\|recycled.*wait\|waits.*ack"`) found
  nothing else.

## 3. `sh gradlew build`

Baseline, `7073adc`, before any change (`baseline-219.log`, `build --continue`):

```
BUILD SUCCESSFUL in 1m 42s
620 actionable tasks: 429 executed, 191 from cache
```

At `b726aa5` (`build-b726aa5-219.log`, `build --continue`):

```
BUILD SUCCESSFUL in 1m 8s
611 actionable tasks: 45 executed, 566 up-to-date
```

Tasks this ticket turned green: none (the baseline had no failing task; `runUdpProof` is outside
`check`). Baseline failures, unchanged: none. The task count difference is the 12 `:build-logic:*`
tasks that ran in the baseline log and not in the second (the configuration cache was reused).
Diffing the two logs' `> Task` lines finds no other difference. No GL surface touched, so no xvfb
run.

## 4. `:moba:runUdpProof`, repeated

Each row is `runUdpProof` run repeatedly, one run at a time. A run counts only if Gradle finished
it: on this box another session stopped the Gradle daemon under three runs
(`Gradle build daemon has been stopped: stop command received`). Those are listed as no result
and not counted. Classification comes from each run's `[udp-proof][lossy]` component lines.

| tree | runs with a result | Position-only failure | every component differs | other failure |
|---|---|---|---|---|
| `origin/kmp` replication code (both production files checked out from `7073adc`; this branch's tests and comments) | 30 (32 launched, 2 daemon-stopped) | 6 | 0 | 0 |
| same, plus temporary diagnostic logging | 29 (30 launched, 1 daemon-stopped) | 4 | 0 | 0 |
| fix, `6f9c2e4` | 30 | 0 | 0 | 0 |
| fix, `b726aa5` (final) | 20 | 0 | 0 | 0 |

Spliced from `udp219/baseline-kmp-code/run-5.log`, one of the six. It has the same numbers as the
issue's example:

```
    [udp-proof][lossy] MobaUdpClient {tick=200, units=28, entities=52, applied=166, stale=0, unitHash=7193960222938830810}
    [udp-proof][lossy] server@t200 {tick=200, entities=53, units=28, unitHash=-1227099876265329699}
```
… (Attributes through Player MATCH) …
```
    [udp-proof][lossy]   Position DIFFER
```

Load was not the same across rows. The `origin/kmp` row ran with a load average between 2.38 and
31.25, the `6f9c2e4` row between 2.61 and 24.37, and the final row between 0.96 and 3.16 (from
each directory's `progress.txt`). **"Every component differs" did not appear in any row**, so
nothing here says whether that failure is fixed. In the in-process harness, missing creeps (the
same symptom) went from failing to 0 once both fixes were in, but that is not a `runUdpProof`
result.

## 5. Images

None. The owner ruled this ticket needs no screenshots: `runUdpProof` is headless, and nothing
visible changed.

## 6. The acceptance criteria (as the lead set them)

1. **Root cause named, with its layer, and why a later datagram changes `Position` only.**
   Section 2. Defect 1, baseline/ack recovery in `ReplicationServer`: the straddle lengthens the
   round trip, a late ack re-tracks a dead creep, and its recycled index's new creep stops being
   sent. A walking creep changes only `Position`. Evidence: the diagnostic transcript in section 2,
   and M2's `MobaStraddledPollTest` failure on `#36@4 Position`.
2. **A deterministic test with no sockets and no wall clock that fails on `origin/kmp` and passes
   with the fix.** `MobaStraddledPollTest` (the real battle, a seeded reader that leaves datagrams
   for the next poll, every tick checked), plus `RecycledIndexStallTest` and the new
   `AckWindowConvergenceTest` case for each mechanism. Section 1: green at `b726aa5`, red under
   M1 and M2.
3. **`runUdpProof` at least 20 runs, in the issue's table shape.** Section 4: 30 and 20 runs with
   the fix, 0 failures. `origin/kmp` code: 6 of 30. "Every component differs": 0 in every row, so
   no claim is made about it.
4. **No frozen contract changed.** None. `docs/contracts/`, `docs/contracts.lock`, `net-protocol.lock`
   and `expected-generated-hashes.txt` are untouched (`git diff 7073adc --stat` names only the files
   under section 7's heading below).
5. **No workaround that hides it.** Nothing drains polls or changes timing. The fixes change which
   states the packer diffs against and when a new occupant is written.

## 7. Regenerated files

None. No replicated component was added or removed. `net-protocol.lock` and
`expected-generated-hashes.txt` are unchanged.

Files changed against `7073adc`: `udea-net/.../ReplicationServer.kt`,
`udea-net/.../ClientReplicationState.kt`, `udea-net/.../AckWindowConvergenceTest.kt` (one test
added), `udea-net/.../RecycledIndexStallTest.kt` (new),
`moba/src/test/.../MobaStraddledPollTest.kt` (new), and KDoc only in `moba/.../NetStateProbe.kt`,
`moba/.../MobaNetProof.kt`, `moba/src/test/.../MobaUdpTwoProcessTest.kt`.

## Artefacts

All under `/tmp/claude-1000/-srv-ssd1-workspace-Udea/63d0acb8-bd65-4c8c-8a79-47eb9be28599/scratchpad/`:
`baseline-219.log`, `build-b726aa5-219.log`, `evidence-{fixed-final,M1,M2,M3-displaced}/`
(Gradle log and the three XML reports each), `mutation-M{1-overflow,2-stall,3-displaced}.diff`,
`udp219/<row>/run-N.log` and `.xml` and `progress.txt`, `udp219/summary.py` (the classifier).
`RS.fixed.kt` and `CRS.fixed.kt` are byte-identical to the two committed production files at
`b726aa5` (checked with `cmp`).

## Mutation diffs

### M1: overflow fix removed

<details><summary>mutation-M1-overflow.diff</summary>

```diff
--- /tmp/claude-1000/-srv-ssd1-workspace-Udea/63d0acb8-bd65-4c8c-8a79-47eb9be28599/scratchpad/CRS.fixed.kt	2026-09-17 03:43:17.962139202 +0000
+++ udea-net/src/commonMain/kotlin/dev/wildware/udea/net/replication/ClientReplicationState.kt	2026-09-17 03:43:37.508527421 +0000
@@ -81,28 +81,9 @@
      */
     private var pendingTicks = LongArray(initialIndices * PENDING_PER_INDEX)
 
-    /** Per `NetId.index`: how many of [pendingTicks] are live, `0..`[PENDING_PER_INDEX]. */
+    /** Per `NetId.index`: how many of [pendingTicks] are live, or [PENDING_OVERFLOW]. */
     private var pendingCounts = IntArray(initialIndices)
 
-    /**
-     * Per `NetId.index`: the newest send that did **not** fit in [pendingTicks], or [NO_BASELINE]
-     * while every unacknowledged send is listed there.
-     *
-     * A tick and not a flag, because what ends the overflow is an acknowledgement *at or after
-     * this tick*, and nothing earlier. When the list fills, the sends it held and the one that
-     * did not fit are all at or before this tick; the list is emptied and later sends are listed
-     * again as normal. Until the client acknowledges a packet at or after this tick it may be
-     * holding any of those forgotten states, so the entity is written in full. Once it has, it
-     * holds that packet's state or a newer one - and every newer send is back in the list.
-     *
-     * Issue #219 is what a sentinel count did here. Any acknowledgement cleared it, including one
-     * for a packet that left before the overflow, and the list it cleared had stopped recording.
-     * The packer then diffed against a single baseline while the client held a later full write,
-     * omitted a field that had changed and changed back, and the client kept the wrong value
-     * until the field next moved. A round trip longer than [PENDING_PER_INDEX] ticks was enough.
-     */
-    private var untrackedThrough = LongArray(initialIndices) { NO_BASELINE }
-
     private val records = Array(RECORD_RING) { SentPacket() }
 
     /** The next sequence number this client's packets will carry. */
@@ -146,14 +127,12 @@
      *
      * [PENDING_OVERFLOW] means the tracking ran out of room and the server must stop guessing:
      * write the entity in full. That is the same recovery a baseline that has aged out of the
-     * ring takes, and it ends when the client acknowledges a packet sent at or after the send
-     * that did not fit - see [untrackedThrough] for why no earlier acknowledgement may end it.
+     * ring takes, and it clears itself, because the ack for the full write empties the list.
      */
     public fun pendingSendCount(netId: NetId): Int {
         val index = netId.index
         if (index >= pendingCounts.size) return 0
         if (baselineGenerations[index] != netId.generation) return 0
-        if (untrackedThrough[index] != NO_BASELINE) return PENDING_OVERFLOW
         return pendingCounts[index]
     }
 
@@ -327,7 +306,6 @@
         lastSentTicks[index] = NO_BASELINE
         slotStates[index] = TRACKED
         pendingCounts[index] = 0
-        untrackedThrough[index] = NO_BASELINE
         destroyTicks[index] = NO_BASELINE
     }
 
@@ -389,38 +367,29 @@
         destroyTicks[index] = NO_BASELINE
     }
 
-    /**
-     * Appends [tick] to [index]'s unacknowledged-send list.
-     *
-     * A send that does not fit empties the list and becomes [untrackedThrough], so the list keeps
-     * recording every send after it. It must: those are states the client may be holding when
-     * the overflow ends.
-     */
+    /** Appends [tick] to [index]'s unacknowledged-send list, or marks the list overflowed. */
     private fun pushPending(index: Int, tick: Long) {
         val count = pendingCounts[index]
+        if (count == PENDING_OVERFLOW) return
         val base = index * PENDING_PER_INDEX
         if (count > 0 && pendingTicks[base + count - 1] == tick) return
         if (count == PENDING_PER_INDEX) {
-            untrackedThrough[index] = tick
-            pendingCounts[index] = 0
+            pendingCounts[index] = PENDING_OVERFLOW
             return
         }
         pendingTicks[base + count] = tick
         pendingCounts[index] = count + 1
     }
 
-    /**
-     * Drops every unacknowledged-send tick at or before [tick]: it is the baseline, or older.
-     *
-     * Ends an overflow only when [tick] is at or after [untrackedThrough]: the client then holds
-     * this packet's state or a newer one, and every newer send is in the list.
-     */
+    /** Drops every unacknowledged-send tick at or before [tick]: it is the baseline, or older. */
     private fun prunePending(index: Int, tick: Long) {
-        if (untrackedThrough[index] != NO_BASELINE && tick >= untrackedThrough[index]) {
-            untrackedThrough[index] = NO_BASELINE
-        }
         val count = pendingCounts[index]
-        if (count == 0) return
+        if (count <= 0) {
+            // An overflowed list is restored by the ack for the full write it forced, and only
+            // then: clearing it earlier would resume delta-encoding against an incomplete set.
+            if (count == PENDING_OVERFLOW) pendingCounts[index] = 0
+            return
+        }
         val base = index * PENDING_PER_INDEX
         var kept = 0
         for (position in 0 until count) {
@@ -464,8 +433,6 @@
         destroyTicks = destroyTicks.copyOf(capacity).also { it.fill(NO_BASELINE, destroyTicks.size, capacity) }
         pendingTicks = pendingTicks.copyOf(capacity * PENDING_PER_INDEX)
         pendingCounts = pendingCounts.copyOf(capacity)
-        untrackedThrough = untrackedThrough.copyOf(capacity)
-            .also { it.fill(NO_BASELINE, untrackedThrough.size, capacity) }
     }
 
     /** One in-flight packet: which entities it carried, so an ack can promote their baselines. */
```

</details>

### M2: stall fix removed

<details><summary>mutation-M2-stall.diff</summary>

```diff
--- /tmp/claude-1000/-srv-ssd1-workspace-Udea/63d0acb8-bd65-4c8c-8a79-47eb9be28599/scratchpad/RS.fixed.kt	2026-09-17 03:43:17.960139162 +0000
+++ udea-net/src/commonMain/kotlin/dev/wildware/udea/net/replication/ReplicationServer.kt	2026-09-17 03:43:55.071876308 +0000
@@ -110,24 +110,6 @@
     private val states = LinkedHashMap<Int, ClientReplicationState>()
     private val jitterBuffers = LinkedHashMap<Int, JitterBuffer>()
 
-    /**
-     * Per `NetId.index`, for the datagram being built: the live occupant's generation, valid only
-     * where [occupantStamps] holds [sendStamp]. Stamped rather than cleared, so a send costs one
-     * walk of the rows and no fill.
-     */
-    private var occupantGenerations = IntArray(INITIAL_INDICES)
-    private var occupantStamps = IntArray(INITIAL_INDICES)
-
-    /** Per `NetId.index`: [sendStamp] when this datagram wrote an entity record for that index. */
-    private var writtenStamps = IntArray(INITIAL_INDICES)
-
-    /** Identifies the datagram being built, for the stamp arrays. Never zero once sending. */
-    private var sendStamp = 0
-
-    /** Raw ids of dead generations whose index this datagram hands to a new occupant. */
-    private var displaced = IntArray(INITIAL_INDICES)
-    private var displacedCount = 0
-
     /** Entities that were written in full because their baseline had aged out of the ring. */
     public var baselineRecoveries: Long = 0L
         private set
@@ -139,12 +121,10 @@
     /**
      * `Destroy` records that did not fit this datagram and will be written again next tick.
      *
-     * Removals are written before any entity, so this is non-zero when the destroys *alone* exceed
-     * the budget - a wave dying at once - or when a dead generation's new occupant was deferred
-     * and the `Destroy` written in its place did not fit either (see `writeRemovals`). It is
-     * counted rather than assumed away because the failure it replaces was silent: a truncated
-     * section loses destroys, and a client keeps corpses that the server deleted with nothing
-     * anywhere saying so.
+     * Removals are written before anything else, so this is only ever non-zero when the destroys
+     * *alone* exceed the budget - a wave dying at once. It is counted rather than assumed away
+     * because the failure it replaces was silent: a truncated section loses destroys, and a
+     * client keeps corpses that the server deleted with nothing anywhere saying so.
      */
     public var removalDeferrals: Long = 0L
         private set
@@ -269,11 +249,9 @@
         // is the buffer minus that tail, never the buffer.
         val budgetBytes = minOf(budget.bytesPerPacket, buffer.size - SECTION_TAIL_BYTES)
 
-        stampOccupants(fields)
         writeRemovals(payload, state, fields, seq, current.tick, budgetBytes)
         accumulateAndSelect(state, fields, current.tick)
         packSelected(payload, state, current, fields, seq, budgetBytes)
-        writeDisplacedRemovals(payload, state, seq, current.tick, budgetBytes)
 
         section.end(payload)
         frames.endMessage()
@@ -303,20 +281,6 @@
      * worse for a `Leave` - the entity could never be given back, because the server would think
      * the client had it. That is what makes this loop walk *state* rather than a per-tick event
      * list: the state is still true next tick, so the record is simply written again.
-     *
-     * ## A dead generation whose index already has a new occupant
-     *
-     * Is not written here. One section addresses each index once, and the new occupant's `Create`
-     * removes the dead generation on the client by itself: a create for another generation
-     * replaces the row at that index (`ReplicaStore.createRow`), and a removal names its
-     * generation exactly, so it could never have deleted the new occupant either. The index is left
-     * for [packSelected], and [writeDisplacedRemovals] writes the `Destroy` after all if the new
-     * occupant did not make it into this datagram.
-     *
-     * Issue #219 is what the other order did. A dead occupant whose create was acknowledged late -
-     * after the index had been handed on and the new occupant's create had left - was destroyed
-     * first, and the new occupant held back until the destroy was confirmed. The client already
-     * held the new occupant, and kept it frozen where it was created for a whole round trip.
      */
     private fun writeRemovals(
         out: BitWriter,
@@ -336,126 +300,45 @@
                 !relevancy.isRelevant(state.peer, netId) -> EntityOp.Leave
                 else -> continue
             }
-            if (gone && hasRelevantOccupant(state, index)) {
-                displace(netId)
-                continue
-            }
-            if (!writeRemoval(out, state, netId, op, seq, tick, budgetBytes)) return
-        }
-    }
-
-    /**
-     * Writes the `Destroy` for each [displaced] generation whose new occupant this datagram did not
-     * carry after all - deferred by the budget, or with nothing this client may see. The client may
-     * still hold the dead generation, and nothing else in the datagram removes it.
-     */
-    private fun writeDisplacedRemovals(
-        out: BitWriter,
-        state: ClientReplicationState,
-        seq: Int,
-        tick: Tick,
-        budgetBytes: Int,
-    ) {
-        for (position in 0 until displacedCount) {
-            val netId = NetId.ofRaw(displaced[position])
-            if (writtenStamps[netId.index] == sendStamp) continue
-            if (!writeRemoval(out, state, netId, EntityOp.Destroy, seq, tick, budgetBytes)) return
-        }
-    }
-
-    /**
-     * Writes one removal record for [netId] and marks it pending.
-     *
-     * @return false when the record did not fit. Nothing was written, and the caller writes no
-     *   further removals into this datagram.
-     */
-    private fun writeRemoval(
-        out: BitWriter,
-        state: ClientReplicationState,
-        netId: NetId,
-        op: EntityOp,
-        seq: Int,
-        tick: Tick,
-        budgetBytes: Int,
-    ): Boolean {
-        // Enough units dying in one tick will fill a datagram with destroys alone. The
-        // section must not be truncated mid-record: the same rollback pair the entity packer
-        // uses puts the bytes and the delta chain back, and the destroys that did not fit
-        // are simply not marked pending, so they are written again next tick.
-        val mark = writer.bitPosition
-        val cursor = section.cursor()
-        try {
-            section.writeRemoval(out, netId, op)
-        } catch (overflow: BitBufferOverflow) {
-            writer.truncateTo(mark)
-            section.rewindTo(cursor)
-            removalDeferrals++
-            return false
-        }
-        if (writer.byteLength > budgetBytes) {
-            writer.truncateTo(mark)
-            section.rewindTo(cursor)
-            removalDeferrals++
-            return false
-        }
-        state.markDestroyPending(netId, tick)
-        if (op == EntityOp.Leave) leaveWrites++
-        // Recorded *as a removal*, so the ack that confirms the datagram retires the id and
-        // is never mistaken for the client acknowledging that it holds one — and an unacked
-        // removal is simply written again next tick.
-        state.recordRemovalSent(netId, seq, tick)
-        return true
-    }
 
-    /** Records, for this datagram, which generation occupies each index of [fields]. */
-    private fun stampOccupants(fields: WorldFieldStore) {
-        sendStamp++
-        if (sendStamp == 0) {
-            // Wrapped round: every stale stamp would read as this datagram's. Start over.
-            occupantStamps.fill(0)
-            writtenStamps.fill(0)
-            sendStamp = 1
-        }
-        displacedCount = 0
-        for (row in 0 until fields.rowCount) {
-            val netId = fields.netIdAt(row)
-            ensureIndices(netId.index + 1)
-            occupantGenerations[netId.index] = netId.generation
-            occupantStamps[netId.index] = sendStamp
+            // Enough units dying in one tick will fill a datagram with destroys alone. The
+            // section must not be truncated mid-record: the same rollback pair the entity packer
+            // uses puts the bytes and the delta chain back, and the destroys that did not fit
+            // are simply not marked pending, so they are written again next tick.
+            val mark = writer.bitPosition
+            val cursor = section.cursor()
+            try {
+                section.writeRemoval(out, netId, op)
+            } catch (overflow: BitBufferOverflow) {
+                writer.truncateTo(mark)
+                section.rewindTo(cursor)
+                removalDeferrals++
+                return
+            }
+            if (writer.byteLength > budgetBytes) {
+                writer.truncateTo(mark)
+                section.rewindTo(cursor)
+                removalDeferrals++
+                return
+            }
+            state.markDestroyPending(netId, tick)
+            if (op == EntityOp.Leave) leaveWrites++
+            // Recorded *as a removal*, so the ack that confirms the datagram retires the id and
+            // is never mistaken for the client acknowledging that it holds one — and an unacked
+            // removal is simply written again next tick.
+            state.recordRemovalSent(netId, seq, tick)
         }
     }
 
-    /** Whether [index] holds a live entity that [state]'s client may be told about. */
-    private fun hasRelevantOccupant(state: ClientReplicationState, index: Int): Boolean {
-        if (index >= occupantStamps.size || occupantStamps[index] != sendStamp) return false
-        return relevancy.isRelevant(state.peer, NetId.of(index, occupantGenerations[index]))
-    }
-
-    private fun displace(netId: NetId) {
-        if (displacedCount == displaced.size) displaced = displaced.copyOf(displaced.size * 2)
-        displaced[displacedCount++] = netId.raw
-    }
-
-    private fun ensureIndices(required: Int) {
-        if (required <= occupantStamps.size) return
-        var capacity = occupantStamps.size
-        while (capacity < required) capacity *= 2
-        occupantGenerations = occupantGenerations.copyOf(capacity)
-        occupantStamps = occupantStamps.copyOf(capacity)
-        writtenStamps = writtenStamps.copyOf(capacity)
-    }
-
     private fun accumulateAndSelect(state: ClientReplicationState, fields: WorldFieldStore, tick: Tick) {
         selector.clear()
         for (row in 0 until fields.rowCount) {
             val netId = fields.netIdAt(row)
             if (!relevancy.isRelevant(state.peer, netId)) continue
-            // An entity whose own removal is unacknowledged waits for it: that is a `Leave` for an
-            // entity back in view, which re-enters as a create once the client has confirmed it
-            // left. Another generation at that index does not wait - see `writeRemovals`.
-            val ownRemovalPending = state.isDestroyPending(netId.index) &&
-                state.trackedGeneration(netId.index) == netId.generation
-            if (ownRemovalPending) continue
+            // An index whose Destroy is still unacknowledged cannot also carry its new occupant:
+            // one section addresses each index once, and a client that saw the create before the
+            // destroy would delete the entity it had just been given. It waits one ack.
+            if (state.isDestroyPending(netId.index)) continue
             val priority = accumulator.accumulate(state, netId, tick, relevancy.weightOf(state.peer, netId))
             selector.add(netId, priority)
         }
@@ -522,7 +405,6 @@
                 return
             }
             state.recordSent(netId, seq, current.tick)
-            writtenStamps[netId.index] = sendStamp
         }
     }
 
@@ -578,8 +460,5 @@
          * out of room for the terminator is a thrown `BitBufferOverflow` at the top of `send`.
          */
         public const val SECTION_TAIL_BYTES: Int = 4
-
-        /** Starting size of the per-index scratch arrays. Grown to the highest index sent. */
-        private const val INITIAL_INDICES: Int = 256
     }
 }
```

</details>

### M3: displaced-Destroy fallback removed

<details><summary>mutation-M3-displaced.diff</summary>

```diff
--- /tmp/claude-1000/-srv-ssd1-workspace-Udea/63d0acb8-bd65-4c8c-8a79-47eb9be28599/scratchpad/RS.fixed.kt	2026-09-17 03:43:17.960139162 +0000
+++ udea-net/src/commonMain/kotlin/dev/wildware/udea/net/replication/ReplicationServer.kt	2026-09-17 03:43:17.969139341 +0000
@@ -273,7 +273,7 @@
         writeRemovals(payload, state, fields, seq, current.tick, budgetBytes)
         accumulateAndSelect(state, fields, current.tick)
         packSelected(payload, state, current, fields, seq, budgetBytes)
-        writeDisplacedRemovals(payload, state, seq, current.tick, budgetBytes)
+        // writeDisplacedRemovals(payload, state, seq, current.tick, budgetBytes)
 
         section.end(payload)
         frames.endMessage()
```

</details>
