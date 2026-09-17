f2546fd

# #209: udea-net on Kotlin Multiplatform, UDP on Ktor sockets, and a WebSocket transport

Branch `issue-209-net-kmp`, with `origin/kmp` merged in at 0a760f1 (#205). The branch has no `-x` exclusions and no changes under `docs/contracts/` or in `udea-codegen`. `git diff --stat origin/kmp HEAD -- udea-codegen docs/contracts docs/contracts.lock` prints nothing.

## Evidence command

```
sh gradlew :udea-net:wasmJsNodeTest :udea-net:jvmTest
```

The Wasm test runs on Node. The build first starts a JVM process running `WebSocketSnapshotServer`, which is `jvmTest` code. The Wasm client connects to it and must apply at least 30 snapshots whose entity positions match what the server wrote.

On f2546fd it passes. This is the Wasm test's own output, from `udea-net/build/test-results/wasmJsNodeTest/*.xml`:

```
  <system-out><![CDATA[client1 applied 30 snapshot(s) from ws://127.0.0.1:32835/udea, up to server tick t52
```

**It goes red when the feature is reverted.** The mutation is `EVIDENCE-client-drops-payload`: the client no longer hands received payloads to the game. I ran the exact command above on that mutation, and both tasks failed.

```diff
diff --git a/udea-net/src/commonMain/kotlin/dev/wildware/udea/net/transport/WebSocketTransport.kt b/udea-net/src/commonMain/kotlin/dev/wildware/udea/net/transport/WebSocketTransport.kt
index d4c3581..bdc5a25 100644
--- a/udea-net/src/commonMain/kotlin/dev/wildware/udea/net/transport/WebSocketTransport.kt
+++ b/udea-net/src/commonMain/kotlin/dev/wildware/udea/net/transport/WebSocketTransport.kt
@@ -149,7 +149,6 @@ public class WebSocketTransport private constructor(
             WebSocketMessageType.Payload -> if (connected) {
                 val length = message.size - WebSocketLayout.PAYLOAD_BODY
                 serverStats.recordReceived(length)
-                sink.receive(PeerId.SERVER, message, WebSocketLayout.PAYLOAD_BODY, length)
                 return 1
             }
             WebSocketMessageType.Accepted -> if (!connected && failure == null && message.size >= WebSocketLayout.ACCEPTED_BYTES) {
```

```
dev.wildware.udea.net.transport.WebSocketWasmClientTest.aWasmClientReplicatesSnapshotsFromAJvmServer[wasmJs, node] FAILED
    kotlin.AssertionError at file:///srv/ssd1/workspace/Udea/.claude/worktrees/agent-a4a820538f153b24b/build/wasm/packages/udea-udea-net-test/kotlin/udea-udea-net-test.import-object.mjs:142
> Task :udea-net:wasmJsNodeTest FAILED
WebSocketTransportTest[jvm] > payload bytes arrive whole and in order in both directions()[jvm] FAILED
WebSocketTransportTest[jvm] > snapshots stream from the server to a client and its acknowledgements flow back()[jvm] FAILED
> Task :udea-net:jvmTest FAILED
BUILD FAILED in 1m 1s
```

I also checked that the Wasm test really exercises the connection. Earlier in the ticket I made two other changes. With `UDEA_WS_SNAPSHOT_URL` renamed, the test failed with "not set". With client payload delivery disabled (`if (connected && false)`), it failed after 29.158s with "only 0 snapshot(s) applied". Both were reverted. I did not keep those logs, so this paragraph is a description, not a transcript.

## Summary

**The targets.** `udea-net` now uses `udea.kotlin-multiplatform-no-ios`: `jvm`, `android` and `wasmJs`. The iOS targets stay off because `udea-core` has none (#215). The build script has a one-line switch comment that names #215. A custom hierarchy group, `socket` (`jvm` + `androidJvm`), provides `socketMain`. The UDP transport and its handshake live there: `UdpTransport`, `UdpConnection`, `ConnectionSecret` and `HandshakeRateLimiter`. Wasm gets the WebSocket client only, with no expect/actual stub for UDP.

**The UDP port.**
- `java.nio.DatagramChannel` is replaced by Ktor `ktor-network` datagram sockets.
- `ByteBuffer` views are replaced by `BigEndian` over `ByteArray`. The wire layout and byte order are unchanged; mutation M5 shows eleven UDP tests catch a byte-order slip.
- `javax.crypto.Mac` is replaced by cryptography-kotlin's HMAC-SHA256 through `CryptographyProvider.JDK`.
- Sends stay synchronous: `outgoing.trySend` calls `DatagramChannel.send` on the calling thread. I checked the 3.6.0 bytecode for `trySendImpl`.
- Receive is now a reader coroutine that feeds a queue, and `poll` drains that queue. This changes timing, and it has a cost; see "What changed about `runUdpProof`" below.
- The UDP tests now call `settle(...)`, which waits until the datagrams the other end sent have reached the queue before each step. It fails loudly on a timeout. Only the 200,000-datagram test tolerates loss (`settleWithLoss`).

**The WebSocket transport.**
- `WebSocketTransport` is the client, in `commonMain`. It uses Ktor's CIO engine on socket targets and the Js engine on Wasm (npm `ws` on Node).
- `WebSocketServerTransport` is the server, on `jvm` only, using Ktor server CIO.
- Both implement the existing `Transport` SPI: a non-blocking `poll`, a `DatagramSink`, and `UdpConnectionListener` events.
- The handshake is a Hello carrying `protoHash`, answered by Accepted (with a peer id) or Denied (with a `DisconnectReason`).
- `DisconnectReason.Unreachable(7)` is new, for a client that never reached a server.
- There are no durations: TCP handles timeouts and retransmission.

**Tests.** The Wasm test needs a real JVM server process. A Gradle `BuildService` in `udea-net/build.gradle.kts` starts `WebSocketSnapshotServer` from the `jvmTest` classpath, waits for it to write its URL to a file, and passes that URL to `wasmJsNodeTest` through the environment. The service closes the process when the build ends. The design is configuration-cache safe: the service parameters are only files and a lifetime, and the classpath is passed in at `doFirst`.

**Decisions**, each commented on #209 with the alternative and how to reverse it:
- targets and `socketMain`
- the two-process mechanism for the Wasm test
- excluding `kotlin-reflect` from the Ktor server artifacts. `ktor-server-core` pulls it in, UDEA-MG-005 bans it from `moba`, and M4 shows both gates catch it.
- versions: Ktor 3.6.0, kotlinx-coroutines 1.11.0, cryptography-kotlin 0.6.0, the JDK provider by name
- async UDP receive, and settling in the UDP tests
- how I read AC3
- committing `kotlin-js-store/wasm/yarn.lock`
- keeping the Ktor receive in spite of the `runUdpProof` numbers

**Found on the way:**
1. **Two WebSocket tests never ran.** A test whose body is an expression ending in a non-Unit value compiles to a non-void method, and JUnit skips it without a word. The unreachable-server test and the over-limit test both did that. With the new malformed-hello test added, the class declared 11 tests and the JVM report showed 9. Every expression-bodied test in the class is now `runBlocking<Unit>`, and the report shows 11. I searched every `@Test` in `udea-*` and `moba` for an expression body that is not `runBlocking<Unit>` or `runTest`, and found none.
2. **Mutation M6 survived at first.** Dropping the host from the connect-token MAC left every test green, because the "other address" in `the connect token is bound to address, salt and expiry` differed only in port. That test now also tries `127.0.0.2`, and M6 turns it red.
3. **Public API trimmed.** `WebSocketCounters`, `failureCause`, `port` and `connections()` are `internal`. Only this module's tests read them. The public surface is what a game client or server needs: `client`, `start`, `url`, `isConnected`, `localPeer`, `failure`, and the `Transport` methods. Nothing outside `udea-net` uses the WebSocket classes yet; the web client is #212's to wire.

**Merged, not mine:** `origin/kmp`'s #205 pins `kotlinx-io` to 0.9.1. Ktor 3.6.0 asks for 0.9.0, and resolution raises it (`sh gradlew :moba:dependencyInsight --configuration runtimeClasspath --dependency kotlinx-io-core` prints `org.jetbrains.kotlinx:kotlinx-io-core:0.9.0 -> 0.9.1`). The full build and all transport tests pass on 0.9.1.

**Left alone:** `udea-replay/src/main/kotlin/dev/wildware/udea/replay/fixture/ReplayFixtures.kt:106` still says `./gradlew :udea-net:test -Dupdate.goldens=true`. That task is now `:udea-net:jvmTest`. The file belongs to #206, so I did not edit it.

### What changed about `runUdpProof` (surprise, filed as #219)

`runUdpProof` is not part of `check`. I ran it many times on both trees, and the result files are kept in `scratchpad/logs/*-udp-*-results/`. The table below is the output of `tally.py` over them:

```
baseline ca2d5f5, unmodified: 26 runs {'pass': 25, 'fail: Attributes,CharacterView,Combatant,GameUnit,Inventory,LaneCreep,LaneState,MatchState,Player,Position,Projectile,Respawn,Tower,Wallet': 1}
baseline ca2d5f5, client boot delayed (100ms / 60ms): 28 runs {'pass': 28}
baseline ca2d5f5, poll stops early 1 time in 5: 28 runs {'pass': 26, 'fail: Attributes,CharacterView,Combatant,GameUnit,Inventory,LaneCreep,LaneState,MatchState,Player,Position,Projectile,Respawn,Tower,Wallet': 1, 'fail: Position': 1}
branch: 32 runs {'pass': 26, 'fail: Position': 6}
```

- The **perfect-link** case passed in every run on both trees. Every failing test case was the lossy one.
- On this branch the lossy case fails in 6 of 32 runs, always as a disagreement on `Position` only.
- The same Position-only failure appears on `origin/kmp`'s own java.nio transport after one change: `poll` sometimes stops before taking every datagram that is waiting. The diff is in #219.
- So the defect is in replication and predates this branch. Delivering datagrams through a coroutine splits a burst across two polls more often, which exposes it more often.
- I rejected two fixes. Using java.nio in `socketMain` goes against this issue's title and D11. Making `poll` wait for the reader puts a wall-clock wait in the game loop.
- The #209 comment says what to do instead if the owner wants the old pass rate before merge.
- Earlier guidance called this proof "red today, 5/5 under loss". That no longer describes `origin/kmp`, which passed 25 of 26 unmodified runs.

## `sh gradlew build`

Baseline, `origin/kmp` at ca2d5f5, `build --continue` in a detached worktree (`scratchpad/logs/baseline-build.log`). No task failed (`grep -c FAILED` gives 0):

```
BUILD SUCCESSFUL in 1m 24s
406 actionable tasks: 272 executed, 134 from cache
Configuration cache entry stored.
```

On this branch after the merge, at dfb8280, `build --continue` (`scratchpad/logs/branch-build-2.log`):

```
BUILD SUCCESSFUL in 2m 25s
499 actionable tasks: 431 executed, 3 from cache, 65 up-to-date
Configuration cache entry stored.
```

At f2546fd, which is dfb8280 plus a change to one test file, again `build --continue` (`scratchpad/logs/branch-build-f2546fd.log`):

```
> Task :udea-net:wasmJsNodeTest
> Task :udea-net:jvmTest
BUILD SUCCESSFUL in 19s
490 actionable tasks: 16 executed, 4 from cache, 470 up-to-date
Configuration cache entry reused.
```

At that point the `udea-net` test reports show `jvmTest 266 tests 0 skipped 0 failures 0 errors` and `wasmJsNodeTest 1 tests 0 skipped 0 failures 0 errors`. `testAndroidHostTest` reports 0 tests: `udea-net` has no Android host tests.

`udeaDaemonBudget` did not fail in any of these builds.

Gates outside `check` (`scratchpad/logs/verifiers.log`): `sh gradlew udeaVerifyModuleGraph udeaVerifyNoLegacyDependencies udeaVerifyAgentsMd udeaVerifyDeterminism :udea-net:compileKotlinJvm :udea-net:compileAndroidMain :udea-net:compileKotlinWasmJs` ends `BUILD SUCCESSFUL in 5s`.

**GL:** this ticket touches no GL, `udea-render` or render half of `udea-agent-host`, so there was no xvfb run.

## Images

None. Nothing in this ticket draws anything. The owner ruled that the old LibGDX shot tasks are not evidence for KMP work. The dashboard got text posts instead.

## The issue, criterion by criterion

**AC1: `udea-net` builds for all four targets, with no `java.net` or `javax.crypto` in commonMain.**
- `jvm`, `android` and `wasmJs` compile: see the gate run above and the full build.
- The fourth target is iOS. It is off by the lead's decision until #215, so `sh gradlew :udea-net:compileKotlinIosArm64` answers `Cannot locate tasks that match ':udea-net:compileKotlinIosArm64' as task 'compileKotlinIosArm64' not found in project ':udea-net'.` (`scratchpad/logs/no-ios-task.log`). iOS cannot build on this Linux box, and I make no claim that it was tested.
- Before the port, `compileKotlinWasmJs` on commonMain failed with 134 `e:` lines, starting `NetRegistry.kt:48:73 Unresolved reference 'java'` and `Q.kt:134:21 Unresolved reference 'Math'` (`scratchpad/logs/red-compile-wasm.log`).
- The Wasm compile is itself the fence: any JVM-only reference in commonMain fails it. `grep -rnE "java\.(net|nio|io)\.|javax\.crypto|System\.arraycopy|Math\." udea-net/src/commonMain udea-net/src/wasmJsMain` finds only three KDoc and comment lines (in `BigEndian.kt`, `ManualClock.kt` and `Q.kt`), no code.

**AC2: a JVM server and a Wasm client (Node) exchange snapshots over WebSocket in a test.** `WebSocketWasmClientTest` runs against a separate JVM process. See the evidence command above, including the red run. The same exchange with both ends on the JVM is `WebSocketTransportTest > snapshots stream from the server to a client and its acknowledgements flow back`.

**AC3: `runNetProof`'s three hashes still agree on JVM over UDP.** `sh gradlew :moba:runNetProof --rerun` at dfb8280 (`scratchpad/logs/runNetProof-dfb8280.log`):

```
[moba.netproof] perfect        units AGREED
[moba.netproof] 150ms+5% loss  units AGREED
[moba.netproof] TRELLO_8       units AGREED
BUILD SUCCESSFUL in 5s
```

- Every `hash` and `AGREED` line matches `origin/kmp` ca2d5f5's run exactly. Filtered to those lines, the only diff is `BUILD SUCCESSFUL in 8s` on the baseline side.
- The `worldHash ... DIFFER` lines under loss appear identically on both trees. They are not asserted.
- `runNetProof` does not use UDP: it replicates in-process over `SimulatedTransport`. So I also ran `:moba:runUdpProof`, which uses three OS processes and real UDP. Its perfect-link case passed in every run on this branch. The lossy case is in the section above.

## Mutations

Every diff below is the file `scratchpad/logs/mutations/<name>.diff`, written by `mutate.py` while the mutation was applied. `mutate.py` also runs the tasks, lists the failing tests from fresh result files, and reverts the file.

| Mutation | Command | Failing tests |
|---|---|---|
| M1 client drops payloads | `:udea-net:wasmJsNodeTest :udea-net:jvmTest --tests '*WebSocketTransportTest*'` | `WebSocketWasmClientTest > aWasmClientReplicatesSnapshotsFromAJvmServer[wasmJs, node]`, `WebSocketTransportTest > payload bytes arrive whole and in order in both directions()`, `> snapshots stream from the server to a client and its acknowledgements flow back()` |
| M2 server ignores protoHash | `:udea-net:jvmTest --tests '*WebSocketTransportTest*'` | `WebSocketTransportTest > a client built against a different protocol is denied by name()` |
| M3 server keeps a slot after leave | same | `WebSocketTransportTest > a client that leaves frees its slot for the next one()` |
| M4 kotlin-reflect not excluded | `:udea-net:jvmTest --tests '*WebSocketTransportTest*' :moba:udeaVerifyModuleGraph` | `WebSocketTransportTest > the server runs without kotlin-reflect on the classpath()`, plus `:moba:udeaVerifyModuleGraph` FAILED with `UDEA-MG-005 :moba runtimeClasspath -> org.jetbrains.kotlin:kotlin-reflect` |
| M5 short written little-endian | `:udea-net:jvmTest --tests '*Udp*'` | 11 tests: `UdpTwoProcessTest > two processes replicate over real udp, and the server survives both ways of losing one()`, `UdpHostileTest > a peer that never answers the challenge costs the server no state at all()`, and 9 in `UdpTransportTest` (handshake, round trip, fragmentation, 200000 datagrams, keep-alive, timeout, released slot, full server, reassembly limit) |
| M6 connect token ignores host | `:udea-net:jvmTest --tests '*UdpHostileTest*'` | `UdpHostileTest > the connect token is bound to address, salt and expiry()`. It survived before f2546fd; see "Found on the way" |
| M7 malformed hello left open | `:udea-net:jvmTest --tests '*WebSocketTransportTest*'` | `WebSocketTransportTest > a connection that opens with anything but a hello is counted and dropped()` |

```diff
diff --git a/udea-net/src/commonMain/kotlin/dev/wildware/udea/net/transport/WebSocketTransport.kt b/udea-net/src/commonMain/kotlin/dev/wildware/udea/net/transport/WebSocketTransport.kt
index d4c3581..bdc5a25 100644
--- a/udea-net/src/commonMain/kotlin/dev/wildware/udea/net/transport/WebSocketTransport.kt
+++ b/udea-net/src/commonMain/kotlin/dev/wildware/udea/net/transport/WebSocketTransport.kt
@@ -149,7 +149,6 @@ public class WebSocketTransport private constructor(
             WebSocketMessageType.Payload -> if (connected) {
                 val length = message.size - WebSocketLayout.PAYLOAD_BODY
                 serverStats.recordReceived(length)
-                sink.receive(PeerId.SERVER, message, WebSocketLayout.PAYLOAD_BODY, length)
                 return 1
             }
             WebSocketMessageType.Accepted -> if (!connected && failure == null && message.size >= WebSocketLayout.ACCEPTED_BYTES) {
diff --git a/udea-net/src/jvmMain/kotlin/dev/wildware/udea/net/transport/WebSocketServerTransport.kt b/udea-net/src/jvmMain/kotlin/dev/wildware/udea/net/transport/WebSocketServerTransport.kt
index 5115b4d..dd53b0d 100644
--- a/udea-net/src/jvmMain/kotlin/dev/wildware/udea/net/transport/WebSocketServerTransport.kt
+++ b/udea-net/src/jvmMain/kotlin/dev/wildware/udea/net/transport/WebSocketServerTransport.kt
@@ -152,10 +152,6 @@ public class WebSocketServerTransport private constructor(
             link.outbox.close()
             return
         }
-        if (WebSocketLayout.unsignedShort(hello, WebSocketLayout.HELLO_PROTO_HASH) != protoHash) {
-            deny(link, DisconnectReason.ProtocolMismatch)
-            return
-        }
         val slot = (1..config.maxClients).firstOrNull { byPeer[it] == null }
         if (slot == null) {
             deny(link, DisconnectReason.ServerFull)
diff --git a/udea-net/src/jvmMain/kotlin/dev/wildware/udea/net/transport/WebSocketServerTransport.kt b/udea-net/src/jvmMain/kotlin/dev/wildware/udea/net/transport/WebSocketServerTransport.kt
index 5115b4d..c195532 100644
--- a/udea-net/src/jvmMain/kotlin/dev/wildware/udea/net/transport/WebSocketServerTransport.kt
+++ b/udea-net/src/jvmMain/kotlin/dev/wildware/udea/net/transport/WebSocketServerTransport.kt
@@ -192,7 +192,6 @@ public class WebSocketServerTransport private constructor(
         link.outbox.close()
         val peer = link.peer ?: return
         if (byPeer[peer.raw] !== link) return
-        byPeer[peer.raw] = null
         listener.onDisconnected(peer, DisconnectReason.RemoteClosed)
     }
 
diff --git a/udea-net/build.gradle.kts b/udea-net/build.gradle.kts
index 8e5157f..e125971 100644
--- a/udea-net/build.gradle.kts
+++ b/udea-net/build.gradle.kts
@@ -22,7 +22,6 @@ plugins {
  * server test on a classpath without it and asserts that it is absent.
  */
 fun ExternalModuleDependency.withoutKotlinReflect() {
-    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
 }
 
 kotlin {
diff --git a/udea-net/src/commonMain/kotlin/dev/wildware/udea/net/transport/BigEndian.kt b/udea-net/src/commonMain/kotlin/dev/wildware/udea/net/transport/BigEndian.kt
index 7dc62c2..2e459ae 100644
--- a/udea-net/src/commonMain/kotlin/dev/wildware/udea/net/transport/BigEndian.kt
+++ b/udea-net/src/commonMain/kotlin/dev/wildware/udea/net/transport/BigEndian.kt
@@ -11,8 +11,8 @@ package dev.wildware.udea.net.transport
 internal object BigEndian {
 
     fun putShort(bytes: ByteArray, offset: Int, value: Short) {
-        bytes[offset] = (value.toInt() ushr 8).toByte()
-        bytes[offset + 1] = value.toByte()
+        bytes[offset] = value.toByte()
+        bytes[offset + 1] = (value.toInt() ushr 8).toByte()
     }
 
     fun putInt(bytes: ByteArray, offset: Int, value: Int) {
diff --git a/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/ConnectionSecret.kt b/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/ConnectionSecret.kt
index c22770b..b4efed0 100644
--- a/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/ConnectionSecret.kt
+++ b/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/ConnectionSecret.kt
@@ -105,7 +105,7 @@ public class ConnectionSecret(key: ByteArray) {
     private fun encode(address: InetSocketAddress, clientSalt: Long, expiry: Tick): Int {
         var cursor = 0
         val host = address.resolveAddress() ?: address.hostname.encodeToByteArray()
-        val hostBytes = minOf(host.size, MAX_HOST_BYTES)
+        val hostBytes = 0
         message[cursor++] = hostBytes.toByte()
         host.copyInto(message, cursor, 0, hostBytes)
         cursor += hostBytes
diff --git a/udea-net/src/jvmMain/kotlin/dev/wildware/udea/net/transport/WebSocketServerTransport.kt b/udea-net/src/jvmMain/kotlin/dev/wildware/udea/net/transport/WebSocketServerTransport.kt
index 5115b4d..0714926 100644
--- a/udea-net/src/jvmMain/kotlin/dev/wildware/udea/net/transport/WebSocketServerTransport.kt
+++ b/udea-net/src/jvmMain/kotlin/dev/wildware/udea/net/transport/WebSocketServerTransport.kt
@@ -149,7 +149,6 @@ public class WebSocketServerTransport private constructor(
     private fun admit(link: Link, hello: ByteArray) {
         if (WebSocketLayout.typeOf(hello) != WebSocketMessageType.Hello || hello.size < WebSocketLayout.HELLO_BYTES) {
             counters.malformed++
-            link.outbox.close()
             return
         }
         if (WebSocketLayout.unsignedShort(hello, WebSocketLayout.HELLO_PROTO_HASH) != protoHash) {
```

Scratch paths above are relative to `/tmp/claude-1000/-srv-ssd1-workspace-Udea/6f6b1984-74b9-4a3a-add9-13e80864ffe9/`.

## Regenerated files

None. No replicated component was added or removed, so neither `udea-codegen/net-protocol.lock` nor `expected-generated-hashes.txt` changed.

One file is new and generated by the Kotlin/JS tooling: `kotlin-js-store/wasm/yarn.lock`, which pins `ws` 8.20.1. It is committed; the decision is on #209.
