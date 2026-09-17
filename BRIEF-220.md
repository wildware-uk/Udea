ce3deb7

# Issue #220: UdpTransport reader stops silently when Ktor ends `socket.incoming`

Branch `issue-220-udp-reader-stop`, off `origin/kmp` at `8c733a4`. `origin/kmp` has since gained
`058294b` (`.claude/WAVE.md` only); `git merge-tree --write-tree HEAD origin/kmp` merges clean.

Every output block below is spliced by `brief.py` from files saved under
`/srv/ssd1/workspace/Udea/build/issue220-evidence/`. Nothing in them is typed by hand.

## 1. Evidence command

```
JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem ANDROID_HOME=$HOME/Android/Sdk \
  sh gradlew :udea-net:jvmTest --tests 'dev.wildware.udea.net.transport.UdpReaderStopTest' --rerun
```

`--rerun` matters. Without it, Gradle can hand back a cached result: my first control run on
`ce3deb7` came `FROM-CACHE`, with a report timestamped before the commit.

**Green on `ce3deb7`** (`/srv/ssd1/workspace/Udea/build/issue220-evidence/final/none.xml`):

```
tests=5 failures=0 errors=0 timestamp=2026-09-17T02:21:16.348Z
pass what arrived before the reader stopped is still delivered, and the stop is reported once()[jvm]
pass a server whose socket reader stops reports every connection it can no longer hear()[jvm]
pass a client still handshaking when its reader stops fails the handshake with the reason()[jvm]
pass a receive channel cancelled underneath an open transport is reported too()[jvm]
pass a client whose socket reader stops reports the failure and drops its connection()[jvm]
```

**Red with the feature reverted.** This diff puts the reader loop back exactly as it is on `origin/kmp`
(`/srv/ssd1/workspace/Udea/build/issue220-evidence/final/revert.diff`):

```diff
diff --git a/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt b/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
index ca2fdf9..7fec211 100644
--- a/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
+++ b/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
@@ -185,26 +185,11 @@ public class UdpTransport private constructor(
 
     init {
         io.launch {
-            val cause: Throwable? = try {
-                for (datagram in socket.incoming) {
-                    val bytes = datagram.packet.readByteArray()
-                    inbox.send(Arrival(datagram.address as InetSocketAddress, bytes))
-                    synchronized(arrivalLock) { arrivals++ }
-                }
-                null
-            } catch (cancelled: CancellationException) {
-                // This coroutine's own cancellation is `close`, and rethrows here. Anything else
-                // is `incoming` cancelled underneath a reader that is still meant to be reading.
-                currentCoroutineContext().ensureActive()
-                cancelled
-            } catch (error: Exception) {
-                error
+            for (datagram in socket.incoming) {
+                val bytes = datagram.packet.readByteArray()
+                inbox.send(Arrival(datagram.address as InetSocketAddress, bytes))
+                synchronized(arrivalLock) { arrivals++ }
             }
-            // Not swallowed, whichever way the loop ended: queued behind every datagram that
-            // arrived first, and turned into this transport's `failure` by the `poll` that reaches
-            // it. There is no restarting the read instead - Ktor starts its one receive loop when
-            // the socket is created, and a finished one cannot be run again.
-            inbox.send(ReaderStopped(cause))
         }
     }
 
```

`/srv/ssd1/workspace/Udea/build/issue220-evidence/final/revert.xml`:

```
tests=5 failures=5 errors=0 timestamp=2026-09-17T02:18:52.016Z
RED  what arrived before the reader stopped is still delivered, and the stop is reported once()[jvm]  -- org.opentest4j.AssertionFailedError: the server never noticed
RED  a server whose socket reader stops reports every connection it can no longer hear()[jvm]  -- org.opentest4j.AssertionFailedError: the server never noticed
RED  a client still handshaking when its reader stops fails the handshake with the reason()[jvm]  -- org.opentest4j.AssertionFailedError: the client never noticed
RED  a receive channel cancelled underneath an open transport is reported too()[jvm]  -- org.opentest4j.AssertionFailedError: the client never noticed
RED  a client whose socket reader stops reports the failure and drops its connection()[jvm]  -- org.opentest4j.AssertionFailedError: the client never noticed
```

