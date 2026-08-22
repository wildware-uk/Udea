# Udea — AI-Native Rewrite Design

**Date:** 2026-08-22
**Status:** Approved for planning
**Repo:** `wildware-uk/Udea`, new module tree in place, old modules deleted as replaced.

---

## 1. What we are building

Udea is a Kotlin/LibGDX/Fleks game engine built so that AI agents can do most of the
work of making a game with it.

Every Udea game exposes an MCP tool surface automatically. An agent can query the
world, inspect any entity field, spawn blueprints, activate abilities, synthesise
input, take screenshots, pause, single-step, rewind sixty seconds and fast-forward —
without the game shipping a single line of debug code. There is no level editor and
no IDE plugin: **the tool surface is the editor.**

Three decisions make that real rather than aspirational.

1. The simulation runs on a **fixed 60Hz tick** with a seeded RNG service and a
   build-time verifier that fails the build if simulation code reads a wall clock or
   an unseeded random. Time travel works today; bit-exact replay is a retrofit rather
   than an archaeology project.
2. Assets are authored in `.udea.kts` but **compiled, resolved and validated at build
   time**. A bad `reference("...")` is a compile error with a file, a line and a
   did-you-mean — not a crash forty seconds into a match.
3. **One generated `Replicator<T>` per component** serves delta replication, snapshot
   capture, snapshot restore and the agent's field access. The netcode, the rewind
   buffer and the inspector cannot disagree about what an entity is.

The proving ground is a playable 5v5 three-lane MOBA, built alongside the engine.

---

## 2. Locked decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Same repo, new `udea-*` module tree; old modules deleted as replaced | Keeps history and docs; delete-as-you-go prevents two half-engines |
| D2 | Kotlin + LibGDX + Fleks retained; package root `dev.wildware.udea` | No reason to churn the stack |
| D3 | Snapshot/restore now; determinism as Phase 7 | Time travel ships early; loop built retrofittable |
| D4 | `.udea.kts` kept as authoring format, compiled at build time | Keeps the DSL; kills the runtime script host |
| D5 | Dirty-tracked replication + client prediction, Sandbox-style | What a MOBA actually needs |
| D6 | IDEA plugin, level editor and `compose-ui` dropped | The MCP surface is the editor |
| D7 | Example game is a 5v5 three-lane MOBA, arena → lane → map | Forcing function for every engine feature |
| D8 | **KSP2 and a K2 compiler plugin, both from Phase 0** | User call. They compose: KSP2 emits files, K2 adds diagnostics and synthesised members. See §3.2 |
| D9 | Full MOBA item system with actives and uniques | Heavily exercises GAS |
| D10 | Public-internet hardening built in Phase 4 | Cheaper than retrofitting a live wire format |
| D11 | Existing orc/soldier/priest sprites reused as placeholder art | Atlas packer and animation tooling stay minimal until Phase 5 |

---

## 3. The decisions that carry the design

### 3.1 One codec, five consumers

The five subsystem analyses proposed four different serialization mechanisms. That
would have been four things that can drift apart.

**Settled: exactly one generated artefact per component, `Replicator<T>`.** It serves
network delta write, network full write, snapshot capture, snapshot restore, and the
agent's field read/write. No `@Snapshotted`. No separate `SnapshotCodec`. No
reflective fallback.

This is not tidiness. It resolves three problems at once:

- The snapshot ring **is** the replication baseline store, so time travel and delta
  encoding are one mechanism rather than two.
- `describe_entity` and `set_component_field` are free consequences of
  `Replicator.getField/setField` — the MCP surface needs no reflection and survives R8.
- `desync_report(tick)` is a field-by-field `FieldStore` comparison, not a byte diff.

Not everything snapshotted should be replicated: jungle respawn timers and bot
blackboards must rewind but must never reach a client. **One annotation family, two
masks.** `@Net` = replicated and snapshotted; `@Sim` = snapshotted only. Both land in
the same `FieldStore`. `writeDelta` considers only `NET_MASK`; capture uses `ALL_MASK`.

