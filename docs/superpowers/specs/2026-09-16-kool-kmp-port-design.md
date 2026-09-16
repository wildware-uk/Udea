# Udea on Kool, Kotlin Multiplatform

**Date:** 2026-09-16
**Status:** Approved in brainstorming; awaiting the owner's review of this written spec
**Branch:** `kmp`, cut from `master` at `409c044` (`master` was fast-forwarded to `example` the same day)
**Supersedes:** the LibGDX rendering and JVM-only runtime of `2026-08-22-udea-ai-native-rewrite-design.md`.
Everything else in that spec stands: the tick model, `Replicator<T>`, capture-and-diff, `NetId`,
`SimBarrier`, `RngService`, diagnostics, and "the tool surface is the editor".

---

## 1. What changes, in one paragraph

Udea stops being a JVM engine drawn by LibGDX. Its runtime modules become Kotlin Multiplatform,
and rendering moves to [Kool](https://github.com/fabmax/kool). Udea still owns the world state,
the simulation, assets, networking, replay and the agent tool surface; Kool only draws, plays
sound and delivers input. UI stays on ComposeGL, through a new thin `composegl-kool` backend.

## 2. Decisions

| # | Question | Decision | Rejected |
|---|---|---|---|
| D1 | Platforms | Desktop JVM, Web (Wasm), Android, iOS | fewer targets |
| D2 | iOS, which Kool does not support | Every Udea module builds and tests for iOS; the iOS **renderer** waits for Kool | porting Kool to iOS ourselves; dropping iOS |
| D3 | Cross-platform determinism | **Server-only.** The desktop JVM server is authoritative and bit-exact; clients predict and accept corrections; a replay is guaranteed to play back on the JVM | bit-exact on every platform (own math library, owned physics) |
| D4 | Physics | Box2D leaves with LibGDX. `MobaPhysicsModule` was never installed; movement is Udea's own mover | Kool's Box2D in simulation |
| D5 | UI | ComposeGL, via a `composegl-kool` backend in the ComposeGL repository | Kool's own UI |
| D6 | Graphics API | Kool on the GL family now (desktop OpenGL, WebGL 2, GLES 3). Udea calls only Kool's API, never raw GL, so a later Vulkan/WebGPU move touches only ComposeGL | Vulkan/WebGPU now |
| D7 | Discovery (`ServiceLoader`) | A KSP-generated registry. **Frozen contract change, approved by the owner** | per-platform lookup; hand registration |
| D8 | Agent HTTP host | `udea-agent` (tools, dispatcher) is multiplatform; `udea-agent-host` stays desktop JVM. Remote agents on web/mobile builds is a later ticket | a server on every platform now |
| D9 | Migration | **Big bang** on a `kmp` integration branch; red builds allowed mid-port | parallel renderers with a cut-over |
| D10 | Backlog | Close every open issue except the ComposeGL ones (#185, #188, #189). File new issues from this spec | finishing #192/#193 first |
| D11 | Browser transport | WebSocket first, behind `udea-net`'s transport interface. WebRTC/WebTransport is a later ticket | WebRTC or WebTransport now |
| D12 | `moba` layout | Nested projects: `:moba:game`, `:moba:desktop`, `:moba:web`, `:moba:android` | flat `moba-*` modules |

## 3. Modules and targets

| Module | Targets | Change |
|---|---|---|
| `build-logic`, `udea-codegen`, `udea-compiler-plugin`, `udea-assets-compiler`, `udea-gradle` | JVM (build time) | Codegen also emits the registry (section 5) |
| `udea-annotations`, `udea-diagnostics` | all four | Build conversion only |
| `udea-core`, `udea-gas`, `udea-assets`, `udea-replay`, `udea-audio` | all four | Java IO, zip and concurrency replaced (section 6) |
| `udea-net` | all four | UDP on Ktor sockets (JVM, Android, iOS); WebSocket (all clients, Wasm's only one); crypto off `javax` |
| `udea-agent` | all four | `ServiceLoader` replaced by the registry |
| `udea-agent-host` | JVM | Unchanged transport; its LibGDX overlay moves to `udea-render` |
| `udea-render` | JVM, Android, Wasm | Rewritten on Kool (section 4) |
| `:moba:game` | all four | The game: components, systems, assets |
| `:moba:desktop` | JVM | Server, client and agent launchers; shot tasks |
| `:moba:web` | Wasm | Browser client; `runWebShot` |
| `:moba:android` | Android | Phone client |
| `common`, `gradle-plugin`, `example`, `example:assets` | — | **Deleted** |

"All four" means `jvm`, `androidTarget`, `wasmJs`, `iosArm64` + `iosSimulatorArm64`.

Rules that change:

- "No GL outside `udea-render`" becomes **"No Kool and no ComposeGL backend outside `udea-render`"**,
  enforced by the module-graph verifier.
- No JVM-only API in a common source set - the Kotlin compiler enforces this by construction.
- The legacy-tree gates (`udeaVerifyNoLegacyDependencies`, `udeaVerifyMigration`,
  `udeaLegacyReport`) and `docs/migration/ledger.md` are deleted with the old tree.

## 4. Rendering on Kool

Seams that stay: `RenderSystem` / `OverlaySystem` (presentation is never a Fleks system), the
interpolation alpha, `IntentSource`, and `RenderMode` (`Headless`, `Offscreen`, `Windowed`) with
`/health` reporting it.

| Area in `udea-render` | Today (LibGDX) | On Kool |
|---|---|---|
| backend | LWJGL3 application | A Kool context per platform; `GameHost` owns one Kool scene and advances the simulation from Kool's frame callback |
| draw | `SpriteBatch`, `TextureRegion`, `BitmapFont` | `SpriteBatch2D`: a thin layer over Kool - one instanced quad mesh per atlas, instance = position, size, UV rect, tint, depth - drawn with `KslUnlitShader`. Kool draws 2D as meshes on a plane and has no sprite API of its own |
| camera | `OrthographicCamera`, viewports | Kool orthographic camera with Udea's fit rules |
| capture | `FrameBuffer` + `Pixmap` | `OffscreenPass2d` + read-back, PNG encoded by a common-code encoder |
| input | `InputProcessor`, multiplexer | Kool pointer/key events to intents; UI gets first refusal, as `UiInputOrderTest` asserts today |
| ui | `composegl-gdx` host | `composegl-kool`: `KoolGl` binding, Kool glyphs, Kool input, GL state handed back each frame (the KorGE backend's pattern) |
| agent overlay | LibGDX in `udea-agent-host` | An `OverlaySystem` in `udea-render`; draws only to the screen target, never into a captured frame |

**Audio.** The `AudioDevice` SPI stays. One Kool-backed implementation (`AudioClip`/`AudioOutput`
exist on desktop, web and Android). `AudioDevice.Silent` still serves `Headless`.

**Assets.** `.udeapak` and the asset compiler are unchanged. Texture loading moves to Kool
textures read through kotlinx-io, so the web build fetches the pak over HTTP.

**Parity.** Before LibGDX is deleted, the current `runMatchShot`, `runLaneShot` and `runShot` PNGs
are captured as references. Kool shots are compared by a person for every scene, and by a
pixel-difference threshold for scenes without text. Exact equality is not expected.

**Riskiest unknown.** Whether Kool's `Offscreen` mode runs under xvfb with software GL on this box.
Ticket 1 is a spike that answers it before anything is built on it.

## 5. The generated registry (frozen contract change)

- KSP runs on each module's common source set and emits a `ModuleRegistry`: its agent tools,
  replicators and level sections.
- Each launcher gets one generated `UdeaRegistry` that lists module registries in sorted-FQN
  order and is passed to the game at start.
- Id assignment is unchanged: one generator, sorted FQNs, `net-protocol.lock`, `u16 protoHash` in
  packet byte 0. Only discovery changes - a missing registration is now a compile error.
- Edited: `docs/contracts/agent-tools.md`, the "Id assignment" row in `AGENTS.md`;
  `docs/contracts.lock` rewritten with `udeaWriteContractLock` in the same change.

## 6. Simulation, data, threads, networking

**Determinism (D3).** The JVM server is the only bit-exact simulation. The cross-OS
`replay-equality` gate stays JVM-only. Any platform may record a `.udearep`; verification runs on
the JVM. The simulation rules do not loosen: no wall clock, no unseeded randomness, `Tick`
everywhere.

**Threads.** Wasm has one thread, so every platform uses one model: simulation step, barrier
drain and rendering on one thread, driven by Kool's frame callback on clients and a fixed-rate
loop on the server. Network I/O runs on coroutines and delivers into a queue drained at the top of
`step()`, beside tool calls. `java.util.concurrent` is replaced by coroutines, and by `atomicfu`
where state is genuinely shared.

**Files.** kotlinx-io everywhere; on Wasm, a "file" is an HTTP fetch or an in-memory buffer.
`.udeapak`, `.udealevel` and `.udearep` stay byte-compatible. `java.util.zip` is replaced by a
multiplatform library or removed where compression earns little - decided in that ticket.

**Networking.** `udea-net` keeps its transport interface with two implementations:
`UdpTransport` (Ktor network sockets: JVM, Android, iOS) and `WebSocketTransport` (Ktor client on
every client platform; Ktor server on the desktop server). Snapshot and input rates, the protocol
hash and `NetId` are unchanged. Packet crypto moves to `cryptography-kotlin`.

**Agent surface.** `/health`, `/state`, `/command`, `/tools` and `gamebridge.json` keep their
shape.

## 7. Build, testing, process

**Branching.** `kmp` is the integration branch, cut from `master`. Ticket branches merge into
`kmp` after a fresh reviewer's PASS. `kmp` may be red while the port is in flight; each ticket
names the Gradle tasks it turned green and the reviewer runs exactly those. `kmp` merges into
`master` when `sh gradlew build` is fully green. The `example` branch is retired (distinct from the `example` module, deleted in ticket 9).

**Tests per target.**

| Target | Tasks | Runs on |
|---|---|---|
| JVM | `jvmTest`; GL and agent GL tests under xvfb with `-Pudea.render.requireGl=true` | this box, CI |
| Wasm | `wasmJsNodeTest` for logic; `wasmJsBrowserTest` (headless Chrome) for rendering | this box, CI |
| Android | `testDebugUnitTest`; an emulator smoke run later | this box, CI |
| iOS | `iosSimulatorArm64Test` | **macOS CI runner only** - Kotlin/Native cannot build iOS on Linux |

**Evidence commands after the port.** `:moba:desktop:runMatchShot`, `runLaneShot`, `runShot` keep
their PNG output; `:moba:web:runWebShot` screenshots the Wasm build in headless Chrome.

**Docs rewritten in the change that makes them true.** `AGENTS.md` (module table, targets, the
Kool rule), `docs/engineering-standards.md` (GL rule, one-thread rule), `docs/module-graph.md`,
the two contract edits. `udeaVerifyAgentsMd` still fails a stale module table.

## 8. Backlog

- Close every open issue except #185, #188, #189, each with one line: superseded by this spec.
- Comment #188: its composables now target `composegl-kool`; the drawing half is not wasted.
- File one epic and the tickets below.

## 9. Ticket order

1. **Spike:** Kool `Offscreen` under xvfb + software GL; `OffscreenPass2d` read-back to PNG. Throwaway code; the output is a yes/no and what it took.
2. **Build logic:** a KMP convention plugin for runtime modules; build-time modules untouched.
3. **Generated registry** and the contract change.
4. **Leaf modules:** annotations, diagnostics, core, gas, assets, replay, audio, agent - one ticket each, parallel where modules are disjoint.
5. **`udea-net` transports.**
6. **`composegl-kool`**, in the ComposeGL repository.
7. **`udea-render` on Kool:** draw, camera, capture, input, UI host, overlay.
8. **`moba` split** into nested projects; reference shots captured first, parity compared after.
9. **Delete the old tree**, LibGDX and the legacy gates; give the character art a source outside `example`.
10. **Docs**, fully green build, merge `kmp` into `master`.

4-6 run in parallel. 7 and 8 need 1, 2, 3 and 6.

## 10. Out of scope

Kool on iOS; Vulkan/WebGPU rendering; WebRTC/WebTransport; remote agent control of web or mobile
builds; physics; bit-exact cross-platform simulation. Each becomes its own issue when needed.