Reverting the whole of `UdpTransport.kt` to `origin/kmp` instead fails to compile, because the test
reads the new internal `failureCause`. That is red, but not red for the reason the issue describes,
so the evidence above reverts only the behaviour.

**Stable.** Five back-to-back runs with `--rerun` (`/srv/ssd1/workspace/Udea/build/issue220-evidence/final/repeat.txt`):

```
run 1: gradle exit 0  tests="5" skipped="0" failures="0" errors="0" timestamp="2026-09-17T02:21:37.504Z" hostname="wild-home-server" time="0.172"
run 2: gradle exit 0  tests="5" skipped="0" failures="0" errors="0" timestamp="2026-09-17T02:21:41.269Z" hostname="wild-home-server" time="0.173"
run 3: gradle exit 0  tests="5" skipped="0" failures="0" errors="0" timestamp="2026-09-17T02:21:45.005Z" hostname="wild-home-server" time="0.177"
run 4: gradle exit 0  tests="5" skipped="0" failures="0" errors="0" timestamp="2026-09-17T02:21:48.748Z" hostname="wild-home-server" time="0.169"
run 5: gradle exit 0  tests="5" skipped="0" failures="0" errors="0" timestamp="2026-09-17T02:21:52.456Z" hostname="wild-home-server" time="0.185"
```

## 2. Summary

**The defect, confirmed in bytecode.** In `DatagramSocketImpl$receiver$1` (ktor-network-jvm 3.6.0), the
receive loop's exception table catches `ClosedChannelException` and `IOException` and ends the
`produce` normally. `UdpTransport`'s `for (datagram in socket.incoming)` then just finished. It sent no
signal and bumped no counter, and the transport stayed "open".

**The fix, all in `UdpTransport` (socketMain):**
- When the reader ends, it queues a `ReaderStopped(cause)` on the same inbox as the datagrams. That
  puts it behind everything already read.
- The `poll` that reaches it calls `onReaderStopped`, which:
  - counts it in `counters.receiveErrors`, where the old `java.nio` transport counted a receive
    `IOException` (line 241 of `udea-net/src/main/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt`
    at `afe9f64^`);
  - keeps the cause in an internal `failureCause`, as `WebSocketTransport` already does;
  - retires every connection with the new `DisconnectReason.ReceiveFailed(8)`, so the listener hears
    `onDisconnected`;
  - sets `failure`. That covers a client still handshaking (through `failHandshake`, now shared with
    handshake timeout and denial) and a server, which otherwise has no way to report once its clients
    are gone.
- A reader that throws, or whose `incoming` is cancelled by something other than `close()`, is reported
  the same way. The reader's own cancellation (from `close()`) is still rethrown.
- Nothing closes the socket. `close()` stays the caller's.

**Why not leave it to the timeout.** A deaf client does time out after `timeoutTicks`, but as
`Timeout`, which blames the server. A deaf server drops every client that way and then accepts nobody,
with nothing to say why.