```kotlin
@Replicated
data class Transform(
    @Net var position: Vector2 = Vector2(),
    @Net @Q(bits = 12) var rotation: Float = 0f,
    @Sim var lastGroundedTick: Tick = Tick(0),
) : Component<Transform> {
    override fun type() = Transform          // deleted later — see §3.2, gated
    companion object : ComponentType<Transform>()
}
```

`Replicator<T>` is consumed by four modules. **It is frozen in Phase 0**, behind
golden-file and round-trip tests, before a single game component is annotated.

### 3.2 Codegen: KSP2 and K2 compose

**The dirty bits are not what the compiler plugin is for.** The networking analysis
established a fact that constrains any codegen choice:

> `Transform.position` is a mutable `Vector2` mutated in place by `position.set(...)`
> and by Box2D's write-back. **No setter ever fires for the field that matters most.**
> Dirty-on-assign would silently under-replicate position.

So replication is **capture-and-diff** against the previous tick, regardless of which
tool generates the code. The `by net(...)` delegate fallback is deleted; `@Net var
health = 100f` is a plain property that a generated `capture()` reads.

That leaves a clean division of labour:

| Concern | Tool | Why |
|---|---|---|
| `Replicator<T>` emission | **KSP2** + KotlinPoet | Real files on disk: golden-file testable, diffable, steppable in a debugger |
| Cross-module `NetModule` / `ToolModule` registries | **KSP2** | ServiceLoader resources; no magic package |
| `@AgentTool` manifests + JSON Schema | **KSP2** | Same |
| `@Net` on a `val`, >64 fields, `@Q` on non-float | **K2 FIR checker** | Diagnostic at the exact symbol, not a task-boundary error |
| `reference("typo")` inside `.kt` | **K2 FIR checker** | The only way to get this in-editor rather than at `udeaValidateAssets` |
| KDoc → generated DSL members (Trello #12) | **K2** | KSP cannot read or re-emit KDoc |
| Delete `override fun type()` (Fleks boilerplate) | **K2 FIR declaration synthesis** | **Gated — see below** |

**The gate.** FIR *checkers* only add diagnostics; if the IDE does not load the plugin
the worst case is that a warning is invisible and the build still fails. They cannot
produce false red squiggles, so they ship in Phase 0 unconditionally.

FIR *declaration synthesis* is different: if IntelliJ does not resolve a synthesised
`type()`, every component in the project shows "abstract member not implemented" in
red. Since D6 drops the IDEA plugin, there is no companion plugin to fix that. So
declaration synthesis is preceded by a **timeboxed spike** that verifies real IDE
behaviour with the plugin applied via Gradle. If the IDE resolves it, we adopt it and
delete the boilerplate. If not, the boilerplate stays and only the checkers ship.
This is scoped as its own issue with an explicit go/no-go.

**Version policy.** A K2 plugin is the most Kotlin-version-fragile component in the
project. It is pinned to the exact project Kotlin version, it has its own
`kotlin-compile-testing` suite that must pass before any Kotlin upgrade merges, and
CI runs a build with the plugin **disabled** to prove the engine still compiles
without it. That last check is what keeps the plugin from becoming load-bearing: if a
Kotlin release breaks it, the project drops to checkers-off and keeps moving.

Bit indices come from one place — sorted FQNs in `udea-codegen` — written to a
checked-in `net-protocol.lock` that CI diffs, and hashed into a `u16 protoHash` in
byte 0 of every packet.

### 3.3 The tick loop

60Hz fixed simulation. 20Hz snapshots (every 3rd tick). 30Hz input send. Render
decoupled with an interpolation alpha.

Presentation systems are **not Fleks systems** — they implement `RenderSystem` and
live in `udea-render`, so `world.update(dt)` is pure simulation *by construction*,
not by convention.

Three analyses independently invented an "apply between ticks" queue: core for scene
swaps, assets for hot-reload `GraphDelta`, MCP for mutating tool calls. **Unified as
`SimBarrier`** — one queue drained at the top of `Simulation.step()` before any system
runs. Net snapshot application joins it. No system ever observes a torn world, and
there is one place to reason about atomicity rather than three.

### 3.4 Box2D is demoted

`CharacterMover` — a capsule sweep against static collision geometry, allocation-free,
replayable 60× per frame — is the authoritative movement model for every predicted or
replicated entity, run identically on server and client.

Box2D survives behind a `PhysicsWorld` interface for sensor queries, debris and
server-only projectiles, and **is never snapshot state**. The warm-start-impulse
fidelity problem and the "plausible but not bit-identical" caveat evaporate, because
nothing that matters lives in the solver. After restore, bodies are rebuilt from
components in one deterministic pass.

### 3.5 "Headless" meant two incompatible things

Core said headless means no `RenderSystem` and metadata-only textures. MCP said
headless screenshots use an offscreen FrameBuffer. You cannot render to an FBO
without a GL context.

**Three explicit `RenderMode`s**, all running the identical `Simulation`, differing
only in whether a `Presentation` exists:

| Mode | GL context | Window | Screenshots | Used by |
|---|---|---|---|---|
| `Headless` | none | none | typed `no_render_context` error | dedicated server, CI, `SimHarness`, fast-forward |
| `Offscreen` | real LWJGL3 | hidden | full | `moba.agent` default |
| `Windowed` | real LWJGL3 | visible | full | the player |

`/health` reports the mode, so an agent knows which toolsets are live before calling one.

### 3.6 Assets: the build is the compiler, the daemon is the same compiler

The runtime `BasicJvmScriptingHost` dies.

1. **Pass 1** — syntactic PSI scan, no classpath, no resolution. Gives every
   declaration and every `reference("...")` literal a precise span.
2. **Pass 2** — compile and evaluate in a `processIsolation` worker.
3. **Pass 3** — validate the graph.
4. **Pass 4** — deterministic atlas pack and `.udeapak` write.
5. **Pass 5** — generate typed `GameAssets` accessors.

**Scripts never consume generated accessors.** They use validated `reference("id")`
strings; only `.kt` uses `GameAssets.character.orcElite`. Same validation, but no
asset rename invalidating the script compile classpath and recompiling every script —
which would blow the 3s asset-edit budget on day one.

`udea-assets-compiler` holds **zero Gradle types** and is the only implementation, so
the dev daemon cannot diverge from CI. A conformance test asserts byte-identical
`diagnostics.json` from both paths.

**Hot reload vs rewind:** assets never enter a snapshot — only pack-time-stable
`AssetIndex` ints do. So rewinding across a reload restores state referencing the
*new* data, which is exactly the tuning loop an agent wants. `rewind()` succeeds and
returns `assetGraphChangedSince: true` with the changed ids. It refuses only for
shape-changing deltas, which the daemon already classifies as
`RELOAD_REQUIRES_RESTART`.

---

## 4. Module tree

| Module | Purpose | Replaces |
|---|---|---|
| `udea-annotations` | Zero-dependency leaf: `@Net`, `@Sim`, `@Q`, `@Replicated`, `@AgentTool`, `@Arg` | Two conflicting `UdeaNetworked` declarations on one classpath |
| `udea-codegen` | The KSP2 processor + KotlinPoet emitters; owns id assignment | `NetworkGenerator`, `UdeaDslProcessor`, `@CreateDsl` |
| `udea-compiler-plugin` | The K2 FIR/IR plugin: checkers, KDoc propagation, gated declaration synthesis | new (D8) |
| `udea-core` | Headless kernel. No GL on the compile classpath | `UdeaGameManager`/`GameScreen`, the globals, `properties.kt`, `reflection.kt` |
| `udea-assets` | Runtime asset model + `.udeapak` reader | `common/assets/*`, the `Assets` global |
| `udea-assets-compiler` | Build-time only, zero Gradle types | `scriptHost.kt`, `AssetScanner`, `GameAssetLoader` |
| `udea-gas` | Abilities, attributes, effects — tick-denominated | `common/ability/*`, `AbilitySystem`, `AttributeSystem` |
| `udea-net` | Transports, baselines, relevancy, prediction, RPC | `common/network/*`, both `Network*System`s, KryoNet |
| `udea-render` | The only module that touches GL | `SpriteBatchSystem` et al., `GameScreen`'s rendering half |
| `udea-agent` | MCP surface + test harness — same code path | FruitGameKTX's `DebugBridge` pattern, generalised |
| `udea-agent-host` | HTTP server, debug-only, verified absent from release | `level-editor`, `idea-plugin`, `compose-ui` |
| `udea-gradle` | Tasks, verifiers, `gamebridge.json` emission | old `gradle-plugin` (which leaked `gradleApi` onto the game runtime) |
| `moba` | The example game | `example` |

Dependency rule enforced by a Gradle check from Phase 0: **no `udea-*` or `moba`
project may have `common` on its compile classpath.** Anything needed is copied
forward deliberately, file by file, with the copy reviewed.

---

## 5. Cross-cutting contracts

Contracts two or more modules must agree on. Each is frozen early because a late
change breaks several modules at once.

| Contract | Resolution |
|---|---|
| **Serialization** | One `Replicator<T>`, two masks (`@Net`/`@Sim`), frozen Phase 0 behind golden tests |
| **Dirty determination** | Capture-and-diff, never setter instrumentation — in-place `Vector2` mutation defeats setters |
| **Id assignment** | One generator, sorted FQNs, checked-in `net-protocol.lock`, `u16 protoHash` in packet byte 0; ServiceLoader discovery, no magic package |
| **Between-tick mutation** | One `SimBarrier` drained at top of `step()`; scene swaps, asset deltas, agent mutations, snapshot application all use it |
| **Entity identity** | `NetId` (dense `u16` + `u8` generation), never Fleks `Entity`, across snapshots, packets and tool calls; `IntArray` for O(1) resolution |
| **Time** | `Tick` is universal — GAS durations, ring slots, baselines, input stamps, `step(n)`. `SimClock.time` is derived, never accumulated. Seconds exist only in `udea-render` and audio |
| **Authority vocabulary** | One family on `@Net`: `authority = Server \| OwnerPredicted \| OwnerWritable`, `lifetime = OnCreate \| Always`, `visibility = All \| OwnerOnly`, `agentWritable = false` by default. RPC guards, mask stripping, GAS prediction eligibility and `set_component_field` all read the same declarations |
| **Diagnostics** | One `UdeaDiagnostic` — severity, stable rule id, message, `SourceSpan` (repo-relative, never absolute), `assetId`, optional `Fix`. Capped at 25, ranked root-cause-first, mandatory Levenshtein did-you-mean. The K2 checkers emit the *same* rule ids as the asset validator |
| **Randomness** | `RngService` with named streams (`Combat`, `Loot`, `AI`, `Spawn`, `Wave`), xoshiro256** with explicit state in the snapshot. Presentation gets a separately typed, wall-seeded `PresentationRandom` in a module simulation cannot see |

---

## 6. Delivery phases

Ordered so that **stopping early still leaves a working artefact.**

### Phase 0 — Kernel, generator and compiler plugin you can trust
Prove `Replicator<T>` + the columnar snapshot ring works end to end, headless, no
globals. Stand up both codegen tools.

*Demo:* 200 entities, 600 fixed ticks in <50ms, snapshot, restore, re-run — identical
hash stream. Zero GL, zero globals, zero steady-state allocation. `@Net val` produces
a red error at the symbol in the IDE.

*Exit:* Replicator generated for ≥3 components with golden tests · snapshot-equivalence
green · capture of 1000 entities <1ms and allocation-free, gated in CI ·
`net-protocol.lock` deterministic across module build orders · K2 checkers have a
`kotlin-compile-testing` suite · **CI builds green with the K2 plugin disabled** ·
clean build <90s · no new module has `common` on its classpath (Gradle rule, not
convention)

### Phase 1 — The agent can see, drive and rewind
**Deliberately out of dependency order.** Every later phase goes faster once the agent
can observe and manipulate the world unattended.

*Demo:* With no human input, an agent spawns 20 blueprints, steps 200 ticks,
screenshots, rewinds 100, screenshots again, diffs the images, then inspects the entity
whose health changed — through the **unmodified** `game-bridge-mcp`.

*Exit:* the unmodified bridge lists/describes/calls every generated tool · bridge's TS
client vendored into CI asserting `/health` identity, `completedCommandId` **and** its
`frames-advanced` degradation, `close`/`waitForSilence` · digest <0.3ms at 500 entities
· `udeaVerifyRelease` proves no agent class in the release jar · a throwing tool lands
as `ok:false` without stalling the loop

### Phase 2 — Assets compile at build time
*Demo:* An agent patches a fireball's damage; the running game reflects it in <1s
without restart. A typo'd reference is rejected in <300ms with file, line, column and
did-you-mean, and the running game keeps its last-good graph. Two clean builds produce
byte-identical `.udeapak`. In-editor, `reference("charater/orc")` is red before the
build runs.

*Exit:* zero `kotlin-scripting-*` and zero `kotlin-reflect` on the shipped classpath ·
warm validate <300ms, asset edit-to-observe <3s · Gradle and daemon paths produce
byte-identical `diagnostics.json` · the K2 `reference()` checker and the asset
validator emit identical rule ids for the same defect · the transpile-to-plain-`.kt`
escape hatch prototype exists **now, not after a crisis** · process start to first
frame <800ms

### Phase 3 — Arena: GAS, and two clients in one JVM
*Demo:* `net.spawn_session(clients=2)` stands up a server and two clients in one JVM.
Both champions move, cast, damage and die under 150ms latency and 5% loss.
`net.desync_report(tick)` is clean. No sockets, no threads, no sleeps.

*Exit:* golden byte-stream protocol fixtures with `--update-goldens` · round-trip
fuzzer green under random loss and reorder · server and client `CharacterMover`
bit-identical for a scripted input sequence · ring <64MB and per-tick bytes in budget,
gated · KryoNet removed and old `Network*System` files deleted

### Phase 4 — Prediction, fog, and real UDP
Includes public-internet hardening (D10): connection-token handshake,
anti-amplification, rate limiting.

*Demo:* Two OS processes over real UDP on one lane. Local champion responds instantly;
remotes interpolate smoothly. Walking a unit along a fog boundary produces zero
Leave/Create oscillation. `net.assert_not_visible(client, netId)` passes as a
checked-in **anti-cheat** regression — the server never serialised the field, so a
sniffer sees nothing.

*Exit:* reconciliation test at 150ms/5% loss asserting convergence, correction count
and max magnitude · fog-boundary walk asserting zero oscillation · sequence wraparound
over 200k packets; bounded fragment reassembly with timeout · 10-client/20%-loss/5-minute
seeded soak green · fog solve cost instrumented and in budget

### Phase 5 — One lane, played by bots
Creeps, towers, pathfinding, minimap, respawn, gold and XP, plus the item system (D9)
and bots good enough to play unattended.

*Demo:* A bot-vs-bot lane runs unattended for ten minutes driven by an agent script,
reporting gold, XP, tower state, creep-wave timing and a screenshot a minute. No human
touches the keyboard.

*Exit:* ten-minute unattended lane, no crash, no leak, no desync · frame budget met at
200 entities · every `moba` tool has a description that reads correctly in `/tools` as
a model would see it

### Phase 6 — Full map, and the old tree deleted
*Demo:* A full 5v5 — two humans, eight bots — from spawn to nexus kill, with fog, three
lanes, jungle and items, over real UDP across two machines.

*Exit:* `settings.gradle.kts` contains only the new modules · no `dev.wildware.udea`
class outside the new layout remains · bandwidth per client in budget · release
artifact verified free of agent classes, as a gate on `assemble`

### Phase 7 — Determinism epic
Cash in the retrofit: fixed timestep, seeded streams, no wall clock in sim, no Box2D
in authoritative state, input as the only client→server state.

*Demo:* A recorded ten-minute 5v5 replays bit-identically on Windows **and** Linux. An
agent bisects a reported bug by replaying to tick N, rewinding, and single-stepping
through the divergence.

*Exit:* replay equality on ≥2 OS/JVM combinations · divergence reported as the first
differing tick **and field**, not a failing hash · the allowlist is a reviewed
artefact, not a dumping ground

---

## 7. Top risks

| Risk | Impact | Mitigation |
|---|---|---|
| **Scope.** Eight phases plus a 5v5 MOBA, one person. Most likely failure is running out of will around Phase 5 | Total — an abandoned rewrite is worse than the current code, because the repo carries both | Every phase ends in something independently valuable. Phase 1 alone gives an agent-drivable sandbox; Phase 3 networked co-op; Phase 4 a real multiplayer engine. The MOBA is a pull-track, never a blocker. **Say out loud at each phase boundary whether to continue** |
| **The K2 plugin breaks on a Kotlin release** and blocks every phase behind it (D8) | High — it is on the critical path by construction | Pinned to the exact project Kotlin version; own `kotlin-compile-testing` suite gating any upgrade; **CI proves the build is green with the plugin disabled**, so a breakage degrades to checkers-off rather than stopping work. Declaration synthesis is gated behind an IDE-behaviour spike with an explicit go/no-go |
| **`Replicator<T>` couples four modules.** They break together if the interface moves (e.g. >64 fields forcing `Long` → `LongArray`) | High | Freeze in Phase 0 behind golden tests before any game component is annotated. Design both known extension points in from the start: the `@Net`/`@Sim` mask split, and a mask type only ever passed through the API and never stored in game code, so `LongArray` is non-breaking. KSP error at 64 fields directs to splitting the component — better ECS design anyway |
| **`kotlin-compiler-embeddable` in a Gradle worker** — version coupling, `--add-opens`, large heap, slow cold path. Phase 2 gates Phases 3+ | High — assets are on the critical path for every gameplay phase | Pin to the exact project Kotlin version, gate upgrades on the asset suite. `processIsolation` worker with fixed heap so a compiler OOM cannot kill the daemon. **Build the transpile-to-`.kt` escape hatch during the work item, not after a crisis** — a `.udea.kts` maps ~1:1 onto `fun build(scope: AssetScope)`, reusing every downstream stage |
| **Two module trees coexist for six phases.** If any new module transitively depends on `common`, the globals leak back and headless silently breaks | High and insidious — the symptom appears far from the cause | Gradle rule landed in Phase 0, before the second module exists. Old modules deleted as replaced, never left "for reference" |
| **The agent surface drifts from the game-bridge-mcp contract** and nobody notices until the bridge misbehaves mid-session | High — a broken bridge silently halves throughput | Vendor the bridge's TS client into CI; assert real behaviours against a headless instance on every commit touching `udea-agent`. Both new fields (`commandResults`, `/artifact`) are additive and invisible to today's bridge, so conformance is a genuine gate |
| **Fog-of-war shadowcasting at 10Hz** becomes the dominant server cost; expand-only double-buffering done wrong causes relevancy flicker that presents as a network bug | Medium-high — lands in Phase 4 on the critical path, with no prior art here | Compute vision **per team**, not per client (5× saving for 5v5). Budget and instrument the solve from the first commit. Ship `net.relevancy(client)` returning the granting vision source so flicker is diagnosable in one tool call. Exit criteria include the fog-boundary oscillation test and the Leave-vs-Destroy distinction |
| **The determinism ASM scanner produces false confidence** — it catches direct calls but not nondeterminism laundered through Fleks internals, LibGDX math, or cross-JVM float differences, and its green light will be trusted | Medium — only bites in Phase 7, by which point violations may have accumulated behind a passing check | Treat the scanner as a cheap first filter and the **`WorldHasher` snapshot-equivalence test as the actual gate from Phase 0** — it catches nondeterminism regardless of source, long before input replay exists. Run on every CI platform to catch float divergence early. Audit the used Fleks/LibGDX surface once, manually, into the checked-in allowlist with reasoning |
| **Snapshot ring cost at MOBA scale** — one structure carries time travel, replication baselines and rollback. If capture allocates, three features degrade at once | Medium-high | Hard CI gate, not aspiration: capture <1ms at 1000 entities and allocation-free, ring <64MB. Columnar `FieldStore` with pooled buffers, no per-client shadow copies (per-client state is `lastAckedTick` + an `IntArray`). Two cadences from one ring: 2s dense for rollback, 60s sparse for agent rewind. If the budget is missed, degrade the sparse cadence — rewind precision is preserved by stepping forward from the keyframe |

---

## 8. Open questions

Not blocking; each has a working assumption recorded.

1. **Desktop JVM only, forever?** Assumed yes (LWJGL3, Box2D natives, JDK HttpServer).
   Android or GraalVM native-image would change the offscreen-render story and whether
   `kotlin-compiler-embeddable` can be tolerated near the runtime. Trello #31 wants
   multiplatform; it is not planned in Phases 0–7.
2. **Is the engine meant for a second game, or MOBA-first-and-only?** Module boundaries
   are drawn assuming a second game is plausible, which costs a little ceremony.
3. **Is a 60-second rewind window enough?** Longer is cheap once Phase 7's input replay
   lands, expensive before it. If long rewind is wanted early, Phase 7 moves earlier.
4. **One `moba` module or split client/server/shared?** Assumed one until it hurts;
   `RenderMode` and the module-arrow rule already prevent the worst mistakes.
5. **Kotlin version policy.** Both `udea-compiler-plugin` and `udea-assets-compiler`
   pin to the exact project Kotlin version. Assumed: lag Kotlin releases behind a
   passing suite rather than tracking latest.

---

## 9. Carried-forward Trello work

The Trello board (`3JqieuNR`) is the pre-existing backlog. Its cards map as follows.

**Absorbed into the rewrite:** #5 Eager and Dirty sync · #6 Tick count sync · #8
Server-only architecture with local queue · #12 Copy KDoc to DSL (now Phase 0, enabled
by D8) · #13 Precompiled assets (answered: Gradle) · #16 Custom network packets → typed
RPC · #24 Consolidate Asset vs AssetReference · #26 Separate network connection from
level/game screen · #32 Asset refs resolve to integers · #33 K2 compiler plugin (now
Phase 0) · #34 Cache remote entities · #35 Custom user attributes · #28 Example game →
the MOBA.

**Obsoleted by D6** (editor dropped): #9 Animation sequencer · #10 Binary level format
· #11 Level editor.

**Deferred, not planned:** #14 Mod support · #15 Script sandboxing · #31 Multiplatform.

**Still wanted, scheduled by phase:** #18 Axis controller (P3) · #19 Documentation wiki
(P6) · #20 Ability cooldowns (P3) · #21 Ability assets (P3) · #22 Loading screen (P5) ·
#27 In-game UI (P5) · #29 Tilesheets (P5).