**Decisions** (commented on #220: https://github.com/wildware-uk/Udea/issues/220#issuecomment-5707399850):
- *Chosen:* the existing seams (`failure`, the listener, `receiveErrors`) plus one new enum value,
  `ReceiveFailed(8)`. It is local only and never sent in a denial; an older build reads id 8 as `Timeout`.
- *Rejected: restart the read.* Ktor starts its one receive loop inside the socket constructor, so a
  finished loop cannot be run again.
- *Rejected: throw from `poll`.* That is new behaviour on the shared `Transport` interface, and
  `WebSocketTransport` reports the same kind of ending through `failure` and the listener.
- *Rejected: reuse `Timeout` or `Unreachable`.* Both give the wrong reason.

**How the test reaches the defect, and its one piece of reflection.** `breakRead` calls
`selector.notifyClosed(socket)`, which is how Ktor's own `close()` wakes a suspended read. It fails
the read with a `ClosedChannelException`, and Ktor ends `incoming` quietly: the defect's exact path,
with the socket left open. `breakRead` then waits for `incoming.isClosedForReceive` and fails loudly if
it never comes, so no test can pass without reaching the defect. That wait earned its place: my first
approach (closing the operating-system channel underneath Ktor) woke nothing, and the check turned
that into "Ktor did not end incoming" rather than a false pass (`/srv/ssd1/workspace/Udea/build/issue220-evidence/red-2.log` is the later,
correct red run). The socket and selector are private, so the test reads them by reflection. That is
test-only, never on a per-tick path, and a renamed field fails loudly.

**Mutations:** each one in section 7 turns at least one test red. What no mutation turns red is
listed here:
- `currentCoroutineContext().ensureActive()` in the cancellation catch. Removing it is not observable:
  after `close()`, `inbox.send` in the cancelled coroutine throws anyway, and `poll` returns early on a
  closed transport. It stays because rethrowing our own cancellation is the correct shape.
- An earlier test, "closing the transport is not reported as a reader failure", passed on `origin/kmp`
  and under every mutation I ran while it existed, for those same two reasons. It was a test that could not fail, so I
  removed it (commit `b8372c3`).
- The "is reported once" and "delivered first" parts of the ordering test sit in a test that goes red
  under m2, m4, m5 and m7. No mutation targets those two parts specifically, because the code has no
  path that re-queues the stop or reorders the queue.

**Not exercised:**
- The Android target: `socketMain` compiled and `:udea-net:testAndroid` ran in the build, but
  `UdpReaderStopTest` is in `jvmTest` only.
- iOS: nothing ran on this box. `udea-net` has no iOS target (#215).
- wasmJs: has no `UdpTransport`.

## 3. Build

`JAVA_HOME=$HOME/.sdkman/candidates/java/21.0.11-tem ANDROID_HOME=$HOME/Android/Sdk sh gradlew build --continue`
on `ce3deb7`, run after sustained quiet (no other Gradle client for 30s) (`/srv/ssd1/workspace/Udea/build/issue220-evidence/build.log`, last lines):

```
BUILD SUCCESSFUL in 2m 22s
539 actionable tasks: 370 executed, 130 from cache, 39 up-to-date
Configuration cache entry stored.
exit=0
```

(`exit=0` is appended by my wrapper.) No task failed: `grep -c FAILED build.log` gives `0`.
`udea-net`'s test reports from that build (`/srv/ssd1/workspace/Udea/build/issue220-evidence/build-udea-net-counts.txt`):

```
jvmTest: suites=43 tests=271 failures=0 errors=0 skipped=0 readerStop=['TEST-dev.wildware.udea.net.transport.UdpReaderStopTest.xml']
wasmJsNodeTest: suites=1 tests=1 failures=0 errors=0 skipped=0 readerStop=[]
```

- **Tasks this ticket turned green:** none named. The ticket adds a test class; it does not target a
  red task.
- **Baseline failures, unchanged:** the lead's baseline (root `build --continue` on `origin/kmp` at
  `dc6c708`, green) had none, and this branch has none. I did not rerun the baseline myself: the box
  was busy, and `8c733a4` differs from `dc6c708` only in `.claude/WAVE.md`.

`sh gradlew -p build-logic check --continue` (`/srv/ssd1/workspace/Udea/build/issue220-evidence/build-logic.log`): the only failure is the known
baseline one (#216):

```
OuterBuildInputsTest > every repository file a build-logic test names is a declared input of this task() FAILED
311 tests completed, 1 failed
> Task :test FAILED
BUILD FAILED in 1m 37s
```

**GL:** not touched by this ticket (no `udea-render`, no `udea-agent-host`), so no xvfb run. In the
build above, `udeaGlTest` was `FROM-CACHE` and `udeaAgentGlTest` ran with `requireGl` unset, which is the mode that skips without a display. That says
nothing about GL, and I do not claim it does.

**`:moba:runUdpProof`**, run once (`/srv/ssd1/workspace/Udea/build/issue220-evidence/udpproof.log`, `udpproof.xml`). It passed on this run:

```
    [udp-proof][lossy] MobaUdpClient whole-roster hash MATCH
    [udp-proof][lossy] MobaUdpClient whole-roster hash MATCH
    [udp-proof][perfect] MobaUdpClient whole-roster hash MATCH
    [udp-proof][perfect] MobaUdpClient whole-roster hash MATCH
BUILD SUCCESSFUL in 14s
```

This is one run of a proof known to be flaky under loss (#219). It is not a claim that #219 is fixed,
and this ticket does not touch the poll or queue draining #219 is about.

`udeaDaemonBudget`: not part of this `build` (no such task line in `build.log`), and not run by name,
because this ticket does not touch `udea-assets-compiler`.

## 4. Images

None. Nothing here is visible: the change is a transport failure path, and the evidence is the test
report and transcripts above.

## 5. Acceptance criteria

| Criterion | Proof |
|---|---|
| A test that makes the receive channel end with the transport still open observes a reported failure (not silence) | `UdpReaderStopTest`: Ktor ends `incoming` through a real `IOException` on client, server and handshaking client, plus a cancelled `incoming`. Each asserts `failure == ReceiveFailed` and the listener's `onDisconnected`; the client, server and ordering tests also assert `receiveErrors == 1`. Green on `ce3deb7`, 5/5 red with the reader reverted (section 1) |
| `sh gradlew build --continue` shows no new failure | Section 3: `BUILD SUCCESSFUL`, no failed task. `build-logic check` has only the baseline `OuterBuildInputsTest` |

## 6. Regenerated files

None. No replicated component was added or removed; `net-protocol.lock` and
`expected-generated-hashes.txt` are untouched.

## 7. Mutation table

Each row is run against `ce3deb7` by `/srv/ssd1/workspace/Udea/build/issue220-evidence/rerun.py`, which uses `mutate.py` to apply the mutation,
saves `git diff HEAD`, runs the test class, keeps the JUnit XML, and restores the file. It asserts a
clean tree after every row. `rerun.py` does not pass `--rerun`. That is safe for the red rows, whose
reports carry fresh timestamps, because Gradle does not cache a failed test task. The green control
is the one that came from cache, and it was rerun by hand with `--rerun` (section 1).

#### m2: The reader ends without queuing the stop

```diff
diff --git a/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt b/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
index ca2fdf9..63772be 100644
--- a/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
+++ b/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
@@ -204,7 +204,6 @@ public class UdpTransport private constructor(
             // arrived first, and turned into this transport's `failure` by the `poll` that reaches
             // it. There is no restarting the read instead - Ktor starts its one receive loop when
             // the socket is created, and a finished one cannot be run again.
-            inbox.send(ReaderStopped(cause))
         }
     }
 
```

`/srv/ssd1/workspace/Udea/build/issue220-evidence/final/m2.xml`:

```
tests=5 failures=5 errors=0 timestamp=2026-09-17T02:19:22.449Z
RED  what arrived before the reader stopped is still delivered, and the stop is reported once()[jvm]  -- org.opentest4j.AssertionFailedError: the server never noticed
RED  a server whose socket reader stops reports every connection it can no longer hear()[jvm]  -- org.opentest4j.AssertionFailedError: the server never noticed
RED  a client still handshaking when its reader stops fails the handshake with the reason()[jvm]  -- org.opentest4j.AssertionFailedError: the client never noticed
RED  a receive channel cancelled underneath an open transport is reported too()[jvm]  -- org.opentest4j.AssertionFailedError: the client never noticed
RED  a client whose socket reader stops reports the failure and drops its connection()[jvm]  -- org.opentest4j.AssertionFailedError: the client never noticed
```

#### m3: A cancelled `incoming` is rethrown, the shape `WebSocketTransport`'s reader uses

```diff
diff --git a/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt b/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
index ca2fdf9..e12c4a8 100644
--- a/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
+++ b/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
@@ -195,8 +195,7 @@ public class UdpTransport private constructor(
             } catch (cancelled: CancellationException) {
                 // This coroutine's own cancellation is `close`, and rethrows here. Anything else
                 // is `incoming` cancelled underneath a reader that is still meant to be reading.
-                currentCoroutineContext().ensureActive()
-                cancelled
+                throw cancelled
             } catch (error: Exception) {
                 error
             }
```

`/srv/ssd1/workspace/Udea/build/issue220-evidence/final/m3.xml`:

```
tests=5 failures=1 errors=0 timestamp=2026-09-17T02:19:52.854Z
pass what arrived before the reader stopped is still delivered, and the stop is reported once()[jvm]
pass a server whose socket reader stops reports every connection it can no longer hear()[jvm]
pass a client still handshaking when its reader stops fails the handshake with the reason()[jvm]
RED  a receive channel cancelled underneath an open transport is reported too()[jvm]  -- org.opentest4j.AssertionFailedError: the client never noticed
pass a client whose socket reader stops reports the failure and drops its connection()[jvm]
```

#### m4: Connections are not retired

```diff
diff --git a/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt b/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
index ca2fdf9..f322356 100644
--- a/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
+++ b/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
@@ -357,7 +357,6 @@ public class UdpTransport private constructor(
     private fun onReaderStopped(cause: Throwable?) {
         counters.receiveErrors++
         failureCause = cause
-        for (connection in byPeer) if (connection != null) retire(connection, DisconnectReason.ReceiveFailed)
         if (isServer) {
             failure = DisconnectReason.ReceiveFailed
         } else if (handshakeState != HandshakeState.Failed) {
```

`/srv/ssd1/workspace/Udea/build/issue220-evidence/final/m4.xml`:

```
tests=5 failures=3 errors=0 timestamp=2026-09-17T02:20:03.222Z
RED  what arrived before the reader stopped is still delivered, and the stop is reported once()[jvm]  -- org.opentest4j.AssertionFailedError: expected: <[(client1, ReceiveFailed)]> but was: <[]>
RED  a server whose socket reader stops reports every connection it can no longer hear()[jvm]  -- org.opentest4j.AssertionFailedError: expected: <[(client1, ReceiveFailed)]> but was: <[]>
pass a client still handshaking when its reader stops fails the handshake with the reason()[jvm]
pass a receive channel cancelled underneath an open transport is reported too()[jvm]
RED  a client whose socket reader stops reports the failure and drops its connection()[jvm]  -- org.opentest4j.AssertionFailedError: a connection the client cannot hear is still listed
```

#### m5: A server records no `failure`

```diff
diff --git a/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt b/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
index ca2fdf9..598d2ad 100644
--- a/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
+++ b/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
@@ -359,7 +359,6 @@ public class UdpTransport private constructor(
         failureCause = cause
         for (connection in byPeer) if (connection != null) retire(connection, DisconnectReason.ReceiveFailed)
         if (isServer) {
-            failure = DisconnectReason.ReceiveFailed
         } else if (handshakeState != HandshakeState.Failed) {
             failHandshake(DisconnectReason.ReceiveFailed)
         }
```

`/srv/ssd1/workspace/Udea/build/issue220-evidence/final/m5.xml`:

```
tests=5 failures=2 errors=0 timestamp=2026-09-17T02:20:08.643Z
RED  what arrived before the reader stopped is still delivered, and the stop is reported once()[jvm]  -- org.opentest4j.AssertionFailedError: the server never noticed
RED  a server whose socket reader stops reports every connection it can no longer hear()[jvm]  -- org.opentest4j.AssertionFailedError: the server never noticed
pass a client still handshaking when its reader stops fails the handshake with the reason()[jvm]
pass a receive channel cancelled underneath an open transport is reported too()[jvm]
pass a client whose socket reader stops reports the failure and drops its connection()[jvm]
```

#### m6: A client still handshaking is not failed

```diff
diff --git a/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt b/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
index ca2fdf9..178e084 100644
--- a/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
+++ b/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
@@ -360,8 +360,6 @@ public class UdpTransport private constructor(
         for (connection in byPeer) if (connection != null) retire(connection, DisconnectReason.ReceiveFailed)
         if (isServer) {
             failure = DisconnectReason.ReceiveFailed
-        } else if (handshakeState != HandshakeState.Failed) {
-            failHandshake(DisconnectReason.ReceiveFailed)
         }
     }
 
```

`/srv/ssd1/workspace/Udea/build/issue220-evidence/final/m6.xml`:

```
tests=5 failures=1 errors=0 timestamp=2026-09-17T02:20:24.405Z
pass what arrived before the reader stopped is still delivered, and the stop is reported once()[jvm]
pass a server whose socket reader stops reports every connection it can no longer hear()[jvm]
RED  a client still handshaking when its reader stops fails the handshake with the reason()[jvm]  -- org.opentest4j.AssertionFailedError: the client never noticed
pass a receive channel cancelled underneath an open transport is reported too()[jvm]
pass a client whose socket reader stops reports the failure and drops its connection()[jvm]
```

#### m7: The stop is not counted

```diff
diff --git a/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt b/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
index ca2fdf9..a881c70 100644
--- a/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
+++ b/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
@@ -355,7 +355,6 @@ public class UdpTransport private constructor(
      * with nothing to say why. The socket is not closed here: [close] stays the caller's.
      */
     private fun onReaderStopped(cause: Throwable?) {
-        counters.receiveErrors++
         failureCause = cause
         for (connection in byPeer) if (connection != null) retire(connection, DisconnectReason.ReceiveFailed)
         if (isServer) {
```

`/srv/ssd1/workspace/Udea/build/issue220-evidence/final/m7.xml`:

```
tests=5 failures=3 errors=0 timestamp=2026-09-17T02:20:38.738Z
RED  what arrived before the reader stopped is still delivered, and the stop is reported once()[jvm]  -- org.opentest4j.AssertionFailedError: expected: <1> but was: <0>
RED  a server whose socket reader stops reports every connection it can no longer hear()[jvm]  -- org.opentest4j.AssertionFailedError: expected: <1> but was: <0>
pass a client still handshaking when its reader stops fails the handshake with the reason()[jvm]
pass a receive channel cancelled underneath an open transport is reported too()[jvm]
RED  a client whose socket reader stops reports the failure and drops its connection()[jvm]  -- org.opentest4j.AssertionFailedError: expected: <1> but was: <0>
```

#### m8: The cause is dropped

```diff
diff --git a/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt b/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
index ca2fdf9..c7583ba 100644
--- a/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
+++ b/udea-net/src/socketMain/kotlin/dev/wildware/udea/net/transport/UdpTransport.kt
@@ -356,7 +356,6 @@ public class UdpTransport private constructor(
      */
     private fun onReaderStopped(cause: Throwable?) {
         counters.receiveErrors++
-        failureCause = cause
         for (connection in byPeer) if (connection != null) retire(connection, DisconnectReason.ReceiveFailed)
         if (isServer) {
             failure = DisconnectReason.ReceiveFailed
```

`/srv/ssd1/workspace/Udea/build/issue220-evidence/final/m8.xml`:

```
tests=5 failures=1 errors=0 timestamp=2026-09-17T02:20:45.166Z
pass what arrived before the reader stopped is still delivered, and the stop is reported once()[jvm]
pass a server whose socket reader stops reports every connection it can no longer hear()[jvm]
pass a client still handshaking when its reader stops fails the handshake with the reason()[jvm]
RED  a receive channel cancelled underneath an open transport is reported too()[jvm]  -- org.opentest4j.AssertionFailedError: Expected value to be of type <java.util.concurrent.CancellationException (Kotlin reflection is not available)>, actual <null>.
pass a client whose socket reader stops reports the failure and drops its connection()[jvm]
```
